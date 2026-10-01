package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.math.Math

/**
 * A world's sea currents on a latitude-longitude grid, for the map. Built once on its own thread,
 * like [RichnessGrid]. Currents change only with the world's seed, so it's kept per world and seed.
 */
object CurrentGrid {

    /**
     * The points with a current worth an arrow: body-fixed unit direction x, y, z, then the current
     * there, body-fixed, in m/s. Six floats each.
     */
    class Points(val data: FloatArray) {
        val count: Int get() = data.size / 6
    }

    private val ready = com.rm.apogee.core.concurrentMapOf<Pair<String, Int>, Points>()
    private val asked = com.rm.apogee.core.concurrentSetOf<Pair<String, Int>>()
    private val worker = Worker("currents")

    /** [body]'s currents with sea seed [seed], or null while they're still being worked out. */
    fun points(body: CelestialBody, moon: CelestialBody?, seed: Int): Points? {
        val key = body.id to seed
        ready[key]?.let { return it }
        if (body.ocean == null || body.terrain == null) return null
        if (asked.add(key)) worker.execute {
            // Its own sea, since a sea's caches aren't thread safe.
            val sea = Sea(body, moon, null, seed)
            val out = ArrayList<Float>()
            val velocity = Vec3()
            val point = Vec3()
            var lat = -90.0 + STEP / 2
            while (lat < 90.0) {
                // Fewer across near the poles, so the arrows stay evenly spread.
                val across = maxOf(1, (360.0 / STEP * kotlin.math.cos(Math.toRadians(lat))).toInt())
                for (j in 0 until across) {
                    val d = SolarSystem.surfaceDirection(Math.toRadians(lat), Math.toRadians(360.0 * j / across))
                    sea.roughCurrent(point.setTo(d).mulInPlace(body.radius), velocity)
                    if (velocity.length < SHOWN) continue
                    out.add(d.x.toFloat()); out.add(d.y.toFloat()); out.add(d.z.toFloat())
                    out.add(velocity.x.toFloat()); out.add(velocity.y.toFloat()); out.add(velocity.z.toFloat())
                }
                lat += STEP
            }
            ready[key] = Points(out.toFloatArray())
        }
        return null
    }

    /** Grid spacing, in degrees. */
    const val STEP = 3.0

    /** The slowest current, in m/s, worth an arrow. */
    private const val SHOWN = 0.05
}
