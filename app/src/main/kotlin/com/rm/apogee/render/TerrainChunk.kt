package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.CubeSphere
import com.rm.apogee.core.terrain.Terrain

/**
 * One square of a cube face at one level of detail: face, level, and its
 * column and row among the 2^level chunks along that face's side.
 *
 * A chunk is [TerrainChunk.CELLS] cells across at every level, so each level
 * down halves the size of the facets. At the finest level a chunk's samples are exactly
 * the collider's tile samples - same positions on the same grid - so the
 * ground drawn under a craft is the ground it rests on.
 */
data class ChunkKey(
    val face: Int,
    val level: Int,
    val i: Int,
    val j: Int,
    /**
     * Which body's ground this is. Every body has the same faces and levels,
     * and the GPU cache outlives a flight: without this, Luna's chunk at a
     * given place on the grid would be drawn with Terra's mesh.
     */
    val body: Int = 0,
) {
    fun child(di: Int, dj: Int) = ChunkKey(face, level + 1, i * 2 + di, j * 2 + dj, body)
    val parent: ChunkKey? get() = if (level == 0) null else ChunkKey(face, level - 1, i / 2, j / 2, body)
}

/** Built geometry for a chunk, ready to upload. */
/**
 * One chunk in a draw list, and which of its quarters to draw: all four
 * normally, fewer when it is standing in for children only some of which
 * are ready. Bit `dj * 2 + di` is the quarter [ChunkKey.child] (di, dj) covers.
 */
class DrawEntry(val chunk: ChunkData, val quadrants: Int = ALL_QUADRANTS) {
    val key: ChunkKey get() = chunk.key

    companion object {
        const val ALL_QUADRANTS = 0xF
    }
}

class ChunkData(
    val key: ChunkKey,
    /**
     * [TerrainChunk.STRIDE_FLOATS] per vertex, relative to [centre]. Cleared
     * once uploaded - the GPU has it, and a few hundred chunks of vertex data
     * kept on the heap as well would be tens of megabytes for nothing.
     */
    @Volatile var vertices: FloatArray?,
    /** Body-fixed, metres from the body's centre. */
    val centre: Vec3,
    /** Radius of a sphere around [centre] containing every vertex, metres. */
    val boundingRadius: Double,
)

object TerrainChunk {

    /**
     * Cells along each side.
     *
     * 16, not 32. At 32 the split distances that keep the ground detailed out
     * to the horizon put triangles about seventeen pixels across and drew
     * three-quarters of a million of them on the ground - far finer than a
     * low-poly look wants and far more than a phone can afford. Sixteen gives
     * facets a style can own, a quarter of the triangles, and a quarter of the
     * build time per chunk.
     */
    const val CELLS = 16

    /** Vertices along a side. */
    const val SIDE = CELLS + 1

    /**
     * Depth code: the wet attribute is 0 on land, 1 + depth/1000 on the sea
     * bed (depth to this many metres), and 4 more on a skirt.
     */
    const val MAX_DEPTH_CODE = 999.0

    /** Position(3), normal(3), colour(3), wet(1). */
    const val STRIDE_FLOATS = 10

    /** Grid vertices plus four skirts of [SIDE] each. */
    const val VERTEX_COUNT = SIDE * SIDE + 4 * SIDE

    /**
     * The level whose samples coincide with the collider's tiles: a tile is
     * 64 cells and a chunk [CELLS], so 64 / CELLS chunks per tile along each side.
     */
    fun finestLevel(tilesPerFace: Int): Int =
        Integer.numberOfTrailingZeros(tilesPerFace) + Integer.numberOfTrailingZeros(64 / CELLS)

    /** Metres across a chunk at [level] on a body of [radius]. */
    fun size(radius: Double, level: Int): Double = radius * Math.PI / 2.0 / (1 shl level)

