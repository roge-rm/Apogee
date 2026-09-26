package com.rm.apogee.core.scenario

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.SystemData
import com.rm.apogee.core.orbit.Trajectory
import com.rm.apogee.core.orbit.TransferWindow
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Burns
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.PlannedBurn
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * To Rubra: from a parking orbit round Terra, out at the window the map
 * gives, a correction on the way, and caught into orbit at Rubra - every
 * burn flown by the autopilot. Then, at the canyon, the last of a landing.
 */
class RubraMissionTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /**
     * The Moonshot above its first stage: its upper stage and the lander on
     * top, as a player would send it on from orbit. (A bigger upper tank
     * was more than its wheels and its engine's swivel could hold steady.)
     */
    private fun cruiser(): CraftDesign {
        val m = StockCraft.moonshot(catalog)
        val drop = setOf("decoupler-broad", "tank-broad8", "engine-forge")
        val keep = m.parts.indices.filter { m.parts[it].partId !in drop }
        val remap = keep.withIndex().associate { (n, old) -> old to n }
        val parts = keep.map { old ->
            val p = m.parts[old]
            p.copy(parentIndex = if (p.parentIndex < 0) -1 else remap.getValue(p.parentIndex))
        }
        val stages = m.stages.drop(1).map { s -> s.copy(activatedParts = s.activatedParts.mapNotNull { remap[it] }) }
        return m.copy(name = "Rubra Cruiser", parts = parts, stages = stages)
    }

    private fun autoBurn(world: World, craft: Vessel, name: String) {
        world.apply(Command.SetAutopilot(craft.id.raw, autoBurn = true, autoLand = false))
        val limit = (craft.plannedBurns.first().time - world.time).coerceAtLeast(0.0) + 900.0
        var t = 0.0
        while (craft.plannedBurns.isNotEmpty() && t < limit) {
            world.step(dt); t += dt
        }
        assertTrue(
            "the $name burn never finished: ${craft.control.autopilotNote}, ${world.burnRemaining(craft)} m/s left, " +
                "charge ${craft.amountOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE)}, powered ${craft.powered}",
            craft.plannedBurns.isEmpty(),
        )
    }

    /** How well a path from [orbit] (about [bodyId]) at [time] meets Rubra: 0 is perfect; lower is better. */
    private fun miss(world: World, bodyId: String, orbit: Orbit, time: Double): Double {
        val rubra = world.system.body("rubra")
        val path = Trajectory.predict(world.system, bodyId, orbit.position, orbit.velocity, time)
        path.about("rubra")?.let { return abs(it.orbit.periapsis - rubra.radius - GOAL) / 1_000.0 }
        val leg = path.about("sol") ?: return Double.MAX_VALUE
        val sol = world.system.body("sol").id
        return 1e6 + Trajectory.closestApproach(leg) { t, out ->
            out.setTo(world.system.positionOf("rubra", t)).subInPlace(world.system.positionOf(sol, t))
        }.distance
    }

    private fun meetsWell(world: World, bodyId: String, orbit: Orbit, time: Double) = miss(world, bodyId, orbit, time) < 150.0

    /**
     * The smallest burn at [at] that brings the path from [orbit] to
     * Rubra's low orbit: a step each way along each axis, halving the step
     * whenever none helps - as a player nudges the burn until the map meets.
     */
    private fun correction(world: World, bodyId: String, orbit: Orbit, at: Double, start: PlannedBurn = PlannedBurn(at)): PlannedBurn {
        var best = start
        var score = miss(world, bodyId, Burns.after(best, orbit), at)
        var step = 20.0
        while (step > 0.02 && score > 20.0) {
            var improved = false
            for ((dp, dn, dr) in listOf(Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0), Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1))) {
                val burn = PlannedBurn(at, best.prograde + dp * step, best.normal + dn * step, best.radial + dr * step)
                val s = miss(world, bodyId, Burns.after(burn, orbit), at)
                if (s < score) { score = s; best = burn; improved = true }
            }
            if (!improved) step *= 0.5
        }
        return best
    }

    @Test
    fun `the Cruiser has the delta-v for the crossing and the capture`() {
        val design = cruiser()
        assertTrue("won't validate: ${design.validate(catalog)}", design.validate(catalog).isEmpty())
        // Out of a low orbit at the window, a correction, and caught at Rubra: about 1.75 km/s.
        val upper = CraftStats.analyze(design, catalog).stages.first().deltaVVacuum
        assertTrue("its upper stage has only $upper m/s", upper > 2_000.0)
    }

    @Test
    fun `out from Terra at the window, and caught by Rubra`() {
        val world = World.default(catalog)
        val system = world.system
        val terra = system.body("terra")
        val rubra = system.body("rubra")

        // A circular parking orbit, 150 km up, in the plane the planets go round in.
        val r = terra.radius + 150_000.0
        val north = SystemData.ECLIPTIC_NORTH.normalized()
        val out = Vec3(1.0, 0.0, 0.0).let { it.subInPlace(north * (it dot north)).normalizeInPlace() }
        val along = north.cross(out).normalizeInPlace()
        val craft = world.spawnAt(cruiser(), "terra", out * r, along * sqrt(terra.gravitationalParameter / r), Quat.identity())
        craft.control.pitch = 0.0; craft.control.yaw = 0.0; craft.control.roll = 0.0
        // The upper stage's engine lit, as it is when the first stage falls
        // away, and the Shroud thrown open: its panels to the sun for the
        // weeks of waiting and crossing.
        world.stage(craft)
        world.stage(craft)
        assertFalse("the lander still rides inside a closed Shroud", craft.enclosed().any { it })

        // To the window, on rails.
        val window = TransferWindow.between(system, "terra", "rubra", world.time, r)
        assertNotNull("no window to Rubra", window)
        window!!
        val leaveAt = world.time + window.waitFor
        while (world.time < leaveAt - 3_000.0) {
            val moved = world.advanceOnRails(minOf(200_000.0, leaveAt - 3_000.0 - world.time))
            assertTrue("rails stopped at ${world.time}", moved > 0.0)
        }

        // The departure: the window's speed, at whichever point round the
        // parking orbit sends it nearest Rubra - then nudged until it meets.
        val parking = world.orbitOf(craft)
        var departure: PlannedBurn? = null
        var bestScore = Double.MAX_VALUE
        var t = world.time + 300.0
        while (t < world.time + 300.0 + parking.period) {
            for (extra in listOf(-60.0, -30.0, 0.0, 30.0, 60.0, 100.0)) {
                val burn = PlannedBurn(t, prograde = window.departure + extra)
                val s = miss(world, "terra", Burns.after(burn, parking), t)
                if (s < bestScore) { bestScore = s; departure = burn }
            }
            t += 60.0
        }
        departure = correction(world, "terra", parking, departure!!.time, departure)
        world.apply(Command.PlanBurns(craft.id.raw, listOf(departure)))
        autoBurn(world, craft, "departure")

        // Out of Terra's reach, and a correction or two on the way.
        while (craft.referenceBodyId == "terra") assertTrue(world.advanceOnRails(20_000.0) > 0.0)
        assertEquals("sol", craft.referenceBodyId)
        repeat(3) {
            val coasting = world.orbitOf(craft)
            if (meetsWell(world, "sol", coasting, world.time)) return@repeat
            val at = world.time + 600.0
            val fix = correction(world, "sol", coasting, at)
            world.apply(Command.PlanBurns(craft.id.raw, listOf(fix)))
            autoBurn(world, craft, "correction")
            // A little way on, and look again.
            world.advanceOnRails(200_000.0)
        }
        assertTrue("the path never met Rubra", meetsWell(world, "sol", world.orbitOf(craft), world.time))

        // Across, on rails, at the fastest warps.
        var crossed = 0.0
        while (craft.referenceBodyId == "sol" && crossed < 3.0e7) {
            val moved = world.advanceOnRails(200_000.0)
            assertTrue("rails stopped out between the worlds at ${world.time}", moved > 0.0)
            crossed += moved
        }
        assertEquals("never reached Rubra", "rubra", craft.referenceBodyId)

        // Caught at the low point: slowed to a circle there.
        val o = world.orbitOf(craft)
        assertTrue("passing Rubra ${o.periapsis - rubra.radius} m up", o.periapsis - rubra.radius > 20_000.0)
        while (world.orbitOf(craft).timeToPeriapsis > 400.0) {
            val left = world.orbitOf(craft).timeToPeriapsis - 400.0
            if (world.advanceOnRails(left) <= 0.0) break
        }
        val at = world.orbitOf(craft).let { it.propagate(it.timeToPeriapsis) }
        val capture = at.velocity.length - sqrt(rubra.gravitationalParameter / at.position.length)
        world.apply(Command.PlanBurns(craft.id.raw, listOf(PlannedBurn(world.time + world.orbitOf(craft).timeToPeriapsis, prograde = -capture))))
        autoBurn(world, craft, "capture")
        val caught = world.orbitOf(craft)
        assertTrue("not caught by Rubra: $caught", caught.isBound)
        assertTrue("caught into an orbit that hits the ground: ${caught.periapsis - rubra.radius} m", caught.periapsis > rubra.radius + 10_000.0)
        assertFalse("broke on the way", craft.broken.any { it })
    }

    @Test
    fun `the lander sets itself down in the canyon`() {
        val world = World.default(catalog)
        val site = World.launchSites.first { it.id == "rubra-rift" }
        val craft = world.spawnOnSurface(StockCraft.lander(catalog), site)
        world.stage(craft)
        val body = world.attractorFor(craft)
        craft.wake()
        // Coming down into the canyon, a few kilometres up and falling.
        val up = craft.body.position.normalized()
        craft.body.position.addScaledInPlace(up, 3_000.0)
        body.surfaceVelocityAt(craft.body.position, craft.body.linearVelocity).addScaledInPlace(up, -35.0)
        world.apply(Command.SetAutopilot(craft.id.raw, autoBurn = false, autoLand = true))
        var t = 0.0
        while (craft.control.autoLand && t < 600.0) { world.step(dt); t += dt }
        assertEquals("did not land: ${craft.control.autopilotNote}", "Landed", craft.control.autopilotNote)
        assertFalse("broke landing", craft.broken.any { it })
        val tilt = Math.toDegrees(kotlin.math.acos((craft.forward() dot craft.body.position.copy().normalizeInPlace()).coerceIn(-1.0, 1.0)))
        assertTrue("landed tilted $tilt degrees", tilt < 10.0)
    }

    private companion object {
        /** The low point to aim for at Rubra, m above its datum. */
        const val GOAL = 200_000.0
    }
}
