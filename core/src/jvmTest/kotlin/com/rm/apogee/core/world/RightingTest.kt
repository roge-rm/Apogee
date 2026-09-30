package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A small craft that's gone over, a boat keel up or a buggy on its roof, can be rolled back upright
 * by its crew. A big one can't, and nothing that's upright is offered it.
 */
class RightingTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** How far its deck is from upright, in degrees. */
    private fun tilt(vessel: Vessel): Double {
        val deck = vessel.body.orientation.rotate(vessel.design.orientation.up, Vec3())
        return Math.toDegrees(kotlin.math.acos((deck dot vessel.body.position.normalized()).coerceIn(-1.0, 1.0)))
    }

    /** [design] launched at [siteId], left to settle, then turned over about its length. */
    private fun overturned(design: CraftDesign, siteId: String, settle: Double = 10.0, world: World = World.default(catalog)): Pair<World, Vessel> {
        val craft = world.spawnOnSurface(design, World.launchSites.first { it.id == siteId })
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        // Asleep, it would be put back the way it went to sleep.
        craft.wake()
        val ahead = craft.body.orientation.rotate(design.orientation.forward, Vec3())
        craft.body.orientation.setTo(Quat.fromAxisAngle(ahead, Math.PI) * craft.body.orientation).normalizeInPlace()
        if (craft.touchingGround && !craft.buoyed) world.setDown(craft)
        repeat((settle / dt).toInt()) { world.step(dt) }
        return world to craft
    }

    @Test
    fun `a Jet Boat keel up stays that way, until her crew right her`() {
        val (world, boat) = overturned(StockCraft.jetBoat(catalog), "harbour")
        assertTrue("she came back up by herself, at ${tilt(boat)} degrees", tilt(boat) > 150.0)
        assertTrue(world.canRight(boat))
        world.apply(Command.RightCraft(boat.id.raw))
        repeat((6.0 / dt).toInt()) { world.step(dt) }
        assertTrue("she's still at ${tilt(boat)} degrees", tilt(boat) < 20.0)
        assertTrue("not afloat", boat.buoyed && !boat.submerged)
        assertTrue("broke: ${boat.broken.count { it }}", boat.broken.none { it })
        assertFalse("offered again the right way up", world.canRight(boat))
    }

    @Test
    fun `one rolled over by the Roaring Sea can be righted there`() {
        val world = World.default(catalog)
        world.weatherConfig = WeatherConfig(intensity = WeatherIntensity.WILD)
        // A start the sea rolls her over from, idling side on to it.
        world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = 40_000.0, nextVesselId = 1L))
        val boat = world.spawnOnSurface(StockCraft.jetBoat(catalog), World.launchSites.first { it.id == "roaring-sea" })
        var seconds = 0
        while (!world.canRight(boat) && seconds < 300) { repeat(60) { world.step(dt) }; seconds++ }
        assertTrue("she never went over, or couldn't be righted", world.canRight(boat))
        world.apply(Command.RightCraft(boat.id.raw))
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        assertTrue("she's still at ${tilt(boat)} degrees", tilt(boat) < 45.0)
        assertTrue("she went down", boat.buoyed && !boat.submerged)
    }

    @Test
    fun `a buggy on its roof goes back onto its wheels`() {
        val (world, buggy) = overturned(StockCraft.buggy(catalog), "cape")
        assertTrue("it's at ${tilt(buggy)} degrees", tilt(buggy) > 150.0)
        assertTrue(world.canRight(buggy))
        world.apply(Command.RightCraft(buggy.id.raw))
        repeat((8.0 / dt).toInt()) { world.step(dt) }
        assertTrue("it's at ${tilt(buggy)} degrees", tilt(buggy) < 15.0)
        assertTrue("not on the ground", buggy.touchingGround)
        assertTrue("broke: ${buggy.broken.count { it }}", buggy.broken.none { it })
    }

    @Test
    fun `nothing upright is offered it, and nothing big`() {
        val world = World.default(catalog)
        val boat = world.spawnOnSurface(StockCraft.jetBoat(catalog), World.launchSites.first { it.id == "harbour" })
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        assertFalse("offered upright", world.canRight(boat))
        // Asked straight away, because left alone the Cutter comes back up by herself.
        val (_, cutter) = overturned(StockCraft.cutter(catalog), "harbour", settle = dt)
        assertTrue("the Cutter's at ${tilt(cutter)} degrees", tilt(cutter) > 75.0)
        assertFalse("offered on a ${cutter.body.mass} kg Cutter", world.canRight(cutter))
    }
}
