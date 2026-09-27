package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keeper core: it holds a craft over a spot, at a height, with whatever the craft has. */
class StationKeepingTest {

    private val catalog = StockParts.catalog

    @Test
    fun `a quad held by its keeper core stays within a couple of metres for five minutes`() {
        val world = World.default(catalog)
        val quad = Aloft.spawn(world, StockCraft.quad(catalog), 60.0)
        world.apply(Command.SetThrottle(quad.id.raw, 0.7))
        world.apply(Command.SetStationKeep(quad.id.raw, true))
        assertTrue(quad.control.autopilotNote, quad.control.keeping)
        val spot = Aloft.fixed(world, quad)
        var worst = 0.0
        Aloft.run(world, 300.0) { worst = maxOf(worst, Aloft.moved(world, quad, spot)) }
        assertTrue("wandered $worst m", worst < 3.0)
        assertTrue(quad.control.keeping)
    }

    @Test
    fun `steered by hand it moves off, and let go it holds the new spot`() {
        val world = World.default(catalog)
        val quad = Aloft.spawn(world, StockCraft.quad(catalog), 60.0)
        world.apply(Command.SetThrottle(quad.id.raw, 0.7))
        world.apply(Command.SetStationKeep(quad.id.raw, true))
        Aloft.run(world, 5.0)
        val first = Aloft.fixed(world, quad)
        world.apply(Command.SetAttitude(quad.id.raw, 0.4, 0.0, 0.0))
        Aloft.run(world, 4.0)
        world.apply(Command.SetAttitude(quad.id.raw, 0.0, 0.0, 0.0))
        Aloft.run(world, 10.0)
        assertTrue("only moved ${Aloft.moved(world, quad, first)} m", Aloft.moved(world, quad, first) > 3.0)
        val second = Aloft.fixed(world, quad)
        var worst = 0.0
        Aloft.run(world, 60.0) { worst = maxOf(worst, Aloft.moved(world, quad, second)) }
        assertTrue("wandered $worst m from where it was let go", worst < 6.0)
    }

    @Test
    fun `the Sky Platform holds itself on its gas and fans, and the ballonets take the load`() {
        val world = World.default(catalog)
        val platform = Aloft.spawn(world, StockCraft.skyPlatform(catalog), 150.0)
        world.apply(Command.SetStationKeep(platform.id.raw, true))
        val spot = Aloft.fixed(world, platform)
        var worst = 0.0
        Aloft.run(world, 300.0) { worst = maxOf(worst, Aloft.moved(world, platform, spot)) }
        assertTrue("wandered $worst m", worst < 5.0)
        assertTrue("fans at ${platform.control.throttle}", platform.control.throttle < 0.1)
    }

    @Test
    fun `the Zeppelin keeps near its spot and its height`() {
        val world = World.default(catalog)
        val ship = Aloft.spawn(world, StockCraft.zeppelin(catalog), 300.0)
        ship.activated.fill(true)
        world.apply(Command.SetStationKeep(ship.id.raw, true))
        Aloft.run(world, 120.0)
        val spot = Aloft.fixed(world, ship)
        var worst = 0.0
        Aloft.run(world, 300.0) { worst = maxOf(worst, Aloft.moved(world, ship, spot)) }
        assertTrue("wandered $worst m", worst < 80.0)
    }

    @Test
    fun `it won't hold without a keeper core, or on the ground`() {
        val world = World.default(catalog)
        val heli = Aloft.spawn(world, StockCraft.hummingbird(catalog), 50.0)
        world.apply(Command.SetStationKeep(heli.id.raw, true))
        assertFalse(heli.control.keeping)
        assertEquals("No keeper core", heli.control.autopilotNote)
        val parked = world.spawnOnSurface(StockCraft.quad(catalog), World.launchSites.first { it.id == "cape" })
        world.assignOwner(parked, "p1")
        Aloft.run(world, 3.0)
        world.apply(Command.SetStationKeep(parked.id.raw, true))
        assertFalse(parked.control.keeping)
        assertEquals("Lift off first", parked.control.autopilotNote)
    }
}
