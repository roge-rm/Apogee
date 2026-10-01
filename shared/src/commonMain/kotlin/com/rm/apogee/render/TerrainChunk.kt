package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.CubeSphere
import com.rm.apogee.core.terrain.Terrain
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile

/**
 * One square of a cube face at one level of detail: face, level, and column and row among the
 * 2^level chunks along a side. Always [TerrainChunk.CELLS] cells across. At the finest level the
 * samples are exactly the collider's, so the drawn ground is the ground a craft rests on.
 */
data class ChunkKey(
    val face: Int,
    val level: Int,
    val i: Int,
    val j: Int,
    /** Which body's ground this is, since the GPU cache outlives a flight. */
    val body: Int = 0,
) {
    fun child(di: Int, dj: Int) = ChunkKey(face, level + 1, i * 2 + di, j * 2 + dj, body)
    val parent: ChunkKey? get() = if (level == 0) null else ChunkKey(face, level - 1, i / 2, j / 2, body)
}

/**
 * One chunk in a draw list, and which quarters to draw: fewer than four when it stands in for
 * children and only some are ready. Bit `dj * 2 + di` is the quarter [ChunkKey.child] (di, dj)
 * covers.
 */
class DrawEntry(val chunk: ChunkData, val quadrants: Int = ALL_QUADRANTS) {
    val key: ChunkKey get() = chunk.key

    companion object {
        const val ALL_QUADRANTS = 0xF
    }
}

/** Built geometry for a chunk, ready to upload. */
class ChunkData(
    val key: ChunkKey,
    /** [TerrainChunk.STRIDE_FLOATS] per vertex, relative to [centre]. Cleared once uploaded. */
    @Volatile var vertices: FloatArray?,
    /** Body-fixed, in metres from the body's centre. */
    val centre: Vec3,
    /** The radius of a sphere around [centre] that holds every vertex, in metres. */
    val boundingRadius: Double,
)

object TerrainChunk {

    /** Cells along each side. 32 was far too many triangles for a phone and the low-poly look. */
    const val CELLS = 16

    /** Vertices along a side. */
    const val SIDE = CELLS + 1

    /**
     * Depth code: the wet attribute is 0 on land, 1 + depth/1000 on the seabed (depth up to this
     * many metres), and 4 more on a skirt.
     */
    const val MAX_DEPTH_CODE = 999.0

    /** Position(3), normal(3), colour(3), wet(1). */
    const val STRIDE_FLOATS = 10

    /** Grid vertices plus four skirts of [SIDE] each. */
    const val VERTEX_COUNT = SIDE * SIDE + 4 * SIDE

    /**
     * The level whose samples line up with the collider's tiles. A tile is 64 cells and a chunk is
     * [CELLS], so there are 64 / CELLS chunks per tile along each side.
     */
    fun finestLevel(tilesPerFace: Int): Int =
        (tilesPerFace).countTrailingZeroBits() + (64 / CELLS).countTrailingZeroBits()

    /** Metres across a chunk at [level] on a body of [radius]. */
    fun size(radius: Double, level: Int): Double = radius * Math.PI / 2.0 / (1 shl level)

    /**
     * The triangle list every chunk shares: the grid, split on the collider's diagonal, and the
     * skirts. Laid out a quarter at a time (quarter `dj * 2 + di` holds the cells [ChunkKey.child]
     * (di, dj) covers, plus its skirt), so a chunk can draw only the quarters it needs.
     */
    val indices: ShortArray by lazy {
        val half = CELLS / 2
        val list = ArrayList<Int>(CELLS * CELLS * 6 + 4 * CELLS * 12)
        // Skirts: strips hanging from each edge to hide cracks against coarser neighbours, wound
        // both ways so they're double-sided.
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
                // The collider's u + v <= 1 split. u x v is outward, so this winds outward.
                list += a; list += b; list += c
                list += d; list += c; list += b
            }
            edges.forEachIndexed { e, edge ->
                // Which quarter each segment of edge e is in. Edges 0 and 1 run along p, 2 and 3
                // along q.
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

    /** Indices in each quarter's block of [indices]. The blocks are all the same size. */
    val quadrantIndexCount: Int by lazy { indices.size / 4 }

    /**
     * Samples [terrain] into [key]'s geometry, with a one-cell border for normals and slope. Same
     * central differences as the collider's tiles, so materials line up.
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

        // The chunk's origin is its middle sample, so vertex coordinates stay small for floats.
        val mid = (SIDE / 2 + 1) * bordered + (SIDE / 2 + 1)
        val sea = terrain.hasOcean
        // The seabed at its real depth. The shader lifts it to the water far off. See
        // [Shaders.TERRAIN_VERTEX].
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

            // The normal comes from the drawn surface either side.
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
            // Slope from the true ground, as the collider's tiles measure it.
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
            // The land's own colour, sea floor included. Paving is drawn over it (see Paving).
            val material = terrain.groundMaterial(direction, height, slope)
            // Keyed on the global grid position, so shared vertices get the same jitter.
            val jitterKey = jitterKeyFor(key, p, q)
            TerrainPalette.colour(material, height, jitterKey, vertices, base + 6, terrain.world)
            // Under the sea, the bed's depth, so the shader can lift it to the water past the waves.
            vertices[base + 9] = if (sea && height < 0.0) (1.0 + kotlin.math.min(-height, MAX_DEPTH_CODE) / 1_000.0).toFloat() else 0f
        }

        // Skirts: each edge vertex dropped a few cells straight down, keeping normal and colour.
        val drop = (cellMetres * 3.0).toFloat()
        val edgeIndex = arrayOf(
            IntArray(SIDE) { it }, IntArray(SIDE) { CELLS * SIDE + it },
            IntArray(SIDE) { it * SIDE }, IntArray(SIDE) { it * SIDE + CELLS },
        )
        edgeIndex.forEachIndexed { e, edge ->
            for (k in 0 until SIDE) {
                val from = edge[k] * STRIDE_FLOATS
                val to = (SIDE * SIDE + e * SIDE + k) * STRIDE_FLOATS
                vertices.copyInto(vertices, to, from, from + STRIDE_FLOATS)
                // Down is towards the body's centre.
                val px = vertices[from] + centre.x; val py = vertices[from + 1] + centre.y; val pz = vertices[from + 2] + centre.z
                val l = kotlin.math.sqrt(px * px + py * py + pz * pz)
                vertices[to] -= (px / l * drop).toFloat()
                vertices[to + 1] -= (py / l * drop).toFloat()
                vertices[to + 2] -= (pz / l * drop).toFloat()
                // +4 marks a skirt, so the shader lights it with the ground's normal, not its own
                // vertical face, which would show as a dark line along the seam.
                vertices[to + 9] += 4f
            }
        }

        return ChunkData(key, vertices, centre, kotlin.math.sqrt(bound) + drop)
    }

    /** A stable id for grid point (p, q) of [key], shared by every chunk and level with that point. */
    private fun jitterKeyFor(key: ChunkKey, p: Int, q: Int): Int {
        val shift = 20 - key.level
        val gx = ((key.i * CELLS + p).toLong() shl shift.coerceAtLeast(0)).toInt()
        val gy = ((key.j * CELLS + q).toLong() shl shift.coerceAtLeast(0)).toInt()
        return gx * 73856093 xor gy * 19349663 xor key.face * 83492791
    }
}
