package dev.lavalink.mixer.plugin

import dev.arbjerg.lavalink.api.IPlayer
import dev.arbjerg.lavalink.api.ISocketContext
import dev.arbjerg.lavalink.api.PluginEventHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Tracks guild players so each gets a [GuildMixer] with track listeners attached. */
@Service
class MixerEvents(private val plugin: MixerPlugin) : PluginEventHandler() {
    override fun onNewPlayer(context: ISocketContext, player: IPlayer) {
        val mixer = plugin.getOrCreate(player.guildId)
        log.info(
            "player created for guild {} -> mixer {} (filter build logs the same identity, " +
                "so mismatched hashes mean the filter is bound to a different instance)",
            player.guildId, MixerFilterExtension.describe(mixer),
        )
        mixer.attachMain(player)
    }

    override fun onDestroyPlayer(context: ISocketContext, player: IPlayer) {
        val mixer = plugin.find(player.guildId)
        log.info(
            "player destroyed for guild {} (mixer {})",
            player.guildId, mixer?.let { MixerFilterExtension.describe(it) } ?: "already gone",
        )
        plugin.remove(player.guildId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MixerEvents::class.java)
    }
}
