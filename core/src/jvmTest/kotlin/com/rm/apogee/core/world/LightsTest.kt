package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.LightMode
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The light switch: off to start, on, or by itself after dark; and only lit with power. */
class LightsTest {
    private val catalog = StockParts.catalog

    private fun minnow(world: World) = Aloft.spawn(world, StockCraft.minnow(catalog), 50.0)

    @Test
    fun `lights start off, and switched on they light and draw power`() {
        val world = World.default(catalog)
        val sub = minnow(world)
        assertEquals(LightMode.OFF, sub.control.lights)
        assertNull(world.litLamps(sub))
        world.apply(Command.SetLights(sub.id.raw, LightMode.ON))
        val lamps = sub.defs.indices.filter { sub.defs[it].module<com.rm.apogee.core.part.Lamp>() != null }
        assertEquals(lamps, world.litLamps(sub))
        assertTrue(world.systemsOf(sub).lamps)
        assertEquals(LightMode.ON, world.systemsOf(sub).lights)
    }

    @Test
    fun `on auto they're lit only in the dark`() {
        val world = World.default(catalog)
        val sub = minnow(world)
        world.apply(Command.SetLights(sub.id.raw, LightMode.AUTO))
        assertEquals(world.darkAt(sub), world.litLamps(sub) != null)
    }

    @Test
    fun `with the battery flat nothing lights, but an isotope still glows`() {
        val world = World.default(catalog)
        val sub = minnow(world)
        world.apply(Command.SetLights(sub.id.raw, LightMode.ON))
        sub.powered = false
        assertNull(world.litLamps(sub))
        val stake = world.spawnAt(
            com.rm.apogee.core.craft.CraftDesign("Stake", listOf(com.rm.apogee.core.craft.PlacedPart("glow-stake", com.rm.apogee.core.math.Vec3.zero())), catalogHash = catalog.contentHash),
            "terra", sub.body.position.copy(), sub.body.linearVelocity.copy(), sub.body.orientation.copy(),
        )
        stake.powered = false
        assertEquals(listOf(0), world.litLamps(stake))
        // And has no switch to show.
        assertTrue(!world.systemsOf(stake).lamps)
    }

    @Test
    fun `the switch is kept through a save`() {
        val world = World.default(catalog)
        val sub = minnow(world)
        world.apply(Command.SetLights(sub.id.raw, LightMode.AUTO))
        val restored = World(world.system, catalog).also { it.restore(world.save()) }
        assertEquals(LightMode.AUTO, restored.vessel(sub.id)!!.control.lights)
    }

    @Test
    fun `lit lamps draw the battery down, and switched off they don't`() {
        fun drained(mode: LightMode): Double {
            val world = World.default(catalog)
            val sub = minnow(world)
            world.apply(Command.SetLights(sub.id.raw, mode))
            val before = sub.amountOf(ResourceType.ELECTRIC_CHARGE)
            Aloft.run(world, 20.0)
            return before - sub.amountOf(ResourceType.ELECTRIC_CHARGE)
        }
        assertTrue(drained(LightMode.ON) > drained(LightMode.OFF) + 1.0)
    }
}
