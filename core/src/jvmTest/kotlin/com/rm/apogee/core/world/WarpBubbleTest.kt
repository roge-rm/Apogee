package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A craft warping ahead in a bubble of its own time, and back onto the world's. */
class WarpBubbleTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun worldWithTwo(): Triple<World, com.rm.apogee.core.craft.Vessel, com.rm.apogee.core.craft.Vessel> {
        val world = World.default(catalog)
        val mu = world.system.body("terra").gravitationalParameter
        // High enough for 1000x (see World.RAILS_HEIGHTS), and well clear of Luna.
        val traveller = world.spawnInOrbit(StockCraft.moteProbe(catalog), "terra", Orbit.circular(1_500_000.0, mu))
        val stayer = world.spawnInOrbit(StockCraft.moteProbe(catalog), "terra", Orbit.circular(2_500_000.0, mu))
        return Triple(world, traveller, stayer)
    }

    @Test
    fun `a bubble runs ahead on its own, and the world and everyone else keep real time`() {
        val (world, traveller, stayer) = worldWithTwo()
        val bubble = world.openBubble(listOf(traveller), "traveller")
        bubble.rate = 1_000.0
        val stayerAt = stayer.body.position.copy()
        repeat(60) {
            world.step(dt)
            world.advanceBubble(bubble, dt)
        }
        assertEquals("the world went on a second", 1.0, world.time, 1e-6)
        assertEquals("the bubble a thousand", 1_000.0, bubble.time, 1.0)
        assertEquals(999.0, world.leadOf(traveller), 1.0)
        assertEquals("nobody else", 0.0, world.leadOf(stayer), 0.0)
        // A second of orbit for the one staying, a thousand for the traveller.
        assertTrue("the stayer moved a little", stayer.body.position.distanceTo(stayerAt) < 3_000.0)
        assertTrue("the world's warp isn't held by the traveller", world.maxWarp() > World.PHYSICS_WARP)
    }

    @Test
    fun `waiting, the world catches a bubble up`() {
        val (world, traveller, _) = worldWithTwo()
        val bubble = world.openBubble(listOf(traveller), "traveller")
        bubble.rate = 1_000.0
        repeat(6) { world.step(dt); world.advanceBubble(bubble, dt) }
        val lead = world.leadOf(traveller)
        bubble.rate = 0.0
        repeat(30) { world.step(dt); world.advanceBubble(bubble, dt) }
        assertEquals("half a second less ahead", lead - 0.5, world.leadOf(traveller), 1e-6)
    }

    @Test
    fun `back on the world's clock it keeps its orbit and its burns move with it`() {
        val (world, traveller, _) = worldWithTwo()
        val bubble = world.openBubble(listOf(traveller), "traveller")
        bubble.rate = 1_000.0
        repeat(30) { world.step(dt); world.advanceBubble(bubble, dt) }
        traveller.plannedBurns.add(PlannedBurn(bubble.time + 600.0, prograde = 10.0))
        val position = traveller.body.position.copy()
        val velocity = traveller.body.linearVelocity.copy()
        val lead = world.leadOf(traveller)
        assertTrue("round Terra, so it can close", world.canClose(bubble))
        world.closeBubble(bubble)
        assertNull(world.bubbles[traveller.id.raw])
        assertEquals(0.0, world.leadOf(traveller), 0.0)
        assertEquals("where it was round Terra", 0.0, traveller.body.position.distanceTo(position), 1e-9)
        assertEquals(0.0, traveller.body.linearVelocity.distanceTo(velocity), 1e-9)
        assertEquals("the burn as far off as it was", world.time + 600.0, traveller.plannedBurns.first().time, 1e-6)
        assertTrue(lead > 400.0)
    }

    @Test
    fun `out between worlds a bubble can't close`() {
        val world = World.default(catalog)
        val sol = world.system.body(world.system.rootId)
        val terra = world.system.positionOf("terra", world.time)
        val out = Orbit(terra.copy().mulInPlace(1.5), com.rm.apogee.core.math.Vec3(0.0, 0.0, 0.0).also {
            // A slow solar orbit well outside Terra's reach.
            it.setTo(0.0, kotlin.math.sqrt(sol.gravitationalParameter / (terra.length * 1.5)), 0.0)
        }, sol.gravitationalParameter)
        val cruiser = world.spawnInOrbit(StockCraft.moteProbe(catalog), sol.id, out)
        val bubble = world.openBubble(listOf(cruiser), "cruiser")
        assertFalse(world.canClose(bubble))
    }

    @Test
    fun `on its way out to Luna a bubble can't close, and round Terra low down it can`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val luna = world.system.body("luna").orbit!!
        // Apoapsis out at Luna's distance: a transfer.
        val low = 900_000.0
        val high = luna.periapsis
        val a = (low + high) / 2.0
        val speed = kotlin.math.sqrt(terra.gravitationalParameter * (2.0 / low - 1.0 / a))
        val transfer = Orbit(com.rm.apogee.core.math.Vec3(low, 0.0, 0.0), com.rm.apogee.core.math.Vec3(0.0, 0.0, speed), terra.gravitationalParameter)
        val going = world.spawnInOrbit(StockCraft.moteProbe(catalog), "terra", transfer)
        assertFalse(world.canClose(world.openBubble(listOf(going), "a")))
        val staying = world.spawnInOrbit(StockCraft.moteProbe(catalog), "terra", Orbit.circular(low, terra.gravitationalParameter))
        assertTrue(world.canClose(world.openBubble(listOf(staying), "b")))
    }

    @Test
    fun `a bubble is saved, and comes back waiting`() {
        val (world, traveller, _) = worldWithTwo()
        val bubble = world.openBubble(listOf(traveller), "traveller")
        bubble.rate = 1_000.0
        repeat(30) { world.step(dt); world.advanceBubble(bubble, dt) }
        val again = World.default(catalog)
        again.restore(world.save())
        val back = again.bubbles[traveller.id.raw]!!
        assertEquals(bubble.time, back.time, 1e-9)
        assertEquals("waiting", 0.0, back.rate, 0.0)
        assertEquals("traveller", back.owner)
    }
}
