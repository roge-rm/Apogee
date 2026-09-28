package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.AttachNodeKind
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CraftBuilderTest {

    private val catalog = StockParts.catalog

    private fun builder() = CraftBuilder(catalog)

    private fun nodeOn(builder: CraftBuilder, partIndex: Int, nodeId: String): OpenNode =
        builder.openNodes().first { it.partIndex == partIndex && it.node.id == nodeId }

    @Test
    fun `the first part becomes the root`() {
        val builder = builder()
        assertTrue(builder.placeRoot("pod-halo"))
        assertEquals(1, builder.partCount)
        assertEquals(-1, builder.design.parts.first().parentIndex)
        assertFalse("a second root should be refused", builder.placeRoot("pod-halo"))
    }

    @Test
    fun `attaching a tank below a pod puts their nodes in the same place`() {
        val builder = builder()
        builder.placeRoot("pod-halo")
        val bottom = nodeOn(builder, 0, "bottom")

        val added = builder.attach("tank-cask2", bottom)
        assertEquals(1, added.size)

        // The tank's top node should now be in the same place as where the pod's bottom node was.
        // That *is* attachment, and everything else about placement follows from it.
        val tank = builder.design.parts[added.first()]
        val tankDef = catalog.require("tank-cask2")
        val tankTop = tankDef.attachNodes.first { it.id == "top" }
        val tankTopInDesign = tank.rotation.rotate(tankTop.position).addInPlace(tank.position)

        assertTrue(
            "nodes did not meet: $tankTopInDesign vs ${bottom.position}",
            tankTopInDesign.approxEquals(bottom.position, 1e-9),
        )
    }

    @Test
    fun `an attached part faces back into the node it joined`() {
        val builder = builder()
        builder.placeRoot("pod-halo")
        val bottom = nodeOn(builder, 0, "bottom")
        val added = builder.attach("tank-cask2", bottom).first()

        val tank = builder.design.parts[added]
        val tankDef = catalog.require("tank-cask2")
        val mountDirection = tank.rotation.rotate(
            tankDef.attachNodes.first { it.id == "top" }.direction
        )
        // Opposite to the target's outward direction, which is what makes the orientation automatic
        // instead of something the player sets by hand.
        assertEquals(-1.0, mountDirection dot bottom.direction, 1e-9)
    }

    @Test
    fun `a node stops being open once something is on it`() {
        val builder = builder()
        builder.placeRoot("pod-halo")
        builder.attach("tank-cask2", nodeOn(builder, 0, "bottom"))

        val open = builder.openNodes()
        assertTrue(
            "the pod's bottom node should be taken",
            open.none { it.partIndex == 0 && it.node.id == "bottom" },
        )
        assertTrue(
            "the tank's top node should be taken",
            open.none { it.partIndex == 1 && it.node.id == "top" },
        )
        assertTrue(
            "the tank's bottom node should still be free",
            open.any { it.partIndex == 1 && it.node.id == "bottom" },
        )
    }

    @Test
    fun `stack nodes of different sizes won't mate`() {
        val builder = builder()
        builder.placeRoot("pod-halo")
        // The pod's top node is size 0 stack. The tank's stack nodes are size 1, and its generated
        // surface nodes can't mate with a stack node at all.
        val top = nodeOn(builder, 0, "top")
        val added = builder.attach("tank-cask4", top)
        assertTrue("a size mismatch should be refused", added.isEmpty())
        assertEquals("nothing should have been added", 1, builder.partCount)
    }

    @Test
    fun `radial symmetry places evenly spaced copies`() {
        val builder = builder()
        builder.placeRoot("pod-halo")
        val tank = builder.attach("tank-cask4", nodeOn(builder, 0, "bottom")).first()

        builder.symmetry = SymmetryMode.QUAD
        val surface = builder.openNodes().first {
            it.partIndex == tank && it.kind == AttachNodeKind.SURFACE
        }
        val added = builder.attach("fin-vane", surface)

        assertEquals("expected four fins", 4, added.size)
        val group = builder.design.parts[added.first()].symmetryGroup
        assertTrue("symmetry parts should share a group", group >= 0)
        assertTrue(added.all { builder.design.parts[it].symmetryGroup == group })

        // Four different places, all the same distance from the stack axis.
        val radii = added.map { index ->
            val p = builder.design.parts[index].position
            kotlin.math.hypot(p.x, p.z)
        }
        assertTrue(
            "copies should be equidistant from the axis, got $radii",
            radii.all { kotlin.math.abs(it - radii.first()) < 1e-9 },
        )
        assertEquals(
            "copies should be at distinct angles",
            4,
            added.map { index ->
                val p = builder.design.parts[index].position
                "%.4f,%.4f".format(p.x, p.z)
            }.distinct().size,
        )
    }

    @Test
    fun `removing a part takes its subtree with it`() {
        val builder = builder()
        builder.placeRoot("pod-halo")
        val tank = builder.attach("tank-cask2", nodeOn(builder, 0, "bottom")).first()
        builder.attach("engine-vesper", nodeOn(builder, tank, "bottom")).first()
        assertEquals(3, builder.partCount)

        builder.remove(tank)
        assertEquals("the engine below it should go too", 1, builder.partCount)
    }

    @Test
    fun `removing a symmetry partner removes them all`() {
        val builder = builder()
        builder.placeRoot("pod-halo")
        val tank = builder.attach("tank-cask4", nodeOn(builder, 0, "bottom")).first()
        builder.symmetry = SymmetryMode.TRIPLE
        val fins = builder.attach(
            "fin-vane",
            builder.openNodes().first {
                it.partIndex == tank && it.kind == AttachNodeKind.SURFACE
            },
        )
        assertEquals(3, fins.size)

        builder.remove(fins.first())
        assertTrue(
            "leaving two of three fins is never what was meant",
            builder.design.parts.none { it.partId == "fin-vane" },
        )
    }

    @Test
    fun `the root can't be removed`() {
        val builder = builder()
        builder.placeRoot("pod-halo")
        assertFalse(builder.remove(0))
        assertEquals(1, builder.partCount)
    }

    @Test
    fun `undo and redo walk the edit history`() {
        val builder = builder()
        builder.placeRoot("pod-halo")
        builder.attach("tank-cask2", nodeOn(builder, 0, "bottom"))
        assertEquals(2, builder.partCount)

        assertTrue(builder.undo())
        assertEquals(1, builder.partCount)
        assertTrue(builder.redo())
        assertEquals(2, builder.partCount)

        // A fresh edit clears the redo branch.
        builder.undo()
        builder.attach("tank-cask4", nodeOn(builder, 0, "bottom"))
        assertFalse("a new edit should discard the redo branch", builder.canRedo)
    }

    @Test
    fun `auto-staging reproduces the stock rocket's sequence`() {
        val design = StockCraft.starterRocket(catalog)
        val stages = CraftBuilder.autoStage(design, catalog)

        fun partIdsIn(stage: Stage) = stage.activatedParts.map { design.parts[it].partId }.toSet()

        assertEquals("expected four stages, got ${stages.size}", 4, stages.size)
        assertEquals(setOf("engine-ember"), partIdsIn(stages[0]))
        assertEquals(setOf("decoupler-ring", "engine-vesper"), partIdsIn(stages[1]))
        assertEquals(setOf("decoupler-ring"), partIdsIn(stages[2]))
        assertEquals(setOf("chute-canopy"), partIdsIn(stages[3]))
    }

    @Test
    fun `a craft built through the builder is flyable`() {
        val builder = builder()
        builder.placeRoot("pod-halo")
        val tank = builder.attach("tank-cask2", nodeOn(builder, 0, "bottom")).first()
        builder.attach("engine-vesper", nodeOn(builder, tank, "bottom"))

        val problems = builder.design.validate(catalog)
        assertTrue("built craft should validate: $problems", problems.isEmpty())
        assertTrue("it should have staging", builder.design.stages.isNotEmpty())
    }

    @Test
    fun `an empty builder reports no open nodes`() {
        assertTrue(builder().openNodes().isEmpty())
        assertNull(catalog["does-not-exist"])
        assertEquals(Vec3.zero(), Vec3.zero())
    }

    // --- orientation ---------------------------------------------------------

    private fun horizontalTank(): CraftBuilder = builder().also {
        it.placeRoot("tank-cask4")
        it.orientation = CraftOrientation.HORIZONTAL
    }

    private fun wheelNodes(builder: CraftBuilder): List<OpenNode> {
        val wheel = catalog.require("wheel-tread")
        return builder.openNodes().filter {
            Attachment.accepts(wheel, it, builder.orientation) &&
                Attachment.mountNodeFor(wheel, it) != null
        }
    }

    @Test
    fun `a horizontal craft takes wheels only underneath`() {
        val builder = horizontalTank()
        val nodes = wheelNodes(builder)
        // The belly plus the two lower quarters.
        assertEquals(3, nodes.size)
        nodes.forEach {
            assertTrue("a wheel was offered ${it.direction}", it.direction.z < -0.5)
        }

        val side = builder.openNodes().first { it.direction.x > 0.9 }
        assertTrue("a wheel went on the side", builder.attach("wheel-tread", side).isEmpty())
        // Wings still go on the side, where they belong.
        assertEquals(1, builder.attach("wing-plank", side).size)
    }

    @Test
    fun `a standing craft keeps its four waist nodes and nothing else`() {
        val builder = builder()
        builder.placeRoot("tank-cask4")
        assertTrue(builder.openNodes().none { it.node.id.startsWith("surface-q") })
        assertEquals(4, wheelNodes(builder).size)
    }

    @Test
    fun `mirror symmetry puts the pair either side, not on the roof`() {
        val builder = horizontalTank()
        builder.symmetry = SymmetryMode.MIRROR
        val quarter = wheelNodes(builder).first { it.node.id.startsWith("surface-q") }

        val added = builder.attach("wheel-tread", quarter)
        assertEquals(2, added.size)
        val (a, b) = added.map { builder.design.parts[it].position }
        assertEquals(-a.x, b.x, 1e-9)
        assertEquals(a.y, b.y, 1e-9)
        assertEquals(a.z, b.z, 1e-9)
        assertTrue("wheels are not underneath: $a", a.z < 0.0)

        // The copy covers its own node, so only the belly is left.
        val left = wheelNodes(builder)
        assertEquals(1, left.size)
        assertTrue(left.single().direction.z < -0.99)
    }

    @Test
    fun `a mirrored wing meets the hull at its root`() {
        val builder = horizontalTank()
        builder.symmetry = SymmetryMode.MIRROR
        val side = builder.openNodes().first { it.direction.x > 0.9 }
        val added = builder.attach("wing-plank", side)
        assertEquals(2, added.size)

        val root = catalog.require("wing-plank").attachNodes.first()
        val copy = builder.design.parts[added[1]]
        val rootInDesign = copy.rotation.rotate(root.position).addInPlace(copy.position)
        assertTrue(
            "the mirrored wing floats off the hull at $rootInDesign",
            rootInDesign.approxEquals(Vec3(-side.position.x, side.position.y, side.position.z), 1e-9),
        )
    }

    @Test
    fun `a craft laid down cycles only between one and a mirrored pair`() {
        val builder = builder()
        builder.placeRoot("tank-cask4")
        builder.symmetry = SymmetryMode.QUAD
        builder.orientation = CraftOrientation.HORIZONTAL
        assertEquals(SymmetryMode.MIRROR, builder.symmetry)
        assertEquals(SymmetryMode.NONE, builder.symmetry.next(builder.orientation))
        assertEquals(SymmetryMode.MIRROR, SymmetryMode.NONE.next(builder.orientation))

        // And it's an edit like any other.
        builder.undo()
        assertEquals(CraftOrientation.VERTICAL, builder.orientation)
        assertEquals(SymmetryMode.QUAD, builder.symmetry)
    }
}
