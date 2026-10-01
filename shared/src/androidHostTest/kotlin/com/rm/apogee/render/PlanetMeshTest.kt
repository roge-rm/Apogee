package com.rm.apogee.render

import org.junit.Assert.assertTrue
import org.junit.Test

class PlanetMeshTest {

    /** Every triangle of the globe faces out, or culling hides the near side. */
    @Test
    fun `the globe faces outward`() {
        val data = PlanetMesh.buildGlobe(null, 600_000.0, 16)
        val stride = TerrainChunk.STRIDE_FLOATS
        val v = data.vertices
        var checked = 0
        for (t in 0 until data.indices.size / 3) {
            val i = IntArray(3) { data.indices[t * 3 + it] * stride }
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

    /** HIGH's globe has more than 65536 vertices, so its indices are 32-bit and must all fit. */
    @Test
    fun `the finest globe indexes past sixteen bits`() {
        val data = PlanetMesh.buildGlobe(null, 600_000.0, 256)
        val vertexCount = data.vertices.size / TerrainChunk.STRIDE_FLOATS
        assertTrue("expected more vertices than a short can index: $vertexCount", vertexCount > 65_536)
        assertTrue(data.indices.all { it in 0 until vertexCount })
        assertTrue("the last vertices are used", data.indices.max() == vertexCount - 1)
    }
}
