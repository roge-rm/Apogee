package com.rm.apogee.render

import com.rm.apogee.core.part.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Every model shape is a closed solid wound outward. It's checked in a way that can't be fooled by
 * how a generator happens to list its corners. Summed over a closed surface, area-weighted normals
 * cancel, and the divergence theorem gives a positive volume only when every face points out.
 */
class ModelShapesTest {

    private val shapes: Map<String, ModelSpec> = mapOf(
        "lathe" to ModelSpec.Lathe(listOf(listOf(0.5, -1.0), listOf(0.6, 0.0), listOf(0.3, 0.8), listOf(0.0, 1.0))),
        "nose cone" to ModelSpec.NoseCone(0.625, 1.6),
        // An engine bell: in at the throat, out round the rim, and up the outside.
        "bell" to ModelSpec.Lathe(listOf(listOf(0.0, -0.3), listOf(0.2, -0.35), listOf(0.5, -0.7), listOf(0.55, -0.7), listOf(0.62, 0.4), listOf(0.62, 0.7))),
        "tank" to ModelSpec.Tank(0.625, 2.0, bands = 2),
        "fin" to ModelSpec.Fin(0.8, 0.3, 0.7, sweep = 0.3),
        "loft" to ModelSpec.Loft(
            listOf(
                ModelSpec.Loft.Section(-2.0, 0.8, 0.4, -0.4, vee = 0.6),
                ModelSpec.Loft.Section(1.0, 1.0, 0.5, -0.5, vee = 0.5),
                ModelSpec.Loft.Section(2.0, 0.2, 0.5, 0.1),
            ),
        ),
        "tyre" to ModelSpec.Tyre(0.35, 0.25),
        "prop" to ModelSpec.Prop(0.9, blades = 3),
    )

    @Test
    fun `every shape is closed and faces outward`() {
        for ((name, spec) in shapes) {
            val data = ModelShapes.build(spec)
            val v = data.vertices
            val stride = Mesh.STRIDE_FLOATS
            var volume = 0.0
            var sx = 0.0; var sy = 0.0; var sz = 0.0
            var area = 0.0
            for (t in 0 until data.indices.size / 3) {
                val p = Array(3) { k -> val i = data.indices[t * 3 + k] * stride; doubleArrayOf(v[i].toDouble(), v[i + 1].toDouble(), v[i + 2].toDouble()) }
                val ux = p[1][0] - p[0][0]; val uy = p[1][1] - p[0][1]; val uz = p[1][2] - p[0][2]
                val wx = p[2][0] - p[0][0]; val wy = p[2][1] - p[0][1]; val wz = p[2][2] - p[0][2]
                val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
                sx += cx; sy += cy; sz += cz
                area += sqrt(cx * cx + cy * cy + cz * cz)
                volume += (p[0][0] * (p[1][1] * p[2][2] - p[1][2] * p[2][1]) -
                    p[0][1] * (p[1][0] * p[2][2] - p[1][2] * p[2][0]) +
                    p[0][2] * (p[1][0] * p[2][1] - p[1][1] * p[2][0])) / 6.0
                // The stored normal agrees with the winding.
                val i0 = data.indices[t * 3] * stride
                val dot = v[i0 + 3] * cx + v[i0 + 4] * cy + v[i0 + 5] * cz
                assertTrue("$name: triangle $t's normal disagrees with its winding", dot >= 0.0)
            }
            val open = sqrt(sx * sx + sy * sy + sz * sz) / area
            assertTrue("$name should be closed (open fraction $open)", open < 1e-4)
            assertTrue("$name should face outward (volume $volume)", volume > 0.0)
        }
    }

    @Test
    fun `a nose cone meets the stack at its full radius and comes to a point`() {
        val data = ModelShapes.build(ModelSpec.NoseCone(0.625, 1.0))
        val v = data.vertices
        var bottomRadius = 0.0
        var topRadius = 0.0
        var i = 0
        while (i < v.size) {
            val r = sqrt((v[i] * v[i] + v[i + 2] * v[i + 2]).toDouble())
            if (abs(v[i + 1] + 0.5f) < 1e-4) bottomRadius = maxOf(bottomRadius, r)
            if (v[i + 1] > 0.45f) topRadius = maxOf(topRadius, r)
            i += Mesh.STRIDE_FLOATS
        }
        assertEquals(0.625, bottomRadius, 1e-3)
        assertTrue("the tip should be nearly a point, radius $topRadius", topRadius < 0.08)
    }
}
