package com.rm.apogee.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Winding checks for every hand-built primitive. A wrongly wound shape still looks roughly right,
 * because culling just keeps the far side, so it needs a test.
 *
 * Each triangle's cross-product normal has to agree with the normals stored on its vertices.
 */
class MeshShapesTest {

    @Test
    fun `cylinder faces outward`() = assertWoundOutward(MeshShapes.cylinder(0.625f, 2f))

    @Test
    fun `cone faces outward`() = assertWoundOutward(MeshShapes.frustum(0.55f, 0.625f, 1.4f))

    @Test
    fun `nose cone faces outward`() = assertWoundOutward(MeshShapes.frustum(0.625f, 0.05f, 1f))

    @Test
    fun `box faces outward`() = assertWoundOutward(MeshShapes.box(0.7f, 0.6f, 0.1f))

    @Test
    fun `sphere faces outward`() = assertWoundOutward(MeshShapes.sphere(1f))

    @Test
    fun `capped ends face outward`() {
        for (caps in listOf(StackCaps.TOP, StackCaps.BOTTOM, StackCaps.BOTH)) {
            assertWoundOutward(MeshShapes.cylinder(0.625f, 2f, caps = caps))
        }
    }

    /** Leaving out a cap removes triangles and nothing else. */
    @Test
    fun `cap mask controls triangle count`() {
        val both = MeshShapes.cylinder(0.625f, 2f, caps = StackCaps.BOTH).indices.size
        val top = MeshShapes.cylinder(0.625f, 2f, caps = StackCaps.TOP).indices.size
        val none = MeshShapes.cylinder(0.625f, 2f, caps = 0).indices.size
        assertTrue(none < top && top < both)
        assertEquals(both - top, top - none)
    }

    /** A cone tip has no disc to draw, so asking for that cap adds nothing. */
    @Test
    fun `degenerate cap is skipped`() {
        val withCap = MeshShapes.frustum(0.625f, 0f, 1f, caps = StackCaps.TOP)
        val without = MeshShapes.frustum(0.625f, 0f, 1f, caps = 0)
        assertEquals(without.indices.size, withCap.indices.size)
    }

    private fun assertWoundOutward(mesh: MeshData) {
        val stride = Mesh.STRIDE_FLOATS
        assertTrue("mesh has triangles", mesh.indices.isNotEmpty())
        assertEquals(0, mesh.indices.size % 3)

        for (t in mesh.indices.indices step 3) {
            val i0 = mesh.indices[t] * stride
            val i1 = mesh.indices[t + 1] * stride
            val i2 = mesh.indices[t + 2] * stride

            val e1 = floatArrayOf(
                mesh.vertices[i1] - mesh.vertices[i0],
                mesh.vertices[i1 + 1] - mesh.vertices[i0 + 1],
                mesh.vertices[i1 + 2] - mesh.vertices[i0 + 2],
            )
            val e2 = floatArrayOf(
                mesh.vertices[i2] - mesh.vertices[i0],
                mesh.vertices[i2 + 1] - mesh.vertices[i0 + 1],
                mesh.vertices[i2 + 2] - mesh.vertices[i0 + 2],
            )
            val face = floatArrayOf(
                e1[1] * e2[2] - e1[2] * e2[1],
                e1[2] * e2[0] - e1[0] * e2[2],
                e1[0] * e2[1] - e1[1] * e2[0],
            )

            // A sphere's polar triangles are degenerate and have no winding.
            val area = face[0] * face[0] + face[1] * face[1] + face[2] * face[2]
            if (area < 1e-12f) continue

            // Against the average of the three normals, since a cone's side normals tilt.
            var nx = 0f; var ny = 0f; var nz = 0f
            for (v in intArrayOf(i0, i1, i2)) {
                nx += mesh.vertices[v + 3]; ny += mesh.vertices[v + 4]; nz += mesh.vertices[v + 5]
            }

            val dot = face[0] * nx + face[1] * ny + face[2] * nz
            assertTrue(
                "triangle at index $t is wound inward (dot $dot)",
                dot > 0f,
            )
        }
    }
}
