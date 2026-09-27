package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
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
import kotlin.test.assertTrue

/**
 * End-to-end guard for the downstream-forward fix.
 *
 * Lavaplayer pipelines data only through build-time downstream references;
 * before the fix the mixer filter dropped every chunk, the frame buffer
 * stayed empty, and the first track always reported TrackStuck after 10s
 * with zero frames. This test plays a real WAV through a chain containing
 * the mixer filter and asserts frames actually flow.
 */
class FirstTrackIntegrationTest {
    @Test
    fun `first track flows through the mixer filter chain`() {
        val manager = DefaultAudioPlayerManager()
        manager.registerSourceManager(com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager())
        // PCM output: same chain protocol as production (opus needs the
        // libopus native, which some dev hosts don't carry).
        val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        manager.configuration.outputFormat = format

        val wav = File.createTempFile("mixer-first-track", ".wav")
        try {
            writeSineWav(wav, seconds = 3)

            val mixer = GuildMixer(1L, manager, MixerConfig())
            val player = manager.createPlayer()

            // Mirror Lavalink's wiring: the mixer is the terminal plugin
            // filter, built with the chain's downstream output.
            player.setFilterFactory(PcmFilterFactory { _, _, output ->
                mutableListOf<AudioFilter>(mixer.newFilter(output))
            })

            val track = loadTrack(manager, wav)
            player.playTrack(track)

            val frame = MutableAudioFrame().apply {
                setBuffer(ByteBuffer.allocate(format.maximumChunkSize()))
            }
            val deadline = System.currentTimeMillis() + 15_000
            var provided = 0
            while (System.currentTimeMillis() < deadline && provided < 20) {
                if (player.provide(frame)) {
                    provided++
                } else {
                    Thread.sleep(10)
                }
            }

            player.stopTrack()
            mixer.destroy()

            assertTrue(provided >= 10, "expected frames through the mixer chain, got $provided")
        } finally {
            wav.delete()
        }
    }

    private fun loadTrack(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
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

    private fun writeSineWav(file: File, seconds: Int) {
        val sampleRate = 48000
        val channels = 2
        val samples = sampleRate * seconds
        val dataLen = samples * channels * 2

        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1) // PCM
            out.writeShortLE(channels)
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * channels * 2)
            out.writeShortLE(channels * 2)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (i in 0 until samples) {
                val v = (sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 16000).toInt()
                out.writeShortLE(v)
                out.writeShortLE(v)
            }
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
