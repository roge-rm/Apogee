package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The auto-land: from a fall, down onto its legs, still and upright. */
class AutoLandTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /**
     * [design] [height] m over pad [pad] of [siteId], falling at [falling] m/s and drifting east at
     * [drifting] m/s, with its first stage (the engine) lit.
     */
    private fun overSite(world: World, design: CraftDesign, siteId: String, pad: Int, height: Double, falling: Double, drifting: Double): Vessel {
        val site = World.launchSites.first { it.id == siteId }
        val craft = world.spawnOnSurface(design, site, pad)
        world.stage(craft)
        val body = world.attractorFor(craft)
        craft.wake()
        val up = craft.body.position.copy().normalizeInPlace()
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        craft.body.position.addScaledInPlace(up, height)
        body.surfaceVelocityAt(craft.body.position, craft.body.linearVelocity)
            .addScaledInPlace(up, -falling).addScaledInPlace(east, drifting)
        return craft
    }

    /** The fastest the last [land] climbed on the way down, in m/s. */
    private var climbed = 0.0

    /** Flies it down with the auto-land, and returns the hardest it touched down, in m/s. */
    private fun land(world: World, craft: Vessel, limit: Double = 400.0): Double {
        world.apply(Command.SetAutopilot(craft.id.raw, autoBurn = false, autoLand = true))
        var hardest = 0.0
        var t = 0.0
        val body = world.attractorFor(craft)
        while (craft.control.autoLand && t < limit) {
            world.step(dt); t += dt
            // Down all the way, never climbing on the way.
            val up = craft.body.position.copy().normalizeInPlace()
            val climb = craft.body.linearVelocity.copy().subInPlace(body.surfaceVelocityAt(craft.body.position, Vec3())) dot up
            climbed = maxOf(climbed, climb)
            for (event in world.drainEvents()) if (event is WorldEvent.Touchdown && event.id == craft.id) hardest = maxOf(hardest, event.impactSpeed)
        }
        return hardest
    }

    private fun tilt(craft: Vessel): Double =
        Math.toDegrees(kotlin.math.acos((craft.forward() dot craft.body.position.copy().normalizeInPlace()).coerceIn(-1.0, 1.0)))

    @Test
    fun `the Stilt Lander sets itself down on the mare from a fast fall`() {
        val world = World.default(catalog)
        val craft = overSite(world, StockCraft.lander(catalog), "luna-mare", 2, 3_000.0, 50.0, 60.0)
        // The chute is the next stage, and it's useless here. The legs come after it.
        world.stage(craft)
        val hardest = land(world, craft)
        assertEquals("did not finish: ${craft.control.autopilotNote}", "Landed", craft.control.autopilotNote)
        assertTrue("touched down at $hardest m/s", hardest < 3.0)
        assertTrue("climbed at $climbed m/s on the way down", climbed < 2.0)
        assertTrue("landed tilted ${tilt(craft)} degrees", tilt(craft) < 6.0)
        assertFalse("broke something", craft.broken.any { it })
        assertEquals("throttle left open", 0.0, craft.control.throttle, 0.0)
    }

    @Test
    fun `the Base Core Lander lands itself, and the base can be founded there`() {
        val world = World.default(catalog)
        val craft = overSite(world, StockCraft.baseCoreLander(catalog), "luna-mare", 2, 1_500.0, 30.0, 20.0)
        val hardest = land(world, craft)
        assertEquals("did not finish: ${craft.control.autopilotNote}", "Landed", craft.control.autopilotNote)
        assertTrue("touched down at $hardest m/s", hardest < 3.0)
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        assertTrue("could not found it where it landed", world.anchor(craft))
    }

    @Test
    fun `it won't try to land on Terra with a vacuum engine too weak to hold it up`() {
        val world = World.default(catalog)
        val craft = overSite(world, StockCraft.lander(catalog), "cape", 3, 2_000.0, 0.0, 0.0)
        world.apply(Command.SetAutopilot(craft.id.raw, autoBurn = false, autoLand = true))
        world.step(dt)
        assertFalse(craft.control.autoLand)
        assertEquals("Too little thrust to land here", craft.control.autopilotNote)
    }
}
