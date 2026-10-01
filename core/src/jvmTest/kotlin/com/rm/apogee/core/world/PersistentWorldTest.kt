package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One world that people come back to, outliving each flight. */
class PersistentWorldTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val site = World.launchSites.first()

    @Test
    fun `a world survives being saved and restored`() {
        val first = World.default(catalog)
        val vessel = first.spawnOnSurface(StockCraft.lander(catalog), site)
        vessel.owner = "Pilot"
        repeat(600) { first.step(dt) }

        val saved = first.save()
        val second = World.default(catalog)
        val problems = second.restore(saved)

        assertTrue("restoring should not complain: $problems", problems.isEmpty())
        assertEquals("universe time should carry over", first.time, second.time, 1e-9)
        assertEquals(1, second.vessels.size)

        val restored = second.vessel(vessel.id)
        assertNotNull("the craft should come back with its id", restored)
        assertEquals("and its owner", "Pilot", restored!!.owner)
        val moved = Vec3().setTo(restored.body.position)
            .subInPlace(vessel.body.position).length
        assertTrue("it should come back where it was, moved $moved m", moved < 0.01)
    }

    /** Launching into a world you already have a craft in: land a module, launch the next, weld. */
    @Test
    fun `launching a second craft leaves the first where it was`() {
        val world = World.default(catalog)
        val first = world.spawnFor(
            Command.SpawnCraft(StockCraft.lander(catalog), site.id), owner = "Pilot",
        )
        repeat(600) { world.step(dt) }
        val firstPlace = first.body.position.copy()

        val second = world.spawnFor(
            Command.SpawnCraft(StockCraft.lander(catalog), site.id), owner = "Pilot",
        )

        assertEquals("both craft should exist", 2, world.vessels.size)
        val drift = Vec3().setTo(first.body.position).subInPlace(firstPlace).length
        assertTrue("the first craft should not have been disturbed ($drift m)", drift < 0.01)
    }

    /** A new craft mustn't spawn inside one already standing there, or they explode. */
    @Test
    fun `craft launched one after another get their own pads`() {
        val world = World.default(catalog)
        val spawned = (0 until 4).map {
            world.spawnFor(
                Command.SpawnCraft(StockCraft.lander(catalog), site.id), owner = "Pilot",
            )
        }

        for (i in spawned.indices) {
            for (j in i + 1 until spawned.size) {
                val apart = Vec3().setTo(spawned[i].body.position)
                    .subInPlace(spawned[j].body.position).length
                assertTrue("craft $i and $j spawned ${apart}m apart", apart > 10.0)
            }
        }

        // And none of them should have been destroyed by landing on another.
        repeat(600) { world.step(dt) }
        assertEquals("all four should survive", 4, world.vessels.size)
    }

    @Test
    fun `a restored craft is still owned and still switchable`() {
        val world = World.default(catalog)
        world.spawnFor(Command.SpawnCraft(StockCraft.lander(catalog), site.id), "Pilot")
        world.spawnFor(Command.SpawnCraft(StockCraft.lander(catalog), site.id), "Pilot")

        val reloaded = World.default(catalog)
        reloaded.restore(world.save())

        val mine = reloaded.vessels.filter { it.owner == "Pilot" }
        assertEquals("both craft should come back as the player's", 2, mine.size)
        // The structure update is what the client switches on.
        for (vessel in mine) {
            assertEquals("Pilot", reloaded.structureUpdateFor(vessel).owner)
        }
    }
}
