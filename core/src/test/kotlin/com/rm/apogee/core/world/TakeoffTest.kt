package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.part.Wheel
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An aeroplane leaving the ground under its own power.
 *
 * Flown the way a player would: nose level on the roll, full back stick from
 * sixty metres a second until the nose is at the climb attitude - which the
 * aircraft answers when it is fast enough to, about a hundred at this wing
 * loading - and then hands off, with SAS holding it there.
 *
 * Height is measured above the terrain underneath, not above the pad. The
 * first version of this measured from the pad and passed: the aircraft had
 * rolled off the end of the level ground into rising country and been shoved
 * up the hillside by the contact solver with its wheels on the grass.
 */
class TakeoffTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun launch(): Pair<World, Vessel> {
        val world = World.default(catalog)
        return world to world.spawnOnSurface(StockCraft.aeroplane(catalog), World.launchSites.first())
    }

    /** How far [point] (a world position) is above the terrain beneath it. */
    private fun aboveGround(world: World, vessel: Vessel, point: Vec3): Double {
        val attractor = world.attractorFor(vessel)
        val rotation = Quat()
        attractor.rotationAt(world.time, rotation)
        val bodyFixed = attractor.toBodyFixed(point, rotation, Vec3())
        return point.length - attractor.surfaceRadiusInBodyFrame(bodyFixed)
    }

    /** The lowest point of part [index] above the ground. */
    private fun partClearance(world: World, vessel: Vessel, index: Int): Double {
        val point = Vec3()
        return vessel.defs[index].contactPoints.indices.minOf {
            vessel.contactPointWorld(index, it, point)
            aboveGround(world, vessel, point)
        }
    }

    @Test
    fun `it stands on its wheels with its tail clear`() {
        val (world, plane) = launch()
        repeat(120) { world.step(dt) }
        for (i in plane.defs.indices) {
            if (plane.defs[i].module<Wheel>() != null) continue
            val clearance = partClearance(world, plane, i)
            assertTrue(
                "${plane.design.parts[i].partId} is resting on the ground ($clearance m)",
                clearance > 0.1,
            )
        }
    }

    @Test
    fun `it takes off from the runway and climbs`() {
        val (world, plane) = launch()
        val attractor = world.attractorFor(plane)
        val pilot = AttitudeController()
        world.stage(plane)
        plane.control.throttle = 1.0

        val up = Vec3()
        val east = Vec3()
        val surface = Vec3()
        val ground = Vec3()
        val desired = Vec3()
        var rotating = false
        var rotated = false
        plane.control.sasEnabled = true
        var rolled = 0.0
        var airborne = false
        var lowestAfterLiftoff = Double.MAX_VALUE

        while (world.time < 60.0) {
            up.setTo(plane.body.position).normalizeInPlace()
            attractor.surfaceVelocityAt(plane.body.position, surface)
            east.setTo(surface).addScaledInPlace(up, -(surface dot up)).normalizeInPlace()
            ground.setTo(plane.body.linearVelocity).subInPlace(surface)
            if (ground.length > ROTATE_SPEED) rotating = true

            if (!rotating) {
                // Nose level down the runway.
                desired.setTo(east)
                pilot.steer(plane, desired)
            } else if (!rotated) {
                // Full back stick until the nose is up, as a player would...
                plane.control.pitch = 1.0
                plane.control.yaw = 0.0
                plane.control.roll = 0.0
                if ((plane.forward() dot up) > kotlin.math.sin(Math.toRadians(CLIMB_DEGREES))) {
                    rotated = true
                }
            } else {
                // ...then let go and let SAS hold it there.
                plane.control.pitch = 0.0
            }
            world.step(dt)

            val height = aboveGround(world, plane, plane.body.position)
            if (!airborne) {
                rolled += ground.length * dt
                if (height > 20.0) airborne = true
            } else {
                lowestAfterLiftoff = minOf(lowestAfterLiftoff, height)
            }
        }

        val height = aboveGround(world, plane, plane.body.position)
        val climb = plane.body.linearVelocity.copy().subInPlace(surface) dot up
        assertTrue("never got off the ground", airborne)
        assertTrue("used ${rolled.toInt()} m of a 2500 m runway", rolled < 2_000.0)
        assertTrue("came back down to $lowestAfterLiftoff m after lifting off", lowestAfterLiftoff > 10.0)
        assertTrue("only $height m up after a minute", height > 150.0)
        assertTrue("not climbing at the end: $climb m/s", climb > 0.0)
    }

    private companion object {
        const val ROTATE_SPEED = 60.0
        const val CLIMB_DEGREES = 10.0
    }
}
