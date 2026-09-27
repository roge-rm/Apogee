package com.rm.apogee.core.craft

import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every stock craft sorts under the kind you'd look for it under. */
class CraftKindTest {

    private val catalog = StockParts.catalog

    @Test
    fun `the stock craft sort the way you'd expect`() {
        val expected = mapOf(
            CraftKind.ROCKET to listOf(
                StockCraft.sounder(catalog), StockCraft.starterRocket(catalog), StockCraft.moteProbe(catalog),
                StockCraft.lander(catalog), StockCraft.moonshot(catalog), StockCraft.moduleTug(catalog),
                StockCraft.prospector(catalog), StockCraft.surveyor(catalog), StockCraft.dockProbe(catalog),
                // A lander with a docking ring, for putting bases together.
                StockCraft.portTug(catalog),
            ),
            CraftKind.PLANE to listOf(StockCraft.aeroplane(catalog), StockCraft.sparrow(catalog)),
            CraftKind.ROVER to listOf(
                StockCraft.rover(catalog), StockCraft.buggy(catalog), StockCraft.hauler(catalog),
                StockCraft.towBuggy(catalog), StockCraft.cart(catalog),
            ),
            CraftKind.BOAT to listOf(
                StockCraft.boat(catalog), StockCraft.skiff(catalog), StockCraft.sloop(catalog),
                StockCraft.cutter(catalog), StockCraft.trawler(catalog),
            ),
            CraftKind.SUB to listOf(StockCraft.minnow(catalog), StockCraft.nautilus(catalog), StockCraft.abyss(catalog)),
            CraftKind.BASE to listOf(
                StockCraft.baseCore(catalog), StockCraft.baseCoreLander(catalog), StockCraft.padBase(catalog),
                StockCraft.moduleHauler(catalog), StockCraft.baseCoreHauler(catalog), StockCraft.depotHauler(catalog),
            ),
        )
        val wrong = expected.flatMap { (kind, designs) ->
            designs.mapNotNull { d -> CraftKind.of(d, catalog).takeIf { it != kind }?.let { "${d.name}: $it, not $kind" } }
        }
        assertEquals(emptyList<String>(), wrong)
    }
}
