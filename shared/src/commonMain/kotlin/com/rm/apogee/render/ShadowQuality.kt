package com.rm.apogee.render

/**
 * How much shadow to draw.
 *
 * - [nearSize]: the map round the flown craft, texels a side.
 * - [kernel]: extra taps each way. 0 is the hardware's 2x2; 1 is 3x3 of those, softer and dearer.
 * - [farSize] and [farEvery]: the mountains' map, and seconds between redraws. 0 for none.
 * - [farReach]: how far the mountains' map reaches each way, in metres.
 */
enum class ShadowQuality(
    val label: String,
    val nearSize: Int,
    val kernel: Int,
    val farSize: Int,
    val farEvery: Double,
    val farReach: Double,
) {
    OFF("Off", 0, 0, 0, 0.0, 0.0),
    LOW("Low", 1024, 0, 0, 0.0, 0.0),
    MEDIUM("Medium", 2048, 0, 1024, 2.0, 15_000.0),
    HIGH("High", 2048, 1, 2048, 1.0, 25_000.0);

    val on: Boolean get() = nearSize > 0
    val mountains: Boolean get() = farSize > 0

    companion object {
        /** What a device of [tier] gets unless the player says otherwise. */
        fun defaultFor(tier: QualityTier): ShadowQuality = when (tier) {
            QualityTier.LOW -> OFF
            QualityTier.MEDIUM -> MEDIUM
            QualityTier.HIGH -> HIGH
        }
    }
}
