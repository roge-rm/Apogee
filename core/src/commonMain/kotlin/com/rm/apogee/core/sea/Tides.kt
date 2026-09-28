package com.rm.apogee.core.sea

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.math.Math

/**
 * The tide: the sea heaped up under [moon] and on the far side of [body], as the moon's pull
 * stretches the ocean along the line between them.
 *
 * It's the equilibrium tide, from the two bodies as they are: a bulge of `(μm/μ)(R/d)³R`, about 1.4
 * m for Luna on Terra, varying as the second Legendre polynomial of the angle from the moon. That
 * gives two highs a day as the planet turns under it, with the bulge trailing the moon by
 * [LAG_DEGREES] like a real ocean's does. It's also bigger along the coasts. Over a shelf and up a
 * gentle shore the tide piles up to [SHELF_BOOST] times the open ocean's, so about 2 m of range at
 * sea and 4-6 m in the shallows.
 */
internal class Tides(private val body: CelestialBody, private val moon: CelestialBody?) {

    private val rotation = Quat()
    private val position = Vec3()
    private val moonDirection = Vec3()
    private var amplitude = 0.0

    // The moon's direction at whole seconds either side of the time asked about, blended. That
    // keeps it a pure function of the time, however it gets called, and works out one orbit a
    // second instead of one per sample.
    private var second = Long.MIN_VALUE
    private val before = Vec3()
    private val after = Vec3()
    private var amplitudeBefore = 0.0
    private var amplitudeAfter = 0.0
    private var at = Double.NaN

    /**
     * How far the moon's pull raises the sea here, in metres, over a bed at [bed] m above the datum
     * (negative under the sea).
     */
    fun height(direction: Vec3, time: Double, bed: Double): Double {
        if (moon?.orbit == null) return 0.0
        if (time != at) blend(time)
        val c = direction.x * moonDirection.x + direction.y * moonDirection.y + direction.z * moonDirection.z
        val p2 = 1.5 * c * c - 0.5
        return amplitude * p2 * boost(bed)
    }

    private fun blend(time: Double) {
        at = time
        val s = Math.floor(time).toLong()
        if (s != second) {
            if (s == second + 1) {
                before.setTo(after); amplitudeBefore = amplitudeAfter
            } else {
                aim(s.toDouble()); before.setTo(moonDirection); amplitudeBefore = amplitude
            }
            aim(s + 1.0); after.setTo(moonDirection); amplitudeAfter = amplitude
            second = s
        }
        val f = time - s
        moonDirection.setTo(before).mulInPlace(1.0 - f).addScaledInPlace(after, f).normalizeInPlace()
        amplitude = amplitudeBefore + (amplitudeAfter - amplitudeBefore) * f
    }

    /** How many times the open ocean's tide the sea over a bed at [bed] m gets. */
    fun boost(bed: Double): Double {
        val depth = -bed
        return 1.0 + (SHELF_BOOST - 1.0) * (1.0 - smoothstep(SHALLOW, SHELF, depth))
    }

    /**
     * The moon's direction in [body]'s own turning frame at [time], a little behind where it really
     * is.
     */
    private fun aim(time: Double) {
        val m = moon ?: return
        val orbit = m.orbit ?: return
        // The tide trails the moon. The bulge is where the moon was, seen from the ground, a little
        // while ago.
        val synodic = synodicSeconds(orbit.period)
        val lag = LAG_DEGREES / 360.0 * synodic
        val state = orbit.stateAt(time - lag)
        body.rotationAt(time - lag, rotation)
        position.setTo(state.position)
        val d = position.length
        rotation.inverseRotate(position, moonDirection).normalizeInPlace()
        val ratio = body.radius / d
        amplitude = m.gravitationalParameter / body.gravitationalParameter * ratio * ratio * ratio * body.radius
    }

    /** Seconds between one pass of the moon overhead and the next. */
    private fun synodicSeconds(orbitPeriod: Double): Double {
        val day = body.rotationPeriod
        val relative = 1.0 / day - 1.0 / orbitPeriod
        return if (kotlin.math.abs(relative) < 1e-12) day else 1.0 / kotlin.math.abs(relative)
    }

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    companion object {
        /** Degrees the bulge trails the moon. */
        const val LAG_DEGREES = 25.0

        /** How many times the open ocean's tide piles up over the shallows. */
        const val SHELF_BOOST = 3.0

        /**
         * The depths, in metres, between which the tide grows from the ocean's to the shallows'.
         */
        const val SHELF = 200.0
        const val SHALLOW = 20.0
    }
}
