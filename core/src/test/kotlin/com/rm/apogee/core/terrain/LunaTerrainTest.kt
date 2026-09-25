package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Luna: maria, highlands, craters. Airless, so no sea - which the surface
 * radius, the scatter and the renderer all have to respect.
 */
class LunaTerrainTest {

    private val luna = SolarSystem.defaultSystem().body("luna").terrain!!
    private val r = 200_000.0

    private fun at(north: Double, z: Double) = Vec3(r, north, z).normalizeInPlace()

    @Test
    fun `luna is bit-for-bit what it was`() {
        assertEquals("regenerate these goldens deliberately", 4, TerrainField.GENERATION)
        val golden = longArrayOf(
            4644256880680753404, 4640039160974970086, 4642148242464717512, -4592394242755245043,
            -4589500061515185479, -4588732638978946812, -4584096367367572447, 4632640584737521801,
        )
        golden.forEachIndexed { i, bits ->
            val d = Vec3(1.0 + i * 0.13, -0.4 + i * 0.07, 0.3 - i * 0.05)
            assertEquals("sample $i", bits, java.lang.Double.doubleToRawLongBits(luna.elevation(d)))
        }
    }

    @Test
    fun `luna has no sea, and its lowlands are ground`() {
        assertFalse(luna.hasOcean)
        val low = at(30_000.0, 25_000.0)
        val e = luna.elevation(low)
        assertTrue("the mare should lie below the datum, was $e m", e < -500.0)
        assertFalse(luna.isOcean(low))
        assertEquals(r + e, luna.surfaceRadius(low), 1e-6)
    }

    /**
     * A big complex crater a few tens of kilometres from the prime meridian:
     * a floor well below its rim, a central peak standing up off the floor,
     * and a rim that stands above the plain outside it.
     */
    @Test
    fun `a large crater has a floor, a central peak and a raised rim`() {
        fun h(z: Double) = luna.elevation(at(-4_000.0, z))
        val floor = minOf(h(24_000.0), h(34_000.0))
        val peak = h(29_000.0)
        val rim = maxOf(h(10_000.0), h(11_000.0), h(12_000.0))
        assertTrue("the floor ($floor m) should be far below the rim ($rim m)", rim - floor > 700.0)
        assertTrue("the central peak ($peak m) should rise off the floor ($floor m)", peak - floor > 150.0)
    }

    /**
     * Small craters, the ones a rover actually meets, are everywhere. Counted
     * as bowls on a 3 km square: points lower than everything within 16 m, and
     * at least a metre and a half below the ring around them.
     */
    @Test
    fun `small craters pock the ground`() {
        val n = 375
        val step = 8.0
        val h = DoubleArray(n * n) { luna.elevation(at(5_000.0 + (it / n) * step, (it % n) * step)) }
        var bowls = 0
        for (y in 2 until n - 2) for (x in 2 until n - 2) {
            val c = h[y * n + x]
            var lowest = true
            var rim = Double.NEGATIVE_INFINITY
            for (dy in -2..2) for (dx in -2..2) {
                val v = h[(y + dy) * n + x + dx]
                if (v < c) lowest = false
                rim = maxOf(rim, v)
            }
            if (lowest && rim - c > 1.5) bowls++
        }
        assertTrue("expected a scatter of small craters on 9 km², found $bowls", bowls >= 5)
    }

    @Test
    fun `fresh craters are ringed with boulders`() {
        var scree = 0
        var basalt = 0
        var regolith = 0
        for (row in 0 until 200) for (col in 0 until 200) {
            val d = at(-40_000.0 + row * 400.0, -40_000.0 + col * 400.0)
            when (luna.material(d, luna.elevation(d), 0.0)) {
                SurfaceMaterial.SCREE -> scree++
                SurfaceMaterial.BASALT -> basalt++
                SurfaceMaterial.REGOLITH -> regolith++
                else -> Unit
            }
        }
        assertTrue("some rubble ($scree)", scree > 100)
        assertTrue("some mare ($basalt)", basalt > 1_000)
        assertTrue("mostly highland regolith ($regolith)", regolith > basalt)
    }

    @Test
    fun `luna has no steps`() {
        for (row in -5..5) for (col in -5..5) {
            val north = row * 11_000.0 + 777.0
            val z0 = col * 11_000.0
            var previous = luna.elevation(at(north, z0))
            var m = 0.0
            while (m < 300.0) {
                m += 0.5
                val v = luna.elevation(at(north, z0 + m))
                assertTrue(
                    "a %.1f m step at north %.0f, z %.0f".format(abs(v - previous), north, z0 + m),
                    abs(v - previous) < 3.0,
                )
                previous = v
            }
        }
    }

    @Test
    fun `boulders lie on the maria too`() {
        val field = luna.scatter!!
        var boulders = 0
        field.forEachBlockNear(at(30_000.0, 25_000.0), 400.0, Vec3()) { boulders += it.ids.size }
        assertTrue("expected rocks on the mare, found $boulders", boulders > 0)
    }
}
