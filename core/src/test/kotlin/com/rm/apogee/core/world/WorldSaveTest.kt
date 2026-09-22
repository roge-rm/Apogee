package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldSaveTest {

    private val catalog = StockParts.catalog
    private val format = Json { prettyPrint = true; classDiscriminator = "type" }
    private val dt = 1.0 / 60.0

    /** A world with one craft that has flown for a bit. */
    private fun flownWorld(): World {
        val world = World.default(catalog)
        val vessel = world.spawnOnSurface(
            StockCraft.starterRocket(catalog),
            World.launchSites.first(),
        )
        vessel.owner = "Alice"
        world.apply(Command.Stage(vessel.id.raw))
        world.apply(Command.SetThrottle(vessel.id.raw, 1.0))
        repeat(600) { world.step(dt) }
        return world
    }

    @Test
    fun `a world round-trips through a save`() {
        val original = flownWorld()
        val save = original.save()

        val restored = World.default(catalog)
        val problems = restored.restore(save)
        assertTrue("restore reported problems: $problems", problems.isEmpty())

        assertEquals(original.time, restored.time, 0.0)
        assertEquals(original.vessels.size, restored.vessels.size)

        val before = original.vessels.first()
        val after = restored.vessels.first()
        assertTrue(
            "position drifted: ${before.body.position} vs ${after.body.position}",
            before.body.position.approxEquals(after.body.position, 1e-9),
        )
        assertTrue(before.body.linearVelocity.approxEquals(after.body.linearVelocity, 1e-9))
        assertEquals(before.name, after.name)
        assertEquals(before.owner, after.owner)
        assertEquals(before.currentStage, after.currentStage)
    }

    @Test
    fun `propellant levels survive a save`() {
        val original = flownWorld()
        val burned = original.vessels.first().amountOf(ResourceType.PROPELLANT)
        assertTrue("the craft should have burned some fuel", burned < 1_400.0)

        val restored = World.default(catalog)
        restored.restore(original.save())

        assertEquals(
            "a reloaded craft must not refill its tanks",
            burned,
            restored.vessels.first().amountOf(ResourceType.PROPELLANT),
            1e-9,
        )
    }

    @Test
    fun `a reloaded world keeps flying from where it stopped`() {
        val original = flownWorld()
        val restored = World.default(catalog)
        restored.restore(original.save())

        // Same inputs from the same state must give the same result.
        repeat(300) { original.step(dt); restored.step(dt) }

        assertTrue(
            "a reloaded world diverged from one that never stopped " +
                "(${original.vessels.first().body.position} vs " +
                "${restored.vessels.first().body.position})",
            original.vessels.first().body.position
                .approxEquals(restored.vessels.first().body.position, 1e-6),
        )
    }

    @Test
    fun `a throttle setting survives but a held stick does not`() {
        val world = World.default(catalog)
        val vessel = world.spawnOnSurface(
            StockCraft.starterRocket(catalog),
            World.launchSites.first(),
        )
        world.apply(Command.Stage(vessel.id.raw))
        world.apply(Command.SetThrottle(vessel.id.raw, 0.75))
        world.apply(Command.SetSas(vessel.id.raw, true))
        world.apply(Command.SetAttitude(vessel.id.raw, 1.0, -1.0, 0.5))
        repeat(60) { world.step(dt) }

        val restored = World.default(catalog)
        restored.restore(world.save())
        val after = restored.vessels.first().control

        // A throttle is a lever someone set and left.
        assertEquals(0.75, after.throttle, 1e-9)
        assertTrue("stability assist is a mode too", after.sasEnabled)

        // A stick is being held. Resuming it would have the craft rotating on
        // its own with nobody touching the controls.
        assertEquals("pitch", 0.0, after.pitch, 0.0)
        assertEquals("yaw", 0.0, after.yaw, 0.0)
        assertEquals("roll", 0.0, after.roll, 0.0)
    }

    @Test
    fun `a save is readable JSON that round-trips`() {
        val save = flownWorld().save()
        val text = format.encodeToString(save)
        val decoded = format.decodeFromString<WorldSave>(text)

        assertTrue("a save should be human-readable", text.contains("\"universeTime\""))
        assertEquals(save.universeTime, decoded.universeTime, 0.0)
        assertEquals(save.vessels.size, decoded.vessels.size)
        assertEquals(save.vessels.first().owner, decoded.vessels.first().owner)
    }

    @Test
    fun `a save from a future format is refused rather than half-loaded`() {
        val save = flownWorld().save()
        val future = WorldSave(
            formatVersion = WorldSave.FORMAT_VERSION + 1,
            catalogHash = save.catalogHash,
            universeTime = save.universeTime,
            nextVesselId = save.nextVesselId,
            vessels = save.vessels,
        )

        val world = World.default(catalog)
        val problems = world.restore(future)
        assertTrue("should refuse: $problems", problems.isNotEmpty())
        assertTrue(problems.first().contains("format"))
        assertTrue("and load nothing", world.vessels.isEmpty())
    }

    @Test
    fun `a craft using parts this build lacks is skipped and reported`() {
        val save = flownWorld().save()
        val broken = save.vessels.first().let { vessel ->
            VesselSave(
                id = vessel.id,
                name = "Ghost",
                owner = vessel.owner,
                design = vessel.design.copy(
                    parts = vessel.design.parts.map { it.copy(partId = "part-that-left") },
                ),
                referenceBodyId = vessel.referenceBodyId,
                position = vessel.position,
                rotation = vessel.rotation,
                velocity = vessel.velocity,
                angularVelocity = vessel.angularVelocity,
            )
        }

        val world = World.default(catalog)
        val problems = world.restore(
            WorldSave(
                catalogHash = save.catalogHash,
                universeTime = save.universeTime,
                nextVesselId = save.nextVesselId,
                vessels = save.vessels + broken,
            )
        )

        assertEquals("the good craft should still load", 1, world.vessels.size)
        assertTrue(
            "and the lost one should be named: $problems",
            problems.any { it.contains("Ghost") },
        )
    }

    @Test
    fun `ids handed out after a load do not collide with loaded ones`() {
        val original = flownWorld()
        val world = World.default(catalog)
        world.restore(original.save())

        val existing = world.vessels.map { it.id.raw }.toSet()
        val fresh = world.spawnOnSurface(StockCraft.probe(catalog), World.launchSites.first())

        assertTrue(
            "a new craft reused id ${fresh.id.raw}",
            fresh.id.raw !in existing,
        )
    }

    @Test
    fun `a returning player is matched to their craft`() {
        val original = flownWorld()
        val world = World.default(catalog)
        world.restore(original.save())

        assertNotNull("Alice's craft should still be hers", world.vesselOwnedBy("Alice"))
        assertNotNull("and matching should not care about case", world.vesselOwnedBy("alice"))
        assertNull(world.vesselOwnedBy("Bob"))
        assertNull("nobody owns the unowned", world.vesselOwnedBy(""))
    }

    @Test
    fun `resource slots are stored positionally in a pinned order`() {
        // The save format writes resource levels by position. Reordering the
        // enum would silently reinterpret every saved craft's fuel as
        // something else, so the order is part of the format.
        assertEquals(
            listOf("PROPELLANT", "MONOPROPELLANT", "ELECTRIC_CHARGE"),
            ResourceType.entries.map { it.name },
        )
        assertEquals(ResourceType.entries.size, WorldSave.RESOURCE_SLOTS)
    }
}
