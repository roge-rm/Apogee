package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rovers: the claim that a vehicle class is a bag of modules, not a system.
 *
 * Nothing here adds a code path to the step loop. A wheel is a landing leg whose friction is split
 * along its rolling axis and which can be driven, and driving is the throttle already in
 * [com.rm.apogee.core.craft.ControlState].
 */
class RoverTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun worldWithRover(): Pair<World, com.rm.apogee.core.craft.Vessel> {
        val world = World.default(catalog)
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), World.launchSites.first())
        // Let it settle onto its suspension before anything is asked of it.
        repeat(120) { world.step(dt) }
        return world to rover
    }

    /** Ground speed: what a driver would read, not orbital speed. */
    private fun groundSpeed(world: World, vessel: com.rm.apogee.core.craft.Vessel): Double {
        val attractor = world.system.body(vessel.referenceBodyId)!!
        val surface = com.rm.apogee.core.math.Vec3()
        attractor.surfaceVelocityAt(vessel.body.position, surface)
        return com.rm.apogee.core.math.Vec3().setTo(vessel.body.linearVelocity)
            .subInPlace(surface).length
    }

    @Test
    fun `a parked rover stays parked`() {
        val (world, rover) = worldWithRover()
        repeat(300) { world.step(dt) }
        assertTrue(
            "a rover with no throttle rolled away at ${groundSpeed(world, rover)} m/s",
            groundSpeed(world, rover) < 0.5,
        )
    }

    @Test
    fun `throttle drives it along the ground`() {
        val (world, rover) = worldWithRover()
        rover.control.throttle = 1.0
        repeat(600) { world.step(dt) }

        val speed = groundSpeed(world, rover)
        assertTrue("the rover never got moving (${speed} m/s)", speed > 8.0)
        // The motor fades out near its top speed, so a rover has one. Without that it just keeps
        // speeding up, which is a sled, not a vehicle.
        assertTrue(
            "a rover doing ${speed} m/s has no top speed",
            speed < 25.0,
        )
    }

    /**
     * The point of the friction split. The same craft on landing legs can't be driven at all,
     * because ordinary ground friction pins it.
     */
    @Test
    fun `wheels are what make it drivable`() {
        val (world, rover) = worldWithRover()
        rover.control.throttle = 1.0
        repeat(600) { world.step(dt) }
        val onWheels = groundSpeed(world, rover)

        val legWorld = World.default(catalog)
        val onLegs = legWorld.spawnOnSurface(
            StockCraft.lander(catalog), World.launchSites.first(),
        )
        repeat(120) { legWorld.step(dt) }
        onLegs.control.throttle = 0.0
        repeat(600) { legWorld.step(dt) }

        assertTrue("wheels should move and legs should not", onWheels > groundSpeed(legWorld, onLegs) + 2.0)
    }

    /** Heading over the ground, which is the only frame a driver cares about. */
    private fun groundVelocity(
        world: World,
        vessel: com.rm.apogee.core.craft.Vessel,
        out: com.rm.apogee.core.math.Vec3,
    ): com.rm.apogee.core.math.Vec3 {
        val attractor = world.system.body(vessel.referenceBodyId)!!
        val surface = com.rm.apogee.core.math.Vec3()
        attractor.surfaceVelocityAt(vessel.body.position, surface)
        return out.setTo(vessel.body.linearVelocity).subInPlace(surface)
    }

    @Test
    fun `steering turns it`() {
        val (world, rover) = worldWithRover()
        rover.control.throttle = 1.0
        repeat(240) { world.step(dt) }

        // Ground-relative, not inertial. The surface moves at 175 m/s at the equator, so an
        // inertial heading is almost all the planet's rotation, and a rover turning hard barely
        // shows up in it. That's exactly how this test first claimed steering did nothing.
        val before = groundVelocity(world, rover, com.rm.apogee.core.math.Vec3())
        rover.control.yaw = 1.0
        repeat(360) { world.step(dt) }
        val after = groundVelocity(world, rover, com.rm.apogee.core.math.Vec3())

        val cosine = (before dot after) / (before.length * after.length)
        assertTrue(
            "steering did not change the direction of travel (cos $cosine)",
            cosine < 0.95,
        )
    }

    @Test
    fun `a rover is a catalogue entry, not a new simulation`() {
        val design = StockCraft.rover(catalog)
        assertEquals("no staging needed to drive", 0, design.stages.size)
        assertTrue(
            "and no engine either",
            design.parts.none {
                catalog[it.partId]?.module<com.rm.apogee.core.part.Engine>() != null
            },
        )
    }

    /** And it stays on its wheels doing it. Full-friction brakes flipped it. */
    @Test
    fun `brakes stop it`() {
        val (world, rover) = worldWithRover()
        rover.control.throttle = 1.0
        repeat(600) { world.step(dt) }
        val cruising = groundSpeed(world, rover)

        rover.control.throttle = 0.0
        rover.control.brakes = true
        // Brake friction of 0.35 is about three and a half metres a second every second, so six
        // seconds from top speed, and eight is a stop with room to spare.
        repeat(480) { world.step(dt) }
        assertTrue(
            "from $cruising m/s, still doing ${groundSpeed(world, rover)} after eight seconds of brakes",
            groundSpeed(world, rover) < 0.5,
        )
        val up = rover.body.position.copy().normalizeInPlace()
        val mast = rover.body.orientation.rotate(rover.design.orientation.up)
        assertTrue("it went over braking", (mast dot up) > 0.9)
    }

    /** Coasting, on the other hand, goes a long way, which is what a wheel is for. */
    @Test
    fun `without brakes it coasts`() {
        val (world, rover) = worldWithRover()
        rover.control.throttle = 1.0
        repeat(600) { world.step(dt) }
        rover.control.throttle = 0.0
        repeat(180) { world.step(dt) }
        assertTrue("it stopped without brakes", groundSpeed(world, rover) > 5.0)
    }

    @Test
    fun `brakes hold it against its own motor`() {
        val (world, rover) = worldWithRover()
        rover.control.brakes = true
        rover.control.throttle = 1.0
        repeat(300) { world.step(dt) }
        assertTrue("it drove through its brakes", groundSpeed(world, rover) < 0.5)
    }

    /**
     * Out into the real country, flat out, for a minute: about a kilometre, off the levelled pad
     * and over the basin's hills. It has to still be on its wheels at the end. Ground rough enough
     * to throw a rover over at speed isn't fun to drive, and the first cut of this terrain was like
     * that.
     */
    @Test
    fun `a rover can cross the country around the Cape`() {
        val (world, rover) = worldWithRover()
        rover.control.throttle = 1.0
        repeat((60.0 / dt).toInt()) { world.step(dt) }
        assertTrue("destroyed on the way", world.vessel(rover.id) != null)
        val up = rover.body.position.copy().normalizeInPlace()
        val mast = rover.body.orientation.rotate(rover.design.orientation.up)
        val travelled = groundSpeed(world, rover)
        assertTrue("rolled over (mast at ${Math.toDegrees(kotlin.math.acos((mast dot up).coerceIn(-1.0, 1.0)))} degrees)", (mast dot up) > 0.7)
        assertTrue("stopped moving: $travelled m/s", travelled > 3.0)
    }

    /**
     * Flat out along the runway and hard over. Its steering gives less lock the faster it goes, and
     * it stays on its wheels. At full lock at eleven metres a second, the Trundler once rolled.
     */
    @Test
    fun `hard over at top speed, it turns and stays on its wheels`() {
        val world = World.default(catalog)
        val rover = world.spawnFor(Command.SpawnCraft(StockCraft.rover(catalog), "airfield"), "p1")
        world.apply(Command.SetThrottle(rover.id.raw, 1.0))
        repeat((15.0 / dt).toInt()) { world.step(dt) }
        val fast = groundSpeed(world, rover)
        assertTrue("never got going: $fast m/s", fast > 8.0)
        repeat((10.0 / dt).toInt()) { world.apply(Command.SetAttitude(rover.id.raw, 0.0, 1.0, 0.0)); world.step(dt) }
        val up = rover.body.position.copy().normalizeInPlace()
        val deck = rover.body.orientation.rotate(rover.design.orientation.up)
        assertTrue("rolled over at $fast m/s: deck ${Math.toDegrees(kotlin.math.acos((deck dot up).coerceIn(-1.0, 1.0)))} degrees off", (deck dot up) > 0.8)
    }
}
