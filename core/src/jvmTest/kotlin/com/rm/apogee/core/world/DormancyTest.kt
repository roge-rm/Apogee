package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sleeping craft. A dormant craft costs nothing but is still there: it holds its place on the
 * ground, still collides, and wakes when anything touches it.
 */
class DormancyTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val site = World.launchSites.first()

    private fun world() = World.default(catalog)

    private fun settle(world: World, seconds: Double = 6.0) {
        repeat((seconds / dt).toInt()) { world.step(dt) }
    }

    /** Where the craft is standing, in the frame that doesn't rotate away. */
    private fun groundPosition(world: World, vessel: Vessel): Vec3 {
        val attractor = world.attractorFor(vessel)
        return attractor.toBodyFixed(
            vessel.body.position, attractor.rotationAt(world.time), Vec3(),
        )
    }

    @Test
    fun `a craft left alone on the pad falls asleep`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), site)
        assertFalse("it should start awake", vessel.dormant)
        settle(world)
        assertTrue("a craft sitting on a pad should sleep", vessel.dormant)
    }

    /**
     * Sleep freezes the ground-relative state. A craft on the equator moves at about 175 m/s
     * inertially, and freezing that would let the planet turn out from under it.
     */
    @Test
    fun `a sleeping craft stays where it is standing, not where it was in space`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), site)
        settle(world)
        assertTrue(vessel.dormant)

        val before = groundPosition(world, vessel)
        val inertialBefore = vessel.body.position.copy()
        settle(world, 60.0)
        val after = groundPosition(world, vessel)

        val drift = Vec3().setTo(after).subInPlace(before).length
        assertTrue("it drifted $drift m across the ground while asleep", drift < 0.01)

        // And it still moved through space, so the check above isn't passing by accident.
        val inertialMoved = Vec3().setTo(vessel.body.position)
            .subInPlace(inertialBefore).length
        assertTrue(
            "a sleeping craft should still ride the planet round, moved $inertialMoved m",
            inertialMoved > 1_000.0,
        )
    }

    @Test
    fun `a sleeping craft keeps its altitude above the ground`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), site)
        val attractor = world.attractorFor(vessel)
        settle(world)

        fun clearance(): Double {
            val rotation = attractor.rotationAt(world.time)
            val bodyFixed = attractor.toBodyFixed(vessel.body.position, rotation, Vec3())
            return vessel.body.position.length -
                attractor.surfaceRadiusInBodyFrame(bodyFixed)
        }

        val before = clearance()
        settle(world, 120.0)
        assertEquals("it should not sink or rise while asleep", before, clearance(), 0.01)
    }

    @Test
    fun `a command wakes it`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), site)
        settle(world)
        assertTrue(vessel.dormant)

        world.apply(Command.SetThrottle(vessel.id.raw, 1.0))
        assertFalse("throttling up is somebody paying attention to it", vessel.dormant)
    }

    @Test
    fun `something landing on it wakes it`() {
        val world = world()
        val base = world.spawnOnSurface(StockCraft.lander(catalog), site)
        settle(world)
        assertTrue("the base should be asleep first", base.dormant)

        // Drop another craft onto it.
        val arriving = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 1)
        val up = Vec3().setTo(base.body.position).normalizeInPlace()
        arriving.body.position.setTo(base.body.position).addScaledInPlace(up, 7.0)
        world.attractorFor(arriving)
            .surfaceVelocityAt(arriving.body.position, arriving.body.linearVelocity)

        // Woken at some point. Once the other craft settles, the base can rightly sleep again.
        var woke = false
        repeat(300) {
            world.step(dt)
            if (!base.dormant) woke = true
        }
        assertTrue("being landed on should wake it", woke)
    }

    @Test
    fun `a craft under power never sleeps, however still it looks`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.starterRocket(catalog), site)
        world.apply(Command.Stage(vessel.id.raw))
        world.apply(Command.SetThrottle(vessel.id.raw, 0.01))
        settle(world)
        assertFalse(
            "a craft holding itself up on its engine is in equilibrium, not at rest",
            vessel.dormant,
        )
    }

    /** A resting craft must be truly still, or it would never sleep. */
    @Test
    fun `a resting craft is held exactly still, not roughly`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), site)
        val attractor = world.attractorFor(vessel)
        settle(world, 4.0)

        // Sampled while awake, so this measures the contact resolver and not dormancy freezing it.
        var worstLinear = 0.0
        var worstAngular = 0.0
        val surface = Vec3()
        val spin = Vec3()
        repeat(120) {
            world.step(dt)
            if (vessel.dormant) return@repeat
            attractor.surfaceVelocityAt(vessel.body.position, surface)
            worstLinear = maxOf(
                worstLinear,
                Vec3().setTo(vessel.body.linearVelocity).subInPlace(surface).length,
            )
            attractor.angularVelocity(spin)
            worstAngular = maxOf(
                worstAngular,
                Vec3().setTo(vessel.body.angularVelocity).subInPlace(spin).length,
            )
        }

        // Friction anchoring should remove all creep and twitch.
        assertTrue("it still creeps at $worstLinear m/s", worstLinear < 1e-9)
        assertTrue("it still twitches at $worstAngular rad/s", worstAngular < 1e-9)
    }

    /** Anchoring isn't glue: anything stronger than friction still moves the craft. */
    @Test
    fun `a craft isn't held by friction when something stronger acts on it`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.starterRocket(catalog), site)
        settle(world, 4.0)
        assertTrue("it should be asleep to begin with", vessel.dormant)

        world.apply(Command.Stage(vessel.id.raw))
        world.apply(Command.SetThrottle(vessel.id.raw, 1.0))
        repeat(180) { world.step(dt) }

        assertFalse("thrust beats friction", vessel.dormant)
        val attractor = world.attractorFor(vessel)
        val surface = attractor.surfaceVelocityAt(vessel.body.position, Vec3())
        val climb = Vec3().setTo(vessel.body.linearVelocity).subInPlace(surface).length
        assertTrue("and it should be climbing, not stuck ($climb m/s)", climb > 5.0)
    }

    /**
     * Bases land on gear, so this matters. Contacts resolve after gravity, so a craft on sprung legs
     * ends each tick holding that tick's gravity impulse (0.163 m/s) while not moving at all. A
     * plain velocity test would never let it sleep.
     */
    @Test
    fun `a craft resting on deployed legs sleeps too`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), site)
        world.gearDown(vessel, stages = 0)
        assertTrue("the gear should be down", vessel.gearDown(vessel.defs.indices.first {
            vessel.defs[it].id == "leg-stilt"
        }))

        settle(world, 12.0)
        assertTrue("a craft standing on its gear should settle", vessel.dormant)
    }

    @Test
    fun `a craft in flight doesn't sleep`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), site)
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        vessel.body.position.addScaledInPlace(up, 3_000.0)
        settle(world, 3.0)
        assertFalse("it is falling, not resting", vessel.dormant)
    }
}
