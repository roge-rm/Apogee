package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.terrain.Deposits
import com.rm.apogee.core.math.Math

/**
 * A surveyed body's ore or water on a latitude-longitude grid, for the map. Built once per body and
 * resource on its own thread (thousands of terrain samples) and kept while the app runs, since the
 * ground is the same in every world.
 */
object RichnessGrid {

    /**
     * The points worth drawing: body-fixed unit direction x, y, z, then richness. Four floats each.
     */
    class Points(val data: FloatArray) {
        val count: Int get() = data.size / 4
    }

    private val ready = com.rm.apogee.core.concurrentMapOf<Pair<String, ResourceType>, Points>()
    private val asked = com.rm.apogee.core.concurrentSetOf<Pair<String, ResourceType>>()
    private val worker = Worker("richness")

    /** [body]'s grid of [resource], or null while it's still being worked out. */
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

    /** Grid spacing, in degrees. */
    private const val STEP = 3.0

    /** Richness under which a point isn't worth a dot. */
    private const val SHOWN = 0.15
}
