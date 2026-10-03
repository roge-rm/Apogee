package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legs take time to come down, and the ground meets them where they are as they swing. */
class LandingGearTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun legs(vessel: Vessel) = vessel.defs.indices.filter { vessel.defs[it].hasModule<LandingLeg>() }

    /** [vessel] with its gear up and folded, set down where it is. */
    private fun World.gearUp(vessel: Vessel) {
        vessel.control.gear = false
        for (i in legs(vessel)) vessel.setLegDeploy(i, 0.0)
        setDown(vessel)
    }

    @Test
    fun `legs swing up and down with the gear over their deploy time`() {
        val world = World.default(catalog)
        val lander = Aloft.spawn(world, StockCraft.lander(catalog), 500.0, bodyId = "luna")
        val legs = legs(lander)
        assertTrue(legs.isNotEmpty())
        for (i in legs) assertEquals("down to start", 1.0, lander.legDeploy[i], 1e-9)
        val deployTime = lander.defs[legs.first()].module<LandingLeg>()!!.deployTime
        world.apply(Command.SetGear(lander.id.raw, false))
        repeat((deployTime * 1.2 / dt).toInt()) { world.step(dt) }
        for (i in legs) assertEquals("up", 0.0, lander.legDeploy[i], 1e-9)
        world.apply(Command.SetGear(lander.id.raw, true))
        repeat((deployTime * 0.5 / dt).toInt()) { world.step(dt) }
        for (i in legs) assertEquals("half way down", 0.5, lander.legDeploy[i], 0.05)
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
            if (deployed) world.gearDown(lander) else world.gearUp(lander)
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
        world.gearUp(lander)
        repeat(120) { world.step(dt) }
        val attractor = world.attractorFor(lander)
        fun height(): Double {
            val up = Vec3().setTo(lander.body.position).normalizeInPlace()
            return lander.body.position.length -
                attractor.surfaceRadiusInBodyFrame(attractor.toBodyFixed(up, attractor.rotationAt(world.time)))
        }
        val before = height()
        // Through the command, the way the game does it. It wakes a parked craft.
        world.apply(Command.SetGear(lander.id.raw, true))
        repeat(300) { world.step(dt) }
        assertTrue("it should be standing on its legs now: ${height()} m against $before m", height() - before > 0.3)
        assertTrue("and still standing", world.vessel(lander.id) != null)
    }
}
