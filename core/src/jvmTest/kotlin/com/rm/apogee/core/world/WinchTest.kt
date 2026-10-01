package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.part.Winch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The winch drags in a craft it's hooked to, drags itself along the ground, does nothing slack,
 * and snaps on a hard yank.
 */
class WinchTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** [design] on the runway, [east] metres along it, facing east. */
    private fun onRunway(world: World, design: CraftDesign, east: Double): Vessel {
        val d = SolarSystem.capeDirection(east, -400.0)
        return world.spawnOnSurface(design, LaunchSite("runway", "Runway", "terra", SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d)))
    }

    private fun settle(world: World, seconds: Double = 5.0) = repeat((seconds / dt).toInt()) { world.step(dt) }

    private fun bodyFixed(world: World, v: Vessel): Vec3 {
        val terra = world.attractorFor(v)
        return terra.toBodyFixed(v.body.position, terra.rotationAt(world.time), Vec3())
    }

    private fun gap(world: World, a: Vessel, b: Vessel) = bodyFixed(world, a).distanceTo(bodyFixed(world, b))

    @Test
    fun `the Hauler carries a winch`() {
        assertTrue(StockCraft.hauler(catalog).parts.any { catalog[it.partId]?.module<Winch>() != null })
    }

    @Test
    fun `hooked onto a buggy ahead, it winds it in`() {
        val world = World.default(catalog)
        val hauler = onRunway(world, StockCraft.hauler(catalog), 600.0)
        val buggy = onRunway(world, StockCraft.buggy(catalog), 622.0)
        settle(world)
        val target = world.hookTarget(hauler)
        assertNotNull("the buggy is in reach", target)
        assertEquals(buggy.id, target!!.craft?.id)
        world.apply(Command.Hook(hauler.id.raw))
        assertNotNull(world.lineOf(hauler))
        val before = gap(world, hauler, buggy)
        world.apply(Command.Reel(hauler.id.raw, 1))
        settle(world, 20.0)
        val after = gap(world, hauler, buggy)
        assertTrue("wound in from $before to $after m", after < before - 6.0)
    }

    @Test
    fun `hooked onto the ground, it drags itself along`() {
        val world = World.default(catalog)
        val hauler = onRunway(world, StockCraft.hauler(catalog), 600.0)
        settle(world)
        val target = world.hookTarget(hauler)
        assertNotNull(target)
        assertNull("nothing else near, so the ground", target!!.craft)
        val start = bodyFixed(world, hauler)
        world.apply(Command.Hook(hauler.id.raw))
        world.apply(Command.Reel(hauler.id.raw, 1))
        settle(world, 15.0)
        assertTrue("moved ${bodyFixed(world, hauler).distanceTo(start)} m", bodyFixed(world, hauler).distanceTo(start) > 5.0)
    }

    @Test
    fun `a slack line pulls nothing`() {
        val world = World.default(catalog)
        val hauler = onRunway(world, StockCraft.hauler(catalog), 600.0)
        val buggy = onRunway(world, StockCraft.buggy(catalog), 622.0)
        settle(world)
        world.apply(Command.Hook(hauler.id.raw))
        world.apply(Command.Reel(hauler.id.raw, -1))
        val before = gap(world, hauler, buggy)
        settle(world, 10.0)
        assertFalse(world.lineOf(hauler)!!.taut)
        assertEquals(before, gap(world, hauler, buggy), 0.3)
    }

    @Test
    fun `a hard yank snaps the line`() {
        val world = World.default(catalog)
        val hauler = onRunway(world, StockCraft.hauler(catalog), 600.0)
        val buggy = onRunway(world, StockCraft.buggy(catalog), 622.0)
        settle(world)
        world.apply(Command.Hook(hauler.id.raw))
        // The buggy flung away east at 30 m/s.
        val east = world.attractorFor(buggy).surfaceVelocityAt(buggy.body.position, Vec3()).normalizeInPlace()
        buggy.wake()
        buggy.body.linearVelocity.addScaledInPlace(east, 30.0)
        settle(world, 3.0)
        assertNull("snapped", world.lineOf(hauler))
    }

    @Test
    fun `a line survives a save`() {
        val world = World.default(catalog)
        val hauler = onRunway(world, StockCraft.hauler(catalog), 600.0)
        onRunway(world, StockCraft.buggy(catalog), 622.0)
        settle(world)
        world.apply(Command.Hook(hauler.id.raw))
        world.apply(Command.Reel(hauler.id.raw, 1))
        val restored = World.default(catalog)
        restored.restore(world.save())
        val line = restored.winchLines.single()
        assertEquals(hauler.id, line.a)
        assertEquals(1, line.reel)
    }
}
