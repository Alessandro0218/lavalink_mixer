package dev.lavalink.mixer.plugin

import kotlin.math.abs
import kotlin.math.pow

data class TrimPoints(val leadMs: Long, val trailMs: Long) {
    companion object {
        val NONE = TrimPoints(0, 0)
    }
}

/**
 * Pure PCM silence analysis. Operates on decoded float chunks
 * (each `Array<FloatArray>` = one channel set, any length).
 *
 * A sample counts as "sound" when any channel reaches [thresholdAmp].
 * Runs shorter than [minSoundSamples] are ignored so clicks/pops don't
 * register as content.
 */
object SilenceAnalyzer {
    fun thresholdAmp(db: Float): Float = 10f.pow(db / 20f)

    /**
     * ms offset of the first sustained sound from the buffer start,
     * or -1 when the whole buffer is silent.
     */
    fun findFirstSound(
        frames: List<Array<FloatArray>>,
        thresholdAmp: Float,
        minSoundSamples: Int,
        sampleRate: Int = 48000,
    ): Long {
        var runStart = -1L
        var runLen = 0
        var total = 0L
        for (frame in frames) {
            val len = frame[0].size
            for (i in 0 until len) {
                if (isSound(frame, i, thresholdAmp)) {
                    if (runStart < 0) runStart = total
                    runLen++
                    if (runLen >= minSoundSamples) return runStart * 1000 / sampleRate
                } else {
                    runStart = -1
                    runLen = 0
                }
                total++
            }
        }
        return -1
    }

    /**
     * ms offset (from the buffer start) of the END of the last sustained
     * sound, or -1 when the buffer holds no sound at all.
     */
    fun findLastSound(
        frames: List<Array<FloatArray>>,
        thresholdAmp: Float,
        minSoundSamples: Int,
        sampleRate: Int = 48000,
    ): Long {
        var runLen = 0
        var runEnd = -1L // exclusive end offset of the run going backwards
        var idx = 0L
        for (f in frames) idx += f[0].size
        idx--
        for (fi in frames.indices.reversed()) {
            val frame = frames[fi]
            for (i in frame[0].size - 1 downTo 0) {
                if (isSound(frame, i, thresholdAmp)) {
                    if (runLen == 0) runEnd = idx + 1
                    runLen++
                } else {
                    if (runLen >= minSoundSamples) return runEnd * 1000 / sampleRate
                    runLen = 0
                    runEnd = -1
                }
                idx--
            }
        }
        if (runLen >= minSoundSamples) return runEnd * 1000 / sampleRate
        return -1
    }

    private fun isSound(frame: Array<FloatArray>, i: Int, thresholdAmp: Float): Boolean {
        for (c in frame.indices) {
            if (abs(frame[c][i]) >= thresholdAmp) return true
        }
        return false
    }

    /**
     * Streaming version of [findFirstSound]: feed decoded chunks as they
     * arrive and stop the moment the lead is confirmed, so analysis decodes
     * ~1-2s of a typical track instead of the whole scan window.
     */
    class LeadScanner(
        private val thresholdAmp: Float,
        private val minSoundSamples: Int,
        private val sampleRate: Int = 48000,
    ) {
        private var runStart = -1L
        private var runLen = 0
        private var total = 0L

        /** Confirmed lead offset in ms, or null while still scanning. */
        var resultMs: Long? = null
            private set

        /** Feeds one chunk ([length] valid samples); returns [resultMs] when confirmed. */
        fun feed(chunk: Array<FloatArray>, length: Int): Long? {
            resultMs?.let { return it }
            for (i in 0 until length) {
                total++
                var loud = false
                for (c in chunk.indices) {
                    if (abs(chunk[c][i]) >= thresholdAmp) {
                        loud = true
                        break
                    }
                }
                if (loud) {
                    if (runStart < 0) runStart = total - 1
                    runLen++
                    if (runLen >= minSoundSamples) {
                        resultMs = runStart * 1000 / sampleRate
                        return resultMs
                    }
                } else {
                    runStart = -1
                    runLen = 0
                }
            }
            return null
        }

        val samplesSeen: Long get() = total
    }
}
