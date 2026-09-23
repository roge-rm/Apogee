package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wing on the left is the mirror of one on the right: leading edge
 * forward on both, swept back on both. A wing on an exactly opposed node
 * used to come out half-turned about the vertical - leading edge at the
 * back, swept forward.
 */
class MirroredWingTest {

    private val catalog = StockParts.catalog

    private fun chord(placed: PlacedPart): Vec3 = placed.rotation.rotate(Vec3.unitY())

    @Test
    fun `the Sparrow's wings and tail both lead with their leading edges`() {
        val design = StockCraft.sparrow(catalog)
        for (id in listOf("wing-swept", "tail-stabilator")) {
            val pair = design.parts.filter { it.partId == id }
            assertEquals(2, pair.size)
            for (p in pair) {
                assertTrue("$id at ${p.position} leads with ${chord(p)}", chord(p).y > 0.99)
            }
            // Mirrored: one each side.
            assertTrue(pair[0].position.x * pair[1].position.x < 0)
        }
    }

    @Test
    fun `a design saved with a back-to-front wing is repaired on loading`() {
        val good = StockCraft.sparrow(catalog)
        val index = good.parts.indexOfFirst { it.partId == "wing-swept" && it.position.x < 0 }
        val wing = good.parts[index]
        // Half a turn about the join line through its root: the other of the
        // two turns that seat it, and the one the old code could pick.
        val root = catalog.require("wing-swept").allAttachNodes.first { it.id == wing.ownNodeId }
        val node = wing.rotation.rotate(root.position).addInPlace(wing.position)
        val flipped = Quat.fromAxisAngle(Vec3.unitX(), Math.PI) * wing.rotation
        val broken = good.copy(
            parts = good.parts.toMutableList().also {
                it[index] = wing.copy(rotation = flipped, position = node.copy().subInPlace(flipped.rotate(root.position)))
            },
        )
        assertTrue(chord(broken.parts[index]).y < -0.99)
        val repaired = StockCraft.facingOutward(broken, catalog)
        assertTrue("leading edge forward again", chord(repaired.parts[index]).y > 0.99)
        assertTrue("and in the same place", repaired.parts[index].position.distanceTo(wing.position) < 1e-6)
    }
}
