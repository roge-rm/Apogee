package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-land for things that fly on the air: a plane glides in and flares, and a helicopter and an
 * airship come straight down, each onto the airfield's runway, and each stops there in one piece.
 */
class AirLandingTest {
    private val catalog = StockParts.catalog

    /** [design] [height] metres over the Cape's ground at [east], [north], level, crewed. */
    private fun over(world: World, design: CraftDesign, east: Double, north: Double, height: Double, speed: Double = 0.0): Vessel {
        val terra = world.system.body("terra")
        val up = SolarSystem.capeDirection(east, north)
        val ground = (terra.terrain?.elevation(up) ?: 0.0).coerceAtLeast(0.0)
        val rotation = terra.rotationAt(world.time)
        val position = rotation.rotate(up, Vec3()).mulInPlace(terra.radius + ground + height)
        val upNow = position.normalized()
        // East along the ground, the way the planet turns.
        val eastNow = terra.surfaceVelocityAt(position, Vec3()).normalizeInPlace()
        val velocity = terra.surfaceVelocityAt(position, Vec3()).addScaledInPlace(eastNow, speed)
        val q1 = quatFromTo(design.orientation.up, upNow)
        val f1 = q1.rotate(design.orientation.forward, Vec3())
        val angle = kotlin.math.atan2(f1.cross(eastNow) dot upNow, f1 dot eastNow)
        val vessel = world.spawnAt(design, "terra", position, velocity, Quat.fromAxisAngle(upNow, angle) * q1)
        world.assignOwner(vessel, "p1")
        world.seatCrew(vessel)
        return vessel
    }

    private fun land(world: World, vessel: Vessel, seconds: Double): Double {
        world.apply(Command.SetAutopilot(vessel.id.raw, autoBurn = false, autoLand = true))
        var t = 0.0
        var hardest = 0.0
        while (t < seconds && vessel.control.autoLand) {
            world.step(Aloft.DT); t += Aloft.DT
            if (vessel.touchingGround) hardest = maxOf(hardest, -Aloft.climb(world, vessel))
        }
        return hardest
    }

    @Test
    fun `a Sparrow on a straight-in approach glides down, flares and stops on its wheels`() {
        val world = World.default(catalog)
        val plane = over(world, StockCraft.sparrow(catalog), -2_300.0, -400.0, 120.0, speed = 118.0)
        world.apply(Command.Stage(plane.id.raw))
        world.apply(Command.SetThrottle(plane.id.raw, 0.4))
        repeat(30) { world.step(Aloft.DT) }
        val hardest = land(world, plane, 180.0)
        assertTrue("came down hard, at $hardest m/s", hardest < 2.5)
        assertTrue("never stopped: ${plane.control.autopilotNote}, ${Aloft.climb(world, plane)} m/s climb, touching ${plane.touchingGround}", plane.control.autopilotNote == "Landed")
        assertTrue("it broke: ${plane.broken.count { it }} parts", plane.broken.none { it })
        assertTrue("not on its wheels: tilted ${Aloft.tilt(plane)} degrees", Aloft.tilt(plane) < 20.0)
    }

    @Test
    fun `a Hummingbird comes straight down and shuts down on the ground`() {
        val world = World.default(catalog)
        val heli = over(world, StockCraft.hummingbird(catalog), 1_500.0, -400.0, 150.0)
        Aloft.spinUp(heli, 0.6)
        world.apply(Command.SetThrottle(heli.id.raw, 0.6))
        val spot = Aloft.fixed(world, heli)
        val heliHard = land(world, heli, 240.0)
        assertTrue("never down: ${heli.control.autopilotNote}, ${Aloft.climb(world, heli)} m/s", heli.control.autopilotNote == "Landed")
        assertTrue("came down hard, at $heliHard m/s", heliHard < 1.5)
        assertTrue("it broke: ${heli.broken.count { it }} parts", heli.broken.none { it })
        // Across the ground, not counting the height it came down.
        val here = Aloft.fixed(world, heli).normalizeInPlace()
        val across = here.distanceTo(spot.copy().normalizeInPlace()) * world.attractorFor(heli).radius
        assertTrue("wandered $across m", across < 60.0)
        assertTrue("not upright: ${Aloft.tilt(heli)} degrees", Aloft.tilt(heli) < 15.0)
        assertTrue("still pulling: ${heli.control.throttle}", heli.control.throttle == 0.0)
    }

    @Test
    fun `a Quad drone comes straight down level`() {
        val world = World.default(catalog)
        val quad = over(world, StockCraft.quad(catalog), 1_500.0, -400.0, 80.0)
        Aloft.spinUp(quad, 0.5)
        world.apply(Command.SetThrottle(quad.id.raw, 0.5))
        val hardest = land(world, quad, 180.0)
        assertTrue("never down: ${quad.control.autopilotNote}, ${Aloft.climb(world, quad)} m/s", quad.control.autopilotNote == "Landed")
        assertTrue("came down hard, at $hardest m/s", hardest < 1.5)
        assertTrue("it broke: ${quad.broken.count { it }} parts", quad.broken.none { it })
        assertTrue("not level: ${Aloft.tilt(quad)} degrees", Aloft.tilt(quad) < 10.0)
    }

    @Test
    fun `the Skylark balloon comes down on its ballonets`() {
        val world = World.default(catalog)
        val balloon = over(world, StockCraft.skylark(catalog), 1_500.0, -400.0, 150.0)
        val hardest = land(world, balloon, 900.0)
        assertTrue("never down: ${balloon.control.autopilotNote}, ${Aloft.climb(world, balloon)} m/s, ballonet ${balloon.ballonet}", balloon.control.autopilotNote == "Landed")
        assertTrue("came down hard, at $hardest m/s", hardest < 1.5)
        assertTrue("it broke: ${balloon.broken.count { it }} parts", balloon.broken.none { it })
    }

    @Test
    fun `the Zeppelin comes down on its ballonets and stays down`() {
        val world = World.default(catalog)
        val ship = over(world, StockCraft.zeppelin(catalog), 1_500.0, -400.0, 200.0)
        val shipHard = land(world, ship, 900.0)
        assertTrue("came down hard, at $shipHard m/s", shipHard < 1.0)
        assertTrue("never down: ${ship.control.autopilotNote}, ${Aloft.climb(world, ship)} m/s, ballonet ${ship.ballonet}", ship.control.autopilotNote == "Landed")
        assertTrue("it broke: ${ship.broken.count { it }} parts", ship.broken.none { it })
        Aloft.run(world, 30.0)
        assertTrue("floated off again", ship.touchingGround)
    }
}
