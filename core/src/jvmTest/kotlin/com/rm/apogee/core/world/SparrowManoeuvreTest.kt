package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Planes flown hard at speed with SAS on: the hold doesn't chatter its surfaces, and full stick
 * turns the plane without pulling its wings off.
 */
class SparrowManoeuvreTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** [design] at [height] over the Cape, flying east at [speed], level, SAS on, full throttle. */
    private fun flying(world: World, design: CraftDesign, height: Double, speed: Double): Vessel {
        val terra = world.system.body("terra")!!
        val up = com.rm.apogee.core.orbit.SolarSystem.capeDirection(-5_000.0, 0.0)
        val position = Vec3().setTo(up).mulInPlace(terra.radius + height)
        val surface = terra.surfaceVelocityAt(position, Vec3())
        val east = surface.copy().normalizeInPlace()
        val right = east.copy().crossInPlace(up).normalizeInPlace()
        val rotation = quatFromTo(Vec3(0.0, 0.0, 1.0), up)
        val nose = rotation.rotate(Vec3(0.0, 1.0, 0.0))
        rotation.setTo(quatFromTo(nose, east) * rotation)
        rotation.setTo(Quat.fromAxisAngle(right, Math.toRadians(1.0)) * rotation)
        val velocity = surface.copy().addScaledInPlace(east, speed)
        val plane = world.spawnAt(design, "terra", position, velocity, rotation)
        world.assignOwner(plane, "p1")
        world.seatCrew(plane)
        plane.activated.fill(true)
        plane.control.gear = false
        for (i in plane.defs.indices) if (plane.defs[i].fold != null) plane.setLegDeploy(i, 0.0)
        world.apply(Command.SetSas(plane.id.raw, true))
        world.apply(Command.SetThrottle(plane.id.raw, 1.0))
        return plane
    }

    private fun surfaces(v: Vessel) = v.defs.indices.filter { v.defs[it].module<AeroSurface>() != null }

    /** The worst wing or fin load over [seconds] with the stick at [pitch], [yaw], [roll]. */
    private fun worstLoad(world: World, v: Vessel, seconds: Double, pitch: Double, yaw: Double, roll: Double): Double {
        world.apply(Command.SetAttitude(v.id.raw, pitch, yaw, roll))
        var worst = 0.0
        var t = 0.0
        while (t < seconds) {
            world.step(dt); t += dt
            for (i in surfaces(v)) worst = maxOf(worst, v.jointLoad[i].toDouble())
        }
        world.apply(Command.SetAttitude(v.id.raw, 0.0, 0.0, 0.0))
        return worst
    }

    /** Ticks in [seconds] where the roll command turned back on itself, chattering. */
    private fun chatter(world: World, v: Vessel, seconds: Double): Int {
        var count = 0
        var last = v.control.commandRoll
        var lastStep = 0.0
        var t = 0.0
        while (t < seconds) {
            world.step(dt); t += dt
            val now = v.control.commandRoll
            val step = now - last
            if (kotlin.math.abs(step) > 0.01 && step * lastStep < 0.0) count++
            if (kotlin.math.abs(step) > 0.01) lastStep = step
            last = now
        }
        return count
    }

    @Test
    fun `SAS holds the Sparrow at 158 m per s without chattering its surfaces`() {
        val world = World.default(catalog)
        val plane = flying(world, StockCraft.sparrow(catalog), 600.0, 158.0)
        repeat(60) { world.step(dt) }
        val count = chatter(world, plane, 4.0)
        assertTrue("roll command turned back $count times in 4 s", count < 8)
    }

    @Test
    fun `full stick at 158 m per s keeps the Sparrow's wings on`() {
        for ((name, stick) in listOf(
            "pull" to Triple(1.0, 0.0, 0.0),
            "push" to Triple(-1.0, 0.0, 0.0),
            "yaw" to Triple(0.0, 1.0, 0.0),
            "pull and yaw" to Triple(1.0, -1.0, 0.0),
            "roll" to Triple(0.0, 0.0, 1.0),
        )) {
            val world = World.default(catalog)
            val plane = flying(world, StockCraft.sparrow(catalog), 600.0, 158.0)
            val parts = plane.design.parts.size
            repeat(60) { world.step(dt) }
            val worst = worstLoad(world, plane, 3.0, stick.first, stick.second, stick.third)
            assertEquals("$name: parts left", parts, plane.design.parts.size)
            assertTrue("$name: worst surface load $worst", worst < 0.9)
        }
    }

    @Test
    fun `full stick at speed keeps the Petrel's wings on`() {
        for ((name, stick) in listOf("pull" to Triple(1.0, 0.0, 0.0), "yaw" to Triple(0.0, 1.0, 0.0))) {
            val world = World.default(catalog)
            val plane = flying(world, StockCraft.petrel(catalog), 600.0, 90.0)
            val parts = plane.design.parts.size
            repeat(60) { world.step(dt) }
            val worst = worstLoad(world, plane, 3.0, stick.first, stick.second, stick.third)
            assertEquals("$name: parts left", parts, plane.design.parts.size)
            assertTrue("$name: worst surface load $worst", worst < 0.9)
        }
    }

    @Test
    fun `the Sparrow still turns hard at speed`() {
        val world = World.default(catalog)
        val plane = flying(world, StockCraft.sparrow(catalog), 600.0, 158.0)
        repeat(60) { world.step(dt) }
        val start = plane.forward()
        world.apply(Command.SetAttitude(plane.id.raw, 1.0, 0.0, 0.0))
        repeat(60) { world.step(dt) }
        val turned = Math.toDegrees(kotlin.math.acos((start dot plane.forward()).coerceIn(-1.0, 1.0)))
        // A few g at this speed is upwards of ten degrees a second.
        assertTrue("turned $turned degrees in a second", turned > 10.0)
    }
}
