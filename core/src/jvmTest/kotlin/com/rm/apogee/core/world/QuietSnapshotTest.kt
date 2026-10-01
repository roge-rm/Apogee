package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietSnapshotTest {
    private val catalog = StockParts.catalog

    private fun World.sends(id: Long, quietEvery: Long): Boolean = snapshot(quietEvery).vessels.any { it.vessel == id }

    @Test
    fun `a craft asleep on the ground is sent now and then, and straight away once it wakes`() {
        val world = World.default(catalog)
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), World.launchSites.first())
        rover.control.brakes = true
        repeat(600) { world.step(1.0 / 60.0) }
        assertTrue("the rover should be asleep by now", rover.dormant)
        val id = rover.id.raw
        assertTrue("not in the first snapshot", world.sends(id, 120))
        repeat(3) { world.step(1.0 / 60.0) }
        assertFalse("sent again three ticks on", world.sends(id, 120))
        repeat(120) { world.step(1.0 / 60.0) }
        assertTrue("not sent again after two seconds", world.sends(id, 120))
        repeat(3) { world.step(1.0 / 60.0) }
        assertFalse(world.sends(id, 120))
        rover.wake()
        assertTrue("not sent once it woke", world.sends(id, 120))
        // Someone arriving gets it at once.
        rover.control.brakes = true
        repeat(600) { world.step(1.0 / 60.0) }
        assertTrue(rover.dormant)
        world.sends(id, 120)
        world.step(1.0 / 60.0)
        world.sendEverythingNext()
        assertTrue("not sent to someone who's just arrived", world.sends(id, 120))
        // One being flown goes every time.
        world.step(1.0 / 60.0)
        assertTrue("the flown craft went quiet", world.snapshot(120, always = setOf(id)).vessels.any { it.vessel == id })
        // Without the quiet ticks, everything goes every time.
        assertTrue(world.sends(id, 0L) && world.sends(id, 0L))
    }

    @Test
    fun `the launch complex goes quiet too`() {
        val world = World.default(catalog)
        world.ensureStructures()
        repeat(10) { world.step(1.0 / 60.0) }
        val all = world.snapshot().vessels.size
        world.step(1.0 / 60.0)
        world.snapshot(120)
        world.step(1.0 / 60.0)
        val quiet = world.snapshot(120).vessels.size
        assertTrue("there should be structures to send", all > 0)
        assertTrue("$quiet of $all still sent in the next snapshot", quiet < all)
    }
}
