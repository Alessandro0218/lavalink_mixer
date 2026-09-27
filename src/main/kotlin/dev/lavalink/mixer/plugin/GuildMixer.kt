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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class Gains(val main: Float, val sub: Float)

class MixerBusyException(message: String) : RuntimeException(message)

/**
 * Per-guild mixing state. Owns:
 * - a gapless "next track" slot, preloaded via [MixerPlugin.preloadNext],
 * - an overlap crossfade (secondary player decoding the next track while the
 *   main player's tail fades out),
 * - TTS/announcement overlays with music ducking.
 *
 * The secondary player never touches the voice connection; its PCM is
 * siphoned into [siphon] and mixed into the main chain by [MixerFilter].
 * A shared scheduler in [MixerPlugin] calls [drain] (~50Hz, keeps the
 * secondary decoder flowing) and [poll] (~10Hz, crossfade trigger).
 *
 * All mutable state is guarded by [lock]; [advance] is called from the voice
 * thread and only snapshots gains.
 */
class GuildMixer(
    val guildId: Long,
    private val playerManager: AudioPlayerManager,
    config: MixerConfig,
) {
    private val lock = Any()

    @Volatile var main: IPlayer? = null
        private set

    @Volatile var crossfadeEnabled: Boolean = config.crossfadeEnabled
    @Volatile var crossfadeMs: Long = config.crossfadeMs
    @Volatile var fadeInMs: Long = config.fadeInMs
    @Volatile var duckLevel: Float = config.duckLevel
    @Volatile var duckFadeMs: Long = config.duckFadeMs
    @Volatile var maskMs: Long = config.maskMs

    @Volatile var silenceSkipEnabled: Boolean = config.silenceSkipEnabled
    @Volatile var silenceThresholdDb: Float = config.silenceThresholdDb
    @Volatile var silenceMinSoundMs: Long = config.silenceMinSoundMs
    @Volatile var silenceHeadScanMs: Long = config.silenceHeadScanMs
    @Volatile var silenceTailScanMs: Long = config.silenceTailScanMs
    /** Sustained realtime silence needed before an early cut. */
    @Volatile var tailConfirmMs: Long = config.tailConfirmMs
    /** Set by poll(): realtime tail watch armed (cheap per-chunk check reads this). */
    @Volatile var tailWatch: Boolean = false

    private val tailCutter = TailCutDecider()

    private var pendingNextTrack: AudioTrack? = null
    private var readyNextTrack: AudioTrack? = null
    private var attachedPlayers = mutableSetOf<AudioPlayer>()

    private var subPlayer: AudioPlayer? = null
    private var siphonFactory: SiphonFactory? = null
    @Volatile private var analysisActive: Boolean = false
    private val trimCache = LinkedHashMap<String, TrimPoints>()
    private var subTrack: AudioTrack? = null
    private var subStartMs: Long = 0L
    /** Voice thread reads these without taking [lock]; races only shift a fade by a chunk. */
    @Volatile private var overlayActive: Boolean = false
    @Volatile private var xfadeActive: Boolean = false
    private var xfadeHandoffMs: Long = -1L
    /** Position within [subTrack] that has reached the audible output. */
    private val subOutputSamples = AtomicLong(0L)
    private var subStartPositionMs: Long = 0L
    private var announceToken: Long = 0L
    private var masking: Boolean = false

    private var lastMainTrack: AudioTrack? = null
    private var lastPosition: Long = 0L

    /** Ticks once per mixer-filter chunk; see [checkFilterHeartbeatLocked]. */
    private val filterRunCount = AtomicLong(0L)
    private var filterRunBaseline = 0L
    private var filterBaselinePos = 0L
    @Volatile private var filterMissing: Boolean = false
    private var filterMissingWarned: Boolean = false
    private val underrunWarnAt = AtomicLong(0L)
    /** Only touched from [poll], which the single mixer-loop thread drives. */
    private var xfadeLogMs: Long = 0L

    private val mainGain = GainRamp(1f)
    private val subGain = GainRamp(0f)
    /** Written by the voice thread every chunk; control plane only (re)starts the fade. */
    @Volatile private var xfadeLeft: Int = 0
    @Volatile private var xfadeTotal: Int = 1
    /** Set when the sub decoder reports stuck/exception mid-analysis so scans abort early. */
    @Volatile private var analysisBroken: Boolean = false

    val siphon = SiphonQueue.forFrameBuffer(safeFrameBufferDurationMs())
    private val drainFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(provideBufferSize()))
    }

    private data class StartSnap(val xfade: Boolean, val leadMs: Long, val fadeInMs: Long)

    val mainListener = object : AudioEventAdapter() {
        override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
            // Snapshot under lock; the (potentially blocking) seek runs outside it
            // so the voice thread never stalls behind us.
            val snap = synchronized(lock) {
                tailCutter.reset()
                if (masking) {
                    masking = false
                    null
                } else {
                    StartSnap(
                        xfadeActive,
                        if (!xfadeActive && silenceSkipEnabled) leadForLocked(track) else -1L,
                        fadeInMs,
                    )
                }
            } ?: return
            // Leading-silence skip: start at content, not file start.
            // Queued tracks already seek in seekPastLead; this covers
            // the first track (played via Lavalink REST) when its trim
            // was cached by an earlier preload/analysis.
            if (!snap.xfade && snap.leadMs > 200 && track.isSeekable) {
                try {
                    val duration = track.duration
                    track.position = if (duration > 0) {
                        snap.leadMs.coerceIn(0, (duration - 500).coerceAtLeast(0))
                    } else snap.leadMs
                } catch (e: Exception) {
                    log.warn("lead skip seek failed on guild {}", guildId, e)
                }
            }
            if (!snap.xfade && snap.fadeInMs > 0) {
                mainGain.setNow(0f)
                mainGain.rampTo(1f, msToSamples(snap.fadeInMs))
            }
        }

        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            if (endReason != AudioTrackEndReason.FINISHED && endReason != AudioTrackEndReason.LOAD_FAILED) return
            // Decide under lock, act outside it (play/seek may block on source I/O).
            val handoff = synchronized(lock) { xfadeActive }
            if (handoff) {
                doHandoff()
            } else {
                val hasNext = synchronized(lock) { readyNextTrack != null || pendingNextTrack != null }
                if (hasNext) advanceNext()
            }
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            val title = runCatching { track.info.title }.getOrDefault("unknown")
            log.warn("main track '{}' stuck on guild {} (threshold {}ms); mixer failover", title, guildId, thresholdMs)
            recoverFromMainStall()
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) {
            val title = runCatching { track.info.title }.getOrDefault("unknown")
            log.warn("main track '{}' failed on guild {}; mixer failover", title, guildId, exception)
            recoverFromMainStall()
        }
    }

    /**
     * A stalled main track never reaches FINISHED, so without this the mixer
     * would hang: during a crossfade the handoff position never arrives (the
     * position is frozen), and otherwise the queued track never starts.
     * Failing over keeps audio flowing; the TrackStuck/Exception event itself
     * is still delivered to the client by the server's own listener.
     */
    private fun recoverFromMainStall() {
        val handoff = synchronized(lock) { xfadeActive }
        if (handoff) {
            doHandoff()
            return
        }
        val hasNext = synchronized(lock) { readyNextTrack != null || pendingNextTrack != null }
        if (hasNext) advanceNext()
    }

    private val subListener = object : AudioEventAdapter() {
        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            val overlay = synchronized(lock) { overlayActive }
            if (overlay) {
                finishOverlay()
                return
            }
            val xfade = synchronized(lock) { xfadeActive }
            if (xfade) abortXfade("sub track ended: $endReason")
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) {
            subFailed()
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            subFailed()
        }

        private fun subFailed() {
            val action = synchronized(lock) {
                when {
                    overlayActive -> 1
                    xfadeActive -> 2
                    analysisActive -> {
                        // Unblock analyzeBlocking scans waiting on tap data that will never arrive.
                        analysisBroken = true
                        0
                    }
                    else -> 0
                }
            }
            when (action) {
                1 -> finishOverlay()
                2 -> abortXfade("sub track stuck/failed")
            }
        }
    }

    fun attachMain(player: IPlayer) {
        synchronized(lock) {
            main = player
            if (attachedPlayers.add(player.audioPlayer)) {
                player.audioPlayer.addListener(mainListener)
            }
        }
    }

    fun newFilter(downstream: com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter? = null): MixerFilter =
        MixerFilter(this, downstream)

    // ---- voice-thread entry points ----

    /** Advances ramps by [samples] and returns the gains to mix with. */
    fun advance(samples: Int): Gains {
        // Lock-free on purpose: this runs on the voice thread for every ~20ms
        // chunk, and blocking here stalls main-track output until Lavaplayer
        // reports the (healthy) track as stuck. GainRamp fields are volatile;
        // a race with the control plane only shifts a fade by one chunk.
        if (xfadeActive) {
            val total = xfadeTotal.coerceAtLeast(1)
            val left = (xfadeLeft - samples).coerceAtLeast(0)
            xfadeLeft = left
            val t = (1f - left.toFloat() / total).coerceIn(0f, 1f)
            // Equal-power curve keeps perceived loudness flat through the overlap.
            mainGain.setNow(cos(t * PI.toFloat() / 2f))
            subGain.setNow(sin(t * PI.toFloat() / 2f))
        } else {
            mainGain.advance(samples)
            subGain.advance(samples)
        }
        return Gains(mainGain.current, subGain.current)
    }

    fun takeSub(samples: Int, channels: Int, out: Array<FloatArray>) = siphon.take(samples, channels, out)

    /** Counts only PCM that is actually mixed into the Discord output. */
    fun noteSubOutput(samples: Int) {
        if (samples <= 0 || !xfadeActive) return
        subOutputSamples.addAndGet(samples.toLong())
    }

    /**
     * Heartbeat from the voice path: the mixer filter processed a chunk.
     * [checkFilterHeartbeatLocked] uses the absence of these to prove the
     * filter never made it into the running track's chain.
     */
    fun noteFilterRun() {
        filterRunCount.incrementAndGet()
    }

    /**
     * Throttled voice-thread warning: an overlap is fading in but the
     * secondary decoder did not supply the whole chunk, so the mix dropped
     * to silence on the sub side for this frame.
     */
    fun noteUnderrun(missing: Int, requested: Int) {
        if (!xfadeActive || missing <= 0) return
        val now = System.currentTimeMillis()
        val last = underrunWarnAt.get()
        if (now - last < 1000 || !underrunWarnAt.compareAndSet(last, now)) return
        log.warn(
            "crossfade sub starvation on guild {}: {} of {} samples silent " +
                "(pending={}f/{}ms dropped={}f/{}ms subOut={}ms)",
            guildId, missing, requested,
            siphon.pendingFrames, siphon.pendingSamples * 1000L / SAMPLE_RATE,
            siphon.droppedFrames, siphon.droppedSamples,
            subOutputSamples.get() * 1000L / SAMPLE_RATE,
        )
    }

    /**
     * Threshold amp for realtime tail watching, or null when no peak
     * computation is needed this chunk (voice-thread fast path).
     */
    fun realtimeTailAmp(): Float? =
        if (tailWatch && silenceSkipEnabled) SilenceAnalyzer.thresholdAmp(silenceThresholdDb) else null

    fun noteChunk(peak: Float, samples: Int, thresholdAmp: Float) = tailCutter.note(peak, samples, thresholdAmp)

    /** Test seam: bypass ramps and pin gains. */
    internal fun gainsForTest(main: Float, sub: Float) {
        mainGain.setNow(main)
        subGain.setNow(sub)
    }

    /** Test seam: arm a fake overlap so [advance]/[noteSubOutput] can be driven without players. */
    internal fun beginXfadeForTest(totalSamples: Int) {
        xfadeTotal = totalSamples.coerceAtLeast(1)
        xfadeLeft = xfadeTotal
        subOutputSamples.set(0L)
        xfadeActive = true
    }

    internal fun endXfadeForTest() {
        xfadeActive = false
    }

    internal fun subOutputSamplesForTest(): Long = subOutputSamples.get()

    internal fun filterRunsForTest(): Long = filterRunCount.get()

    internal fun filterMissingForTest(): Boolean = filterMissing

    /** Advances the heartbeat baseline as if a fresh main track just started. */
    internal fun resetFilterHeartbeatForTest(position: Long = 0L) {
        synchronized(lock) { resetFilterHeartbeatLocked(position) }
    }

    internal fun trimForTest(identifier: String): TrimPoints? =
        synchronized(lock) { trimCache[identifier] }

    // ---- scheduler entry points ----

    /** Keeps the secondary decoder flowing; its PCM is captured by the siphon filter. */
    fun drain() {
        val sub = synchronized(lock) { subPlayer?.takeIf { it.playingTrack != null } } ?: return
        try {
            sub.provide(drainFrame)
        } catch (e: Exception) {
            log.warn("sub drain failed on guild {}", guildId, e)
        }
    }

    private sealed interface PollAction {
        data object None : PollAction
        data class StartOverlap(val plan: CrossfadePlan) : PollAction
        data object Handoff : PollAction
        data object AdvanceNext : PollAction
        data class AbortXfade(val reason: String) : PollAction
    }

    /**
     * Starts/finalizes a transition against the final *audible* sample.
     * A detected trailing silent segment is never played or included in a
     * crossfade: the overlap ends at `duration - trailingSilence`.
     *
     * Only cheap state reads happen under [lock]; player/track calls
     * (play, seek, stop) run outside it so a slow source can never stall
     * the voice thread into a false TrackStuck.
     */
    fun poll() {
        val action = synchronized(lock) { decidePollLocked() }
        // Read the transition state before acting on it: doHandoff clears it.
        if (xfadeActive) logXfadeProgress(action)
        when (action) {
            is PollAction.None -> Unit
            is PollAction.StartOverlap -> startOverlap(action.plan)
            is PollAction.Handoff -> doHandoff()
            is PollAction.AdvanceNext -> advanceNext()
            is PollAction.AbortXfade -> abortXfade(action.reason)
        }
    }

    /** 1Hz breadcrumb while an overlap runs: is the sub actually feeding us? */
    private fun logXfadeProgress(action: PollAction) {
        val now = System.currentTimeMillis()
        if (now - xfadeLogMs < 1000) return
        xfadeLogMs = now
        val track = main?.audioPlayer?.playingTrack ?: return
        val pos = safePosition(track)
        val handoff: Long
        val subPos: Long
        val subOutMs: Long
        synchronized(lock) {
            handoff = xfadeHandoffMs
            subPos = subTrack?.let { safePosition(it) } ?: -1L
            subOutMs = subOutputSamples.get()
        }
        log.info(
            "crossfade progress guild {} at {}ms/handoff {}ms ({}ms left, action={}): " +
                "pending={}f/{}ms dropped={}f subOut={}ms subPos={}ms mixerFilterCalls={}",
            guildId, pos, handoff, (handoff - pos).coerceAtLeast(0), action,
            siphon.pendingFrames, siphon.pendingSamples * 1000L / SAMPLE_RATE,
            siphon.droppedFrames,
            subOutMs * 1000L / SAMPLE_RATE,
            subPos,
            filterRunCount.get(),
        )
    }

    private fun decidePollLocked(): PollAction {
        val audioPlayer = main?.audioPlayer ?: return PollAction.None
        val track = audioPlayer.playingTrack ?: run {
            lastMainTrack = null
            return PollAction.None
        }
        if (track !== lastMainTrack) {
            lastMainTrack = track
            lastPosition = safePosition(track)
            resetFilterHeartbeatLocked(lastPosition)
            return PollAction.None
        }
        val pos = safePosition(track)
        if (pos < lastPosition - 1500) {
            // User seeked backwards: any armed overlap is stale.
            tailCutter.reset()
            resetFilterHeartbeatLocked(pos)
            if (xfadeActive) return PollAction.AbortXfade("seek during crossfade")
        }
        lastPosition = pos
        checkFilterHeartbeatLocked(audioPlayer, pos)
        // Opportunistic promotion: analysis may have finished since queue.
        promoteIfPendingLocked()
        if (!xfadeActive && (readyNextTrack != null || pendingNextTrack != null)) {
            tailWatch = silenceSkipEnabled
            val duration = track.duration
            if (duration > 0 && duration < Long.MAX_VALUE / 2) {
                val trail = if (silenceSkipEnabled) trailForLocked(track) else 0L
                val contentEnd = TrimTransition.contentEnd(duration, trail)
                val plan = if (track.isSeekable && crossfadeEnabled) {
                    TrimTransition.crossfade(contentEnd, crossfadeMs)
                } else null
                if (readyNextTrack != null && plan != null && pos >= plan.startMs && !analysisActive) {
                    return PollAction.StartOverlap(plan)
                } else if (silenceSkipEnabled && trail > 0 && pos >= contentEnd) {
                    // End exactly at the last audible sample; do not emit the file's tail silence.
                    // Force-promote pending so a slow analysis never plays silence.
                    if (readyNextTrack == null) promoteForcedLocked()
                    return PollAction.AdvanceNext
                } else if (silenceSkipEnabled &&
                    tailCutter.shouldCut(pos, duration, tailConfirmMs, silenceTailScanMs)
                ) {
                    // Realtime fallback: sustained silence inside the tail window.
                    if (readyNextTrack == null) promoteForcedLocked()
                    return PollAction.AdvanceNext
                } else if (readyNextTrack == null && pendingNextTrack != null && pos >= duration - 500) {
                    // Analysis still running at file end: advance anyway (degraded, no trim).
                    promoteForcedLocked()
                    return PollAction.AdvanceNext
                }
            } else if (silenceSkipEnabled &&
                tailCutter.shouldCut(pos, -1, tailConfirmMs, silenceTailScanMs)
            ) {
                // Streams have no pre-analysis tail; realtime is the only signal.
                if (readyNextTrack == null) promoteForcedLocked()
                return PollAction.AdvanceNext
            }
            return PollAction.None
        } else if (xfadeActive && xfadeHandoffMs >= 0 && pos >= xfadeHandoffMs) {
            // `FINISHED` occurs at the physical file end. Switch at the
            // audible end instead, so detected trailing silence is gone.
            return PollAction.Handoff
        } else {
            tailWatch = false
            return PollAction.None
        }
    }

    private fun resetFilterHeartbeatLocked(position: Long) {
        filterRunBaseline = filterRunCount.get()
        filterBaselinePos = position
        filterMissing = false
        filterMissingWarned = false
    }

    /**
     * A main track whose position keeps advancing while [filterRunCount]
     * never moves means Lavaplayer built this chain without our filter: the
     * client sent `play` before `filters`, or never sent `pluginFilters.mixer`
     * at all. Lavaplayer does not hot-swap filter factories into a running
     * track, so the crossfade then only ever computes gains nobody applies —
     * the exact symptom of "track A plays to the end, B starts afterwards
     * from zero". Say so loudly instead of failing silently.
     */
    private fun checkFilterHeartbeatLocked(audioPlayer: AudioPlayer, pos: Long) {
        val runs = filterRunCount.get()
        if (runs != filterRunBaseline) {
            if (filterMissing) {
                filterMissing = false
                log.info("mixer filter is live on guild {} ({} chunks since track start)", guildId, runs - filterRunBaseline)
            }
            return
        }
        if (filterMissingWarned || main == null || audioPlayer.isPaused) return
        val played = pos - filterBaselinePos
        if (played < 1000) return
        filterMissingWarned = true
        filterMissing = true
        log.warn(
            "mixer filter is NOT in the running track's chain on guild {}: {}ms decoded, " +
                "0 mixer filter calls. Send the `filters` op with pluginFilters.mixer BEFORE " +
                "`play` (or re-send `filters` and seek) — Lavalink does not rebuild the chain " +
                "of a track that is already playing.",
            guildId, played,
        )
    }

    // ---- control-plane entry points (REST) ----

    fun queueNext(future: CompletableFuture<AudioTrack>) {
        future.whenComplete { track, err ->
            if (err == null && track != null) {
                synchronized(lock) {
                    // Overwrite any previous slot; promote immediately when
                    // trim is already cached, otherwise wait for analysis.
                    readyNextTrack = null
                    pendingNextTrack = track
                    promoteIfPendingLocked()
                }
                log.info("queued next track '{}' on guild {}", track.info.title, guildId)
            }
        }
    }

    fun clearNext(): Boolean = synchronized(lock) {
        val had = pendingNextTrack != null || readyNextTrack != null
        pendingNextTrack = null
        readyNextTrack = null
        had
    }

    fun hasNext(): Boolean = synchronized(lock) { pendingNextTrack != null || readyNextTrack != null }

    fun isOverlayActive(): Boolean = synchronized(lock) { overlayActive }
    fun isXfadeActive(): Boolean = synchronized(lock) { xfadeActive }
    fun isSubBusy(): Boolean = synchronized(lock) { overlayActive || xfadeActive || analysisActive }

    fun startOverlay(track: AudioTrack, duck: Float): AudioTrack {
        val sub = synchronized(lock) {
            if (overlayActive || xfadeActive) throw MixerBusyException("secondary player busy on guild $guildId")
            val s = ensureSubLocked()
            announceToken++
            subTrack = track
            overlayActive = true
            subStartMs = System.currentTimeMillis()
            siphon.clear()
            mainGain.rampTo(duck.coerceIn(0f, 1f), msToSamples(duckFadeMs))
            subGain.setNow(0f)
            subGain.rampTo(1f, msToSamples(150))
            s
        }
        try {
            sub.playTrack(track)
        } catch (e: Exception) {
            log.warn("overlay play failed on guild {}", guildId, e)
            finishOverlay()
            throw e
        }
        log.info("overlay '{}' started on guild {}", track.info.title, guildId)
        return track
    }

    fun cancelOverlay(): Boolean {
        val sub = synchronized(lock) {
            if (!overlayActive) return false
            subPlayer
        }
        try {
            sub?.stopTrack()
        } finally {
            finishOverlay()
        }
        return true
    }

    fun destroy() {
        val sub = synchronized(lock) {
            pendingNextTrack = null
            readyNextTrack = null
            subPlayer.also { subPlayer = null }
        }
        try {
            sub?.destroy()
        } catch (e: Exception) {
            log.warn("sub destroy failed on guild {}", guildId, e)
        }
    }

    // ---- internals (all called under lock) ----

    /**
     * How much decoded audio Lavaplayer pre-buffers into a track's frame
     * buffer before anyone pulls a frame. The siphon has to be able to hold
     * all of it, or the first seconds of an overlaid track are dropped the
     * instant playTrack() returns. Guarded because tests build mixers over
     * stub managers.
     */
    private fun safeFrameBufferDurationMs(): Int = try {
        playerManager.frameBufferDuration
    } catch (t: Throwable) {
        DEFAULT_FRAME_BUFFER_MS
    }

    /**
     * A provide() frame must hold one whole encoded chunk of the *manager's*
     * output format. Sizing it from DISCORD_OPUS while the server emits PCM
     * overflows on every frame, [drain] then logs and drops each attempt, the
     * secondary decoder never advances — and the crossfade degenerates into
     * "the old track plays out, the new one starts after". Also guarded:
     * tests build mixers over stub managers.
     */
    private fun provideBufferSize(): Int = try {
        playerManager.configuration?.outputFormat?.maximumChunkSize()
            ?: StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    } catch (t: Throwable) {
        StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()
    }

    /** Separate buffer from [drainFrame]: analysis runs on its own thread. */
    private fun newProvideFrame(): MutableAudioFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(provideBufferSize()))
    }

    /** @return false when the sub's frame buffer had nothing to give. */
    private fun pumpAnalysis(sub: AudioPlayer, frame: MutableAudioFrame): Boolean = try {
        sub.provide(frame)
    } catch (e: Exception) {
        analysisBroken = true
        log.warn("analysis pump failed on guild {}", guildId, e)
        false
    }

    /**
     * Keeps the secondary decoder moving while a scan runs. Nothing else
     * does: Lavaplayer fills the frame buffer once (frameBufferDuration) and
     * then blocks until somebody calls provide(), and the mixer-loop's
     * [drain] only ticks ~50Hz on whatever happens to be playing. Without
     * this the scan collects a single buffer's worth of audio and then sits
     * on an empty tap until its 45s deadline, which either truncates the
     * trim or stalls every preload by 45 seconds. The frames pulled here are
     * discarded — the tap, not the frame buffer, is the scan's data source.
     */
    private fun pumpSub(sub: AudioPlayer, frame: MutableAudioFrame) {
        var burst = 0
        while (burst < ANALYSIS_PUMP_BURST && !analysisBroken) {
            if (!pumpAnalysis(sub, frame)) break
            burst++
        }
        try {
            Thread.sleep(if (burst > 0) ANALYSIS_PUMP_SLEEP_MS else ANALYSIS_IDLE_SLEEP_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            analysisBroken = true
        }
    }

    private fun ensureSubLocked(): AudioPlayer {
        var sub = subPlayer
        if (sub == null) {
            sub = playerManager.createPlayer()
            val factory = SiphonFactory(siphon)
            siphonFactory = factory
            sub.setFilterFactory(factory)
            sub.addListener(subListener)
            subPlayer = sub
        }
        return sub
    }

    private fun startOverlap(plan: CrossfadePlan) {
        // Pop the slot first; seeks and playTrack run outside the lock.
        val next = synchronized(lock) {
            val n = readyNextTrack ?: pendingNextTrack ?: return
            readyNextTrack = null
            pendingNextTrack = null
            n
        }
        seekPastLead(next)
        val sub = synchronized(lock) { ensureSubLocked() }
        synchronized(lock) {
            if (overlayActive || xfadeActive) {
                // Lost a race with an overlay/overlap started on another thread:
                // re-queue rather than dropping the track.
                if (readyNextTrack == null) readyNextTrack = next
                else if (pendingNextTrack == null) pendingNextTrack = next
                else log.warn("dropping overlapped queue on guild {}: slot busy", guildId)
                return
            }
            subTrack = next
            overlayActive = false
            xfadeActive = true
            xfadeHandoffMs = plan.handoffMs
            subOutputSamples.set(0L)
            subStartPositionMs = safePosition(next)
            subStartMs = System.currentTimeMillis()
            siphon.clear()
            xfadeTotal = msToSamples(plan.durationMs).coerceAtLeast(1)
            xfadeLeft = xfadeTotal
            xfadeLogMs = 0L
        }
        try {
            sub.playTrack(next)
        } catch (e: Exception) {
            log.warn("overlap play failed on guild {}", guildId, e)
            abortXfade("overlap play failed")
            return
        }
        log.info(
            "crossfade started on guild {} at {}ms; handoff at {}ms ({}ms audible overlap); " +
                "sub track '{}' @{}ms, mixerFilterCalls={}",
            guildId, plan.startMs, plan.handoffMs, plan.durationMs,
            titleOf(next), subStartPositionMs, filterRunCount.get(),
        )
    }

    private fun titleOf(track: AudioTrack): String =
        try { track.info.title ?: track.info.identifier } catch (e: Exception) { "unknown" }

    private data class Handoff(
        val source: AudioTrack,
        val clone: AudioTrack,
        val outputPosition: Long,
        val subReportedMs: Long,
        val subOutMs: Long,
        val droppedFrames: Long,
    )

    private fun doHandoff() {
        val plan = synchronized(lock) {
            // poll(), mainListener.onTrackEnd and recoverFromMainStall all read
            // xfadeActive under the lock and then call us *after* releasing it.
            // A main track hitting FINISHED at the exact handoff position can
            // therefore race the position-based handoff; without this guard the
            // second caller replays the whole transition and throws away the
            // mask ramp it just armed.
            if (!xfadeActive) return
            val sub = subTrack
            xfadeActive = false
            xfadeHandoffMs = -1L
            if (sub == null) {
                resetGainsLocked()
                null
            } else {
                val clone = sub.makeClone()
                val subOutMs = subOutputSamples.get() * 1000L / SAMPLE_RATE
                Handoff(
                    source = sub,
                    clone = clone,
                    outputPosition = subStartPositionMs + subOutMs,
                    subReportedMs = safePosition(sub),
                    subOutMs = subOutMs,
                    droppedFrames = siphon.droppedFrames,
                )
            }
        } ?: return
        try {
            if (plan.clone.isSeekable) {
                val duration = plan.clone.duration
                plan.clone.position = if (duration > 0) {
                    plan.outputPosition.coerceIn(0, duration)
                } else {
                    plan.outputPosition.coerceAtLeast(0)
                }
            }
        } catch (e: Exception) {
            log.warn("handoff seek failed on guild {}, starting from 0", guildId, e)
        }
        val sub = synchronized(lock) { subPlayer }
        try {
            sub?.stopTrack()
        } catch (e: Exception) {
            log.warn("sub stop failed on guild {}", guildId, e)
        }
        siphon.clear()
        subGain.setNow(0f)
        mainGain.setNow(0f)
        mainGain.rampTo(1f, msToSamples(maskMs).coerceAtLeast(1))
        tailCutter.reset()
        // Arm the mask BEFORE play(): Lavaplayer dispatches TrackStart
        // synchronously from playTrack(), so a flag set afterwards is too late.
        // An unmasked onTrackStart re-seeks the clone to its leading-silence
        // offset, rewinding the whole overlap the crossfade just built.
        synchronized(lock) {
            // Only clear our own slot; a newer overlap may already reuse it.
            if (subTrack === plan.source) subTrack = null
            masking = true
        }
        try {
            // Volatile read: attachMain publishes the player safely.
            main?.play(plan.clone)
            log.info(
                "crossfade handoff on guild {} at {}ms (sub decoder was at {}ms, " +
                    "{}ms of sub audio heard, {} frames dropped)",
                guildId, plan.outputPosition, plan.subReportedMs, plan.subOutMs, plan.droppedFrames,
            )
        } catch (e: Exception) {
            log.warn("handoff play failed on guild {}", guildId, e)
            synchronized(lock) {
                if (subTrack === plan.source) subTrack = null
                masking = false
                resetGainsLocked()
            }
        }
    }

    private fun abortXfade(reason: String) {
        val sub = synchronized(lock) {
            val subPosMs = subTrack?.let { safePosition(it) } ?: -1L
            val subOutMs = subOutputSamples.get() * 1000L / SAMPLE_RATE
            val elapsed = if (subStartMs > 0) System.currentTimeMillis() - subStartMs else -1L
            log.warn(
                "crossfade aborted on guild {} after {}ms: {} " +
                    "(subPos={}ms subOut={}ms pending={}f dropped={}f)",
                guildId, elapsed, reason, subPosMs, subOutMs,
                siphon.pendingFrames, siphon.droppedFrames,
            )
            xfadeActive = false
            xfadeHandoffMs = -1L
            subTrack = null
            siphon.clear()
            mainGain.rampTo(1f, msToSamples(duckFadeMs))
            subGain.setNow(0f)
            subPlayer
        }
        try {
            sub?.stopTrack()
        } catch (e: Exception) {
            log.warn("sub stop failed on guild {}", guildId, e)
        }
    }

    private fun finishOverlay() {
        val sub = synchronized(lock) {
            overlayActive = false
            subTrack = null
            siphon.clear()
            mainGain.rampTo(1f, msToSamples(duckFadeMs))
            subGain.setNow(0f)
            subPlayer
        }
        try {
            sub?.stopTrack()
        } catch (e: Exception) {
            log.warn("sub stop failed on guild {}", guildId, e)
        }
    }

    /** Plays the queued track now, seeking past analyzed leading silence. */
    private fun advanceNext() {
        val next = synchronized(lock) {
            val n = readyNextTrack ?: pendingNextTrack ?: return
            readyNextTrack = null
            pendingNextTrack = null
            lastMainTrack = null
            tailCutter.reset()
            n
        }
        seekPastLead(next)
        try {
            main?.play(next)
            log.debug("gapless advance on guild {}", guildId)
        } catch (e: Exception) {
            log.warn("gapless play failed on guild {}", guildId, e)
        }
    }

    private fun seekPastLead(track: AudioTrack) {
        if (!silenceSkipEnabled) return
        val lead = synchronized(lock) { leadForLocked(track) }
        if (lead > 200 && track.isSeekable) {
            try {
                val duration = track.duration
                track.position = if (duration > 0) lead.coerceIn(0, (duration - 500).coerceAtLeast(0)) else lead
                log.debug("skipped {}ms leading silence on guild {}", lead, guildId)
            } catch (e: Exception) {
                log.warn("lead skip seek failed on guild {}", guildId, e)
            }
        }
    }

    private fun resetGainsLocked() {
        mainGain.setNow(1f)
        subGain.setNow(0f)
    }

    // ---- silence analysis ----

    private fun idOf(track: AudioTrack): String? = try {
        track.identifier
    } catch (e: Exception) {
        null
    }

    private fun trimForLocked(track: AudioTrack): TrimPoints {
        val id = idOf(track) ?: return TrimPoints.NONE
        return trimCache[id] ?: TrimPoints.NONE
    }

    private fun leadForLocked(track: AudioTrack): Long = trimForLocked(track).leadMs
    private fun trailForLocked(track: AudioTrack): Long = trimForLocked(track).trailMs

    fun peekNextTrim(): TrimPoints? = synchronized(lock) {
        val next = readyNextTrack ?: pendingNextTrack ?: return null
        trimForLocked(next).takeIf { it != TrimPoints.NONE }
    }

    /** Moves pending → ready once its trim is known (or no trim needed). */
    private fun promoteIfPendingLocked() {
        val pending = pendingNextTrack ?: return
        if (!silenceSkipEnabled) {
            pendingNextTrack = null
            readyNextTrack = pending
            return
        }
        val id = idOf(pending) ?: run {
            pendingNextTrack = null
            readyNextTrack = pending
            return
        }
        if (trimCache.containsKey(id)) {
            pendingNextTrack = null
            readyNextTrack = pending
        }
    }

    /** Forced promotion when we must advance despite incomplete analysis. */
    private fun promoteForcedLocked() {
        val pending = pendingNextTrack ?: return
        pendingNextTrack = null
        if (readyNextTrack == null) readyNextTrack = pending
    }

    /**
     * Decodes [track] (a clone; the original is untouched) on the secondary
     * player and measures leading/trailing silence into [trimCache].
     * Runs on the shared analysis thread; safe to call from a future callback.
     * Skipped while the sub player is otherwise in use.
     */
    fun analyzeBlocking(track: AudioTrack) {
        if (!silenceSkipEnabled) {
            synchronized(lock) { promoteIfPendingLocked() }
            return
        }
        val identifier = idOf(track) ?: run {
            synchronized(lock) { promoteIfPendingLocked() }
            return
        }
        synchronized(lock) {
            if (trimCache.containsKey(identifier)) {
                promoteIfPendingLocked()
                return
            }
            if (overlayActive || xfadeActive || analysisActive) return
            analysisActive = true
            analysisBroken = false
        }
        try {
            val factory = synchronized(lock) { ensureSubLocked(); siphonFactory }
                ?: return
            val duration = try {
                track.duration
            } catch (e: Exception) {
                -1L
            }
            val seekable = try {
                track.isSeekable
            } catch (e: Exception) {
                false
            }
            val scanCap = maxOf(silenceHeadScanMs, silenceTailScanMs)
            // Short tracks: a single decode from the start yields both ends.
            val (lead, tail) = if (duration in 1..minOf(scanCap, 30000)) {
                analyzeSinglePass(track, factory, duration)
            } else {
                val head = scanHead(track, factory)
                val t = if (seekable && duration > 0 && duration < Long.MAX_VALUE / 2) {
                    scanTail(track, factory, duration)
                } else 0L
                head to t
            }
            val guardedLead = if (duration > 0 && lead >= duration - 500) 0 else lead.coerceAtLeast(0)
            if (analysisBroken) {
                // The sub decoder died mid-scan; partial PCM would poison the
                // cache. Leave it uncached so a later preload retries, and let
                // poll() degrade to an untrimmed advance at content end.
                log.warn("silence analysis aborted on guild {}: sub decoder failed", guildId)
                return
            }
            synchronized(lock) {
                trimCache[identifier] = TrimPoints(guardedLead, tail.coerceAtLeast(0))
                while (trimCache.size > 200) trimCache.remove(trimCache.keys.first())
                promoteIfPendingLocked()
            }
            log.info("silence analysis guild {}: lead={}ms trail={}ms", guildId, guardedLead, tail)
        } catch (e: Exception) {
            log.warn("silence analysis failed on guild {}", guildId, e)
        } finally {
            synchronized(lock) {
                analysisActive = false
                try {
                    subPlayer?.stopTrack()
                } catch (e: Exception) {
                    log.warn("analysis cleanup stop failed on guild {}", guildId, e)
                }
            }
        }
    }

    /** Streams the head through [SilenceAnalyzer.LeadScanner]; stops decoding once confirmed. */
    private fun scanHead(track: AudioTrack, factory: SiphonFactory): Long {
        val tap = SiphonQueue(maxFrames = 512)
        factory.addTap(tap)
        try {
            val sub = synchronized(lock) { subPlayer } ?: return 0
            val clone = track.makeClone()
            sub.playTrack(clone)
            val scanner = SilenceAnalyzer.LeadScanner(
                SilenceAnalyzer.thresholdAmp(silenceThresholdDb),
                msToSamples(silenceMinSoundMs),
            )
            val cap = msToSamples(silenceHeadScanMs)
            var collected = 0
            val pumpFrame = newProvideFrame()
            val deadline = System.currentTimeMillis() + 45000
            while (collected < cap && System.currentTimeMillis() < deadline && !analysisBroken) {
                if (sub.playingTrack !== clone) break
                if (tap.pendingFrames == 0) {
                    pumpSub(sub, pumpFrame)
                    continue
                }
                while (tap.pendingFrames > 0 && collected < cap) {
                    val n = minOf(1920, cap - collected)
                    val chunk = Array(2) { FloatArray(n) }
                    val took = tap.take(n, 2, chunk)
                    if (took <= 0) break
                    collected += took
                    if (scanner.feed(chunk, took) != null) break
                }
                if (scanner.resultMs != null) break
            }
            try {
                sub.stopTrack()
            } catch (e: Exception) {
                log.warn("head scan stop failed on guild {}", guildId, e)
            }
            return scanner.resultMs ?: 0
        } finally {
            factory.removeTap(tap)
        }
    }

    private fun scanTail(track: AudioTrack, factory: SiphonFactory, duration: Long): Long {
        val startMs = (duration - silenceTailScanMs).coerceAtLeast(0)
        val collected = collectFrom(track, factory, startMs, msToSamples(silenceTailScanMs + 2000))
        if (collected.isEmpty()) return 0
        var total = 0L
        for (f in collected) total += f[0].size
        val collectedMs = total * 1000 / 48000
        val lastEnd = SilenceAnalyzer.findLastSound(
            collected,
            SilenceAnalyzer.thresholdAmp(silenceThresholdDb),
            msToSamples(silenceMinSoundMs),
        )
        // No sound in the whole window: the window itself is trailing silence.
        if (lastEnd < 0) return collectedMs
        return (collectedMs - lastEnd).coerceAtLeast(0)
    }

    private fun analyzeSinglePass(track: AudioTrack, factory: SiphonFactory, duration: Long): Pair<Long, Long> {
        val collected = collectFrom(track, factory, 0, msToSamples(duration + 1000))
        if (collected.isEmpty()) return 0L to 0L
        var total = 0L
        for (f in collected) total += f[0].size
        val totalMs = total * 1000 / 48000
        val threshold = SilenceAnalyzer.thresholdAmp(silenceThresholdDb)
        val minSound = msToSamples(silenceMinSoundMs)
        val first = SilenceAnalyzer.findFirstSound(collected, threshold, minSound)
        val lastEnd = SilenceAnalyzer.findLastSound(collected, threshold, minSound)
        val lead = if (first < 0) 0 else first
        val trail = if (lastEnd < 0) totalMs else (totalMs - lastEnd).coerceAtLeast(0)
        return lead to trail
    }

    /**
     * Decodes [track] from [startMs] (seeking a clone first) into exact-length
     * chunks, up to [capSamples]. The clone is stopped before returning.
     */
    private fun collectFrom(
        track: AudioTrack,
        factory: SiphonFactory,
        startMs: Long,
        capSamples: Int,
    ): List<Array<FloatArray>> {
        val tap = SiphonQueue(maxFrames = 512)
        factory.addTap(tap)
        try {
            val sub = synchronized(lock) { subPlayer } ?: return emptyList()
            val clone = track.makeClone()
            if (startMs > 0) {
                try {
                    clone.position = startMs
                } catch (e: Exception) {
                    log.warn("scan seek failed on guild {}", guildId, e)
                    return emptyList()
                }
            }
            sub.playTrack(clone)
            val frames = mutableListOf<Array<FloatArray>>()
            var collected = 0
            val pumpFrame = newProvideFrame()
            val deadline = System.currentTimeMillis() + 45000
            while (collected < capSamples && System.currentTimeMillis() < deadline && !analysisBroken) {
                if (sub.playingTrack !== clone) break
                if (tap.pendingFrames == 0) {
                    pumpSub(sub, pumpFrame)
                    continue
                }
                while (tap.pendingFrames > 0 && collected < capSamples) {
                    val n = minOf(1920, capSamples - collected)
                    val chunk = Array(2) { FloatArray(n) }
                    val took = tap.take(n, 2, chunk)
                    if (took <= 0) break
                    frames.add(if (took < n) Array(2) { c -> chunk[c].copyOf(took) } else chunk)
                    collected += took
                }
            }
            try {
                sub.stopTrack()
            } catch (e: Exception) {
                log.warn("scan stop failed on guild {}", guildId, e)
            }
            return frames
        } finally {
            factory.removeTap(tap)
        }
    }

    private fun safePosition(track: AudioTrack): Long = try {
        track.position
    } catch (e: Exception) {
        lastPosition
    }

    companion object {
        private val log = LoggerFactory.getLogger(GuildMixer::class.java)
        private const val SAMPLE_RATE = 48000

        /** Lavaplayer's default frame buffer; used when the manager can't say. */
        private const val DEFAULT_FRAME_BUFFER_MS = 5000
        private const val ANALYSIS_PUMP_BURST = 8
        private const val ANALYSIS_PUMP_SLEEP_MS = 10L
        private const val ANALYSIS_IDLE_SLEEP_MS = 20L

        fun msToSamples(ms: Long): Int = ((ms.coerceAtLeast(0) * SAMPLE_RATE) / 1000).toInt().coerceAtLeast(1)
    }
}
