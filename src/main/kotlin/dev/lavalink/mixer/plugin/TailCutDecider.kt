package dev.lavalink.mixer.plugin

/**
 * Realtime trailing-silence cut decision. The mixer filter reports per-chunk
 * peaks; once silence has persisted for [confirmMs] AND the position is
 * inside the tail window, the track may end early.
 *
 * Unlike pre-analysis this needs no extra decode pass, so it also covers
 * streams — at the price of hearing [confirmMs] of silence first.
 */
class TailCutDecider {
    @Volatile var silentSamples: Int = 0

    fun note(peak: Float, samples: Int, thresholdAmp: Float) {
        silentSamples = if (peak >= thresholdAmp) 0 else silentSamples + samples
    }

    fun reset() {
        silentSamples = 0
    }

    fun sustainedMs(sampleRate: Int = 48000): Long = silentSamples * 1000L / sampleRate

    fun shouldCut(posMs: Long, durationMs: Long, confirmMs: Long, tailWindowMs: Long): Boolean {
        if (sustainedMs() < confirmMs) return false
        if (durationMs > 0 && durationMs < Long.MAX_VALUE / 2) {
            if (posMs < 5000) return false
            return durationMs - posMs <= tailWindowMs
        }
        // Stream: no position reference — demand longer evidence.
        return sustainedMs() >= maxOf(confirmMs, 3000)
    }
}
