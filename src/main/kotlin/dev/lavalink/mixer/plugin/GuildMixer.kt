package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.IPlayer
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

data class Gains(val main: Float, val sub: Float)

/** Another overlay is already running on the guild. */
class MixerBusyException(message: String) : RuntimeException(message)

/**
 * The overlay would not be heard: [code] is `main_idle` (no music flowing
 * through the main player) or `filter_inactive` (music flows but the mixer
 * filter is not in its chain, so nothing would be mixed or ducked).
 */
class MixerUnavailableException(val code: String, message: String) : RuntimeException(message)

/** How the last overlay ended: `finished`, `cancelled`, `failed`, `stuck`, `timeout`, `main_stopped`, `play_failed`. */
data class OverlayResult(val id: Long, val reason: String)

/**
 * Per-guild overlay state: a hidden secondary Lavaplayer player decodes the
 * clip, its PCM is siphoned into [siphon] and [MixerFilter] mixes it into the
 * main player's chain while the music is ducked.
 *
 * The voice thread only reads volatile gains ([advance]); everything else
 * runs under [lock] or on the single mixer-loop thread ([tick]). Player and
 * track calls are made outside the lock so a slow source never stalls the
 * voice thread into a false TrackStuck.
 *
 * The chain only runs while the main player decodes, so an overlay needs
 * flowing music: [preflight] refuses otherwise and callers fall back to
 * pausing the music.
 */
