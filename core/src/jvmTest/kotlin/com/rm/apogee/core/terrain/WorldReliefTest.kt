package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.world.Wonders
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The close-up relief: no steps in it, within each world's heights, and its landmarks as named. */
class WorldReliefTest {
    private val system = SolarSystem.defaultSystem()
    private val worlds = system.bodies.values.filter { it.terrain != null && it.id != "terra" }

    private fun dir(lat: Double, lon: Double) = SolarSystem.surfaceDirection(Math.toRadians(lat), Math.toRadians(lon))

    /** Along a line [length] metres out from [from], east, sampled every [step] metres. */
    private fun line(body: String, from: Vec3, length: Double, step: Double): DoubleArray {
        val t = system.body(body).terrain!!
        val up = from.normalized()
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        val n = (length / step).toInt()
        return DoubleArray(n) { i -> t.elevation(Vec3().setTo(up).mulInPlace(t.bodyRadius).addScaledInPlace(east, i * step)) }
    }

    @Test
    fun `the ground has no steps in it, round every site and every place`() {
        val starts = Worlds.SITES.map { it.bodyId to SolarSystem.surfaceDirection(it.latitude, it.longitude) } +
            Wonders.land.map { it.bodyId to it.direction }
        for ((body, at) in starts) {
            val t = system.body(body).terrain!!
            val up = at.normalized()
            val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
            val h = line(body, at, 3_000.0, 0.5)
            for (i in 1 until h.size) {
                val rise = abs(h[i] - h[i - 1])
                if (rise < 0.75) continue
                // Steep is allowed, a cliff's layers say so; a break isn't. A tenth as far apart, a
                // smooth cliff rises a tenth as much.
                var most = 0.0
                var last = h[i - 1]
                for (k in 1..10) {
                    val e = (i - 1) * 0.5 + k * 0.05
                    val v = t.elevation(Vec3().setTo(up).mulInPlace(t.bodyRadius).addScaledInPlace(east, e))
                    most = maxOf(most, abs(v - last)); last = v
                }
                assertTrue("a break of $most m in $rise on $body ${i * 0.5} m out", most < rise * 0.35)
            }
        }
    }

    @Test
    fun `no world's ground goes past its own heights`() {
        for (body in worlds) {
            val t = body.terrain!!
            for (i in 0 until 4_000) {
                val z = 2.0 * ((i * 0.618034) % 1.0) - 1.0
                val a = 2.0 * Math.PI * ((i * 0.754877) % 1.0)
                val r = kotlin.math.sqrt(1.0 - z * z)
                val h = t.elevation(Vec3(r * kotlin.math.cos(a), z, r * kotlin.math.sin(a)))
                assertTrue("${body.id} reaches $h m", abs(h) < t.maxElevation * 1.05 + 500.0)
            }
        }
    }

    private fun h(body: String, lat: Double, lon: Double) = system.body(body).terrain!!.elevation(dir(lat, lon))

    @Test
    fun `the landmarks are what they're called`() {
        // A pit, sheer and deep.
        val pit = h("luna", 7.2, 8.3); val rim = line("luna", dir(7.2, 8.3), 120.0, 5.0).last()
        assertTrue("the Skylight is $pit m, its rim $rim", pit < rim - 40.0)
        // A peak far over the plains round it.
        val peak = h("fornax", -20.0, 70.0); val plain = h("fornax", -20.0, 77.0)
        assertTrue("Fire Peak is $peak m, the plain $plain", peak > plain + 7_000.0)
        // A ring of ridges round a sunken middle.
        val crown = h("caligo", -30.0, 20.0 + 20_000.0 / 570_000.0 * 180.0 / Math.PI / kotlin.math.cos(Math.toRadians(30.0))); val middle = h("caligo", -30.0, 20.0)
        assertTrue("the Crown is $crown m, its middle $middle", crown > middle + 300.0)
        // A mountain with a pit on top.
        val top = h("ultima", -30.0, 170.0); val shoulder = line("ultima", dir(-30.0, 170.0), 6_000.0, 50.0).max()
        assertTrue("the Hollow Mountain's top is $top m, its shoulder $shoulder", top < shoulder - 500.0)
        // A block of ice standing alone.
        val house = h("fons", -75.0, 40.0)
        val round = (line("fons", dir(-75.0, 40.0), 50.0, 10.0).last() + line("fons", dir(-75.0, 40.0 - 0.2), 50.0, 10.0).first()) / 2
        assertTrue("Ice House is $house m, the ground $round", house > round + 12.0)
        // Bright rubble round the young craters.
        val luna = system.body("luna").terrain!!
        assertTrue(luna.material(dir(-20.0, 40.0), h("luna", -20.0, 40.0), 0.0) == SurfaceMaterial.EJECTA)
    }
}
