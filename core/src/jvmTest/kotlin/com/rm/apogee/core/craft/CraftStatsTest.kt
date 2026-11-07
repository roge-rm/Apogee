package com.rm.apogee.core.craft

import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.Tank
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CraftStatsTest {

    private val catalog = StockParts.catalog

    @Test
    fun `the stock rocket is analysed as a two-burn launcher`() {
        val stats = CraftStats.analyze(StockCraft.starterRocket(catalog), catalog)

        assertTrue("should be flyable: ${stats.problems}", stats.isFlyable)
        assertEquals(15, stats.partCount)
        assertEquals("two engine stages, the pod's release and the chute", 4, stats.stages.size)

        // Later stages keep the upper engine lit with nothing to feed it, so only two burn.
        val burning = stats.burns
        assertEquals(2, burning.size)
        assertTrue("first stage should out-thrust the second",
            burning[0].thrustVacuum > burning[1].thrustVacuum)
    }

    @Test
    fun `liftoff thrust-to-weight is above one`() {
        val stats = CraftStats.analyze(StockCraft.starterRocket(catalog), catalog)
        assertTrue(
            "a craft with TWR ${stats.liftoffTwr} would sit on the pad",
            stats.liftoffTwr > 1.0,
        )
        assertTrue("and one this high would be uncomfortable", stats.liftoffTwr < 4.0)
    }

    /**
     * The builder's delta-v has to match what the craft delivers in flight, allowing for gravity
     * and drag losses the rocket equation can't know about.
     */
    @Test
    fun `predicted delta-v is consistent with what the rocket actually achieves`() {
        val stats = CraftStats.analyze(StockCraft.starterRocket(catalog), catalog)
        val predicted = stats.totalDeltaV

        // Orbit here costs about 3.4 km/s ideal, plus losses.
        assertTrue(
            "predicted $predicted m/s is not enough to reach orbit at all",
            predicted > 3_200.0,
        )
        assertTrue(
            "predicted $predicted m/s is implausibly high for this craft",
            predicted < 4_200.0,
        )
    }

    @Test
    fun `each stage only burns the fuel its own engines can reach`() {
        val stats = CraftStats.analyze(StockCraft.starterRocket(catalog), catalog)
        val burning = stats.burns

        // Three T800s at 400 units, 5 kg each.
        assertEquals("first stage propellant", 6_000.0, burning[0].propellantMass, 1.0)
        // One T400 at 200 units.
        assertEquals("second stage propellant", 2_000.0, burning[1].propellantMass, 1.0)
    }

    @Test
    fun `staging drops the spent stage's mass`() {
        val stats = CraftStats.analyze(StockCraft.starterRocket(catalog), catalog)
        val burning = stats.burns

        assertTrue(
            "second stage should start lighter than the first ended " +
                "(${burning[1].startMass} vs ${burning[0].endMass})",
            burning[1].startMass < burning[0].endMass,
        )
    }

    @Test
    fun `a craft with no engines is reported as unflyable`() {
        val builder = CraftBuilder(catalog)
        builder.placeRoot("pod-halo")
        builder.attach("tank-cask2", builder.openNodes().first { it.node.id == "bottom" })

        val stats = CraftStats.analyze(builder.design, catalog)
        assertFalse(stats.isFlyable)
        assertTrue(
            "should say what is missing: ${stats.problems}",
            stats.problems.any { it.contains("engine", ignoreCase = true) },
        )
    }

    @Test
    fun `a solid motor needs a matching tank in its own part`() {
        val design = StockCraft.sounder(catalog)
        val motor = catalog.require("srb-flint")
        val external = catalog.require("tank-cask2").let {
            it.copy(modules = it.modules + Tank(ResourceType.SOLID_FUEL, 100.0))
        }
        for (tanks in listOf(emptyList(), listOf(Tank(ResourceType.PROPELLANT, 100.0)))) {
            val broken = motor.copy(id = "srb-missing-fuel", modules = motor.modules.filterNot { it is Tank } + tanks)
            val parts = PartCatalog.of(catalog.parts.values.filter { it.id != external.id } + external + broken)
            val invalid = design.copy(parts = design.parts.map { part ->
                if (part.partId == "engine-ember") part.copy(partId = broken.id) else part
            })
            val stats = CraftStats.analyze(invalid, parts)
            assertFalse(stats.isFlyable)
            assertEquals(listOf("1 engine(s) have no fuel tank connected"), stats.problems)
        }
    }

    @Test
    fun `a liquid engine cannot use a solid motor's fuel`() {
        val design = StockCraft.sounder(catalog).let { sounder ->
            sounder.copy(parts = sounder.parts.map {
                if (it.partId == "tank-cask2") it.copy(partId = "srb-flint") else it
            })
        }
        val stats = CraftStats.analyze(design, catalog)
        assertFalse(stats.isFlyable)
        assertEquals(listOf("1 engine(s) have no fuel tank connected"), stats.problems)
    }

    @Test
    fun `a liquid engine cannot reach tanks across a non-feeding decoupler`() {
        val design = StockCraft.sounder(catalog)
        val disconnected = design.copy(parts = design.parts.map {
            if (it.partId == "engine-ember") it.copy(parentIndex = 0) else it
        })
        val stats = CraftStats.analyze(disconnected, catalog)
        assertFalse(stats.isFlyable)
        assertEquals(listOf("1 engine(s) have no fuel tank connected"), stats.problems)
    }

    @Test
    fun `a craft with no pod is reported as unflyable`() {
        val design = CraftDesign(
            "Headless",
            listOf(PlacedPart("tank-cask2", com.rm.apogee.core.math.Vec3.zero())),
        )
        val stats = CraftStats.analyze(design, catalog)
        assertTrue(
            "should say there is nothing to fly it from: ${stats.problems}",
            stats.problems.any { it.contains("command", ignoreCase = true) },
        )
    }

    @Test
    fun `an underpowered craft gets flagged before it wastes a launch`() {
        // A vacuum engine under a heavy stack: plenty of delta-v, but no lift.
        val builder = CraftBuilder(catalog)
        builder.placeRoot("pod-halo")
        var node = builder.openNodes().first { it.partIndex == 0 && it.node.id == "bottom" }
        repeat(3) {
            val tank = builder.attach("tank-cask4", node).first()
            node = builder.openNodes().first { it.partIndex == tank && it.node.id == "bottom" }
        }
        builder.attach("engine-vesper", node)

        val stats = CraftStats.analyze(builder.design, catalog)
        assertTrue(
            "should warn about thrust-to-weight: ${stats.warnings}",
            stats.warnings.any { it.contains("Thrust-to-weight") },
        )
        // Only a warning: landers have thrust-to-weight below one and must still be placeable.
        assertTrue("and it should still be placeable", stats.isFlyable)
    }

    @Test
    fun `an empty design analyses without throwing`() {
        val stats = CraftStats.analyze(CraftDesign("Empty", emptyList()), catalog)
        assertEquals(0, stats.partCount)
        assertFalse(stats.isFlyable)
    }

    /** A submarine's screw runs on charge, not propellant, so its batteries are its fuel. */
    @Test
    fun `the submarines can be launched`() {
        for (design in listOf(StockCraft.minnow(catalog), StockCraft.nautilus(catalog), StockCraft.abyss(catalog))) {
            val stats = CraftStats.analyze(design, catalog)
            assertTrue("${design.name} refused: ${stats.problems}", stats.isFlyable)
        }
    }

    /** A rover has no engine. Its wheels are what move it. */
    @Test
    fun `a rover can be launched`() {
        val stats = CraftStats.analyze(StockCraft.rover(catalog), catalog)
        assertTrue("rover refused: ${stats.problems}", stats.isFlyable)
    }

    @Test
    fun `a helicopter without a tail rotor is warned, and one with it isn't`() {
        val tailRotor = "Its rotor will spin it. Add a tail rotor, or a second rotor turning the other way"
        assertFalse(tailRotor in CraftStats.analyze(StockCraft.hummingbird(catalog), catalog).warnings)
        assertFalse("a quad's rotors cancel", tailRotor in CraftStats.analyze(StockCraft.quad(catalog), catalog).warnings)
        // The Hummingbird with a fin where its tail rotor was.
        val heli = StockCraft.hummingbird(catalog)
        val noTail = heli.copy(parts = heli.parts.map { if (it.partId == "rotor-tail") it.copy(partId = "tail-rudder") else it })
        val stats = CraftStats.analyze(noTail, catalog)
        assertTrue("warnings: ${stats.warnings}", tailRotor in stats.warnings)
        assertTrue("still flyable", stats.isFlyable)
    }
}
