package dev.lavalink.mixer.plugin

data class CrossfadePlan(
    /** Point where the fade begins in the outgoing track. */
    val startMs: Long,
    /** Point where the outgoing track's audible content ends. */
    val handoffMs: Long,
    val durationMs: Long,
)

/** Timing rules for silence-trimmed transitions. */
object TrimTransition {
    /** Never let an invalid measured tail move the content end before 0. */
    fun contentEnd(durationMs: Long, trailingSilenceMs: Long): Long =
        (durationMs - trailingSilenceMs.coerceAtLeast(0)).coerceAtLeast(0)

    /**
     * Fades across the final audible part of the track, not its trailing
     * silence. The handoff happens exactly at [contentEndMs].
     */
    fun crossfade(contentEndMs: Long, requestedMs: Long): CrossfadePlan? {
        if (contentEndMs <= 0 || requestedMs <= 0) return null
        val duration = minOf(contentEndMs, requestedMs)
        return CrossfadePlan(contentEndMs - duration, contentEndMs, duration)
    }
}
