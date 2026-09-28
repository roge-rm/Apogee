package com.rm.apogee.core.craft

import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Boosters on the side of a stack: held by a clamp, fed through one, and let go outward. */
class BoosterTest {

    private val catalog = StockParts.catalog

    /** A core of a tank and an engine, with a booster of the same on a [clamp] on its side. */
    private fun withBooster(clamp: String): CraftDesign {
        val builder = CraftBuilder(catalog)
        check(builder.placeRoot("tank-cask4"))
        fun on(parent: Int, node: String, part: String): Int {
            val target = builder.openNodes().first { it.partIndex == parent && it.node.id == node }
            return builder.attach(part, target).first()
        }
        on(0, "bottom", "engine-ember")
        val held = on(0, "surface-0", clamp)
        val booster = on(held, "mount", "tank-cask4")
        on(booster, "bottom", "engine-ember")
        builder.restage()
        return builder.design
    }

    private fun tanks(design: CraftDesign) = design.parts.indices.filter { design.parts[it].partId == "tank-cask4" }

    @Test
    fun `the booster stands upright beside the core, level with it`() {
        val design = withBooster("decoupler-side")
        val (core, booster) = tanks(design)
        val a = design.parts[core]; val b = design.parts[booster]
        assertEquals("level", a.position.y, b.position.y, 0.05)
        assertTrue("beside it", abs(b.position.x - a.position.x) + abs(b.position.z - a.position.z) > 1.2)
        assertEquals("upright", 1.0, b.rotation.rotate(com.rm.apogee.core.math.Vec3.unitY()).y, 1e-6)
    }

    @Test
    fun `a plain clamp keeps the booster's fuel its own, and a feed clamp shares it`() {
        val plain = withBooster("decoupler-side")
        val (a, b) = tanks(plain)
        val defs = plain.parts.map { catalog.require(it.partId) }
        FuelGroups.compute(plain, defs).let { assertNotEquals(it[a], it[b]) }
        val fed = withBooster("decoupler-feed")
        val (c, d) = tanks(fed)
        val fedDefs = fed.parts.map { catalog.require(it.partId) }
        FuelGroups.compute(fed, fedDefs).let { assertEquals(it[c], it[d]) }
        FuelGroups.tiers(fed, fedDefs).let { assertEquals(0, it[c]); assertEquals(1, it[d]) }
    }

    @Test
    fun `fed, the booster's tank is drunk dry before the core's is touched`() {
        val design = withBooster("decoupler-feed")
        val (core, booster) = tanks(design)
        val vessel = Vessel(VesselId(1), design, design.parts.map { catalog.require(it.partId) }, "terra")
        val full = vessel.amountInPart(core, ResourceType.PROPELLANT)
        val engine = design.parts.indexOfFirst { it.partId == "engine-ember" }
        // Most of the booster's tank.
        vessel.drainFromGroupOf(engine, ResourceType.PROPELLANT, full * 0.9)
        assertEquals("the core's untouched", full, vessel.amountInPart(core, ResourceType.PROPELLANT), 1e-6)
        assertEquals("the booster's nearly empty", full * 0.1, vessel.amountInPart(booster, ResourceType.PROPELLANT), 1e-6)
        // Then past it, into the core.
        vessel.drainFromGroupOf(engine, ResourceType.PROPELLANT, full * 0.3)
        assertEquals(0.0, vessel.amountInPart(booster, ResourceType.PROPELLANT), 1e-6)
        assertEquals(full * 0.8, vessel.amountInPart(core, ResourceType.PROPELLANT), 1e-6)
    }

    @Test
    fun `the builder's first stage burns the booster's fuel, and the next the core's`() {
        val design = withBooster("decoupler-feed")
        val stats = CraftStats.analyze(design, catalog)
        val burns = stats.stages.filter { it.isBurn }
        assertTrue("two burns: $burns", burns.size >= 2)
        val first = burns.first()
        val tank = catalog.require("tank-cask4").module<com.rm.apogee.core.part.Tank>()!!.capacity
        assertEquals("one tank's worth", tank, first.fuel.first().amount, 1.0)
    }

    @Test
    fun `staged, the clamp lets the booster go outward as a craft of its own`() {
        val world = com.rm.apogee.core.world.World.default(catalog)
        val craft = world.spawnFor(com.rm.apogee.core.world.Command.SpawnCraft(withBooster("decoupler-side"), "cape"), "p1")
        val clamp = craft.design.parts.indexOfFirst { it.partId == "decoupler-side" }
        val stage = craft.design.stages.indexOfFirst { clamp in it.activatedParts }
        assertTrue("the clamp is staged", stage >= 0)
        repeat(stage + 1) { world.stage(craft); repeat(5) { world.step(1.0 / 60.0) } }
        repeat(30) { world.step(1.0 / 60.0) }
        assertTrue("the core lost its booster", craft.design.parts.count { it.partId == "tank-cask4" } == 1)
        val booster = world.vessels.first { it !== craft && it.design.parts.any { p -> p.partId == "tank-cask4" } }
        val apart = booster.body.position.distanceTo(craft.body.position)
        assertTrue("it's moving away, $apart m apart", apart > 1.6)
    }
}
