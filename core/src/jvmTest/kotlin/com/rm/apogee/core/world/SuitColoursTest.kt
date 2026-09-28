package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.crew.Crew
import com.rm.apogee.core.crew.CrewMember
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suits: a player's stripe on all their crew, and a visor of each one's own. */
class SuitColoursTest {
    private val catalog = StockParts.catalog

    @Test
    fun `recruits who join together get different visors, until one is picked`() {
        val visors = (1L..Crew.VISORS).map { Crew.visorOf(CrewMember(it, "x", "alice")) }
        assertEquals("the first eight all differ: $visors", Crew.VISORS, visors.toSet().size)
        assertEquals(5, Crew.visorOf(CrewMember(1, "x", "alice", visor = 5)))
    }

    @Test
    fun `a player's stripe is the one they picked, or one of the palette's`() {
        assertEquals(3, Crew.stripeFor("alice", 3))
        for (owner in listOf("alice", "bob", "", "install-7")) {
            assertTrue(Crew.stripeFor(owner, null) in 0 until Crew.STRIPES)
            assertTrue(Crew.stripeFor(owner, 99) in 0 until Crew.STRIPES)
        }
    }

    @Test
    fun `someone out in a suit carries their colours, and a craft doesn't`() {
        val world = World.default(catalog)
        world.stripes["alice"] = 6
        val pod = world.spawnFor(Command.SpawnCraft(StockCraft.starterRocket(catalog), "cape"), owner = "alice")
        val pilot = world.crew.getValue(pod.crew.first { it.isNotEmpty() }.first())
        world.setVisor(pilot.id, 2)
        assertEquals(-1, world.structureUpdateFor(pod).stripe)
        assertEquals(-1, world.structureUpdateFor(pod).visor)
        val suit = world.eva(pod.id.raw, pilot.id)!!
        val update = world.structureUpdateFor(suit)
        assertEquals(6, update.stripe)
        assertEquals(2, update.visor)
    }

    @Test
    fun `stripes and visors are kept with the world`() {
        val world = World.default(catalog)
        world.stripes["alice"] = 4
        val member = world.recruit("alice")
        world.setVisor(member.id, 7)
        val again = World.default(catalog)
        again.restore(world.save())
        assertEquals(4, again.stripes["alice"])
        assertEquals(7, again.crew.getValue(member.id).visor)
    }
}
