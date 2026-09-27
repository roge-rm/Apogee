package com.rm.apogee.render

import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.terrain.ScatterKind
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-poly shapes for scatter, one per [ScatterKind].
 *
 * Each is twenty to fifty triangles, faceted (every triangle has its own three vertices and its own
 * normal, the same flat look as the ground), and coloured per vertex, so a tree is a brown trunk
 * under a green crown without a texture. They're built at unit size in a local frame with +Y up.
 * The renderer scales each instance by its kind's usual size and its own variation, and turns it
 * about the vertical.
 */
object ScatterMeshes {

    /** Position(3), normal(3), colour(3). */
    const val STRIDE_FLOATS = 9

    class Data(val vertices: FloatArray, val indices: ShortArray)

    private class Builder {
        val v = ArrayList<Float>()
        fun triangle(a: FloatArray, b: FloatArray, c: FloatArray, r: Float, g: Float, bl: Float) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val wx = c[0] - a[0]; val wy = c[1] - a[1]; val wz = c[2] - a[2]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            nx /= l; ny /= l; nz /= l
            for (p in arrayOf(a, b, c)) {
                v += p[0]; v += p[1]; v += p[2]; v += nx; v += ny; v += nz; v += r; v += g; v += bl
            }
        }

        /** A solid made of rings: a list of rings (y, radius), closed at both ends. */
        fun lathe(rings: List<Pair<Float, Float>>, sides: Int, r: Float, g: Float, b: Float, twist: Float = 0f) {
            fun at(ring: Int, k: Int): FloatArray {
                val (y, rad) = rings[ring]
                val a = (k + twist) * 2.0 * Math.PI / sides
                return floatArrayOf((cos(a) * rad).toFloat(), y, (sin(a) * rad).toFloat())
            }
            for (ring in 0 until rings.size - 1) for (k in 0 until sides) {
                val a = at(ring, k); val bb = at(ring, k + 1)
                val c = at(ring + 1, k); val d = at(ring + 1, k + 1)
                triangle(a, c, bb, r, g, b)
                triangle(bb, c, d, r, g, b)
            }
            // The bottom cap.
            val bottom = rings.first()
            if (bottom.second > 1e-4f) {
                val centre = floatArrayOf(0f, bottom.first, 0f)
                for (k in 0 until sides) triangle(centre, at(0, k), at(0, k + 1), r * 0.8f, g * 0.8f, b * 0.8f)
            }
            val top = rings.last()
            if (top.second > 1e-4f) {
                val centre = floatArrayOf(0f, top.first, 0f)
                val last = rings.size - 1
                for (k in 0 until sides) triangle(centre, at(last, k + 1), at(last, k), r, g, b)
            }
        }

        /** An icosahedron, jittered by [seed] and squashed vertically by [squash]. */
        fun rock(seed: Int, cy: Float, radius: Float, squash: Float, r: Float, g: Float, b: Float) {
            val t = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
            val base = arrayOf(
                floatArrayOf(-1f, t, 0f), floatArrayOf(1f, t, 0f), floatArrayOf(-1f, -t, 0f), floatArrayOf(1f, -t, 0f),
                floatArrayOf(0f, -1f, t), floatArrayOf(0f, 1f, t), floatArrayOf(0f, -1f, -t), floatArrayOf(0f, 1f, -t),
                floatArrayOf(t, 0f, -1f), floatArrayOf(t, 0f, 1f), floatArrayOf(-t, 0f, -1f), floatArrayOf(-t, 0f, 1f),
            )
            val points = base.mapIndexed { i, p ->
                val l = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
                val jitter = (0.78 + 0.44 * Noise.hash(seed, i, 0, 0)).toFloat() * radius / l
                floatArrayOf(p[0] * jitter, p[1] * jitter * squash + cy, p[2] * jitter)
            }
            val faces = arrayOf(
                intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
                intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
                intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
                intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
            )
            faces.forEachIndexed { k, f ->
                // Each face is a shade off, which is what makes it look like rock.
                val shade = (0.9 + 0.2 * Noise.hash(seed + 1, k, 0, 0)).toFloat()
                triangle(points[f[0]], points[f[1]], points[f[2]], r * shade, g * shade, b * shade)
            }
        }

