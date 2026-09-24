package com.rm.apogee.render

import com.rm.apogee.core.part.Shape
import com.rm.apogee.core.terrain.Noise
import kotlin.math.sqrt

/**
 * A unit cloud puff, drawn stretched to each lobe's size. [variant] picks its
 * lumps, so neighbouring lobes do not match; [flat] squashes it into a deck
 * puff for layer cloud.
 */
data class CloudPuff(
    val variant: Int,
    val flat: Boolean = false,
    /** Subdivisions of the icosahedron: 3 close (1,280 facets), 2 near (320), 1 far (80). */
    val detail: Int = 2,
) : Shape

/**
 * Cloud meshes in the game's own style: faceted, flat-shaded heaps, like
 * everything else - a subdivided icosahedron pushed in and out by noise,
 * with its underside cut flat, because cumulus have flat bases.
 */
object CloudShapes {

    const val VARIANTS = 12

    fun puff(shape: CloudPuff): MeshData {
        val (vertices, faces) = icosphere(shape.detail.coerceIn(0, 3))
        val seed = 0xC10D + shape.variant * 7919
        val displaced = vertices.map { v ->
            // Broad billows, gently: a finer, stronger layer pushed the
            // facets out into spikes and cracks - rock, not cloud.
            val bump = 1.0 + 0.2 * Noise.simplex(seed, v[0] * 1.3, v[1] * 1.3, v[2] * 1.3) +
                0.05 * Noise.simplex(seed + 1, v[0] * 2.6, v[1] * 2.6, v[2] * 2.6)
            val p = doubleArrayOf(v[0] * bump, v[1] * bump, v[2] * bump)
            if (shape.flat) p[1] *= 0.55
            // A flat base: cloud forms at a level, and stops there.
            if (p[1] < BASE) p[1] = BASE
            p
        }
        val soup = ModelShapes.Soup()
        val inside = doubleArrayOf(0.0, 0.0, 0.0)
        for (f in faces) soup.tri(displaced[f[0]], displaced[f[1]], displaced[f[2]], inside)
        return soup.data()
    }

    /** How low a puff reaches, as a fraction of its vertical radius. */
    private const val BASE = -0.4

    private fun icosphere(subdivisions: Int): Pair<List<DoubleArray>, List<IntArray>> {
        val t = (1.0 + sqrt(5.0)) / 2.0
        val vertices = mutableListOf(
            doubleArrayOf(-1.0, t, 0.0), doubleArrayOf(1.0, t, 0.0), doubleArrayOf(-1.0, -t, 0.0), doubleArrayOf(1.0, -t, 0.0),
            doubleArrayOf(0.0, -1.0, t), doubleArrayOf(0.0, 1.0, t), doubleArrayOf(0.0, -1.0, -t), doubleArrayOf(0.0, 1.0, -t),
            doubleArrayOf(t, 0.0, -1.0), doubleArrayOf(t, 0.0, 1.0), doubleArrayOf(-t, 0.0, -1.0), doubleArrayOf(-t, 0.0, 1.0),
        ).map { normalise(it) }.toMutableList()
        var faces = listOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
            intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
            intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
        )
        repeat(subdivisions) {
            val midpoints = HashMap<Long, Int>()
            fun middle(a: Int, b: Int): Int {
                val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
                return midpoints.getOrPut(key) {
                    val va = vertices[a]; val vb = vertices[b]
                    vertices.add(normalise(doubleArrayOf((va[0] + vb[0]) / 2, (va[1] + vb[1]) / 2, (va[2] + vb[2]) / 2)))
                    vertices.size - 1
                }
            }
            faces = faces.flatMap { f ->
                val ab = middle(f[0], f[1]); val bc = middle(f[1], f[2]); val ca = middle(f[2], f[0])
                listOf(intArrayOf(f[0], ab, ca), intArrayOf(f[1], bc, ab), intArrayOf(f[2], ca, bc), intArrayOf(ab, bc, ca))
            }
        }
        return vertices to faces
    }

    private fun normalise(v: DoubleArray): DoubleArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }
}
