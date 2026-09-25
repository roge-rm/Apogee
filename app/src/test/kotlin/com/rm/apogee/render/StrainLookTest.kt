package com.rm.apogee.render

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrainLookTest {

    private val catalog = StockParts.catalog
    private val design = StockCraft.starterRocket(catalog)
    private val defs = design.parts.map { catalog[it.partId] }
    private val n = design.parts.size

    /** A part with a parent that itself has children: a joint in the middle of the stack. */
    private val middle = (0 until n).first { i ->
        design.parts[i].parentIndex >= 0 && (0 until n).any { design.parts[it].parentIndex == i }
    }

    @Test
    fun `quiet joints leave the craft as built`() {
        val look = StrainLook()
        assertFalse(look.compute(design, defs, FloatArray(n) { 0.5f }, 1.0, 1))
    }

    @Test
    fun `a straining joint swings what hangs from it about the seam, and nothing else`() {
        val loads = FloatArray(n).also { it[middle] = 1.1f }
        val look = StrainLook()
        // Somewhere in the shudder it is well off straight.
        var most = 0.0
        val child = (0 until n).first { design.parts[it].parentIndex == middle }
        val parent = design.parts[middle].parentIndex
        for (k in 0 until 200) {
            assertTrue(look.compute(design, defs, loads, k * 0.013, 7))
            // The seam holds: both sides of it agree where it is.
            val seam = look.seams[middle]
            val fromParent = look.apply(parent, seam, Vec3())
            val fromPart = look.apply(middle, seam, Vec3())
            assertEquals(0.0, fromParent.distanceTo(fromPart), 1e-9)
            // The part it hangs from does not move.
            val p = design.parts[parent].position
            assertEquals(0.0, look.apply(parent, p, Vec3()).distanceTo(p), 1e-9)
            // What hangs beyond it swings with it.
            val c = design.parts[child].position
            most = maxOf(most, look.apply(child, c, Vec3()).distanceTo(c))
        }
        assertTrue("the part beyond flexes visibly: $most m", most > 0.01)
        assertTrue("but only a touch: $most m", most < 1.0)
    }
}
