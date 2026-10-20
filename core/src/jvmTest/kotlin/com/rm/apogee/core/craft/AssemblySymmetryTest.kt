package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Symmetry continues through each booster branch, including its stack joints. */
class AssemblySymmetryTest {
    private val catalog = StockParts.catalog

    private fun node(builder: CraftBuilder, parent: Int, id: String): OpenNode =
        builder.openNodes().first { it.partIndex == parent && it.node.id == id }

    private fun boosters(mode: SymmetryMode = SymmetryMode.QUAD): Pair<CraftBuilder, List<Int>> {
        val builder = CraftBuilder(catalog)
        builder.placeRoot("tank-cask4")
        builder.symmetry = mode
        val clamps = builder.attach("decoupler-side", node(builder, 0, "surface-0"))
        return builder to clamps
    }

    private fun assertJoined(design: CraftDesign, children: List<Int>, parents: List<Int>) {
        assertEquals(parents.toSet(), children.map { design.parts[it].parentIndex }.toSet())
        for (i in children) {
            val child = design.parts[i]
            val parent = design.parts[child.parentIndex]
            val parentNode = catalog.require(parent.partId).allAttachNodes.first { it.id == child.parentNodeId }
            val ownNode = catalog.require(child.partId).allAttachNodes.first { it.id == child.ownNodeId }
            val target = Attachment.resolve(child.parentIndex, parent, parentNode)
            val mount = Attachment.resolve(i, child, ownNode)
            assertTrue("part $i meets its recorded parent", target.position.approxEquals(mount.position, 1e-8))
            assertEquals(-1.0, target.direction dot mount.direction, 1e-8)
        }
    }

    @Test
    fun `each symmetric tank belongs to its own side clamp`() {
        val (builder, clamps) = boosters()
        val tanks = builder.attach("tank-cask4", node(builder, clamps.first(), "mount"))
        assertEquals(4, tanks.size)
        assertJoined(builder.design, tanks, clamps)
        assertTrue("all clamp mounts occupied", builder.openNodes().none { it.partIndex in clamps && it.node.id == "mount" })
    }

    @Test
    fun `loading an older design repairs copied tanks pointing at the first clamp`() {
        val (builder, clamps) = boosters()
        val tanks = builder.attach("tank-cask4", node(builder, clamps.first(), "mount"))
        val correct = builder.design
        val legacy = correct.copy(parts = correct.parts.mapIndexed { i, part ->
            if (i in tanks) part.copy(parentIndex = clamps.first()) else part
        })
        builder.load(legacy)
        assertJoined(builder.design, tanks, clamps)
        assertJoined(CraftBuilder(catalog, legacy).design, tanks, clamps)
        for (i in correct.parts.indices) {
            assertTrue(correct.parts[i].position.approxEquals(builder.design.parts[i].position, 1e-8))
            assertTrue(correct.parts[i].rotation.approxEqualsRotation(builder.design.parts[i].rotation, 1e-8))
        }
        assertTrue(builder.attach("engine-ember", node(builder, tanks.first(), "bottom")).size == 4)
    }

    @Test
    fun `engine preview and placement continue symmetry through several tank sections`() {
        for (mode in listOf(SymmetryMode.MIRROR, SymmetryMode.TRIPLE, SymmetryMode.QUAD)) {
            val (builder, clamps) = boosters(mode)
            val upper = builder.attach("tank-cask4", node(builder, clamps.last(), "mount"))
            val lower = builder.attach("tank-cask2", node(builder, upper.last(), "bottom"))
            assertEquals(mode.count, lower.size)
            assertJoined(builder.design, lower, upper)
            val target = node(builder, lower.last(), "bottom")
            val preview = Assemblies.attach(builder.design, Assembly.of("engine-ember"), target, mode, catalog)!!
            assertEquals("all engines shown in the ghost", mode.count, preview.added.size)
            assertJoined(preview.design, preview.added, lower)
            val engines = builder.attach("engine-ember", target)
            assertEquals(mode.count, engines.size)
            assertJoined(builder.design, engines, lower)
            assertTrue(builder.openNodes().none { it.partIndex in lower && it.node.id == "bottom" })
            assertEquals(1, engines.map { builder.design.parts[it].symmetryGroup }.distinct().size)
            for (i in engines) assertTrue(builder.design.parts[i].position.approxEquals(preview.design.parts[i].position, 1e-8))
            assertTrue(builder.undo())
            assertEquals(mode.count, builder.openNodes().count { it.partIndex in lower && it.node.id == "bottom" })
            assertTrue(builder.redo())
            assertJoined(builder.design, engines, lower)
        }
    }

