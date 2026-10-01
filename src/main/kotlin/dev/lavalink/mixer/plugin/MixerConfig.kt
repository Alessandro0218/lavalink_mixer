package dev.lavalink.mixer.plugin

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Defaults (overridable per request). Also bindable from application.yml under `mixer:`. */
@Component
@ConfigurationProperties(prefix = "mixer")
class MixerConfig {
    /** Music gain while an overlay plays (0 = silent, 1 = untouched). */
    var duckLevel: Float = 0.2f

    /** Time the music takes to get out of the way, and to come back. The clip starts after the first. */
    var duckFadeMs: Long = 300

    /** Hard cap on one overlay, so a clip that never ends cannot leave the music ducked forever. */
    var maxOverlayMs: Long = 120_000
}
