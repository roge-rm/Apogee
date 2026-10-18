package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftBuilder
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.part.CurveSteps
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.Tank
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** A solid motor: full thrust once lit, no throttle, no shutdown, only its own fuel. */
class SolidMotorTest {

    private val catalog = StockParts.catalog
    private val terra = World.default(catalog).system.body("terra")
    private val dt = 1.0 / 60.0
    private val G0 = 9.80665
    private val flint = catalog.require("srb-flint")
    private val engine = flint.module<Engine>()!!
    /** Thrust at lighting, on its curve. */
    private val lighting = engine.thrustVacuum * engine.thrustFactor(1.0)

    /** Two Flints stacked, so they share a fuel group. Index 0 is the top one. */
    private fun twoFlints(group: Int = 0): CraftDesign {
        val builder = CraftBuilder(catalog)
        check(builder.placeRoot("srb-flint"))
        val target = builder.openNodes().first { it.partIndex == 0 && it.node.id == "bottom" }
        builder.attach("srb-flint", target)
        val design = builder.design
        return design.copy(parts = design.parts.map { it.copy(group = group) })
    }

    /** In vacuum with only the bottom Flint lit. */
    private fun lit(design: CraftDesign = twoFlints()): Vessel {
        val vessel = Vessel(VesselId(1), design, design.parts.map { catalog.require(it.partId) }, "terra")
        vessel.body.position.setTo(0.0, terra.radius + 200_000.0, 0.0)
        vessel.body.orientation.setIdentity()
        vessel.restoreStaging(0, listOf(1))
        vessel.recomputeMass(shiftBodyPosition = false)
        return vessel
    }

    private fun burn(vessel: Vessel, forces: Forces = Forces()): Double {
        vessel.body.clearAccumulators()
        forces.applyThrust(vessel, terra, dt)
        return vessel.body.force.length
    }

    @Test
    fun `it burns at full thrust with the throttle closed`() {
        val vessel = lit()
        vessel.control.throttle = 0.0
        assertTrue(vessel.burning)
        assertEquals(lighting, burn(vessel), lighting * 1e-6)
    }

    @Test
    fun `the throttle doesn't change it`() {
        val vessel = lit()
        vessel.control.throttle = 0.3
        assertEquals(lighting, burn(vessel), lighting * 1e-3)
        vessel.control.throttle = 1.0
        assertEquals(lighting, burn(vessel), lighting * 1e-3)
    }

    @Test
    fun `switching its action group off doesn't stop it`() {
        val vessel = lit(twoFlints(group = 1))
        vessel.groupStates[1] = -1
        vessel.control.throttle = 0.0
        assertEquals(listOf(1), vessel.activeEngines())
        assertEquals(lighting, burn(vessel), lighting * 1e-6)
    }

    @Test
    fun `it burns to empty in fuel over flow`() {
        val vessel = lit()
        val full = vessel.amountInPart(1, ResourceType.SOLID_FUEL)
        val expected = CurveSteps.burn(engine, full * ResourceType.SOLID_FUEL.densityPerUnit).seconds
        val forces = Forces()
        var t = 0.0
        while (burn(vessel, forces) > 0.0 && t < 1000.0) t += dt
        assertEquals("burn time", expected, t, expected * 0.01)
        assertEquals(0.0, vessel.amountInPart(1, ResourceType.SOLID_FUEL), 1e-9)
        assertFalse("out, it stops", vessel.burning)
    }

    /** One [id] alone in vacuum, lit, burnt till it's out: its burn time and impulse against the stats. */
    private fun curveCheck(id: String) {
        val def = catalog.require(id)
        val motor = def.module<Engine>()!!
        val builder = CraftBuilder(catalog)
        check(builder.placeRoot(id))
        val design = builder.design.copy(stages = listOf(com.rm.apogee.core.craft.Stage(listOf(0))))
        val stats = CraftStats.analyze(design, catalog).stages.single()
        val vessel = Vessel(VesselId(1), design, listOf(def), "terra")
        vessel.body.position.setTo(0.0, terra.radius + 200_000.0, 0.0)
        vessel.body.orientation.setIdentity()
        vessel.restoreStaging(0, listOf(0))
        vessel.recomputeMass(shiftBodyPosition = false)
        val fullKg = def.module<Tank>()!!.capacity * ResourceType.SOLID_FUEL.densityPerUnit
        val forces = Forces()
        var t = 0.0
        var impulse = 0.0
        val thrust = ArrayList<Double>()
        while (t < 1000.0) {
            val force = burn(vessel, forces)
            if (force <= 0.0) break
            thrust.add(force)
            impulse += force * dt
            t += dt
        }
        val total = fullKg * motor.ispVacuum * G0
        println("$id: burns ${"%.1f".format(t)} s (stats ${"%.1f".format(stats.burnTime)} s, flat ${"%.1f".format(fullKg / (motor.thrustVacuum / (motor.ispVacuum * G0)))} s), impulse ${"%.0f".format(impulse / 1000)} kN s of ${"%.0f".format(total / 1000)}")
        assertEquals("$id burn time", stats.burnTime, t, stats.burnTime * 0.01)
        assertEquals("$id total impulse", total, impulse, total * 0.01)
        // High at lighting, a dip, then a tail-off.
        val rated = motor.thrustVacuum
        assertTrue("$id lights high", thrust.first() > rated * 1.1)
        assertTrue("$id dips", thrust.subList(thrust.size / 4, thrust.size * 3 / 4).min() < rated)
        assertTrue("$id tails off", thrust.last() < rated * 0.5)
    }

