package com.rm.apogee.core.sea

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.LaunchSite
import com.rm.apogee.core.world.SeaWonders
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ocean currents: running in the open sea, calm in harbours, bays and protected places, the same
 * wherever they're worked out, and carrying what floats in them.
 */
class CurrentsTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun terraSea(): Sea {
        val system = SolarSystem.defaultSystem()
        val terra = system.body("terra")!!
        return Sea(terra, system.body("luna"), null, 0)
    }

    private val radius = SolarSystem.defaultSystem().body("terra")!!.radius

    private fun speedAt(sea: Sea, d: Vec3, below: Double = 0.0): Double =
        sea.current(d.copy().mulInPlace(radius), below, Vec3()).length

    /** The strongest current on a grid of points [reach] metres around the Cape, and where. */
    private fun strongest(sea: Sea, reach: Double = 250_000.0, step: Double = 25_000.0): Pair<Vec3, Double> {
        var best = Vec3() to 0.0
        var e = -reach
        while (e <= reach) {
            var n = -reach
            while (n <= reach) {
                val d = SolarSystem.capeDirection(e, n)
                val s = speedAt(sea, d)
                if (s > best.second) best = d to s
                n += step
            }
            e += step
        }
        return best
    }

    @Test
    fun `the open sea has currents, a few tenths of a metre a second`() {
        val (_, speed) = strongest(terraSea())
        assertTrue("strongest $speed m/s", speed > 0.2 && speed < 2.5)
    }

    @Test
    fun `the harbour, the bay, the sea launch sites and the named places are calm`() {
        val sea = terraSea()
        for (site in World.launchSites.filter { it.bodyId == "terra" && it.id in setOf("harbour", "arch-sea", "chimneys-sea") }) {
            val d = SolarSystem.surfaceDirection(site.latitude, site.longitude)
            assertEquals("at ${site.id}", 0.0, speedAt(sea, d), 0.02)
        }
        for (wonder in SeaWonders.all.filter { it.bodyId == "terra" }) {
            assertEquals("at ${wonder.id}", 0.0, speedAt(sea, wonder.direction, below = 0.0), 0.02)
        }
        // The broad bay beside the Cape.
        assertEquals(0.0, speedAt(sea, SolarSystem.capeDirection(3_500.0, 300.0)), 0.02)
    }

    @Test
    fun `currents are the same wherever they're worked out`() {
        val a = terraSea(); val b = terraSea()
        val d = strongest(a).first
        assertEquals(0.0, a.current(d.copy().mulInPlace(radius), 0.0, Vec3()).distanceTo(b.current(d.copy().mulInPlace(radius), 0.0, Vec3())), 1e-12)
    }

    @Test
    fun `the map's rough reading is the same out in the open sea`() {
        val sea = terraSea()
        val (d, speed) = strongest(sea)
        val rough = sea.roughCurrent(d.copy().mulInPlace(radius), Vec3())
        val full = sea.current(d.copy().mulInPlace(radius), 0.0, Vec3())
        assertTrue("rough ${rough.length}, full $speed", rough.distanceTo(full) < 0.1 * speed)
    }

    @Test
    fun `they fade with depth`() {
        val sea = terraSea()
        val d = strongest(sea).first
        assertTrue(speedAt(sea, d, below = 600.0) < 0.05 * speedAt(sea, d))
    }

    @Test
    fun `a boat drifting in a current goes with it, and one left alone anchors`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")!!
        val sea = terra.ocean!!.sea!!
        val (d, speed) = strongest(sea)
        val site = LaunchSite("sea", "Sea", "terra", SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d))
        val skiff = world.spawnOnSurface(StockCraft.skiff(catalog), site)
        world.assignOwner(skiff, "p1")
        world.seatCrew(skiff)
        world.apply(Command.Stage(skiff.id.raw))
        // Just enough throttle that it's not left alone, and no real push.
        world.apply(Command.SetThrottle(skiff.id.raw, 1e-4))
        repeat((60.0 / dt).toInt()) { world.step(dt) }
        val ground = skiff.body.linearVelocity.copy().subInPlace(terra.surfaceVelocityAt(skiff.body.position, Vec3()))
        val current = world.currentAt(skiff)
        assertTrue("current $speed m/s", current.length > 0.15)
        assertTrue("drifting ${ground.length} m/s in a ${current.length} m/s current", ground.distanceTo(current) < 0.1 * current.length + 0.02)

        // Engine off: it's left alone, so it drops anchor and sleeps, and stays where it is.
        world.apply(Command.SetThrottle(skiff.id.raw, 0.0))
        repeat((60.0 / dt).toInt()) { world.step(dt) }
        assertTrue("asleep", skiff.dormant)
        val rotation = terra.rotationAt(world.time)
        val here = terra.toBodyFixed(skiff.body.position, rotation)
        repeat((30.0 / dt).toInt()) { world.step(dt) }
        val later = terra.toBodyFixed(skiff.body.position, terra.rotationAt(world.time))
        assertTrue("moved ${here.distanceTo(later)} m asleep", here.distanceTo(later) < 1.0)
    }
}
