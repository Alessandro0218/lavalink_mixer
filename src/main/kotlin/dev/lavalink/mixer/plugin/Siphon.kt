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
 */
class SiphonQueue(private val maxFrames: Int = 150) {
    private data class Frame(val channels: Array<FloatArray>, var offset: Int = 0)

    private val lock = Any()
    private val deque = ArrayDeque<Frame>()
    var droppedFrames: Long = 0L
        private set

    fun push(input: Array<FloatArray>, offset: Int, length: Int) {
        if (length <= 0) return
        val copy = Array(input.size) { c -> input[c].copyOfRange(offset, offset + length) }
        synchronized(lock) {
            deque.addLast(Frame(copy))
            while (deque.size > maxFrames) {
                deque.removeFirst()
                droppedFrames++
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
                if (head.offset >= head.channels[0].size) deque.removeFirst()
                remaining -= n
                outOffset += n
            }
            if (remaining > 0) {
                for (c in 0 until minOf(channels, out.size)) {
                    out[c].fill(0f, outOffset, outOffset + remaining)
                }
            }
            return samples - remaining
        }
    }

    fun clear() {
        synchronized(lock) { deque.clear() }
    }

    val pendingFrames: Int get() = synchronized(lock) { deque.size }
}

/**
 * Capture filter for the secondary player: copies PCM into every attached
 * tap queue and passes audio through untouched (its encoded output is
 * drained and discarded). The primary tap feeds the live mixer; analysis
 * taps are attached temporarily so scanning never steals live audio.
 */
class SiphonFilter(private val taps: List<SiphonQueue>) : FloatPcmAudioFilter {
    override fun process(input: Array<FloatArray>, offset: Int, length: Int) {
        for (queue in taps) queue.push(input, offset, length)
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
    ): MutableList<AudioFilter> = mutableListOf(SiphonFilter(taps.toList()))
}
