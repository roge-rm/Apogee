package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arriving, as distinct from leaving.
 *
 * The ascent scenario proves a craft can get off the ground. Nothing proved
 * it could come back down onto it, and until terrain landed the only surface
 * anything had ever touched was a pad we deliberately made dead level. These
 * drop a lander from a known height at a known speed and ask what survived.
 */
class LandingTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun world() = World.default(catalog)

    /**
     * Puts a lander [height] metres over the pad, falling at [descentRate],
     * with [gearDown] deciding whether the legs are deployed.
     *
     * Dropped rather than flown: a scripted descent would test the autopilot
     * as much as the contact solver, and it is the contact solver on trial.
     */
    private fun drop(
        world: World,
        height: Double,
        descentRate: Double,
        gearDown: Boolean,
        lateralDrift: Double = 0.0,
    ): Vessel {
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), World.launchSites.first())
        // Stage 0 lights the engine, 1 pops the chute, 2 drops the gear. Only
        // the gear matters here, so skip straight to it - but only if it is
        // meant to be down.
        if (gearDown) repeat(3) { world.stage(vessel) }
        vessel.control.throttle = 0.0

        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        vessel.body.position.addScaledInPlace(up, height)

        // Start on the surface's own velocity, then add the descent, so the
        // craft is falling relative to the ground rather than relative to an
        // inertial frame the ground is moving through at 175 m/s.
        val attractor = world.attractorFor(vessel)
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.linearVelocity.addScaledInPlace(up, -descentRate)
        if (lateralDrift != 0.0) {
            val east = Vec3.unitY().cross(up).normalizeInPlace()
            vessel.body.linearVelocity.addScaledInPlace(east, lateralDrift)
        }
        return vessel
    }

    /** Runs until the craft settles, is destroyed, or [seconds] elapse. */
    private fun settle(world: World, id: com.rm.apogee.core.craft.VesselId, seconds: Double) {
        repeat((seconds / dt).toInt()) {
            world.step(dt)
            val vessel = world.vessel(id) ?: return
            val attractor = world.attractorFor(vessel)
            attractor.surfaceVelocityAt(vessel.body.position, scratch)
            val relative = Vec3().setTo(vessel.body.linearVelocity).subInPlace(scratch)
            if (relative.length < 0.05 && vessel.body.angularVelocity.length < 0.02) return
        }
    }

    private val scratch = Vec3()

    /** How far a vessel's nose has tipped from straight up, degrees. */
    private fun tiltDegrees(world: World, vessel: Vessel): Double {
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        val nose = vessel.forward()
        return Math.toDegrees(kotlin.math.acos((nose dot up).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `a lander on its gear survives a touchdown that would otherwise break it`() {
        val world = world()
        val vessel = drop(world, height = 12.0, descentRate = 6.0, gearDown = true)
        val id = vessel.id
        settle(world, id, 30.0)

        val landed = world.vessel(id)
        assertNotNull("the lander should have survived on its gear", landed)
        assertTrue(
            "it should still be upright, tilted ${tiltDegrees(world, landed!!)} degrees",
            tiltDegrees(world, landed) < 15.0,
        )
        assertFalse(
            "no leg should have collapsed at this descent rate",
            landed.broken.any { it },
        )
    }

    /**
     * The control for the test above. Gear has to be the difference, not the
     * descent rate being survivable anyway.
     */
    @Test
    fun `the same touchdown with the gear up destroys the craft`() {
        val world = world()
        val vessel = drop(world, height = 12.0, descentRate = 6.0, gearDown = false)
        val id = vessel.id
        settle(world, id, 30.0)

        assertNull("landing on the engine bell at 6 m/s should not be survivable", world.vessel(id))
        val destroyed = world.drainEvents().filterIsInstance<WorldEvent.VesselDestroyed>()
        assertTrue("should report what failed", destroyed.any { it.id == id })
    }

    @Test
    fun `a leg collapses rather than the craft exploding when it is hit too hard`() {
        val world = world()
        // Past the leg's 18 m/s tolerance but nowhere near orbital.
        val vessel = drop(world, height = 40.0, descentRate = 22.0, gearDown = true)
        val id = vessel.id
        val events = ArrayList<WorldEvent>()
        repeat(600) {
            world.step(dt)
            events.addAll(world.drainEvents())
        }

        val failures = events.filterIsInstance<WorldEvent.PartFailed>()
        assertTrue(
            "a leg should have failed before anything else did",
            failures.isNotEmpty(),
        )
        assertTrue(
            "and it should be the legs that gave way, not the airframe",
            failures.all { vessel.defs[it.partIndex].id == "leg-stilt" },
        )
        // Sacrificing the gear is the gear doing its job. The craft is a
        // write-off either way, but it is a write-off standing on the ground
        // rather than a crater.
        assertNotNull("the craft should have survived on collapsed legs", world.vessel(id))
    }

    /**
     * The thing the pad could never test. Terrain is levelled for 200 m around
     * the launch complex, so a landing on real ground has to happen elsewhere.
     */
    @Test
    fun `a lander settles on sloping ground without sliding away`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), World.launchSites.first())
        repeat(3) { world.stage(vessel) }
        vessel.control.throttle = 0.0

        // Move it well clear of the levelled pad, onto ground with real relief,
        // and re-seat it on the surface there.
        val attractor = world.attractorFor(vessel)
        val field = attractor.terrain!!
        val rotation = attractor.rotationAt(world.time)
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        val east = Vec3.unitY().cross(up).normalizeInPlace()
        val moved = Vec3().setTo(up).addScaledInPlace(east, 8_000.0 / attractor.radius)
            .normalizeInPlace()

        val bodyFixed = attractor.toBodyFixed(moved, rotation)
        val ground = field.surfaceRadius(bodyFixed)
        vessel.body.position.setTo(moved).mulInPlace(ground + 6.0)
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.angularVelocity.setTo(Vec3.zero())

        val id = vessel.id
        settle(world, id, 40.0)

        val landed = world.vessel(id)
        assertNotNull("the lander should survive a six-metre drop onto a hillside", landed)

        // It may lean with the slope; it must not still be moving.
        attractor.surfaceVelocityAt(landed!!.body.position, scratch)
        val drift = Vec3().setTo(landed.body.linearVelocity).subInPlace(scratch).length
        assertTrue("it should have come to rest, still drifting $drift m/s", drift < 0.5)
        assertTrue(
            "it should not have toppled, tilted ${tiltDegrees(world, landed)} degrees",
            tiltDegrees(world, landed) < 40.0,
        )
    }

    @Test
    fun `a parachute deployed too fast tears away instead of stopping the craft`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), World.launchSites.first())
        val chuteIndex = vessel.defs.indexOfFirst {
            it.module<com.rm.apogee.core.part.Parachute>() != null
        }
        assertTrue("the lander should carry a chute", chuteIndex >= 0)
        repeat(2) { world.stage(vessel) }

        // Well past the canopy's 300 m/s rating, low enough for real air.
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        vessel.body.position.addScaledInPlace(up, 6_000.0)
        val attractor = world.attractorFor(vessel)
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.linearVelocity.addScaledInPlace(up, -450.0)

        repeat(30) { world.step(dt) }

        assertTrue("the canopy should have torn away", vessel.isBroken(chuteIndex))
        assertEquals(
            "and it should say so",
            1,
            world.drainEvents().filterIsInstance<WorldEvent.PartFailed>()
                .count { it.partIndex == chuteIndex },
        )
    }
}
