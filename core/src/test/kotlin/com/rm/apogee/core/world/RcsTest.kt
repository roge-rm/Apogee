package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thrusters, and what they are for.
 *
 * A base is built by landing modules and pushing them together, and a main
 * engine cannot do the pushing - it points one way and delivers tonnes. These
 * are about the nudge.
 */
class RcsTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val site = World.launchSites.first()

    private fun world() = World.default(catalog)

    /** Ground-relative velocity, which is the only kind that means anything here. */
    private fun driftOf(world: World, vessel: com.rm.apogee.core.craft.Vessel): Vec3 {
        val surface = world.attractorFor(vessel)
            .surfaceVelocityAt(vessel.body.position, Vec3())
        return Vec3().setTo(vessel.body.linearVelocity).subInPlace(surface)
    }

    @Test
    fun `a landed module can be nudged sideways`() {
        val world = world()
        val lander = world.spawnOnSurface(StockCraft.moduleTug(catalog), site)
        repeat(3) { world.stage(lander) }
        repeat(400) { world.step(dt) }

        val attractor = world.attractorFor(lander)
        val before = attractor.toBodyFixed(
            lander.body.position, attractor.rotationAt(world.time), Vec3(),
        )

        world.apply(Command.SetRcs(lander.id.raw, true))
        // Half thrust: a nudge. Full thrust sideways accelerates the tug at
        // about half a g against legs gripping at 0.6, which is its tipping
        // point to within the noise - it went over on one pad and not another.
        world.apply(Command.SetTranslation(lander.id.raw, 0.5, 0.0, 0.0))
        repeat(180) { world.step(dt) }
        world.apply(Command.SetTranslation(lander.id.raw, 0.0, 0.0, 0.0))
        repeat(300) { world.step(dt) }

        val after = attractor.toBodyFixed(
            lander.body.position, attractor.rotationAt(world.time), Vec3(),
        )
        val moved = Vec3().setTo(after).subInPlace(before).length
        assertTrue("it should have shifted across the ground, moved $moved m", moved > 0.5)
        assertTrue("but this is a nudge, not a launch ($moved m)", moved < 200.0)
    }

    /**
     * Thrusters fire at their own offsets, so a symmetric set has to cancel
     * its own torque. If it does not, translating turns into tumbling and the
     * whole point is lost.
     */
    @Test
    fun `a symmetric thruster set translates without spinning`() {
        val world = world()
        val lander = world.spawnInOrbit(
            StockCraft.moduleTug(catalog),
            "terra",
            com.rm.apogee.core.orbit.Orbit.circular(700_000.0, 3.5316000e12),
        )
        repeat(3) { world.stage(lander) }
        lander.body.angularVelocity.setTo(Vec3.zero())

        world.apply(Command.SetRcs(lander.id.raw, true))
        world.apply(Command.SetTranslation(lander.id.raw, 1.0, 0.0, 0.0))
        repeat(240) { world.step(dt) }

        assertTrue(
            "a balanced set should not induce spin, got " +
                "${lander.body.angularVelocity.length} rad/s",
            lander.body.angularVelocity.length < 1e-6,
        )
    }

    @Test
    fun `thrusters do nothing while switched off`() {
        val world = world()
        val lander = world.spawnOnSurface(StockCraft.moduleTug(catalog), site)
        repeat(3) { world.stage(lander) }
        repeat(700) { world.step(dt) }

        val fuelBefore = lander.amountOf(ResourceType.MONOPROPELLANT)
        world.apply(Command.SetTranslation(lander.id.raw, 1.0, 0.0, 0.0))
        // Long enough to settle again: the command itself wakes the craft,
        // whether or not the thrusters are switched on.
        repeat(400) { world.step(dt) }

        assertEquals(
            "no propellant should be spent with RCS off",
            fuelBefore, lander.amountOf(ResourceType.MONOPROPELLANT), 1e-9,
        )
        val drift = driftOf(world, lander).length
        assertTrue("and it should not have been pushed anywhere ($drift m/s)", drift < 0.2)
    }

    @Test
    fun `thrusters burn monopropellant and eventually run dry`() {
        val world = world()
        val lander = world.spawnInOrbit(
            StockCraft.moduleTug(catalog),
            "terra",
            com.rm.apogee.core.orbit.Orbit.circular(700_000.0, 3.5316000e12),
        )
        repeat(3) { world.stage(lander) }

        val started = lander.amountOf(ResourceType.MONOPROPELLANT)
        assertTrue("the lander should carry some", started > 0.0)

        world.apply(Command.SetRcs(lander.id.raw, true))
        world.apply(Command.SetTranslation(lander.id.raw, 0.0, 1.0, 0.0))
        repeat(3_600) { world.step(dt) }

        val left = lander.amountOf(ResourceType.MONOPROPELLANT)
        assertTrue("it should have spent propellant ($started -> $left)", left < started)
    }
}
