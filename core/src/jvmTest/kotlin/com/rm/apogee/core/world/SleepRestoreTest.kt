package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Craft saved asleep load asleep, on the ground or aloft as they were. */
class SleepRestoreTest {
    private val catalog = StockParts.catalog
    private val json = Json { classDiscriminator = "type" }

    private fun reload(world: World): World =
        World.default(catalog).also { it.restore(WorldStore.decode(json.encodeToString(WorldSave.serializer(), world.save()))) }

    @Test
    fun `a craft asleep on the ground loads asleep and touching it`() {
        val world = World.default(catalog)
        val design = StockCraft.quad(catalog)
        val quad = world.spawnOnSurface(design, World.launchSiteFor(design, catalog))
        Aloft.run(world, 60.0)
        assertTrue("asleep", quad.dormant)
        assertTrue("touching", quad.touchingGround)
        val loaded = reload(world).vessel(quad.id)!!
        assertTrue("loaded asleep", loaded.dormant)
        assertTrue("loaded touching", loaded.touchingGround)
    }

    @Test
    fun `a platform asleep in the sky loads asleep and not touching the ground`() {
        val world = World.default(catalog)
        val platform = Aloft.spawn(world, StockCraft.skyPlatform(catalog), 150.0)
        world.apply(Command.SetStationKeep(platform.id.raw, true))
        Aloft.run(world, 60.0)
        assertTrue("asleep", platform.dormant)
        val loaded = reload(world).vessel(platform.id)!!
        assertTrue("loaded asleep", loaded.dormant)
        assertFalse("loaded touching", loaded.touchingGround)
        assertFalse("loaded buoyed", loaded.buoyed)
    }
}
