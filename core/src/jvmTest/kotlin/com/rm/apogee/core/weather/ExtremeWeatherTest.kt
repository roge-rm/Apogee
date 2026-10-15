package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.sea.SeaSample
import com.rm.apogee.core.terrain.Noise
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Extreme: worse than Wild on every count, saved and shared like the rest, and still deterministic. */
class ExtremeWeatherTest {

    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val luna = system.body("luna")
    private val terrain = terra.terrain!!

    private fun weather(intensity: WeatherIntensity) = Weather(terra, WeatherConfig(intensity = intensity))

    private fun sea(intensity: WeatherIntensity) = Sea(terra, luna, weather(intensity), WeatherConfig().seed)

    private fun direction(i: Int): Vec3 {
        val lat = (Noise.hash(17, i, 0, 0) - 0.5) * 1.6
        val lon = Noise.hash(17, i, 1, 0) * 2 * Math.PI
        return Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon)).normalizeInPlace()
    }

    /** Directions over deep open sea. */
    private fun openSea(count: Int): List<Vec3> =
        (0 until 20_000).asSequence().map { direction(it) }.filter { terrain.elevation(it) < -300.0 }.take(count).toList()

    @Test
    fun `the rare-event factor goes up with the setting`() {
        assertEquals(0.0, WeatherIntensity.CALM.rare, 0.0)
        assertEquals(0.0, WeatherIntensity.NORMAL.rare, 0.0)
        assertEquals(0.1, WeatherIntensity.WILD.rare, 0.0)
        assertEquals(1.0, WeatherIntensity.EXTREME.rare, 0.0)
        val wild = WeatherIntensity.WILD; val extreme = WeatherIntensity.EXTREME
        assertTrue(extreme.wind > wild.wind && extreme.storms > wild.storms && extreme.thermals > wild.thermals)
        assertTrue(extreme.gusts > wild.gusts && extreme.sea > wild.sea && extreme.stormPower > wild.stormPower)
        assertEquals("Extreme", extreme.label)
    }

    @Test
    fun `Extreme goes into a config and comes back, and older configs still read`() {
        val json = Json { ignoreUnknownKeys = true }
        val config = WeatherConfig(seed = 7, intensity = WeatherIntensity.EXTREME, clouds = CloudCover.HEAVY)
        val text = json.encodeToString(WeatherConfig.serializer(), config)
        assertTrue(text, "\"extreme\"" in text)
        assertEquals(config, json.decodeFromString(WeatherConfig.serializer(), text))
        val old = json.decodeFromString(WeatherConfig.serializer(), """{"seed":3,"intensity":"wild"}""")
        assertEquals(WeatherIntensity.WILD, old.intensity)
    }

    @Test
    fun `two Extreme skies agree wherever they're worked out`() {
        val a = weather(WeatherIntensity.EXTREME)
        val b = weather(WeatherIntensity.EXTREME)
        val sa = AirSample(); val sb = AirSample()
        for (i in 0 until 150) {
            val p = direction(i).mulInPlace(terra.radius + 50.0 + 2_000.0 * Noise.hash(5, i, 0, 0))
            val t = 20_000.0 * Noise.hash(6, i, 0, 0)
            a.sample(p, t, sa)
            b.sample(direction(i + 500).mulInPlace(terra.radius + 800.0), t + 3.0, sb)
            b.sample(p, t, sb)
            assertEquals(sa.wind.x, sb.wind.x, 0.0)
            assertEquals(sa.wind.y, sb.wind.y, 0.0)
            assertEquals(sa.wind.z, sb.wind.z, 0.0)
            assertEquals(sa.turbulence, sb.turbulence, 0.0)
        }
    }

    @Test
    fun `two Extreme seas agree, rogues and all`() {
        val a = sea(WeatherIntensity.EXTREME)
        val b = sea(WeatherIntensity.EXTREME)
        val points = openSea(40).mapIndexed { i, d -> d.mulInPlace(terra.radius) to 2_000.0 + 53.0 * i }
        val first = points.map { (p, t) -> a.height(p, t) to a.rogueAt(p, t) }
        val second = points.reversed().map { (p, t) -> b.height(p, t) to b.rogueAt(p, t) }.reversed()
        assertEquals(first, second)
    }

    @Test
    fun `Extreme blows harder than Wild`() {
        val wild = weather(WeatherIntensity.WILD)
        val extreme = weather(WeatherIntensity.EXTREME)
        val s = AirSample()
        var w = 0.0; var e = 0.0
        for (i in 0 until 300) {
            val d = direction(i)
            val p = d.mulInPlace(terra.radius + maxOf(terrain.elevation(d), 0.0) + 30.0)
            val t = 50_000.0 * Noise.hash(9, i, 0, 0)
            w += wild.sample(p, t, s).wind.length
            e += extreme.sample(p, t, s).wind.length
        }
        assertTrue("Extreme $e against Wild $w", e > 1.15 * w)
    }

    @Test
    fun `Extreme has more storms than Wild, and stronger ones`() {
        val wild = weather(WeatherIntensity.WILD)
        val extreme = weather(WeatherIntensity.EXTREME)
        val e = Vec3(); val n = Vec3()
        val ws = ArrayList<Storms.Storm>(); val es = ArrayList<Storms.Storm>()
        for (i in 0 until 200) {
            val d = direction(i)
            frame(d, e, n)
            wild.stormModel.around(d, e, n, 1, 5_000.0, ws)
            extreme.stormModel.around(d, e, n, 1, 5_000.0, es)
        }
        assertTrue("Extreme ${es.size} against Wild ${ws.size}", es.size > ws.size)
        // Their winds are stronger too: the strongest updraught found under each.
        val sample = AirSample()
        fun strongestLift(w: Weather, storms: List<Storms.Storm>): Double = storms.take(60).maxOf { st ->
            val t = st.start + 0.5 * Storms.CYCLE
            val main = st.mainCell
            val centre = Vec3(); val track = Vec3(); val right = Vec3()
            w.stormModel.frameAt(st, t, centre, track, right)
            val at = w.stormModel.place(centre, track, right, st.cellAlong[main], st.cellAcross[main], terra.radius + st.base + 1_000.0, Vec3())
            w.sample(at, t, sample).lift
        }
        assertTrue(strongestLift(extreme, es) > strongestLift(wild, ws))
    }

    @Test
    fun `Extreme seas are bigger than Wild ones`() {
        val wild = sea(WeatherIntensity.WILD)
        val extreme = sea(WeatherIntensity.EXTREME)
        val s = SeaSample()
        var w = 0.0; var e = 0.0
        for ((i, d) in openSea(60).withIndex()) {
            val p = d.mulInPlace(terra.radius)
            val t = 3_000.0 + 7_919.0 * i
            w += wild.sample(p, t, s).significantHeight
            e += extreme.sample(p, t, s).significantHeight
        }
        assertTrue("Extreme $e m against Wild $w m", e > 1.15 * w)
    }

    /** Open-sea rogues that peak near [places] over a few hours, counted once each. */
    private fun openRogues(sea: Sea, places: List<Vec3>): Int {
        val seen = HashSet<Pair<Double, Double>>()
        for (d in places) for (slot in 0 until 20) {
            val p = d.copy().mulInPlace(sea.body.radius)
            for (r in sea.roguesNear(p, 6_000.0 + slot * 600.0)) if (!r.storm) seen.add(r.focus.x to r.time)
        }
        return seen.size
    }

    @Test
    fun `rogues come up in open sea on Extreme, rarely on Wild and never on Normal`() {
        val extreme = sea(WeatherIntensity.EXTREME)
        val s = SeaSample()
        // Rough open sea away from storms.
        val places = openSea(600).filter {
            val p = it.copy().mulInPlace(terra.radius)
            extreme.sample(p, 6_000.0, s).significantHeight > 4.0 && s.stormHeight < 0.5
        }.take(6)
        assertTrue("found ${places.size} rough places", places.size == 6)
        val e = openRogues(extreme, places)
        val w = openRogues(sea(WeatherIntensity.WILD), places)
        val n = openRogues(sea(WeatherIntensity.NORMAL), places)
        assertTrue("Extreme found $e", e > 0)
        assertTrue("Wild found $w against Extreme's $e", w < e)
        assertEquals(0, n)
    }
}
