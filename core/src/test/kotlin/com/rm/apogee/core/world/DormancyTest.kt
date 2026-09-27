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
 * Sleeping craft.
 *
 * A world people leave bases in is mostly made of things nobody is looking at, and the whole point
 * is that those cost nothing. What makes it safe and not just cheap is that a dormant craft is
 * still *there*. It holds its place on the ground, it still collides, and anything that touches it
 * wakes it.
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
     * The one that would catch freezing the inertial state instead of the ground-relative one. A
     * craft parked on the equator is travelling at about 175 m/s inertially. Holding *that* still
     * leaves the planet to rotate out from under it, and the base sinks or floats away.
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

        // And it really did keep moving through space instead of being frozen, otherwise the test
        // above would pass for the wrong reason.
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

        // Woken at some point, not necessarily awake at the end. Once the other craft has come to
        // rest (or rolled off) the base is still again and can rightly go back to sleep.
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

    /**
     * The thing that made dormancy hard to get right in the first place: a craft "at rest" was
     * never really at rest.
     */
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

        // Before anchoring these peaked at 0.065 m/s and 0.032 rad/s and never died away. Friction
        // should now remove them completely.
        assertTrue("it still creeps at $worstLinear m/s", worstLinear < 1e-9)
        assertTrue("it still twitches at $worstAngular rad/s", worstAngular < 1e-9)
    }

    /**
     * Anchoring mustn't turn into glue. A craft on ground steeper than friction can hold has to
     * slide, which is the same rule seen from the other side.
     */
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
     * The case a base actually lands in.
     *
     * A craft on sprung legs never passed the old velocity test. Contacts resolve after gravity, so
     * it finishes every tick holding the impulse that cancelled that tick's gravity (0.163 m/s,
     * against a budget of 0.098), while its height above the ground doesn't move in five decimal
     * places. Since bases land on gear, that meant nothing in a persistent world would ever have
     * slept.
     */
    @Test
    fun `a craft resting on deployed legs sleeps too`() {
        val world = world()
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), site)
        repeat(3) { world.stage(vessel) }
        assertTrue("the gear should be down", vessel.isActivated(vessel.defs.indices.first {
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
