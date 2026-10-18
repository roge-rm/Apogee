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
import com.rm.apogee.core.part.ResourceType
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
 * The Wayfarer: enough to reach Terra orbit and go on to Rubra or Caligo, it holds together on the
 * pad and on the way up, and its crossing stage alone takes the lander from parking orbit to an
 * orbit round Rubra.
 */
class WayfarerTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** The parking orbit's radius the crossings are worked out from, 150 km up. */
    private fun parked(world: World) = world.system.body("terra").radius + 150_000.0

    @Test
    fun `the Wayfarer has the delta-v for orbit and a crossing to either neighbour`() {
        val design = StockCraft.wayfarer(catalog)
        assertTrue("won't validate: ${design.validate(catalog)}", design.validate(catalog).isEmpty())
        val stats = CraftStats.analyze(design, catalog)
        val dv = stats.stages.map { it.deltaVVacuum }
        // Boosters, core, upper to orbit; the crossing stage; the lander.
        val toOrbit = dv[0] + dv[1] + dv[2]
        val crossing = dv[4]
        assertTrue("lifts off at ${stats.liftoffTwr}", stats.liftoffTwr in 1.4..2.5)
        // Low Terra orbit takes about 3.4 km/s with the losses on the way up.
        assertTrue("only $toOrbit m/s to orbit", toOrbit > 4_000.0)

        val world = World.default(catalog)
        for (to in listOf("rubra", "caligo")) {
            val window = TransferWindow.between(world.system, "terra", to, 0.0, parked(world))!!
            val need = window.departure + window.arrival
            // A third more for corrections and a burn flown by hand.
            assertTrue("$to takes $need m/s and the crossing stage has $crossing", crossing > need * 1.3)
        }
        assertTrue("the lander has only ${dv[5]} m/s", dv[5] > 1_000.0)
    }

    @Test
    fun `it stands on the pad`() {
        val world = World.default(catalog)
        val craft = world.spawnOnSurface(StockCraft.wayfarer(catalog), World.launchSites.first())
        val parts = craft.design.parts.size
        // Everything above the Shroud's ring rides inside it, and nothing below.
        val inside = craft.enclosed()
        for ((i, part) in craft.design.parts.withIndex()) {
            assertEquals("${part.partId} at ${part.position.y}", part.position.y > 48.5, inside[i])
        }
        var t = 0.0
        while (t < 10.0) { world.step(dt); t += dt }
        val tilt = Math.toDegrees(kotlin.math.acos((craft.forward() dot craft.body.position.copy().normalizeInPlace()).coerceIn(-1.0, 1.0)))
        assertTrue("leaning $tilt degrees", tilt < 1.0)
        assertEquals("lost parts standing still", parts, craft.design.parts.size)
        assertFalse("broke standing still", craft.broken.any { it })
    }

    @Test
    fun `it flies to orbit whole, dropping its boosters clear`() {
        var boostersLeft: List<Vessel> = emptyList()
        var dropped = -1.0
        var clearance = Double.MAX_VALUE
        var atDrop = 0.0
        val ascent = AscentScenario(
            design = { StockCraft.wayfarer(it) },
            onTick = { w, v ->
                val alt = w.attractorFor(v).altitudeOf(v.body.position)
                // The boosters go when their tanks are dry; everything drinks those first.
                val boosters = v.design.parts.indices.filter { i ->
                    v.design.parts[i].partId == "tank-broad8" &&
                        v.design.parts.getOrNull(v.design.parts[i].parentIndex)?.partId == "decoupler-feed"
                }
                if (v.currentStage == 1 && boosters.isNotEmpty() && boosters.all { v.amountInPart(it, ResourceType.PROPELLANT) < 1e-6 }) {
                    val before = w.vessels.toSet()
                    w.stage(v)
                    dropped = w.time
                    boostersLeft = w.vessels.filter { it !in before && it !== v }
                    atDrop = boostersLeft.minOfOrNull { it.body.position.distanceTo(v.body.position) } ?: 0.0
                }
                // How near any dropped booster comes to the core, over the next ten seconds.
                if (dropped >= 0.0 && w.time - dropped < 10.0) {
                    for (b in boostersLeft) if (b in w.vessels) clearance = minOf(clearance, b.body.position.distanceTo(v.body.position))
                }
                // The core is let go once it has coasted out of the air, so the upper stage starts
                // its circularising burn in time.
                if (v.currentStage == 2 && v.control.throttle == 0.0 && alt > 45_000.0) w.stage(v)
                if (v.currentStage == 3 && alt > 60_000.0) w.stage(v)
            },
        ).fly()
        if (!ascent.reachedOrbit) println(ascent.log.joinToString("\n"))
        assertTrue("never made orbit: ${ascent.failure}", ascent.reachedOrbit)
        assertEquals("parts torn off on the way up", 0, ascent.partsLost)
        assertTrue("a joint near snapping: ${ascent.peakStress} at ${ascent.peakStressPart}", ascent.peakStress < 0.8)
        assertEquals("four boosters let go", 4, boostersLeft.size)
        // Centre to centre: none comes back toward the core after letting go.
        assertTrue("a booster closed to $clearance m from $atDrop m", clearance > atDrop - 0.5)
        println("Wayfarer ascent: peak stress ${ascent.peakStress} at ${ascent.peakStressPart}, nearest booster $clearance m from $atDrop m, apoapsis ${ascent.apoapsisAltitude}, periapsis ${ascent.periapsisAltitude}, fuel left ${ascent.propellantRemaining}")
        val craft = ascent.vessel!!
        assertFalse("the lander still rides inside a closed Shroud", craft.enclosed().any { it })
        assertEquals("the crossing stage lights next", 4, craft.currentStage)
        // The upper stage is still on, with what it has left for the crossing.
        assertEquals("a stage went with the climb", 2, craft.defs.count { it.id == "engine-lantern" })
    }

    /** The lander and its crossing stage, as the climb leaves them. */
    private fun crossingStage(): CraftDesign {
        val w = StockCraft.wayfarer(catalog)
        // Everything below the crossing stage's own engine.
        val firstDrop = w.parts.indexOfFirst { it.partId == "decoupler-broad" }
        val keep = w.parts.indices.filter { it < firstDrop }
        val remap = keep.withIndex().associate { (n, old) -> old to n }
        val parts = keep.map { old ->
            val p = w.parts[old]
            p.copy(parentIndex = if (p.parentIndex < 0) -1 else remap.getValue(p.parentIndex))
        }
        val stages = w.stages.drop(3).map { s -> s.copy(activatedParts = s.activatedParts.mapNotNull { remap[it] }) }
        return w.copy(name = "Wayfarer Crossing", parts = parts, stages = stages)
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
                "charge ${craft.amountOf(ResourceType.ELECTRIC_CHARGE)}, powered ${craft.powered}",
            craft.plannedBurns.isEmpty(),
        )
    }

    /** How well a path from [orbit] around [bodyId] at [time] meets Rubra. 0 is perfect. */
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

    /** The smallest burn at [at] that brings the path from [orbit] to Rubra's low orbit. */
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
    fun `its crossing stage takes the lander from parking orbit into orbit round Rubra`() {
        val world = World.default(catalog)
        val system = world.system
        val terra = system.body("terra")
        val rubra = system.body("rubra")

        // A circular parking orbit, 150 km up, in the plane the planets go round in.
        val r = parked(world)
        val north = SystemData.ECLIPTIC_NORTH.normalized()
        val out = Vec3(1.0, 0.0, 0.0).let { it.subInPlace(north * (it dot north)).normalizeInPlace() }
        val along = north.cross(out).normalizeInPlace()
        val craft = world.spawnAt(crossingStage(), "terra", out * r, along * sqrt(terra.gravitationalParameter / r), Quat.identity())
        craft.control.pitch = 0.0; craft.control.yaw = 0.0; craft.control.roll = 0.0
        // Open the Shroud, so the panels get sun on the crossing, and light the crossing stage.
        world.stage(craft)
        world.stage(craft)
        assertFalse("the lander still rides inside a closed Shroud", craft.enclosed().any { it })
        val fuelAtStart = craft.amountOf(ResourceType.PROPELLANT)

        val window = assertNotNull(TransferWindow.between(system, "terra", "rubra", world.time, r)).let {
            TransferWindow.between(system, "terra", "rubra", world.time, r)!!
        }
        val leaveAt = world.time + window.waitFor
        while (world.time < leaveAt - 3_000.0) {
            val moved = world.advanceOnRails(minOf(200_000.0, leaveAt - 3_000.0 - world.time))
            assertTrue("rails stopped at ${world.time}", moved > 0.0)
        }

        // The window's speed, at the point of the parking orbit nearest Rubra, then nudged to meet.
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

        while (craft.referenceBodyId == "terra") assertTrue(world.advanceOnRails(20_000.0) > 0.0)
        assertEquals("sol", craft.referenceBodyId)
        repeat(3) {
            val coasting = world.orbitOf(craft)
            if (meetsWell(world, "sol", coasting, world.time)) return@repeat
            val at = world.time + 600.0
            world.apply(Command.PlanBurns(craft.id.raw, listOf(correction(world, "sol", coasting, at))))
            autoBurn(world, craft, "correction")
            world.advanceOnRails(200_000.0)
        }
        assertTrue("the path never met Rubra", meetsWell(world, "sol", world.orbitOf(craft), world.time))

        // In short steps: the rails only look for a new world at the end of each, and one long
        // enough passes through Rubra's reach without noticing.
        var crossed = 0.0
        while (craft.referenceBodyId == "sol" && crossed < 3.0e7) {
            val moved = world.advanceOnRails(5_000.0)
            assertTrue("rails stopped out between the worlds at ${world.time}", moved > 0.0)
            crossed += moved
        }
        assertEquals("never reached Rubra: miss ${miss(world, craft.referenceBodyId, world.orbitOf(craft), world.time)}", "rubra", craft.referenceBodyId)

        // Caught at the low point, slowed to a circle there.
        assertTrue("passing Rubra ${world.orbitOf(craft).periapsis - rubra.radius} m up", world.orbitOf(craft).periapsis - rubra.radius > 20_000.0)
        while (world.orbitOf(craft).timeToPeriapsis > 400.0) {
            if (world.advanceOnRails(world.orbitOf(craft).timeToPeriapsis - 400.0) <= 0.0) break
        }
        val at = world.orbitOf(craft).let { it.propagate(it.timeToPeriapsis) }
        val capture = at.velocity.length - sqrt(rubra.gravitationalParameter / at.position.length)
        world.apply(Command.PlanBurns(craft.id.raw, listOf(PlannedBurn(world.time + world.orbitOf(craft).timeToPeriapsis, prograde = -capture))))
        autoBurn(world, craft, "capture")
        val caught = world.orbitOf(craft)
        assertTrue("not caught by Rubra: $caught", caught.isBound)
        assertTrue("caught into an orbit that hits the ground: ${caught.periapsis - rubra.radius} m", caught.periapsis > rubra.radius + 10_000.0)
        assertFalse("broke on the way", craft.broken.any { it })
        // Still on the crossing stage, with fuel to spare, and the lander's own tank untouched.
        assertTrue("the crossing stage was used up", craft.defs.any { it.id == "engine-lantern" })
        val used = fuelAtStart - craft.amountOf(ResourceType.PROPELLANT)
        println("Wayfarer to Rubra: departure ${departure.deltaV} m/s, capture $capture m/s, used $used of $fuelAtStart units")
        assertTrue("used $used of $fuelAtStart units", used < 0.85 * (fuelAtStart - 200.0))
    }

    private companion object {
        /** The low point to aim for at Rubra, in metres above its datum. */
        const val GOAL = 200_000.0
    }
}
