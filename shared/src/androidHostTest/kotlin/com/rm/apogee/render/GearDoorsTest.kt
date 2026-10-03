package com.rm.apogee.render

import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plane wheels fold into the body behind doors that open first and shut after. */
class GearDoorsTest {
    private val folding = listOf("wheel-gear-main", "wheel-gear-nose", "wheel-gear", "wheel-tail")

    private fun leaves(id: String, deploy: Double): List<PartModels.Leaf> {
        val out = ArrayList<PartModels.Leaf>()
        PartModels.expand(StockParts.catalog[id]!!, StackCaps.BOTH, PartAnim(deploy = deploy), out)
        return out
    }

    /** The doors: the thin boxes. */
    private fun doors(leaves: List<PartModels.Leaf>) = leaves.filter { (it.shape as? MeshSpec.Box)?.width == 0.03 }

    /** Where the body's skin is, in part space: the attach node, with the body on its -X side. */
    private fun skin(id: String) = StockParts.catalog[id]!!.attachNodes.first().position.x

    @Test
    fun `out, the doors hang open off the skin, and up, they lie shut on it`() {
        for (id in folding) {
            val skin = skin(id)
            val open = doors(leaves(id, 1.0))
            assertEquals(id, 2, open.size)
            for (d in open) assertTrue("$id: an open door at ${d.position.x}, skin at $skin", d.position.x > skin + 0.05)
            for (d in doors(leaves(id, 0.0))) assertEquals("$id: shut", skin + 0.015, d.position.x, 0.01)
        }
    }

    @Test
    fun `up, the wheel is inside the body, and the doors shut only once it's in`() {
        for (id in folding) {
            val skin = skin(id)
            // Everything but the doors is inside, with its whole tyre.
            val radius = StockParts.catalog[id]!!.module<com.rm.apogee.core.part.Wheel>()!!.radius
            val up = leaves(id, 0.0) - doors(leaves(id, 0.0)).toSet()
            for (leaf in up) assertTrue("$id: a piece at ${leaf.position.x}, skin at $skin", leaf.position.x < skin + 0.1)
            assertTrue("$id: the wheel isn't in", up.any { it.position.x < skin - radius })
            // Just as the wheel gets in, the doors are still open.
            val share = com.rm.apogee.core.part.GearFold.DOOR_SHARE
            for (d in doors(leaves(id, share))) assertTrue("$id: shut too soon", d.position.x > skin + 0.05)
        }
    }
}
