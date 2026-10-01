package com.rm.apogee.core.scenario

import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.orbit.LaunchWindows
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.orbit.Trajectory
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
import kotlin.math.sqrt

/**
 * The Moonshot: launched at a window for Luna, into orbit, on to Luna by a planned burn, into orbit
 * there, and down onto it. The autopilot flies every burn, the way a player could.
 */
class MoonshotTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    @Test
    fun `the Moonshot has the delta-v to land on Luna`() {
        val stats = CraftStats.analyze(StockCraft.moonshot(catalog), catalog)
        assertTrue("won't validate: ${StockCraft.moonshot(catalog).validate(catalog)}", StockCraft.moonshot(catalog).validate(catalog).isEmpty())
        val total = stats.stages.sumOf { it.deltaVVacuum }
        assertTrue("only $total m/s", total > 5_400.0)
        assertTrue("too heavy to lift: ${stats.stages.first().twrSeaLevel}", stats.stages.first().twrSeaLevel > 1.4)
    }

    /** Flies [craft]'s next planned burn with the autopilot, to the end. */
    private fun autoBurn(world: World, craft: Vessel, name: String) {
        world.apply(Command.SetAutopilot(craft.id.raw, autoBurn = true, autoLand = false))
        // However long until it starts, plus ten minutes for the burn.
        val limit = (craft.plannedBurns.first().time - world.time).coerceAtLeast(0.0) + 600.0
        var t = 0.0
        while (craft.plannedBurns.isNotEmpty() && t < limit) {
            world.step(dt); t += dt
        }
        assertTrue("the $name burn never finished: ${craft.control.autopilotNote}, ${world.burnRemaining(craft)} m/s left", craft.plannedBurns.isEmpty())
    }

    @Test
    fun `the Moonshot flies to Luna and lands there`() {
        val system = SolarSystem.defaultSystem()
        val terra = system.body("terra")
        val luna = system.body("luna")
        val pad = SolarSystem.surfaceDirection(SolarSystem.PAD_LATITUDE, SolarSystem.PAD_LONGITUDE)
        val window = assertNotNull(LaunchWindows.next(terra, pad, luna, 0.0)).let { LaunchWindows.next(terra, pad, luna, 0.0)!! }

        // Up, with the Shroud thrown open once it's out of the air.
        val ascent = AscentScenario(
            // It leaves the pad at 2.5 times its weight, so it pitches over sooner.
            turnEndAltitude = 22_000.0,
            design = { StockCraft.moonshot(it) },
            launchAt = window,
            onTick = { w, v ->
                if (v.currentStage == 2 && w.attractorFor(v).altitudeOf(v.body.position) > 60_000.0) w.stage(v)
            },
        ).fly()
        if (!ascent.reachedOrbit) println(ascent.log.joinToString("\n"))
        assertTrue("never made orbit: ${ascent.failure}", ascent.reachedOrbit)
        val world = ascent.world!!
        val craft = ascent.vessel!!
        assertTrue("the Shroud never opened", craft.defs.indices.any { craft.defs[it].id == "fairing-base" && craft.activated[it] })
        assertFalse("the lander still rides inside a closed Shroud", craft.enclosed().any { it })
        assertTrue("the lander went with the ascent", craft.currentStage == 3)
        // Hands off the stick, like a player's are when the autopilot flies.
        craft.control.pitch = 0.0; craft.control.yaw = 0.0; craft.control.roll = 0.0

        // A transfer: the Hohmann speed, at whichever point of the orbit passes Luna at a height
        // worth braking at.
        val orbit = world.orbitOf(craft)
        val r1 = orbit.position.length
        val a = 0.5 * (r1 + luna.orbit!!.semiMajorAxis)
        val boost = sqrt(terra.gravitationalParameter * (2.0 / r1 - 1.0 / a)) - sqrt(terra.gravitationalParameter / r1)
        var plan: PlannedBurn? = null
        var t = world.time + 120.0
        search@ while (t < world.time + 120.0 + orbit.period) {
            for (extra in listOf(0.0, 10.0, -10.0, 20.0, -20.0, 35.0)) {
                val burn = PlannedBurn(t, prograde = boost + extra)
                val after = Burns.after(burn, orbit)
                val path = Trajectory.predict(system, "terra", after.position, after.velocity, t)
                val there = path.about("luna") ?: continue
                val height = there.orbit.periapsis - luna.radius
                if (height in 30_000.0..400_000.0) { plan = burn; break@search }
            }
            t += 15.0
        }
        assertNotNull("no transfer to Luna found from this orbit", plan)
        world.apply(Command.PlanBurns(craft.id.raw, listOf(plan!!)))
        autoBurn(world, craft, "transfer")

        // A flown burn is never exact, so make the smallest correction that meets Luna if needed.
        fun meetsWell(o: com.rm.apogee.core.orbit.Orbit, at: Double): Boolean {
            val there = Trajectory.predict(system, "terra", o.position, o.velocity, at).about("luna") ?: return false
            return there.orbit.periapsis - luna.radius in 30_000.0..400_000.0
        }
        val coasting = world.orbitOf(craft)
        if (!meetsWell(coasting, world.time)) {
            val at = world.time + 600.0
            var best: PlannedBurn? = null
            for (p in -30..30 step 2) for (r in -30..30 step 5) for (n in -30..30 step 5) {
                val burn = PlannedBurn(at, prograde = p.toDouble(), normal = n.toDouble(), radial = r.toDouble())
                if (best != null && burn.deltaV >= best.deltaV) continue
                if (meetsWell(Burns.after(burn, coasting), at)) best = burn
            }
            assertNotNull("no correction finds Luna", best)
            world.apply(Command.PlanBurns(craft.id.raw, listOf(best!!)))
            autoBurn(world, craft, "correction")
        }

        // There, on rails.
        var coasted = 0.0
        var moved = 0.0
        while (craft.referenceBodyId == "terra" && coasted < 200_000.0) { moved += world.advanceOnRails(50.0); coasted += 50.0 }
        assertEquals(
            "never reached Luna: coasted $moved s, warp ${world.maxWarp()}, throttle ${craft.control.throttle}, " +
                "meets ${meetsWell(world.orbitOf(craft), world.time)}, orbit ${world.orbitOf(craft)}",
            "luna", craft.referenceBodyId,
        )

        // Braked into a low orbit at the low point.
        run {
            val o = world.orbitOf(craft)
            world.advanceOnRails(o.timeToPeriapsis - 200.0)
            val at = world.orbitOf(craft).let { it.propagate(it.timeToPeriapsis) }
            val capture = at.velocity.length - sqrt(luna.gravitationalParameter / at.position.length)
            world.apply(Command.PlanBurns(craft.id.raw, listOf(PlannedBurn(world.time + 200.0, prograde = -capture))))
            autoBurn(world, craft, "capture")
            assertTrue("not caught by Luna", world.orbitOf(craft).isBound)
        }
        // The upper stage let go, if the capture didn't burn it dry already, and the lander lit.
        if (craft.defs.any { it.id == "tank-broad4" }) world.stage(craft)
        assertTrue("the lander was not let go", craft.defs.none { it.id == "tank-broad4" })

        // Down: a burn to bring the low point under the ground, then the auto-land.
        run {
            val o = world.orbitOf(craft)
            val r = o.position.length
            val speed = sqrt(luna.gravitationalParameter / r)
            // The low point 20 km under the datum: an ellipse from here to there.
            val low = luna.radius - 20_000.0
            val want = sqrt(luna.gravitationalParameter * (2.0 / r - 2.0 / (r + low)))
            world.apply(Command.PlanBurns(craft.id.raw, listOf(PlannedBurn(world.time + 120.0, prograde = want - speed))))
            autoBurn(world, craft, "deorbit")
        }
        // Fall on rails to where there's still room to brake from orbital speed, then fly the rest.
        val lunaBody = world.attractorFor(craft)
        fun high(): Double = lunaBody.heightAboveTerrain(craft.body.position,
            lunaBody.toBodyFixed(craft.body.position, lunaBody.rotationAt(world.time)).normalizeInPlace())
        while (high() > 25_000.0 && world.advanceOnRails(20.0) >= 20.0) Unit
        world.apply(Command.SetAutopilot(craft.id.raw, autoBurn = false, autoLand = true))
        var down = 0.0
        while (craft.control.autoLand && down < 1_500.0) {
            world.step(dt); down += dt
        }
        assertEquals("did not land: ${craft.control.autopilotNote}", "Landed", craft.control.autopilotNote)
        assertFalse("broke landing", craft.broken.any { it })
        val tilt = Math.toDegrees(kotlin.math.acos((craft.forward() dot craft.body.position.copy().normalizeInPlace()).coerceIn(-1.0, 1.0)))
        assertTrue("landed tilted $tilt degrees", tilt < 8.0)
    }
}
