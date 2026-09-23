package com.rm.apogee.render

import org.junit.Assert.assertTrue
import org.junit.Test

class PlanetMeshTest {

    /**
     * Every triangle of the globe faces out of the planet. Culling keeps only
     * the faces pointed at the camera; wound inward, the whole near side
     * vanished and orbit showed the inside of the far one.
     */
    @Test
    fun `the globe faces outward`() {
        val data = PlanetMesh.buildGlobe(null, 600_000.0, 16)
        val stride = TerrainChunk.STRIDE_FLOATS
        val v = data.vertices
        var checked = 0
        for (t in 0 until data.indices.size / 3) {
            val i = IntArray(3) { (data.indices[t * 3 + it].toInt() and 0xFFFF) * stride }
            val ax = v[i[0]].toDouble(); val ay = v[i[0] + 1].toDouble(); val az = v[i[0] + 2].toDouble()
            val ux = v[i[1]] - ax; val uy = v[i[1] + 1] - ay; val uz = v[i[1] + 2] - az
            val wx = v[i[2]] - ax; val wy = v[i[2] + 1] - ay; val wz = v[i[2] + 2] - az
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            val area = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (area < 1e-9) continue // the degenerate slivers at the poles
            val cx = ax + ux / 3 + wx / 3; val cy = ay + uy / 3 + wy / 3; val cz = az + uz / 3 + wz / 3
            assertTrue("triangle $t faces inward", nx * cx + ny * cy + nz * cz > 0.0)
            checked++
        }
        assertTrue(checked > 16 * 32)
    }
}
