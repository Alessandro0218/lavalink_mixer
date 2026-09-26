package dev.lavalink.mixer.plugin

import dev.arbjerg.lavalink.api.IPlayer
import dev.arbjerg.lavalink.api.ISocketContext
import dev.arbjerg.lavalink.api.PluginEventHandler
import org.springframework.stereotype.Service

/** Tracks guild players so each gets a [GuildMixer] with track listeners attached. */
@Service
class MixerEvents(private val plugin: MixerPlugin) : PluginEventHandler() {
    override fun onNewPlayer(context: ISocketContext, player: IPlayer) {
        plugin.getOrCreate(player.guildId).attachMain(player)
    }

    override fun onDestroyPlayer(context: ISocketContext, player: IPlayer) {
        plugin.remove(player.guildId)
    }
}
