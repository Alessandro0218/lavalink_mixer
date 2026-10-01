package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Serializable
data class AnnounceRequest(
    /** Snowflake as a string: JSON numbers lose precision past 2^53. */
    val guildId: String,
    /** Re-resolved via source managers. */
    val identifier: String? = null,
    /** Exact Lavalink base64 track, preferred (no re-resolution). One of the two is required. */
    val encodedTrack: String? = null,
    /** Music gain while the clip plays, 0..1. Defaults to `mixer.duckLevel`. */
    val duckLevel: Float? = null,
)

@Serializable
data class AnnounceCancelRequest(val guildId: String)

@Serializable
data class TrackInfo(
    val identifier: String,
    val title: String,
    val author: String,
    val duration: Long,
    val uri: String?,
    val isStream: Boolean,
    val isSeekable: Boolean,
    val source: String,
)

@Serializable
data class AnnounceResponse(
    /** Poll `/mixer/state` until `lastOverlay.id` reaches this to know the clip is over. */
    val overlayId: Long,
    val track: TrackInfo,
)

@Serializable
data class OverlayResultInfo(val id: Long, val reason: String)

@Serializable
data class MixerState(
    /** Echoes the request guild id verbatim (string snowflake). */
    val guildId: String,
    val overlayActive: Boolean,
    /** Id of the running overlay, or of the last one started. */
    val overlayId: Long,
    /** How the last finished overlay ended; null until one has. */
    val lastOverlay: OverlayResultInfo? = null,
    /** The mixer filter processed audio in the last moments, i.e. it is in the running track's chain. */
    val filterLive: Boolean,
    val duckLevel: Float,
    val duckFadeMs: Long,
)

/**
 * Control plane for the mixer plugin, under `/mixer`. Audio only flows once
 * the guild's filters carry `"pluginFilters": { "mixer": { "guildId": "<id>" } }`,
 * sent before the music track starts.
 *
 * Errors: 409 with message `overlay_busy`, `mixer_unavailable:main_idle` or
 * `mixer_unavailable:filter_inactive`; 400 for a bad request or an
 * unloadable clip; 504 when the clip did not load in time (nothing started).
 */
@Service
@RestController
class MixerRestHandler(private val plugin: MixerPlugin) {

    @PostMapping("/mixer/announce")
    fun announce(@RequestBody body: AnnounceRequest): ResponseEntity<AnnounceResponse> {
        if (body.identifier.isNullOrBlank() && body.encodedTrack.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide identifier or encodedTrack")
        }
        body.duckLevel?.let {
            if (it !in 0f..1f) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "duckLevel must be within 0..1")
        }
        val guildId = gid(body.guildId)
        val future = try {
            plugin.announce(guildId, body.identifier, body.encodedTrack, body.duckLevel)
        } catch (e: MixerBusyException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "overlay_busy", e)
        } catch (e: MixerUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "mixer_unavailable:${e.code}", e)
        }
        val announced = awaitFuture(future, body.identifier ?: "encoded track")
        return ResponseEntity.ok(AnnounceResponse(announced.overlayId, announced.track.toInfo()))
    }

    @PostMapping("/mixer/announce/cancel")
    fun cancelAnnounce(@RequestBody body: AnnounceCancelRequest): ResponseEntity<Unit> {
        val guildId = gid(body.guildId)
        val mixer = plugin.find(guildId) ?: throw notFound(guildId)
        return if (mixer.cancelOverlay()) ResponseEntity.noContent().build()
        else throw ResponseStatusException(HttpStatus.NOT_FOUND, "No active overlay on guild $guildId")
    }

    @GetMapping("/mixer/state")
    fun state(@RequestParam guildId: String): ResponseEntity<MixerState> {
        val mixer = plugin.find(gid(guildId)) ?: throw notFound(gid(guildId))
        return ResponseEntity.ok(
            MixerState(
                guildId = guildId,
                overlayActive = mixer.isOverlayActive(),
                overlayId = mixer.currentOverlayId(),
                lastOverlay = mixer.lastOverlayResult()?.let { OverlayResultInfo(it.id, it.reason) },
                filterLive = mixer.filterLive(),
                duckLevel = mixer.duckLevel,
                duckFadeMs = mixer.duckFadeMs,
            )
        )
    }

    private fun awaitFuture(future: CompletableFuture<Announced>, identifier: String): Announced {
        return try {
            future.get(LOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            // Cancelling the dependent future keeps a late load from starting the overlay behind our back.
            future.cancel(false)
            throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Timed out loading $identifier", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is MixerBusyException) throw ResponseStatusException(HttpStatus.CONFLICT, "overlay_busy", cause)
            if (cause is NoMatchException) throw ResponseStatusException(HttpStatus.BAD_REQUEST, cause.message, cause)
            log.warn("overlay load failed", cause ?: e)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Load failed: ${cause?.message}", cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted", e)
        }
    }

    private fun AudioTrack.toInfo(): TrackInfo {
        val info = this.info
        return TrackInfo(
            identifier = info.identifier,
            title = info.title,
            author = info.author,
            duration = info.length,
            uri = info.uri,
            isStream = info.isStream,
            isSeekable = this.isSeekable,
            source = try {
                this.sourceManager.javaClass.simpleName
            } catch (e: Exception) {
                "unknown"
            },
        )
    }

    private fun notFound(guildId: Long) =
        ResponseStatusException(HttpStatus.NOT_FOUND, "No mixer state for guild $guildId (player never seen?)")

    private fun gid(raw: String): Long =
        raw.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid guildId: $raw")

    companion object {
        private val log = LoggerFactory.getLogger(MixerRestHandler::class.java)
        private const val LOAD_TIMEOUT_SEC = 20L
    }
}
