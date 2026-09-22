package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Welding two craft into one, which is how a base gets built: land the
 * modules, push them together, tie them.
 *
 * The invariant that matters throughout is that nothing *moves* when it is
 * joined. A player has already positioned these things; a merge that shifts
 * them by even a metre would be worse than no merge at all.
 */
class JoinTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val site = World.launchSites.first()

    private fun world() = World.default(catalog)

    private fun placeBeside(world: World, vessel: Vessel, reference: Vessel, metres: Double) {
        val up = Vec3().setTo(reference.body.position).normalizeInPlace()
        val east = Vec3.unitY().cross(up).normalizeInPlace()
        vessel.body.position.setTo(reference.body.position).addScaledInPlace(east, metres)
        vessel.body.orientation.setTo(reference.body.orientation)
        world.attractorFor(vessel)
            .surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.angularVelocity.setTo(Vec3.zero())
    }

    @Test
    fun `two landed modules weld into one craft`() {
        val world = world()
        val base = world.spawnOnSurface(StockCraft.lander(catalog), site)
        val module = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 1)
        placeBeside(world, module, base, 3.0)

        val partsBefore = base.partCount + module.partCount
        val merged = world.joinToNeighbour(base)

        assertNotNull("they are touching, so it should have joined", merged)
        assertEquals("every part should survive the merge", partsBefore, merged!!.partCount)
        assertNull("the absorbed craft should be gone", world.vessel(module.id))
        assertEquals("and only one craft should remain", 1, world.vessels.size)
    }

    /**
     * The one that would catch a bad transform. Merging re-expresses one
     * craft's parts in the other's design space, and getting that wrong moves
     * things without anyone noticing until a base looks scrambled.
     */
    @Test
    fun `welding does not move anything`() {
        val world = world()
        val base = world.spawnOnSurface(StockCraft.lander(catalog), site)
        val module = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 1)
        placeBeside(world, module, base, 3.0)

        val before = (0 until base.partCount).map { base.partPositionWorld(it).copy() } +
            (0 until module.partCount).map { module.partPositionWorld(it).copy() }

        val merged = world.joinToNeighbour(base)!!

        val after = (0 until merged.partCount).map { merged.partPositionWorld(it).copy() }
        assertEquals(before.size, after.size)
        for (i in before.indices) {
            val moved = Vec3().setTo(after[i]).subInPlace(before[i]).length
            assertTrue("part $i moved $moved m during the weld", moved < 0.01)
        }
    }

    @Test
    fun `a welded base stays put afterwards`() {
        val world = world()
        val base = world.spawnOnSurface(StockCraft.lander(catalog), site)
        repeat(3) { world.stage(base) }
        val module = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 1)
        repeat(3) { world.stage(module) }
        placeBeside(world, module, base, 3.0)

        val merged = world.joinToNeighbour(base)!!
        val attractor = world.attractorFor(merged)

        // Measured in the body-fixed frame. Inertially a craft parked on the
        // pad covers a hundred and seventy-five metres a second, because the
        // planet turns underneath it - comparing inertial positions would call
        // a perfectly stationary base a runaway.
        val start = attractor.toBodyFixed(
            merged.body.position, attractor.rotationAt(world.time), Vec3(),
        )
        repeat(900) { world.step(dt) }
        val end = attractor.toBodyFixed(
            merged.body.position, attractor.rotationAt(world.time), Vec3(),
        )

        val drift = Vec3().setTo(end).subInPlace(start).length
        assertTrue("the base drifted $drift m across the ground after welding", drift < 1.0)
        assertTrue(
            "and should not be spinning at ${merged.body.angularVelocity.length} rad/s",
            merged.body.angularVelocity.length < 0.05,
        )
    }

    @Test
    fun `nothing happens when there is nothing to join to`() {
        val world = world()
        val solo = world.spawnOnSurface(StockCraft.lander(catalog), site)
        assertNull("a craft on its own has no neighbour", world.joinToNeighbour(solo))
        assertEquals(1, world.vessels.size)
    }

    /**
     * Welding to something you are flying past would be a grappling hook, not
     * an assembly mechanic.
     */
    @Test
    fun `a craft moving quickly past another does not weld to it`() {
        val world = world()
        val base = world.spawnOnSurface(StockCraft.lander(catalog), site)
        val flyby = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 1)
        placeBeside(world, flyby, base, 3.0)

        val up = Vec3().setTo(flyby.body.position).normalizeInPlace()
        flyby.body.linearVelocity.addScaledInPlace(up, 25.0)

        assertNull("25 m/s is not an assembly", world.joinToNeighbour(base))
        assertEquals(2, world.vessels.size)
    }

    @Test
    fun `a merged craft carries both halves' mass`() {
        val world = world()
        val base = world.spawnOnSurface(StockCraft.lander(catalog), site)
        val module = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 1)
        placeBeside(world, module, base, 3.0)

        val expected = base.body.mass + module.body.mass
        val merged = world.joinToNeighbour(base)!!
        assertEquals("mass should be conserved", expected, merged.body.mass, 1.0)
    }
}