        fun build(): Data {
            val vertices = FloatArray(v.size) { v[it] }
            val count = v.size / STRIDE_FLOATS
            return Data(vertices, ShortArray(count) { it.toShort() })
        }
    }

    /**
     * A kind's mesh, at size 1 in metres: a boulder of its usual radius, or a tree of its usual
     * height.
     */
    fun build(kind: ScatterKind): Data {
        val b = Builder()
        when (kind) {
            ScatterKind.BOULDER_SMALL, ScatterKind.BOULDER_LARGE -> {
                // Centred the same way the collider is, sitting a little buried.
                val r = kind.radius.toFloat()
                b.rock(kind.ordinal * 31 + 7, r * 0.55f, r, 0.8f, 0.46f, 0.44f, 0.41f)
            }
            ScatterKind.CONIFER -> {
                val h = kind.height.toFloat()
                b.lathe(listOf(0f to 0.28f, h * 0.22f to 0.24f), 5, 0.36f, 0.25f, 0.16f)
                b.lathe(listOf(h * 0.18f to 1.9f, h * 0.55f to 0f), 6, 0.13f, 0.30f, 0.16f)
                b.lathe(listOf(h * 0.45f to 1.4f, h to 0f), 6, 0.15f, 0.34f, 0.18f, twist = 0.5f)
            }
            ScatterKind.BROADLEAF -> {
                val h = kind.height.toFloat()
                b.lathe(listOf(0f to 0.32f, h * 0.45f to 0.26f), 5, 0.38f, 0.27f, 0.17f)
                b.rock(311, h * 0.68f, 2.4f, 0.8f, 0.24f, 0.42f, 0.18f)
            }
            ScatterKind.DEAD_TREE -> {
                val h = kind.height.toFloat()
                b.lathe(listOf(0f to 0.25f, h to 0.06f), 4, 0.42f, 0.38f, 0.33f)
                // A single bare branch, leaning out.
                b.lathe(listOf(h * 0.55f to 0.12f, h * 0.85f to 0.03f), 4, 0.42f, 0.38f, 0.33f, twist = 0.25f)
            }
            ScatterKind.SHRUB -> b.rock(97, kind.height.toFloat() * 0.35f, kind.radius.toFloat(), 0.7f, 0.30f, 0.40f, 0.18f)
            ScatterKind.CACTUS -> {
                val h = kind.height.toFloat()
                b.lathe(listOf(0f to 0.3f, h to 0.25f, h + 0.1f to 0f), 6, 0.30f, 0.48f, 0.26f)
            }
            ScatterKind.PINNACLE -> {
                // A spire of rock, broad at its foot and leaning to a point, in two pieces turned
                // against each other.
                val h = kind.height.toFloat()
                val r = kind.radius.toFloat()
                b.lathe(listOf(-1f to r * 1.3f, h * 0.35f to r * 0.9f, h * 0.6f to r * 0.55f), 5, 0.40f, 0.37f, 0.33f)
                b.lathe(listOf(h * 0.55f to r * 0.6f, h * 0.85f to r * 0.3f, h to 0f), 5, 0.36f, 0.34f, 0.31f, twist = 0.5f)
            }
            ScatterKind.VENT -> {
                // A chimney: lumpy, stacked mineral, black at its mouth.
                val h = kind.height.toFloat()
                val r = kind.radius.toFloat()
                b.lathe(listOf(-0.5f to r * 1.4f, h * 0.3f to r * 0.9f, h * 0.55f to r * 1.05f, h * 0.8f to r * 0.7f), 6, 0.40f, 0.24f, 0.15f)
                b.lathe(listOf(h * 0.78f to r * 0.72f, h to r * 0.5f, h + 0.05f to 0f), 6, 0.10f, 0.09f, 0.08f, twist = 0.5f)
            }
            ScatterKind.NODULE -> {
                val r = kind.radius.toFloat()
                b.rock(kind.ordinal * 31 + 11, r * 0.4f, r, 0.6f, 0.20f, 0.19f, 0.18f)
            }
        }
        return b.build()
    }
}
