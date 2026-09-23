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
        if (gearDown) world.gearDown(vessel)
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

    /**
     * Legs still swinging out are not yet on their springs: the same 6 m/s
     * touchdown that deployed legs soak up breaks something when it arrives
     * half way through the deploy. Too late is not the same as down.
     */
    @Test
    fun `touching down while the legs are still deploying is a hard landing`() {
        val world = world()
        val vessel = drop(world, height = 2.0, descentRate = 6.0, gearDown = false)
        val id = vessel.id
        // Deployed just before touchdown: a third of a second of a 1.5 s swing.
        repeat(3) { world.stage(vessel) }
        settle(world, id, 30.0)

        val landed = world.vessel(id)
        assertTrue(
            "a mid-deploy touchdown at 6 m/s should break something",
            landed == null || landed.broken.any { it },
        )
    }

    @Test
    fun `a leg collapses rather than the craft exploding when it is hit too hard`() {
        val world = world()
        // Just past the leg's 18 m/s tolerance. Fast enough to break the
        // gear, slow enough that what is left of the craft survives the drop
        // onto its engine bell once the legs have given way.
        val vessel = drop(world, height = 1.0, descentRate = 19.0, gearDown = true)
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
     * The tunnelling regression.
     *
     * A tick is a sixtieth of a second, so a craft arriving at sixty metres a
     * second covers a full metre in one. The lander's feet reach 0.6 m below
     * its engine bell, and with a single contact sample per tick that whole
     * margin is stepped over: the first thing the solver sees is the legs
     * *and* the engine already buried, both in the same tick, and the gear
     * never gets a chance to be the thing that arrives first.
     *
     * Measured as separation in ticks rather than as who failed, because the
     * gear always shows up in the failure list either way - what tunnelling
     * destroys is the *order*, and with it any possibility of the suspension
     * doing its job before the airframe reaches the ground.
     */
    @Test
    fun `gear touches down a measurable moment before the airframe does`() {
        // Fast enough that one tick of travel exceeds the legs' reach below
        // the bell, which is the regime where a single sample per tick fails.
        for (descentRate in listOf(45.0, 60.0)) {
            val world = world()
            val vessel = drop(world, height = 2.0, descentRate = descentRate, gearDown = true)
            val id = vessel.id

            var gearTick = -1
            var airframeTick = -1
            for (tick in 0 until 240) {
                world.step(dt)
                for (event in world.drainEvents()) {
                    if (event is WorldEvent.PartFailed && gearTick < 0) gearTick = tick
                    if (event is WorldEvent.VesselDestroyed && airframeTick < 0) {
                        airframeTick = tick
                    }
                }
                if (airframeTick >= 0) break
            }

            assertTrue("at $descentRate m/s the gear never registered", gearTick >= 0)
            // Whether the craft ultimately survives is not the point - the
            // gear may absorb the whole arrival, which is a fine outcome. What
            // must never happen is the airframe being written off in the same
            // tick the gear first touches, because that means the solver
            // stepped straight past the six hundred millimetres between them.
            assertTrue(
                "at $descentRate m/s the airframe was written off in the same " +
                    "tick the gear touched ($gearTick): the legs were stepped over",
                airframeTick < 0 || airframeTick > gearTick,
            )
            // And it must not be gone before the gear was ever blamed.
            assertTrue(
                "at $descentRate m/s the craft died at tick $airframeTick with " +
                    "the gear first seen at $gearTick",
                airframeTick < 0 || gearTick < airframeTick,
            )
            assertNotNull("sanity: the vessel id should be stable", id)
        }
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
        val north = up.cross(east).normalizeInPlace()

        // Found, not assumed: a patch of genuinely sloping ground - four to
        // eight degrees, the kind of hillside a lander would pick - somewhere
        // out past the levelled pad. Assuming "eight kilometres east" is a
        // hillside stopped being true the day the terrain grew cliffs. Steeper
        // is a different claim: at eleven degrees this tall, narrow lander
        // survives the drop, bounces, and topples - which is physics, not a
        // collider fault.
        val terrainField = field as com.rm.apogee.core.terrain.TerrainField
        var moved: Vec3? = null
        search@ for (ring in 4..30) for (step in 0 until 24) {
            val angle = step * Math.PI / 12
            val d = Vec3().setTo(up)
                .addScaledInPlace(east, ring * 500.0 * kotlin.math.cos(angle) / attractor.radius)
                .addScaledInPlace(north, ring * 500.0 * kotlin.math.sin(angle) / attractor.radius)
                .normalizeInPlace()
            val bf = attractor.toBodyFixed(d, rotation)
            val normal = terrainField.surfaceNormal(bf, sample = 6.0)
            val slope = Math.toDegrees(kotlin.math.acos((normal dot bf.normalized()).coerceIn(-1.0, 1.0)))
            if (slope !in 4.0..8.0) continue
            // And smooth under the legs, as a pilot would choose it. The
            // ground has metre-scale relief now, and this lander - centre of
            // mass 2.6 m above feet that reach 0.8 m to its tipping edge -
            // goes over at about seventeen degrees, which a bumpy six-degree
            // hillside can reach under one leg.
            val footprintEast = Vec3.unitY().cross(bf.normalized()).normalizeInPlace()
            val footprintNorth = bf.normalized().cross(footprintEast)
            val ground = com.rm.apogee.core.terrain.GroundPoint()
            val look = com.rm.apogee.core.terrain.TerrainTileCache.Lookup()
            var smooth = true
            for (i in -3..3) for (j in -3..3) {
                val p = bf.normalized()
                    .addScaledInPlace(footprintEast, i * 0.7 / attractor.radius)
                    .addScaledInPlace(footprintNorth, j * 0.7 / attractor.radius)
                terrainField.tiles.ground(p, ground, look)
                val facet = Math.toDegrees(kotlin.math.acos((ground.normal dot p.normalized()).coerceIn(-1.0, 1.0)))
                if (facet > 9.0) smooth = false
            }
            if (smooth) { moved = d; break@search }
        }
        requireNotNull(moved) { "no hillside found near the pad" }

        val bodyFixed = attractor.toBodyFixed(moved, rotation)
        val ground = field.surfaceRadius(bodyFixed)
        // Two metres, a set-down rather than a drop. The ground has metre-scale
        // relief now - facets under the legs of up to ten degrees on a
        // six-degree hillside - and falling six metres onto one leg spun this
        // tall, narrow lander over, which is a fair result for a bad landing
        // but not what this test is about.
        vessel.body.position.setTo(moved).mulInPlace(ground + 2.0)
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.angularVelocity.setTo(Vec3.zero())

        val id = vessel.id
        settle(world, id, 40.0)

        val landed = world.vessel(id)
        assertNotNull("the lander should survive being set down on a hillside", landed)

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
