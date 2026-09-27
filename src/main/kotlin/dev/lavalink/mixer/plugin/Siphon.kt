package dev.lavalink.mixer.plugin

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.track.AudioTrack

/**
 * Bounded FIFO of decoded float PCM chunks siphoned off the secondary
 * (overlay/crossfade) player. The mixer filter on the main player drains it.
 * Overflow drops the oldest audio; underflow reads as silence.
 *
 * The bound is in *samples*, not frames: a decoder chunk is not a time unit.
 * Opus/webm sources emit 20ms chunks (960 samples), WAV ~85ms (4096), so a
 * frame cap silently changes meaning per source. More importantly the moment
 * `playTrack()` returns Lavaplayer fills the whole frame buffer
 * (`frameBufferDuration`, 5000ms by default) as fast as it can decode — none
 * of which the mixer has consumed yet. A frame-based cap therefore throws
 * away the opening seconds of every overlaid track before the crossfade
 * hears them. Size the default from the frame buffer instead.
 */
class SiphonQueue(
    private val maxFrames: Int = Int.MAX_VALUE,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
) {
    private data class Frame(val channels: Array<FloatArray>, var offset: Int = 0)

    private val lock = Any()
    private val deque = ArrayDeque<Frame>()
    private var queuedSamples: Int = 0
    var droppedFrames: Long = 0L
        private set
    var droppedSamples: Long = 0L
        private set

    fun push(input: Array<FloatArray>, offset: Int, length: Int) {
        if (length <= 0) return
        val copy = Array(input.size) { c -> input[c].copyOfRange(offset, offset + length) }
        synchronized(lock) {
            deque.addLast(Frame(copy))
            queuedSamples += length
            while ((deque.size > maxFrames || queuedSamples > maxSamples) && deque.isNotEmpty()) {
                val head = deque.removeFirst()
                val remaining = head.channels[0].size - head.offset
                queuedSamples -= remaining
                droppedFrames++
                droppedSamples += remaining.toLong()
            }
        }
    }

    /** Fills [out] (each channel exactly [samples]) from the queue, zero-filling shortfall.
     *  @return samples actually taken from queued audio (excluding zero-fill). */
    fun take(samples: Int, channels: Int, out: Array<FloatArray>): Int {
        var remaining = samples
        var outOffset = 0
        synchronized(lock) {
            while (remaining > 0 && deque.isNotEmpty()) {
                val head = deque.first()
                val channelCount = minOf(channels, head.channels.size)
                val avail = head.channels[0].size - head.offset
                val n = minOf(remaining, avail)
                for (c in 0 until channelCount) {
                    head.channels[c].copyInto(out[c], outOffset, head.offset, head.offset + n)
                }
                // Channels the sub stream lacks stay silent (already zero-filled).
                head.offset += n
                queuedSamples -= n
                if (head.offset >= head.channels[0].size) deque.removeFirst()
                remaining -= n
                outOffset += n
            }
            if (remaining > 0) {
                for (c in 0 until minOf(channels, out.size)) {
                    out[c].fill(0f, outOffset, outOffset + remaining)
                }
            }
            return outOffset
        }
    }

    fun clear() {
        synchronized(lock) {
            deque.clear()
            queuedSamples = 0
        }
    }

    val pendingFrames: Int get() = synchronized(lock) { deque.size }

    /** Decoded-but-not-yet-mixed audio, in samples per channel. */
    val pendingSamples: Int get() = synchronized(lock) { queuedSamples }

    companion object {
        /** 8s at 48kHz: comfortably above a default 5s frame-buffer prefill. */
        const val DEFAULT_MAX_SAMPLES: Int = 8000 * 48

        /** Holds a whole frame-buffer prefill plus slack for the live backlog. */
        fun forFrameBuffer(frameBufferMs: Int): SiphonQueue =
            SiphonQueue(maxSamples = ((frameBufferMs + 3000).coerceAtLeast(DEFAULT_MAX_SAMPLES / 48)) * 48)
    }
}

/**
 * Capture filter for the secondary player: copies PCM into every attached
 * tap queue and forwards it downstream to the frame buffer. Forwarding is
 * required by Lavaplayer's chain protocol (data only flows through
 * build-time downstream references); without it the sub decoder free-runs,
 * the bounded queue only ever holds the tail of the track, and the sub
 * player's own provide() stays empty (its drain does nothing).
 * The primary tap feeds the live mixer; analysis taps are attached
 * temporarily so scanning never steals live audio.
 */
class SiphonFilter(
    private val taps: List<SiphonQueue>,
    private val downstream: UniversalPcmAudioFilter?,
) : FloatPcmAudioFilter {
    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        for (queue in taps) queue.push(input, offset, length)
        downstream?.process(input, offset, length)
    }

    override fun seekPerformed(requestedTime: Long, providedTime: Long) {
        for (queue in taps) queue.clear()
    }

    override fun flush() {
        for (queue in taps) queue.clear()
    }

    override fun close() = Unit
}

class SiphonFactory(primary: SiphonQueue) : PcmFilterFactory {
    private val taps = java.util.concurrent.CopyOnWriteArrayList<SiphonQueue>(listOf(primary))

    fun addTap(queue: SiphonQueue) {
        taps.addIfAbsent(queue)
    }

    fun removeTap(queue: SiphonQueue) {
        taps.remove(queue)
    }

    override fun buildChain(
        track: AudioTrack?,
        format: AudioDataFormat,
        output: UniversalPcmAudioFilter,
    ): MutableList<AudioFilter> = mutableListOf(SiphonFilter(taps.toList(), output))
}
