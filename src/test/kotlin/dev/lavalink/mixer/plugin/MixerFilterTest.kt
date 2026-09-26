package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MixerFilterTest {
    private fun mixer(): GuildMixer {
        val manager = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AudioPlayerManager::class.java),
        ) { _, _, _ -> throw UnsupportedOperationException("not needed") } as AudioPlayerManager
        return GuildMixer(123L, manager, MixerConfig())
    }

    @Test
    fun `idle filter passes audio through`() {
        val filter = MixerFilter(mixer())
        val input = arrayOf(floatArrayOf(0.1f, -0.2f, 0.3f), floatArrayOf(-0.1f, 0.2f, -0.3f))
        filter.process(input, 0, 3)
        assertContentEquals(floatArrayOf(0.1f, -0.2f, 0.3f), input[0])
        assertContentEquals(floatArrayOf(-0.1f, 0.2f, -0.3f), input[1])
    }

    @Test
    fun `mixing applies gains per channel`() {
        val m = mixer()
        m.gainsForTest(0.5f, 0.5f)
        m.siphon.push(arrayOf(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f)), 0, 2)
        val filter = MixerFilter(m)
        val input = arrayOf(floatArrayOf(0.2f, 0.2f), floatArrayOf(-0.4f, -0.4f))
        filter.process(input, 0, 2)
        assertContentEquals(floatArrayOf(0.6f, 0.6f), input[0])
        assertContentEquals(floatArrayOf(0.3f, 0.3f), input[1])
    }

    @Test
    fun `output clamps to unit range`() {
        val m = mixer()
        m.gainsForTest(1f, 1f)
        m.siphon.push(arrayOf(floatArrayOf(0.8f), floatArrayOf(-0.8f)), 0, 1)
        val filter = MixerFilter(m)
        val input = arrayOf(floatArrayOf(0.8f), floatArrayOf(-0.8f))
        filter.process(input, 0, 1)
        assertEquals(1f, input[0][0])
        assertEquals(-1f, input[1][0])
    }

    @Test
    fun `empty sub queue with sub gain only scales main`() {
        val m = mixer()
        m.gainsForTest(0.2f, 1f)
        val filter = MixerFilter(m)
        val input = arrayOf(floatArrayOf(0.5f, 0.5f))
        filter.process(input, 0, 2)
        assertContentEquals(floatArrayOf(0.1f, 0.1f), input[0])
    }

    @Test
    fun `main-only gain scales without sub audio`() {
        val m = mixer()
        m.gainsForTest(0.5f, 0f)
        val filter = MixerFilter(m)
        val input = arrayOf(floatArrayOf(0.4f, -0.4f), floatArrayOf(0.2f, -0.2f))
        filter.process(input, 0, 2)
        assertContentEquals(floatArrayOf(0.2f, -0.2f), input[0])
        assertContentEquals(floatArrayOf(0.1f, -0.1f), input[1])
    }
}
