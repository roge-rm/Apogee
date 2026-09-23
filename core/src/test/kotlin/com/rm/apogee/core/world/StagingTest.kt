package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Stage
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Parachute
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staging in flight: what separates, what fires next, and what is left. */
class StagingTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /**
     * After the upper stage separates, the chute is still next - and still
     * stage 2, the number the player saw it by in the builder and on the
     * stack. The lower stage's entry stays in the sequence, empty.
     */
    @Test
    fun `the chute still fires after the stages below it are gone`() {
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        world.stage(rocket) // lower engine
        world.stage(rocket) // separation, upper engine
        assertEquals("the chute keeps its number", 2, rocket.currentStage)
        world.stage(rocket) // chute
        val chute = rocket.defs.indices.first { rocket.defs[it].module<Parachute>() != null }
        assertTrue("the chute never fired", rocket.isActivated(chute))
        assertEquals(0, rocket.stagesRemaining)
    }

    /** Radial boosters: several decouplers in one stage, and every one lets go. */
    @Test
    fun `every decoupler in a stage separates`() {
        val parts = listOf(
            PlacedPart("pod-halo", Vec3.zero()),
            PlacedPart("decoupler-ring", Vec3(1.5, 0.0, 0.0), parentIndex = 0),
            PlacedPart("tank-cask2", Vec3(1.5, -1.0, 0.0), parentIndex = 1),
            PlacedPart("decoupler-ring", Vec3(-1.5, 0.0, 0.0), parentIndex = 0),
            PlacedPart("tank-cask2", Vec3(-1.5, -1.0, 0.0), parentIndex = 3),
        )
        val design = CraftDesign("Pair", parts, listOf(Stage(listOf(1, 3))), catalog.contentHash)
        val world = World.default(catalog)
        val craft = world.spawnOnSurface(design, World.launchSites.first())
        val before = world.vessels.size
        world.stage(craft)
        assertEquals("two boosters should have fallen away", before + 2, world.vessels.size)
        assertEquals(listOf("pod-halo"), craft.design.parts.map { it.partId })
    }

    @Test
    fun `a craft laid down stays laid down after dropping a stage`() {
        val world = World.default(catalog)
        val design = StockCraft.starterRocket(catalog).copy(orientation = CraftOrientation.HORIZONTAL)
        val craft = world.spawnOnSurface(design, World.launchSites.first())
        world.stage(craft)
        world.stage(craft)
        assertEquals(CraftOrientation.HORIZONTAL, craft.design.orientation)
    }

    /**
     * The flight HUD's figures: burning brings the current stage's fuel and
     * delta-v down from what the builder promised, and the stages above
     * still read full.
     */
    @Test
    fun `live stage figures follow the burn`() {
        val design = StockCraft.starterRocket(catalog)
        val planned = CraftStats.analyze(design, catalog).burns.first()
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(design, World.launchSites.first())
        world.apply(Command.Stage(rocket.id.raw))
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        repeat((planned.burnTime * 0.4 / dt).toInt()) { world.step(dt) }

        val live = CraftStats.analyzeLive(rocket)
        val now = live.first()
        assertEquals("the first entry is the stage burning", 0, now.index)
        assertTrue("fuel should be part used: ${now.fuelFraction}", now.fuelFraction in 0.45..0.75)
        assertTrue("delta-v should have dropped: ${now.deltaV} of ${planned.deltaV}", now.deltaV < planned.deltaV * 0.8)
        val upper = live.first { it.index == 1 }
        assertEquals("the upper stage is untouched", 1.0, upper.fuelFraction, 1e-9)
    }

    /** A jet's figures, which read zero when only vacuum was quoted. */
    @Test
    fun `an air-breathing stage has delta-v`() {
        val stats = CraftStats.analyze(StockCraft.sparrow(catalog), catalog)
        val burn = stats.burns.firstOrNull()
        assertTrue("the Sparrow should have a burn", burn != null)
        assertTrue("and delta-v: ${burn!!.deltaV}", burn.deltaV > 100.0)
        assertTrue(burn.fuel.isNotEmpty())
    }
}
