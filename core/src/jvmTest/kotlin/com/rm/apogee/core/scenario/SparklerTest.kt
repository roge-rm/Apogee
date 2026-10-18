package com.rm.apogee.core.scenario

import com.rm.apogee.core.career.TechTree
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sparkler: the Sounder with two Flints, inside the first pad, and it goes higher. */
class SparklerTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    @Test
    fun `it fits the first pad and lifts off hard`() {
        val design = StockCraft.sparkler(catalog)
        assertTrue("won't validate: ${design.validate(catalog)}", design.validate(catalog).isEmpty())
        val stats = CraftStats.analyze(design, catalog)
        val pad = TechTree.stock.facilities.pad[0]
        println("Sparkler: ${stats.totalMass} kg, ${design.parts.size} parts, TWR ${stats.liftoffTwr}")
        assertTrue("${stats.totalMass} kg", stats.totalMass <= pad.mass)
        assertTrue("${design.parts.size} parts", design.parts.size <= pad.parts)
        assertEquals(14, design.parts.size)
        assertTrue("lifts off at ${stats.liftoffTwr}", stats.liftoffTwr in 3.0..5.0)
        assertTrue("first stage is the Flints and the Ember", stats.stages[0].solid)
    }

    /** Flown straight up: lit, the clamps dropped when the Flints are out, the ring when the tanks are. Its peak height. */
    private fun peak(design: CraftDesign): Double {
        val world = World.default(catalog)
        val craft = world.spawnOnSurface(design, World.launchSites.first { it.id == "cape" })
        val parts = design.parts.size
        val terra = world.system.body("terra")
        world.stage(craft)
        world.apply(Command.SetSas(craft.id.raw, true))
        world.apply(Command.SetThrottle(craft.id.raw, 1.0))
        val solid = craft.amountOf(ResourceType.SOLID_FUEL) > 0.0
        var clamps = !solid
        var ring = false
        var top = 0.0
        var t = 0.0
        while (t < 600.0) {
            world.step(dt); t += dt
            val altitude = terra.altitudeOf(craft.body.position)
            top = maxOf(top, altitude)
            if (!clamps && craft.amountOf(ResourceType.SOLID_FUEL) < 0.01) {
                world.stage(craft); clamps = true
                assertEquals("the clamps and Flints gone", parts - 4, craft.design.parts.size)
            }
            if (clamps && !ring && craft.amountOf(ResourceType.PROPELLANT) < 1.0) { world.stage(craft); ring = true }
            val vertical = craft.body.linearVelocity dot craft.body.position.normalized()
            if (ring && vertical < 0.0) break
        }
        assertFalse("broke on the way up", craft.broken.any { it })
        return top
    }

    @Test
    fun `it goes higher than the Sounder`() {
        val sounder = peak(StockCraft.sounder(catalog))
        val sparkler = peak(StockCraft.sparkler(catalog))
        println("Peak: Sounder ${sounder.toInt()} m, Sparkler ${sparkler.toInt()} m")
        assertTrue("Sparkler $sparkler m against the Sounder's $sounder m", sparkler > sounder * 1.2)
    }
}
