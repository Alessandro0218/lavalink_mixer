package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import org.slf4j.LoggerFactory
import kotlin.math.abs

/**
 * Mix point on the main player's filter chain (registered via the
 * "mixer" [MixerFilterExtension]).
 *
 * Lavaplayer pipelines data only through build-time downstream references:
 * the decoder writes into the chain head and each filter must feed its
 * [downstream], ending at the frame buffer. [MixerFilterExtension.build]
 * receives exactly that downstream and hands it here — so after mixing,
 * [process] forwards the same (mutated) array to it. Without the forward
 * the chain is severed: no frame ever reaches the buffer and Lavaplayer
 * reports a healthy track as stuck.
 *
 * output = main * mainGain + sub * subGain, clamped to [-1, 1].
 * Gains are advanced once per process() call (per chunk, not per sample);
 * with ~20ms chunks that is ~50 gain steps/second, free of zipper noise
 * for ramps >= 80ms. When idle (main=1, sub=0) chunks pass through
 * unmodified.
 *
 * Never lets mixer bugs break main output: a mixer exception degrades to
 * passthrough (logged, throttled) while the chunk still goes downstream —
 * the worst case is unmixed audio, never silence.
 */
class MixerFilter(
    private val mixer: GuildMixer,
    private val downstream: FloatPcmAudioFilter?,
) : FloatPcmAudioFilter {
    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        if (length <= 0) return
        try {
            mixInto(input, offset, length)
        } catch (e: Exception) {
            // Leave input (possibly) untouched and throttle the log:
            // this runs ~50x/sec, so only the first occurrence per minute.
            if (now() - lastWarn >= 60_000) {
                lastWarn = now()
                log.warn("mixer filter failed, degrading to passthrough", e)
            }
        }
        // Always forward, even after a mixer failure — stock chain semantics.
        downstream?.process(input, offset, length)
    }

    private fun mixInto(input: Array<FloatArray>, offset: Int, length: Int) {
        // Realtime tail watch runs on the raw main input, before gains.
        mixer.realtimeTailAmp()?.let { thresholdAmp ->
            var peak = 0f
            for (c in input.indices) {
                val channel = input[c]
                val end = offset + length
                for (i in offset until end) {
                    val a = abs(channel[i])
                    if (a > peak) peak = a
                }
            }
            mixer.noteChunk(peak, length, thresholdAmp)
        }

        val gains = mixer.advance(length)
        if (gains.main >= 0.999f && gains.sub <= 0.001f) return // idle passthrough

        if (gains.sub <= 0.001f) {
            // Main-only (fade-in/mask/duck release): scale in place, no sub buffer.
            if (gains.main < 0.999f) {
                for (c in input.indices) {
                    val channel = input[c]
                    val end = offset + length
                    for (i in offset until end) {
                        channel[i] = (channel[i] * gains.main).coerceIn(-1f, 1f)
                    }
                }
            }
            return
        }

        val sub = Array(input.size) { FloatArray(length) }
        val subSamples = mixer.takeSub(length, input.size, sub)
        mixer.noteSubOutput(subSamples)

        for (c in input.indices) {
            val channel = input[c]
            val subChannel = sub[c]
            val end = offset + length
            for (i in offset until end) {
                val mixed = channel[i] * gains.main + subChannel[i - offset] * gains.sub
                channel[i] = mixed.coerceIn(-1f, 1f)
            }
        }
    }

    // Lifecycle events are delivered to every filter in the pipeline by
    // Lavaplayer itself; forwarding them here would double-call downstream.
    override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit

    override fun flush() = Unit

    override fun close() = Unit

    companion object {
        private val log = LoggerFactory.getLogger(MixerFilter::class.java)

        @Volatile
        private var lastWarn: Long = 0L

        private fun now(): Long = System.currentTimeMillis()
    }
}
