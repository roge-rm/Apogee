package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.world.World
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ground is asked for its height a hundred times a craft a tick, and for its material for every
 * tile sample. Measured against Terra's in the same run, so a busy machine doesn't fail it.
 */
class ElevationCostTest {
    private val system = SolarSystem.defaultSystem()

    /** Half near the world's first test site, where craft are, half anywhere. */
    private fun directions(body: String, count: Int): Array<Vec3> {
        val terrain = system.body(body).terrain!!
        val site = World.launchSites.firstOrNull { it.bodyId == body }
            ?.let { SolarSystem.surfaceDirection(it.latitude, it.longitude) } ?: Vec3(1.0, 0.0, 0.0)
        val spread = 20_000.0 / terrain.bodyRadius
        return Array(count) { i ->
            fun u(k: Int) = (Noise.hashInt(7, i, k, 0) ushr 8) / 8_388_608.0 * 2 - 1
            val a = u(1); val b = u(2); val c = u(3)
            if (i % 2 == 0) Vec3(site.x + a * spread, site.y + b * spread, site.z + c * spread).normalizeInPlace()
            else Vec3(a, b, c).let { if (it.lengthSq < 1e-6) Vec3(1.0, 0.0, 0.0) else it.normalizeInPlace() }
        }
    }

    private val dirs = HashMap<String, Array<Vec3>>()
    private var sink = 0.0

    /** Nanoseconds for one height, and for one height and its material, in one run. */
    private fun once(body: String): Pair<Double, Double> {
        val terrain = system.body(body).terrain!!
        val d = dirs.getOrPut(body) { directions(body, 20_000) }
        var start = System.nanoTime()
        for (v in d) sink += terrain.elevation(v)
        val height = (System.nanoTime() - start).toDouble() / d.size
        start = System.nanoTime()
        for (v in d) sink += terrain.material(v, terrain.elevation(v), 0.1).ordinal
        return height to (System.nanoTime() - start).toDouble() / d.size
    }

    /** [body]'s cost as shares of Terra's: Terra and it run turn about, each at its best. */
    private fun share(body: String): Pair<Double, Double> {
        var t1 = Double.MAX_VALUE; var t2 = Double.MAX_VALUE; var w1 = Double.MAX_VALUE; var w2 = Double.MAX_VALUE
        repeat(7) {
            val t = once("terra"); val w = once(body)
            t1 = minOf(t1, t.first); t2 = minOf(t2, t.second); w1 = minOf(w1, w.first); w2 = minOf(w2, w.second)
        }
        return w1 / t1 to w2 / t2
    }

    @Test
    fun `the close-up relief adds no more than about a Terra sample to any world's ground`() {
        // Warm the JIT on all of them first.
        for (w in BEFORE.keys) once(w)
        once("terra")
        val shares = BEFORE.keys.associateWith { share(it) }
        println(shares.entries.joinToString("\n") { (w, c) -> "COST %-10s height %.2f (was %.2f)   with material %.2f (was %.2f)".format(w, c.first, BEFORE.getValue(w).first, c.second, BEFORE.getValue(w).second) })
        if (sink.isNaN()) println(sink)
        for ((w, before) in BEFORE) {
            val (h, b) = shares.getValue(w)
            assertTrue("$w's height costs ${"%.2f".format(h)} of Terra's, was ${before.first}", h <= before.first + ADDED && h <= MOST)
            assertTrue("$w's ground costs ${"%.2f".format(b)} of Terra's, was ${before.second}", b <= before.second + ADDED && b <= MOST)
        }
    }

    private companion object {
        /** Each world's cost at generation 8, as shares of Terra's: height, then height and material. */
        val BEFORE = mapOf(
            "luna" to (2.12 to 2.44),
            "celer" to (2.90 to 2.83),
            "caligo" to (1.91 to 1.79),
            "rubra" to (2.24 to 2.26),
            "timor" to (1.96 to 1.53),
            "pavor" to (1.94 to 1.53),
            "fornax" to (1.13 to 1.07),
            "crusta" to (2.32 to 2.97),
            "maxima" to (1.96 to 1.57),
            "cicatrix" to (2.26 to 2.46),
            "aurantia" to (1.72 to 1.30),
            "fons" to (1.47 to 1.06),
            "aversa" to (0.80 to 0.69),
            "ultima" to (1.81 to 1.57),
            "portitor" to (1.74 to 1.33),
        )

        /** How much the close-up relief may add to a world's cost, in Terra's, and the most any world may cost. */
        const val ADDED = 1.5
        const val MOST = 4.5
    }
}
