package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import kotlin.test.Test

class ChainDiagnosticTest {
    private fun format() = StandardAudioDataFormats.DISCORD_PCM_S16_LE

    private fun pump(player: com.sedmelluq.discord.lavaplayer.player.AudioPlayer, label: String): Int {
        val frame = MutableAudioFrame().apply {
            setBuffer(ByteBuffer.allocate(format().maximumChunkSize()))
        }
        val deadline = System.currentTimeMillis() + 10_000
        var provided = 0
        while (System.currentTimeMillis() < deadline && provided < 10) {
            if (player.provide(frame)) {
                provided++
            } else {
                Thread.sleep(10)
            }
        }
        println("$label: playingTrack=${player.playingTrack != null} provided=$provided")
        player.stopTrack()
        return provided
    }

    private fun manager(): DefaultAudioPlayerManager {
        val m = DefaultAudioPlayerManager()
        m.registerSourceManager(com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager())
        m.configuration.outputFormat = format()
        return m
    }

    private fun track(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                future.complete(track)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                future.complete(playlist.selectedTrack ?: playlist.tracks.first())
            }

            override fun noMatches() {
                future.completeExceptionally(NoMatchException(file.absolutePath))
            }

            override fun loadFailed(exception: FriendlyException) {
                future.completeExceptionally(exception)
            }
        })
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun wav(): File {
        val file = File.createTempFile("chain-diag", ".wav")
        val sampleRate = 48000
        val samples = sampleRate * 3
        val dataLen = samples * 2 * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(2)
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * 2 * 2)
            out.writeShortLE(4)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (i in 0 until samples) {
                val v = (sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 16000).toInt()
                out.writeShortLE(v)
                out.writeShortLE(v)
            }
        }
        return file
    }

    @Test
    fun `baseline - no filter factory`() {
        val manager = manager()
        val file = wav()
        try {
            val player = manager.createPlayer()
            player.playTrack(track(manager, file))
            pump(player, "baseline")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `passthrough filter factory`() {
        val manager = manager()
        val file = wav()
        try {
            val player = manager.createPlayer()
            player.setFilterFactory(PcmFilterFactory { _, _, output ->
                mutableListOf<AudioFilter>(object : FloatPcmAudioFilter {
                    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
                        output.process(input, offset, length)
                    }

                    override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
                    override fun flush() = Unit
                    override fun close() = Unit
                })
            })
            player.playTrack(track(manager, file))
            pump(player, "passthrough")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `mixer filter factory`() {
        val manager = manager()
        val file = wav()
        try {
            val mixer = GuildMixer(1L, manager, MixerConfig())
            val player = manager.createPlayer()
            player.setFilterFactory(PcmFilterFactory { _, _, output ->
                mutableListOf<AudioFilter>(mixer.newFilter(output))
            })
            player.playTrack(track(manager, file))
            pump(player, "mixer")
            mixer.destroy()
        } finally {
            file.delete()
        }
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
