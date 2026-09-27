package dev.lavalink.mixer.plugin

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SiphonQueueTest {
    @Test
    fun `push and take roundtrip`() {
        val q = SiphonQueue()
        q.push(arrayOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.3f, 0.4f)), 0, 2)
        val out = Array(2) { FloatArray(2) }
        q.take(2, 2, out)
        assertContentEquals(floatArrayOf(0.1f, 0.2f), out[0])
        assertContentEquals(floatArrayOf(0.3f, 0.4f), out[1])
        assertEquals(0, q.pendingFrames)
    }

    @Test
    fun `partial consumption spans frames`() {
        val q = SiphonQueue()
        q.push(arrayOf(floatArrayOf(1f, 2f)), 0, 2)
        q.push(arrayOf(floatArrayOf(3f, 4f)), 0, 2)
        val out = Array(1) { FloatArray(3) }
        q.take(3, 1, out)
        assertContentEquals(floatArrayOf(1f, 2f, 3f), out[0])
        val rest = Array(1) { FloatArray(1) }
        q.take(1, 1, rest)
        assertContentEquals(floatArrayOf(4f), rest[0])
    }

    @Test
    fun `underflow zero-fills`() {
        val q = SiphonQueue()
        val out = Array(2) { FloatArray(4) { 9f } }
        q.take(4, 2, out)
        assertContentEquals(FloatArray(4), out[0])
        assertContentEquals(FloatArray(4), out[1])
    }

    @Test
    fun `overflow drops oldest`() {
        val q = SiphonQueue(maxFrames = 2)
        q.push(arrayOf(floatArrayOf(1f)), 0, 1)
        q.push(arrayOf(floatArrayOf(2f)), 0, 1)
        q.push(arrayOf(floatArrayOf(3f)), 0, 1)
        assertEquals(2, q.pendingFrames)
        assertTrue(q.droppedFrames >= 1)
        val out = Array(1) { FloatArray(2) }
        q.take(2, 1, out)
        assertContentEquals(floatArrayOf(2f, 3f), out[0])
    }

    @Test
    fun `missing sub channels stay silent`() {
        val q = SiphonQueue()
        q.push(arrayOf(floatArrayOf(0.5f, 0.5f)), 0, 2)
        val out = Array(2) { FloatArray(2) }
        q.take(2, 2, out)
        assertContentEquals(floatArrayOf(0.5f, 0.5f), out[0])
        assertContentEquals(floatArrayOf(0f, 0f), out[1])
    }

    @Test
    fun `clear empties queue`() {
        val q = SiphonQueue()
        q.push(arrayOf(floatArrayOf(1f)), 0, 1)
        q.clear()
        assertEquals(0, q.pendingFrames)
        assertEquals(0, q.pendingSamples)
    }

    @Test
    fun `frame buffer prefill does not drop the head of the sub track`() {
        // Lavaplayer fills the whole frame buffer the instant playTrack()
        // returns, long before the mixer has consumed a single sample. With a
        // frame-based cap sized for "a little slack" (150 frames) every 20ms
        // source lost its first 2 seconds here — the crossfade then played
        // track B from the middle and reported a handoff position that did
        // not match what was actually heard.
        val q = SiphonQueue.forFrameBuffer(5000)
        val chunk = 960 // 20ms at 48kHz — what opus/webm sources emit
        repeat(5000 / 20) { q.push(Array(2) { FloatArray(chunk) }, 0, chunk) }

        assertEquals(0, q.droppedFrames, "sub track opening was dropped before the crossfade heard it")
        assertEquals(0L, q.droppedSamples)
        assertTrue(
            q.pendingSamples >= 4000 * 48,
            "expected most of the 5s prefill retained, got ${q.pendingSamples / 48}ms",
        )
    }

    @Test
    fun `sample accounting survives partial takes`() {
        val q = SiphonQueue(maxSamples = 1000)
        q.push(arrayOf(FloatArray(600)), 0, 600)
        assertEquals(600, q.pendingSamples)
        val out = Array(1) { FloatArray(250) }
        assertEquals(250, q.take(250, 1, out))
        assertEquals(350, q.pendingSamples)
        // Over the cap: the oldest (partially consumed) frame goes first.
        q.push(arrayOf(FloatArray(900)), 0, 900)
        assertEquals(1, q.pendingFrames)
        assertEquals(900, q.pendingSamples)
        assertEquals(1L, q.droppedFrames)
        assertEquals(350L, q.droppedSamples)
    }

    @Test
    fun `unbounded frames still drop at the sample cap`() {
        val q = SiphonQueue(maxSamples = 480)
        repeat(10) { q.push(arrayOf(FloatArray(240)), 0, 240) }
        assertEquals(2, q.pendingFrames)
        assertEquals(480, q.pendingSamples)
        assertEquals(8L, q.droppedFrames)
    }
}

