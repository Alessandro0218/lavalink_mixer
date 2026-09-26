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
    private var analysisActive: Boolean = false
    private val trimCache = LinkedHashMap<String, TrimPoints>()
    private var subTrack: AudioTrack? = null
    private var subStartMs: Long = 0L
    private var overlayActive: Boolean = false
    private var xfadeActive: Boolean = false
    private var xfadeHandoffMs: Long = -1L
    /** Position within [subTrack] that has reached the audible output. */
    private var subOutputSamples: Long = 0L
    private var subStartPositionMs: Long = 0L
    private var announceToken: Long = 0L
    private var masking: Boolean = false

    private var lastMainTrack: AudioTrack? = null
    private var lastPosition: Long = 0L

    private val mainGain = GainRamp(1f)
    private val subGain = GainRamp(0f)
    private var xfadeLeft: Int = 0
    private var xfadeTotal: Int = 1

    val siphon = SiphonQueue()
    private val drainFrame = MutableAudioFrame().apply {
        setBuffer(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()))
    }

    val mainListener = object : AudioEventAdapter() {
        override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
            synchronized(lock) {
                tailCutter.reset()
                if (masking) {
                    masking = false
                    return
                }
                // Leading-silence skip: start at content, not file start.
                // Queued tracks already seek in applyLeadLocked; this covers
                // the first track (played via Lavalink REST) when its trim
                // was cached by an earlier preload/analysis.
                if (!xfadeActive && silenceSkipEnabled) {
                    val lead = leadForLocked(track)
                    if (lead > 200 && track.isSeekable) {
                        try {
                            val duration = track.duration
                            track.position = if (duration > 0) {
                                lead.coerceIn(0, (duration - 500).coerceAtLeast(0))
                            } else lead
                        } catch (e: Exception) {
                            log.warn("lead skip seek failed on guild {}", guildId, e)
                        }
                    }
                }
                if (!xfadeActive && fadeInMs > 0) {
                    mainGain.setNow(0f)
                    mainGain.rampTo(1f, msToSamples(fadeInMs))
                }
            }
        }

        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            synchronized(lock) {
                if (endReason != AudioTrackEndReason.FINISHED && endReason != AudioTrackEndReason.LOAD_FAILED) return
                if (xfadeActive) {
                    doHandoffLocked()
                } else {
                    advanceNextLocked()
                }
            }
        }
    }

    private val subListener = object : AudioEventAdapter() {
        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            synchronized(lock) {
                if (overlayActive) finishOverlayLocked()
                else if (xfadeActive) abortXfadeLocked("sub track ended: $endReason")
            }
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: com.sedmelluq.discord.lavaplayer.tools.FriendlyException) {
            synchronized(lock) {
                if (overlayActive) finishOverlayLocked()
                else if (xfadeActive) abortXfadeLocked("sub track exception")
            }
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            synchronized(lock) {
                if (overlayActive) finishOverlayLocked()
                else if (xfadeActive) abortXfadeLocked("sub track stuck")
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

    fun newFilter(): MixerFilter = MixerFilter(this)

    // ---- voice-thread entry points ----

    /** Advances ramps by [samples] and returns the gains to mix with. */
    fun advance(samples: Int): Gains {
        synchronized(lock) {
            if (xfadeActive) {
                xfadeLeft -= samples
                val t = (1f - xfadeLeft.toFloat() / xfadeTotal).coerceIn(0f, 1f)
                // Equal-power curve keeps perceived loudness flat through the overlap.
                val main = cos(t * PI.toFloat() / 2f)
                val sub = sin(t * PI.toFloat() / 2f)
                mainGain.setNow(main)
                subGain.setNow(sub)
            } else {
                mainGain.advance(samples)
                subGain.advance(samples)
            }
            return Gains(mainGain.current, subGain.current)
        }
    }

    fun takeSub(samples: Int, channels: Int, out: Array<FloatArray>) = siphon.take(samples, channels, out)

    /** Counts only PCM that is actually mixed into the Discord output. */
    fun noteSubOutput(samples: Int) {
        if (samples <= 0) return
        synchronized(lock) {
            if (xfadeActive) subOutputSamples += samples
        }
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

    /**
     * Starts/finalizes a transition against the final *audible* sample.
     * A detected trailing silent segment is never played or included in a
     * crossfade: the overlap ends at `duration - trailingSilence`.
     */
    fun poll() {
        synchronized(lock) {
            val audioPlayer = main?.audioPlayer ?: return
            val track = audioPlayer.playingTrack ?: run {
                lastMainTrack = null
                return
            }
            if (track !== lastMainTrack) {
                lastMainTrack = track
                lastPosition = safePosition(track)
                return
            }
            val pos = safePosition(track)
            if (pos < lastPosition - 1500) {
                // User seeked backwards: any armed overlap is stale.
                if (xfadeActive) abortXfadeLocked("seek during crossfade")
                tailCutter.reset()
            }
            lastPosition = pos
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
                        startOverlapLocked(plan)
                    } else if (silenceSkipEnabled && trail > 0 && pos >= contentEnd) {
                        // End exactly at the last audible sample; do not emit the file's tail silence.
                        // Force-promote pending so a slow analysis never plays silence.
                        if (readyNextTrack == null) promoteForcedLocked()
                        advanceNextLocked()
                    } else if (silenceSkipEnabled &&
                        tailCutter.shouldCut(pos, duration, tailConfirmMs, silenceTailScanMs)
                    ) {
                        // Realtime fallback: sustained silence inside the tail window.
                        if (readyNextTrack == null) promoteForcedLocked()
                        advanceNextLocked()
                    } else if (readyNextTrack == null && pendingNextTrack != null && pos >= duration - 500) {
                        // Analysis still running at file end: advance anyway (degraded, no trim).
                        promoteForcedLocked()
                        advanceNextLocked()
                    }
                } else if (silenceSkipEnabled &&
                    tailCutter.shouldCut(pos, -1, tailConfirmMs, silenceTailScanMs)
                ) {
                    // Streams have no pre-analysis tail; realtime is the only signal.
                    if (readyNextTrack == null) promoteForcedLocked()
                    advanceNextLocked()
                }
            } else if (xfadeActive && xfadeHandoffMs >= 0 && pos >= xfadeHandoffMs) {
                // `FINISHED` occurs at the physical file end. Switch at the
                // audible end instead, so detected trailing silence is gone.
                doHandoffLocked()
            } else {
                tailWatch = false
            }
        }
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
        synchronized(lock) {
            if (overlayActive || xfadeActive) throw MixerBusyException("secondary player busy on guild $guildId")
            val sub = ensureSubLocked()
            announceToken++
            subTrack = track
            overlayActive = true
            subStartMs = System.currentTimeMillis()
            siphon.clear()
            sub.playTrack(track)
            mainGain.rampTo(duck.coerceIn(0f, 1f), msToSamples(duckFadeMs))
            subGain.setNow(0f)
            subGain.rampTo(1f, msToSamples(150))
            log.info("overlay '{}' started on guild {}", track.info.title, guildId)
            return track
        }
    }

    fun cancelOverlay(): Boolean = synchronized(lock) {
        if (!overlayActive) return false
        try {
            subPlayer?.stopTrack()
        } finally {
            finishOverlayLocked()
        }
        true
    }

    fun destroy() {
        synchronized(lock) {
            try {
                subPlayer?.destroy()
            } catch (e: Exception) {
                log.warn("sub destroy failed on guild {}", guildId, e)
            } finally {
                subPlayer = null
                pendingNextTrack = null
                readyNextTrack = null
            }
        }
    }

    // ---- internals (all called under lock) ----

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

    private fun startOverlapLocked(plan: CrossfadePlan) {
        val next = readyNextTrack ?: pendingNextTrack ?: return
        readyNextTrack = null
        pendingNextTrack = null
        val sub = ensureSubLocked()
        applyLeadLocked(next)
        subTrack = next
        overlayActive = false
        xfadeActive = true
        xfadeHandoffMs = plan.handoffMs
        subOutputSamples = 0L
        subStartPositionMs = safePosition(next)
        siphon.clear()
        sub.playTrack(next)
        xfadeTotal = msToSamples(plan.durationMs).coerceAtLeast(1)
        xfadeLeft = xfadeTotal
        log.info(
            "crossfade started on guild {} at {}ms; handoff at {}ms ({}ms audible overlap)",
            guildId, plan.startMs, plan.handoffMs, plan.durationMs
        )
    }

    private fun doHandoffLocked() {
        val sub = subTrack
        xfadeActive = false
        xfadeHandoffMs = -1L
        if (sub == null) {
            resetGainsLocked()
            return
        }
        val clone = sub.makeClone()
        val outputPosition = subStartPositionMs + subOutputSamples * 1000L / SAMPLE_RATE
        try {
            if (clone.isSeekable) {
                val duration = clone.duration
                clone.position = if (duration > 0) {
                    outputPosition.coerceIn(0, duration)
                } else {
                    outputPosition.coerceAtLeast(0)
                }
            }
        } catch (e: Exception) {
            log.warn("handoff seek failed on guild {}, starting from 0", guildId, e)
        }
        stopSubLocked()
        siphon.clear()
        subGain.setNow(0f)
        mainGain.setNow(0f)
        mainGain.rampTo(1f, msToSamples(maskMs).coerceAtLeast(1))
        masking = true
        tailCutter.reset()
        try {
            main?.play(clone)
            log.info("crossfade handoff on guild {} at {}ms", guildId, outputPosition)
        } catch (e: Exception) {
            log.warn("handoff play failed on guild {}", guildId, e)
            resetGainsLocked()
        } finally {
            subTrack = null
        }
    }

    private fun abortXfadeLocked(reason: String) {
        log.warn("crossfade aborted on guild {}: {}", guildId, reason)
        xfadeActive = false
        xfadeHandoffMs = -1L
        subTrack = null
        stopSubLocked()
        siphon.clear()
        mainGain.rampTo(1f, msToSamples(duckFadeMs))
        subGain.setNow(0f)
    }

    private fun finishOverlayLocked() {
        overlayActive = false
        subTrack = null
        stopSubLocked()
        siphon.clear()
        mainGain.rampTo(1f, msToSamples(duckFadeMs))
        subGain.setNow(0f)
    }

    private fun stopSubLocked() {
        try {
            subPlayer?.stopTrack()
        } catch (e: Exception) {
            log.warn("sub stop failed on guild {}", guildId, e)
        }
    }

    /** Plays the queued track now, seeking past analyzed leading silence. */
    private fun advanceNextLocked() {
        val next = readyNextTrack ?: pendingNextTrack ?: return
        readyNextTrack = null
        pendingNextTrack = null
        lastMainTrack = null
        tailCutter.reset()
        applyLeadLocked(next)
        try {
            main?.play(next)
            log.debug("gapless advance on guild {}", guildId)
        } catch (e: Exception) {
            log.warn("gapless play failed on guild {}", guildId, e)
        }
    }

    private fun applyLeadLocked(track: AudioTrack) {
        if (!silenceSkipEnabled) return
        val lead = leadForLocked(track)
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
            val deadline = System.currentTimeMillis() + 45000
            while (collected < cap && System.currentTimeMillis() < deadline) {
                if (sub.playingTrack !== clone) break
                if (tap.pendingFrames == 0) {
                    Thread.sleep(20)
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
            val deadline = System.currentTimeMillis() + 45000
            while (collected < capSamples && System.currentTimeMillis() < deadline) {
                if (sub.playingTrack !== clone) break
                if (tap.pendingFrames == 0) {
                    Thread.sleep(20)
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

        fun msToSamples(ms: Long): Int = ((ms.coerceAtLeast(0) * SAMPLE_RATE) / 1000).toInt().coerceAtLeast(1)
    }
}
