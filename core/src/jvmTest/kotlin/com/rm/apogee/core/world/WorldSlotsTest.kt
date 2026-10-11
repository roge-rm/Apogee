package com.rm.apogee.core.world

import com.rm.apogee.core.FileFolder
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** More than one world of each kind, kept apart, named, and deleted. */
class WorldSlotsTest {
    private fun folder() = FileFolder(kotlin.io.path.createTempDirectory("slots").toFile())

    @Test
    fun `the first world is there from the start, and new ones keep apart from it and the career`() {
        val folder = folder()
        val sandbox = WorldSlots(folder, "solo")
        val career = WorldSlots(folder, "career")
        assertEquals(listOf("World 1"), sandbox.list().map { it.name })

        val world = World.default(StockParts.catalog)
        sandbox.store(sandbox.list().first()).save(world.save())
        val second = sandbox.create("Moon base")
        assertEquals(listOf("World 1", "Moon base"), sandbox.list().map { it.name })
        assertEquals("solo-2", second.id)
        assertFalse("nothing saved in it yet", sandbox.store(second).exists)
        assertEquals("the career has its own", listOf("Career 1"), career.list().map { it.name })

        sandbox.rename(second, "Luna")
        assertEquals("Luna", sandbox.find("solo-2").name)
    }

    @Test
    fun `deleting a world takes its save point too, and the first is only emptied`() {
        val folder = folder()
        val slots = WorldSlots(folder, "solo")
        val world = World.default(StockParts.catalog).save()
        val second = slots.create("Two")
        slots.store(second).save(world)
        slots.savePointStore(second).save(world)
        slots.delete(second)
        assertEquals(listOf("solo"), slots.list().map { it.id })
        assertFalse(folder.exists("solo-2-savepoint.json"))

        val first = slots.list().first()
        slots.store(first).save(world)
        assertTrue(slots.store(first).exists)
        slots.delete(first)
        assertEquals("still there, empty", 1, slots.list().size)
        assertFalse(slots.store(first).exists)
    }
}
