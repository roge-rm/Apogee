package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorldStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val catalog = StockParts.catalog

    private fun world(): World {
        val world = World.default(catalog)
        world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
            .owner = "Alice"
        repeat(120) { world.step(1.0 / 60.0) }
        return world
    }

    private fun store() = WorldStore(File(folder.newFolder("state"), "world.json"))

    @Test
    fun `a world survives a save and load`() {
        val store = store()
        assertFalse("nothing saved yet", store.exists)

        store.save(world().save()).getOrThrow()
        assertTrue(store.exists)

        val loaded = store.load().getOrThrow()
        assertEquals(1, loaded.vessels.size)
        assertEquals("Alice", loaded.vessels.first().owner)
    }

    @Test
    fun `saving twice keeps one step back`() {
        val store = store()
        val first = world()
        store.save(first.save()).getOrThrow()

        val second = world()
        repeat(600) { second.step(1.0 / 60.0) }
        store.save(second.save()).getOrThrow()

        val (loaded, warning) = store.loadWithFallback()!!
        assertNull(warning)
        assertEquals(second.time, loaded.universeTime, 1e-9)
    }

    @Test
    fun `a corrupt save falls back to the backup`() {
        val store = store()
        val original = world()
        store.save(original.save()).getOrThrow()
        // A second save is what creates the backup of the first.
        store.save(original.save()).getOrThrow()

        // Something truncated the live file.
        File(store.path).writeText("{ this is not a world")

        val (loaded, warning) = store.loadWithFallback()!!
        assertNotNull("should say it fell back: $warning", warning)
        assertEquals(
            "and the recovered world should be the real one",
            original.vessels.size,
            loaded.vessels.size,
        )
    }

    @Test
    fun `an empty directory loads as nothing instead of failing`() {
        assertNull("a first run has no world to load", store().loadWithFallback())
    }

    @Test
    fun `a failed load doesn't throw`() {
        val store = store()
        File(store.path).parentFile.mkdirs()
        File(store.path).writeText("nonsense")
        assertTrue(store.load().isFailure)
    }
}
