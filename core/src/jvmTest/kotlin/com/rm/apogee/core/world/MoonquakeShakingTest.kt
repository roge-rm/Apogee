package com.rm.apogee.core.world

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
 * Moonquakes shake the ground under craft on Luna, with no air: a lander near the biggest one
 * sways and strains but stays up, and one parked asleep wakes as the shaking comes, with warp
 * held down for it.
 */
class MoonquakeShakingTest {
    private val catalog = StockParts.catalog
    private val site = World.launchSites.first { it.id == "luna-mare" }
    private val dt = 1.0 / 60.0

    private fun world(): World = World(SolarSystem.defaultSystem(), catalog).also {
        it.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = 0.0, nextVesselId = 1L,
            weather = WeatherConfig(intensity = WeatherIntensity.CALM)))
    }

    /** [km] east of the site, unit. */
    private fun eastOfSite(km: Double): Vec3 = SolarSystem.surfaceDirection(site.latitude, site.longitude + km * 1_000.0 /
        (SolarSystem.defaultSystem().body("luna").radius * kotlin.math.cos(site.latitude)))

    private fun tilt(v: Vessel): Double {
        val up = v.body.position.normalized()
        return Math.toDegrees(kotlin.math.acos((v.body.orientation.rotate(v.design.orientation.up) dot up).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `a lander near the biggest moonquake sways but stays up`() {
        val world = world()
        val luna = world.system.body("luna")
        val design = StockCraft.prospector(catalog)
        val craft = world.spawnOnSurface(design, site, legsOut = true)
        repeat((3.0 / dt).toInt()) { world.step(dt) }
        val settled = tilt(craft)
        world.quakesFor(luna)!!.force(eastOfSite(2.0), world.time + 1.0, 6.5, 1_500.0)
        var most = 0.0; var stress = 0.0; var shaken = 0
        val start = world.time
        while (world.time < start + 60.0) {
            world.step(dt)
            val v = world.vessel(craft.id) ?: break
            most = maxOf(most, tilt(v)); stress = maxOf(stress, v.stress)
            if (v.shaken) shaken++
        }
        val v = world.vessel(craft.id)
        println("lander on Luna, moonquake 6.5 2 km off: tilted from ${"%.1f".format(settled)} up to ${"%.1f".format(most)}, stress up to ${"%.3f".format(stress)}, shaken ${shaken} ticks")
        assertTrue("shaken ticks $shaken", shaken > 60)
        assertTrue("strained: $stress", stress > 0.005)
        assertTrue(v != null)
        assertEquals(design.parts.size, v!!.defs.size)
        assertTrue("tilt $most", most < 10.0)
    }

    @Test
    fun `a lander parked asleep wakes as the shaking comes, with warp held down for it`() {
        val world = world()
        val luna = world.system.body("luna")
        val lander = world.spawnOnSurface(StockCraft.prospector(catalog), site, legsOut = true)
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        if (!lander.dormant) lander.sleep(luna.rotationAt(world.time))
        assertTrue(lander.dormant)
        val quake = world.time + 3_000.0
        world.quakesFor(luna)!!.force(eastOfSite(2.0), quake, 6.5, 1_500.0)
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
        println("parked lander on Luna: held to physics ${physicsFrom - quake} s from the moonquake, woke $wokeAt s after it")
        assertTrue("held only near it: ${physicsFrom - quake}", physicsFrom - quake > -60.0 && physicsFrom - quake < 6.0)
        assertTrue("woke: $wokeAt", wokeAt > 0.0 && wokeAt < 15.0)
        assertTrue(world.vessel(lander.id) != null)
    }
}
