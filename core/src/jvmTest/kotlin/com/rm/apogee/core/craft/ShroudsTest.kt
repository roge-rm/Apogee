package com.rm.apogee.core.craft

import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An engine with something under it has a shell round it, and the stage below takes it away. */
class ShroudsTest {

    private val catalog = StockParts.catalog

    @Test
    fun `the parting ring under the Starter's upper engine carries a shell round it`() {
        val design = StockCraft.starterRocket(catalog)
        val shrouds = Shrouds.of(design, catalog)
        val upper = design.parts.indexOfFirst { it.partId == "engine-vesper" }
        val ring = design.parts.indexOfFirst { it.parentIndex == upper }
        val shroud = assertNotNull(shrouds[ring]).let { shrouds[ring]!! }
        assertEquals("as tall as the engine", 1.0, shroud.height, 1e-9)
        assertTrue("as wide as the tank above it", shroud.radius > 0.62)
        // The bottom engine has nothing under it, and nothing else gets one.
        assertEquals(1, shrouds.count { it != null })
    }

    @Test
    fun `staging the first stage away sheds the shell and leaves the engine bare`() {
        val world = World.default(catalog)
        val craft = world.spawnFor(Command.SpawnCraft(StockCraft.starterRocket(catalog), "cape"), "p1")
        world.stage(craft)
        repeat(10) { world.step(1.0 / 60.0) }
        world.stage(craft)
        repeat(10) { world.step(1.0 / 60.0) }
        // The upper stage: no shell of its own now.
        assertTrue(craft.design.parts.any { it.partId == "engine-vesper" })
        assertTrue("the upper stage still has a shell", Shrouds.of(craft.design, catalog).all { it == null })
        // The dropped stage's ring records the shell it shed, to draw it falling away.
        val dropped = world.vessels.first { v -> v !== craft && v.design.parts.firstOrNull()?.partId == "decoupler-ring" }
        assertNotNull("the dropped ring's shed shell", dropped.design.parts[0].shroud)
        assertNull(Shrouds.of(dropped.design, catalog)[0])
        assertNull(dropped.design.parts.drop(1).firstNotNullOfOrNull { it.shroud })
    }
}
