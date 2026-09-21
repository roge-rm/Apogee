package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldTest {

    private val catalog = StockParts.catalog
    private fun world() = World.default(catalog)
    private val dt = 1.0 / 60.0

    @Test
    fun `a craft spawns resting on the surface, not inside it or above it`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        val terra = world.attractorFor(vessel)

        val lowest = (0 until vessel.partCount).minOf { index ->
            val position = vessel.partPositionWorld(index)
            position.length - vessel.defs[index].boundsHalfExtents.length
        }
        val clearance = lowest - terra.radius

        assertTrue("craft is buried ${-clearance}m into the ground", clearance > -0.5)
        assertTrue("craft is floating ${clearance}m above the pad", clearance < 1.0)
    }

    @Test
    fun `a craft on the pad moves with the surface it is standing on`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        val terra = world.attractorFor(vessel)

        val surfaceVelocity = terra.surfaceVelocityAt(vessel.body.position)
        // Spawned at rest in the inertial frame it would be dragged off the pad
        // at a couple of hundred m/s the moment friction applied.
        assertTrue(
            "should match surface velocity, got ${vessel.body.linearVelocity} vs $surfaceVelocity",
            vessel.body.linearVelocity.approxEquals(surfaceVelocity, 1e-6),
        )
        assertTrue("equatorial surface should be moving", surfaceVelocity.length > 100.0)
    }

    @Test
    fun `an unpowered craft stays put on the pad`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        val terra = world.attractorFor(vessel)
        val startAltitude = terra.altitudeOf(vessel.body.position)

        repeat(600) { world.step(dt) }

        val endAltitude = terra.altitudeOf(vessel.body.position)
        assertTrue(
            "craft sank ${startAltitude - endAltitude}m through the pad in 10s",
            kotlin.math.abs(endAltitude - startAltitude) < 1.0,
        )
    }

    @Test
    fun `staging a decoupler splits the craft in two`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        val partsBefore = vessel.partCount

        world.stage(vessel)          // ignite
        assertEquals(1, world.vessels.size)

        world.stage(vessel)          // separate
        assertEquals("separation should create a second vessel", 2, world.vessels.size)

        assertTrue(
            "the crewed half should have shed parts ($partsBefore -> ${vessel.partCount})",
            vessel.partCount < partsBefore,
        )

        val debris = world.vessels.first { it.id != vessel.id }
        assertEquals(
            "no parts may be lost or duplicated in a split",
            partsBefore,
            vessel.partCount + debris.partCount,
        )
        assertTrue(
            "the pod must stay with the crewed half",
            vessel.design.parts.any { it.partId == "pod-mk1" },
        )
        assertTrue(
            "the spent booster must go with the debris",
            debris.design.parts.any { it.partId == "engine-lvt45" },
        )
    }

    @Test
    fun `the surviving half keeps its remaining propellant`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())

        world.stage(vessel)
        world.stage(vessel)

        assertEquals(
            "the upper stage tank should still be full",
            200.0,
            vessel.amountOf(ResourceType.PROPELLANT),
            1e-9,
        )
    }

    @Test
    fun `an orbiting craft stays in orbit`() {
        val world = world()
        val terra = world.system.body("terra")
        val radius = terra.radius + 120_000.0
        val vessel = world.spawnInOrbit(
            StockCraft.probe(catalog),
            "terra",
            Orbit.circular(radius, terra.gravitationalParameter),
        )

        val before = world.orbitOf(vessel)
        // A quarter of an orbit, integrated at the simulation's real timestep.
        val steps = (before.period / 4.0 / dt).toInt()
        repeat(steps) { world.step(dt) }
        val after = world.orbitOf(vessel)

        // The integrator is first-order, so some drift is expected; what must
        // not happen is the orbit decaying or inflating appreciably.
        assertEquals(
            "semi-major axis drifted ${after.semiMajorAxis - before.semiMajorAxis}m",
            before.semiMajorAxis,
            after.semiMajorAxis,
            before.semiMajorAxis * 1e-3,
        )
        assertTrue("should still be bound", after.isBound)
        assertTrue("should not have re-entered", after.periapsis > terra.radius)
    }

    @Test
    fun `the simulation is reproducible given the same commands`() {
        fun run(): String {
            val world = world()
            val vessel = world.spawnOnSurface(
                StockCraft.starterRocket(catalog),
                World.launchSites.first(),
            )
            world.apply(Command.Stage(vessel.id.raw))
            world.apply(Command.SetThrottle(vessel.id.raw, 1.0))
            repeat(1_200) { tick ->
                if (tick == 400) world.apply(Command.SetAttitude(vessel.id.raw, 0.3, 0.0, 0.0))
                world.step(dt)
            }
            return fingerprint(world)
        }

        assertEquals("identical inputs must produce identical state", run(), run())
    }

    @Test
    fun `commands addressed to a missing vessel are ignored rather than fatal`() {
        val world = world()
        // A stale command for a vessel that was destroyed is entirely normal
        // over a network; it must not take the server down.
        world.apply(Command.SetThrottle(9_999L, 1.0))
        world.apply(Command.Stage(9_999L))
        world.step(dt)
    }

    @Test
    fun `a snapshot round-trips every vessel`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        world.step(dt)

        val snapshot = world.snapshot()
        assertEquals(1, snapshot.vessels.size)
        assertEquals(vessel.id.raw, snapshot.vessels.first().vessel)
        assertNotNull(world.structureUpdateFor(vessel).design)
    }

    /** A stable digest of every vessel's motion, for comparing two runs. */
    private fun fingerprint(world: World): String = buildString {
        append(world.tick).append('|')
        for (vessel: Vessel in world.vessels.sortedBy { it.id.raw }) {
            append(vessel.id.raw).append(':')
            append(vessel.body.position.x).append(',')
            append(vessel.body.position.y).append(',')
            append(vessel.body.position.z).append(';')
            append(vessel.body.linearVelocity.x).append(',')
            append(vessel.body.linearVelocity.y).append(',')
            append(vessel.body.linearVelocity.z).append('|')
        }
    }
}
