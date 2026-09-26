package dev.lavalink.mixer.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrimTransitionTest {
    @Test
    fun `trailing silence moves the crossfade handoff to content end`() {
        val contentEnd = TrimTransition.contentEnd(durationMs = 300_000, trailingSilenceMs = 5_000)
        val plan = TrimTransition.crossfade(contentEnd, requestedMs = 5_000)!!
        assertEquals(295_000, plan.handoffMs)
        assertEquals(290_000, plan.startMs)
        assertEquals(5_000, plan.durationMs)
    }

    @Test
    fun `short content uses its entire audible duration for crossfade`() {
        val plan = TrimTransition.crossfade(contentEndMs = 2_000, requestedMs = 5_000)!!
        assertEquals(0, plan.startMs)
        assertEquals(2_000, plan.handoffMs)
        assertEquals(2_000, plan.durationMs)
    }

    @Test
    fun `invalid or absent content has no plan`() {
        assertEquals(0, TrimTransition.contentEnd(1_000, 2_000))
        assertNull(TrimTransition.crossfade(0, 5_000))
    }
}
