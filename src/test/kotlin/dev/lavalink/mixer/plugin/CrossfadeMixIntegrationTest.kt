package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.IPlayer
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end guard for the crossfade itself.
 *
 * The failure this pins down is the one users actually report: the outgoing
 * track plays to the end, and the incoming one only starts afterwards — from
 * zero — while the log still says a crossfade ran. The overlap either never
 * reached the audio (no mixer filter in the running track's chain) or the
 * secondary stream was thrown away before anyone heard it. Both show up as
 * "no window of the output where both tones are audible at once".
 */
class CrossfadeMixIntegrationTest {
    private val sampleRate = 48000
    private val format = StandardAudioDataFormats.DISCORD_PCM_S16_LE

    private class RecordingSink {
        private val lock = Any()
        private val chunks = mutableListOf<FloatArray>()

        fun add(chunk: FloatArray) = synchronized(lock) { chunks.add(chunk) }

        val size: Int get() = synchronized(lock) { chunks.sumOf { it.size } }

        fun snapshot(): FloatArray = synchronized(lock) {
            var total = 0
            for (c in chunks) total += c.size
            FloatArray(total).also { out ->
                var i = 0
                for (c in chunks) {
                    c.copyInto(out, i)
                    i += c.size
                }
            }
        }
    }

    /** Sits between the mixer filter and the frame buffer: records what is heard. */
    private class RecordingFilter(
        private val sink: RecordingSink,
        private val downstream: FloatPcmAudioFilter?,
    ) : FloatPcmAudioFilter {
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
            if (length > 0 && input.isNotEmpty()) {
                val copy = FloatArray(length)
                input[0].copyInto(copy, 0, offset, offset + length)
                sink.add(copy)
            }
            downstream?.process(input, offset, length)
        }

        override fun seekPerformed(requestedTime: Long, providedTime: Long) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    @Test
    fun `overlapping tracks are blended into one output, not played back to back`() {
        val manager = manager()
        val outgoing = toneFile("mixer-xfade-out", 440.0, 10)
        val incoming = toneFile("mixer-xfade-in", 880.0, 10)
        try {
            val config = MixerConfig().apply {
                crossfadeEnabled = true
                crossfadeMs = 3000
                fadeInMs = 0
                maskMs = 0
                silenceSkipEnabled = false
            }
            val mixer = GuildMixer(1L, manager, config)
            val player = manager.createPlayer()
            val sink = RecordingSink()
            player.setFilterFactory(PcmFilterFactory { _, _, output ->
                mutableListOf(mixer.newFilter(RecordingFilter(sink, output)))
            })
            mixer.attachMain(iPlayer(1L, player))

            val trackA = loadTrack(manager, outgoing)
            val trackB = loadTrack(manager, incoming)
            player.playTrack(trackA)
            mixer.queueNext(CompletableFuture.completedFuture(trackB))

            var sawOverlap = false
            var handoffSample = -1
            pumpUntil(player, mixer) {
                val size = sink.size
                if (mixer.isXfadeActive()) sawOverlap = true
                else if (sawOverlap && handoffSample < 0) handoffSample = size
                // Keep going a little past the handoff so the output also
                // contains settled "incoming only" windows to compare against.
                (handoffSample >= 0 && size - handoffSample > 0.8 * sampleRate) ||
                    size * 1000L / sampleRate > 15_000
            }

            val recorded = sink.snapshot()
            val recordedMs = recorded.size * 1000L / sampleRate

            assertTrue(sawOverlap, "the crossfade never armed: recorded ${recordedMs}ms and poll() never planned an overlap")
            assertTrue(
                mixer.filterRunsForTest() > 0,
                "the mixer filter never processed a sample — the filters op did not reach this track",
            )
            assertTrue(
                !mixer.filterMissingForTest(),
                "the heartbeat probe thinks the running track has no mixer filter",
            )
            assertTrue(mixer.siphon.droppedFrames == 0L, "the sub track's opening was dropped: ${mixer.siphon.droppedFrames} frames")
            assertTrue(player.playingTrack !== trackA, "the outgoing track was never handed off")

            // 10s of track A; the overlap is its final 3000ms.
            assertTrue(recordedMs >= 9_500, "recorded only ${recordedMs}ms of output")
            assertBlend(recorded)
        } finally {
            outgoing.delete()
            incoming.delete()
        }
    }

    /** A hard cut is a single straddling window; a real crossfade is sustained. */
    private fun assertBlend(recorded: FloatArray) {
        val window = sampleRate / 4 // 250ms
        val powers = mutableListOf<Triple<Double, Double, Int>>()
        var i = 0
        while (i + window <= recorded.size) {
            powers.add(
                Triple(
                    goertzelPower(recorded, i, i + window, 440.0),
                    goertzelPower(recorded, i, i + window, 880.0),
                    i,
                ),
            )
            i += window
        }
        assertTrue(powers.size >= 20, "not enough analysis windows (${powers.size})")

        val maxA = powers.maxOf { it.first }
        val maxB = powers.maxOf { it.second }
        assertTrue(maxA > 1e-6, "the outgoing 440Hz tone never appears in the output")
        assertTrue(maxB > 1e-6, "the incoming 880Hz tone never appears in the output")

        val outgoingOnly = powers.count { it.first > 0.5 * maxA && it.second < 0.05 * maxB }
        val incomingOnly = powers.count { it.second > 0.5 * maxB && it.first < 0.05 * maxA }
        assertTrue(outgoingOnly >= 2, "no window with only the outgoing tone ($outgoingOnly)")
        assertTrue(incomingOnly >= 2, "no window with only the incoming tone ($incomingOnly)")

        val blended = powers.count { it.first > 0.15 * maxA && it.second > 0.15 * maxB }
        assertTrue(
            blended >= 4,
            "expected ~${3000 / 250} windows where both tones are audible at once, found $blended — " +
                "the tracks were sequenced, not crossfaded",
        )
    }

    /**
     * Lavaplayer never rebuilds the chain of a track that is already playing,
     * so if the client sends `play` first the mixer filter simply is not
     * there. The heartbeat probe has to notice instead of letting the
     * crossfade run against nothing.
     */
    @Test
    fun `a track started without the mixer filter is reported`() {
        val manager = manager()
        val file = toneFile("mixer-no-filter", 440.0, 4)
        try {
            val mixer = GuildMixer(9L, manager, MixerConfig().apply { fadeInMs = 0 })
            val player = manager.createPlayer()
            // Deliberately no setFilterFactory: this is the late-filters client.
            mixer.attachMain(iPlayer(9L, player))
            player.playTrack(loadTrack(manager, file))

            pumpFrames(player, mixer, targetMs = 2_500)

            assertTrue(mixer.filterRunsForTest() == 0L, "no filter was attached, so nothing should tick")
            assertTrue(
                mixer.filterMissingForTest(),
                "the missing-filter probe stayed silent while 2.5s of audio decoded",
            )
        } finally {
            file.delete()
        }
    }

    /**
     * Analysis never calls drain() — that runs on the mixer-loop — and the
     * frame buffer fills once then blocks until somebody provides. Without
     * pumping inside the scan it collects one buffer's worth of audio and
     * then waits out its 45s deadline, so every preload with silence skip
     * enabled stalls the crossfade queue for three quarters of a minute.
     */
    @Test
    fun `silence analysis finishes instead of waiting out its deadline`() {
        val manager = manager()
        val file = toneFile("mixer-analysis", 440.0, 10)
        try {
            val mixer = GuildMixer(5L, manager, MixerConfig().apply {
                silenceSkipEnabled = true
                silenceHeadScanMs = 3000
                silenceTailScanMs = 4000
            })
            val track = loadTrack(manager, file)
            val started = System.currentTimeMillis()
            mixer.analyzeBlocking(track)
            val elapsed = System.currentTimeMillis() - started

            assertTrue(elapsed < 20_000, "analysis took ${elapsed}ms — it stalled on an empty tap")
            val trim = mixer.trimForTest(track.identifier)
            assertNotNull(trim, "analysis finished without caching a trim")
            assertTrue(trim.leadMs <= 500, "lead should be ~0 for a tone that starts immediately, was ${trim.leadMs}ms")

            // Cached, not merely computed: a repeat run answers from the cache.
            val rerun = System.currentTimeMillis()
            mixer.analyzeBlocking(track)
            assertTrue(System.currentTimeMillis() - rerun < 1_000, "a cached trim should not be re-decoded")
            mixer.destroy()
        } finally {
            file.delete()
        }
    }

    // ---- harness ----

    /**
     * Mirrors the production loop: one drain per decoded frame, poll at 100ms
     * of audio, and - crucially - take frames at the rate the voice connection
     * does. Unpaced, the loop drains the frame buffer faster than the decoder
     * refills it, so the decoder runs flat out in bursts and crosses the
     * crossfade window between two polls. That is not what Lavalink does: its
     * audio thread pulls one frame every 20ms, the buffer stays full, and the
     * decode side advances at playback rate.
     */
    private fun pumpUntil(player: AudioPlayer, mixer: GuildMixer, stop: () -> Boolean) {
        val frame = MutableAudioFrame().apply { setBuffer(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_PCM_S16_LE.maximumChunkSize())) }
        val deadline = System.currentTimeMillis() + 60_000
        val started = System.nanoTime()
        var frames = 0L
        while (System.currentTimeMillis() < deadline) {
            if (player.provide(frame)) {
                frames++
                mixer.drain()
                if (frames % 5L == 0L) mixer.poll()
                pace(started, frames)
            } else {
                mixer.poll()
                Thread.sleep(1)
            }
            if (stop()) return
        }
    }

    /** Sleeps until [frames] 20ms frames have elapsed since [started]. */
    private fun pace(started: Long, frames: Long) {
        val due = started + frames * 20_000_000L
        val remaining = due - System.nanoTime()
        if (remaining <= 0L) return
        try {
            Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun pumpFrames(player: AudioPlayer, mixer: GuildMixer, targetMs: Long) {
        val frame = MutableAudioFrame().apply { setBuffer(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_PCM_S16_LE.maximumChunkSize())) }
        val deadline = System.currentTimeMillis() + 60_000
        val started = System.nanoTime()
        var frames = 0L
        val targetFrames = targetMs / 20L
        while (frames < targetFrames && System.currentTimeMillis() < deadline) {
            if (player.provide(frame)) {
                frames++
                mixer.drain()
                if (frames % 5L == 0L) mixer.poll()
                pace(started, frames)
            } else {
                mixer.poll()
                Thread.sleep(1)
            }
        }
        assertTrue(frames >= targetFrames, "only decoded ${frames * 20}ms of ${targetMs}ms")
        player.stopTrack()
    }

    private fun manager(): DefaultAudioPlayerManager {
        val m = DefaultAudioPlayerManager()
        m.registerSourceManager(com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager())
        m.configuration.outputFormat = format
        return m
    }

    private fun iPlayer(guildId: Long, ap: AudioPlayer): IPlayer = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(IPlayer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAudioPlayer" -> ap
            "getGuildId" -> guildId
            "getTrack" -> ap.playingTrack
            "isPlaying" -> ap.playingTrack != null
            "play" -> {
                ap.playTrack(args!![0] as AudioTrack)
                null
            }
            "stop" -> {
                ap.stopTrack()
                null
            }
            else -> null
        }
    } as IPlayer

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

    private fun toneFile(prefix: String, frequency: Double, seconds: Int): File {
        val file = File.createTempFile(prefix, ".wav")
        val samples = sampleRate * seconds
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
                val v = (sin(2.0 * PI * frequency * i / sampleRate) * 16000).toInt()
                out.writeShortLE(v)
                out.writeShortLE(v)
            }
        }
        return file
    }

    private fun goertzelPower(samples: FloatArray, from: Int, to: Int, frequency: Double): Double {
        val n = to - from
        if (n <= 0) return 0.0
        val coeff = 2.0 * cos(2.0 * PI * frequency / sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until to) {
            val s0 = samples[i].toDouble() + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        val norm = n.toDouble() * n.toDouble()
        return (power / norm).coerceAtLeast(0.0)
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
