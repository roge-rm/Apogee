package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Time warp: how fast each situation allows, and moving craft on rails. */
class WarpTest {

    private val catalog = StockParts.catalog

    private fun inOrbit(world: World, altitude: Double) = world.system.body("terra").let { terra ->
        val position = Vec3(terra.radius + altitude, 0.0, 0.0)
        val velocity = Vec3(0.0, 0.0, sqrt(terra.gravitationalParameter / position.x))
        world.spawnAt(StockCraft.starterRocket(catalog), "terra", position, velocity, quatFromTo(Vec3.unitY(), Vec3(1.0, 0.0, 0.0)))
    }

    @Test
    fun `in the air or on the pad only the faster physics is allowed`() {
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        rocket.wake()
        assertEquals(World.PHYSICS_WARP, world.warpLimit(rocket), 0.0)
    }

    @Test
    fun `higher orbits allow faster warp`() {
        val low = World.default(catalog).let { it.warpLimit(inOrbit(it, 100_000.0)) }
        val high = World.default(catalog).let { it.warpLimit(inOrbit(it, 600_000.0)) }
        assertEquals("just above the air, rails at the slowest", 10.0, low, 0.0)
        assertEquals("a Terra radius out, as fast as near a world goes", 10_000.0, high, 0.0)
    }

    @Test
    fun `under power it stays physical`() {
        val world = World.default(catalog)
        val rocket = inOrbit(world, 600_000.0)
        world.stage(rocket)
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        assertEquals(World.PHYSICS_WARP, world.warpLimit(rocket), 0.0)
    }

    /** On rails a craft goes exactly where its orbit says. */
    @Test
    fun `on rails a craft follows its orbit`() {
        val world = World.default(catalog)
        val rocket = inOrbit(world, 300_000.0)
        val terra = world.attractorFor(rocket)
        val expected = Orbit(rocket.body.position.copy(), rocket.body.linearVelocity.copy(), terra.gravitationalParameter).propagate(600.0)
        val start = world.time
        val done = world.advanceOnRails(600.0)
        assertEquals(600.0, done, 1e-9)
        assertEquals(start + 600.0, world.time, 1e-9)
        assertTrue(
            "off its orbit by ${rocket.body.position.distanceTo(expected.position)} m",
            rocket.body.position.distanceTo(expected.position) < 1.0,
        )
    }

    /** Coming down into the air, rails stop by themselves. */
    @Test
    fun `rails stop at the edge of the air`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        // Falling toward the atmosphere from just above it.
        val position = Vec3(terra.radius + terra.atmosphereHeight + 3_000.0, 0.0, 0.0)
        val rocket = world.spawnAt(
            StockCraft.starterRocket(catalog), "terra", position, Vec3(-300.0, 0.0, 1_000.0),
            quatFromTo(Vec3.unitY(), Vec3(1.0, 0.0, 0.0)),
        )
        val done = world.advanceOnRails(600.0)
        assertTrue("it should have stopped short: $done s", done < 600.0)
        assertTrue(
            "no deeper than one slice into the air",
            terra.altitudeOf(rocket.body.position) > terra.atmosphereHeight - 2_000.0,
        )
    }
}
