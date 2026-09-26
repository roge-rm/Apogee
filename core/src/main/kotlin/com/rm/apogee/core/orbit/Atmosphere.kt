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
    /** Air temperature at datum, K. */
    val surfaceTemperature: Double = 288.0,
    /** How fast it cools with height, K/m. */
    val lapseRate: Double = 0.0065,
    /** Coldest it gets aloft, K: the tropopause. */
    val tropopause: Double = 217.0,
    /**
     * A gas giant's: no ground under the datum, only more air - thicker,
     * hotter and heavier all the way down, until it crushes what falls in.
     */
    val deep: Boolean = false,
) {
    fun densityAt(altitude: Double): Double {
        if (altitude >= height || altitude.isNaN()) return 0.0
        if (altitude <= 0.0) return if (deep) seaLevelDensity * exp((-altitude / scaleHeight).coerceAtMost(DEEPEST)) else seaLevelDensity
        return seaLevelDensity * exp(-altitude / scaleHeight)
    }

    fun pressureAt(altitude: Double): Double {
        if (altitude >= height || altitude.isNaN()) return 0.0
        if (altitude <= 0.0) return if (deep) seaLevelPressure * exp((-altitude / scaleHeight).coerceAtMost(DEEPEST)) else seaLevelPressure
        return seaLevelPressure * exp(-altitude / scaleHeight)
    }

    /** The air's temperature at [altitude], K: cooling with height to the tropopause - and, deep, heating with depth. */
    fun temperatureAt(altitude: Double): Double =
        (surfaceTemperature - lapseRate * altitude).coerceAtLeast(tropopause)

    /**
     * Ambient pressure as a fraction of Terra's at sea level, which is what
     * engine thrust and Isp curves are rated against: on Rubra's thin air a
     * vacuum engine is nearly at its best, and on Caligo's floor past it.
     */
    fun pressureRatioAt(altitude: Double): Double = pressureAt(altitude) / STANDARD_PRESSURE

    companion object {
        /** Terra's air at sea level, Pa: what engines are rated against. */
        const val STANDARD_PRESSURE = 101_325.0

        /** How many scale heights down a deep atmosphere keeps thickening: past it, nothing is left to crush. */
        private const val DEEPEST = 12.0
    }
}
