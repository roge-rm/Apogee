package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quakes shake the ground under craft: a tall stack on the Cape pad comes down in a big quake
 * close by, a lander rides out a small one, and craft parked asleep wake as the shaking comes,
 * with warp held down for it.
 */
class QuakeShakingTest {
    private val catalog = StockParts.catalog
    private val cape = World.launchSites.first { it.id == "cape" }
    private val dt = 1.0 / 60.0

    private fun world(): World = World(SolarSystem.defaultSystem(), catalog).also {
        it.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = 0.0, nextVesselId = 1L,
            weather = WeatherConfig(intensity = WeatherIntensity.CALM)))
    }

    /** [km] east of the pad, unit. */
    private fun eastOfPad(km: Double): Vec3 = SolarSystem.surfaceDirection(cape.latitude, cape.longitude + km * 1_000.0 /
        (SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID).radius * kotlin.math.cos(cape.latitude)))

    private fun tilt(v: Vessel): Double {
        val up = v.body.position.normalized()
        return Math.toDegrees(kotlin.math.acos((v.body.orientation.rotate(v.design.orientation.up) dot up).coerceIn(-1.0, 1.0)))
    }

    private class Ride(val tilt: Double, val parts: Int, val alive: Boolean, val worstStress: Double)

    private fun ride(design: CraftDesign, km: Double, magnitude: Double, depth: Double, seconds: Double): Ride {
        val world = world()
        val terra = world.system.body(SolarSystem.HOMEWORLD_ID)
        val craft = world.spawnOnSurface(design, cape, legsOut = true)
        repeat((3.0 / dt).toInt()) { world.step(dt) }
        val parts = craft.defs.size
        world.weatherFor(terra)!!.quakeModel.force(eastOfPad(km), world.time + 1.0, magnitude, depth)
        var most = 0.0
        var stress = 0.0
        val start = world.time
        while (world.time < start + seconds) {
            world.step(dt)
            val v = world.vessel(craft.id) ?: break
            most = maxOf(most, tilt(v))
            stress = maxOf(stress, v.stress)
        }
        val v = world.vessel(craft.id)
        return Ride(most, v?.defs?.size ?: 0, v != null, stress).also {
            println("${design.name}: magnitude $magnitude $km km off: tilted up to ${"%.1f".format(it.tilt)}, parts $parts to ${it.parts}, stress up to ${"%.2f".format(stress)}")
        }
    }

    @Test
    fun `a tall stack on the pad comes down in a big quake close by`() {
        val r = ride(StockCraft.moonshot(catalog), 4.0, 8.5, 3_000.0, 60.0)
        assertTrue("toppled or broke: tilt ${r.tilt}, parts ${r.parts}", r.tilt > 45.0 || !r.alive || r.parts < StockCraft.moonshot(catalog).parts.size)
    }

    @Test
    fun `a lander rides out a small quake`() {
        val design = StockCraft.prospector(catalog)
        val r = ride(design, 3.0, 6.5, 3_000.0, 40.0)
        assertTrue("shaken: stress ${r.worstStress}", r.worstStress > 0.01)
        assertTrue(r.alive)
        assertEquals(design.parts.size, r.parts)
        assertTrue("tilt ${r.tilt}", r.tilt < 10.0)
    }

    @Test
    fun `a craft parked asleep wakes as the shaking comes, with warp held down for it`() {
        val world = world()
        val terra = world.system.body(SolarSystem.HOMEWORLD_ID)
        val lander = world.spawnOnSurface(StockCraft.prospector(catalog), cape, legsOut = true)
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        if (!lander.dormant) lander.sleep(terra.rotationAt(world.time))
        assertTrue(lander.dormant)
        val quake = world.time + 3_000.0
        world.weatherFor(terra)!!.quakeModel.force(eastOfPad(20.0), quake, 7.5, 3_000.0)
        val asked = World.WARP_RATES.last()
        world.hurried = true
        var physicsFrom = Double.NaN
        var wokeAt = Double.NaN
        while (world.time < quake + 120.0) {
            val rate = minOf(asked, world.maxWarp())
            if (rate <= World.PHYSICS_WARP) {
                if (physicsFrom.isNaN()) physicsFrom = world.time
                repeat(rate.toInt()) { world.step(dt) }
            } else {
                val done = world.advanceOnRails(dt * rate)
                if (done < dt * rate) repeat(World.PHYSICS_WARP.toInt()) { world.step(dt) }
            }
            if (wokeAt.isNaN() && !lander.dormant) wokeAt = world.time - quake
        }
        world.hurried = false
        println("parked lander: held to physics ${physicsFrom - quake} s from the quake, woke ${wokeAt} s after it")
        assertTrue("held only near it: ${physicsFrom - quake}", physicsFrom - quake > -60.0 && physicsFrom - quake < 6.0)
        assertTrue("woke: $wokeAt", wokeAt > 0.0 && wokeAt < 15.0)
        assertTrue(world.vessel(lander.id) != null)
    }
}
