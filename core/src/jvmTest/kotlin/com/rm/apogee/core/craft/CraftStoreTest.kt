package com.rm.apogee.core.craft

import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CraftStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val catalog = StockParts.catalog

    private fun store() = CraftStore(folder.newFolder("craft"))

    @Test
    fun `a saved design comes back identical`() {
        val store = store()
        val original = StockCraft.starterRocket(catalog)

        val saved = store.save(original).getOrThrow()
        val loaded = store.load(saved.fileName).getOrThrow()

        assertEquals(original, loaded)
    }

    @Test
    fun `saving twice overwrites instead of duplicating`() {
        val store = store()
        val design = StockCraft.starterRocket(catalog)
        store.save(design).getOrThrow()
        store.save(design.copy(stages = emptyList())).getOrThrow()

        assertEquals(1, store.list().size)
    }

    @Test
    fun `the list reports what's on disk`() {
        val store = store()
        store.save(StockCraft.starterRocket(catalog).copy(name = "Alpha")).getOrThrow()
        store.save(StockCraft.probe(catalog).copy(name = "Beta")).getOrThrow()

        val names = store.list().map { it.name }.toSet()
        assertEquals(setOf("Alpha", "Beta"), names)
        assertEquals(1, store.list().first { it.name == "Beta" }.partCount)
    }

    @Test
    fun `craft names that aren't safe filenames still save`() {
        val store = store()
        // Names can hold slashes and quotes.
        val design = StockCraft.probe(catalog).copy(name = "Apollo / 11: \"Eagle\"")

        val saved = store.save(design).getOrThrow()
        assertFalse("path separators must not survive", saved.fileName.contains("/"))
        assertEquals(design, store.load(saved.fileName).getOrThrow())
        assertEquals("the display name is unchanged", "Apollo / 11: \"Eagle\"", store.list().first().name)
    }

    @Test
    fun `a corrupt file doesn't hide the others`() {
        val store = store()
        store.save(StockCraft.probe(catalog).copy(name = "Good")).getOrThrow()
        folder.root.walkTopDown().first { it.isDirectory && it.name == "craft" }
            .resolve("broken.craft").writeText("{ not json")

        val listed = store.list()
        assertEquals("the good craft should still be listed", 1, listed.size)
        assertEquals("Good", listed.first().name)
    }

    @Test
    fun `deleting removes it from the list`() {
        val store = store()
        val saved = store.save(StockCraft.probe(catalog).copy(name = "Doomed")).getOrThrow()
        assertTrue(store.delete(saved.fileName))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `loading a missing file fails without throwing`() {
        assertTrue(store().load("nothing.craft").isFailure)
    }

    // --- stock designs -------------------------------------------------------

    private fun names(store: CraftStore) = store.list().map { it.name }.toSet()

    /** The craft built from the vehicle kits, offered from 0.3.5. */
    private val KIT = setOf(
        "Sparrow", "Buggy", "Hauler", "Skiff", "Sloop", "Jet Boat", "Jet Ski", "Hummingbird", "Quad", "Skylark", "Zeppelin", "Sky Platform", "Sea Platform", "Cutter", "Port Tug", "Dock Probe", "Tow Buggy", "Cart", "Trawler",
        "Coaster", "Electric Coaster", "Schooner", "Deck Barge", "Harbour Tug", "Petrel", "Landing Barge", "Flat Top",
        "Base Core", "Pad Base", "Sea Floor Base", "Base Core Hauler", "Module Hauler", "Depot Hauler", "Base Core Lander", "Moonshot",
        "Mote Probe", "Prospector", "Surveyor", "Sounder", "Minnow", "Nautilus", "Abyss",
    )

    @Test
    fun `a new store gets every stock design`() {
        val store = store()
        store.seedStockDesigns(catalog)
        assertEquals(
            setOf("Starter I", "Stilt Lander", "Stilt Tug", "Trundler", "Plank", "Punt") + KIT,
            names(store),
        )
    }

    @Test
    fun `a deleted stock design stays deleted`() {
        val store = store()
        store.seedStockDesigns(catalog)
        store.delete(store.list().first { it.name == "Plank" }.fileName)
        store.seedStockDesigns(catalog)
        assertFalse("Plank" in names(store))
    }

    /** A store seeded by the old empty-store rule: it had the first three and one was deleted. */
    @Test
    fun `an old store gets the new designs and not the deleted old one`() {
        val store = store()
        store.save(StockCraft.starterRocket(catalog))
        store.save(StockCraft.lander(catalog))
        store.save(StockCraft.aeroplane(catalog).copy(name = "Mine"))

        store.seedStockDesigns(catalog)
        assertEquals(
            setOf("Starter I", "Stilt Lander", "Mine", "Trundler", "Plank", "Punt") + KIT,
            names(store),
        )
    }

    @Test
    fun `a player's craft is never overwritten by a stock one`() {
        val store = store()
        val mine = StockCraft.probe(catalog).copy(name = "Plank")
        store.save(mine)
        store.seedStockDesigns(catalog)
        assertEquals(mine, store.load(store.list().first { it.name == "Plank" }.fileName).getOrThrow())
    }

    @Test
    fun `orientation survives a save`() {
        val store = store()
        val plane = StockCraft.aeroplane(catalog)
        val loaded = store.load(store.save(plane).getOrThrow().fileName).getOrThrow()
        assertEquals(CraftOrientation.HORIZONTAL, loaded.orientation)
    }
}
