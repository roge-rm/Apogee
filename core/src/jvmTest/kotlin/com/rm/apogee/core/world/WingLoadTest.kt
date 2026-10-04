package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wings carry what a wing can make, and come off only past it. */
class WingLoadTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** [design] flying level east at [speed] m/s, [height] m over the sea west of the Cape, engine lit. */
    private fun flying(world: World, design: com.rm.apogee.core.craft.CraftDesign, speed: Double, height: Double = 300.0): com.rm.apogee.core.craft.Vessel {
        val terra = world.system.body("terra")!!
        val up = com.rm.apogee.core.orbit.SolarSystem.capeDirection(-5_000.0, 0.0)
        val position = Vec3().setTo(up).mulInPlace(terra.radius + height)
        val surface = terra.surfaceVelocityAt(position, Vec3())
        val east = surface.copy().normalizeInPlace()
        val rotation = quatFromTo(design.orientation.up, up)
        val nose = rotation.rotate(design.orientation.forward)
        rotation.setTo(quatFromTo(nose, east) * rotation)
        val plane = world.spawnAt(design, "terra", position, surface.copy().addScaledInPlace(east, speed), rotation)
        world.assignOwner(plane, "p1")
        world.seatCrew(plane)
        plane.activated.fill(true)
        plane.control.gear = false
        for (i in plane.defs.indices) if (plane.defs[i].fold != null) plane.setLegDeploy(i, 0.0)
        world.apply(Command.SetThrottle(plane.id.raw, 1.0))
        return plane
    }

    private fun wings(v: com.rm.apogee.core.craft.Vessel) = v.design.parts.count { it.partId.startsWith("wing-") }

    @Test
    fun `a Plank pulled up hard at its top speed keeps its wings`() {
        val world = World.default(catalog)
        val plane = flying(world, StockCraft.aeroplane(catalog), 115.0)
        val before = wings(plane)
        assertEquals(2, before)
        world.apply(Command.SetAttitude(plane.id.raw, 1.0, 0.0, 0.0))
        var worst = 0f
        repeat(240) {
            world.step(dt)
            val v = world.vessel(plane.id) ?: return@repeat
            for (i in v.defs.indices) if (v.design.parts[i].partId.startsWith("wing-")) worst = maxOf(worst, v.jointLoad.getOrElse(i) { 0f })
        }
        val after = world.vessel(plane.id)!!
        assertEquals("wings left (worst load $worst)", before, wings(after))
    }

    @Test
    fun `a Plank pulled through rough air keeps its wings`() {
        for (intensity in listOf(WeatherIntensity.NORMAL, WeatherIntensity.WILD)) {
            for (seed in 0 until 6) {
                val world = World.default(catalog).also { it.weatherConfig = WeatherConfig(intensity = intensity) }
                world.step(seed * 37.0)
                val plane = flying(world, StockCraft.aeroplane(catalog), 120.0, height = 400.0)
                // Stick right back, as a player would to climb.
                world.apply(Command.SetAttitude(plane.id.raw, 1.0, 0.0, 0.0))
                var worst = 0f
                repeat(600) {
                    world.step(dt)
                    val v = world.vessel(plane.id) ?: return@repeat
                    for (i in v.defs.indices) if (v.design.parts[i].partId.startsWith("wing-")) worst = maxOf(worst, v.jointLoad.getOrElse(i) { 0f })
                }
                val after = world.vessel(plane.id)
                assertEquals("$intensity, seed $seed, worst load $worst", 2, after?.let { wings(it) })
            }
        }
    }

    @Test
    fun `a Plank rolled and pulled at full stick stays whole`() {
        for ((p, r) in listOf(1.0 to 0.0, 1.0 to 1.0, 1.0 to -1.0, 0.5 to 1.0, -1.0 to 1.0, 0.0 to 1.0)) {
            val world = World.default(catalog).also { it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.NORMAL) }
            val plane = flying(world, StockCraft.aeroplane(catalog), 125.0, height = 400.0)
            world.apply(Command.SetAttitude(plane.id.raw, p, 0.0, r))
            var worst = 0f
            repeat(600) { k ->
                world.step(dt)
                val v = world.vessel(plane.id) ?: return@repeat
                for (i in v.defs.indices) if (v.design.parts[i].partId.startsWith("wing-")) {
                    val l = v.jointLoad.getOrElse(i) { 0f }
                    if (l > worst) worst = l
                }
            }
            val after = world.vessel(plane.id)
            assertTrue("pitch $p roll $r: ${after?.design?.parts?.size} parts left", after != null && after.design.parts.size == plane.design.parts.size)
        }
    }

    @Test
    fun `a wing still snaps past what it can carry`() {
        val world = World.default(catalog)
        // Far too fast for it, low down.
        val plane = flying(world, StockCraft.aeroplane(catalog), 260.0, height = 200.0)
        world.apply(Command.SetAttitude(plane.id.raw, 1.0, 0.0, 0.0))
        repeat(240) { world.step(dt) }
        val after = world.vessel(plane.id)
        assertTrue("a wing should have gone", after == null || wings(after) < 2)
    }
}
