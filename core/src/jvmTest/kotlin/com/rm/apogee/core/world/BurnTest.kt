package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.Trajectory
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/** Planned burns: what they do to an orbit, how long they take, and flown by the autopilot to Luna and into orbit there. */
class BurnTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    @Test
    fun `a prograde burn raises the far side of the orbit and nothing else`() {
        val mu = 3.5316e12
        val r = 700_000.0
        val orbit = Orbit(Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, sqrt(mu / r)), mu, 0.0)
        val after = Burns.after(PlannedBurn(0.0, prograde = 200.0), orbit)
        assertEquals(r, after.periapsis, 1.0)
        assertTrue("apoapsis ${after.apoapsis}", after.apoapsis > r + 250_000.0)
        // Normal tips the plane and radial swings the apsides, and neither changes the speed much.
        val tipped = Burns.after(PlannedBurn(0.0, normal = 100.0), orbit)
        assertTrue("normal did not tip the plane", tipped.inclination - orbit.inclination > 0.01 || orbit.inclination - tipped.inclination > 0.01)
    }

    /**
     * The Stilt Lander in a 100 km orbit around Terra, in Luna's plane, placed so that [lead]
     * seconds on from it is where a transfer to Luna should start.
     */
    private fun parked(world: World, lead: Double, wide: Double): Pair<Vessel, Double> {
        val system = world.system
        val terra = system.body("terra")
        val luna = system.body("luna")
        val mu = terra.gravitationalParameter
        val r1 = terra.radius + 100_000.0
        val r2 = luna.orbit!!.semiMajorAxis + wide
        val a = 0.5 * (r1 + r2)
        val flight = PI * sqrt(a * a * a / mu)
        val departs = world.time + lead
        val normal = luna.orbit!!.angularMomentum.normalized()
        val arrival = luna.orbit!!.stateAt(departs + flight).position.normalized()
        val departure = arrival.copy().negateInPlace()
        // Back along the circular orbit by [lead] seconds' worth.
        val back = Quat.fromAxisAngle(normal, -lead * sqrt(mu / r1) / r1)
        val position = back.rotate(departure).mulInPlace(r1)
        val velocity = normal.cross(position).normalizeInPlace().mulInPlace(sqrt(mu / r1))
        val design = StockCraft.lander(catalog)
        // Nose along the way it's going.
        val rotation = quatFromTo(design.orientation.forward, velocity.normalized())
        val craft = world.spawnAt(design, "terra", position, velocity, rotation)
        val departSpeed = sqrt(mu * (2.0 / r1 - 1.0 / a))
        return craft to (departSpeed - sqrt(mu / r1))
    }

    private fun flyBurn(world: World, craft: Vessel, limit: Double) {
        var t = 0.0
        while (craft.plannedBurns.isNotEmpty() && t < limit) {
            world.step(dt); t += dt
        }
        assertTrue("the burn never finished (${craft.control.autopilotNote})", craft.plannedBurns.isEmpty())
        assertEquals("throttle left open", 0.0, craft.control.throttle, 0.0)
    }

    @Test
    fun `the autopilot flies a transfer to Luna and a capture into orbit there`() {
        val world = World.default(catalog)
        val (craft, deltaV) = parked(world, lead = 240.0, wide = 800_000.0)
        world.stage(craft)
        val id = craft.id.raw
        world.apply(Command.PlanBurns(id, listOf(PlannedBurn(world.time + 240.0, prograde = deltaV))))
        assertTrue("no time for the burn worked out", craft.burnDuration in 5.0..300.0)
        world.apply(Command.SetAutopilot(id, autoBurn = true, autoLand = false))
        flyBurn(world, craft, 900.0)

        // On its way, and the map would show it meeting Luna.
        val path = Trajectory.predict(world.system, "terra", craft.body.position, craft.body.linearVelocity, world.time)
        assertEquals(Trajectory.Ending.ENCOUNTER, path.segments.first().ending)
        val about = path.about("luna")!!
        assertTrue("would pass Luna at ${about.orbit.periapsis - world.system.body("luna").radius} m",
            about.orbit.periapsis > world.system.body("luna").radius + 10_000.0)

        // Coast there on rails.
        var coasted = 0.0
        while (craft.referenceBodyId == "terra" && coasted < 100_000.0) { world.advanceOnRails(50.0); coasted += 50.0 }
        assertEquals("luna", craft.referenceBodyId)

        // Brake at the low point into a circular orbit there.
        val luna = world.system.body("luna")
        val orbit = world.orbitOf(craft)
        val low = orbit.timeToPeriapsis
        val atLow = orbit.propagate(low)
        val capture = atLow.velocity.length - sqrt(luna.gravitationalParameter / atLow.position.length)
        world.advanceOnRails(low - 300.0)
        world.apply(Command.PlanBurns(id, listOf(PlannedBurn(world.time + 300.0, prograde = -capture))))
        world.apply(Command.SetAutopilot(id, autoBurn = true, autoLand = false))
        flyBurn(world, craft, 1_200.0)
        val captured = world.orbitOf(craft)
        assertTrue("not caught by Luna: e = ${captured.eccentricity}", captured.isBound)
        assertTrue("its orbit dips into Luna", captured.periapsis > luna.radius + 5_000.0)
        assertTrue("its orbit reaches out of Luna's pull", captured.apoapsis < luna.sphereOfInfluence)
    }

    @Test
    fun `a burn's time is predicted from the stages`() {
        val world = World.default(catalog)
        val (craft, _) = parked(world, lead = 120.0, wide = 0.0)
        world.stage(craft)
        val duration = Burns.duration(craft, 400.0)
        world.apply(Command.PlanBurns(craft.id.raw, listOf(PlannedBurn(world.time + 120.0, prograde = 400.0))))
        world.apply(Command.SetAutopilot(craft.id.raw, autoBurn = true, autoLand = false))
        var burning = 0.0
        var t = 0.0
        while (craft.plannedBurns.isNotEmpty() && t < 600.0) {
            world.step(dt); t += dt
            if (craft.control.throttle > 0.0) burning += dt * craft.control.throttle
        }
        // Full-throttle seconds. The turn and the easing off at the end cost a little.
        assertEquals(duration, burning, duration * 0.05 + 0.5)
    }
}
