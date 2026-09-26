package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Serializable
data class QueueRequest(
    /** Snowflake as a string — JSON numbers lose precision past 2^53. */
    val guildId: String,
    /** Re-resolved via source managers. */
    val identifier: String? = null,
    /** Exact Lavalink base64 track — preferred, no re-resolution. At least one of the two is required. */
    val encodedTrack: String? = null,
)

@Serializable
data class CrossfadeRequest(val guildId: String, val enabled: Boolean, val durationMs: Long? = null)

@Serializable
data class FadeInRequest(val guildId: String, val durationMs: Long)

@Serializable
data class AnnounceRequest(
    val guildId: String,
    val identifier: String? = null,
    val encodedTrack: String? = null,
    val duckLevel: Float? = null,
)

@Serializable
data class AnnounceCancelRequest(val guildId: String)

@Serializable
data class SilenceRequest(
    val guildId: String,
    val enabled: Boolean,
    val thresholdDb: Float? = null,
    val minSoundMs: Long? = null,
    val headScanMs: Long? = null,
    val tailScanMs: Long? = null,
    val tailConfirmMs: Long? = null,
)

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
data class MixerState(
    /** Echoes the request guild id verbatim (string snowflake). */
    val guildId: String,
    val hasNext: Boolean,
    val overlayActive: Boolean,
    val crossfadeActive: Boolean,
    val crossfadeEnabled: Boolean,
    val crossfadeMs: Long,
    val fadeInMs: Long,
    val duckLevel: Float,
    val silenceEnabled: Boolean,
    val silenceThresholdDb: Float,
    val tailConfirmMs: Long,
    /** [leadMs, trailMs] of the queued track when already analyzed, else null. */
    val nextTrimMs: List<Long>? = null,
)

/**
 * Control plane for the mixer plugin. All endpoints live under the /mixer
 * path prefix alongside the server's own routes.
 *
 * Note enabling audio output also requires the filters op once per guild:
 * `"pluginFilters": { "mixer": { "guildId": "<id>" } }`.
 */
@Service
@RestController
class MixerRestHandler(private val plugin: MixerPlugin) {

    @PostMapping("/mixer/queue/next")
    fun queueNext(@RequestBody body: QueueRequest): ResponseEntity<TrackInfo> {
        requireTrackRef(body.identifier, body.encodedTrack)
        val guildId = gid(body.guildId)
        val track = await(plugin.preloadNext(guildId, body.identifier, body.encodedTrack), body.identifier ?: "encoded track")
        return ResponseEntity.ok(track.toInfo())
    }

    @DeleteMapping("/mixer/queue/next")
    fun clearNext(@RequestParam guildId: Long): ResponseEntity<Unit> {
        val mixer = plugin.find(guildId) ?: throw notFound(guildId)
        return if (mixer.clearNext()) ResponseEntity.noContent().build()
        else throw ResponseStatusException(HttpStatus.NOT_FOUND, "No queued track on guild $guildId")
    }

    @GetMapping("/mixer/state")
    fun state(@RequestParam guildId: Long): ResponseEntity<MixerState> {
        return stateById(guildId.toString(), guildId)
    }

    private fun stateById(raw: String, guildId: Long): ResponseEntity<MixerState> {
        val mixer = plugin.find(guildId) ?: throw notFound(guildId)
        return ResponseEntity.ok(
            MixerState(
                guildId = raw,
                hasNext = mixer.hasNext(),
                overlayActive = mixer.isOverlayActive(),
                crossfadeActive = mixer.isXfadeActive(),
                crossfadeEnabled = mixer.crossfadeEnabled,
                crossfadeMs = mixer.crossfadeMs,
                fadeInMs = mixer.fadeInMs,
                duckLevel = mixer.duckLevel,
                silenceEnabled = mixer.silenceSkipEnabled,
                silenceThresholdDb = mixer.silenceThresholdDb,
                tailConfirmMs = mixer.tailConfirmMs,
                nextTrimMs = mixer.peekNextTrim()?.let { listOf(it.leadMs, it.trailMs) },
            )
        )
    }

