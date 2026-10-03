package com.rm.apogee.core.world

import com.rm.apogee.core.career.Program
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Altitude and heading hold: the Sparrow let go at cruise holds its height and its heading. */
class CruiseTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** The Sparrow at [height] over the Cape, flying east at [speed], level, SAS on, under power. */
    private fun cruising(world: World, height: Double = 1_500.0, speed: Double = 110.0): Vessel {
        val terra = world.system.body("terra")!!
        val up = com.rm.apogee.core.orbit.SolarSystem.capeDirection(-5_000.0, 0.0)
        val position = Vec3().setTo(up).mulInPlace(terra.radius + height)
        val surface = terra.surfaceVelocityAt(position, Vec3())
        val east = surface.copy().normalizeInPlace()
        val right = east.copy().crossInPlace(up).normalizeInPlace()
        val rotation = quatFromTo(Vec3(0.0, 0.0, 1.0), up)
        val nose = rotation.rotate(Vec3(0.0, 1.0, 0.0))
        rotation.setTo(quatFromTo(nose, east) * rotation)
        rotation.setTo(Quat.fromAxisAngle(right, Math.toRadians(3.0)) * rotation)
        val velocity = surface.copy().addScaledInPlace(east, speed)
        val plane = world.spawnAt(StockCraft.sparrow(catalog), "terra", position, velocity, rotation)
        world.assignOwner(plane, "p1")
        world.seatCrew(plane)
        plane.activated.fill(true)
        // Cruising, with its gear up.
        plane.control.gear = false
        for (i in plane.defs.indices) if (plane.defs[i].fold != null) plane.setLegDeploy(i, 0.0)
        world.apply(Command.SetSas(plane.id.raw, true))
        world.apply(Command.SetThrottle(plane.id.raw, 0.7))
        return plane
    }

    private fun height(world: World, v: Vessel) = world.attractorFor(v).altitudeOf(v.body.position)

    private fun track(world: World, v: Vessel): Double = Cruise().track(v, world.attractorFor(v))

    private fun off(a: Double, b: Double): Double {
        var d = a - b
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return kotlin.math.abs(d)
    }

    @Test
    fun `let go at cruise, it holds its height and heading for five minutes`() {
        val world = World.default(catalog)
        val plane = cruising(world)
        repeat(300) { world.step(dt) }
        world.apply(Command.SetCruise(plane.id.raw, true))
        assertTrue(plane.control.autopilotNote, plane.control.cruise)
        val height = plane.control.cruiseHeight
        val heading = plane.control.cruiseHeading
        var worstHeight = 0.0
        var worstHeading = 0.0
        var t = 0.0
        while (t < 300.0) {
            world.step(dt); t += dt
            if (t > 30.0) {
                worstHeight = maxOf(worstHeight, kotlin.math.abs(height(world, plane) - height))
                worstHeading = maxOf(worstHeading, off(track(world, plane), heading))
            }
        }
        assertTrue("held within $worstHeight m", worstHeight < 30.0)
        assertTrue("held within $worstHeading degrees", worstHeading < 3.0)
        assertTrue("still flying", plane.control.cruise && !plane.touchingGround)
    }

    @Test
    fun `steered by hand and let go, it holds the new heading`() {
        val world = World.default(catalog)
        val plane = cruising(world)
        repeat(300) { world.step(dt) }
        world.apply(Command.SetCruise(plane.id.raw, true))
        repeat(1_200) { world.step(dt) }
        val before = plane.control.cruiseHeading
        // A short flick of left stick into a bank, then pulled round the turn for a few seconds.
        // Held longer, the stick rolls it right round.
        world.apply(Command.SetAttitude(plane.id.raw, 0.0, 0.0, -0.6))
        repeat(25) { world.step(dt) }
        world.apply(Command.SetAttitude(plane.id.raw, 0.15, 0.0, 0.0))
        repeat(240) { world.step(dt) }
        world.apply(Command.SetAttitude(plane.id.raw, 0.0, 0.0, 0.0))
        repeat(60) { world.step(dt) }
        val after = plane.control.cruiseHeading
        assertTrue("turned from $before to $after", off(after, before) > 5.0)
        repeat(1_800) { world.step(dt) }
        assertTrue("holding $after, flying ${track(world, plane)}", off(track(world, plane), after) < 4.0)
    }

    @Test
    fun `a career needs Cruise Control first`() {
        val world = World.default(catalog)
        world.program = Program()
        val plane = cruising(world)
        repeat(60) { world.step(dt) }
        world.apply(Command.SetCruise(plane.id.raw, true))
        assertFalse(plane.control.cruise)
    }
}
