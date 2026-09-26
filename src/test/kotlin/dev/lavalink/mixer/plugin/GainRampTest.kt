package dev.lavalink.mixer.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GainRampTest {
    @Test
    fun `ramp progresses linearly and settles on target`() {
        val ramp = GainRamp(0f)
        ramp.rampTo(1f, 4)
        ramp.advance(1)
        assertEquals(0.25f, ramp.current, 1e-6f)
        ramp.advance(3)
        assertEquals(1f, ramp.current, 1e-6f)
        assertTrue(ramp.settled)
    }

    @Test
    fun `overshoot clamps to target`() {
        val ramp = GainRamp(0f)
        ramp.rampTo(1f, 10)
        ramp.advance(100)
        assertEquals(1f, ramp.current, 1e-6f)
        assertTrue(ramp.settled)
    }

    @Test
    fun `downward ramp clamps on arrival`() {
        val ramp = GainRamp(1f)
        ramp.rampTo(0.2f, 4)
        ramp.advance(2)
        assertEquals(0.6f, ramp.current, 1e-6f)
        ramp.advance(10)
        assertEquals(0.2f, ramp.current, 1e-6f)
        assertTrue(ramp.settled)
    }

    @Test
    fun `zero-sample ramp applies immediately`() {
        val ramp = GainRamp(0.3f)
        ramp.rampTo(0.9f, 0)
        assertEquals(0.9f, ramp.current, 1e-6f)
        assertTrue(ramp.settled)
    }

    @Test
    fun `setNow pins value and clears motion`() {
        val ramp = GainRamp(0f)
        ramp.rampTo(1f, 100)
        ramp.setNow(0.5f)
        assertEquals(0.5f, ramp.current, 1e-6f)
        assertTrue(ramp.settled)
        ramp.advance(1000)
        assertEquals(0.5f, ramp.current, 1e-6f)
    }
}
