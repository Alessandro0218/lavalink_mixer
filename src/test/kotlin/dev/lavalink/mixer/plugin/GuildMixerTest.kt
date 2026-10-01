package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuildMixerTest {
    private fun mixer(config: MixerConfig = MixerConfig()): GuildMixer {
        val manager = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AudioPlayerManager::class.java),
        ) { _, _, _ -> throw UnsupportedOperationException("not needed") } as AudioPlayerManager
        return GuildMixer(1L, manager, config)
    }

    private fun clip(samples: Int) = arrayOf(FloatArray(samples) { 0.5f }, FloatArray(samples) { 0.5f })

    @Test
    fun `preflight refuses when nothing flows through the filter`() {
        val m = mixer()
        val e = assertFailsWith<MixerUnavailableException> { m.preflight() }
        assertEquals("main_idle", e.code)
    }

    @Test
    fun `preflight passes once the filter has processed a chunk`() {
        val m = mixer()
        m.noteFilterRun()
        m.preflight()
    }

    @Test
    fun `preflight refuses a second overlay`() {
        val m = mixer()
        m.noteFilterRun()
        m.overlayForTest(active = true)
        assertFailsWith<MixerBusyException> { m.preflight() }
    }

    @Test
    fun `an overlay waits for the queued clip audio to play out before ending`() {
        val m = mixer()
        m.noteFilterRun()
        m.overlayForTest(active = true, ended = true)
        m.siphon.push(clip(960), 0, 960)

        m.tick()
        assertTrue(m.isOverlayActive(), "the decoder is done but the clip has not been heard yet")

        m.takeSub(960, 2, clip(960))
        m.tick()
        assertFalse(m.isOverlayActive())
        assertEquals("finished", m.lastOverlayResult()?.reason)
    }

    @Test
    fun `an overlay whose decoder is still running keeps going`() {
        val m = mixer()
        m.noteFilterRun()
        m.overlayForTest(active = true, ended = false)
        m.tick()
        assertTrue(m.isOverlayActive())
        assertNull(m.lastOverlayResult())
    }

    @Test
    fun `an overlay ends when the main chain stops running`() {
        val m = mixer()
        m.overlayForTest(active = true, startedAgoMs = GuildMixer.FILTER_STALE_MS + 500)
        m.tick()
        assertFalse(m.isOverlayActive())
        assertEquals("main_stopped", m.lastOverlayResult()?.reason)
    }

    @Test
    fun `an overlay is capped so the music is never ducked forever`() {
        val m = mixer(MixerConfig().apply { maxOverlayMs = 100 })
        m.noteFilterRun()
        m.overlayForTest(active = true, startedAgoMs = 200)
        m.tick()
        assertEquals("timeout", m.lastOverlayResult()?.reason)
        assertFalse(m.isOverlayActive())
        assertNotNull(m.lastOverlayResult())
    }

    @Test
    fun `finishing with a stalled chain leaves the next track at full gain`() {
        val m = mixer()
        m.gainsForTest(0.2f, 1f)
        m.overlayForTest(active = true, startedAgoMs = GuildMixer.FILTER_STALE_MS + 500)
        m.tick()
        val gains = m.advance(960)
        assertEquals(1f, gains.main)
        assertEquals(0f, gains.sub)
    }

    @Test
    fun `cancel reports whether an overlay was running`() {
        val m = mixer()
        assertFalse(m.cancelOverlay())
        m.noteFilterRun()
        m.overlayForTest(active = true)
        assertTrue(m.cancelOverlay())
        assertEquals("cancelled", m.lastOverlayResult()?.reason)
    }
}
