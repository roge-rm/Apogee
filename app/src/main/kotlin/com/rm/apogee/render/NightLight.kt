package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3

/**
 * How much sun reaches a place, and what lights it when none does: the same
 * sums as `NIGHT_LIGHT` in [Shaders], for what is lit on the CPU - smoke,
 * dust and rain - so particles agree with the ground about how dark it is.
 */
object NightLight {

    /** A full moon's light, faint and blue: `MOON` in the shaders. */
    val MOON = floatArrayOf(0.21f, 0.25f, 0.37f)

    /** Lightning's light: `FLASH` in the shaders. Change both together. */
    val FLASH = floatArrayOf(0.8f, 0.85f, 1.0f)

    /** Air and fog at night, as a share of their daylight brightness. */
    const val NIGHT_AIR = 0.08f

    /** Twilight's glow from the sky, at its strongest: `duskGlow` in the shaders. */
    private val DUSK = floatArrayOf(0.17f, 0.15f, 0.18f)

    /**
     * How much sunlight reaches [position] - planet-centred, on a body of
     * [radius] - with the sun along [sun]: 1 by day, 0 in the planet's
     * shadow, eased across the terminator. High up the sun is seen past the
     * planet's edge for longer - by the angle the horizon dips at that
     * height - so a craft in orbit is lit well round onto the night side.
     */
    fun daylight(position: Vec3, radius: Double, sun: Vec3): Float {
        val r = position.length
        if (r < 1.0) return 1f
        val facing = (position.x * sun.x + position.y * sun.y + position.z * sun.z) / r
        val ratio = (radius / maxOf(r, radius)).coerceIn(0.0, 1.0)
        val dip = kotlin.math.sqrt(1.0 - ratio * ratio)
        val t = ((facing + dip + 0.08) / 0.43).coerceIn(0.0, 1.0)
        return (t * t * (3 - 2 * t)).toFloat()
    }

    /** Moonlight left with [daylight] of the sun: all of it until the sun is well up. */
    fun moonLeft(daylight: Float): Float {
        val t = ((daylight - 0.5f) / 0.5f).coerceIn(0f, 1f)
        return 1f - t * t * (3 - 2 * t)
    }

    /**
     * The light on something unlit by any face - a puff of smoke - per
     * channel, into [out]: the sun's [daySun] share by day, the moon and
     * twilight sky otherwise, dimmed under a storm by [lightScale].
     */
    fun flatLight(daylight: Float, daySun: Float, lightScale: Float, out: FloatArray): FloatArray {
        val moon = moonLeft(daylight) * (0.4f + 0.6f * lightScale)
        val dusk = 4f * daylight * (1f - daylight)
        for (c in 0 until 3) out[c] = daySun * daylight + MOON[c] * moon + DUSK[c] * dusk
        return out
    }
}
