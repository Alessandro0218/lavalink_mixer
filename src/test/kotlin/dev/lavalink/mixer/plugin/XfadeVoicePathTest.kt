package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the lock-free voice path: [GuildMixer.advance] and
 * [GuildMixer.noteSubOutput] must follow the equal-power curve and stay
 * race-free under concurrent control-plane writes, otherwise a stalled
 * voice thread surfaces as a false TrackStuck on a healthy main track.
 */
class XfadeVoicePathTest {
    private fun mixer(): GuildMixer {
        val manager = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AudioPlayerManager::class.java),
        ) { _, _, _ -> throw UnsupportedOperationException("not needed") } as AudioPlayerManager
        return GuildMixer(123L, manager, MixerConfig())
    }

    @Test
    fun `advance follows equal power curve`() {
        val m = mixer()
        val total = 48_000
        m.beginXfadeForTest(total)

        var gains = m.advance(24_000)
        val t = 0.5f
        assertEquals(cos(t * PI.toFloat() / 2f), gains.main, 1e-5f)
        assertEquals(sin(t * PI.toFloat() / 2f), gains.sub, 1e-5f)

        gains = m.advance(24_000)
        assertEquals(0f, gains.main, 1e-5f)
        assertEquals(1f, gains.sub, 1e-5f)

        // Overshooting clamps instead of wrapping.
        gains = m.advance(1_000)
        assertEquals(0f, gains.main, 1e-5f)
        assertEquals(1f, gains.sub, 1e-5f)
    }

    @Test
    fun `sub output counted only while overlap active`() {
        val m = mixer()
        m.noteSubOutput(100)
        assertEquals(0L, m.subOutputSamplesForTest())

        m.beginXfadeForTest(48_000)
        m.noteSubOutput(100)
        m.noteSubOutput(0)
        m.noteSubOutput(-5)
        assertEquals(100L, m.subOutputSamplesForTest())

        m.endXfadeForTest()
        m.noteSubOutput(100)
        assertEquals(100L, m.subOutputSamplesForTest())
    }

    @Test
    fun `concurrent advance and control writes stay consistent`() {
        val m = mixer()
        m.beginXfadeForTest(48_000)
        val threads = 8
        val latch = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { i ->
            pool.execute {
                try {
                    repeat(5_000) { n ->
                        if ((n + i) % 997 == 0) m.beginXfadeForTest(48_000)
                        val g = m.advance(96)
                        // cos(pi/2) is -4.37e-8 in float; downstream clamps to [-1, 1].
                        check(g.main in -1e-6f..1.000001f && g.sub in -1e-6f..1.000001f) {
                            "gains out of range: $g"
                        }
                        m.noteSubOutput(96)
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "worker threads did not finish")
        pool.shutdownNow()
        assertTrue(failures.isEmpty(), "voice path threw: ${failures.firstOrNull()}")
        assertTrue(m.subOutputSamplesForTest() >= 0L)
    }
}
