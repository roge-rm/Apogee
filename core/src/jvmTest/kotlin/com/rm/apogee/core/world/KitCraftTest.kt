package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos

/**
 * The craft built from the vehicle kits do what their kind does: the jet flies, the rovers drive
 * and steer, and the boats float, go and turn without rolling over. Each one is only parts, with no
 * craft-specific code, so these are really tests that the kit parts are sized and placed sensibly.
 */
class KitCraftTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun spawn(design: CraftDesign): Pair<World, Vessel> {
        val world = World.default(catalog)
        return world to world.spawnOnSurface(design, World.launchSiteFor(design, catalog))
    }

    private fun run(world: World, seconds: Double, each: () -> Unit = {}) =
        repeat((seconds / dt).toInt()) { each(); world.step(dt) }

    private fun up(vessel: Vessel) = vessel.body.position.copy().normalizeInPlace()

    private fun groundVelocity(world: World, vessel: Vessel): Vec3 {
        val surface = world.attractorFor(vessel).surfaceVelocityAt(vessel.body.position, Vec3())
        return vessel.body.linearVelocity.copy().subInPlace(surface)
    }

    private fun heightAboveGround(world: World, vessel: Vessel): Double {
        val attractor = world.attractorFor(vessel)
        val bodyFixed = attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(world.time))
        return attractor.heightAboveTerrain(vessel.body.position, bodyFixed)
    }

    /** How far the craft's own up is from the local vertical, in degrees. */
    private fun tilt(vessel: Vessel): Double {
        val craftUp = vessel.body.orientation.rotate(vessel.design.orientation.up)
        return Math.toDegrees(acos((craftUp dot up(vessel)).coerceIn(-1.0, 1.0)))
    }

    /** Degrees between two directions, flattened onto the local horizontal. */
    private fun turned(vessel: Vessel, a: Vec3, b: Vec3): Double {
        val u = up(vessel)
        val fa = a.copy().addScaledInPlace(u, -(a dot u)).normalizeInPlace()
        val fb = b.copy().addScaledInPlace(u, -(b dot u)).normalizeInPlace()
        return Math.toDegrees(acos((fa dot fb).coerceIn(-1.0, 1.0)))
    }

    // --- air ----------------------------------------------------------------

    private fun noseUp(vessel: Vessel): Double {
        val nose = vessel.body.orientation.rotate(vessel.design.orientation.forward)
        return Math.toDegrees(kotlin.math.asin((nose dot up(vessel)).coerceIn(-1.0, 1.0)))
    }

    /**
     * Flown the way a pilot would: full throttle, SAS on, and the stick back from 50 m/s to hold
     * the nose 12 degrees up until it's ten metres off the ground, then let go. It leaves the
     * ground at a light jet's speed and, trimmed with the tail neutral, SAS holds the climb. It
     * neither drops its nose into the ground nor loops.
     */
    @Test
    fun `the Sparrow rotates, lifts off and holds its climb`() {
        val (world, jet) = spawn(StockCraft.sparrow(catalog))
        world.apply(Command.Stage(jet.id.raw))
        world.apply(Command.SetThrottle(jet.id.raw, 1.0))
        world.apply(Command.SetSas(jet.id.raw, true))
        var liftOffSpeed = Double.NaN
        var phase = 0 // 0 rolling, 1 rotating, 2 hands off
        var highestNose = 0.0
        repeat((30.0 / dt).toInt()) {
            val speed = groundVelocity(world, jet).length
            if (phase == 0 && speed > 50.0) phase = 1
            if (phase == 1) {
                // Hold the nose at 12 degrees, easing off as it gets there.
                val stick = ((12.0 - noseUp(jet)) / 6.0).coerceIn(-1.0, 1.0)
                world.apply(Command.SetAttitude(jet.id.raw, stick, 0.0, 0.0))
                if (heightAboveGround(world, jet) > 10.0) {
                    world.apply(Command.SetAttitude(jet.id.raw, 0.0, 0.0, 0.0))
                    phase = 2
                }
            }
            world.step(dt)
            if (phase == 2) highestNose = maxOf(highestNose, noseUp(jet))
            if (liftOffSpeed.isNaN() && heightAboveGround(world, jet) > 5.0) liftOffSpeed = speed
        }
        assertTrue("it never left the ground", !liftOffSpeed.isNaN())
        assertTrue("lifted off only at $liftOffSpeed m/s", liftOffSpeed < 100.0)
        assertTrue("not climbing: ${heightAboveGround(world, jet)} m up", heightAboveGround(world, jet) > 100.0)
        assertTrue("nose ran away to $highestNose degrees", highestNose < 25.0)
        assertTrue("it lost parts", jet.broken.none { it })
    }

    // --- land ---------------------------------------------------------------

    private fun drivesAndSteers(design: CraftDesign, minSpeed: Double) {
        val (world, rover) = spawn(design)
        world.apply(Command.SetThrottle(rover.id.raw, 1.0))
        run(world, 20.0)
        val speed = groundVelocity(world, rover).length
        assertTrue("${design.name} only reached $speed m/s", speed > minSpeed)
        assertTrue("${design.name} is tipping: ${tilt(rover)} degrees", tilt(rover) < 10.0)

        val before = rover.forward().copy()
        world.apply(Command.SetAttitude(rover.id.raw, 0.0, 0.5, 0.0))
        run(world, 8.0)
        assertTrue("${design.name} turned only ${turned(rover, before, rover.forward())} degrees",
            turned(rover, before, rover.forward()) > 30.0)
        assertTrue("${design.name} rolled over", tilt(rover) < 30.0)
        assertTrue("${design.name} lost parts", rover.broken.none { it })
    }

    @Test
    fun `the Buggy drives and steers`() = drivesAndSteers(StockCraft.buggy(catalog), minSpeed = 8.0)

    @Test
    fun `the Hauler drives and steers`() = drivesAndSteers(StockCraft.hauler(catalog), minSpeed = 8.0)

    // --- sea ----------------------------------------------------------------

    private fun goesAndTurns(design: CraftDesign, minSpeed: Double) {
        val (world, boat) = spawn(design)
        assertEquals("harbour", World.launchSiteFor(design, catalog).id)
        run(world, 10.0)
        assertTrue("${design.name} is not floating level: ${tilt(boat)} degrees", tilt(boat) < 8.0)
        assertTrue("${design.name} sits on the sea floor", !boat.touchingGround)

        world.apply(Command.Stage(boat.id.raw))
        world.apply(Command.SetThrottle(boat.id.raw, 1.0))
        run(world, 20.0)
        val speed = groundVelocity(world, boat).length
        assertTrue("${design.name} only makes $speed m/s", speed > minSpeed)

        // Heading added up tick by tick, because a quick boat comes all the way round in the time,
        // and the angle between start and end would wrap.
        world.apply(Command.SetAttitude(boat.id.raw, 0.0, 0.6, 0.0))
        var worstTilt = 0.0
        var heading = 0.0
        run(world, 15.0) {
            worstTilt = maxOf(worstTilt, tilt(boat))
            heading += Math.toDegrees(boat.body.angularVelocity dot up(boat)) * dt
        }
        assertTrue("${design.name} turned only ${abs(heading)} degrees", abs(heading) > 30.0)
        assertTrue("${design.name} heeled ${worstTilt} degrees in the turn", worstTilt < 35.0)
    }

    @Test
    fun `the Skiff goes and turns without capsizing`() = goesAndTurns(StockCraft.skiff(catalog), minSpeed = 1.5)

    @Test
    fun `the Cutter goes and turns without capsizing`() = goesAndTurns(StockCraft.cutter(catalog), minSpeed = 1.0)

    /**
     * The keel is a plate across the water's path sideways. Shove a cutter beam-on and it stops
     * sliding within seconds instead of skating on.
     */
    @Test
    fun `a keel stops a boat sliding sideways`() {
        val (world, boat) = spawn(StockCraft.cutter(catalog))
        run(world, 10.0)
        boat.wake()
        val side = boat.forward().cross(up(boat)).normalizeInPlace()
        boat.body.linearVelocity.addScaledInPlace(side, 3.0)
        run(world, 4.0)
        val sideways = abs(groundVelocity(world, boat) dot side)
        assertTrue("still sliding sideways at $sideways m/s", sideways < 0.6)
    }

    /**
     * Out of the water, a water propeller and a rudder are just weight. An outboard at full
     * throttle in the air pushes nothing, and a keel does nothing to a craft falling through air.
     */
    @Test
    fun `an outboard pushes nothing out of the water`() {
        val (world, boat) = spawn(StockCraft.skiff(catalog))
        run(world, 5.0)
        boat.body.position.addScaledInPlace(up(boat), 200.0)
        world.apply(Command.Stage(boat.id.raw))
        world.apply(Command.SetThrottle(boat.id.raw, 1.0))
        boat.wake()
        run(world, 2.0)
        val v = groundVelocity(world, boat)
        val horizontal = v.copy().addScaledInPlace(up(boat), -(v dot up(boat))).length
        assertTrue("it drove through the air at $horizontal m/s", horizontal < 0.3)
    }

    /**
     * One stick, one way round. The same yaw turns every craft that steers (the rovers on their
     * wheels, a boat on its rudder, a jet on its tail) toward the same side of itself. The
     * Trundler, when it was a pod standing on wheels, turned the opposite way to every other rover.
     */
    @Test
    fun `the same yaw turns every rover, boat and plane the same way`() {
        for (design in listOf(StockCraft.rover(catalog), StockCraft.buggy(catalog), StockCraft.skiff(catalog), StockCraft.sparrow(catalog))) {
            val (world, craft) = spawn(design)
            world.apply(Command.Stage(craft.id.raw))
            world.apply(Command.SetThrottle(craft.id.raw, if (design.name == "Sparrow") 0.4 else 1.0))
            run(world, 5.0)
            val before = groundVelocity(world, craft).normalizeInPlace()
            val side = craft.body.orientation.rotate(Vec3.unitX())
            run(world, 2.0) { world.apply(Command.SetAttitude(craft.id.raw, 0.0, 0.5, 0.0)) }
            val swung = groundVelocity(world, craft).normalizeInPlace().subInPlace(before) dot side
            assertTrue("${design.name} turned the other way: $swung", swung < -0.05)
        }
    }
}
