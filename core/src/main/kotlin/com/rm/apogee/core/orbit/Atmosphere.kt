package com.rm.apogee.core.orbit

import kotlinx.serialization.Serializable
import kotlin.math.exp

/**
 * An exponential atmosphere.
 *
 * `density(h) = ρ₀ · exp(-h / H)`, cut off hard at [height].
 *
 * The hard ceiling is a deliberate simplification and a gameplay decision, not
 * just a modelling one: a real atmosphere thins asymptotically forever, which
 * would mean every orbit decays and nothing is ever truly stable. A defined
 * edge gives the player an altitude to get above, and the game a clean answer
 * to "am I in space yet".
 */
@Serializable
data class Atmosphere(
    /** kg/m³ at datum. */
    val seaLevelDensity: Double = 1.225,
    /** Pascals at datum. */
    val seaLevelPressure: Double = 101_325.0,
    /** Metres. The altitude at which density falls by a factor of e. */
    val scaleHeight: Double = 5_600.0,
    /** Metres above datum where the atmosphere ends outright. */
    val height: Double = 70_000.0,
) {
    fun densityAt(altitude: Double): Double {
        if (altitude >= height || altitude.isNaN()) return 0.0
        if (altitude <= 0.0) return seaLevelDensity
        return seaLevelDensity * exp(-altitude / scaleHeight)
    }

    fun pressureAt(altitude: Double): Double {
        if (altitude >= height || altitude.isNaN()) return 0.0
        if (altitude <= 0.0) return seaLevelPressure
        return seaLevelPressure * exp(-altitude / scaleHeight)
    }

    /**
     * Ambient pressure as a fraction of sea level, which is the form engine
     * thrust and Isp curves are interpolated against.
     */
    fun pressureRatioAt(altitude: Double): Double =
        if (seaLevelPressure <= 0.0) 0.0 else pressureAt(altitude) / seaLevelPressure
}
