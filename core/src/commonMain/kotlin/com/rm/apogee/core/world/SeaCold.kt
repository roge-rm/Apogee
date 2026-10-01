package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import kotlin.math.abs
import kotlin.math.pow

/**
 * How cold the sea is, and how long someone in it lasts. Terra's sea is warm at the equator, near
 * freezing at the poles and four degrees in the deep. Nobody lasts more than a couple of minutes in
 * another world's sea.
 */
internal object SeaCold {
    private val fixed = Vec3()

    /** The sea's temperature in °C at [position] around [attractor], [depth] metres down, at [time]. */
    fun celsius(attractor: CelestialBody, position: Vec3, depth: Double, time: Double): Double {
        attractor.toBodyFixed(position, attractor.rotationAt(time), fixed).normalizeInPlace()
        val latitude = abs(kotlin.math.sin(SolarSystem.latitudeOf(fixed)))
        val top = EQUATOR - (EQUATOR - POLE) * latitude.pow(1.5)
        val down = (depth / DEEP_BY).coerceIn(0.0, 1.0)
        return top + (DEEP - top) * down
    }

    /** How long someone lasts in the sea at [position], [depth] metres down, in seconds. */
    fun lasts(attractor: CelestialBody, position: Vec3, depth: Double, time: Double): Double {
        if (attractor.id != SolarSystem.HOMEWORLD_ID) return ALIEN_SEA
        val c = celsius(attractor, position, maxOf(0.0, depth), time)
        // Between the marks, in a straight line.
        if (c <= LASTS[0].first) return LASTS[0].second
        for (i in 1 until LASTS.size) {
            val (t1, s1) = LASTS[i]
            if (c <= t1) {
                val (t0, s0) = LASTS[i - 1]
                return s0 + (s1 - s0) * (c - t0) / (t1 - t0)
            }
        }
        return LASTS.last().second
    }

    /** Terra's sea at the surface, at the equator and at the poles, and in the deep, in °C. */
    const val EQUATOR = 28.0
    const val POLE = -1.5
    const val DEEP = 4.0

    /** How far down, in metres, it's as cold as the deep. */
    const val DEEP_BY = 300.0

    /** How long someone lasts in another world's sea, in seconds. */
    const val ALIEN_SEA = 120.0

    /** How long someone lasts, in seconds, at each temperature in °C. */
    private val LASTS = listOf(
        -2.0 to 600.0,
        0.0 to 900.0,
        10.0 to 3_600.0,
        20.0 to 7_200.0,
        30.0 to 14_400.0,
    )
}
