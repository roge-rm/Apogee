package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/** A craft's power: what makes it, what uses it, and what going flat takes away. */
class PowerTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val sun = LaunchTime.SUN_DIRECTION

    /** A Halo with [extras] on its sides, each on the outside of the hull facing out. */
    private fun pod(vararg extras: String): CraftDesign {
        val sides = listOf(0.0, PI, PI / 2, -PI / 2, PI / 4, -3 * PI / 4)
        val parts = ArrayList<PlacedPart>()
        parts += PlacedPart("pod-halo", Vec3(0.0, 0.0, 0.0))
        extras.forEachIndexed { k, id ->
            val angle = sides[k]
            // Its own -X to the hull: turned about Y to face out at [angle].
            val out = Vec3(kotlin.math.cos(angle), 0.0, -kotlin.math.sin(angle))
            parts += PlacedPart(
                id, out.copy().mulInPlace(0.7),
                rotation = com.rm.apogee.core.math.Quat.fromAxisAngle(Vec3.unitY(), angle, com.rm.apogee.core.math.Quat.identity()),
                parentIndex = 0,
            )
        }
        return CraftDesign(name = "Test Pod", parts = parts, stages = emptyList(), manualStaging = true, catalogHash = catalog.contentHash)
    }

    /**
     * [design] in a circular orbit [height] up, over the sunlit side of
     * Terra or ([lit] false) in the middle of its shadow, its +X to the sun.
     */
    private fun inOrbit(world: World, design: CraftDesign, lit: Boolean = true, height: Double = 100_000.0): Vessel {
        val terra = world.system.body("terra")
        val r = terra.radius + height
        val up = sun.copy().mulInPlace(if (lit) 1.0 else -1.0)
        val along = sun.cross(Vec3.unitY()).normalizeInPlace()
        val speed = sqrt(terra.gravitationalParameter / r)
        return world.spawnAt(design, "terra", up.mulInPlace(r), along.mulInPlace(speed), quatFromTo(Vec3(1.0, 0.0, 0.0), sun))
    }

    private fun charge(v: Vessel) = v.amountOf(ResourceType.ELECTRIC_CHARGE)
    private fun setCharge(v: Vessel, to: Double) {
        v.drawCharge(charge(v))
        v.storeCharge(to)
    }
    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }

    @Test
    fun `sunlight is none inside Terra's shadow and full outside it`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val power = Power(world.system)
        val r = terra.radius + 100_000.0
        val across = sun.cross(Vec3.unitY()).normalizeInPlace()
        assertEquals(1.0, power.sunlight(terra, sun.copy().mulInPlace(r), 0.0), 0.0)
        assertEquals(0.0, power.sunlight(terra, sun.copy().mulInPlace(-r), 0.0), 0.0)
        // Behind Terra, just inside its shadow and just outside it.
        val behind = sun.copy().mulInPlace(-3.0 * terra.radius)
        assertEquals(0.0, power.sunlight(terra, behind.copy().addScaledInPlace(across, terra.radius - 10_000.0), 0.0), 0.0)
        assertEquals(1.0, power.sunlight(terra, behind.copy().addScaledInPlace(across, terra.radius + 10_000.0), 0.0), 0.0)
        // Beside it, level with it: lit.
        assertEquals(1.0, power.sunlight(terra, across.copy().mulInPlace(r), 0.0), 0.0)
    }

    @Test
    fun `a pod with nothing to charge it runs flat, and then its wheels and assist hold nothing`() {
        val world = World.default(catalog)
        val pod = inOrbit(world, pod())
        world.apply(Command.SetSas(pod.id.raw, true))
        setCharge(pod, 0.1)
        run(world, 10.0)
        assertFalse("still powered with ${charge(pod)}", pod.powered)
        assertEquals(0.0, charge(pod), 1e-9)

        // A spin it cannot stop.
        pod.body.angularVelocity.setTo(0.3, 0.0, 0.0)
        run(world, 5.0)
        assertEquals("a flat pod's wheels bit", 0.3, pod.body.angularVelocity.length, 0.01)

        // Nor fly itself.
        world.apply(Command.SetAutopilot(pod.id.raw, autoBurn = false, autoLand = true))
        run(world, 0.1)
        assertFalse(pod.control.autoLand)
        assertEquals("No power", pod.control.autopilotNote)

        // With charge, the same spin is stopped.
        val other = World.default(catalog)
        val live = inOrbit(other, pod())
        other.apply(Command.SetSas(live.id.raw, true))
        live.body.angularVelocity.setTo(0.3, 0.0, 0.0)
        run(other, 5.0)
        assertTrue("a charged pod's spin: ${live.body.angularVelocity.length}", live.body.angularVelocity.length < 0.05)
        assertTrue(live.powered)
    }

    @Test
    fun `a sun wing out in sunlight charges a pod, and in Terra's shadow it drains`() {
        fun trial(lit: Boolean, deploy: Boolean): Double {
            val world = World.default(catalog)
            val pod = inOrbit(world, pod("wing-kite"), lit)
            if (deploy) world.apply(Command.Deploy(pod.id.raw, true))
            run(world, 5.0)
            val wing = pod.defs.indexOfFirst { it.id == "wing-kite" }
            assertEquals("the wing out", if (deploy) 1.0 else 0.0, pod.legDeploy[wing], 1e-9)
            setCharge(pod, 25.0)
            run(world, 10.0)
            return charge(pod) - 25.0
        }
        val sunlit = trial(lit = true, deploy = true)
        assertEquals("sunlit, out", 10.0 * (1.2 - 0.02), sunlit, 0.3)
        assertTrue("in shadow: $sunlit", trial(lit = false, deploy = true) < 0.0)
        assertTrue("folded, in the sun", trial(lit = true, deploy = false) < 0.0)
    }

    @Test
    fun `a fixed panel makes charge by how squarely it faces the sun`() {
        fun gain(facing: Boolean): Double {
            val world = World.default(catalog)
            val design = pod("panel-glint")
            // Face the craft's +X to the sun, or away from it.
            val pod = inOrbit(world, design)
            if (!facing) quatFromTo(Vec3(-1.0, 0.0, 0.0), sun).let { pod.body.orientation.setTo(it) }
            setCharge(pod, 25.0)
            run(world, 10.0)
            return charge(pod) - 25.0
        }
        assertEquals(10.0 * (0.3 - 0.02), gain(facing = true), 0.1)
        assertEquals(10.0 * -0.02, gain(facing = false), 0.05)
    }

    @Test
    fun `a running engine charges the batteries`() {
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        run(world, 1.0)
        setCharge(rocket, 5.0)
        world.stage(rocket)
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        run(world, 5.0)
        assertTrue("charged to ${charge(rocket)}", charge(rocket) > 8.0)
        assertTrue(rocket.powerNet > 0.5)
    }

    @Test
    fun `a fuel cell cuts in below a quarter, burns monopropellant, and cuts out well charged`() {
        val world = World.default(catalog)
        val pod = inOrbit(world, pod("cell-spark"), lit = false)
        val mono = pod.amountOf(ResourceType.MONOPROPELLANT)
        setCharge(pod, 20.0)
        run(world, 1.0)
        assertFalse("on at 40%", pod.fuelCellsOn)
        setCharge(pod, 10.0)
        run(world, 1.0)
        assertTrue("off at 20%", pod.fuelCellsOn)
        assertTrue("charge ${charge(pod)}", charge(pod) > 10.0)
        assertTrue("no monopropellant burnt", pod.amountOf(ResourceType.MONOPROPELLANT) < mono)
        run(world, 40.0)
        assertFalse("still on at ${charge(pod)}", pod.fuelCellsOn)
        assertTrue("charged to ${charge(pod)}", charge(pod) > 0.85 * 50.0)
    }

    @Test
    fun `on rails and stepped, a craft's charge comes out the same over an orbit`() {
        val design = pod("panel-glint", "battery-hoard", "battery-hoard", "battery-hoard")
        val height = 200_000.0
        fun start(): Pair<World, Vessel> {
            val world = World.default(catalog)
            val craft = inOrbit(world, design, lit = true, height = height)
            setCharge(craft, 0.0)
            return world to craft
        }
        val terra = World.default(catalog).system.body("terra")
        val r = terra.radius + height
        val period = 2 * PI * sqrt(r * r * r / terra.gravitationalParameter)

        val (stepped, a) = start()
        val ticks = (period / dt).toInt()
        repeat(ticks) { stepped.step(dt) }

        val (railed, b) = start()
        var moved = 0.0
        while (moved < ticks * dt - 1e-6) {
            val got = railed.advanceOnRails(minOf(100.0, ticks * dt - moved))
            assertTrue("would not go on rails", got > 0.0)
            moved += got
        }
        assertTrue("it made nothing: ${charge(a)}", charge(a) > 50.0)
        assertTrue("it filled: ${charge(a)}", charge(a) < a.capacityOf(ResourceType.ELECTRIC_CHARGE) - 1.0)
        assertEquals("stepped ${charge(a)}, on rails ${charge(b)}", 0.0, (charge(b) - charge(a)) / charge(a), 0.01)
    }

    @Test
    fun `fold-out and fuel-cell state survive a save`() {
        val world = World.default(catalog)
        val pod = inOrbit(world, pod("wing-kite", "cell-spark"))
        world.apply(Command.Deploy(pod.id.raw, true))
        run(world, 5.0)
        // Running, as it would be halfway through filling a flat battery.
        pod.fuelCellsOn = true
        val again = World.default(catalog)
        again.restore(world.save())
        val back = again.vessel(pod.id)!!
        assertTrue(back.control.deployed)
        assertTrue(back.fuelCellsOn)
        assertEquals(1.0, back.legDeploy[back.defs.indexOfFirst { it.id == "wing-kite" }], 1e-9)
    }
}
