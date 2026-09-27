package com.rm.apogee.core.craft

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

        // The stages after the second still have the upper engine lit
        // behind them, with nothing left to feed it - only two burn.
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
     * The number the builder quotes has to be the number the craft delivers.
     *
     * A delta-v readout that disagrees with the simulation is worse than none
     * at all - it teaches the player to distrust the builder. This compares the
     * prediction against the real flight, allowing for the gravity and drag
     * losses that an ideal rocket equation cannot know about.
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
    fun `an underpowered craft is flagged before it wastes a launch`() {
        // A vacuum engine under a heavy stack: plenty of delta-v, no lift.
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
        // A warning, not a refusal. Landers have a thrust-to-weight below one
        // by design and still have to be placeable.
        assertTrue("and it should still be placeable", stats.isFlyable)
    }

    @Test
    fun `an empty design analyses without throwing`() {
        val stats = CraftStats.analyze(CraftDesign("Empty", emptyList()), catalog)
        assertEquals(0, stats.partCount)
        assertFalse(stats.isFlyable)
    }

    /** A submarine's screw runs on charge, not propellant: its batteries are its fuel. */
    @Test
    fun `the submarines can be launched`() {
        for (design in listOf(StockCraft.minnow(catalog), StockCraft.nautilus(catalog), StockCraft.abyss(catalog))) {
            val stats = CraftStats.analyze(design, catalog)
            assertTrue("${design.name} refused: ${stats.problems}", stats.isFlyable)
        }
    }

    /** A rover has no engine; its wheels are what move it. */
    @Test
    fun `a rover can be launched`() {
        val stats = CraftStats.analyze(StockCraft.rover(catalog), catalog)
        assertTrue("rover refused: ${stats.problems}", stats.isFlyable)
    }
}
