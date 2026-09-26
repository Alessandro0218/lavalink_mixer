package dev.lavalink.mixer.plugin

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Defaults (overridable per guild via REST). Also bindable from application.yml under `mixer:`. */
@Component
@ConfigurationProperties(prefix = "mixer")
class MixerConfig {
    var crossfadeEnabled: Boolean = true
    var crossfadeMs: Long = 5000
    var fadeInMs: Long = 300
    var duckLevel: Float = 0.2f
    var duckFadeMs: Long = 300
    var maskMs: Long = 80

    // Silence skipping is opt-in: every preloaded track is decoded once more
    // for analysis, which costs CPU/bandwidth.
    var silenceSkipEnabled: Boolean = false
    var silenceThresholdDb: Float = -60f
    var silenceMinSoundMs: Long = 120
    var silenceHeadScanMs: Long = 15000
    var silenceTailScanMs: Long = 15000
    /** Sustained realtime silence before an early cut (streams demand max(this, 3000)). */
    var tailConfirmMs: Long = 1000

    /** Parallel silence analyses (per-guild decoders are independent). Read at startup. */
    var analysisThreads: Int = 2
}
