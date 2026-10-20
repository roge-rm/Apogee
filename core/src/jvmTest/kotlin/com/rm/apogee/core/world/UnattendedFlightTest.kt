package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Atmosphere
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.*
import org.junit.Test

class UnattendedFlightTest {
    private val catalog = StockParts.catalog
    private val pod = CraftDesign("Pod", listOf(PlacedPart("pod-halo", Vec3.zero())))

    private fun world(): World = World(SolarSystem(listOf(CelestialBody(
        "terra", "Terra", 3.5316e12, 600_000.0, 21_549.425, Atmosphere(),
    )), "terra"), catalog)

    private fun spawn(world: World, altitude: Double, across: Double = 0.0): Vessel {
        val body = world.system.body("terra")
        val position = Vec3(body.radius + altitude, 0.0, 0.0)
        val velocity = body.surfaceVelocityAt(position, Vec3()).addInPlace(Vec3(0.0, across, 0.0))
        return world.spawnAt(pod, body.id, position, velocity, Quat.identity())
    }

    @Test
    fun `the reported save allows a hundred times warp while Wayfarer is flown`() {
        val text = javaClass.getResource("/unattended-warp.json")!!.readText()
        val world = World.default(catalog)
        world.restore(WorldStore.decode(text))
        assertEquals("old rule reproduces the cap", 4.0, world.maxWarp(), 0.0)
        world.setHumanPilots(setOf(76L))
        assertEquals(100.0, world.maxWarp(), 0.0)
        assertEquals(VesselId(76), world.warpLimiter()!!.id)
        assertNull(world.movingWarpLimiter())
        assertEquals(10.0, world.advanceOnRails(10.0), 1e-8)
        assertNotNull("Wayfarer survives", world.vessel(VesselId(76)))
        assertTrue("parked Sparrow sleeps", world.vessel(VesselId(38))?.dormant == true)
        assertTrue("parked Starter I remains asleep", world.vessel(VesselId(55))!!.dormant)
        assertEquals(100.0, world.maxWarp(), 0.0)
    }

    @Test
    fun `crew and SAS do not count as a human pilot and abandoned inputs are released`() {
        val world = world()
        val craft = spawn(world, 1_000.0)
        assertTrue(craft.crewAboard > 0)
        craft.control.sasEnabled = true
        craft.control.throttle = 1.0
        craft.control.pitch = 0.7
        craft.control.translateX = 1.0
        world.setHumanPilots(setOf(craft.id.raw))
        assertEquals(4.0, world.maxWarp(), 0.0)
        world.setHumanPilots(emptySet())
        assertEquals(1_000_000.0, world.maxWarp(), 0.0)
        assertEquals(0.0, craft.control.throttle, 0.0)
        assertEquals(0.0, craft.control.pitch, 0.0)
        assertEquals(0.0, craft.control.translateX, 0.0)
    }

    @Test
    fun `powered flight computers keep limiting warp but SAS alone does not`() {
        val world = world()
        val craft = spawn(world, 1_000.0)
        world.setHumanPilots(emptySet())
        val controls = listOf<(Boolean) -> Unit>(
            { craft.control.autoBurn = it }, { craft.control.autoLand = it },
            { craft.control.cruise = it }, { craft.control.keeping = it },
            { craft.control.holdDepth = it },
        )
        for (set in controls) {
            set(true)
            craft.powered = true
            craft.control.throttle = 0.7
            craft.control.pitch = 0.4
            world.setHumanPilots(emptySet())
            assertEquals("autopilot retains its throttle", 0.7, craft.control.throttle, 0.0)
            assertEquals("former pilot's stick is released", 0.0, craft.control.pitch, 0.0)
            assertEquals(4.0, world.maxWarp(), 0.0)
            craft.powered = false
            assertEquals(1_000_000.0, world.maxWarp(), 0.0)
            set(false)
        }
    }

    @Test
    fun `unattended craft fall at normal speed and during warp instead of freezing`() {
        for (rails in listOf(false, true)) {
            val world = world()
            val craft = spawn(world, 1_000.0)
            world.setHumanPilots(emptySet())
            if (rails) world.advanceOnRails(5.0) else repeat(300) { world.step(1.0 / 60.0) }
            assertNotNull(world.vessel(craft.id))
            assertTrue("falls during rails=$rails", world.attractorFor(craft).altitudeOf(craft.body.position) < 950.0)
            assertFalse("not pinned in mid-air", craft.dormant)
            assertEquals(1_000_000.0, world.maxWarp(), 0.0)
        }
    }

    @Test
    fun `an unattended falling craft crashes without stopping warp or passing through terrain`() {
        val world = world()
        val craft = spawn(world, 1_000.0, 100.0)
        world.setHumanPilots(emptySet())
        assertEquals(300.0, world.advanceOnRails(300.0), 1e-8)
        assertNull(world.vessel(craft.id))
    }

    @Test
    fun `parked craft sleep and survive warp and wake when a pilot takes over`() {
        val world = world()
        val craft = spawn(world, 1.0)
        world.setDown(craft)
        world.setHumanPilots(emptySet())
        world.advanceOnRails(30.0)
        assertNotNull(world.vessel(craft.id))
        assertTrue(craft.dormant)
        assertTrue(craft.touchingGround)
        world.setHumanPilots(setOf(craft.id.raw))
        world.apply(Command.SetThrottle(craft.id.raw, 0.5))
        assertFalse(craft.dormant)
        assertEquals(4.0, world.maxWarp(), 0.0)
    }

    @Test
    fun `unattended satellites continue around their orbits`() {
        val world = world()
        val body = world.system.body("terra")
        val orbit = Orbit.circular(body.radius + 250_000.0, body.gravitationalParameter)
        val craft = world.spawnAt(pod, body.id, orbit.position, orbit.velocity, Quat.identity(), seat = false)
        val expected = orbit.propagate(600.0)
        world.setHumanPilots(emptySet())
        world.advanceOnRails(600.0)
        assertNotNull(world.vessel(craft.id))
        assertTrue(craft.body.position.distanceTo(expected.position) < 0.01)
        assertTrue(craft.body.linearVelocity.distanceTo(expected.velocity) < 0.001)
    }

    @Test
    fun `an open canopy stays numerically stable during a background descent`() {
        val world = world()
        val body = world.system.body("terra")
        val position = Vec3(body.radius + 1_000.0, 0.0, 0.0)
        val velocity = body.surfaceVelocityAt(position, Vec3()).addInPlace(Vec3(0.0, 3_000.0, 0.0))
        com.rm.apogee.core.orbit.Descent.advance(body, position, velocity, 0.0, 1_000.0, 5_000.0, 5.0)
        assertTrue(position.length.isFinite())
        assertTrue(velocity.length.isFinite())
        assertTrue(body.surfaceVelocityAt(position, Vec3()).subInPlace(velocity).length < 100.0)
        assertTrue(body.altitudeOf(position) in 900.0..1_001.0)
    }
}
