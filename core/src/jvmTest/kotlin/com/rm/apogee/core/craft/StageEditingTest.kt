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

    /**
     * Pod, a decoupler, tank and engine below, four legs round the tank, and a chute on top. It
     * stages engine, decoupler, chute; the legs go with the gear.
     */
    private fun lander(): CraftBuilder {
        val builder = CraftBuilder(catalog)
        builder.placeRoot("pod-halo")
        val ring = builder.attach("decoupler-ring", nodeOn(builder, 0, "bottom")).first()
        val tank = builder.attach("tank-cask4", nodeOn(builder, ring, "bottom")).first()
        builder.attach("engine-ember", nodeOn(builder, tank, "bottom"))
        builder.attach("chute-canopy", nodeOn(builder, 0, "top"))
        quad(builder, tank, "leg-stilt")
        return builder
    }

    /** Four of [partId] round [onto], in symmetry. */
    private fun quad(builder: CraftBuilder, onto: Int, partId: String) {
        builder.symmetry = SymmetryMode.QUAD
        val surface = builder.openNodes().first { it.partIndex == onto && it.kind == AttachNodeKind.SURFACE }
        builder.attach(partId, surface)
        builder.symmetry = SymmetryMode.NONE
    }

    private fun ids(builder: CraftBuilder, stage: Int) =
        builder.design.stages[stage].activatedParts.map { builder.design.parts[it].partId }

    private fun indexOf(builder: CraftBuilder, partId: String) =
        builder.design.parts.indexOfFirst { it.partId == partId }

    @Test
    fun `automatic staging fires the engine, then the decoupler, then the chute, and leaves the legs to the gear`() {
        val builder = lander()
        assertFalse(builder.design.manualStaging)
        assertEquals(3, builder.design.stages.size)
        assertEquals(listOf("engine-ember"), ids(builder, 0))
        assertEquals(listOf("decoupler-ring"), ids(builder, 1))
        assertEquals(listOf("chute-canopy"), ids(builder, 2))
        assertFalse(builder.isStageable(indexOf(builder, "leg-stilt")))
    }

    @Test
    fun `a part moved to another stage takes its symmetry partners`() {
        val builder = lander()
        quad(builder, indexOf(builder, "tank-cask4"), "chute-side")
        assertEquals(4, ids(builder, 2).count { it == "chute-side" })
        assertTrue(builder.moveToStage(indexOf(builder, "chute-side"), 1))
        assertTrue(builder.design.manualStaging)
        assertEquals(setOf("decoupler-ring", "chute-side"), ids(builder, 1).toSet())
        assertEquals(4, ids(builder, 1).count { it == "chute-side" })
        assertEquals(listOf("chute-canopy"), ids(builder, 2))
    }

    @Test
    fun `further building keeps the hand arrangement`() {
        val builder = lander()
        // Decoupler first, engine second, which isn't what automatic staging would do.
        builder.moveStage(1, 0)
        assertEquals(listOf("decoupler-ring"), ids(builder, 0))

        // A fin isn't staged, so adding it mustn't reshuffle anything.
        val tank = indexOf(builder, "tank-cask4")
        val surface = builder.openNodes().first { it.partIndex == tank && it.kind == AttachNodeKind.SURFACE }
        builder.attach("fin-vane", surface)
        assertEquals(listOf("decoupler-ring"), ids(builder, 0))
        assertEquals(listOf("engine-ember"), ids(builder, 1))
    }

    @Test
    fun `removing a staged part leaves the rest of the arrangement in place`() {
        val builder = lander()
        builder.moveStage(2, 0)
        assertEquals(listOf("chute-canopy"), ids(builder, 0))
        assertTrue(builder.remove(indexOf(builder, "chute-canopy")))
        // Its stage is left empty, not renumbered away under the player.
        assertTrue(builder.design.stages[0].activatedParts.isEmpty())
        assertEquals(listOf("engine-ember"), ids(builder, 1))
        assertEquals(listOf("decoupler-ring"), ids(builder, 2))
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
        quad(builder, tank, "chute-side")
        // Side chutes open with the chute.
        assertEquals(setOf("chute-canopy", "chute-side"), ids(builder, 0).toSet())
        assertEquals(listOf("engine-ember"), ids(builder, 1))
    }

    @Test
    fun `removing a stage hands what it fired to the next`() {
        val builder = lander()
        assertTrue(builder.removeStage(1))
        assertEquals(2, builder.design.stages.size)
        assertEquals(setOf("chute-canopy", "decoupler-ring"), ids(builder, 1).toSet())
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
    fun `only engines, decouplers, chutes and shrouds can be staged`() {
        val builder = lander()
        assertFalse(builder.isStageable(indexOf(builder, "tank-cask4")))
        assertFalse(builder.isStageable(indexOf(builder, "leg-stilt")))
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