class GuildMixer(
    val guildId: Long,
    private val playerManager: AudioPlayerManager,
    config: MixerConfig,
) {
    private val lock = Any()

    @Volatile var main: IPlayer? = null
        private set

    @Volatile var duckLevel: Float = config.duckLevel
    @Volatile var duckFadeMs: Long = config.duckFadeMs
    @Volatile var maxOverlayMs: Long = config.maxOverlayMs

    private val mainGain = GainRamp(1f)
    private val subGain = GainRamp(0f)

    val siphon = SiphonQueue.forFrameBuffer(safeFrameBufferDurationMs())
    private val drainFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(provideBufferSize()))
    }

    private var subPlayer: AudioPlayer? = null

    @Volatile private var overlayActive: Boolean = false
    /** The clip's decoder is done; the overlay ends once the mixer has played out what is queued. */
    @Volatile private var subEnded: Boolean = false
    private var subFailed: Boolean = false
    private var subTrack: AudioTrack? = null
    private var overlayId: Long = 0L
    private var overlayStartedMs: Long = 0L
    private var lastResult: OverlayResult? = null

    /**
     * Samples the voice thread still lets pass before the clip becomes audible:
     * the music needs [duckFadeMs] to get out of the way first, and the clip
     * waits in [siphon] meanwhile (nothing is lost, it is bounded by the
     * frame buffer plus slack).
     */
    @Volatile private var subHold: Int = 0

    /** Wall-clock of the last chunk the mixer filter processed; the "is the filter in the chain" probe. */
    private val lastFilterRunMs = AtomicLong(0L)

    private val subListener = object : AudioEventAdapter() {
        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            if (endReason != AudioTrackEndReason.FINISHED && endReason != AudioTrackEndReason.LOAD_FAILED) return
            synchronized(lock) {
                if (!overlayActive || track !== subTrack) return
                if (endReason == AudioTrackEndReason.LOAD_FAILED) subFailed = true
                subEnded = true
            }
        }

        override fun onTrackException(
            player: AudioPlayer,
            track: AudioTrack,
            exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException,
        ) {
            synchronized(lock) { if (overlayActive && track === subTrack) subFailed = true }
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            if (synchronized(lock) { overlayActive && track === subTrack }) {
                log.warn("overlay track stuck on guild {} (threshold {}ms)", guildId, thresholdMs)
                finishOverlay("stuck")
            }
        }
    }

    fun attachMain(player: IPlayer) {
        main = player
    }

    // ---- voice-thread entry points ----

    /** Advances ramps by [samples] and returns the gains to mix with. Lock-free: runs on the voice thread. */
    fun advance(samples: Int): Gains {
        val hold = subHold
        if (hold > 0) {
            val left = (hold - samples).coerceAtLeast(0)
            subHold = left
            if (left == 0 && overlayActive) subGain.rampTo(1f, msToSamples(OVERLAY_FADE_IN_MS))
        }
        mainGain.advance(samples)
        subGain.advance(samples)
        return Gains(mainGain.current, subGain.current)
    }

    fun takeSub(samples: Int, channels: Int, out: Array<FloatArray>) = siphon.take(samples, channels, out)

    fun noteFilterRun() {
        lastFilterRunMs.set(System.currentTimeMillis())
    }

    private var underrunWarnAt = 0L

    /** Throttled: the clip's decoder did not keep up with the mix. */
    fun noteUnderrun(missing: Int, requested: Int) {
        if (!overlayActive || subEnded || missing <= 0) return
        val now = System.currentTimeMillis()
        if (now - underrunWarnAt < 1000) return
        underrunWarnAt = now
        log.warn("overlay starvation on guild {}: {} of {} samples silent", guildId, missing, requested)
    }

    /** Test seam: bypass ramps and pin gains. */
    internal fun gainsForTest(main: Float, sub: Float) {
        mainGain.setNow(main)
        subGain.setNow(sub)
    }

    internal fun overlayForTest(active: Boolean, ended: Boolean = false, startedAgoMs: Long = 0L) {
        overlayActive = active
        subEnded = ended
        overlayStartedMs = System.currentTimeMillis() - startedAgoMs
    }

    internal fun lastFilterRunForTest(): Long = lastFilterRunMs.get()

    // ---- scheduler entry point ----

    /** ~50Hz from the mixer loop: keeps the secondary decoder flowing and ends the overlay when due. */
    fun tick() {
        val sub = synchronized(lock) { subPlayer?.takeIf { it.playingTrack != null } }
        if (sub != null) {
            try {
                sub.provide(drainFrame)
            } catch (e: Exception) {
                log.warn("sub drain failed on guild {}", guildId, e)
            }
        }
        val reason = synchronized(lock) { dueReasonLocked() } ?: return
        finishOverlay(reason)
    }

    private fun dueReasonLocked(): String? {
        if (!overlayActive) return null
        val now = System.currentTimeMillis()
        if (now - overlayStartedMs > maxOverlayMs) return "timeout"
        // The mix is what plays the clip out, so wait for it to drain before ending.
        if (subEnded && siphon.pendingSamples == 0) return if (subFailed) "failed" else "finished"
        // Music stopped or paused: the chain no longer runs, so the clip cannot be heard and cannot drain.
        if (now - maxOf(lastFilterRunMs.get(), overlayStartedMs) > FILTER_STALE_MS) return "main_stopped"
        return null
    }

    // ---- control-plane entry points (REST) ----

    /** Throws unless an overlay started now would be mixed over flowing music. */
    fun preflight() {
        synchronized(lock) {
            if (overlayActive) throw MixerBusyException("overlay already running on guild $guildId")
        }
        if (System.currentTimeMillis() - lastFilterRunMs.get() <= FILTER_STALE_MS) return
        val player = main
        val flowing = player != null && player.audioPlayer.playingTrack != null && !player.audioPlayer.isPaused
        if (flowing) {
            throw MixerUnavailableException(
                "filter_inactive",
                "music is playing on guild $guildId but the mixer filter is not in its chain; " +
                    "send filters.pluginFilters.mixer before the track starts",
            )
        }
        throw MixerUnavailableException("main_idle", "no music is flowing on guild $guildId")
    }

    /** @return the overlay id. */
    fun startOverlay(track: AudioTrack, duck: Float): Long {
        val fadeMs = duckFadeMs
        val (sub, id) = synchronized(lock) {
            if (overlayActive) throw MixerBusyException("overlay already running on guild $guildId")
            val s = ensureSubLocked()
            overlayId++
            subTrack = track
            overlayActive = true
            subEnded = false
            subFailed = false
            overlayStartedMs = System.currentTimeMillis()
            siphon.clear()
            subGain.setNow(0f)
            subHold = msToSamples(fadeMs)
            mainGain.rampTo(duck.coerceIn(0f, 1f), msToSamples(fadeMs))
            s to overlayId
        }
        try {
            sub.playTrack(track)
        } catch (e: Exception) {
            log.warn("overlay play failed on guild {}", guildId, e)
            finishOverlay("play_failed")
            throw e
        }
        log.info("overlay #{} '{}' started on guild {}", id, titleOf(track), guildId)
        return id
    }

    fun cancelOverlay(): Boolean {
        if (!synchronized(lock) { overlayActive }) return false
        finishOverlay("cancelled")
        return true
    }

    fun isOverlayActive(): Boolean = synchronized(lock) { overlayActive }

    fun currentOverlayId(): Long = synchronized(lock) { overlayId }

    fun lastOverlayResult(): OverlayResult? = synchronized(lock) { lastResult }

    fun filterLive(): Boolean = System.currentTimeMillis() - lastFilterRunMs.get() <= FILTER_STALE_MS

    fun destroy() {
        val sub = synchronized(lock) {
            overlayActive = false
            subTrack = null
            subHold = 0
            subPlayer.also { subPlayer = null }
        }
        try {
            sub?.destroy()
        } catch (e: Exception) {
            log.warn("sub destroy failed on guild {}", guildId, e)
        }
    }

    // ---- internals ----

    private fun finishOverlay(reason: String) {
        val sub = synchronized(lock) {
            if (!overlayActive) return
            overlayActive = false
            subEnded = false
            subTrack = null
            subHold = 0
            siphon.clear()
            subGain.setNow(0f)
            if (filterLive()) {
                mainGain.rampTo(1f, msToSamples(duckFadeMs))
            } else {
                // No chunk will advance the ramp; do not leave the next track ducked.
                mainGain.setNow(1f)
            }
            lastResult = OverlayResult(overlayId, reason)
            subPlayer
        }
        log.info("overlay #{} ended on guild {}: {}", lastResult?.id, guildId, reason)
        try {
            sub?.stopTrack()
        } catch (e: Exception) {
            log.warn("sub stop failed on guild {}", guildId, e)
        }
    }

    private fun ensureSubLocked(): AudioPlayer {
        var sub = subPlayer
        if (sub == null) {
            sub = playerManager.createPlayer()
            sub.setFilterFactory(SiphonFactory(siphon))
            sub.addListener(subListener)
            subPlayer = sub
        }
        return sub
    }

    /**
     * How much decoded audio Lavaplayer pre-buffers into a track's frame
     * buffer before anyone pulls a frame. The siphon has to hold all of it,
     * or the opening of the clip is dropped the instant playTrack() returns.
     * Guarded because tests build mixers over stub managers.
     */
    private fun safeFrameBufferDurationMs(): Int = try {
        playerManager.frameBufferDuration
    } catch (t: Throwable) {
        DEFAULT_FRAME_BUFFER_MS
    }

    /**
     * A provide() frame must hold one whole chunk of the *manager's* output
     * format; sizing it from DISCORD_OPUS while the server emits PCM would
     * overflow on every frame and the secondary decoder would never advance.
     */
    private fun provideBufferSize(): Int = try {
        playerManager.configuration?.outputFormat?.maximumChunkSize()
            ?: StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    } catch (t: Throwable) {
        StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    }

    private fun titleOf(track: AudioTrack): String =
        try { track.info.title ?: track.info.identifier } catch (e: Exception) { "unknown" }

    companion object {
        private val log = LoggerFactory.getLogger(GuildMixer::class.java)
        private const val SAMPLE_RATE = 48000
        private const val DEFAULT_FRAME_BUFFER_MS = 5000
        private const val OVERLAY_FADE_IN_MS = 150L

        /** No mixer chunk for this long means the main chain is not running. */
        const val FILTER_STALE_MS = 1500L

        fun msToSamples(ms: Long): Int = ((ms.coerceAtLeast(0) * SAMPLE_RATE) / 1000).toInt().coerceAtLeast(1)
    }
}
