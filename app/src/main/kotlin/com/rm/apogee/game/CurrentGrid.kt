package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.sea.Sea
import java.util.concurrent.ConcurrentHashMap

/**
 * A world's sea currents, sampled on a latitude-longitude grid for the map. Like [RichnessGrid] it's
 * worked out once, on its own thread, since it's thousands of samples. The currents don't change
 * with time, only with the world's seed, so it's kept per world and seed.
 */
object CurrentGrid {

    /**
     * The points with a current worth an arrow: a body-fixed unit direction x, y, z, then the
     * current there, body-fixed, in m/s, six floats each.
     */
    class Points(val data: FloatArray) {
        val count: Int get() = data.size / 6
    }

    private val ready = ConcurrentHashMap<Pair<String, Int>, Points>()
    private val asked = ConcurrentHashMap.newKeySet<Pair<String, Int>>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE) }
            r.run()
        }, "currents").apply { isDaemon = true }
    }

    /** [body]'s currents with sea seed [seed], or null while they're still being worked out. */
    fun points(body: CelestialBody, moon: CelestialBody?, seed: Int): Points? {
        val key = body.id to seed
        ready[key]?.let { return it }
        if (body.ocean == null || body.terrain == null) return null
        if (asked.add(key)) worker.execute {
            // A sea of its own, since a sea's caches aren't for sharing across threads.
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
