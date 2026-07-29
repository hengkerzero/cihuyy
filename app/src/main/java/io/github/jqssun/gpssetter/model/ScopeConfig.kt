package io.github.jqssun.gpssetter.model

/**
 * Top-level scope configuration container.
 * Maps package names to their individual spoof configs.
 */
data class ScopeConfig(
    val configVersion: Int = 1,
    val scope: MutableMap<String, AppSpoofConfig> = mutableMapOf()
)

/**
 * Per-app spoofing configuration.
 *
 * Location resolution priority:
 * 1. overrideLat/overrideLng (manual override — if both non-null)
 * 2. templateId → resolve from LocationTemplate
 * 3. fallback to real GPS (passthrough)
 */
data class AppSpoofConfig(
    var enabled: Boolean = true,
    var templateId: String? = null,
    var overrideLat: Double? = null,
    var overrideLng: Double? = null,
    var accuracy: Float = 5f,
    var altitude: Double = 0.0,
    var spoofAltitude: Boolean = false,
    var updateIntervalMs: Long = 1000L
)
