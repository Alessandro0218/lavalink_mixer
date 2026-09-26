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
    }
}
