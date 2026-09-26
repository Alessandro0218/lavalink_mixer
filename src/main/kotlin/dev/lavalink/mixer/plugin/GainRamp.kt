package dev.lavalink.mixer.plugin

/**
 * Per-sample-rate gain ramp with a fixed target. [advance] moves [current]
 * towards [target] by [step] per sample and clamps on arrival.
 *
 * Fields are volatile so the voice thread (advancing) and control threads
 * (retargeting) can share one instance without locking; tiny races only
 * shift a ramp by a sample.
 */
class GainRamp(
    @Volatile var current: Float = 1f,
    @Volatile var target: Float = 1f,
    @Volatile var step: Float = 0f,
) {
    fun setNow(value: Float) {
        current = value
        target = value
        step = 0f
    }

    fun rampTo(target: Float, samples: Int) {
        this.target = target
        if (samples <= 0 || current == target) {
            current = target
            step = 0f
        } else {
            step = (target - current) / samples
        }
    }

    fun advance(samples: Int) {
        val s = step
        if (s == 0f) return
        val next = current + s * samples
        if ((s > 0f && next >= target) || (s < 0f && next <= target)) {
            current = target
            step = 0f
        } else {
            current = next
        }
    }

    val settled: Boolean get() = step == 0f
}
