package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legs take time to deploy, and the ground meets them where they are as they swing. */
class LandingGearTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun legs(vessel: Vessel) = vessel.defs.indices.filter { vessel.defs[it].hasModule<LandingLeg>() }

    @Test
    fun `legs swing out over their deploy time once staged`() {
        val world = World.default(catalog)
        val lander = world.spawnOnSurface(StockCraft.lander(catalog), World.launchSites.first())
        val legs = legs(lander)
        assertTrue(legs.isNotEmpty())
        repeat(30) { world.step(dt) }
        for (i in legs) assertEquals("not staged: stowed", 0.0, lander.legDeploy[i], 1e-9)

        repeat(3) { world.stage(lander) }
        val deployTime = lander.defs[legs.first()].module<LandingLeg>()!!.deployTime
        repeat((deployTime * 0.5 / dt).toInt()) { world.step(dt) }
        for (i in legs) assertEquals("half way through its deploy", 0.5, lander.legDeploy[i], 0.05)
        repeat((deployTime / dt).toInt()) { world.step(dt) }
        for (i in legs) assertEquals("out", 1.0, lander.legDeploy[i], 1e-9)
    }

    /**
     * On its legs the lander stands on them. Stowed, it sits lower on its engine bell, so contact
     * is where the drawn leg is.
     */
    @Test
    fun `a lander stands higher on deployed legs than on stowed ones`() {
        fun restingHeight(deployed: Boolean): Double {
            val world = World.default(catalog)
            val lander = world.spawnOnSurface(StockCraft.lander(catalog), World.launchSites.first())
            if (deployed) world.gearDown(lander)
            repeat(360) { world.step(dt) }
            val attractor = world.attractorFor(lander)
            val up = Vec3().setTo(lander.body.position).normalizeInPlace()
            val bodyFixed = attractor.toBodyFixed(up, attractor.rotationAt(world.time))
            return lander.body.position.length - attractor.surfaceRadiusInBodyFrame(bodyFixed)
        }
        val stowed = restingHeight(deployed = false)
        val out = restingHeight(deployed = true)
        assertTrue("on its legs ($out m) it should stand clear of where it sits stowed ($stowed m)", out - stowed > 0.3)
    }

    /** Legs swinging down on the ground lift the craft onto them, not pass through the ground. */
    @Test
    fun `deploying on the ground stands the craft up on its legs`() {
        val world = World.default(catalog)
        val lander = world.spawnOnSurface(StockCraft.lander(catalog), World.launchSites.first())
        repeat(120) { world.step(dt) }
        val attractor = world.attractorFor(lander)
        fun height(): Double {
            val up = Vec3().setTo(lander.body.position).normalizeInPlace()
            return lander.body.position.length -
                attractor.surfaceRadiusInBodyFrame(attractor.toBodyFixed(up, attractor.rotationAt(world.time)))
        }
        val before = height()
        // Through the command, the way the game does it. Staging a parked craft wakes it.
        repeat(3) { world.apply(Command.Stage(lander.id.raw)) }
        repeat(300) { world.step(dt) }
        assertTrue("it should be standing on its legs now: ${height()} m against $before m", height() - before > 0.3)
        assertTrue("and still standing", world.vessel(lander.id) != null)
    }
}
