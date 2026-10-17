package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
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
import org.junit.Assert.assertSame
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
    private fun inBlizzard(): Pair<Double, Vec3> = blizzardSpot().let { it.time to it.centre }

    private class Spot(val time: Double, val centre: Vec3, val age: Double)

    /** As [inBlizzard], with how long it's been going; over sea at least 15 m deep by a cold shore if [sea] (open sea isn't cold). */
    private fun blizzardSpot(sea: Boolean = false): Spot {
        val terra = SolarSystem.defaultSystem().body("terra")
        val w = Weather(terra, extreme)
        val air = AirSample()
        var t = 6 * 3_600.0
        while (t < 300 * 3_600.0) {
            val live = ArrayList<LiveBlizzard>()
            w.blizzards(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (z in live) {
                if (z.strength < 0.6 || z.age < 2_400.0 || z.age > 0.5 * z.life) continue
                // Over the sea, anywhere in it; over land, its middle.
                val across = Vec3().setTo(z.heading).crossInPlace(z.centre).normalizeInPlace()
                val tries = if (sea) 9 else 1
                for (i in 0 until tries) for (j in 0 until tries) {
                    val at = Vec3().setTo(z.centre)
                    if (sea) {
                        at.addScaledInPlace(z.heading, (i - 4) * 0.2 * z.halfLength / terra.radius)
                            .addScaledInPlace(across, (j - 4) * 0.2 * z.halfWidth / terra.radius).normalizeInPlace()
                    }
                    val ground = terra.terrain!!.elevation(at)
                    if (if (sea) ground > -15.0 || w.blizzardCold(at) < 0.3 else ground < 20.0) continue
                    w.sample(Vec3().setTo(at).mulInPlace(terra.radius + maxOf(0.0, ground) + 2.0), t, air)
                    if (air.snow > (if (sea) 0.25 else 0.5) && air.wind.length > 15.0) return Spot(t, at, z.age)
                }
            }
            t += 1_800.0
        }
        error(if (sea) "no blizzard over the sea" else "no blizzard over land")
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

    private val walker = CraftDesign(
        name = "Walker", parts = listOf(PlacedPart(World.SUIT_PART, Vec3.zero())),
        stages = emptyList(), manualStaging = true, catalogHash = catalog.contentHash,
    )

    private fun siteAt(centre: Vec3) =
        LaunchSite("snow", "Snow", "terra", SolarSystem.latitudeOf(centre), SolarSystem.longitudeOf(centre))

    /** Steps until [vessel] sleeps, at most [seconds]. */
    private fun untilAsleep(world: World, vessel: Vessel, seconds: Double) {
        var left = seconds
        while (!vessel.dormant && left > 0.0) { world.step(dt); left -= dt }
        assertTrue("never went to sleep", vessel.dormant)
    }

    @Test
    fun `someone asleep standing on the ground in a blizzard still chills`() {
        val spot = blizzardSpot()
        val world = World.default(catalog).also { it.weatherConfig = extreme; it.syncClock(spot.time) }
        val suit = world.spawnOnSurface(walker, siteAt(spot.centre))
        untilAsleep(world, suit, 30.0)
        val from = suit.chill
        run(world, 30.0)
        println("asleep on the ground: snow %.2f, chill %.3f to %.3f, still asleep %b".format(suit.air.snow, from, suit.chill, suit.dormant))
        assertTrue("slept through it", suit.dormant)
        assertTrue("not getting cold asleep: ${suit.chill - from}", suit.chill - from > 30.0 / 600.0)
    }

    @Test
    fun `someone asleep on a platform's deck in a blizzard still chills`() {
        val spot = blizzardSpot()
        val world = World.default(catalog).also { it.weatherConfig = extreme; it.syncClock(spot.time - 60.0) }
        val platform = world.spawnOnSurface(StockCraft.seaPlatform(catalog), siteAt(spot.centre))
        world.assignOwner(platform, "p1")
        // Left standing on land, so it can't be founded: the suit is set on its deck by hand.
        run(world, 30.0)
        val deck = platform.defs.indices.first { platform.defs[it].id == "deck-sea" }
        val suit = world.spawnOnSurface(walker, siteAt(spot.centre))
        val up = platform.partPositionWorld(deck).normalizeInPlace()
        val top = platform.partPositionWorld(deck).addScaledInPlace(up, platform.defs[deck].boundsHalfExtents.y)
        suit.body.position.setTo(up).mulInPlace(top.length + 1.0)
        world.attractorFor(suit).surfaceVelocityAt(suit.body.position, suit.body.linearVelocity)
        run(world, 5.0)
        assertSame("not on the deck", platform, suit.standingOn)
        // Put to sleep there by hand: the gusts sway a suit past what lets it sleep on a deck.
        suit.ride(platform)
        assertEquals("not asleep on the deck", platform.id, suit.ridingOn)
        val from = suit.chill
        run(world, 30.0)
        println("asleep on a deck: snow %.2f, chill %.3f to %.3f".format(suit.air.snow, from, suit.chill))
        assertEquals("woke or fell off", platform.id, suit.ridingOn)
        assertTrue("not getting cold on the deck: ${suit.chill - from}", suit.chill - from > 30.0 / 600.0)
    }

    @Test
    fun `warp holds down to physics for someone asleep out in a blizzard, and stops on rails as one comes`() {
        val spot = blizzardSpot()
        // In it: no rails.
        val world = World.default(catalog).also { it.weatherConfig = extreme; it.syncClock(spot.time) }
        val suit = world.spawnOnSurface(walker, siteAt(spot.centre))
        untilAsleep(world, suit, 30.0)
        assertEquals("rails let in a blizzard", World.PHYSICS_WARP, world.maxWarp(), 0.0)
        assertEquals("went on rails", 0.0, world.advanceOnRails(600.0), 0.0)

        // Asleep there before it came: rails, in short slices, until the snow reaches them.
        val early = World.default(catalog).also { it.weatherConfig = extreme; it.syncClock(spot.time - spot.age - 600.0) }
        val before = early.spawnOnSurface(walker, siteAt(spot.centre))
        untilAsleep(early, before, 30.0)
        before.chill = 0.0
        val start = early.time
        assertTrue("no rails before it came: ${early.maxWarp()}", early.maxWarp() > World.PHYSICS_WARP)
        val done = early.advanceOnRails(spot.age + 1_200.0)
        println("rails before the blizzard: %.0f s of %.0f, snow %.2f, chill %.4f, warp now %.0f".format(
            done, spot.age + 1_200.0, before.air.snow, before.chill, early.maxWarp()))
        assertTrue("rails ran through the blizzard", done < spot.age + 1_200.0 - 1.0)
        assertTrue("stopped with no snow there: ${before.air.snow}", before.air.snow > World.BLIZZARD_CHILL_FROM)
        assertEquals(World.PHYSICS_WARP, early.maxWarp(), 0.0)
        assertTrue("chill skipped or overdone on rails: ${before.chill}", before.chill > 0.0 && before.chill < 0.05)
        assertEquals(start + done, early.time, 1e-6)

    }

    @Test
    fun `someone asleep and still cold warms on rails as they would awake`() {
        val spot = blizzardSpot()
        val world = World.default(catalog).also { it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.CALM); it.syncClock(spot.time) }
        val suit = world.spawnOnSurface(walker, siteAt(spot.centre))
        untilAsleep(world, suit, 30.0)
        suit.chill = 0.5
        assertTrue("no rails while warming: ${world.maxWarp()}", world.maxWarp() > World.PHYSICS_WARP)
        val warmed = world.advanceOnRails(600.0)
        println("warming on rails: %.0f s, chill %.4f".format(warmed, suit.chill))
        assertEquals("rails stopped", 600.0, warmed, 1e-6)
        assertEquals("didn't warm as awake", 0.5 - 600.0 / World.WARM_OUT, suit.chill, 1e-6)
    }

    @Test
    fun `someone asleep on snowy ground with no blizzard near lets warp go to the top rate`() {
        val terra = SolarSystem.defaultSystem().body("terra")
        val w = Weather(terra, extreme)
        val pole = Vec3(0.0, -1.0, 0.0)
        assertTrue("not cold country", w.blizzardCold(pole) > 0.0)
        var t = 6 * 3_600.0
        while (w.blizzardDue(pole, t, World.BLIZZARD_LOOK)) t += 1_800.0
        val world = World.default(catalog).also { it.weatherConfig = extreme; it.syncClock(t) }
        val suit = world.spawnOnSurface(walker, LaunchSite("pole", "Pole", "terra", Math.toRadians(-89.0), 0.0))
        untilAsleep(world, suit, 30.0)
        suit.chill = 0.0
        println("pole asleep at %.0f s: warp %.0f".format(world.time, world.maxWarp()))
        assertEquals("held down with no blizzard near", World.WARP_RATES.last(), world.maxWarp(), 0.0)
        // Warming still holds it to rails slices.
        suit.chill = 0.5
        assertTrue("warming at the top rate", world.maxWarp() < World.WARP_RATES.last())
    }

    @Test
    fun `snow over warm ground does not hold warp to physics`() {
        val terra = SolarSystem.defaultSystem().body("terra")
        val w = Weather(terra, extreme)
        // Dry land near the equator, well inland.
        val at = (0 until 360).map { Math.toRadians(it.toDouble()) }.map { lon ->
            Vec3(kotlin.math.cos(lon), 0.0, kotlin.math.sin(lon))
        }.first { terra.terrain!!.elevation(it) > 50.0 && w.blizzardCold(it) == 0.0 }
        val world = World.default(catalog).also { it.weatherConfig = extreme; it.syncClock(6 * 3_600.0) }
        val suit = world.spawnOnSurface(walker, siteAt(at))
        untilAsleep(world, suit, 30.0)
        suit.air.snow = 0.9
        suit.air.wind.setTo(30.0, 0.0, 0.0)
        assertEquals("chills on warm ground", 0.0, world.blizzardChill(suit, world.attractorFor(suit)), 0.0)
        assertTrue("held to physics: ${world.maxWarp()}", world.maxWarp() > World.PHYSICS_WARP)
    }
}
