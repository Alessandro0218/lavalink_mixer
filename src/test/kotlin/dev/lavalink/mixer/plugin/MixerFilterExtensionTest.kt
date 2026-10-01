package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The filter factory is only consulted when a track *starts*; Lavaplayer
 * never hot-swaps factories into a track that is already playing. So [MixerFilterExtension.build]
 * must hand back a filter unconditionally and look the guild mixer up per
 * chunk — capturing the instance (or bailing out) at build time is exactly
 * how a crossfade ends up computing gains nobody ever applies.
 */
class MixerFilterExtensionTest {
    private val plugin = MixerPlugin(proxyManager(), MixerConfig())

    @AfterTest
    fun tearDown() {
        plugin.destroy()
    }

    private fun proxyManager(): AudioPlayerManager = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(AudioPlayerManager::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getFrameBufferDuration" -> 5000
            else -> throw UnsupportedOperationException(method.name)
        }
    } as AudioPlayerManager

    private fun config(guildId: String, enabled: Boolean = true): JsonObject =
        buildMap {
            put("guildId", JsonPrimitive(guildId))
            if (!enabled) put("enabled", JsonPrimitive(false))
        }.let { JsonObject(it) }

    private fun extension() = MixerFilterExtension(plugin)

    @Test
    fun `build returns a filter even when the guild mixer does not exist yet`() {
        val extension = extension()
        val data = config("4242")
        assertTrue(extension.isEnabled(data))

        val filter = extension.build(data, StandardAudioDataFormats.DISCORD_PCM_S16_LE, null)
        assertNotNull(filter, "an unknown guild must still bind, not silently disable mixing")

        // Nothing to mix with yet: audio has to pass through untouched.
        val idle = arrayOf(floatArrayOf(0.5f, -0.5f))
        filter.process(idle, 0, 2)
        assertEquals(0.5f, idle[0][0])
        assertEquals(-0.5f, idle[0][1])

        // The mixer appears afterwards (player created later on this socket);
        // the very same filter must start mixing without a rebuild.
        val mixer = plugin.getOrCreate(4242L)
        mixer.gainsForTest(0.5f, 0f)
        val mixed = arrayOf(floatArrayOf(0.4f, -0.4f))
        filter.process(mixed, 0, 2)
        assertEquals(0.2f, mixed[0][0], 1e-6f)
        assertEquals(-0.2f, mixed[0][1], 1e-6f)
        assertTrue(mixer.lastFilterRunForTest() > 0, "heartbeat must tick once the mixer exists")
    }

    @Test
    fun `disabled or malformed config yields no filter`() {
        val extension = extension()
        assertTrue(!extension.isEnabled(config("4242", enabled = false)))
        assertTrue(!extension.isEnabled(JsonObject(mapOf("enabled" to JsonPrimitive(true)))))
        assertEquals(null, extension.build(JsonObject(mapOf("enabled" to JsonPrimitive(true))), null, null))
        assertEquals(null, extension.build(JsonPrimitive("not an object"), null, null))
    }

    @Test
    fun `a filter bound to a removed guild keeps forwarding`() {
        val extension = extension()
        val filter = extension.build(config("77"), null, null)
        assertNotNull(filter)
        plugin.getOrCreate(77L)
        plugin.remove(77L)

        val input = arrayOf(floatArrayOf(0.25f))
        filter.process(input, 0, 1)
        assertEquals(0.25f, input[0][0], "a removed guild must degrade to passthrough, never silence")
    }
}
