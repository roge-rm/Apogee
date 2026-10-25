package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pieces left loose on the floor, and parts put into a joint that's already joined. */
class LooseAndInsertTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val catalog = StockParts.catalog

    private fun nodeOn(design: CraftDesign, partIndex: Int, nodeId: String): OpenNode =
        Attachment.openNodes(design, catalog).first { it.partIndex == partIndex && it.node.id == nodeId }

    /** Pod, short tank, long tank, engine: a rocket built without a decoupler in the middle. */
    private fun rocket(): CraftBuilder {
        val b = CraftBuilder(catalog)
        b.placeRoot("pod-halo")
        b.attach("tank-cask2", nodeOn(b.design, 0, "bottom"))
        b.attach("tank-cask4", nodeOn(b.design, 1, "bottom"))
        b.attach("engine-ember", nodeOn(b.design, 2, "bottom"))
        return b
    }

    @Test
    fun `a parked lower stack can go back on`() {
        val b = rocket()
        val before = b.design
        assertTrue(b.park(2, Vec3(3.0, -4.0, 0.0)))
        assertEquals("pod and short tank left", 2, b.design.parts.size)
        assertEquals(1, b.loose.size)
        assertEquals("long tank and engine loose", 2, b.loose[0].parts.size)
        assertTrue(Vec3(3.0, -4.0, 0.0).approxEquals(b.loose[0].placed()[0].position, 1e-9))

        val added = b.attachLoose(0, nodeOn(b.design, 1, "bottom"))
        assertEquals(2, added.size)
        assertTrue(b.loose.isEmpty())
        assertEquals(4, b.design.parts.size)
        for (i in before.parts.indices) {
            assertTrue("part $i back where it was", before.parts[i].position.approxEquals(b.design.parts[i].position, 1e-9))
            assertEquals(before.parts[i].parentIndex, b.design.parts[i].parentIndex)
        }
    }

    @Test
    fun `undo and redo cover parking`() {
        val b = rocket()
        val before = b.design
        b.park(2, Vec3(3.0, 0.0, 0.0))
        val parked = b.design
        b.moveLoose(0, Vec3(5.0, 0.0, 0.0))
        b.removeLoose(0)
        assertTrue(b.loose.isEmpty())

        assertTrue(b.undo())
        assertEquals(1, b.loose.size)
        assertTrue(Vec3(5.0, 0.0, 0.0).approxEquals(b.loose[0].position, 1e-9))
        assertTrue(b.undo())
        assertTrue(Vec3(3.0, 0.0, 0.0).approxEquals(b.loose[0].position, 1e-9))
        assertTrue(b.undo())
        assertEquals(before, b.design)
        assertTrue(b.loose.isEmpty())

        assertTrue(b.redo())
        assertEquals(parked, b.design)
        assertEquals(1, b.loose.size)
    }

    @Test
    fun `a separator goes in between two tanks and the lower stack moves down`() {
        val b = rocket()
        val upper = b.design.parts[1]
        val lower = b.design.parts[2]
        val engine = b.design.parts[3]
        val added = b.insert(Assembly.of("decoupler-ring"), 2)
        assertEquals(1, added.size)
        val d = b.design
        assertEquals(5, d.parts.size)
        val ring = d.parts[added[0]]
        assertEquals("decoupler-ring", ring.partId)
        assertEquals("joins the upper tank", 1, ring.parentIndex)
        assertEquals("bottom", ring.parentNodeId)
        assertEquals("top", ring.ownNodeId)
        assertTrue(upper.position.approxEquals(d.parts[1].position, 1e-9))

        val tank = d.parts.indexOfFirst { it.partId == "tank-cask4" }
        val motor = d.parts.indexOfFirst { it.partId == "engine-ember" }
        assertEquals("the lower tank hangs from the ring", added[0], d.parts[tank].parentIndex)
        assertEquals("bottom", d.parts[tank].parentNodeId)
        assertEquals("top", d.parts[tank].ownNodeId)
        assertEquals(tank, d.parts[motor].parentIndex)
        // The ring is 0.2 m between its nodes, so the lower stack drops that far.
        val drop = Vec3(0.0, -0.2, 0.0)
        assertTrue(lower.position.copy().addInPlace(drop).approxEquals(d.parts[tank].position, 1e-9))
        assertTrue(engine.position.copy().addInPlace(drop).approxEquals(d.parts[motor].position, 1e-9))
        assertTrue(Vec3(0.0, upper.position.y - 1.1, 0.0).approxEquals(ring.position, 1e-9))

        assertTrue("one undo step", b.undo())
        assertEquals(4, b.design.parts.size)
    }

    @Test
    fun `a loose separator goes into the joint`() {
        val b = CraftBuilder(catalog)
        b.placeRoot("pod-halo")
        b.attach("tank-cask2", nodeOn(b.design, 0, "bottom"))
        b.attach("tank-cask4", nodeOn(b.design, 1, "bottom"))
        b.attach("decoupler-ring", nodeOn(b.design, 2, "bottom"))
        b.park(3, Vec3(2.0, 0.0, 0.0))
        val added = b.insertLoose(0, 2)
        assertEquals(1, added.size)
        assertTrue(b.loose.isEmpty())
        assertEquals(4, b.design.parts.size)
        assertEquals(added[0], b.design.parts.single { it.partId == "tank-cask4" }.parentIndex)
        assertTrue(b.undo())
        assertEquals("undo brings the loose ring back", 1, b.loose.size)
    }

    @Test
    fun `insert is refused without a far node`() {
        val b = rocket()
        val before = b.design
        assertNull(Assemblies.insert(before, Assembly.of("nosecone-spire"), 2, catalog))
        assertTrue(b.insert(Assembly.of("nosecone-spire"), 2).isEmpty())
        assertEquals(before, b.design)
    }

    @Test
    fun `a lifted piece moves into a joint in one step`() {
        val b = CraftBuilder(catalog)
        b.placeRoot("pod-halo")
        b.attach("tank-cask2", nodeOn(b.design, 0, "bottom"))
        b.attach("tank-cask4", nodeOn(b.design, 1, "bottom"))
        b.attach("decoupler-ring", nodeOn(b.design, 2, "bottom"))
        // Lift the ring off the bottom and put it between the tanks; in what's left the long tank is 2.
        val added = b.moveInsert(3, 2)
        assertEquals(1, added.size)
        val d = b.design
        assertEquals(4, d.parts.size)
        assertEquals(1, d.parts[added[0]].parentIndex)
        assertEquals(added[0], d.parts.single { it.partId == "tank-cask4" }.parentIndex)
    }

    /** The rocket with four radial tanks on the long tank; returns the builder and the tanks. */
    private fun rocketWithRadials(): Pair<CraftBuilder, List<Int>> {
        val b = rocket()
        b.symmetry = SymmetryMode.QUAD
        val radials = b.attach("tank-cask2", nodeOn(b.design, 2, "surface-0"))
        b.symmetry = SymmetryMode.NONE
        assertEquals(4, radials.size)
        return b to radials
    }

    @Test
    fun `four lifted radial tanks are refused by a centre joint`() {
        val (b, radials) = rocketWithRadials()
        val before = b.design
        val lifted = Assemblies.lift(before, radials[0])!!
        assertEquals(4, lifted.copies)
        // In what's left after the lift, the long tank is still 2. One tank alone would fit.
        assertTrue(Assemblies.insert(lifted.rest, lifted.assembly, 2, catalog) != null)
        assertTrue(b.moveInsert(radials[0], 2).isEmpty())
        assertEquals("nothing lost", before, b.design)
    }

    @Test
    fun `four loose radial tanks are refused by a centre joint and stay loose`() {
        val (b, radials) = rocketWithRadials()
        assertTrue(b.park(radials[0], Vec3(3.0, 0.0, 0.0)))
        assertEquals(4, b.loose.single().copies)
        val before = b.design
        assertTrue(b.insertLoose(0, 2).isEmpty())
        assertEquals(before, b.design)
        assertEquals(1, b.loose.size)
    }

    @Test
    fun `the working file keeps loose pieces and still reads old files`() {
        val dir = folder.newFolder("working")
        val store = CraftStore(dir)
        val b = rocket()
        b.park(2, Vec3(3.0, -4.0, 1.0))
        store.saveWorking(b.design, b.loose).getOrThrow()
        val reopened = CraftStore(dir)
        assertEquals(b.design, reopened.loadWorking().getOrThrow())
        assertEquals(b.loose, reopened.loadLoose().getOrThrow())
        assertTrue("not a named save", reopened.list().isEmpty())

        // A file from before loose pieces: just a design.
        store.saveWorking(StockCraft.sounder(catalog)).getOrThrow()
        assertEquals(StockCraft.sounder(catalog), reopened.loadWorking().getOrThrow())
        assertTrue(reopened.loadLoose().getOrThrow().isEmpty())
        java.io.File(dir, "assembly-working.json").writeText("""{"name":"Old","parts":[{"partId":"pod-halo","position":{"x":0.0,"y":0.0,"z":0.0}}]}""")
        assertEquals("Old", reopened.loadWorking().getOrThrow()!!.name)
        assertTrue(reopened.loadLoose().getOrThrow().isEmpty())
    }
}
