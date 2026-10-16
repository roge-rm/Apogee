package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.sea.SeaSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hurricanes: they form over warm tropical sea, spin the right way with a calm eye, weaken on land,
 * raise a sea and a surge near them only, and only when the setting has rare events.
 */
class HurricaneTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val luna = system.body("luna")
    private val terrain = terra.terrain!!
    private val radius = terra.radius

    private fun weather(intensity: WeatherIntensity) = Weather(terra, WeatherConfig(intensity = intensity))

    private fun all(weather: Weather, time: Double): List<LiveHurricane> =
        ArrayList<LiveHurricane>().also { weather.hurricanes(Vec3(1.0, 0.0, 0.0), time, 1e9, it) }

    /** The unit direction [r] metres from unit [c] on bearing [bearing] (radians from east). */
    private fun at(c: Vec3, r: Double, bearing: Double): Vec3 {
        val e = Vec3(); val n = Vec3()
        frame(c, e, n)
        return Vec3().setTo(c).mulInPlace(radius)
            .addScaledInPlace(e, cos(bearing) * r).addScaledInPlace(n, sin(bearing) * r).normalizeInPlace()
    }

    /** Each hurricane seen on Extreme every [step] seconds over [hours], in time order. */
    private fun sightings(step: Double = 600.0, hours: Double = 400.0): Map<Long, List<Pair<Double, LiveHurricane>>> =
        sighted.getOrPut(step to hours) {
            val w = weather(WeatherIntensity.EXTREME)
            val out = LinkedHashMap<Long, MutableList<Pair<Double, LiveHurricane>>>()
            var t = 0.0
            while (t < hours * 3_600.0) {
                for (h in all(w, t)) out.getOrPut(h.id) { ArrayList() }.add(t to h)
                t += step
            }
            out
        }

    private val sighted = HashMap<Pair<Double, Double>, Map<Long, List<Pair<Double, LiveHurricane>>>>()

    private fun strong(min: Double): Pair<Double, LiveHurricane> =
        sightings().values.flatten()
            .filter { it.second.strength >= min && terrain.elevation(it.second.centre) < -300.0 }
            .maxByOrNull { it.second.strength } ?: error("no hurricane as strong as $min")

    @Test
    fun `none on Calm or Normal, and Wild has far fewer than Extreme`() {
        for (intensity in listOf(WeatherIntensity.CALM, WeatherIntensity.NORMAL)) {
            val w = weather(intensity)
            assertTrue(!w.hurricaneModel.active)
            for (k in 0 until 200) assertTrue(all(w, k * 7_200.0).isEmpty())
        }
        val wild = weather(WeatherIntensity.WILD)
        val extreme = weather(WeatherIntensity.EXTREME)
        var wildLive = 0; var extremeLive = 0
        for (k in 0 until 400) {
            val t = k * 3_700.0
            if (all(wild, t).isNotEmpty()) wildLive++
            if (all(extreme, t).isNotEmpty()) extremeLive++
        }
        println("live at 400 times: Extreme $extremeLive, Wild $wildLive")
        assertTrue("Extreme usually has one going: $extremeLive of 400", extremeLive > 240)
        assertTrue("Wild has far fewer: $wildLive vs $extremeLive", wildLive * 3 < extremeLive)
    }

    @Test
    fun `two instances agree on where they are and what they blow`() {
        val a = weather(WeatherIntensity.EXTREME)
        val b = weather(WeatherIntensity.EXTREME)
        // Warm b up elsewhere and at other times so its caches differ.
        for (k in 0 until 30) all(b, 90_000.0 + k * 5_000.0)
        val sa = AirSample(); val sb = AirSample()
        var compared = 0
        for (k in 0 until 60) {
            val t = k * 13_000.0 + 77.0
            val la = all(a, t); val lb = all(b, t)
            assertEquals(la.size, lb.size)
            for ((x, y) in la.zip(lb)) {
                assertEquals(x.id, y.id)
                assertEquals(x.centre, y.centre)
                assertEquals(x.strength, y.strength, 0.0)
                for (i in 0 until 12) {
                    val p = at(x.centre, x.maxWindRadius * (0.2 + 0.5 * i), i * 1.3).mulInPlace(radius + 300.0 + 400.0 * (i % 3))
                    a.sample(p, t, sa); b.sample(p, t, sb)
                    assertEquals(sa.wind, sb.wind)
                    assertEquals(sa.precipitation, sb.precipitation, 0.0)
                    assertEquals(sa.cloudDensity, sb.cloudDensity, 0.0)
                    assertEquals(sa.turbulence, sb.turbulence, 0.0)
                    compared++
                }
            }
        }
        assertTrue(compared > 50)
    }

    @Test
    fun `the eye is calm, the eyewall near seventy metres a second, turning the right way`() {
        val (t, h) = strong(0.9)
        val w = weather(WeatherIntensity.EXTREME)
        val s = AirSample()
        val height = radius + 500.0
        w.sample(Vec3().setTo(h.centre).mulInPlace(height), t, s)
        val eye = s.wind.length
        val eyeRain = s.precipitation
        var wall = 0.0
        for (i in 0 until 24) {
            for (f in listOf(0.9, 1.0, 1.15, 1.3)) {
                val d = at(h.centre, h.maxWindRadius * f, i * Math.PI / 12.0)
                if (terrain.elevation(d) > 0.0) continue
                w.sample(Vec3().setTo(d).mulInPlace(height), t, s)
                wall = maxOf(wall, s.wind.length)
            }
        }
        println("strength ${h.strength}: eye $eye m/s, eyewall $wall m/s, eye rain $eyeRain")
        assertTrue("eye $eye vs wall $wall", eye < 0.3 * wall)
        assertTrue("eye rain $eyeRain", eyeRain < 0.2)
        val expected = Hurricanes.MAX_WIND * h.strength
        assertTrue("eyewall $wall vs $expected", wall > 0.85 * expected && wall < expected + 20.0)

        // Anticlockwise in the north, clockwise in the south: east of the eye the wind has a
        // northward part in the north.
        val east = at(h.centre, h.maxWindRadius, 0.0)
        w.sample(Vec3().setTo(east).mulInPlace(height), t, s)
        val e = Vec3(); val n = Vec3()
        frame(east, e, n)
        val northward = s.wind dot n
        assertTrue("turn ${h.turn}, northward $northward", northward * h.turn > 20.0)
        assertEquals(if (h.centre.y > 0.0) 1.0 else -1.0, h.turn, 0.0)
    }

    @Test
    fun `they form over warm tropical sea`() {
        var formed = 0
        for ((_, seen) in sightings()) {
            val (t0, first) = seen.first()
            // Only ones seen within a step or two of forming.
            if (first.strength > 0.2) continue
            formed++
            val latitude = Math.toDegrees(kotlin.math.asin(first.centre.y))
            assertTrue("formed at $latitude", abs(latitude) in 4.5..23.0)
            assertTrue("formed over ${terrain.elevation(first.centre)} m at $t0", terrain.elevation(first.centre) < 0.0)
        }
        println("$formed seen forming")
        assertTrue(formed >= 10)
    }

    @Test
    fun `they weaken over land`() {
        var pairs = 0; var weaker = 0
        for ((_, seen) in sightings(step = 1_800.0)) {
            for (k in 0 until seen.size - 3) {
                val (_, a) = seen[k]; val (_, b) = seen[k + 1]
                // Not while growing in, nor dying out at the end of its life.
                if (k < 2 || a.strength < 0.1) continue
                if (terrain.elevation(a.centre) <= 10.0 || terrain.elevation(b.centre) <= 10.0) continue
                pairs++
                if (b.strength < a.strength) weaker++
            }
        }
        println("over land: $weaker of $pairs half hours weaker")
        assertTrue(pairs >= 5)
        assertEquals(pairs, weaker)
    }

    @Test
    fun `their sea is big near them and nothing far away`() {
        val (t, h) = strong(0.8)
        val w = weather(WeatherIntensity.EXTREME)
        var under = 0.0
        for (i in 0 until 16) {
            val d = at(h.centre, h.maxWindRadius * 1.1, i * Math.PI / 8.0)
            val out = Storms.StormSea()
            w.hurricaneModel.seaAt(d, t, out)
            under = maxOf(under, out.stormHs)
        }
        println("strength ${h.strength}: sea under the eyewall $under m")
        assertTrue(under > 0.75 * Hurricanes.SEA_HS * h.strength && under <= Hurricanes.SEA_HS + 1e-9)

        // Far from every hurricane, no storm sea from them.
        val live = all(w, t)
        var far = 0
        for (i in 0 until 400) {
            val d = Vec3(cos(i * 2.39) * cos(i * 0.71), sin(i * 0.71), sin(i * 2.39) * cos(i * 0.71)).normalizeInPlace()
            if (live.any { it.centre.distanceTo(d) * radius < it.outerRadius * 1.05 }) continue
            val out = Storms.StormSea()
            w.hurricaneModel.seaAt(d, t, out)
            assertEquals(0.0, out.stormHs, 0.0)
            assertEquals(0.0, w.hurricaneModel.surge(d, t, -5.0), 0.0)
            far++
        }
        assertTrue(far > 100)

        // Its swell gets out over open sea.
        var swell = 0.0
        for (i in 0 until 24) {
            val d = at(h.centre, h.outerRadius * 1.5, i * Math.PI / 12.0)
            if (terrain.elevation(d) > -100.0) continue
            val out = Storms.StormSea()
            w.hurricaneModel.seaAt(d, t, out)
            swell = maxOf(swell, out.swellHs)
        }
        println("swell beyond its edge: $swell m")
        assertTrue(swell > 0.5)

        // And the sea itself carries it, rogues and all.
        val sea = Sea(terra, luna, weather(WeatherIntensity.EXTREME), WeatherConfig().seed)
        val sample = SeaSample()
        var hs = 0.0
        for (i in 0 until 8) {
            val d = at(h.centre, h.maxWindRadius * 1.1, i * Math.PI / 4.0)
            if (terrain.elevation(d) > -200.0) continue
            sea.sample(d, t, sample)
            hs = maxOf(hs, sample.stormHeight)
        }
        assertTrue("sea's storm height $hs", hs > 0.6 * Hurricanes.SEA_HS * h.strength)
    }

    @Test
    fun `the surge raises a shallow coast near one, through the sea itself, and nowhere else`() {
        val w = weather(WeatherIntensity.EXTREME)
        val extreme = Sea(terra, luna, weather(WeatherIntensity.EXTREME), WeatherConfig().seed)
        val normal = Sea(terra, luna, weather(WeatherIntensity.NORMAL), WeatherConfig().seed)
        val a = SeaSample(); val b = SeaSample()
        var best = 0.0
        var checked = 0
        for ((t, h) in sightings(step = 3_600.0).values.flatten().filter { it.second.strength > 0.5 }) {
            if (best > 0.5 && checked > 20) break
            for (i in 0 until 24) for (f in listOf(0.8, 1.0, 1.3, 1.7, 2.2)) {
                val d = at(h.centre, h.maxWindRadius * f, i * Math.PI / 12.0)
                val bed = terrain.elevation(d)
                if (bed > -1.0 || bed < -Hurricanes.SURGE_FULL_DEPTH) continue
                extreme.sample(d, t, a); normal.sample(d, t, b)
                // The sea's tide is higher by the surge on the bed the sea itself sees, and moved by
                // any tsunami passing.
                val surge = w.hurricaneModel.surge(d, t, b.tide - b.depth)
                val tsunami = w.tsunamiModel.height(d, t, b.tide - b.depth)
                assertEquals(surge + tsunami, a.tide - b.tide, 1e-9)
                if (surge > 0.0) checked++
                best = maxOf(best, surge)
            }
            // Deep water under it has none.
            assertEquals(0.0, w.hurricaneModel.surge(h.centre, t, -500.0), 0.0)
        }
        println("biggest surge found ${"%.2f".format(best)} m over $checked shallow points")
        assertTrue(best > 0.5)
        assertTrue(best <= Hurricanes.SURGE + 1e-9)
    }

    @Test
    fun `the first sea worked out near one doesn't ask the terrain much`() {
        val (t, h) = strong(0.5)
        var worst = 0
        var slowest = 0L
        for (k in 0 until 6) {
            val w = weather(WeatherIntensity.EXTREME)
            val d = at(h.centre, h.outerRadius * (0.5 + 0.4 * k), k * 1.1)
            val start = System.nanoTime()
            w.hurricaneModel.seaAt(d, t + k * 100.0, Storms.StormSea())
            slowest = maxOf(slowest, System.nanoTime() - start)
            worst = maxOf(worst, w.hurricaneModel.elevations)
        }
        println("first seaAt: at most $worst terrain heights, ${slowest / 1_000} us")
        assertTrue("$worst terrain heights", worst < 1_200)
    }

    @Test
    fun `heading, wind and sea change smoothly as it moves`() {
        val (t, h) = strong(0.5)
        val w = weather(WeatherIntensity.EXTREME)
        val p = Vec3().setTo(at(h.centre, h.maxWindRadius * 2.0, 0.7)).mulInPlace(radius + 300.0)
        val s = AirSample()
        var last: Vec3? = null
        var lastSea = -1.0
        // Across several track nodes, a tenth of a second apart near each node boundary.
        for (k in 0 until 40) {
            val time = Hurricanes.STEP * kotlin.math.floor(t / Hurricanes.STEP) + (k / 4) * Hurricanes.STEP + (k % 4 - 2) * 0.1
            w.sample(p, time, s)
            val out = Storms.StormSea()
            w.hurricaneModel.seaAt(Vec3().setTo(p).normalizeInPlace(), time, out)
            if (last != null && k % 4 != 0) {
                assertTrue("wind jumped ${last.distanceTo(s.wind)} m/s at $time", last.distanceTo(s.wind) < 0.05)
                assertTrue("sea jumped ${abs(out.stormHs - lastSea)} m at $time", abs(out.stormHs - lastSea) < 0.01)
            }
            last = s.wind.copy(); lastSea = out.stormHs
        }
    }
}
