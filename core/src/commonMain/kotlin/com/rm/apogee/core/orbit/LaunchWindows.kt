package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3

/**
 * When to launch for a moon. A due-east launch makes an orbit tilted by the site's latitude, with
 * the site at its top. Luna's orbit is tilted by the Cape's latitude, so once a day a due-east
 * launch goes straight into Luna's plane, with no plane change later.
 */
object LaunchWindows {

    /**
     * Universe time of the next window at or after [time]: when a due-east launch from [site] (a
     * body-fixed unit direction on [body]) rises into the plane of [target]'s orbit. Null if
     * [target] doesn't orbit [body].
     */
    fun next(body: CelestialBody, site: Vec3, target: CelestialBody, time: Double): Double? {
        val orbit = target.orbit ?: return null
        if (target.parentId != body.id) return null
        val normal = orbit.angularMomentum.normalized()
        // The northernmost point of the target's plane.
        val top = Vec3(0.0, 1.0, 0.0).addScaledInPlace(normal, -normal.y)
        if (top.lengthSq < 1e-12) return null
        top.normalizeInPlace()
        val day = body.rotationPeriod
        val rotation = Quat()
        val p = Vec3()
        fun closeness(t: Double): Double {
            body.rotationAt(t, rotation)
            rotation.rotate(site, p)
            return p dot top
        }
        // A coarse pass over a day, then a golden-section search around the best.
        val steps = 720
        var best = time
        var bestValue = -2.0
        for (k in 0..steps) {
            val t = time + day * k / steps
            val c = closeness(t)
            if (c > bestValue) { bestValue = c; best = t }
        }
        var lo = best - day / steps
        var hi = best + day / steps
        repeat(60) {
            val a = lo + (hi - lo) * 0.382
            val b = lo + (hi - lo) * 0.618
            if (closeness(a) > closeness(b)) hi = b else lo = a
        }
        return (0.5 * (lo + hi)).coerceAtLeast(time)
    }
}
