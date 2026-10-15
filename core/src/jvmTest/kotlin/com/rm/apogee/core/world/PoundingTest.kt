package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.sea.SeaSample
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.Storms
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.weather.frame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Waves work a hull's joints: stock boats take a storm sea, something flimsy doesn't. */
class PoundingTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }

    /** A strong storm over deep sea on [intensity], as a site under it and the time it's at its worst. */
    private fun stormSea(intensity: WeatherIntensity): Pair<LaunchSite, Double> {
        val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
        val terrain = terra.terrain!!
        val weather = Weather(terra, WeatherConfig(intensity = intensity))
        val sea = com.rm.apogee.core.sea.Sea(terra, SolarSystem.defaultSystem().body("luna"), weather, WeatherConfig().seed)
        val e = Vec3(); val n = Vec3()
        val list = ArrayList<Storms.Storm>()
        val sample = SeaSample()
        for (i in 0 until 2_000) {
            val lat = (Noise.hash(23, i, 0, 0) - 0.5) * 1.4
            val lon = Noise.hash(23, i, 1, 0) * 2 * Math.PI
            val d = Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon)).normalizeInPlace()
            if (terrain.elevation(d) > -300.0) continue
            frame(d, e, n)
            list.clear()
            weather.stormModel.around(d, e, n, 1, 20_000.0, list)
            for (st in list) {
                if (st.strength < 0.8) continue
                val t = st.start + 0.5 * Storms.CYCLE
                val c = weather.stormModel.centreAt(st, t, Vec3())
                if (terrain.elevation(c) > -300.0) continue
                if (sea.sample(c.copy().mulInPlace(terra.radius), t, sample).stormHeight < 6.0) continue
                return LaunchSite("storm", "Storm", "terra", SolarSystem.latitudeOf(c), SolarSystem.longitudeOf(c)) to t
            }
        }
        throw AssertionError("no storm sea")
    }

    private fun world(intensity: WeatherIntensity, time: Double): World = World.default(catalog).also {
        it.weatherConfig = WeatherConfig(intensity = intensity)
        it.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = time, nextVesselId = 1L))
    }

    /** Two punt hulls end to end on one thin girder: fine on a pond, not in a big sea. */
    private fun raft(): CraftDesign = CraftDesign(
        name = "Raft",
        parts = listOf(
            PlacedPart("hull-punt", Vec3(0.0, 0.0, 0.0), Quat.identity()),
            PlacedPart("girder-long", Vec3(0.0, 7.0, 0.0), Quat.identity(), parentIndex = 0, parentNodeId = "top", ownNodeId = "bottom"),
            PlacedPart("hull-punt", Vec3(0.0, 14.0, 0.0), Quat.identity(), parentIndex = 1, parentNodeId = "top", ownNodeId = "bottom"),
        ),
        catalogHash = catalog.contentHash,
        orientation = CraftOrientation.HORIZONTAL,
    )

    @Test
    fun `stock boats ride out a wild storm sea without breaking`() {
        val (site, time) = stormSea(WeatherIntensity.WILD)
        for (design in listOf(StockCraft.boat(catalog), StockCraft.cutter(catalog), StockCraft.coaster(catalog), StockCraft.minnow(catalog))) {
            val world = world(WeatherIntensity.WILD, time)
            val boat = world.spawnOnSurface(design, site)
            val parts = boat.defs.size
            run(world, 180.0)
            assertTrue("the ${design.name} lost parts", boat.defs.size == parts && boat.broken.none { it })
        }
    }

    @Test
    fun `a flimsy raft holds in the harbour and on an ordinary day, and is worked hard in an extreme storm sea`() {
        val harbour = world(WeatherIntensity.NORMAL, 3_000.0)
        val moored = harbour.spawnOnSurface(raft(), World.launchSites.first { it.id == "harbour" })
        run(harbour, 120.0)
        assertTrue("it broke in the harbour, worst ${moored.stress}", moored.defs.size == 3)

        // Out in an Extreme storm sea the same raft is worked far harder than on a Normal day there.
        val (site, time) = stormSea(WeatherIntensity.EXTREME)
        val worst = HashMap<WeatherIntensity, Double>()
        for (intensity in listOf(WeatherIntensity.NORMAL, WeatherIntensity.EXTREME)) {
            val world = world(intensity, time - 20.0)
            val raft = world.spawnOnSurface(raft(), site)
            var most = 0.0
            var seconds = 0
            while (raft.defs.size == 3 && seconds < 120) {
                repeat((1.0 / dt).toInt()) { world.step(dt); if (seconds > 5) most = maxOf(most, raft.stress) }
                seconds++
            }
            if (intensity == WeatherIntensity.NORMAL) assertTrue("it broke on $intensity", raft.defs.size == 3)
            worst[intensity] = if (raft.defs.size < 3) Double.MAX_VALUE else most
        }
        assertTrue("worst stress $worst", worst[WeatherIntensity.EXTREME]!! > 1.5 * worst[WeatherIntensity.NORMAL]!!)
    }

    /** The upward water force recorded for Stress on each of [boat]'s parts this tick, summed, in N. */
    private fun recordedUp(boat: com.rm.apogee.core.craft.Vessel): Double {
        val up = boat.body.position.normalized()
        var sum = 0.0
        for (i in boat.defs.indices) sum += boat.partForce[i * 3] * up.x + boat.partForce[i * 3 + 1] * up.y + boat.partForce[i * 3 + 2] * up.z
        return sum
    }

    @Test
    fun `a floating hull's steady buoyancy loads nothing, and a push under loads it by the water it displaces`() {
        val world = world(WeatherIntensity.CALM, 3_000.0)
        world.steadyWind = Vec3()
        val punt = world.spawnOnSurface(StockCraft.boat(catalog), World.launchSites.first { it.id == "harbour" })
        // Kept awake: asleep it would skip the water altogether. She has no tanks to hold with.
        punt.control.holdDepth = true
        run(world, 12.0)
        assertTrue("asleep", !punt.dormant && punt.buoyed)
        val weight = punt.body.mass * 9.81
        assertTrue("afloat it carries ${recordedUp(punt)} N", kotlin.math.abs(recordedUp(punt)) < 0.05 * weight)

        // Half a metre deeper: the punt's 2.4 by 8 m hull displaces 9.6 m³ more.
        val up = punt.body.position.normalized()
        punt.body.position.addScaledInPlace(up, -0.5)
        world.step(dt)
        val density = world.attractorFor(punt).ocean!!.density
        val expected = density * 9.81 * 2.4 * 8.0 * 0.5
        val got = recordedUp(punt)
        assertTrue("pushed under it carries $got N, against $expected N", got in 0.7 * expected..1.3 * expected)
    }
}
