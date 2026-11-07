package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import com.rm.apogee.render.FrameBus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuilderSparklerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `loading the Sparkler allows launch and its boosters lift it off`() {
        val catalog = StockParts.catalog
        val store = CraftStore(folder.newFolder("craft"))
        val saved = store.save(StockCraft.sparkler(catalog)).getOrThrow()
        val session = BuilderSession(FrameBus(), catalog, store)
        session.load(saved)
        assertTrue("${session.stats.problems}", session.stats.isFlyable)
        assertNull(session.launchRefusal())
        val design = session.designForLaunch()
        assertNotNull("Launch must be enabled", design)
        val world = World.default(catalog)
        val craft = world.spawnOnSurface(design!!, session.automaticSite())
        val terra = world.system.body("terra")
        val start = terra.altitudeOf(craft.body.position)
        val fuel = craft.amountOf(ResourceType.SOLID_FUEL)
        world.stage(craft)
        world.apply(Command.SetThrottle(craft.id.raw, 1.0))
        repeat(120) { world.step(1.0 / 60.0) }
        assertTrue("boosters burn", craft.amountOf(ResourceType.SOLID_FUEL) < fuel)
        assertTrue("craft lifts off", terra.altitudeOf(craft.body.position) > start + 10.0)
    }
}
