package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.CubeSphere
import com.rm.apogee.core.terrain.GroundPoint
import com.rm.apogee.core.terrain.TerrainField
import com.rm.apogee.core.terrain.TerrainTileCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawn ground against the collider's. At the finest level a chunk uses the same samples and
 * diagonal as the collider's tiles, so a craft rests on exactly what's drawn.
 */
class TerrainChunkTest {

    private val terra = TerrainField(bodyRadius = 600_000.0, homeDirection = Vec3(1.0, 0.0, 0.0))

    /** A finest-level chunk a little way off the pad, where there's some relief. */
    private fun chunkNearHome(): ChunkData {
        val level = TerrainChunk.finestLevel(terra.tiles.tilesPerFace)
        val coords = Vec3()
        val face = CubeSphere.locate(Vec3(1.0, 0.004, 0.003).normalizeInPlace(), coords)
        val n = 1 shl level
        val key = ChunkKey(face, level, ((coords.x + 1) * 0.5 * n).toInt(), ((coords.y + 1) * 0.5 * n).toInt())
        return TerrainChunk.build(terra, key)
    }

    private fun vertex(chunk: ChunkData, index: Int, out: Vec3): Vec3 {
        val v = chunk.vertices!!
        val base = index * TerrainChunk.STRIDE_FLOATS
        return out.setTo(v[base].toDouble(), v[base + 1].toDouble(), v[base + 2].toDouble()).addInPlace(chunk.centre)
    }

    @Test
    fun `the finest chunk is the collider's surface, to a centimetre`() {
        val chunk = chunkNearHome()
        val ground = GroundPoint()
        val lookup = TerrainTileCache.Lookup()
        val a = Vec3(); val b = Vec3(); val c = Vec3(); val p = Vec3()
        val side = TerrainChunk.SIDE
        for (q in 0 until TerrainChunk.CELLS) for (s in 0 until TerrainChunk.CELLS) {
            vertex(chunk, q * side + s, a)
            vertex(chunk, q * side + s + 1, b)
            vertex(chunk, (q + 1) * side + s, c)

            // At a vertex...
            terra.tiles.ground(a, ground, lookup)
            assertEquals("vertex $s,$q", a.length, ground.radius, 0.01)

            // ...and in the middle of the triangle the collider would use there.
            p.setTo(a).addInPlace(b).addInPlace(c).mulInPlace(1.0 / 3.0)
            terra.tiles.ground(p, ground, lookup)
            assertEquals("inside cell $s,$q", p.length, ground.radius, 0.01)
        }
    }

    /** Every triangle faces out of the planet, or back-face culling eats it. */
    @Test
    fun `chunk triangles face outward`() {
        val chunk = chunkNearHome()
        val indices = TerrainChunk.indices
        val a = Vec3(); val b = Vec3(); val c = Vec3()
        val grid = TerrainChunk.SIDE * TerrainChunk.SIDE
        var checked = 0
        for (t in 0 until indices.size / 3) {
            // Grid triangles only. Skirts are wound both ways on purpose.
            if ((0 until 3).any { (indices[t * 3 + it].toInt() and 0xFFFF) >= grid }) continue
            checked++
            vertex(chunk, indices[t * 3].toInt() and 0xFFFF, a)
            vertex(chunk, indices[t * 3 + 1].toInt() and 0xFFFF, b)
            vertex(chunk, indices[t * 3 + 2].toInt() and 0xFFFF, c)
            val normal = b.copy().subInPlace(a).crossInPlace(c.copy().subInPlace(a))
            assertTrue("triangle $t faces inward", (normal dot a) > 0.0)
        }
        assertEquals(TerrainChunk.CELLS * TerrainChunk.CELLS * 2, checked)
    }

    @Test
    fun `neighbouring chunks share their edge exactly`() {
        val left = chunkNearHome()
        val key = left.key
        val right = TerrainChunk.build(terra, key.copy(i = key.i + 1))
        val side = TerrainChunk.SIDE
        val a = Vec3(); val b = Vec3()
        for (q in 0 until side) {
            vertex(left, q * side + TerrainChunk.CELLS, a)
            vertex(right, q * side, b)
            assertTrue("edge vertex $q: ${a.distanceTo(b)} m apart", a.distanceTo(b) < 0.005)
        }
    }

    /**
     * The shared index buffer is four equal quarters, one per child, so a chunk can stand in for
     * just its missing children. Together: every cell twice (two triangles), every skirt once.
     */
    @Test
    fun `the index buffer is laid out a quarter at a time`() {
        val indices = TerrainChunk.indices
        val side = TerrainChunk.SIDE
        val half = TerrainChunk.CELLS / 2
        val perQuarter = TerrainChunk.quadrantIndexCount
        assertEquals(indices.size, perQuarter * 4)
        val grid = side * side
        val cellsSeen = HashMap<Int, Int>()
        for (quarter in 0 until 4) {
            val di = quarter % 2
            val dj = quarter / 2
            for (t in 0 until perQuarter / 3) {
                val tri = (0 until 3).map { indices[quarter * perQuarter + t * 3 + it].toInt() }
                val top = tri.filter { it < grid }
                // Every grid vertex is inside the quarter or on its edge.
                for (v in top) {
                    val p = v % side; val q = v / side
                    assertTrue("quarter $quarter has vertex ($p,$q)", p in di * half..di * half + half && q in dj * half..dj * half + half)
                }
                if (top.size == 3) {
                    val p = top.minOf { it % side }; val q = top.minOf { it / side }
                    cellsSeen.merge(q * side + p, 1, Int::plus)
                }
            }
        }
        assertEquals("every cell drawn", TerrainChunk.CELLS * TerrainChunk.CELLS, cellsSeen.size)
        assertTrue("two triangles a cell", cellsSeen.values.all { it == 2 })
    }
}
