package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.math.Math

/**
 * When in the day to launch: right now, or the next dawn, noon, dusk or midnight at the launch
 * site. The world's clock moves on to it before the flight starts, and only in a player's own game.
 */
enum class LaunchTime(val label: String, /** The sun's hour angle at the site, in radians: 0 is noon and π is midnight. */ val hourAngle: Double?) {
    NOW("Now", null),
    DAWN("Dawn", -Math.PI / 2 + 0.12),
    NOON("Noon", 0.0),
    DUSK("Dusk", Math.PI / 2 - 0.12),
    MIDNIGHT("Midnight", Math.PI);

    /**
     * The first world time from [from] onward when it's this time of day at unit body-fixed [site]
     * on [body], lit by a star in inertial direction [sun]. [from] itself for [NOW].
     */
    fun nextAt(body: CelestialBody, site: Vec3, sun: Vec3, from: Double): Double {
        val target = hourAngle ?: return from
        val period = body.rotationPeriod
        if (period <= 0.0) return from
        // The site's hour angle now: how far round it is from facing the sun, measured around the
        // spin axis, in the direction the ground turns.
        val now = hourAngleAt(body, site, sun, from)
        var wait = (target - now) / (2 * Math.PI) * period
        wait = ((wait % period) + period) % period
        return from + wait
    }

    /**
     * [nextAt] under a sun that moves: the star's direction from [body] as [system] has it,
     * followed through the wait. A few steps settle it, since the sun moves a degree or so a day.
     */
    fun nextAt(system: com.rm.apogee.core.orbit.SolarSystem, body: CelestialBody, site: Vec3, from: Double): Double {
        var t = nextAt(body, site, system.sunDirection(body.id, Vec3.zero(), from), from)
        repeat(3) { t = nextAt(body, site, system.sunDirection(body.id, Vec3.zero(), t), from) }
        return t
    }

    companion object {
        /** The sun's hour angle at unit body-fixed [site] at [time]: 0 at local noon, growing through the afternoon. */
        fun hourAngleAt(body: CelestialBody, site: Vec3, sun: Vec3, time: Double): Double {
            val axis = body.spinAxis
            val here = body.rotationAt(time).rotate(site, Vec3())
            // Both flattened onto the plane of the equator.
            val a = here.copy().addScaledInPlace(axis, -(here dot axis)).normalizeInPlace()
            val s = sun.copy().addScaledInPlace(axis, -(sun dot axis)).normalizeInPlace()
            val cross = s.copy().crossInPlace(a) dot axis
            return kotlin.math.atan2(cross, s dot a)
        }
    }
}