    @Test
    fun `each solid burns along its curve as the stats say`() {
        curveCheck("srb-flint")
        curveCheck("srb-torch")
        curveCheck("srb-pyre")
    }

    @Test
    fun `it draws only from its own part`() {
        val vessel = lit()
        val full = vessel.amountInPart(0, ResourceType.SOLID_FUEL)
        assertEquals("the two share a fuel group", 2.0 * full, vessel.amountInGroupOf(1, ResourceType.SOLID_FUEL), 1e-9)
        val forces = Forces()
        repeat(600) { burn(vessel, forces) }
        assertEquals("the unlit one keeps its fuel", full, vessel.amountInPart(0, ResourceType.SOLID_FUEL), 1e-9)
        assertTrue(vessel.amountInPart(1, ResourceType.SOLID_FUEL) < full * 0.8)
    }

    @Test
    fun `its mass drops as it burns`() {
        val world = World.default(catalog)
        val r = terra.radius + 200_000.0
        val speed = sqrt(terra.gravitationalParameter / r)
        // Two stacked, the bottom one lit, which pushes the other without tearing it off.
        val design = twoFlints()
        val craft = world.spawnAt(design, "terra", Vec3(r, 0.0, 0.0), Vec3(0.0, speed, 0.0), Quat())
        craft.restoreStaging(0, listOf(1))
        craft.control.throttle = 0.0
        val before = craft.body.mass
        var strain = 0.0
        repeat(600) { world.step(dt); strain = maxOf(strain, craft.stress) }
        val burnt = before - craft.body.mass
        val fullKg = flint.module<Tank>()!!.capacity * ResourceType.SOLID_FUEL.densityPerUnit
        val expected = CurveSteps.burn(engine, fullKg, limit = 10.0).burntKg
        assertEquals("ten seconds' fuel", expected, burnt, expected * 0.05)
        assertEquals("still in one piece", design.parts.size, craft.design.parts.size)
        assertTrue("joints at $strain of their strength", strain < 0.7)
    }

    @Test
    fun `the autopilot won't fly with a solid motor next or lit`() {
        val world = World.default(catalog)
        val r = terra.radius + 200_000.0
        val speed = sqrt(terra.gravitationalParameter / r)
        val design = twoFlints().copy(stages = listOf(com.rm.apogee.core.craft.Stage(listOf(1)), com.rm.apogee.core.craft.Stage(listOf(0))))
        val craft = world.spawnAt(design, "terra", Vec3(r, 0.0, 0.0), Vec3(0.0, speed, 0.0), Quat())
        val id = craft.id.raw
        world.apply(Command.PlanBurns(id, listOf(PlannedBurn(world.time + 60.0, prograde = 100.0))))
        world.apply(Command.SetAutopilot(id, autoBurn = true, autoLand = false))
        assertFalse(craft.control.autoBurn)
        assertEquals("Next stage lights a solid motor", craft.control.autopilotNote)

        world.stage(craft)
        assertTrue(craft.solidLit)
        assertEquals("a solid isn't throttle thrust", 0.0, world.litThrust(craft), 0.0)
        world.apply(Command.SetAutopilot(id, autoBurn = true, autoLand = false))
        assertFalse(craft.control.autoBurn)
        world.apply(Command.SetAutopilot(id, autoBurn = false, autoLand = true))
        assertFalse(craft.control.autoLand)
        assertEquals("Solid motor burning", craft.control.autopilotNote)

        // Already flying when the solid lights: it lets go.
        craft.control.autoLand = true
        world.step(dt)
        assertFalse(craft.control.autoLand)
        assertEquals("Solid motor burning", craft.control.autopilotNote)
    }
}
