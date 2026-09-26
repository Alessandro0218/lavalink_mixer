package dev.lavalink.mixer.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SilenceAnalyzerTest {
    private val threshold = SilenceAnalyzer.thresholdAmp(-60f)
    private val minSound = 4800 // 100ms @ 48kHz

    private fun silent(samples: Int, channels: Int = 2) = Array(channels) { FloatArray(samples) }
    private fun tone(samples: Int, amp: Float = 0.5f, channels: Int = 2) = Array(channels) { FloatArray(samples) { amp } }

    @Test
    fun `leading silence offset detected`() {
        val frames = listOf(silent(48000), tone(48000))
        val lead = SilenceAnalyzer.findFirstSound(frames, threshold, minSound)
        assertEquals(1000L, lead)
    }

    @Test
    fun `short blip below minimum ignored`() {
        val frames = listOf(silent(48000), tone(1000), silent(48000))
        val lead = SilenceAnalyzer.findFirstSound(frames, threshold, minSound)
        assertEquals(-1L, lead)
    }

    @Test
    fun `all silent returns none`() {
        val frames = listOf(silent(48000), silent(48000))
        assertEquals(-1L, SilenceAnalyzer.findFirstSound(frames, threshold, minSound))
        assertEquals(-1L, SilenceAnalyzer.findLastSound(frames, threshold, minSound))
    }

    @Test
    fun `below-threshold signal ignored`() {
        val quiet = Array(2) { FloatArray(96000) { 0.0001f } }
        assertEquals(-1L, SilenceAnalyzer.findFirstSound(listOf(quiet), threshold, minSound))
    }

    @Test
    fun `trailing silence end detected`() {
        val frames = listOf(tone(48000), silent(24000))
        val lastEnd = SilenceAnalyzer.findLastSound(frames, threshold, minSound)
        assertEquals(1000L, lastEnd)
    }

    @Test
    fun `sound running to buffer end reports full length`() {
        val frames = listOf(silent(24000), tone(48000))
        val lastEnd = SilenceAnalyzer.findLastSound(frames, threshold, minSound)
        // 72000 samples total -> 1500ms
        assertEquals(1500L, lastEnd)
    }

    @Test
    fun `chunked frames scan seamlessly`() {
        val frames = List(96) { silent(1000) } + List(96) { tone(1000) }
        val lead = SilenceAnalyzer.findFirstSound(frames, threshold, minSound)
        // 96000 silent samples -> 2000ms
        assertEquals(2000L, lead)
        assertTrue(lead >= 0)
    }

    @Test
    fun `streaming scanner matches batch scan`() {
        val scanner = SilenceAnalyzer.LeadScanner(threshold, minSound)
        var result: Long? = null
        repeat(50) {
            result = scanner.feed(silent(1000), 1000)
        }
        assertEquals(null, result)
        repeat(50) {
            result = scanner.feed(tone(1000), 1000)
        }
        assertEquals(1041L, result)
    }

    @Test
    fun `streaming scanner early-exits on confirmation`() {
        val scanner = SilenceAnalyzer.LeadScanner(threshold, minSound)
        scanner.feed(silent(48000), 48000)
        assertEquals(null, scanner.resultMs)
        // 4800 loud samples confirm; the rest of the chunk is untouched logically.
        scanner.feed(tone(48000), 48000)
        assertEquals(1000L, scanner.resultMs)
        assertEquals(52800L, scanner.samplesSeen)
    }
}
