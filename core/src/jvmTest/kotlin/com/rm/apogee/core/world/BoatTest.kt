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
 * A boat floats, rights itself, goes where it's pointed and turns, with no boat-specific system.
 * It all comes from buoyancy and water drag on cells of each part's volume.
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

    /** How high the craft's centre is above the water where it is, in metres, tide and all. */
    private fun aboveWater(world: World, vessel: Vessel): Double {
        val attractor = world.attractorFor(vessel)
        val bodyFixed = attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(world.time))
        return attractor.altitudeOf(vessel.body.position) - attractor.ocean!!.surfaceHeight(bodyFixed, world.time)
    }

    /** How far the deck (+Z) is from vertical, in degrees. */
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
        val altitude = aboveWater(world, boat)
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

    /** Through commands, since only a command wakes a settled boat. */
    private fun underWay(world: World, boat: Vessel) {
        world.apply(Command.Stage(boat.id.raw))
        world.apply(Command.SetThrottle(boat.id.raw, 1.0))
    }

    @Test
    fun `throttle drives it forward, not sideways`() {
        val (world, boat) = afloat()
        underWay(world, boat)
        repeat((30.0 / dt).toInt()) { world.step(dt) }

        val v = groundVelocity(world, boat)
        val speed = v.length
        assertTrue("barely moving at $speed m/s", speed > 4.0)
        val along = (v dot boat.forward()) / speed
        assertTrue("moving off its heading: $along", along > 0.95)
        assertTrue("it has left the water", abs(aboveWater(world, boat)) < 2.0)
    }

    /** The keel test: a hull that grips the water sideways turns its track with its heading. */
    @Test
    fun `it turns its track, not just its nose`() {
        val (world, boat) = afloat()
        underWay(world, boat)
        // Not long, since the harbour's small.
        repeat((8.0 / dt).toInt()) { world.step(dt) }
        val before = groundVelocity(world, boat).normalizeInPlace()

        world.apply(Command.SetAttitude(boat.id.raw, 0.0, 1.0, 0.0))
        repeat((15.0 / dt).toInt()) { world.step(dt) }
        world.apply(Command.SetAttitude(boat.id.raw, 0.0, 0.0, 0.0))
        val v = groundVelocity(world, boat)
        val after = v.copy().normalizeInPlace()

        val turned = Math.toDegrees(kotlin.math.acos((before dot after).coerceIn(-1.0, 1.0)))
        assertTrue("track turned only $turned degrees", turned > 30.0)
        val slip = Math.toDegrees(asin(((after dot boat.forward())).coerceIn(-1.0, 1.0)))
        assertTrue("skidding: track is ${90 - slip} degrees off the nose", slip > 70.0)
    }

    /** A boat left alone at sea sleeps like one on a pad, and wakes when someone takes the controls. */
    @Test
    fun `moored, it goes to sleep, and wakes to the throttle`() {
        // It rocks into its trim (the motor's at the stern) and is still within the minute.
        val (world, boat) = afloat(settle = 45.0)
        assertTrue("a still boat never went to sleep", boat.dormant)

        underWay(world, boat)
        repeat((10.0 / dt).toInt()) { world.step(dt) }
        assertTrue("it did not wake", !boat.dormant)
        assertTrue("awake but not moving", groundVelocity(world, boat).length > 1.0)
    }

    /**
     * SAS at sea is a helmsman. It keeps the deck level through a turn, and when the wheel is let
     * go it holds the heading with the deck level.
     */
    @Test
    fun `SAS afloat keeps the deck level through a turn and holds the heading after`() {
        fun turn(sas: Boolean): Triple<Double, Double, Double> {
            val world = World.default(catalog)
            val design = StockCraft.skiff(catalog)
            val boat = world.spawnOnSurface(design, World.launchSiteFor(design, catalog))
            repeat((5.0 / dt).toInt()) { world.step(dt) }
            underWay(world, boat)
            world.apply(Command.SetSas(boat.id.raw, sas))
            repeat((5.0 / dt).toInt()) { world.step(dt) }
            world.apply(Command.SetAttitude(boat.id.raw, 0.0, 0.5, 0.0))
            var heeled = 0.0
            repeat((6.0 / dt).toInt()) { world.step(dt); heeled = maxOf(heeled, abs(heel(boat))) }
            world.apply(Command.SetAttitude(boat.id.raw, 0.0, 0.0, 0.0))
            repeat((3.0 / dt).toInt()) { world.step(dt) }
            val heading = boat.forward()
            var after = 0.0
            repeat((6.0 / dt).toInt()) { world.step(dt); after = maxOf(after, abs(heel(boat))) }
            val up = boat.body.position.normalized()
            val turned = Math.toDegrees(kotlin.math.acos((flat(heading, up) dot flat(boat.forward(), up)).coerceIn(-1.0, 1.0)))
            return Triple(heeled, after, turned)
        }
        val (freeHeel, _, _) = turn(sas = false)
        val (heldHeel, after, turned) = turn(sas = true)
        assertTrue("SAS kept it more level through the turn: $heldHeel against $freeHeel degrees", heldHeel < 0.6 * freeHeel)
        assertTrue("level after: $after degrees of heel", after < 2.0)
        assertTrue("on its heading: turned $turned degrees since", turned < 5.0)
    }

    /** Boats make way. A hull's drag is its bow's, counted once, not once per slice along it. */
    @Test
    fun `the stock boats make way at full throttle`() {
        for ((design, least) in listOf(
            StockCraft.skiff(catalog) to 3.5, StockCraft.cutter(catalog) to 3.5, StockCraft.trawler(catalog) to 3.5,
        )) {
            val world = World.default(catalog)
            val boat = world.spawnOnSurface(design, World.launchSiteFor(design, catalog))
            repeat((5.0 / dt).toInt()) { world.step(dt) }
            underWay(world, boat)
            repeat((25.0 / dt).toInt()) { world.step(dt) }
            val speed = groundVelocity(world, boat).length
            assertTrue("${design.name} makes only $speed m/s", speed > least)
        }
    }

    /** Degrees the deck is heeled over, right side down positive. */
    private fun heel(boat: Vessel): Double {
        val up = boat.body.position.normalized()
        val right = boat.body.orientation.rotate(boat.design.orientation.forward.cross(boat.design.orientation.up), Vec3())
        return Math.toDegrees(kotlin.math.asin((-(right dot up)).coerceIn(-1.0, 1.0)))
    }

    private fun flat(v: Vec3, up: Vec3) = v.copy().addScaledInPlace(up, -(v dot up)).normalizeInPlace()
}
