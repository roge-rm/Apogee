package com.rm.apogee.core.world

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
 * Flaps: with them down a wing lifts more at the same speed, so a plane can fly slower, and it pays
 * for that in drag.
 */
class FlapsTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** The Sparrow a kilometre over the Cape at [speed], its nose [noseUp] degrees above its track. */
    private fun flying(speed: Double = 60.0, noseUp: Double = 4.0): Pair<World, Vessel> {
        val world = World.default(catalog)
        val terra = world.system.body("terra")!!
        val up = com.rm.apogee.core.orbit.SolarSystem.capeDirection(260.0, -400.0)
        val position = Vec3().setTo(up).mulInPlace(terra.surfaceRadiusInBodyFrame(up) + 1_000.0)
        val surface = terra.surfaceVelocityAt(position, Vec3())
        val east = surface.copy().normalizeInPlace()
        val right = east.copy().crossInPlace(up).normalizeInPlace()
        // Sky side up, nose east, then pitched up about the wing.
        val rotation = quatFromTo(Vec3(0.0, 0.0, 1.0), up)
        val nose = rotation.rotate(Vec3(0.0, 1.0, 0.0))
        rotation.setTo(quatFromTo(nose, east) * rotation)
        rotation.setTo(Quat.fromAxisAngle(right, Math.toRadians(noseUp)) * rotation)
        val velocity = surface.copy().addScaledInPlace(east, speed)
        return world to world.spawnAt(StockCraft.sparrow(catalog), "terra", position, velocity, rotation)
    }

    /** Puts every flapped wing's flaps all the way down (or up) at once. */
    private fun setFlaps(vessel: Vessel, down: Boolean) {
        vessel.control.flaps = down
        vessel.fitPose()
        for (i in vessel.defs.indices) {
            if ((vessel.defs[i].module<AeroSurface>()?.flapLift ?: 0.0) > 0.0) vessel.flapPosition[i] = if (down) 1.0 else 0.0
        }
    }

    /** Vertical speed gained over one tick, and speed lost along the track, in m/s. */
    private fun oneTick(down: Boolean): Pair<Double, Double> {
        val (world, plane) = flying()
        setFlaps(plane, down)
        val up = plane.body.position.copy().normalizeInPlace()
        val surface = world.attractorFor(plane).surfaceVelocityAt(plane.body.position, Vec3())
        val before = plane.body.linearVelocity.copy().subInPlace(surface)
        val track = before.copy().normalizeInPlace()
        world.step(dt)
        val after = plane.body.linearVelocity.copy().subInPlace(world.attractorFor(plane).surfaceVelocityAt(plane.body.position, Vec3()))
        return ((after dot up) - (before dot up)) to ((before dot track) - (after dot track))
    }

    @Test
    fun `the Sparrow's wings have flaps`() {
        val design = StockCraft.sparrow(catalog)
        assertTrue(design.parts.any { (catalog[it.partId]?.module<AeroSurface>()?.flapLift ?: 0.0) > 0.0 })
    }

    @Test
    fun `flaps down lift more at the same speed, and cost drag`() {
        val (climbUp, lossUp) = oneTick(down = false)
        val (climbDown, lossDown) = oneTick(down = true)
        assertTrue("more lift with flaps: $climbDown vs $climbUp", climbDown > climbUp + 0.01)
        assertTrue("more drag with flaps: $lossDown vs $lossUp", lossDown > lossUp)
    }

    @Test
    fun `flaps run out over a couple of seconds, and back in`() {
        val (world, plane) = flying()
        plane.control.flaps = true
        val flapped = plane.defs.indices.first { (plane.defs[it].module<AeroSurface>()?.flapLift ?: 0.0) > 0.0 }
        repeat(30) { world.step(dt) }
        val halfway = plane.flapPosition[flapped]
        assertTrue("part way after half a second: $halfway", halfway > 0.1 && halfway < 0.5)
        repeat(150) { world.step(dt) }
        assertEquals(1.0, plane.flapPosition[flapped], 1e-9)
        plane.control.flaps = false
        repeat(150) { world.step(dt) }
        assertEquals(0.0, plane.flapPosition[flapped], 1e-9)
    }

    @Test
    fun `other players see the flaps where the pilot has them`() {
        val (_, plane) = flying()
        setFlaps(plane, down = true)
        val values = VesselPose.Values()
        assertTrue(VesselPose.decode(plane.defs, VesselPose.encode(plane), values))
        val flapped = plane.defs.indices.first { (plane.defs[it].module<AeroSurface>()?.flapLift ?: 0.0) > 0.0 }
        assertEquals(1.0, values.flap[flapped], VesselPose.RESOLUTION)
    }

    @Test
    fun `staging a rocket leaves its flap and sail poses the size of what's left`() {
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first { it.id == "cape" })
        world.apply(Command.Stage(rocket.id.raw))
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        val before = rocket.defs.size
        var t = 0.0
        while (rocket.defs.size == before && t < 240.0) {
            world.step(dt); t += dt
            if (rocket.engineOutput.none { it > 0.0 } && t > 5.0) world.apply(Command.Stage(rocket.id.raw))
        }
        assertTrue("it never staged", rocket.defs.size < before)
        world.step(dt)
        assertEquals(rocket.defs.size, rocket.flapPosition.size)
        assertEquals(rocket.defs.size, rocket.sailAngle.size)
        assertEquals(rocket.defs.size, rocket.sailFill.size)
    }
}
