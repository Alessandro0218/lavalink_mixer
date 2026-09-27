package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import dev.arbjerg.lavalink.api.AudioFilterExtension
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Exposes the mixer as the "mixer" plugin filter. Enable per guild with a
 * filters op containing:
 *
 *   "pluginFilters": { "mixer": { "guildId": "123456789" } }
 *
 * The guildId key is required because the extension API does not pass player
 * identity to [build]; it binds the created filter to this plugin's
 * per-guild [GuildMixer].
 *
 * [build] always returns a filter and never captures the mixer instance:
 * Lavaplayer only rebuilds the chain when a track *starts*, and it does not
 * hot-swap filter factories into a running track. Looking the mixer up per
 * chunk keeps a guild that is created after [build] (or replaced by a
 * reconnect) working instead of silently passing audio through.
 */
@Service
class MixerFilterExtension(private val plugin: MixerPlugin) : AudioFilterExtension {
    override val name: String = "mixer"

    override fun isEnabled(data: JsonElement): Boolean {
        val obj = data as? JsonObject ?: return false
        if ((obj["enabled"] as? JsonPrimitive)?.booleanOrNull == false) return false
        return guildIdOf(obj) != null
    }

    override fun build(data: JsonElement, format: AudioDataFormat?, output: FloatPcmAudioFilter?): FloatPcmAudioFilter? {
        val obj = data as? JsonObject ?: return null
        val guildId = guildIdOf(obj) ?: return null
        val existing = plugin.find(guildId)
        if (existing == null) {
            log.warn(
                "mixer filter built for guild {} with no GuildMixer yet (no player on this socket?); " +
                    "binding lazily so the track still mixes once the player appears",
                guildId,
            )
        } else {
            log.info("mixer filter built for guild {} -> {}", guildId, describe(existing))
        }
        return MixerFilter(guildId, { plugin.find(guildId) }, output)
    }

    private fun guildIdOf(obj: JsonObject): Long? =
        (obj["guildId"] as? JsonPrimitive)?.content?.toLongOrNull()

    companion object {
        private val log = LoggerFactory.getLogger(MixerFilterExtension::class.java)

        fun describe(mixer: Any): String = mixer.javaClass.simpleName + "@${Integer.toHexString(System.identityHashCode(mixer))}"
    }
}
