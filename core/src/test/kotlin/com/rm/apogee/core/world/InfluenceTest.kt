package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passing from one body's pull into another's: into Luna's on the way out
 * from Terra, and back into Terra's on the way home - the same place and
 * motion either side, measured from a new centre.
 */
class InfluenceTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /**
     * A probe [outside] metres beyond Luna's sphere of influence, on the
     * side toward Terra, closing on Luna at [closing] m/s: in Terra's pull.
     */
    private fun approaching(world: World, outside: Double, closing: Double): Vessel {
        val system = world.system
        val luna = system.body("luna")
        val t = world.time
        val lunaFromTerra = system.positionOf("luna", t).subInPlace(system.positionOf("terra", t))
        val toTerra = lunaFromTerra.copy().negateInPlace().normalizeInPlace()
        val position = lunaFromTerra.copy().addScaledInPlace(toTerra, luna.sphereOfInfluence + outside)
        val velocity = system.velocityOf("luna", t).subInPlace(system.velocityOf("terra", t)).addScaledInPlace(toTerra, -closing)
        return world.spawnAt(StockCraft.probe(catalog), "terra", position, velocity, Quat.identity())
    }

    private fun absolute(world: World, vessel: Vessel): Vec3 =
        world.system.positionOf(vessel.referenceBodyId, world.time).addInPlace(vessel.body.position)

    @Test
    fun `a craft coasting into Luna's pull changes body without a jump`() {
        val world = World.default(catalog)
        val probe = approaching(world, outside = 300.0, closing = 500.0)
        assertEquals("terra", probe.referenceBodyId)
        val track = ArrayList<Vec3>()
        repeat((2.0 / dt).toInt()) {
            world.step(dt)
            track.add(absolute(world, probe))
        }
        assertEquals("never entered Luna's pull", "luna", probe.referenceBodyId)
        // Smooth through the handover: each tick's move differs from the last
        // by what the pull changes it by, a few micrometres - not by a jump.
        for (i in 1 until track.size - 1) {
            val bend = Vec3().setTo(track[i + 1]).subInPlace(track[i]).subInPlace(track[i]).addInPlace(track[i - 1]).length
            assertTrue("jumped ${bend} m at tick $i", bend < 1e-3)
        }
        val luna = world.system.body("luna")
        assertTrue("not inside Luna's pull", probe.body.position.length < luna.sphereOfInfluence)
    }

    @Test
    fun `on rails too it changes body where it crosses, and ends where stepping would`() {
        val stepped = World.default(catalog)
        val a = approaching(stepped, outside = 1_000.0, closing = 500.0)
        repeat((20.0 / dt).toInt()) { stepped.step(dt) }

        val warped = World.default(catalog)
        val b = approaching(warped, outside = 1_000.0, closing = 500.0)
        assertTrue("rails not allowed out here", warped.maxWarp() > World.PHYSICS_WARP)
        val done = warped.advanceOnRails(20.0)
        assertEquals(20.0, done, 1e-6)
        assertEquals("luna", a.referenceBodyId)
        assertEquals("luna", b.referenceBodyId)
        val apart = a.body.position.distanceTo(b.body.position)
        assertTrue("rails and stepping ended $apart m apart", apart < 1.0)
    }

    @Test
    fun `a craft leaving Luna's pull is Terra's again`() {
        val world = World.default(catalog)
        val luna = world.system.body("luna")
        // Just inside Luna's reach, heading out at 400 m/s.
        val out = Vec3(1.0, 0.0, 0.0)
        val probe = world.spawnAt(
            StockCraft.probe(catalog), "luna",
            Vec3().setTo(out).mulInPlace(luna.sphereOfInfluence - 200.0), Vec3().setTo(out).mulInPlace(400.0), Quat.identity(),
        )
        val before = absolute(world, probe)
        repeat((1.0 / dt).toInt()) { world.step(dt) }
        assertEquals("terra", probe.referenceBodyId)
        // It went about 400 m, in Luna's frame - wherever that took it in Terra's.
        val lunaMoved = world.system.positionOf("luna", world.time).subInPlace(world.system.positionOf("luna", world.time - 1.0))
        val moved = absolute(world, probe).subInPlace(before).subInPlace(lunaMoved).length
        assertEquals(400.0, moved, 2.0)
    }

    @Test
    fun `a craft in low orbit about Terra stays Terra's`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val r = terra.radius + 100_000.0
        val probe = world.spawnAt(
            StockCraft.probe(catalog), "terra", Vec3(r, 0.0, 0.0),
            Vec3(0.0, 0.0, terra.circularVelocityAt(r)), Quat.identity(),
        )
        repeat(600) { world.step(dt) }
        world.advanceOnRails(3_000.0)
        assertEquals("terra", probe.referenceBodyId)
    }
}
