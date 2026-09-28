package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** A probe's link home: straight to a ground station, through relays, or none. */
class CommsTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** [root] with [extras] on its side. */
    private fun craft(root: String, vararg extras: String) = CraftDesign(
        name = "Test $root",
        parts = listOf(PlacedPart(root, Vec3(0.0, 0.0, 0.0))) +
            extras.mapIndexed { k, id -> PlacedPart(id, Vec3(0.7, 0.0, 0.0).also { if (k % 2 == 1) it.mulInPlace(-1.0) }, parentIndex = 0) },
        stages = emptyList(),
        manualStaging = true,
        catalogHash = catalog.contentHash,
    )

    /** Terra to Luna, as a unit vector, now, and a direction square to it. */
    private fun lunaward(world: World): Pair<Vec3, Vec3> {
        val toward = world.system.positionOf("luna", world.time).subInPlace(world.system.positionOf("terra", world.time)).normalizeInPlace()
        val across = toward.cross(Vec3.unitY()).normalizeInPlace()
        return toward to across
    }

    /** [design] parked [offset] from Luna's centre, drifting with it. */
    private fun nearLuna(world: World, design: CraftDesign, offset: Vec3): Vessel {
        val luna = world.system.body("luna")
        val speed = sqrt(luna.gravitationalParameter / offset.length)
        val along = offset.cross(Vec3.unitY()).normalizeInPlace().mulInPlace(speed)
        return world.spawnAt(design, "luna", offset, along, Quat.identity())
    }

    private fun throttleHeld(world: World, v: Vessel): Boolean {
        world.apply(Command.SetThrottle(v.id.raw, 0.5))
        val held = v.control.throttle == 0.5
        v.control.throttle = 0.0
        return held
    }

    @Test
    fun `a probe over the Cape hears it directly and flies`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val up = terra.rotationAt(world.time).rotate(SolarSystem.surfaceDirection(SolarSystem.PAD_LATITUDE, SolarSystem.PAD_LONGITUDE), Vec3())
        val r = terra.radius + 100_000.0
        val along = up.cross(Vec3.unitY()).normalizeInPlace().mulInPlace(sqrt(terra.gravitationalParameter / r))
        val probe = world.spawnAt(craft("probe-mote"), "terra", up.mulInPlace(r), along, Quat.identity())
        assertTrue("its throttle not taken", throttleHeld(world, probe))
        assertEquals(Signal.DIRECT, probe.signal)
        assertTrue(world.controllable(probe))
    }

    @Test
    fun `behind Luna a probe hears nothing and can't be flown, unless a relay sees both`() {
        val world = World.default(catalog)
        val luna = world.system.body("luna")
        val (toward, across) = lunaward(world)
        val probe = nearLuna(world, craft("probe-mote", "antenna-reed"), toward.copy().mulInPlace(luna.radius + 50_000.0))
        assertFalse("flown from behind Luna", throttleHeld(world, probe))
        assertEquals(Signal.NONE, probe.signal)
        assertFalse(world.controllable(probe))

        // A relay off to one side, in sight of the probe and of Terra.
        val relay = nearLuna(
            world, craft("probe-mote", "dish-beacon"),
            toward.copy().mulInPlace(1_000_000.0).addScaledInPlace(across, 2_000_000.0),
        )
        // Out here with its dish folded it can't hear being told, so it's as if it were unfolded
        // near home.
        world.apply(Command.Deploy(relay.id.raw, true))
        assertFalse(relay.control.deployed)
        relay.control.deployed = true
        repeat((6.0 / dt).toInt()) { world.step(dt) }
        assertTrue("the dish not out", Power.deployed(relay, relay.defs.indexOfFirst { it.id == "dish-beacon" }))
        assertTrue("not flown through the relay", throttleHeld(world, probe))
        assertEquals(Signal.RELAYED, probe.signal)
        assertEquals(listOf(relay.id.raw), probe.signalPath)

        // A relay out of charge relays nothing.
        relay.drawCharge(relay.amountOf(ResourceType.ELECTRIC_CHARGE))
        repeat((2.0 / dt).toInt()) { world.step(dt) }
        assertFalse(relay.powered)
        assertEquals(Signal.NONE, probe.signal)
        assertFalse(throttleHeld(world, probe))
    }

    @Test
    fun `a crewed pod behind Luna needs no signal`() {
        val world = World.default(catalog)
        val luna = world.system.body("luna")
        val (toward, _) = lunaward(world)
        val pod = nearLuna(world, craft("pod-halo"), toward.copy().mulInPlace(luna.radius + 50_000.0))
        assertTrue(throttleHeld(world, pod))
        assertTrue(world.controllable(pod))
    }

    @Test
    fun `a flat probe can't be flown even in sight of home`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val up = terra.rotationAt(world.time).rotate(SolarSystem.surfaceDirection(SolarSystem.PAD_LATITUDE, SolarSystem.PAD_LONGITUDE), Vec3())
        val r = terra.radius + 100_000.0
        val along = up.cross(Vec3.unitY()).normalizeInPlace().mulInPlace(sqrt(terra.gravitationalParameter / r))
        val probe = world.spawnAt(craft("probe-mote"), "terra", up.mulInPlace(r), along, Quat.identity())
        probe.drawCharge(probe.amountOf(ResourceType.ELECTRIC_CHARGE))
        repeat(10) { world.step(dt) }
        assertFalse(probe.powered)
        assertFalse(throttleHeld(world, probe))
    }
}
