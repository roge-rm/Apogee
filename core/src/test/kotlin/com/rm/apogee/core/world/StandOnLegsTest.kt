package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lander a player launches stands on its legs, not on its engine bell. */
class StandOnLegsTest {
    private val catalog = StockParts.catalog
    private val luna = World.launchSites.first { it.id == World.LUNA_TEST_SITE }
    private val dt = 1.0 / 60.0

    private fun legs(v: com.rm.apogee.core.craft.Vessel) = v.defs.indices.filter { v.defs[it].hasModule<LandingLeg>() }

    @Test
    fun `a lander set down on Luna stands on its legs, and stays standing`() {
        val world = World.default(catalog)
        val lander = world.spawnOnSurface(StockCraft.prospector(catalog), luna, legsOut = true)
        for (i in legs(lander)) {
            assertTrue("leg $i is working", lander.isWorking(i))
            assertEquals(1.0, lander.legDeploy[i], 1e-9)
        }
        val up = lander.body.position.normalized()
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        val tilt = Math.toDegrees(kotlin.math.acos((lander.body.orientation.rotate(lander.design.orientation.up) dot up).coerceIn(-1.0, 1.0)))
        assertTrue("it tipped over: $tilt°", tilt < 5.0)
        val attractor = world.attractorFor(lander)
        val speed = attractor.surfaceVelocityAt(lander.body.position, com.rm.apogee.core.math.Vec3()).subInPlace(lander.body.linearVelocity).length
        assertTrue("it's moving: $speed m/s", speed < 0.3)
    }

    @Test
    fun `set down for anything else it keeps its legs folded`() {
        val world = World.default(catalog)
        val lander = world.spawnOnSurface(StockCraft.prospector(catalog), luna)
        for (i in legs(lander)) assertTrue("leg $i was put out", !lander.isWorking(i))
    }

    @Test
    fun `a rocket with a lander high on its stack keeps the lander's legs folded`() {
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(StockCraft.moonshot(catalog), World.launchSites.first { it.id == "cape" }, legsOut = true)
        for (i in legs(rocket)) {
            assertTrue("leg $i was put out", !rocket.isWorking(i))
            assertEquals(0.0, rocket.legDeploy[i], 1e-9)
        }
    }
}
