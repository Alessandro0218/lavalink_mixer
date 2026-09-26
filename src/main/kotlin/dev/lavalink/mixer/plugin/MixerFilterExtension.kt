package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
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
 * per-guild [GuildMixer]. Unknown guilds yield null (passthrough).
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
        val mixer = plugin.find(guildId)
        if (mixer == null) {
            log.warn("mixer filter enabled for unknown guild {}, check REST state first", guildId)
            return null
        }
        return mixer.newFilter()
    }

    private fun guildIdOf(obj: JsonObject): Long? =
        (obj["guildId"] as? JsonPrimitive)?.content?.toLongOrNull()

    companion object {
        private val log = LoggerFactory.getLogger(MixerFilterExtension::class.java)
    }
}
