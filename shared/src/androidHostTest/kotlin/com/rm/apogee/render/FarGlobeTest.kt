package com.rm.apogee.render

import com.rm.apogee.core.orbit.SolarSystem
import org.junit.Assert.assertTrue
import org.junit.Test

/** Other worlds seen across space are drawn as themselves, not as balls of one colour. */
class FarGlobeTest {

    private val system = SolarSystem.defaultSystem()

    private fun colours(id: String): List<Triple<Float, Float, Float>> {
        val body = system.body(id)
        val globe = PlanetMesh.buildGlobe(body.terrain, body.radius, 48, id)
        val stride = TerrainChunk.STRIDE_FLOATS
        return (0 until globe.vertices.size / stride).map { v ->
            val o = v * stride
            Triple(globe.vertices[o + 6], globe.vertices[o + 7], globe.vertices[o + 8])
        }
    }

    @Test
    fun `Terra from afar has land and sea`() {
        val all = colours("terra")
        val sea = all.count { (r, g, b) -> b > r + 0.1f && b > g }
        val land = all.count { (r, g, b) -> g >= b || r >= b }
        assertTrue("sea $sea and land $land of ${all.size}", sea > all.size / 4 && land > all.size / 20)
    }

    @Test
    fun `Magna from afar has its bands and its red storm`() {
        val all = colours("magna")
        assertTrue("more than two colours", all.map { (r, g, b) -> Triple((r * 20).toInt(), (g * 20).toInt(), (b * 20).toInt()) }.toSet().size > 3)
        val storm = all.count { (r, g, b) -> r > 0.7f && g < 0.5f && b < 0.4f }
        assertTrue("its storm: $storm vertices", storm > 0)
    }
}
