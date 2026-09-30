package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
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
        // Too fast and too low to get down straight in, it goes round once.
        val hardest = land(world, plane, 420.0)
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

    /** Whether anything stands within [radius] metres of where [vessel] is now: trees, rocks or craft. */
    private fun clearUnder(world: World, vessel: Vessel, radius: Double): Boolean {
        val terra = world.attractorFor(vessel)
        val rotation = terra.rotationAt(world.time)
        val here = terra.toBodyFixed(vessel.body.position, rotation, Vec3()).normalizeInPlace()
        val ground = ClearGround()
        ground.gather(terra, here, radius, world.felledScatter, world.vessels, vessel, rotation)
        return ground.isClear(terra, here, radius, floats = false)
    }

    @Test
    fun `let down over a forest, a Hummingbird moves over to a clearing and lands there, clear of the trees`() {
        val world = World.default(catalog)
        val heli = over(world, StockCraft.hummingbird(catalog), 1_000.0, -1_000.0, 60.0)
        Aloft.spinUp(heli, 0.6)
        world.apply(Command.SetThrottle(heli.id.raw, 0.6))
        assertTrue("the forest's clear there already", !clearUnder(world, heli, heli.contactRadius))
        world.apply(Command.SetAutopilot(heli.id.raw, autoBurn = false, autoLand = true))
        var hard = 0.0
        var fastestTurn = 0.0
        var t = 0.0
        while (t < 300.0 && heli.control.autoLand) {
            world.step(Aloft.DT); t += Aloft.DT
            if (heli.touchingGround) hard = maxOf(hard, -Aloft.climb(world, heli))
            else fastestTurn = maxOf(fastestTurn, Math.toDegrees(kotlin.math.abs(heli.body.angularVelocity dot heli.body.position.normalized())))
        }
        assertTrue("never down: ${heli.control.autopilotNote}", heli.control.autopilotNote == "Landed")
        assertTrue("came down hard, at $hard m/s", hard < 1.5)
        assertTrue("it broke: ${heli.broken.count { it }} parts", heli.broken.none { it })
        assertTrue("came down among the trees", clearUnder(world, heli, heli.contactRadius))
        assertTrue("came down turning at $fastestTurn degrees a second", fastestTurn < 10.0)
    }

    @Test
    fun `a Hummingbird left on uneven ground stays where it's left`() {
        val world = World.default(catalog)
        val spot = SolarSystem.capeDirection(1_200.0, -1_000.0)
        val heli = world.spawnOnSurface(StockCraft.hummingbird(catalog), LaunchSite("s", "S", "terra", SolarSystem.latitudeOf(spot), SolarSystem.longitudeOf(spot)))
        val terra = world.attractorFor(heli)
        repeat(30) { world.step(Aloft.DT) }
        val start = terra.toBodyFixed(heli.body.position, terra.rotationAt(world.time), Vec3())
        repeat((60.0 / Aloft.DT).toInt()) { world.step(Aloft.DT) }
        val moved = terra.toBodyFixed(heli.body.position, terra.rotationAt(world.time), Vec3()).distanceTo(start)
        assertTrue("walked $moved m on its skids", moved < 0.5)
    }

    @Test
    fun `a Sparrow in a strong wind lands on the runway into it`() {
        val world = World.default(catalog)
        world.weatherConfig = com.rm.apogee.core.weather.WeatherConfig(intensity = com.rm.apogee.core.weather.WeatherIntensity.WILD)
        val plane = sparrow(world, -4_000.0, -900.0, 600.0, 90.0)
        world.apply(Command.SetAutopilot(plane.id.raw, autoBurn = false, autoLand = true))
        world.step(Aloft.DT)
        val terra = world.attractorFor(plane)
        val along = terra.rotationAt(world.time).rotate(plane.landStripAlong, Vec3())
        val wind = terra.rotationAt(world.time).rotate(plane.air.wind, Vec3())
        val hard = land(world, plane, 500.0)
        assertTrue("never down: ${plane.control.autopilotNote}", plane.control.autopilotNote == "Landed")
        assertTrue("came down hard, at $hard m/s", hard < 2.5)
        assertTrue("it broke", plane.broken.none { it })
        val rot = terra.rotationAt(world.time)
        val off = plane.body.position.copy().subInPlace(rot.rotate(plane.landStripAt, Vec3()))
        val right = rot.rotate(plane.landStripAlong, Vec3()).crossInPlace(plane.body.position.normalized())
        assertTrue("stopped off the runway, ${off dot rot.rotate(plane.landStripAlong, Vec3())} along, ${off dot right} across, wind ${wind dot along} along, ${plane.control.autopilotNote}", onRunway(world, plane))
        if (kotlin.math.abs(wind dot along) > World.RUNWAY_WIND) assertTrue("landed with ${wind dot along} m/s behind it", (wind dot along) < 0.0)
    }

    @Test
    fun `a Quad let down over a forest lands in a clearing too`() {
        val world = World.default(catalog)
        val quad = over(world, StockCraft.quad(catalog), 1_000.0, -1_000.0, 50.0)
        Aloft.spinUp(quad, 0.5)
        world.apply(Command.SetThrottle(quad.id.raw, 0.5))
        val hard = land(world, quad, 300.0)
        assertTrue("never down: ${quad.control.autopilotNote}", quad.control.autopilotNote == "Landed")
        assertTrue("came down hard, at $hard m/s", hard < 1.5)
        assertTrue("it broke", quad.broken.none { it })
        assertTrue("came down among the trees", clearUnder(world, quad, quad.contactRadius))
    }

    /** Whether [vessel] is on the runway's paving. */
    private fun onRunway(world: World, vessel: Vessel): Boolean {
        val terra = world.attractorFor(vessel)
        val d = terra.toBodyFixed(vessel.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
        val terrain = terra.terrain!!
        return terrain.material(d, terrain.elevation(d), 0.0) == com.rm.apogee.core.terrain.SurfaceMaterial.ASPHALT
    }

    /** A Sparrow flying [east] m/s east (west if negative) [height] metres over the Cape's ground at [x], [y]. */
    private fun sparrow(world: World, x: Double, y: Double, height: Double, speed: Double): Vessel {
        val plane = over(world, StockCraft.sparrow(catalog), x, y, height, speed = kotlin.math.abs(speed))
        if (speed < 0.0) {
            // Turned round to fly west.
            val up = plane.body.position.normalized()
            plane.body.orientation.setTo(Quat.fromAxisAngle(up, Math.PI) * plane.body.orientation).normalizeInPlace()
            val ground = world.attractorFor(plane).surfaceVelocityAt(plane.body.position, Vec3())
            plane.body.linearVelocity.subInPlace(ground).mulInPlace(-1.0).addInPlace(ground)
        }
        world.apply(Command.Stage(plane.id.raw))
        world.apply(Command.SetThrottle(plane.id.raw, 0.6))
        repeat(30) { world.step(Aloft.DT) }
        return plane
    }

    @Test
    fun `a Sparrow four kilometres out lines up with the runway, lands on it, and stops there`() {
        val world = World.default(catalog)
        val plane = sparrow(world, -4_000.0, -900.0, 600.0, 90.0)
        val hard = land(world, plane, 400.0)
        assertTrue("never down: ${plane.control.autopilotNote}", plane.control.autopilotNote == "Landed")
        assertTrue("came down hard, at $hard m/s", hard < 2.5)
        assertTrue("it broke", plane.broken.none { it })
        assertTrue("stopped off the runway", onRunway(world, plane))
    }

    @Test
    fun `a Sparrow past the runway and heading away turns back and lands on it`() {
        val world = World.default(catalog)
        val plane = sparrow(world, 4_500.0, -400.0, 500.0, 80.0)
        val hard = land(world, plane, 500.0)
        assertTrue("never down: ${plane.control.autopilotNote}", plane.control.autopilotNote == "Landed")
        assertTrue("came down hard, at $hard m/s", hard < 2.5)
        assertTrue("it broke", plane.broken.none { it })
        assertTrue("stopped off the runway", onRunway(world, plane))
    }

    @Test
    fun `a Sparrow over forest far from any runway finds a clear strip and lands on it without hitting anything`() {
        val world = World.default(catalog)
        val plane = sparrow(world, 20_000.0, 0.0, 400.0, 80.0)
        world.apply(Command.SetAutopilot(plane.id.raw, autoBurn = false, autoLand = true))
        world.step(Aloft.DT)
        assertEquals("Landing on clear ground", plane.control.autopilotNote)
        val hard = land(world, plane, 500.0)
        assertTrue("never down: ${plane.control.autopilotNote}", plane.control.autopilotNote == "Landed")
        assertTrue("came down hard, at $hard m/s", hard < 3.0)
        assertTrue("it broke: ${plane.broken.count { it }} parts", plane.broken.none { it })
    }
}
