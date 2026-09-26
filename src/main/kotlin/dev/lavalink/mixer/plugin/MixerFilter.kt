package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import kotlin.math.abs

/**
 * Terminal mix point on the main player's filter chain (registered via the
 * "mixer" [MixerFilterExtension], so it runs after volume/EQ, pre-encode).
 *
 * output = main * mainGain + sub * subGain, clamped to [-1, 1].
 * Gains are advanced once per process() call (per chunk, not per sample);
 * with ~20ms chunks that is ~50 gain steps/second, free of zipper noise
 * for ramps >= 80ms. When idle (main=1, sub=0) this is a pure passthrough.
 */
class MixerFilter(private val mixer: GuildMixer) : FloatPcmAudioFilter {
    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        if (length <= 0) return
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
        mixer.takeSub(length, input.size, sub)

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

    // Main-chain seeks don't invalidate the secondary stream; gains keep ramping.
    override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit

    override fun flush() = Unit

    override fun close() = Unit
}
