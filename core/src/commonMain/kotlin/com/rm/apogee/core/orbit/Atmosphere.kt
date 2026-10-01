package com.rm.apogee.core.orbit

import kotlinx.serialization.Serializable
import kotlin.math.exp

/**
 * An exponential atmosphere, `density(h) = ρ₀ · exp(-h / H)`, cut off hard at [height]. The hard
 * edge is on purpose: orbits above it are stable, and "am I in space" has a clear answer.
 */
@Serializable
data class Atmosphere(
    /** kg/m³ at datum. */
    val seaLevelDensity: Double = 1.225,
    /** Pascals at datum. */
    val seaLevelPressure: Double = 101_325.0,
    /** Metres. The height over which density falls by a factor of e. */
    val scaleHeight: Double = 5_600.0,
    /** Metres above datum where the atmosphere ends. */
    val height: Double = 70_000.0,
    /** Air temperature at datum, in K. */
    val surfaceTemperature: Double = 288.0,
    /** Cooling with height, in K/m. */
    val lapseRate: Double = 0.0065,
    /** The coldest it gets up high (the tropopause), in K. */
    val tropopause: Double = 217.0,
    /**
     * A gas giant's air: no ground under the datum, just air getting thicker, hotter and heavier
     * until it crushes whatever falls in.
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

    /**
     * Air temperature at [altitude] in K: cooling with height down to the tropopause, warming with
     * depth in a deep atmosphere.
     */
    fun temperatureAt(altitude: Double): Double =
        (surfaceTemperature - lapseRate * altitude).coerceAtLeast(tropopause)

    /**
     * Ambient pressure as a fraction of Terra's sea level, which engine thrust and Isp curves are
     * rated against.
     */
    fun pressureRatioAt(altitude: Double): Double = pressureAt(altitude) / STANDARD_PRESSURE

    companion object {
        /** Terra's sea-level pressure in Pa, which engines are rated against. */
        const val STANDARD_PRESSURE = 101_325.0

        /** Scale heights down that a deep atmosphere keeps thickening. */
        private const val DEEPEST = 12.0
    }
}
