package dev.lavalink.mixer.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TailCutDeciderTest {
    private val confirm = 1000L
    private val window = 15000L

    private fun loud(d: TailCutDecider, ms: Long) {
        d.note(0.5f, (ms * 48).toInt(), 0.001f)
    }

    private fun quiet(d: TailCutDecider, ms: Long) {
        d.note(0.0f, (ms * 48).toInt(), 0.001f)
    }

    @Test
    fun `no cut before confirmation`() {
        val d = TailCutDecider()
        loud(d, 100000)
        quiet(d, 500)
        assertFalse(d.shouldCut(200000, 210000, confirm, window))
    }

    @Test
    fun `cuts inside tail window after confirmation`() {
        val d = TailCutDecider()
        loud(d, 100000)
        quiet(d, 1200)
        assertTrue(d.shouldCut(200000, 210000, confirm, window))
    }

    @Test
    fun `no cut mid-track even when silent`() {
        val d = TailCutDecider()
        loud(d, 100000)
        quiet(d, 5000)
        assertFalse(d.shouldCut(60000, 210000, confirm, window))
    }

    @Test
    fun `sound resets the timer`() {
        val d = TailCutDecider()
        loud(d, 100000)
        quiet(d, 900)
        loud(d, 100)
        quiet(d, 900)
        assertFalse(d.shouldCut(200000, 210000, confirm, window))
        assertEquals(43200, d.silentSamples)
    }

    @Test
    fun `early track position never cuts`() {
        val d = TailCutDecider()
        quiet(d, 10000)
        assertFalse(d.shouldCut(3000, 210000, confirm, window))
    }

    @Test
    fun `streams need longer evidence`() {
        val d = TailCutDecider()
        loud(d, 100000)
        quiet(d, 1500)
        assertFalse(d.shouldCut(60000, -1, confirm, window))
        quiet(d, 2000)
        assertTrue(d.shouldCut(60000, -1, confirm, window))
    }
}
