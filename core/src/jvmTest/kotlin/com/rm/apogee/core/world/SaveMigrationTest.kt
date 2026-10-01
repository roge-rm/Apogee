package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Old saves, read by a newer game. A renamed part would otherwise fail validation and the craft
 * would be dropped quietly. The rename tables are empty today; these test the mechanism anyway.
 */
class SaveMigrationTest {

    private val catalog = StockParts.catalog
    private val site = World.launchSites.first()

    @Test
    fun `a renamed part is carried forward instead of losing the craft`() {
        val old = CraftDesign(
            name = "Old Faithful",
            parts = listOf(PlacedPart("pod-halo-legacy", Vec3.zero())),
        )
        val result = SaveMigration.migrate(
            old, catalog, renames = mapOf("pod-halo-legacy" to "pod-halo"),
        )

        assertNotNull("the craft should survive a rename", result.design)
        assertEquals("pod-halo", result.design!!.parts.first().partId)
        assertTrue(
            "and it should say what it did: ${result.notes}",
            result.notes.any { it.contains("pod-halo-legacy") && it.contains("pod-halo") },
        )
    }

    @Test
    fun `a retired part loses the craft, by name and with a reason`() {
        val old = CraftDesign(
            name = "Obsolete",
            parts = listOf(PlacedPart("engine-gone", Vec3.zero())),
        )
        val result = SaveMigration.migrate(
            old, catalog, retired = mapOf("engine-gone" to "retired in 0.4"),
        )

        assertNull("it cannot be assembled", result.design)
        assertTrue(
            "the reason should name the part and say why: ${result.notes}",
            result.notes.any { it.contains("engine-gone") && it.contains("retired in 0.4") },
        )
    }

    @Test
    fun `an unknown part is reported even without an entry for it`() {
        val old = CraftDesign(
            name = "Mystery",
            parts = listOf(PlacedPart("never-existed", Vec3.zero())),
        )
        val result = SaveMigration.migrate(old, catalog)

        assertNull(result.design)
        assertTrue(
            "the part should be named: ${result.notes}",
            result.notes.any { it.contains("never-existed") },
        )
    }

    @Test
    fun `a current craft passes through untouched and unremarked`() {
        val design = StockCraft.lander(catalog)
        val result = SaveMigration.migrate(design, catalog)

        assertEquals("nothing should have changed", design, result.design)
        assertTrue("and nothing worth saying: ${result.notes}", result.notes.isEmpty())
    }

    /** One bad craft mustn't stop the world loading. */
    @Test
    fun `one craft that can't load doesn't take the rest of the world with it`() {
        val world = World.default(catalog)
        world.spawnOnSurface(StockCraft.lander(catalog), site).owner = "Pilot"
        world.spawnOnSurface(StockCraft.starterRocket(catalog), site, pad = 1)

        // Corrupt one craft's design the way a retired part would.
        val save = world.save()
        val broken = save.vessels.first().let { first ->
            VesselSave(
                id = first.id,
                name = first.name,
                owner = first.owner,
                referenceBodyId = first.referenceBodyId,
                design = first.design.copy(
                    parts = listOf(PlacedPart("never-existed", Vec3.zero())),
                ),
                position = first.position,
                rotation = first.rotation,
                velocity = first.velocity,
                angularVelocity = first.angularVelocity,
            )
        }
        val damaged = WorldSave(
            catalogHash = save.catalogHash,
            universeTime = save.universeTime,
            nextVesselId = save.nextVesselId,
            vessels = listOf(broken) + save.vessels.drop(1),
        )

        val restored = World.default(catalog)
        val problems = restored.restore(damaged)

        assertEquals("the other craft should still be there", 1, restored.vessels.size)
        assertTrue(
            "and the loss should be reported by name: $problems",
            problems.any { it.contains("never-existed") },
        )
    }

    @Test
    fun `a save from a newer build is refused instead of half read`() {
        val world = World.default(catalog)
        world.spawnOnSurface(StockCraft.lander(catalog), site)
        val save = world.save()
        val fromTheFuture = WorldSave(
            formatVersion = WorldSave.FORMAT_VERSION + 1,
            catalogHash = save.catalogHash,
            universeTime = save.universeTime,
            nextVesselId = save.nextVesselId,
            vessels = save.vessels,
        )

        val restored = World.default(catalog)
        val problems = restored.restore(fromTheFuture)

        assertTrue("it should refuse: $problems", problems.isNotEmpty())
        assertEquals("and load nothing", 0, restored.vessels.size)
    }
}
