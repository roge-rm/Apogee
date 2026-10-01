package com.rm.apogee.core.craft

import com.rm.apogee.core.part.AttachNodeKind
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Staging arranged by hand in the builder. Moved parts stay moved, symmetry partners move together,
 * and more building keeps the arrangement.
 */
class StageEditingTest {

    private val catalog = StockParts.catalog

    private fun nodeOn(builder: CraftBuilder, partIndex: Int, nodeId: String): OpenNode =
        builder.openNodes().first { it.partIndex == partIndex && it.node.id == nodeId }

    /** Pod, tank and engine below, four legs round the tank, and a chute on top. */
    private fun lander(): CraftBuilder {
        val builder = CraftBuilder(catalog)
        builder.placeRoot("pod-halo")
        val tank = builder.attach("tank-cask4", nodeOn(builder, 0, "bottom")).first()
        builder.attach("engine-ember", nodeOn(builder, tank, "bottom"))
        builder.attach("chute-canopy", nodeOn(builder, 0, "top"))
        builder.symmetry = SymmetryMode.QUAD
        val surface = builder.openNodes().first { it.partIndex == tank && it.kind == AttachNodeKind.SURFACE }
        builder.attach("leg-stilt", surface)
        builder.symmetry = SymmetryMode.NONE
        return builder
    }

    private fun ids(builder: CraftBuilder, stage: Int) =
        builder.design.stages[stage].activatedParts.map { builder.design.parts[it].partId }

    private fun indexOf(builder: CraftBuilder, partId: String) =
        builder.design.parts.indexOfFirst { it.partId == partId }

    @Test
    fun `automatic staging puts the legs out last`() {
        val builder = lander()
        assertFalse(builder.design.manualStaging)
        assertEquals(listOf("engine-ember"), ids(builder, 0))
        assertEquals(listOf("chute-canopy"), ids(builder, 1))
        assertEquals(List(4) { "leg-stilt" }, ids(builder, 2))
    }

    @Test
    fun `a leg moved to another stage takes its symmetry partners`() {
        val builder = lander()
        assertTrue(builder.moveToStage(indexOf(builder, "leg-stilt"), 1))
        assertTrue(builder.design.manualStaging)
        assertEquals(setOf("chute-canopy", "leg-stilt"), ids(builder, 1).toSet())
        assertEquals(4, ids(builder, 1).count { it == "leg-stilt" })
        assertTrue("the stage they left is empty", builder.design.stages[2].activatedParts.isEmpty())
    }

    @Test
    fun `further building keeps the hand arrangement`() {
        val builder = lander()
        // Chute first, engine second, which isn't what automatic staging would do.
        builder.moveStage(1, 0)
        assertEquals(listOf("chute-canopy"), ids(builder, 0))

        // A fin isn't staged, so adding it mustn't reshuffle anything.
        val tank = indexOf(builder, "tank-cask4")
        val surface = builder.openNodes().first { it.partIndex == tank && it.kind == AttachNodeKind.SURFACE }
        builder.attach("fin-vane", surface)
        assertEquals(listOf("chute-canopy"), ids(builder, 0))
        assertEquals(listOf("engine-ember"), ids(builder, 1))
    }

    @Test
    fun `removing a staged part leaves the rest of the arrangement in place`() {
        val builder = lander()
        builder.moveStage(1, 0)
        assertTrue(builder.remove(indexOf(builder, "chute-canopy")))
        // Its stage is left empty, not renumbered away under the player.
        assertTrue(builder.design.stages[0].activatedParts.isEmpty())
        assertEquals(listOf("engine-ember"), ids(builder, 1))
        assertEquals(List(4) { "leg-stilt" }, ids(builder, 2))
        // And the design that flies drops it.
        assertEquals(2, builder.design.withoutEmptyStages().stages.size)
    }

    @Test
    fun `a part added to hand staging fires beside what it would fire with`() {
        val builder = CraftBuilder(catalog)
        builder.placeRoot("pod-halo")
        val tank = builder.attach("tank-cask4", nodeOn(builder, 0, "bottom")).first()
        builder.attach("engine-ember", nodeOn(builder, tank, "bottom"))
        builder.attach("chute-canopy", nodeOn(builder, 0, "top"))
        builder.moveStage(1, 0) // chute, then engine
        builder.symmetry = SymmetryMode.QUAD
        val surface = builder.openNodes().first { it.partIndex == tank && it.kind == AttachNodeKind.SURFACE }
        builder.attach("leg-stilt", surface)
        // Legs fire after everything, in a stage of their own, last.
        assertEquals(listOf("chute-canopy"), ids(builder, 0))
        assertEquals(listOf("engine-ember"), ids(builder, 1))
        assertEquals(List(4) { "leg-stilt" }, ids(builder, 2))
    }

    @Test
    fun `removing a stage hands what it fired to the next`() {
        val builder = lander()
        assertTrue(builder.removeStage(1))
        assertEquals(2, builder.design.stages.size)
        assertEquals(setOf("chute-canopy", "leg-stilt"), ids(builder, 1).toSet())
    }

    @Test
    fun `a new stage can be filled and put back to automatic`() {
        val builder = lander()
        assertTrue(builder.addStage(0))
        assertTrue(builder.design.stages[0].activatedParts.isEmpty())
        assertTrue(builder.moveToStage(indexOf(builder, "chute-canopy"), 0))
        assertEquals(listOf("chute-canopy"), ids(builder, 0))

        builder.useAutomaticStaging()
        assertFalse(builder.design.manualStaging)
        assertEquals(listOf("engine-ember"), ids(builder, 0))
    }

    @Test
    fun `only engines, decouplers, chutes and legs can be staged`() {
        val builder = lander()
        assertFalse(builder.isStageable(indexOf(builder, "tank-cask4")))
        assertFalse(builder.moveToStage(indexOf(builder, "tank-cask4"), 0))
        assertTrue(builder.isStageable(indexOf(builder, "engine-ember")))
    }

    @Test
    fun `undo takes back a staging change`() {
        val builder = lander()
        builder.moveStage(1, 0)
        assertTrue(builder.undo())
        assertEquals(listOf("engine-ember"), ids(builder, 0))
        assertFalse(builder.design.manualStaging)
    }
}
