package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.terrain.Deposits
import java.util.concurrent.ConcurrentHashMap

/**
 * A surveyed body's ore or water, sampled on a latitude-longitude grid for
 * the map: worked out once per body and resource on a thread of its own -
 * a few thousand terrain samples, too many for a frame - and kept for as
 * long as the app runs, since the ground is the same in every world.
 */
object RichnessGrid {

    /** The points worth drawing: body-fixed unit direction x, y, z, then richness, four floats each. */
    class Points(val data: FloatArray) {
        val count: Int get() = data.size / 4
    }

    private val ready = ConcurrentHashMap<Pair<String, ResourceType>, Points>()
    private val asked = ConcurrentHashMap.newKeySet<Pair<String, ResourceType>>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE) }
            r.run()
        }, "richness").apply { isDaemon = true }
    }

    /** [body]'s grid of [resource], or null while it is still being worked out. */
    fun points(body: CelestialBody, resource: ResourceType): Points? {
        val key = body.id to resource
        ready[key]?.let { return it }
        val terrain = body.terrain ?: return null
        if (asked.add(key)) worker.execute {
            val out = ArrayList<Float>()
            val d = Vec3()
            var lat = -90.0 + STEP / 2
            while (lat < 90.0) {
                // Fewer across near the poles, so the dots stay evenly spread.
                val across = maxOf(1, (360.0 / STEP * kotlin.math.cos(Math.toRadians(lat))).toInt())
                for (j in 0 until across) {
                    val lon = Math.toRadians(360.0 * j / across)
                    d.setTo(SolarSystem.surfaceDirection(Math.toRadians(lat), lon))
                    val r = Deposits.richness(terrain, d, resource)
                    if (r < SHOWN) continue
                    out.add(d.x.toFloat()); out.add(d.y.toFloat()); out.add(d.z.toFloat()); out.add(r.toFloat())
                }
                lat += STEP
            }
            ready[key] = Points(out.toFloatArray())
        }
        return null
    }

    /** Grid spacing, degrees. */
    private const val STEP = 3.0

    /** Richness under which a point is not worth a dot. */
    private const val SHOWN = 0.15
}
