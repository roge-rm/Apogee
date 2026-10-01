package com.rm.apogee.core.craft

import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VesselTest {

    private val catalog = StockParts.catalog

    private fun starter(): Vessel = Vessel(
        id = VesselId(1),
        design = StockCraft.starterRocket(catalog),
        defs = StockCraft.starterRocket(catalog).parts.map { catalog.require(it.partId) },
        referenceBodyId = "terra",
    )

    @Test
    fun `tanks start full`() {
        val vessel = starter()
        // Three Cask-4s below and one above, at 400 units each.
        assertEquals(1_600.0, vessel.amountOf(ResourceType.PROPELLANT), 1e-9)
    }

    @Test
    fun `a decoupler blocks crossfeed between stages`() {
        val vessel = starter()
        val design = vessel.design

        val mainEngine = design.parts.indexOfFirst { it.partId == "engine-ember" }
        val upperEngine = design.parts.indexOfFirst { it.partId == "engine-vesper" }
        assertTrue(mainEngine >= 0 && upperEngine >= 0)

        val lowerAvailable = vessel.amountInGroupOf(mainEngine, ResourceType.PROPELLANT)
        val upperAvailable = vessel.amountInGroupOf(upperEngine, ResourceType.PROPELLANT)

        // Without crossfeed groups the first stage drinks the upper stage dry.
        assertEquals("first stage should reach only its own three tanks", 1_200.0, lowerAvailable, 1e-9)
        assertEquals("upper stage should reach only its own tank", 400.0, upperAvailable, 1e-9)
    }

    @Test
    fun `draining only touches the reachable group`() {
        val vessel = starter()
        val mainEngine = vessel.design.parts.indexOfFirst { it.partId == "engine-ember" }
        val upperEngine = vessel.design.parts.indexOfFirst { it.partId == "engine-vesper" }

        vessel.drainFromGroupOf(mainEngine, ResourceType.PROPELLANT, 600.0)

        assertEquals(600.0, vessel.amountInGroupOf(mainEngine, ResourceType.PROPELLANT), 1e-9)
        assertEquals(
            "the upper stage must be untouched",
            400.0,
            vessel.amountInGroupOf(upperEngine, ResourceType.PROPELLANT),
            1e-9,
        )
    }

    @Test
    fun `draining more than exists returns only what was there`() {
        val vessel = starter()
        val mainEngine = vessel.design.parts.indexOfFirst { it.partId == "engine-ember" }
        val drawn = vessel.drainFromGroupOf(mainEngine, ResourceType.PROPELLANT, 99_999.0)
        assertEquals(1_200.0, drawn, 1e-9)
        assertEquals(0.0, vessel.amountInGroupOf(mainEngine, ResourceType.PROPELLANT), 1e-9)
    }

    @Test
    fun `centre of mass climbs as the lower stage drains`() {
        val vessel = starter()
        val before = vessel.centerOfMass().y

        val mainEngine = vessel.design.parts.indexOfFirst { it.partId == "engine-ember" }
        vessel.drainFromGroupOf(mainEngine, ResourceType.PROPELLANT, 1_200.0)
        vessel.recomputeMass(shiftBodyPosition = false)

        val after = vessel.centerOfMass().y
        assertTrue(
            "emptying the lower tanks should raise the centre of mass ($before -> $after)",
            after > before + 0.5,
        )
    }

    @Test
    fun `mass drops by exactly the propellant burned`() {
        val vessel = starter()
        val before = vessel.body.mass

        val mainEngine = vessel.design.parts.indexOfFirst { it.partId == "engine-ember" }
        vessel.drainFromGroupOf(mainEngine, ResourceType.PROPELLANT, 100.0)
        vessel.recomputeMass(shiftBodyPosition = false)

        // 100 units at 5 kg per unit.
        assertEquals(before - 500.0, vessel.body.mass, 1e-6)
    }

    @Test
    fun `staging activates the listed parts`() {
        val vessel = starter()
        val mainEngine = vessel.design.parts.indexOfFirst { it.partId == "engine-ember" }

        assertTrue(vessel.activeEngines().isEmpty())
        vessel.activateNextStage()
        assertEquals(listOf(mainEngine), vessel.activeEngines())
    }
}
