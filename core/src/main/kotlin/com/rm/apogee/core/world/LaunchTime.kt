package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody

/**
 * When in the day to launch: whenever it is, or the next dawn, noon, dusk
 * or midnight at the launch site. The world's clock moves on to it before
 * the flight starts - on a player's own game only.
 */
enum class LaunchTime(val label: String, /** The sun's hour angle at the site, radians: 0 noon, π midnight. */ val hourAngle: Double?) {
    NOW("Now", null),
    DAWN("Dawn", -Math.PI / 2 + 0.12),
    NOON("Noon", 0.0),
    DUSK("Dusk", Math.PI / 2 - 0.12),
    MIDNIGHT("Midnight", Math.PI);

    /**
     * The first world time from [from] on at which it is this time of day
     * at unit body-fixed [site] on [body], lit by a star in inertial
     * direction [sun]. [from] itself for [NOW].
     */
    fun nextAt(body: CelestialBody, site: Vec3, sun: Vec3, from: Double): Double {
        val target = hourAngle ?: return from
        val period = body.rotationPeriod
        if (period <= 0.0) return from
        // The site's hour angle now: how far round it is from facing the sun,
        // measured about the spin axis, in the direction the ground turns.
        val now = hourAngleAt(body, site, sun, from)
        var wait = (target - now) / (2 * Math.PI) * period
        wait = ((wait % period) + period) % period
        return from + wait
    }

    companion object {
        /**
         * Where the star is, inertial, unit: fixed for now - one direction
         * for lighting, night and launch times alike.
         */
        val SUN_DIRECTION: Vec3 = Vec3(0.62, 0.45, 0.64).normalizeInPlace()

        /** The sun's hour angle at unit body-fixed [site] at [time]: 0 at local noon, growing through the afternoon. */
        fun hourAngleAt(body: CelestialBody, site: Vec3, sun: Vec3, time: Double): Double {
            val axis = body.rotationAt(time).rotate(Vec3.unitY(), Vec3()) // the spin axis, world +Y
            val here = body.rotationAt(time).rotate(site, Vec3())
            // Both flattened onto the equator's plane.
            val a = here.copy().addScaledInPlace(axis, -(here dot axis)).normalizeInPlace()
            val s = sun.copy().addScaledInPlace(axis, -(sun dot axis)).normalizeInPlace()
            val cross = s.copy().crossInPlace(a) dot axis
            return kotlin.math.atan2(cross, s dot a)
        }
    }
}
