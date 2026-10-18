package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertTrue
import org.junit.Test

/** Warp on rails never carries a craft through a small moon's reach between looks. */
class RailsEncounterTest {
    @Test
    fun `a craft crossing Pavor's reach within one slice is caught by it`() {
        val world = World.default(StockParts.catalog)
        val system = world.system
        val rubra = system.body("rubra")
        val pavor = system.body("pavor")
        // Through Pavor's 17 km reach at 1 km/s, 5 km from its centre, half a slice in.
        val lead = 300.0
        val meet = world.time + lead
        val moon = pavor.orbit!!.stateAt(meet)
        val across = Vec3(moon.velocity).crossInPlace(moon.position).normalizeInPlace()
        val along = Vec3(moon.position).normalizeInPlace()
        val position = Vec3(moon.position).addInPlace(across * 5_000.0)
        val velocity = Vec3(moon.velocity).addInPlace(along * 1_000.0)
        val back = Orbit(position, velocity, rubra.gravitationalParameter).propagate(-lead)
        val craft = world.spawnAt(StockCraft.moteProbe(StockParts.catalog), "rubra", back.position, back.velocity, Quat.identity())
        world.drainEvents()
        assertTrue("rails wouldn't run", world.advanceOnRails(2 * lead) > 0.0)
        val visited = world.drainEvents().filterIsInstance<WorldEvent.BodyChanged>().filter { it.id == craft.id }.map { it.to }
        assertTrue("passed through Pavor's reach unseen: $visited", "pavor" in visited)
    }
}
