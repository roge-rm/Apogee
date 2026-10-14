package com.rm.apogee.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.rm.apogee.core.math.Vec3
import org.junit.Test

class GridMorphTest {
    @Test
    fun `detail can change smoothly without selecting a new tree`() {
        val chunk = ChunkData(ChunkKey(0, 1, 0, 0), null, Vec3(), 100.0)
        val entry = DrawEntry(chunk, parentCentre = Vec3(1_000.0, 0.0, 0.0), parentSize = 100.0, splitAt = 100.0)
        val before = entry.detailAt(Vec3(1_155.0, 0.0, 0.0))
        val after = entry.detailAt(Vec3(1_155.1, 0.0, 0.0))
        assertTrue(before > after)
        assertTrue(before - after < 0.01f)
        assertEquals(1f, entry.detailAt(Vec3(1_100.0, 0.0, 0.0)), 0f)
        assertEquals(0f, entry.detailAt(Vec3(1_180.0, 0.0, 0.0)), 0f)
    }

    @Test
    fun `sampling follows the mesh diagonal rather than bilinear heights`() {
        val corners = floatArrayOf(0f, 0f, 0f, 8f)
        val out = FloatArray(1)
        GridMorph.sample(corners, 2, 1, 0.5, 0.5, out, 0)
        assertEquals(0f, out[0], 0f)
        GridMorph.sample(corners, 2, 1, 0.75, 0.75, out, 0)
        assertEquals(4f, out[0], 0f)
        GridMorph.sample(corners, 2, 1, 1.0, 1.0, out, 0)
        assertEquals(8f, out[0], 0f)
    }
}