    @Test
    fun `one times mode can still build just one branch`() {
        val (builder, clamps) = boosters()
        builder.symmetry = SymmetryMode.NONE
        val tanks = builder.attach("tank-cask4", node(builder, clamps.first(), "mount"))
        assertEquals(1, tanks.size)
        assertJoined(builder.design, tanks, listOf(clamps.first()))
        assertEquals(3, builder.openNodes().count { it.partIndex in clamps && it.node.id == "mount" })
    }

    @Test
    fun `symmetry on a central stack joint still places just one part`() {
        val (builder, _) = boosters()
        val engines = builder.attach("engine-ember", node(builder, 0, "bottom"))
        assertEquals(1, engines.size)
        assertJoined(builder.design, engines, listOf(0))
    }

    @Test
    fun `an occupied partner node refuses the whole group rather than overlapping a part`() {
        val (builder, clamps) = boosters()
        val tanks = builder.attach("tank-cask4", node(builder, clamps.first(), "mount"))
        builder.symmetry = SymmetryMode.NONE
        builder.attach("engine-ember", node(builder, tanks[1], "bottom"))
        builder.symmetry = SymmetryMode.QUAD
        val before = builder.design
        assertTrue(builder.attach("engine-ember", node(builder, tanks[0], "bottom")).isEmpty())
        assertEquals(before, builder.design)
    }

    @Test
    fun `two times mode on four branches uses the opposite pair`() {
        val (builder, clamps) = boosters()
        val tanks = builder.attach("tank-cask4", node(builder, clamps.first(), "mount"))
        builder.symmetry = SymmetryMode.MIRROR
        val engines = builder.attach("engine-ember", node(builder, tanks.first(), "bottom"))
        assertEquals(2, engines.size)
        val a = builder.design.parts[engines[0]].position
        val b = builder.design.parts[engines[1]].position
        assertTrue(Vec3(-a.x, a.y, -a.z).approxEquals(b, 1e-8))
        assertFalse(builder.design.parts[engines[0]].parentIndex == builder.design.parts[engines[1]].parentIndex)
    }

    @Test
    fun `horizontal mirror symmetry also continues down the side tanks`() {
        val builder = CraftBuilder(catalog)
        builder.orientation = CraftOrientation.HORIZONTAL
        builder.placeRoot("fuselage-short")
        builder.symmetry = SymmetryMode.MIRROR
        val clamps = builder.attach("decoupler-side", node(builder, 0, "side-right"))
        val tanks = builder.attach("tank-cask4", node(builder, clamps.first(), "mount"))
        assertEquals(2, tanks.size)
        assertJoined(builder.design, tanks, clamps)
        val engines = builder.attach("engine-ember", node(builder, tanks.last(), "bottom"))
        assertEquals(2, engines.size)
        assertJoined(builder.design, engines, tanks)
        val a = builder.design.parts[engines[0]]
        val b = builder.design.parts[engines[1]]
        val reflected = Attachment.mirror(Attachment.Placement(a.position, a.rotation))
        assertTrue(reflected.position.approxEquals(b.position, 1e-8))
        assertTrue(reflected.rotation.approxEqualsRotation(b.rotation, 1e-8))
    }
}