    /**
     * The triangle list every chunk shares: the grid, split along the same
     * diagonal the collider's tiles use, and the skirts.
     *
     * One index buffer for all chunks, because the topology never changes -
     * only the vertices do. Laid out a quarter at a time - quarter
     * `dj * 2 + di` holds the cells [ChunkKey.child] (di, dj) covers and the
     * skirt along its share of the edge - so a chunk standing in for only
     * some of its children can draw just the quarters they leave uncovered.
     */
    val indices: ShortArray by lazy {
        val half = CELLS / 2
        val list = ArrayList<Int>(CELLS * CELLS * 6 + 4 * CELLS * 12)
        // Skirts: a strip hanging down from each edge, hiding the cracks
        // where a chunk meets a coarser neighbour whose edge cuts its corners.
        // Drawn double-sided in effect by winding each strip both ways.
        val edges = arrayOf(
            IntArray(SIDE) { it },                         // q = 0
            IntArray(SIDE) { CELLS * SIDE + it },         // q = CELLS
            IntArray(SIDE) { it * SIDE },                  // p = 0
            IntArray(SIDE) { it * SIDE + CELLS },          // p = CELLS
        )
        for (quarter in 0 until 4) {
            val di = quarter % 2
            val dj = quarter / 2
            for (q in dj * half until dj * half + half) for (p in di * half until di * half + half) {
                val a = q * SIDE + p
                val b = a + 1
                val c = a + SIDE
                val d = c + 1
                // (p,q) (p+1,q) (p,q+1), then (p+1,q+1) (p,q+1) (p+1,q) - the
                // collider's u + v <= 1 split. Winding is made outward-facing by
                // the face axes (u x v = outward).
                list += a; list += b; list += c
                list += d; list += c; list += b
            }
            edges.forEachIndexed { e, edge ->
                // Which quarter each segment of edge e belongs to: edges 0 and 1
                // run along p (so di varies), 2 and 3 along q (dj varies).
                val fixed = when (e) { 0 -> dj == 0; 1 -> dj == 1; 2 -> di == 0; else -> di == 1 }
                if (!fixed) return@forEachIndexed
                val along = if (e < 2) di else dj
                val skirtBase = SIDE * SIDE + e * SIDE
                for (k in along * half until along * half + half) {
                    val top0 = edge[k]; val top1 = edge[k + 1]
                    val low0 = skirtBase + k; val low1 = skirtBase + k + 1
                    list += top0; list += low0; list += top1
                    list += top1; list += low0; list += low1
                    list += top0; list += top1; list += low0
                    list += top1; list += low1; list += low0
                }
            }
        }
        ShortArray(list.size) { list[it].toShort() }
    }

    /** Indices in each quarter's block of [indices]; the blocks are equal. */
    val quadrantIndexCount: Int by lazy { indices.size / 4 }

    /**
     * Samples [terrain] into [key]'s geometry.
     *
     * Samples a one-cell border too, for normals and for the slope that picks
     * the material - the same bordered central differences the collider's
     * tiles use, so a material boundary lands in the same place in both.
     */
    fun build(terrain: Terrain, key: ChunkKey): ChunkData {
        val radius = terrain.bodyRadius
        val n = 1 shl key.level
        val bordered = SIDE + 2
        val warpedS = DoubleArray(bordered)
        val warpedT = DoubleArray(bordered)
        for (k in 0 until bordered) {
            val cell = (k - 1).toDouble()
            warpedS[k] = CubeSphere.warp(-1.0 + 2.0 * (key.i + cell / CELLS) / n)
            warpedT[k] = CubeSphere.warp(-1.0 + 2.0 * (key.j + cell / CELLS) / n)
        }

        val count = bordered * bordered
        val dx = DoubleArray(count); val dy = DoubleArray(count); val dz = DoubleArray(count)
        val heights = DoubleArray(count)
        val direction = Vec3()
        for (q in 0 until bordered) for (p in 0 until bordered) {
            val index = q * bordered + p
            CubeSphere.directionWarped(key.face, warpedS[p], warpedT[q], direction)
            dx[index] = direction.x; dy[index] = direction.y; dz[index] = direction.z
            heights[index] = terrain.elevation(direction)
        }

        // The chunk's own origin: the surface at its middle sample, so vertex
        // coordinates stay small enough for float to hold them to the
        // millimetre. A vertex 600 km from the planet's centre does not.
        val mid = (SIDE / 2 + 1) * bordered + (SIDE / 2 + 1)
        val sea = terrain.hasOcean
        // The sea bed at its real depth: the sea itself is drawn by the sea
        // (near) or lifted to it by the shader (far). See [Shaders.TERRAIN_VERTEX].
        val centreRadius = radius + if (sea) kotlin.math.max(heights[mid], -MAX_DEPTH_CODE) else heights[mid]
        val centre = Vec3(dx[mid] * centreRadius, dy[mid] * centreRadius, dz[mid] * centreRadius)

        val vertices = FloatArray(VERTEX_COUNT * STRIDE_FLOATS)
        val cellMetres = size(radius, key.level) / CELLS
        var bound = 0.0

        // No deeper than the shader can lift back up to the water.
        fun surface(index: Int): Double = radius + if (sea) kotlin.math.max(heights[index], -MAX_DEPTH_CODE) else heights[index]

        for (q in 0 until SIDE) for (p in 0 until SIDE) {
            val index = (q + 1) * bordered + (p + 1)
            val r = surface(index)
            val base = (q * SIDE + p) * STRIDE_FLOATS
            val x = dx[index] * r - centre.x
            val y = dy[index] * r - centre.y
            val z = dz[index] * r - centre.z
            vertices[base] = x.toFloat(); vertices[base + 1] = y.toFloat(); vertices[base + 2] = z.toFloat()
            bound = maxOf(bound, x * x + y * y + z * z)

            // Normal from the drawn surface either side.
            val right = index + 1; val left = index - 1
            val up = index + bordered; val down = index - bordered
            val ex = dx[right] * surface(right) - dx[left] * surface(left)
            val ey = dy[right] * surface(right) - dy[left] * surface(left)
            val ez = dz[right] * surface(right) - dz[left] * surface(left)
            val fx = dx[up] * surface(up) - dx[down] * surface(down)
            val fy = dy[up] * surface(up) - dy[down] * surface(down)
            val fz = dz[up] * surface(up) - dz[down] * surface(down)
            var nx = ey * fz - ez * fy
            var ny = ez * fx - ex * fz
            var nz = ex * fy - ey * fx
            val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 0.0) { nx /= length; ny /= length; nz /= length }
            if (nx * dx[index] + ny * dy[index] + nz * dz[index] < 0.0) { nx = -nx; ny = -ny; nz = -nz }
            vertices[base + 3] = nx.toFloat(); vertices[base + 4] = ny.toFloat(); vertices[base + 5] = nz.toFloat()

            val height = heights[index]
            if (sea && height < 0.0) {
                // The bed, and how deep it lies - for the shader to lift it
                // to the water beyond the waves, and colour it as water there.
                TerrainPalette.seabed(-height, jitterKeyFor(key, p, q), vertices, base + 6)
                vertices[base + 9] = (1.0 + kotlin.math.min(-height, MAX_DEPTH_CODE) / 1_000.0).toFloat()
            } else {
                // Slope from the true ground, not the drawn one, exactly as the
                // collider's tiles measure it for the material.
                val tx = dx[right] * (radius + heights[right]) - dx[left] * (radius + heights[left])
                val ty = dy[right] * (radius + heights[right]) - dy[left] * (radius + heights[left])
                val tz = dz[right] * (radius + heights[right]) - dz[left] * (radius + heights[left])
                val ux = dx[up] * (radius + heights[up]) - dx[down] * (radius + heights[down])
                val uy = dy[up] * (radius + heights[up]) - dy[down] * (radius + heights[down])
                val uz = dz[up] * (radius + heights[up]) - dz[down] * (radius + heights[down])
                val cx = ty * uz - tz * uy
                val cy = tz * ux - tx * uz
                val cz = tx * uy - ty * ux
                val cl = kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
                val cosine = if (cl > 0.0) (cx * dx[index] + cy * dy[index] + cz * dz[index]) / cl else 1.0
                val slope = (1.0 - kotlin.math.abs(cosine)).coerceIn(0.0, 1.0)
                direction.setTo(dx[index], dy[index], dz[index])
                // The land's own colour: the paving is drawn over it, straight-edged (see Paving).
                val material = terrain.groundMaterial(direction, height, slope)
                // Keyed on the global grid position at the finest spacing, so
                // a vertex shared by two chunks - or two levels - gets the
                // same jitter in both.
                val jitterKey = jitterKeyFor(key, p, q)
                TerrainPalette.colour(material, height, jitterKey, vertices, base + 6)
                vertices[base + 9] = 0f
            }
        }

