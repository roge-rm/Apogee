package com.rm.apogee.core.world

import com.rm.apogee.core.FileFolder
import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Files exported by the optimised Android verification build, read by unoptimised JVM code. */
class R8SaveInteropTest {
    @Test fun prepareUnoptimizedSparklerWorld() {
        val path = System.getenv("R8_VALIDATION_DIR")
        assumeTrue("set R8_VALIDATION_DIR for the Android compatibility fixture", path != null)
        val directory = File(path!!).apply { mkdirs() }
        val world = World.default(StockParts.catalog)
        world.spawnOnSurface(StockCraft.sparkler(), World.launchSites.first()).owner = "r8-fixture"
        WorldStore(FileFolder(directory), "r8-input-world.json").save(world.save()).getOrThrow()
    }

    @Test fun optimizedAndroidExportsLoadOnTheJvm() {
        val path = System.getenv("R8_VALIDATION_DIR")
        assumeTrue("set R8_VALIDATION_DIR to the pulled Android exports", path != null)
        val directory = File(path!!)
        val catalog = StockParts.catalog
        val world = World.default(catalog)
        assertTrue(world.restore(WorldStore.decode(File(directory, "r8-output-world.json").readText())).isEmpty())
        val vessel = world.vessels.single()
        assertTrue(vessel.burning)
        assertEquals("r8-player", vessel.owner)
        assertTrue(vessel.amountOf(ResourceType.SOLID_FUEL) > 0.0)
        val career = World.default(catalog)
        assertTrue(career.restore(WorldStore.decode(File(directory, "r8-output-career.json").readText())).isEmpty())
        assertTrue(career.program!!.careerOf("r8-player").has("tanks"))
        val store = CraftStore(FileFolder(File(directory, "host-store")))
        val craft = store.decode(File(directory, "r8-output-craft.json").readText(), catalog).getOrThrow()
        assertEquals(StockCraft.wayfarer(catalog).parts, craft.parts)
    }
}
