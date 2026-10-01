package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3

/**
 * Where a falling craft will come down, flying it forward as a point under gravity and, in air,
 * drag against air turning with the ground, until it meets ground or sea. Unlike the map's conic,
 * it falls short in air and hits hills on a moon. No engines, lift or wind: what happens if nobody
 * acts.
 */
object Descent {

    /**
     * Where it lands: at universe [time], unit [direction] in the body's turning frame, at [speed]
     * m/s over the ground.
     */
    class Impact(val time: Double, val direction: Vec3, val speed: Double)

    /** Longest prediction in seconds. Past that it isn't coming down soon. */
    const val LIMIT = 1_800.0

    /**
     * Where a craft at [position] and [velocity] (relative to [body]'s centre, at universe [time]),
     * of [mass] kg and drag area [dragArea] (Cd x A, m²), meets the ground. Null if not within
     * [limit] seconds or its orbit never gets low enough.
     */
    fun predict(
        body: CelestialBody,
        position: Vec3,
        velocity: Vec3,
        time: Double,
        mass: Double,
        dragArea: Double,
        limit: Double = LIMIT,
    ): Impact? {
        val highest = body.radius + (body.terrain?.maxElevation ?: 0.0)
        val orbit = Orbit(position, velocity, body.gravitationalParameter)
        // Never low enough to touch the hills or feel the air.
        if (orbit.periapsis > highest + body.atmosphereHeight) return null

        val r = position.copy()
        val v = velocity.copy()
        val rotation = Quat()
        val direction = Vec3()
        val k = if (mass > 0.0) dragArea / mass else 0.0
        var t = time
        var lastHeight = heightAt(body, r, t, rotation, direction)
        if (lastHeight <= 0.0) return null
        val lastR = Vec3()
        val lastV = Vec3()
        // RK4 scratch.
        val k1r = Vec3(); val k1v = Vec3(); val k2r = Vec3(); val k2v = Vec3()
        val k3r = Vec3(); val k3v = Vec3(); val k4r = Vec3(); val k4v = Vec3()
        val tr = Vec3(); val tv = Vec3()
        while (t - time < limit) {
            val speed = v.length.coerceAtLeast(1.0)
            // Short steps near the ground, long strides high up.
            val h = (lastHeight / (speed * 8.0)).coerceIn(0.02, 2.0)
            lastR.setTo(r); lastV.setTo(v)
            accel(body, r, v, k, k1v); k1r.setTo(v)
            tr.setTo(r).addScaledInPlace(k1r, h / 2); tv.setTo(v).addScaledInPlace(k1v, h / 2)
            accel(body, tr, tv, k, k2v); k2r.setTo(tv)
            tr.setTo(r).addScaledInPlace(k2r, h / 2); tv.setTo(v).addScaledInPlace(k2v, h / 2)
            accel(body, tr, tv, k, k3v); k3r.setTo(tv)
            tr.setTo(r).addScaledInPlace(k3r, h); tv.setTo(v).addScaledInPlace(k3v, h)
            accel(body, tr, tv, k, k4v); k4r.setTo(tv)
            r.addScaledInPlace(k1r, h / 6).addScaledInPlace(k2r, h / 3).addScaledInPlace(k3r, h / 3).addScaledInPlace(k4r, h / 6)
            v.addScaledInPlace(k1v, h / 6).addScaledInPlace(k2v, h / 3).addScaledInPlace(k3v, h / 3).addScaledInPlace(k4v, h / 6)
            t += h
            val height = heightAt(body, r, t, rotation, direction)
            if (height <= 0.0) {
                // Interpolate between the last two steps by height.
                val f = lastHeight / (lastHeight - height)
                r.setTo(lastR).addScaledInPlace(lastR.copy().negateInPlace().addInPlace(r), f)
                v.setTo(lastV).addScaledInPlace(lastV.copy().negateInPlace().addInPlace(v), f)
                val at = t - h + f * h
                body.rotationAt(at, rotation)
                body.toBodyFixed(r, rotation, direction).normalizeInPlace()
                val ground = body.surfaceVelocityAt(r, Vec3())
                return Impact(at, direction.copy(), v.copy().subInPlace(ground).length)
            }
            lastHeight = height
        }
        return null
    }

    /** Gravity and drag at [r] moving at [v], into [out]. [k] is drag area over mass. */
    private fun accel(body: CelestialBody, r: Vec3, v: Vec3, k: Double, out: Vec3) {
        body.gravityAt(r, out)
        val atmosphere = body.atmosphere ?: return
        if (k <= 0.0) return
        val density = atmosphere.densityAt(body.altitudeOf(r))
        if (density <= 0.0) return
        val air = body.surfaceVelocityAt(r, Vec3()).negateInPlace().addInPlace(v)
        val speed = air.length
        out.addScaledInPlace(air, -0.5 * density * speed * k)
    }

    /** Height of [r] above the ground or sea under it at [time]. */
    private fun heightAt(body: CelestialBody, r: Vec3, time: Double, rotation: Quat, direction: Vec3): Double {
        body.rotationAt(time, rotation)
        body.toBodyFixed(r, rotation, direction).normalizeInPlace()
        return r.length - body.surfaceRadiusInBodyFrame(direction)
    }
}