        // Skirts: each edge vertex again, dropped a few cells' depth straight
        // down, keeping its normal and colour so the strip reads as ground.
        val drop = (cellMetres * 3.0).toFloat()
        val edgeIndex = arrayOf(
            IntArray(SIDE) { it }, IntArray(SIDE) { CELLS * SIDE + it },
            IntArray(SIDE) { it * SIDE }, IntArray(SIDE) { it * SIDE + CELLS },
        )
        edgeIndex.forEachIndexed { e, edge ->
            for (k in 0 until SIDE) {
                val from = edge[k] * STRIDE_FLOATS
                val to = (SIDE * SIDE + e * SIDE + k) * STRIDE_FLOATS
                System.arraycopy(vertices, from, vertices, to, STRIDE_FLOATS)
                // Down is towards the centre; the vertex's own position plus
                // the chunk centre is its direction from there.
                val px = vertices[from] + centre.x; val py = vertices[from + 1] + centre.y; val pz = vertices[from + 2] + centre.z
                val l = kotlin.math.sqrt(px * px + py * py + pz * pz)
                vertices[to] -= (px / l * drop).toFloat()
                vertices[to + 1] -= (py / l * drop).toFloat()
                vertices[to + 2] -= (pz / l * drop).toFloat()
                // Marked as skirt (+4 on the wet flag), so the shader lights it
                // with the ground's normal it carries rather than its own
                // vertical face - which, showing through a crack between
                // chunks, drew a dark dashed line along the seam.
                vertices[to + 9] += 4f
            }
        }

        return ChunkData(key, vertices, centre, kotlin.math.sqrt(bound) + drop)
    }

    /**
     * A stable id for the grid point (p, q) of [key], the same for every
     * chunk and level that shares that point.
     */
    private fun jitterKeyFor(key: ChunkKey, p: Int, q: Int): Int {
        val shift = 20 - key.level
        val gx = ((key.i * CELLS + p).toLong() shl shift.coerceAtLeast(0)).toInt()
        val gy = ((key.j * CELLS + q).toLong() shl shift.coerceAtLeast(0)).toInt()
        return gx * 73856093 xor gy * 19349663 xor key.face * 83492791
    }
}
