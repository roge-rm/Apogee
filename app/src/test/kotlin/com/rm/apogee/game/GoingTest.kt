package com.rm.apogee.game

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Test

/** The stock craft get the words you'd use for them: you sail the Sloop, you don't fly it. */
class GoingTest {

    private val catalog = StockParts.catalog

    @Test
    fun `each kind of craft gets its own verb`() {
        val expected = mapOf(
            Going.FLY to listOf(
                StockCraft.starterRocket(catalog), StockCraft.aeroplane(catalog), StockCraft.hummingbird(catalog),
                StockCraft.zeppelin(catalog), StockCraft.baseCoreLander(catalog), StockCraft.skyPlatform(catalog),
            ),
            Going.DRIVE to listOf(StockCraft.rover(catalog), StockCraft.hauler(catalog), StockCraft.baseCoreHauler(catalog)),
            Going.SAIL to listOf(StockCraft.sloop(catalog), StockCraft.trawler(catalog), StockCraft.seaPlatform(catalog)),
            Going.DIVE to listOf(StockCraft.minnow(catalog)),
        )
        for ((going, designs) in expected) for (design in designs) {
            assertEquals(design.name, going, Going.of(design, catalog, anchored = false))
        }
    }

    @Test
    fun `a founded base is visited and someone in a suit walks`() {
        assertEquals(Going.VISIT, Going.of(StockCraft.seaPlatform(catalog), catalog, anchored = true))
        val suit = com.rm.apogee.core.craft.CraftDesign(
            "Hugo", listOf(com.rm.apogee.core.craft.PlacedPart(World.SUIT_PART, com.rm.apogee.core.math.Vec3.zero())),
        )
        assertEquals(Going.WALK, Going.of(suit, catalog, anchored = false))
    }

    @Test
    fun `gas craft float and everything else flies`() {
        assertEquals("Floating", Going.aloft(StockCraft.zeppelin(catalog), catalog))
        assertEquals("Flying", Going.aloft(StockCraft.hummingbird(catalog), catalog))
    }
}
