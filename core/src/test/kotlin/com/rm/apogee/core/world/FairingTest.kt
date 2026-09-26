package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftBuilder
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Stage
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Shroud: a closed shell round what rides on it, out of the air until it is thrown open. */
class FairingTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** A pod on a Taper on a Shroud's ring: the smallest thing a Shroud can hold. */
    private fun shrouded(): CraftDesign = CraftDesign(
        name = "Shrouded Pod",
        parts = listOf(
            PlacedPart("pod-halo", Vec3(0.0, 1.9, 0.0)),
            PlacedPart("adapter-taper", Vec3(0.0, 0.7, 0.0), parentIndex = 0),
            PlacedPart("fairing-base", Vec3(0.0, 0.0, 0.0), parentIndex = 1),
        ),
        stages = listOf(Stage(listOf(2))),
        manualStaging = true,
        catalogHash = catalog.contentHash,
    )

    @Test
    fun `what rides on the Moonshot's Shroud is inside it, and the rest is not`() {
        val design = StockCraft.moonshot(catalog)
        val world = World.default(catalog)
        val craft = world.spawnOnSurface(design, World.launchSites.first())
        val inside = craft.enclosed()
        for ((i, part) in design.parts.withIndex()) {
            val riding = part.position.y > 23.9
            assertEquals("${part.partId} at ${part.position.y}", riding, inside[i])
        }
    }

    @Test
    fun `staging the Shroud throws its halves off, one each way, and lightens the craft`() {
        val world = World.default(catalog)
        val craft = world.spawnAt(shrouded(), "terra", Vec3(700_000.0, 0.0, 0.0), Vec3(0.0, 0.0, 2_200.0), Quat.identity())
        val before = craft.body.mass
        assertTrue("the pod should be inside", craft.enclosed()[0])
        world.stage(craft)
        assertFalse("the pod is still inside", craft.enclosed()[0])
        assertEquals(before - 700.0, craft.body.mass, 1.0)
        val halves = world.vessels.filter { it.name == "Shroud Half" }
        assertEquals(2, halves.size)
        // Out sideways from the craft's axis, opposite ways.
        val a = halves[0].body.linearVelocity.copy().subInPlace(craft.body.linearVelocity)
        val b = halves[1].body.linearVelocity.copy().subInPlace(craft.body.linearVelocity)
        assertTrue("not thrown apart: $a, $b", (a dot b) < 0.0 && a.length > 1.0)
        repeat(60) { world.step(dt) }
        assertTrue("the halves stayed on it", halves.all { it.body.position.distanceTo(craft.body.position) > 2.0 })
    }

    /** The pod's temperature after [seconds] at 2 km/s in thin air, the Shroud [open] or not. */
    private fun heated(open: Boolean, seconds: Double): Pair<Double, Vessel> {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val r = terra.radius + 35_000.0
        val up = Vec3(1.0, 0.0, 0.0)
        // Nose first along its path.
        val velocity = Vec3(0.0, 0.0, 2_000.0).addInPlace(terra.surfaceVelocityAt(up.copy().mulInPlace(r), Vec3()))
        val rotation = com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), velocity.normalized())
        val craft = world.spawnAt(shrouded(), "terra", up.copy().mulInPlace(r), velocity, rotation)
        if (open) world.stage(craft)
        repeat((seconds / dt).toInt()) { world.step(dt) }
        return craft.temperature[0] to craft
    }

    @Test
    fun `inside a closed Shroud the pod takes no heat from the air, and no drag`() {
        val (closed, closedCraft) = heated(open = false, seconds = 4.0)
        val (open, openCraft) = heated(open = true, seconds = 4.0)
        assertTrue("shrouded pod heated to $closed K, bare one to $open K", open > closed + 20.0)
        // And its drag: the bare pod's blunt face costs more than the shell round it.
        val forces = Forces()
        assertTrue(
            "closed ${forces.dragArea(closedCraft)} m² vs open ${forces.dragArea(openCraft)} m²",
            forces.dragArea(closedCraft) < forces.dragArea(openCraft),
        )
    }

    @Test
    fun `the builder stages a Shroud before what it holds fires`() {
        val builder = CraftBuilder(catalog)
        assertTrue(builder.placeRoot("fairing-base"))
        val top = builder.openNodes().first { it.partIndex == 0 && it.node.id == "top" }
        assertTrue(builder.attach("adapter-taper", top).isNotEmpty())
        val taperTop = builder.openNodes().first { it.partIndex == 1 && it.node.id == "top" }
        assertTrue(builder.attach("pod-halo", taperTop).isNotEmpty())
        builder.restage()
        assertTrue("the Shroud cannot be staged", builder.isStageable(0))
        val stages = builder.design.stages
        assertTrue("the Shroud is in no stage: $stages", stages.any { 0 in it.activatedParts })
        val defs = builder.design.parts.map { catalog[it.partId]!! }
        val inside = com.rm.apogee.core.craft.Fairings.enclosed(builder.design, defs) { false }
        val pod = builder.design.parts.indexOfFirst { it.partId == "pod-halo" }
        assertTrue("a pod put on a Shroud's ring is not inside it", inside[pod])
    }
}
