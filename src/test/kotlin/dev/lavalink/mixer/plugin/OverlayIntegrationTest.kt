package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real Lavaplayer decoding end to end: a 440Hz "music" track plays through the
 * mixer filter at the pace Discord would consume it, a 1000Hz "clip" is
 * overlaid, and the frames that come out are measured per frequency.
 */
class OverlayIntegrationTest {
    private val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE

    private class Frame(val music: Double, val clip: Double)

    @Test
    fun `music ducks under the clip, the whole clip is heard, then the music returns`() {
        val manager = DefaultAudioPlayerManager()
        manager.registerSourceManager(LocalAudioSourceManager())
        manager.configuration.outputFormat = format
        manager.frameBufferDuration = 500

        val musicFile = File.createTempFile("mixer-music", ".wav")
        val clipFile = File.createTempFile("mixer-clip", ".wav")
        val loop = Executors.newSingleThreadScheduledExecutor()
        try {
            writeSineWav(musicFile, 440.0, seconds = 20)
            writeSineWav(clipFile, 1000.0, seconds = 1)

            val config = MixerConfig().apply { duckLevel = 0.2f; duckFadeMs = 300 }
            val mixer = GuildMixer(1L, manager, config)
            val player = manager.createPlayer()
            player.setFilterFactory(PcmFilterFactory { _, _, output ->
                mutableListOf<AudioFilter>(mixer.newFilterForTest(output))
            })
            loop.scheduleAtFixedRate({ mixer.tick() }, 0, 20, TimeUnit.MILLISECONDS)

            player.playTrack(load(manager, musicFile))

            val backing = ByteBuffer.allocate(format.maximumChunkSize())
            val buffer = MutableAudioFrame().apply { setBuffer(backing) }
            val frames = mutableListOf<Frame>()
            var overlayStartedAtFrame = -1
            var finishedAtFrame = -1
            val deadline = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < deadline) {
                if (player.provide(buffer)) {
                    frames.add(measure(backing, buffer.dataLength))
                } else {
                    Thread.sleep(5)
                    continue
                }
                if (overlayStartedAtFrame < 0 && frames.size == 75) {
                    mixer.startOverlay(load(manager, clipFile), 0.2f)
                    overlayStartedAtFrame = frames.size
                }
                if (finishedAtFrame < 0 && mixer.lastOverlayResult() != null) finishedAtFrame = frames.size
                if (finishedAtFrame > 0 && frames.size > finishedAtFrame + 100) break
                Thread.sleep(18)
            }
            player.stopTrack()
            mixer.destroy()

            assertTrue(overlayStartedAtFrame > 0 && finishedAtFrame > 0, "overlay never started or never finished")
            assertEquals("finished", mixer.lastOverlayResult()?.reason)

            val baseline = frames.subList(20, 60).map { it.music }.average()
            assertTrue(baseline > 0.05, "music must be audible before the overlay, was $baseline")

            val clipFrames = frames.indices.filter { frames[it].clip > 0.05 }
            val heardMs = clipFrames.size * 20
            assertTrue(heardMs in 900..1300, "the whole 1s clip must be heard, heard ${heardMs}ms")

            val first = clipFrames.first()
            val last = clipFrames.last()
            assertTrue(last - first + 1 <= clipFrames.size + 5, "the clip must be heard in one piece")

            val during = frames.subList(first + 10, last - 10).map { it.music }.average()
            assertTrue(during < baseline * 0.35, "music must be ducked under the clip: $during vs $baseline")
            assertTrue(during > baseline * 0.08, "music is ducked, not muted: $during vs $baseline")

            val before = frames.subList(overlayStartedAtFrame, first).map { it.music }
            assertTrue(before.isNotEmpty(), "the clip starts after the duck begins")
            assertTrue(before.min() < baseline * 0.6, "music must already be falling when the clip starts")

            val after = frames.subList(finishedAtFrame + 40, finishedAtFrame + 90).map { it.music }.average()
            assertTrue(after > baseline * 0.9, "music must come back after the clip: $after vs $baseline")
        } finally {
            loop.shutdownNow()
            manager.shutdown()
            musicFile.delete()
            clipFile.delete()
        }
    }

    private fun measure(backing: ByteBuffer, length: Int): Frame {
        val bb = ByteBuffer.wrap(backing.array(), 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val n = length / 4
        val left = DoubleArray(n)
        for (i in 0 until n) {
            left[i] = bb.getShort(i * 4) / 32768.0
        }
        return Frame(goertzel(left, 440.0), goertzel(left, 1000.0))
    }

    /** Amplitude of the [freq] component of [x] (48kHz). */
    private fun goertzel(x: DoubleArray, freq: Double): Double {
        val w = 2.0 * PI * freq / 48000.0
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s = v + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return 2.0 * sqrt(power.coerceAtLeast(0.0)) / x.size
    }

    private fun load(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
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

    private fun writeSineWav(file: File, freq: Double, seconds: Int) {
        val sampleRate = 48000
        val samples = sampleRate * seconds
        val dataLen = samples * 4
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataLen)
            out.writeBytes("WAVEfmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(2)
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * 4)
            out.writeShortLE(4)
            out.writeShortLE(16)
            out.writeBytes("data")
            out.writeIntLE(dataLen)
            for (i in 0 until samples) {
                val v = (sin(2.0 * PI * freq * i / sampleRate) * 0.5 * 32767).toInt()
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
