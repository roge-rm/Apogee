package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** What the ground holds: ice in Luna's polar craters, ore by the rock it is. */
class DepositsTest {
    private val system = SolarSystem.defaultSystem()
    private val luna = system.body("luna").terrain!!
    private val terra = system.body("terra").terrain!!

    /** Directions on a latitude band [from]..[to] degrees, [n] by [n]. */
    private fun band(from: Double, to: Double, n: Int = 60): List<Vec3> = buildList {
        for (i in 0 until n) for (j in 0 until n) {
            val lat = Math.toRadians(from + (to - from) * (i + 0.5) / n)
            val lon = 2 * Math.PI * j / n
            add(Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon)))
        }
    }

    private fun materials(terrain: Terrain, at: List<Vec3>) = at.map { terrain.material(it, terrain.elevation(it), 0.0) }

    @Test
    fun `Luna's polar crater floors are ice, and nowhere else is`() {
        val south = materials(luna, band(-89.5, -79.0))
        val north = materials(luna, band(79.0, 89.5))
        val tropics = materials(luna, band(-60.0, 60.0))
        assertTrue("no ice at the south pole", south.count { it == SurfaceMaterial.ICE } > 20)
        assertTrue("no ice at the north pole", north.count { it == SurfaceMaterial.ICE } > 20)
        assertTrue("ice in the tropics", tropics.none { it == SurfaceMaterial.ICE })
        // Mostly still the old ground: only the floors.
        assertTrue("the pole is all ice", south.count { it == SurfaceMaterial.ICE } < south.size / 2)
    }

    @Test
    fun `ice holds water, the maria and rubble hold ore, and ice holds no ore`() {
        val south = band(-89.5, -79.0)
        val ice = south.first { luna.material(it, luna.elevation(it), 0.0) == SurfaceMaterial.ICE }
        assertTrue(Deposits.richness(luna, ice, ResourceType.WATER) >= 0.8)
        assertEquals(0.0, Deposits.richness(luna, ice, ResourceType.ORE), 0.0)

        for (material in SurfaceMaterial.entries) {
            for (patch in listOf(0.0, 0.5, 1.0)) {
                val ore = Deposits.richnessOf(material, ResourceType.ORE, patch)
                val water = Deposits.richnessOf(material, ResourceType.WATER, patch)
                assertTrue("$material ore $ore", ore in 0.0..1.0)
                assertTrue("$material water $water", water in 0.0..1.0)
            }
        }
        assertTrue(Deposits.richnessOf(SurfaceMaterial.BASALT, ResourceType.ORE, 0.0) >= 0.4)
        assertTrue(Deposits.richnessOf(SurfaceMaterial.REGOLITH, ResourceType.ORE, 1.0) <= 0.3)
        assertTrue(Deposits.richnessOf(SurfaceMaterial.SCREE, ResourceType.ORE, 1.0) > Deposits.richnessOf(SurfaceMaterial.GRASS, ResourceType.ORE, 1.0))
        assertEquals(0.0, Deposits.richnessOf(SurfaceMaterial.BASALT, ResourceType.WATER, 1.0), 0.0)
        assertEquals(0.0, Deposits.richnessOf(SurfaceMaterial.CONCRETE, ResourceType.ORE, 1.0), 0.0)
    }

    @Test
    fun `the same ground is not everywhere equally rich`() {
        val tropics = band(-40.0, 40.0, 40).filter { !terra.isOcean(it) }
        val ores = tropics.map { Deposits.richness(terra, it, ResourceType.ORE) }
        assertTrue("no land sampled", ores.size > 100)
        assertTrue("all the same: ${ores.min()}..${ores.max()}", ores.max() - ores.min() > 0.3)
        // The sea holds nothing a drill can reach.
        val sea = band(-40.0, 40.0, 40).first { terra.isOcean(it) }
        assertEquals(0.0, Deposits.richness(terra, sea, ResourceType.ORE), 0.0)
    }
}
