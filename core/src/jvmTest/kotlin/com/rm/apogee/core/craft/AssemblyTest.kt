package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifting pieces off a craft, putting them back on somewhere else, copying them and turning them.
 */
class AssemblyTest {

    private val catalog = StockParts.catalog

    private fun nodeOn(design: CraftDesign, partIndex: Int, nodeId: String): OpenNode =
        Attachment.openNodes(design, catalog).first { it.partIndex == partIndex && it.node.id == nodeId }

    /** Pod, tank, decoupler, tank, engine, and a tank-and-engine booster on the side of the upper tank. */
    private fun rocket(): CraftBuilder {
        val b = CraftBuilder(catalog)
        b.placeRoot("pod-halo")
        b.attach("tank-cask2", nodeOn(b.design, 0, "bottom"))
        b.attach("decoupler-ring", nodeOn(b.design, 1, "bottom"))
        b.attach("tank-cask4", nodeOn(b.design, 2, "bottom"))
        b.attach("engine-ember", nodeOn(b.design, 3, "bottom"))
        return b
    }

    /** Where [of] sits and faces in [frame]'s own frame, which is what a rigid move keeps. */
    private fun relative(frame: PlacedPart, of: PlacedPart): Pair<Vec3, com.rm.apogee.core.math.Quat> {
        val undo = frame.rotation.conjugate()
        return undo.rotate(Vec3().setTo(of.position).subInPlace(frame.position)) to (undo * of.rotation)
    }

    @Test
    fun `the root can't be lifted`() {
        assertNull(rocket().lift(0))
    }

    @Test
    fun `lifting only looks, and the design is untouched until the move`() {
        val b = rocket()
        val before = b.design
        val lifted = b.lift(3)!!
        assertEquals(before, b.design)
        assertEquals("tank and engine held", 2, lifted.assembly.size)
        assertEquals(3, lifted.rest.parts.size)
    }

    @Test
    fun `a lower stage moved onto the side keeps its engine where it was on it`() {
        val b = rocket()
        val tank = b.design.parts[3]
        val engine = b.design.parts[4]
        val before = relative(tank, engine)
        val lifted = b.lift(3)!!
        // Onto the side of the pod's tank, as a booster now, hung by one of its own side nodes,
        // because the bottom one is the engine's.
        val side = nodeOn(lifted.rest, 1, "surface-0")
        val added = b.move(3, side)
        assertEquals(2, added.size)
        assertEquals(5, b.design.parts.size)
        val movedTank = b.design.parts[added[0]]
        val movedEngine = b.design.parts[added[1]]
        val after = relative(movedTank, movedEngine)
        assertTrue("engine kept its place on the tank", before.first.approxEquals(after.first, 1e-9))
        assertTrue(before.second.approxEqualsRotation(after.second))
        assertEquals("the engine still hangs from the tank", added[0], movedEngine.parentIndex)
    }

    @Test
    fun `a move is one undo step`() {
        val b = rocket()
        val before = b.design
        val lifted = b.lift(4)!!
        b.move(4, nodeOn(lifted.rest, 3, "bottom"))
        assertTrue(b.undo())
        assertEquals(before, b.design)
    }

    @Test
    fun `four fins moved together land as four again`() {
        val b = rocket()
        b.symmetry = SymmetryMode.QUAD
        val fins = b.attach("fin-vane", nodeOn(b.design, 3, "surface-0"))
        assertEquals(4, fins.size)
        val lifted = b.lift(fins[0])!!
        assertEquals(4, lifted.copies)
        assertEquals("all four came off", 5, lifted.rest.parts.size)
        // Onto the pod's tank instead.
        val added = b.move(fins[0], nodeOn(lifted.rest, 1, "surface-0"))
        assertEquals(4, added.size)
        assertEquals(9, b.design.parts.size)
        assertTrue(added.all { b.design.parts[it].parentIndex == 1 })
        assertEquals("one group", 1, added.map { b.design.parts[it].symmetryGroup }.toSet().size)
    }

    @Test
    fun `a copied booster is the same shape as the one it copied`() {
        val b = rocket()
        val copy = b.duplicate(3)!!
        // A second tank-and-engine under the engine is silly but allowed. The copy goes on where it
        // fits and keeps its own shape.
        val added = b.attachAssembly(copy, nodeOn(b.design, 4, "bottom"))
        assertEquals(2, added.size)
        val a = relative(b.design.parts[3], b.design.parts[4])
        val c = relative(b.design.parts[added[0]], b.design.parts[added[1]])
        assertTrue(a.first.approxEquals(c.first, 1e-9))
    }

    @Test
    fun `a manual stage follows the parts that move`() {
        val b = rocket()
        val engine = 4
        b.addStage(b.design.stages.size)
        val last = b.design.stages.size - 1
        assertTrue(b.moveToStage(engine, last))
        val lifted = b.lift(3)!!
        val added = b.move(3, nodeOn(lifted.rest, 2, "bottom"))
        val movedEngine = added[1]
        assertEquals(last, b.stageOf(movedEngine))
    }

    @Test
    fun `a turned cockpit stays turned through save and load`() {
        val b = rocket()
        val tank = 1
        assertTrue(b.turn(tank))
        assertEquals(1, b.design.parts[tank].turn)
        val json = Json { ignoreUnknownKeys = true }
        val saved = json.encodeToString(CraftDesign.serializer(), b.design)
        val loaded = StockCraft.facingOutward(json.decodeFromString(CraftDesign.serializer(), saved), catalog)
        for (i in b.design.parts.indices) {
            assertTrue("part $i turned back on loading", loaded.parts[i].rotation.approxEqualsRotation(b.design.parts[i].rotation, 1e-9))
            assertTrue(loaded.parts[i].position.approxEquals(b.design.parts[i].position, 1e-9))
        }
        // Four turns is all the way round.
        repeat(3) { b.turn(tank) }
        assertEquals(0, b.design.parts[tank].turn)
    }

    @Test
    fun `a mirrored wing pair turns as a pair and stays that way on loading`() {
        val b = CraftBuilder(catalog)
        b.orientation = CraftOrientation.HORIZONTAL
        b.placeRoot("fuselage-short")
        b.symmetry = SymmetryMode.MIRROR
        val wings = b.attach("wing-small", nodeOn(b.design, 0, "side-right"))
        assertEquals(2, wings.size)
        assertTrue(b.turn(wings[0], 2))
        val a = b.design.parts[wings[0]]
        val c = b.design.parts[wings[1]]
        // Still mirror images of each other.
        val mirrored = Attachment.mirror(Attachment.Placement(a.position, a.rotation))
        assertTrue(mirrored.position.approxEquals(c.position, 1e-9))
        assertTrue(mirrored.rotation.approxEqualsRotation(c.rotation, 1e-9))
        val loaded = StockCraft.facingOutward(b.design, catalog)
        for (i in wings) assertTrue(loaded.parts[i].rotation.approxEqualsRotation(b.design.parts[i].rotation, 1e-9))
    }

    @Test
    fun `a design saved before turns existed loads with none`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"name":"Old","parts":[{"partId":"pod-halo","position":{"x":0.0,"y":0.0,"z":0.0}}]}"""
        val design = json.decodeFromString(CraftDesign.serializer(), old)
        assertEquals(0, design.parts[0].turn)
        assertNotNull(design)
    }
}
