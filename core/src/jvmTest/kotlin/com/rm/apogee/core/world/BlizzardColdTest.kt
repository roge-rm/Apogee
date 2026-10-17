package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.crew.CrewStatus
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.LiveBlizzard
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crew outside in a blizzard get cold: minutes in the worst, longer in a mild one, not at all aboard or in calm cold air. */
class BlizzardColdTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val extreme = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }

    @Test
    fun `how long someone lasts goes by the snow, the wind and the cold`() {
        val worst = 1.0 / World.blizzardChillRate(0.9, 30.0, 1.0)
        val mild = 1.0 / World.blizzardChillRate(0.2, 12.0, 1.0)
        println("worst blizzard: %.0f s, mild: %.0f s".format(worst, mild))
        assertTrue("a few minutes in the worst: $worst", worst in 180.0..360.0)
        assertTrue("much longer in a mild one: $mild", mild > 20 * 60.0)
        assertEquals("calm cold air does nothing", 0.0, World.blizzardChillRate(0.0, 0.0, 1.0), 0.0)
        assertEquals("cold wind with no snow does nothing", 0.0, World.blizzardChillRate(0.0, 30.0, 1.0), 0.0)
        assertEquals("snow where it isn't cold does nothing", 0.0, World.blizzardChillRate(0.9, 30.0, 0.0), 0.0)
    }

    /** A thick blizzard over land, and a time and place in it with thick snow at the ground. */
    private fun inBlizzard(): Pair<Double, Vec3> {
        val terra = SolarSystem.defaultSystem().body("terra")
        val w = Weather(terra, extreme)
        val air = AirSample()
        var t = 6 * 3_600.0
        while (t < 300 * 3_600.0) {
            val live = ArrayList<LiveBlizzard>()
            w.blizzards(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (z in live) {
                if (z.strength < 0.6 || z.age < 2_400.0 || z.age > 0.5 * z.life) continue
                if (terra.terrain!!.elevation(z.centre) < 20.0) continue
                w.sample(Vec3().setTo(z.centre).mulInPlace(terra.radius + terra.terrain!!.elevation(z.centre) + 2.0), t, air)
                if (air.snow > 0.5 && air.wind.length > 15.0) return t to z.centre
            }
            t += 1_800.0
        }
        error("no blizzard over land")
    }

    @Test
    fun `out in a blizzard they chill on its timescale, aboard they don't, in calm cold air they don't`() {
        val (time, centre) = inBlizzard()
        val site = LaunchSite("snow", "Snow", "terra", SolarSystem.latitudeOf(centre), SolarSystem.longitudeOf(centre))
        for (config in listOf(extreme, WeatherConfig(intensity = WeatherIntensity.CALM))) {
            val world = World.default(catalog).also { it.weatherConfig = config; it.syncClock(time) }
            val rover = world.spawnOnSurface(StockCraft.rover(catalog), site)
            world.assignOwner(rover, "p1")
            world.seatCrew(rover)
            // Aboard for a minute: sheltered.
            run(world, 60.0)
            val member = rover.crew.first { it.isNotEmpty() }.first()
            val suit = world.eva(rover.id.raw, member)
            assertNotNull("couldn't go outside", suit)
            assertEquals("no cold carried out from aboard", 0.0, suit!!.chill, 0.0)
            run(world, 30.0)
            val snow = suit.air.snow
            val terra = world.attractorFor(suit)
            val here = terra.toBodyFixed(suit.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
            println("  cold there %.2f at centre %.2f, rate %.5f, apart %.0f m".format(world.weatherFor(terra)?.blizzardCold(here) ?: -1.0,
                world.weatherFor(terra)?.blizzardCold(centre) ?: -1.0, world.blizzardChill(suit, terra), here.distanceTo(centre) * terra.radius))
            println("${config.intensity}: snow %.2f wind %.1f m/s, chill after 30 s %.3f".format(snow, suit.air.wind.length, suit.chill))
            if (config.intensity == WeatherIntensity.EXTREME) {
                assertTrue("getting cold out in it: ${suit.chill}", suit.chill > 30.0 / 600.0)
                assertTrue("not dead in seconds: ${suit.chill}", suit.chill < 30.0 / 120.0)
                // Left out until it's too much.
                suit.chill = 0.999
                run(world, 5.0)
                val lost = world.crew.getValue(member)
                assertEquals(CrewStatus.LOST, lost.status)
                assertEquals(World.BLIZZARD_COLD_REASON, lost.lostHow)
            } else {
                assertEquals("calm cold air chills no one", 0.0, suit.chill, 0.0)
            }
        }
    }
}
