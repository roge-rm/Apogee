package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TerraGeographyTest {
    private val terrain = SolarSystem.defaultSystem().body("terra").terrain!!
    private fun direction(y: Double, longitude: Double): Vec3 {
        val r = sqrt(1.0 - y * y)
        return Vec3(r * cos(longitude), y, r * sin(longitude))
    }

    @Test fun `both poles are frozen solid and the south is an elevated ice sheet`() {
        for (sign in listOf(-1.0, 1.0)) for (ring in 0..10) for (k in 0 until 24) {
            val d = direction(sign * (1.0 - ring * 0.0008), k * PI / 12.0)
            val height = terrain.elevation(d)
            assertTrue("polar ground at $d: $height", height > (if (sign > 0.0) 1.0 else 0.0))
            assertTrue(terrain.material(d, height, 0.0) in listOf(SurfaceMaterial.SNOW, SurfaceMaterial.ICE))
            if (sign < 0.0 && ring == 0) assertTrue("south-pole plateau: $height", height > 200.0)
        }
    }

    @Test fun `Earth continents ocean basins mountain ranges and valleys are recognisable and proportional`() {
        fun at(lat: Double, lon: Double): Vec3 {
            val p = Landforms.at(lat, lon)
            return Vec3(p[0], p[1], p[2])
        }
        for ((lat, lon) in listOf(20.0 to 20.0, 40.0 to -100.0, -25.0 to 135.0, -10.0 to -60.0))
            assertTrue("continental interior $lat,$lon", terrain.elevation(at(lat, lon)) > 0.0)
        for ((lat, lon) in listOf(0.0 to -140.0, 0.0 to -30.0, -30.0 to 80.0))
            assertTrue("ocean basin $lat,$lon", terrain.elevation(at(lat, lon)) < -200.0)
        val peak = terrain.elevation(at(27.9881, 86.9250))
        assertTrue("Everest region at Terra scale: $peak", peak in 600.0..850.0)
        val valley = terrain.elevation(at(27.8, 86.7))
        assertTrue("Himalayan valley $valley below ridge $peak", valley < peak - 100.0)
        val alps = terrain.elevation(at(45.8326, 6.8652))
        val rhone = terrain.elevation(at(46.2, 7.4))
        assertTrue("Alpine ridge $alps above Rhone valley $rhone", alps > rhone + 150.0)
        val raw = EarthRelief.elevation(at(27.9881, 86.9250).x, at(27.9881, 86.9250).y, at(27.9881, 86.9250).z)
        assertEquals(raw * terrain.bodyRadius / EarthRelief.EARTH_RADIUS, peak, 3.0)
    }

    @Test fun `permanent polar ice has no vegetation`() {
        for (sign in listOf(-1.0, 1.0)) {
            val coords = Vec3()
            val field = terrain.scatter!!
            val face = CubeSphere.locate(Vec3(0.0, sign, 0.0), coords)
            val centre = field.tilesPerFace / 2
            for (i in -2..2) for (j in -2..2) {
                val block = field.block(face, centre + i, centre + j)
                assertTrue(block.kinds.all { !ScatterKind.of(it.toInt()).lives })
            }
        }
    }

    @Test fun `ocean dominates but continents span the climate zones`() {
        var dry = 0
        val climates = IntArray(3)
        val materials = mutableSetOf<SurfaceMaterial>()
        // Equal-area sphere samples, rather than over-weighting polar map rows.
        for (i in 0 until 12_000) {
            val y = 1.0 - 2.0 * (i + 0.5) / 12_000.0
            val d = direction(y, i * 2.399963229728653)
            val h = terrain.elevation(d)
            if (h <= 0.0) continue
            dry++
            climates[if (abs(y) < 0.35) 0 else if (abs(y) < 0.8) 1 else 2]++
            materials += terrain.material(d, h, 0.0)
        }
        val fraction = dry / 12_000.0
        assertTrue("land fraction $fraction", fraction in 0.25..0.40)
        assertTrue(climates.all { it > 200 })
        assertTrue(materials.containsAll(listOf(SurfaceMaterial.SAND, SurfaceMaterial.FOREST, SurfaceMaterial.GRASS, SurfaceMaterial.SNOW)))
    }

    @Test fun `polar margins and continental coasts have no elevation steps`() {
        for (sign in listOf(-1.0, 1.0)) for (k in 0 until 48) {
            val longitude = k * PI / 24.0
            for (i in 0 until 900) {
                val latitude = sign * (PI / 2.0 - i * 0.0006)
                val a = Vec3(cos(latitude) * cos(longitude), sin(latitude), cos(latitude) * sin(longitude))
                val next = latitude + 0.1 / terrain.bodyRadius
                val b = Vec3(cos(next) * cos(longitude), sin(next), cos(next) * sin(longitude))
                assertTrue("step near $a", abs(terrain.elevation(a) - terrain.elevation(b)) < 2.0)
            }
        }
    }
}
