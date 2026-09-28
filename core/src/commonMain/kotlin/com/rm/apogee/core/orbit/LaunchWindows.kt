package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3

/**
 * When to launch for a moon.
 *
 * A rocket launched due east from some latitude flies into an orbit tilted by that latitude, with
 * the launch site at the top of it. Luna's orbit is tilted by the Cape's latitude, so once a day,
 * as the planet carries the Cape round to the top of Luna's plane, a due-east launch goes straight
 * into Luna's plane and you don't have to spend anything turning into it later.
 */
object LaunchWindows {

    /**
     * The universe time of the next window at or after [time]: when a due-east launch from [site]
     * (a body-fixed unit direction on [body]) rises into the plane of [target]'s orbit around
     * [body]. Null if [target] doesn't orbit [body].
     */
    fun next(body: CelestialBody, site: Vec3, target: CelestialBody, time: Double): Double? {
        val orbit = target.orbit ?: return null
        if (target.parentId != body.id) return null
        val normal = orbit.angularMomentum.normalized()
        // The top of the target's plane, meaning the point on it furthest north.
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
        // Once round roughly to find the best, then closer in around it.
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
