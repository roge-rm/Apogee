package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.asin

/**
 * A boat: a hull that floats, rights itself, goes where it is pointed and
 * turns when asked - none of it from a boat-specific system. Buoyancy and
 * water drag are forces on cells of each part's volume, and everything below
 * falls out of where those cells are.
 */
class BoatTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun afloat(settle: Double = 20.0): Pair<World, Vessel> {
        val world = World.default(catalog)
        val design = StockCraft.boat(catalog)
        val site = World.launchSiteFor(design, catalog)
        assertEquals("harbour", site.id)
        val boat = world.spawnOnSurface(design, site)
        repeat((settle / dt).toInt()) { world.step(dt) }
        return world to boat
    }

    private fun up(vessel: Vessel) = vessel.body.position.copy().normalizeInPlace()

    /** How far the deck (+Z) is from vertical, degrees. */
    private fun tilt(vessel: Vessel): Double {
        val deck = vessel.body.orientation.rotate(Vec3(0.0, 0.0, 1.0))
        return Math.toDegrees(kotlin.math.acos((deck dot up(vessel)).coerceIn(-1.0, 1.0)))
    }

    private fun groundVelocity(world: World, vessel: Vessel): Vec3 {
        val surface = world.attractorFor(vessel).surfaceVelocityAt(vessel.body.position, Vec3())
        return vessel.body.linearVelocity.copy().subInPlace(surface)
    }

    @Test
    fun `it floats, upright, at its waterline`() {
        val (world, boat) = afloat()
        val attractor = world.attractorFor(boat)
        val altitude = attractor.altitudeOf(boat.body.position)
        val v = groundVelocity(world, boat)

        assertTrue("sank or flew: centre at $altitude m", altitude > -1.0 && altitude < 1.0)
        assertTrue("still bobbing at ${v.length} m/s", v.length < 0.2)
        assertTrue("listing or trimmed ${tilt(boat)} degrees", tilt(boat) < 6.0)
        assertTrue("touching the sea floor", !boat.touchingGround)
    }

    /** Archimedes, measured: it displaces its own weight of water. */
    @Test
    fun `it displaces its own weight`() {
        val world = World.default(catalog)
        val design = StockCraft.boat(catalog)
        val boat = world.spawnOnSurface(design, World.launchSiteFor(design, catalog))
        val water = Hydrostatics()
        repeat((20.0 / dt).toInt()) { world.step(dt) }
        water.apply(boat, world.attractorFor(boat), world.time, dt)
        val expected = boat.body.mass / 1_025.0
        assertEquals(expected, water.submergedVolume, expected * 0.05)
    }

    @Test
    fun `heeled over, it comes back upright`() {
        val (world, boat) = afloat()
        val axis = boat.forward()
        boat.body.orientation.setTo(Quat.fromAxisAngle(axis, Math.toRadians(25.0)) * boat.body.orientation)
        repeat((20.0 / dt).toInt()) { world.step(dt) }
        assertTrue("still heeled at ${tilt(boat)} degrees", tilt(boat) < 6.0)
    }

    @Test
    fun `throttle drives it forward, not sideways`() {
        val (world, boat) = afloat()
        world.stage(boat)
        boat.control.throttle = 1.0
        repeat((30.0 / dt).toInt()) { world.step(dt) }

        val v = groundVelocity(world, boat)
        val speed = v.length
        assertTrue("barely moving at $speed m/s", speed > 4.0)
        val along = (v dot boat.forward()) / speed
        assertTrue("moving off its heading: $along", along > 0.95)
        assertTrue("it has left the water", abs(world.attractorFor(boat).altitudeOf(boat.body.position)) < 2.0)
    }

    /**
     * The keel test. A hull that skated sideways as easily as forwards would
     * spin on the spot and keep going the way it was; one that grips the
     * water sideways turns its track with its heading.
     */
    @Test
    fun `it turns its track, not just its nose`() {
        val (world, boat) = afloat()
        world.stage(boat)
        boat.control.throttle = 1.0
        repeat((20.0 / dt).toInt()) { world.step(dt) }
        val before = groundVelocity(world, boat).normalizeInPlace()

        boat.control.yaw = 1.0
        repeat((15.0 / dt).toInt()) { world.step(dt) }
        boat.control.yaw = 0.0
        val v = groundVelocity(world, boat)
        val after = v.copy().normalizeInPlace()

        val turned = Math.toDegrees(kotlin.math.acos((before dot after).coerceIn(-1.0, 1.0)))
        assertTrue("track turned only $turned degrees", turned > 30.0)
        val slip = Math.toDegrees(asin(((after dot boat.forward())).coerceIn(-1.0, 1.0)))
        assertTrue("skidding: track is ${90 - slip} degrees off the nose", slip > 70.0)
    }
}