    @PostMapping("/mixer/crossfade")
    fun crossfade(@RequestBody body: CrossfadeRequest): ResponseEntity<MixerState> {
        val guildId = gid(body.guildId)
        val mixer = plugin.getOrCreate(guildId)
        mixer.crossfadeEnabled = body.enabled
        body.durationMs?.let { mixer.crossfadeMs = it.coerceIn(500, 30000) }
        return stateById(body.guildId, guildId)
    }

    @PostMapping("/mixer/fade-in")
    fun fadeIn(@RequestBody body: FadeInRequest): ResponseEntity<MixerState> {
        val guildId = gid(body.guildId)
        val mixer = plugin.getOrCreate(guildId)
        mixer.fadeInMs = body.durationMs.coerceIn(0, 10000)
        return stateById(body.guildId, guildId)
    }

    @PostMapping("/mixer/announce")
    fun announce(@RequestBody body: AnnounceRequest): ResponseEntity<TrackInfo> {
        requireTrackRef(body.identifier, body.encodedTrack)
        val guildId = gid(body.guildId)
        val future = try {
            plugin.announce(guildId, body.identifier, body.encodedTrack, body.duckLevel)
        } catch (e: MixerBusyException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
        }
        val track = awaitFuture(future, body.identifier ?: "encoded track")
        return ResponseEntity.ok(track.toInfo())
    }

    @PostMapping("/mixer/announce/cancel")
    fun cancelAnnounce(@RequestBody body: AnnounceCancelRequest): ResponseEntity<Unit> {
        val guildId = gid(body.guildId)
        val mixer = plugin.find(guildId) ?: throw notFound(guildId)
        return if (mixer.cancelOverlay()) ResponseEntity.noContent().build()
        else throw ResponseStatusException(HttpStatus.NOT_FOUND, "No active overlay on guild $guildId")
    }

    @PostMapping("/mixer/silence")
    fun silence(@RequestBody body: SilenceRequest): ResponseEntity<MixerState> {
        val guildId = gid(body.guildId)
        val mixer = plugin.getOrCreate(guildId)
        mixer.silenceSkipEnabled = body.enabled
        body.thresholdDb?.let { mixer.silenceThresholdDb = it.coerceIn(-90f, -20f) }
        body.minSoundMs?.let { mixer.silenceMinSoundMs = it.coerceIn(20, 2000) }
        body.headScanMs?.let { mixer.silenceHeadScanMs = it.coerceIn(1000, 60000) }
        body.tailScanMs?.let { mixer.silenceTailScanMs = it.coerceIn(1000, 60000) }
        body.tailConfirmMs?.let { mixer.tailConfirmMs = it.coerceIn(300, 10000) }
        return stateById(body.guildId, guildId)
    }

    private fun await(future: java.util.concurrent.CompletableFuture<AudioTrack>, identifier: String): AudioTrack {
        // preloadNext already attached queueing; this join only surfaces load errors.
        return try {
            awaitFuture(future, identifier)
        } catch (e: ResponseStatusException) {
            throw e
        }
    }

    private fun awaitFuture(future: java.util.concurrent.CompletableFuture<AudioTrack>, identifier: String): AudioTrack {
        return try {
            future.get(LOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw ResponseStatusException(HttpStatus.ACCEPTED, "Still loading $identifier", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is NoMatchException) throw ResponseStatusException(HttpStatus.BAD_REQUEST, cause.message, cause)
            if (cause is MixerBusyException) throw ResponseStatusException(HttpStatus.CONFLICT, cause.message, cause)
            log.warn("track load failed", cause ?: e)
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

    private fun requireTrackRef(identifier: String?, encodedTrack: String?) {
        if (identifier.isNullOrBlank() && encodedTrack.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide identifier or encodedTrack")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MixerRestHandler::class.java)
        private const val LOAD_TIMEOUT_SEC = 20L
    }
}
