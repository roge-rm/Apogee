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

    /**
     * Where modules get walked into place: on Luna, whose sixth of a g lets
     * four thrusters break a landed tug's grip. Lifting a little and pushing,
     * it slides across - and stays on its feet. (On Terra they barely out-push
     * its grip, and pushing at the centre of mass mostly rocks it; the
     * thrusters see to it that it does not go over - see below.)
     */
    @Test
    fun `a landed module can be walked sideways on Luna, lifting and pushing`() {
        val world = world()
        val mare = World.launchSites.first { it.id == "luna-mare" }
        val lander = world.spawnOnSurface(StockCraft.moduleTug(catalog), mare)
        repeat(3) { world.stage(lander) }
        repeat(400) { world.step(dt) }

        val attractor = world.attractorFor(lander)
        val before = attractor.toBodyFixed(
            lander.body.position, attractor.rotationAt(world.time), Vec3(),
        )

        world.apply(Command.SetRcs(lander.id.raw, true))
        // Across (+x) and up (+y, its stack standing on its tail).
        world.apply(Command.SetTranslation(lander.id.raw, 0.6, 0.3, 0.0))
        var worst = 0.0
        repeat(180) {
            world.step(dt)
            val up = lander.body.position.normalized()
            val nose = lander.body.orientation.rotate(Vec3.unitY(), Vec3())
            worst = maxOf(worst, Math.toDegrees(kotlin.math.acos((nose dot up).coerceIn(-1.0, 1.0))))
        }
        world.apply(Command.SetTranslation(lander.id.raw, 0.0, 0.0, 0.0))
        repeat(300) { world.step(dt) }

        val after = attractor.toBodyFixed(
            lander.body.position, attractor.rotationAt(world.time), Vec3(),
        )
        val moved = Vec3().setTo(after).subInPlace(before)
        moved.addScaledInPlace(before.normalized(), -(moved dot before.normalized()))
        val across = moved.length
        assertTrue("it should have shifted across the ground, moved $across m", across > 0.5)
        assertTrue("but this is a nudge, not a launch ($across m)", across < 200.0)
        assertTrue("and it stayed on its feet (leaned $worst deg)", worst < 20.0)
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

    private fun tugInOrbit(world: World): com.rm.apogee.core.craft.Vessel {
        val tug = world.spawnInOrbit(
            StockCraft.moduleTug(catalog),
            "terra",
            com.rm.apogee.core.orbit.Orbit.circular(700_000.0, 3.5316000e12),
        )
        repeat(3) { world.stage(tug) }
        tug.body.angularVelocity.setTo(Vec3.zero())
        return tug
    }

    /** How fast it is turning after a second of full pitch, rad/s, with the thrusters [armed] or not. */
    private fun pitchRate(armed: Boolean): Pair<Double, Double> {
        val world = world()
        val tug = tugInOrbit(world)
        val fuel = tug.amountOf(ResourceType.MONOPROPELLANT)
        world.apply(Command.SetRcs(tug.id.raw, armed))
        world.apply(Command.SetAttitude(tug.id.raw, 1.0, 0.0, 0.0))
        repeat(60) { world.step(dt) }
        return tug.body.angularVelocity.length to fuel - tug.amountOf(ResourceType.MONOPROPELLANT)
    }

    @Test
    fun `armed, the thrusters help turn it - and that costs propellant`() {
        val (wheels, unarmedSpent) = pitchRate(armed = false)
        val (both, armedSpent) = pitchRate(armed = true)
        assertTrue("wheels alone turn it ($wheels rad/s)", wheels > 0.0)
        assertTrue("thrusters add to it: $wheels -> $both rad/s", both > wheels * 1.2)
        assertEquals("nothing spent turning on wheels alone", 0.0, unarmedSpent, 1e-12)
        assertTrue("propellant spent turning ($armedSpent)", armedSpent > 0.0)
    }

    @Test
    fun `each block's push is recorded - all one way for a slide, round the axis for a turn`() {
        val world = world()
        val tug = tugInOrbit(world)
        world.apply(Command.SetRcs(tug.id.raw, true))
        world.apply(Command.SetTranslation(tug.id.raw, 1.0, 0.0, 0.0))
        world.step(dt)
        val blocks = tug.defs.indices.filter { tug.defs[it].module<com.rm.apogee.core.part.Rcs>() != null }
        assertEquals(4, blocks.size)
        for (b in blocks) {
            val f = tug.rcsFiring
            assertEquals("block $b pushes along +x", 1.0, f[b * 3], 1e-6)
            assertEquals(0.0, f[b * 3 + 1], 1e-9)
            assertEquals(0.0, f[b * 3 + 2], 1e-9)
        }

        // Rolling (about the craft's +y): each pushes across, round the axis -
        // opposite blocks opposite ways - and with no slide, nothing along it.
        world.apply(Command.SetTranslation(tug.id.raw, 0.0, 0.0, 0.0))
        world.apply(Command.SetAttitude(tug.id.raw, 0.0, 0.0, 1.0))
        world.step(dt)
        val com = tug.centerOfMass()
        for (b in blocks) {
            val push = Vec3(tug.rcsFiring[b * 3], tug.rcsFiring[b * 3 + 1], tug.rcsFiring[b * 3 + 2])
            val at = Vec3().setTo(tug.design.parts[b].position).subInPlace(com)
            assertTrue("block $b pushes ($push)", push.length > 0.5)
            val torque = Vec3().setTo(at).crossInPlace(push)
            assertTrue("block $b turns it about +y (${torque})", torque.y > 0.0)
        }
    }

    @Test
    fun `with SAS on and the stick let go, armed thrusters stop a spin sooner`() {
        fun spinLeft(armed: Boolean): Double {
            val world = world()
            val tug = tugInOrbit(world)
            tug.body.orientation.rotate(Vec3(0.4, 0.0, 0.0), tug.body.angularVelocity)
            world.apply(Command.SetSas(tug.id.raw, true))
            world.apply(Command.SetRcs(tug.id.raw, armed))
            repeat(60) { world.step(dt) }
            return tug.body.angularVelocity.length
        }
        val wheels = spinLeft(false)
        val both = spinLeft(true)
        assertTrue("armed thrusters damp it faster: $wheels vs $both rad/s", both < wheels * 0.9)
    }

    /** Everyone sees a craft's thrusters puffing, so the push goes over the wire. */
    @Test
    fun `each block's push survives the trip to other players`() {
        val world = world()
        val tug = tugInOrbit(world)
        world.apply(Command.SetRcs(tug.id.raw, true))
        world.apply(Command.SetTranslation(tug.id.raw, 0.6, 0.0, -0.3))
        world.apply(Command.SetAttitude(tug.id.raw, 0.0, 0.0, 0.5))
        world.step(dt)
        val values = VesselPose.Values()
        assertTrue(VesselPose.decode(tug.defs, VesselPose.encode(tug), values))
        var blocks = 0
        for (i in tug.defs.indices) {
            if (tug.defs[i].module<com.rm.apogee.core.part.Rcs>() == null) continue
            blocks++
            for (a in 0 until 3) assertEquals(tug.rcsFiring[i * 3 + a], values.rcs[i * 3 + a], VesselPose.RESOLUTION + 1e-9)
        }
        assertEquals(4, blocks)
    }

    /** How far from upright a landed tug ends up, degrees, after a full slide for [seconds] with SAS [sas]. */
    private fun tiltAfterFullSlide(sas: Boolean, seconds: Double = 4.0, amount: Double = 1.0): Double {
        val world = world()
        val tug = world.spawnOnSurface(StockCraft.moduleTug(catalog), site)
        repeat(3) { world.stage(tug) }
        repeat(400) { world.step(dt) }
        world.apply(Command.SetSas(tug.id.raw, sas))
        world.apply(Command.SetRcs(tug.id.raw, true))
        world.apply(Command.SetTranslation(tug.id.raw, amount, 0.0, 0.0))
        var worst = 0.0
        repeat((seconds / dt).toInt()) {
            world.step(dt)
            val up = tug.body.position.normalized()
            val nose = tug.body.orientation.rotate(Vec3.unitY(), Vec3())
            worst = maxOf(worst, Math.toDegrees(kotlin.math.acos((nose dot up).coerceIn(-1.0, 1.0))))
        }
        return worst
    }

    /**
     * Pushed flat out along the ground, a landed tug rocks on its legs; the
     * thrusters ease off as it does, and it stays on its feet - it went over
     * at anything past a third of their thrust, SAS or not.
     */
    @Test
    fun `a flat-out slide along the ground does not tip it over`() {
        for (sas in listOf(false, true)) {
            val tilt = tiltAfterFullSlide(sas = sas)
            assertTrue("SAS $sas: stays on its feet, leaned $tilt deg", tilt < 20.0)
        }
    }

    /**
     * A tool, not a test: RCS_PROBE=1 prints how far a landed tug on Terra
     * goes, and how far it leans, for a few pushes - for tuning the
     * thrusters' ease-off (Forces.ROCK_ALLOWED / ROCK_SPAN).
     */
    @Test
    fun probeWalking() {
        if (System.getenv("RCS_PROBE") == null) return
        for ((x, y) in listOf(1.0 to 0.0, 0.6 to 0.7, 1.0 to 0.5, 0.7 to 0.9)) {
            val world = world()
            val tug = world.spawnOnSurface(StockCraft.moduleTug(catalog), site)
            repeat(3) { world.stage(tug) }
            repeat(400) { world.step(dt) }
            val a = world.attractorFor(tug)
            val before = a.toBodyFixed(tug.body.position, a.rotationAt(world.time), Vec3())
            world.apply(Command.SetRcs(tug.id.raw, true))
            world.apply(Command.SetTranslation(tug.id.raw, x, y, 0.0))
            var worst = 0.0
            repeat(300) {
                world.step(dt)
                val up = tug.body.position.normalized()
                val nose = tug.body.orientation.rotate(Vec3.unitY(), Vec3())
                worst = maxOf(worst, Math.toDegrees(kotlin.math.acos((nose dot up).coerceIn(-1.0, 1.0))))
            }
            val after = a.toBodyFixed(tug.body.position, a.rotationAt(world.time), Vec3())
            val moved = Vec3().setTo(after).subInPlace(before)
            moved.addScaledInPlace(before.normalized(), -(moved dot before.normalized()))
            println("push ($x, $y): moved ${"%.2f".format(moved.length)} m in 5 s, worst lean ${"%.1f".format(worst)} deg")
        }
    }
}
