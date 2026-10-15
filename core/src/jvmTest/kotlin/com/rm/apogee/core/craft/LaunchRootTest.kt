package com.rm.apogee.core.craft

import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import org.junit.Assert.*
import org.junit.Test

class LaunchRootTest {
    private val catalog = StockParts.catalog
    private fun attach(b: CraftBuilder, parent: Int, node: String, part: String) =
        b.attach(part, b.openNodes().first { it.partIndex == parent && it.node.id == node }).first()

    private fun tankFirst(): CraftDesign {
        val b = CraftBuilder(catalog)
        b.placeRoot("tank-cask4")
        val ring = attach(b, 0, "top", "decoupler-ring")
        val pod = attach(b, ring, "top", "pod-halo")
        attach(b, 0, "bottom", "engine-ember")
        attach(b, pod, "top", "chute-canopy")
        return b.design
    }

    @Test fun `tank first staging lights the engine before releasing the capsule`() {
        val design = tankFirst()
        assertFalse(design.manualStaging)
        assertEquals(listOf(listOf("engine-ember"), listOf("decoupler-ring"), listOf("chute-canopy")),
            design.stages.map { s -> s.activatedParts.map { design.parts[it].partId } })
        val prepared = design.preparedForLaunch(catalog)
        assertEquals("pod-halo", prepared.parts[prepared.rootIndex()].partId)
        assertTrue(prepared.validate(catalog).toString(), prepared.validate(catalog).isEmpty())
        for (i in design.parts.indices) {
            assertEquals(design.parts[i].position, prepared.parts[i].position)
            assertEquals(design.parts[i].rotation, prepared.parts[i].rotation)
        }
        val world = World.default(catalog)
        val rocket = world.spawnFor(Command.SpawnCraft(design, "cape"), "pilot")
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        world.stage(rocket)
        repeat(30) { world.step(1.0 / 60.0) }
        assertEquals("first ignition keeps the rocket together", 1, world.vessels.size)
        assertEquals(design.parts.size, rocket.design.parts.size)
        assertTrue(rocket.activeEngines().isNotEmpty())
        world.stage(rocket)
        assertEquals(2, world.vessels.size)
        assertTrue("the pilot retains the capsule", rocket.design.parts.any { it.partId == "pod-halo" })
        assertFalse("the tank is discarded", rocket.design.parts.any { it.partId == "tank-cask4" })
    }

    @Test fun `a multi-stage rocket built from either engine gets the same launch sequence`() {
        val stock = StockCraft.starterRocket(catalog)
        for (engine in stock.parts.indices.filter { catalog[stock.parts[it].partId]?.module<com.rm.apogee.core.part.Engine>() != null }) {
            val reversed = stock.rerootedAt(engine)
            assertEquals(stock.stages, CraftBuilder.autoStage(reversed, catalog))
            val prepared = reversed.preparedForLaunch(catalog)
            assertEquals(stock.stages, prepared.stages)
            assertEquals(stock.rootIndex(), prepared.rootIndex())
            val world = World.default(catalog)
            val rocket = world.spawnFor(Command.SpawnCraft(reversed, "cape"), "pilot")
            world.stage(rocket)
            assertEquals(stock.parts.size, rocket.design.parts.size)
            assertEquals(listOf("engine-ember"), rocket.activeEngines().map { rocket.defs[it].id })
            world.stage(rocket)
            assertTrue(rocket.design.parts.any { it.partId == "pod-halo" })
            assertEquals(listOf("engine-vesper"), rocket.activeEngines().map { rocket.defs[it].id })
        }
    }

    @Test fun `legacy automatic stages are repaired while manual order and stock sequences stay intact`() {
        val design = tankFirst()
        val engine = design.parts.indexOfFirst { it.partId == "engine-ember" }
        val ring = design.parts.indexOfFirst { it.partId == "decoupler-ring" }
        val old = design.copy(stages = listOf(Stage(listOf(engine, ring))))
        val fixed = old.preparedForLaunch(catalog)
        assertEquals(listOf(engine), fixed.stages.first().activatedParts)
        val manual = old.copy(manualStaging = true).preparedForLaunch(catalog)
        assertEquals(old.stages, manual.stages)
        assertEquals("pod-halo", manual.parts[manual.rootIndex()].partId)
        val stock = StockCraft.starterRocket(catalog)
        assertSame(stock, stock.preparedForLaunch(catalog))
    }
}
