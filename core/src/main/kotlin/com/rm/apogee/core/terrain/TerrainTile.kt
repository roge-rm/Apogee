package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3

/** The ground at one point, as the collider needs it. Mutable, reused. */
class GroundPoint {
    /** Distance from the body's centre to the surface along the query direction. */
    var radius: Double = 0.0
    /** Outward surface normal, body-fixed. The face's, not the radial direction. */
    val normal = Vec3()
    var material: SurfaceMaterial = SurfaceMaterial.GRASS
}

/**
 * A square of sampled ground: heights and materials on a grid, built once.
 *
 * The collider reads these instead of evaluating the height field under every
 * contact point every tick. That is what makes a richer field affordable - a
 * tile costs a few thousand samples once and is then consulted for as long as
 * anything is on it - and it makes the surface a craft touches exactly the
 * one drawn: flat triangles between the same samples the finest mesh uses,
 * rather than the smooth function the mesh only approximates.
 *
 * Addressed on the [CubeSphere]: [face], and [i], [j] among [tilesPerFace]
 * tiles along each side. Samples run 0..[CELLS] inclusive on each axis, so
 * neighbouring tiles share their edge samples and the surface is seamless.
 */
class TerrainTile(
    val face: Int,
    val i: Int,
    val j: Int,
    val tilesPerFace: Int,
    /** Elevation above the datum, metres, row-major, (CELLS + 1)². */
    val elevations: FloatArray,
    /** [SurfaceMaterial] ordinals, laid out as [elevations]. */
    val materials: ByteArray,
    /** tan-warped face coordinate of each sample column, (CELLS + 1). */
    private val warpedS: DoubleArray,
    /** ...and of each sample row. */
    private val warpedT: DoubleArray,
) {
    @Volatile var lastUsed: Long = 0L

    private val corner = Vec3()

    fun elevation(p: Int, q: Int): Double = elevations[q * STRIDE + p].toDouble()

    fun material(p: Int, q: Int): SurfaceMaterial = SurfaceMaterial.of(materials[q * STRIDE + p].toInt())

    /** Surface position of sample (p, q), body-fixed, metres from the centre. */
    fun position(p: Int, q: Int, bodyRadius: Double, out: Vec3): Vec3 {
        CubeSphere.directionWarped(face, warpedS[p], warpedT[q], out)
        return out.mulInPlace(bodyRadius + elevation(p, q))
    }

    /**
     * The ground along [direction], which must lie on this tile.
     *
     * Found by intersecting the ray from the centre with the flat triangle the
     * point falls in - the same triangle a mesh built from these samples
     * draws - so the height is the drawn height and the normal is the face's.
     * The normal is what lets a cliff push a craft back rather than up: a
     * radial normal treats a wall as a floor that happens to be very high.
     *
     * @param fx, fy position within the tile in cells, 0..CELLS.
     */
    fun ground(direction: Vec3, fx: Double, fy: Double, bodyRadius: Double, out: GroundPoint, scratch: Scratch) {
        val cx = kotlin.math.floor(fx).toInt().coerceIn(0, CELLS - 1)
        val cy = kotlin.math.floor(fy).toInt().coerceIn(0, CELLS - 1)
        val u = fx - cx
        val v = fy - cy

        // Each cell split along the same diagonal the mesh uses.
        val a = scratch.a; val b = scratch.b; val c = scratch.c
        // The sample with the largest barycentric weight decides the material.
        val nearest: Int
        if (u + v <= 1.0) {
            position(cx, cy, bodyRadius, a)
            position(cx + 1, cy, bodyRadius, b)
            position(cx, cy + 1, bodyRadius, c)
            val wa = 1.0 - u - v
            nearest = when {
                u >= v && u >= wa -> cy * STRIDE + cx + 1
                v >= wa -> (cy + 1) * STRIDE + cx
                else -> cy * STRIDE + cx
            }
        } else {
            position(cx + 1, cy + 1, bodyRadius, a)
            position(cx, cy + 1, bodyRadius, b)
            position(cx + 1, cy, bodyRadius, c)
            val wa = u + v - 1.0
            val wb = 1.0 - u
            val wc = 1.0 - v
            nearest = when {
                wb >= wc && wb >= wa -> (cy + 1) * STRIDE + cx
                wc >= wa -> cy * STRIDE + cx + 1
                else -> (cy + 1) * STRIDE + cx + 1
            }
        }

        // Normal of the triangle, outward.
        val e1 = scratch.e1.setTo(b).subInPlace(a)
        val e2 = scratch.e2.setTo(c).subInPlace(a)
        val n = out.normal.setTo(e1).crossInPlace(e2)
        if ((n dot a) < 0.0) n.negateInPlace()
        n.normalizeInPlace()

        // Where the ray from the centre crosses the triangle's plane.
        val along = n dot direction
        out.radius = if (along > 1e-9) (n dot a) / along else a.length
        out.material = SurfaceMaterial.of(materials[nearest].toInt())
    }

    /** Per-caller scratch vectors, so lookups allocate nothing. */
    class Scratch {
        val a = Vec3(); val b = Vec3(); val c = Vec3()
        val e1 = Vec3(); val e2 = Vec3()
    }

    companion object {
        /** Cells along each side of a tile. */
        const val CELLS = 64
        const val STRIDE = CELLS + 1

        /** Target tile width, metres; the actual is the nearest that tiles a face in a power of two. */
        const val TARGET_TILE_METRES = 128.0

        /**
         * Samples [terrain] into the tile at ([face], [i], [j]).
         *
         * Samples a one-cell border beyond the tile as well, only to measure
         * the slope at its edge samples the same way the neighbouring tile
         * does - otherwise a material boundary could jump at every tile seam.
         */
        fun build(terrain: Terrain, face: Int, i: Int, j: Int, tilesPerFace: Int): TerrainTile {
            val bordered = CELLS + 3
            val warpedS = DoubleArray(bordered)
            val warpedT = DoubleArray(bordered)
            for (k in 0 until bordered) {
                val cell = (k - 1).toDouble()
                warpedS[k] = CubeSphere.warp(-1.0 + 2.0 * (i + cell / CELLS) / tilesPerFace)
                warpedT[k] = CubeSphere.warp(-1.0 + 2.0 * (j + cell / CELLS) / tilesPerFace)
            }

            val radius = terrain.bodyRadius
            // Flat arrays, not a Vec3 per sample. A tile is four and a half
            // thousand samples, built on a background thread while the game
            // runs, and per-sample objects became garbage collections long
            // enough to stall the simulation tick they were meant to spare.
            val count = bordered * bordered
            val dx = DoubleArray(count); val dy = DoubleArray(count); val dz = DoubleArray(count)
            val heights = DoubleArray(count)
            val direction = Vec3()
            for (q in 0 until bordered) for (p in 0 until bordered) {
                val index = q * bordered + p
                CubeSphere.directionWarped(face, warpedS[p], warpedT[q], direction)
                dx[index] = direction.x; dy[index] = direction.y; dz[index] = direction.z
                heights[index] = terrain.elevation(direction)
            }

            val elevations = FloatArray(STRIDE * STRIDE)
            val materials = ByteArray(STRIDE * STRIDE)
            for (q in 0 until STRIDE) for (p in 0 until STRIDE) {
                val index = (q + 1) * bordered + (p + 1)
                val r = index + 1; val l = index - 1
                val u = index + bordered; val d = index - bordered
                // Central differences of the surface positions either side.
                val ex = dx[r] * (radius + heights[r]) - dx[l] * (radius + heights[l])
                val ey = dy[r] * (radius + heights[r]) - dy[l] * (radius + heights[l])
                val ez = dz[r] * (radius + heights[r]) - dz[l] * (radius + heights[l])
                val nx0 = dx[u] * (radius + heights[u]) - dx[d] * (radius + heights[d])
                val ny0 = dy[u] * (radius + heights[u]) - dy[d] * (radius + heights[d])
                val nz0 = dz[u] * (radius + heights[u]) - dz[d] * (radius + heights[d])
                val cx = ey * nz0 - ez * ny0
                val cy = ez * nx0 - ex * nz0
                val cz = ex * ny0 - ey * nx0
                val length = kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
                val cosine = if (length > 0.0) (cx * dx[index] + cy * dy[index] + cz * dz[index]) / length else 1.0
                // 0 on the flat, 1 on a wall - the same measure the renderer uses.
                val slope = (1.0 - kotlin.math.abs(cosine)).coerceIn(0.0, 1.0)
                val height = heights[index]
                direction.setTo(dx[index], dy[index], dz[index])
                elevations[q * STRIDE + p] = height.toFloat()
                materials[q * STRIDE + p] = terrain.material(direction, height, slope).ordinal.toByte()
            }

            return TerrainTile(
                face, i, j, tilesPerFace, elevations, materials,
                warpedS.copyOfRange(1, 1 + STRIDE), warpedT.copyOfRange(1, 1 + STRIDE),
            )
        }

        /** Tiles along a face's side for a body of [radius], so tiles come out near [TARGET_TILE_METRES]. */
        fun tilesPerFace(radius: Double): Int {
            val faceArc = radius * Math.PI / 2.0
            var tiles = 1
            while (faceArc / tiles > TARGET_TILE_METRES) tiles *= 2
            return tiles
        }
    }
}
