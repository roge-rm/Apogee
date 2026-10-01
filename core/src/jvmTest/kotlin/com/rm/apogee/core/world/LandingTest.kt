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

/** Landings: drop a lander from a known height at a known speed and see what survives. */
class LandingTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun world() = World.default(catalog)

    /**
     * Puts a lander [height] metres over the pad, falling at [descentRate], legs out if [gearDown].
     * It's dropped so only the contact solver is under test.
     */
    private fun drop(
        world: World,
        height: Double,
        descentRate: Double,
        gearDown: Boolean,
        lateralDrift: Double = 0.0,
    ): Vessel {
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), World.launchSites.first())
        // Stage 0 lights the engine, 1 the chute, 2 the gear. Only the gear matters here.
        if (gearDown) world.gearDown(vessel)
        vessel.control.throttle = 0.0

        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        vessel.body.position.addScaledInPlace(up, height)

        // Start at the ground's own velocity (it moves at 175 m/s), then add the descent.
        val attractor = world.attractorFor(vessel)
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.linearVelocity.addScaledInPlace(up, -descentRate)
        if (lateralDrift != 0.0) {
            val east = Vec3.unitY().cross(up).normalizeInPlace()
            vessel.body.linearVelocity.addScaledInPlace(east, lateralDrift)
        }
        return vessel
    }

    /** Runs until the craft settles, is destroyed, or [seconds] have passed. */
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

    /** How far a vessel's nose has tipped from straight up, in degrees. */
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
     * Control for the test above: on its engine bell the same touchdown damages the engine, spares
     * the pod, and the craft survives.
     */
    @Test
    fun `the same touchdown with the gear up damages the engine`() {
        val world = world()
        val vessel = drop(world, height = 12.0, descentRate = 6.0, gearDown = false)
        val id = vessel.id
        settle(world, id, 30.0)

        val landed = world.vessel(id)
        assertNotNull("a hard landing is damage, not the end of the craft", landed)
        val engine = landed!!.defs.indices.first { landed.defs[it].id.startsWith("engine") }
        val pod = landed.defs.indices.first { landed.defs[it].id.startsWith("pod") }
        assertTrue("the engine took the blow: ${landed.health[engine]}", landed.health[engine] < 0.8)
        assertTrue("the pod was spared: ${landed.health[pod]}", landed.health[pod] > 0.95)
        assertTrue("it says what was hit", world.drainEvents().any { it is WorldEvent.Impact && it.id == id })
    }

    /** Legs still swinging out aren't on their springs, so a 6 m/s touchdown mid-deploy hurts. */
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
            "a mid-deploy touchdown at 6 m/s should hurt something",
            landed == null || landed.broken.any { it } || landed.health.any { it < 1.0 },
        )
    }

    @Test
    fun `a leg collapses instead of the craft exploding when it's hit too hard`() {
        val world = world()
        // Just past the leg's 18 m/s tolerance: breaks the gear, but the craft survives the drop
        // onto its engine bell.
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
        assertNotNull("the craft should have survived on collapsed legs", world.vessel(id))
    }

    /**
     * Tunnelling. At 60 m/s a tick covers a metre, more than the 0.6 m the feet reach below the
     * bell, so one contact sample per tick buries legs and engine together. Checked as tick order,
     * since the gear shows up in the failure list either way.
     */
    @Test
    fun `gear touches down a measurable moment before the airframe does`() {
        // One tick of travel is more than the legs' reach below the bell.
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
            // Surviving is fine either way. The airframe mustn't be written off in the tick the
            // gear first touches, which would mean the 0.6 m between them was stepped over.
            assertTrue(
                "at $descentRate m/s the airframe was written off in the same " +
                    "tick the gear touched ($gearTick): the legs were stepped over",
                airframeTick < 0 || airframeTick > gearTick,
            )
            // And it mustn't be gone before the gear was ever blamed.
            assertTrue(
                "at $descentRate m/s the craft died at tick $airframeTick with " +
                    "the gear first seen at $gearTick",
                airframeTick < 0 || gearTick < airframeTick,
            )
            assertNotNull("sanity: the vessel id should be stable", id)
        }
    }

    /** The pad is levelled for 200 m, so this lands on real ground elsewhere. */
    @Test
    fun `a lander settles on sloping ground without sliding away`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), World.launchSites.first())
        repeat(3) { world.stage(vessel) }
        vessel.control.throttle = 0.0

        // Move it clear of the levelled pad and seat it on the ground there.
        val attractor = world.attractorFor(vessel)
        val field = attractor.terrain!!
        val rotation = attractor.rotationAt(world.time)
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        val east = Vec3.unitY().cross(up).normalizeInPlace()
        val north = up.cross(east).normalizeInPlace()

        // Search for a 4 to 8 degree hillside past the pad. At 11 degrees this tall, narrow lander
        // topples, which is fair physics.
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
            // And smooth under the legs. The lander (centre of mass 2.6 m up, 0.8 m to its tipping
            // edge) goes over at about 17 degrees, which a bumpy hillside can reach under one leg.
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
        // Set down from 2 m. Falling 6 m onto one leg on bumpy ground tips it over.
        vessel.body.position.setTo(moved).mulInPlace(ground + 2.0)
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.angularVelocity.setTo(Vec3.zero())

        val id = vessel.id
        settle(world, id, 40.0)

        val landed = world.vessel(id)
        assertNotNull("the lander should survive being set down on a hillside", landed)

        // It can lean with the slope, but it mustn't still be moving.
        attractor.surfaceVelocityAt(landed!!.body.position, scratch)
        val drift = Vec3().setTo(landed.body.linearVelocity).subInPlace(scratch).length
        assertTrue("it should have come to rest, still drifting $drift m/s", drift < 0.5)
        assertTrue(
            "it should not have toppled, tilted ${tiltDegrees(world, landed)} degrees",
            tiltDegrees(world, landed) < 40.0,
        )
    }

    private fun chuteOf(vessel: com.rm.apogee.core.craft.Vessel) =
        vessel.defs.indexOfFirst { it.module<com.rm.apogee.core.part.Parachute>() != null }

    /** The lander [height] up, falling at [speed], with its chute armed. */
    private fun falling(world: World, height: Double, speed: Double): com.rm.apogee.core.craft.Vessel {
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), World.launchSites.first())
        assertTrue("the lander should carry a chute", chuteOf(vessel) >= 0)
        repeat(2) { world.stage(vessel) }
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        vessel.body.position.addScaledInPlace(up, height)
        world.attractorFor(vessel).surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.linearVelocity.addScaledInPlace(up, -speed)
        return vessel
    }

    @Test
    fun `an armed chute waits out a fast fall, then opens itself and fills`() {
        val world = world()
        // Well past the canopy's 300 m/s rating, so armed, it mustn't open yet.
        val vessel = falling(world, 6_000.0, 450.0)
        val chute = chuteOf(vessel)
        repeat(30) { world.step(dt) }
        assertTrue("still whole", !vessel.isBroken(chute))
        assertEquals("still packed", 0.0, vessel.legDeploy[chute], 0.0)

        // Slowed to where it's safe, it opens, and fills over a second or two.
        world.attractorFor(vessel).surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        vessel.body.linearVelocity.addScaledInPlace(up, -150.0)
        repeat(30) { world.step(dt) }
        val half = vessel.legDeploy[chute]
        assertTrue("its drogue filling after half a second: $half", half > 0.0 && half < com.rm.apogee.core.part.Parachute.DROGUE_FULL)
        repeat(150) { world.step(dt) }
        // High up, the drogue holds, and the main waits for the ground.
        assertEquals("drogue full, main not out", com.rm.apogee.core.part.Parachute.DROGUE_FULL, vessel.legDeploy[chute], 1e-9)
        assertTrue("whole", !vessel.isBroken(chute))
    }

    @Test
    fun `an open chute pushed past its rating tears away and says so`() {
        val world = world()
        val vessel = falling(world, 6_000.0, 150.0)
        val chute = chuteOf(vessel)
        repeat(180) { world.step(dt) }
        assertTrue("open", vessel.legDeploy[chute] >= com.rm.apogee.core.part.Parachute.DROGUE_FULL - 1e-9)
        world.drainEvents()
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        world.attractorFor(vessel).surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.linearVelocity.addScaledInPlace(up, -450.0)
        repeat(5) { world.step(dt) }
        assertTrue("the canopy should have torn away", vessel.isBroken(chute))
        assertEquals(
            "and it should say so",
            1,
            world.drainEvents().filterIsInstance<WorldEvent.PartFailed>().count { it.partIndex == chute },
        )
    }

    @Test
    fun `under its chute the lander comes down gently, and the chute is cut on landing`() {
        val world = world()
        val vessel = falling(world, 600.0, 60.0)
        val chute = chuteOf(vessel)
        var touchdown = Double.NaN
        repeat(60 * 240) {
            if (touchdown.isNaN() && vessel.touchingGround) {
                val v = vessel.body.linearVelocity.copy()
                world.attractorFor(vessel).surfaceVelocityAt(vessel.body.position, Vec3()).let { v.subInPlace(it) }
                touchdown = v.length
            }
            world.step(dt)
        }
        assertTrue("touched down gently: $touchdown m/s", touchdown < 12.0)
        assertTrue("cut away once down: ${vessel.legDeploy[chute]}", vessel.legDeploy[chute] < 0.0)
    }

    /**
     * A Starter I's pod alone from high up, chute armed. A blunt body falls slowly enough that the
     * chute is full well before the ground.
     */
    @Test
    fun `a pod falling from high up has its chute open well before the ground`() {
        val world = world()
        val full = StockCraft.starterRocket(catalog)
        val pod = com.rm.apogee.core.craft.CraftDesign(
            full.name, listOf(0, 1, 2).map { full.parts[it] },
            stages = listOf(com.rm.apogee.core.craft.Stage(listOf(1))),
        )
        val terra = world.system.body("terra")
        val up = Vec3(1.0, 0.0, 0.0)
        val position = up.copy().mulInPlace(terra.radius + 30_000.0)
        val velocity = terra.surfaceVelocityAt(position, Vec3()).addScaledInPlace(up, -100.0)
        val vessel = world.spawnAt(pod, "terra", position, velocity, com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), up))
        world.stage(vessel)
        val chute = chuteOf(vessel)
        // A couple of minutes under the chute: drogue high, main for the last 10 to 20 seconds.
        val ground = kotlin.math.max(terra.terrain!!.elevation(up), 0.0)
        var drogueAt = Double.NaN; var drogueTime = Double.NaN
        var mainAt = Double.NaN; var mainTime = Double.NaN
        var downTime = Double.NaN; var downSpeed = Double.NaN
        var t = 0.0
        while (t < 600.0 && downTime.isNaN()) {
            // The speed the moment before it touches, because after that the ground has it.
            val v = vessel.body.linearVelocity.copy().subInPlace(terra.surfaceVelocityAt(vessel.body.position, Vec3())).length
            world.step(dt); t += dt
            val d = vessel.legDeploy[chute]
            val agl = terra.altitudeOf(vessel.body.position) - ground
            if (drogueTime.isNaN() && d >= com.rm.apogee.core.part.Parachute.DROGUE_FULL) { drogueAt = agl; drogueTime = t }
            if (mainTime.isNaN() && d > com.rm.apogee.core.part.Parachute.DROGUE_FULL) { mainAt = agl; mainTime = t }
            if (vessel.touchingGround) { downTime = t; downSpeed = v }
        }
        assertTrue("drogue full well above the ground: $drogueAt m", drogueAt > 3_000.0)
        assertTrue("main out low down: $mainAt m", mainAt in 100.0..250.0)
        assertTrue("a couple of minutes under the chute: ${downTime - drogueTime} s", downTime - drogueTime in 60.0..180.0)
        assertTrue("about twenty seconds under the main: ${downTime - mainTime} s", downTime - mainTime in 15.0..25.0)
        assertTrue("down gently: $downSpeed m/s", downSpeed < 11.0)
        println("chute: drogue at %.0f m, main at %.0f m, %.0f s under the chute, %.0f s under the main, down at %.1f m/s".format(
            drogueAt, mainAt, downTime - drogueTime, downTime - mainTime, downSpeed))
        assertTrue("whole", !vessel.isBroken(chute))
    }
}
