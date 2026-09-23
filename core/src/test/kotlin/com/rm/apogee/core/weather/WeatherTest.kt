package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.Noise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The weather as a pure function, and whether the shapes in it are the
 * ones a pilot would expect: faster aloft, faster on crests than in
 * hollows, lift under cumulus, no thermals at sea, storms only when the
 * world allows them, and all of it the same wherever it is computed.
 */
class WeatherTest {

    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
    private val radius = terra.radius
    private val terrain = terra.terrain!!

    private fun weather(intensity: WeatherIntensity = WeatherIntensity.NORMAL) =
        Weather(terra, WeatherConfig(intensity = intensity))

    private fun direction(lat: Double, lon: Double) =
        Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon)).normalizeInPlace()

    /** A point [agl] metres above the ground or sea at [dir]. */
    private fun above(dir: Vec3, agl: Double): Vec3 {
        val ground = maxOf(terrain.elevation(dir), 0.0)
        return dir.copy().mulInPlace(radius + ground + agl)
    }

    private fun randomDirection(i: Int): Vec3 {
        // Spread over the populated band, away from the poles.
        val lat = (Noise.hash(7, i, 0, 0) - 0.5) * 1.6
        val lon = Noise.hash(7, i, 1, 0) * 2 * Math.PI
        return direction(lat, lon)
    }

    @Test
    fun `the same place and time give the same air, wherever it is computed`() {
        val a = weather(WeatherIntensity.WILD)
        val b = weather(WeatherIntensity.WILD)
        val sa = AirSample(); val sb = AirSample()
        for (i in 0 until 200) {
            val p = above(randomDirection(i), 20.0 + 3_000.0 * Noise.hash(3, i, 0, 0))
            val t = 10_000.0 * Noise.hash(4, i, 0, 0)
            a.sample(p, t, sa)
            // Out of order on the other, so any hidden state would show.
            b.sample(above(randomDirection(i + 1_000), 500.0), t + 7.0, sb)
            b.sample(p, t, sb)
            assertEquals("wind x at $i", sa.wind.x, sb.wind.x, 0.0)
            assertEquals("wind y at $i", sa.wind.y, sb.wind.y, 0.0)
            assertEquals("wind z at $i", sa.wind.z, sb.wind.z, 0.0)
            assertEquals(sa.cloudDensity, sb.cloudDensity, 0.0)
            assertEquals(sa.precipitation, sb.precipitation, 0.0)
        }
    }

    @Test
    fun `wind picks up with height, and there is none in space`() {
        val w = weather()
        val s = AirSample()
        // Averaged over many places: any one can be a hollow or a thermal.
        fun meanSpeed(agl: Double): Double {
            var total = 0.0
            for (i in 0 until 150) {
                w.sample(above(randomDirection(i), agl), 100.0, s)
                total += s.wind.copy().addScaledInPlace(randomDirection(i), -(s.wind dot randomDirection(i))).length
            }
            return total / 150
        }
        val low = meanSpeed(10.0)
        val mid = meanSpeed(300.0)
        val high = meanSpeed(3_000.0)
        assertTrue("10 m ($low) should be slower than 300 m ($mid)", low < mid)
        assertTrue("300 m ($mid) should be slower than 3 km ($high)", mid < high)
        assertTrue("a typical breeze aloft, not a gale or a calm: $high", high in 3.0..30.0)

        w.sample(randomDirection(1).mulInPlace(radius + 40_000.0), 100.0, s)
        assertEquals("no wind at 40 km", 0.0, s.wind.length, 1e-9)
    }

    /**
     * The terrain's hand in it: over many places, crests are windier than
     * hollows at the same height above them.
     */
    @Test
    fun `crests are windier than hollows`() {
        val w = weather(WeatherIntensity.CALM)
        val tw = TerrainWind(terrain, radius)
        val d = DoubleArray(TerrainWind.SIZE)
        val s = AirSample()
        var crest = 0.0; var crests = 0
        var hollow = 0.0; var hollows = 0
        for (i in 0 until 3_000) {
            val dir = randomDirection(i)
            tw.describe(dir, d)
            if (d[TerrainWind.OCEAN] > 0.5) continue
            val exposure = d[TerrainWind.H0] - d[TerrainWind.MEAN]
            if (exposure > 120.0 || exposure < -120.0) {
                w.sample(above(dir, 30.0), 0.0, s)
                val horizontal = s.wind.copy().addScaledInPlace(dir, -(s.wind dot dir)).length
                if (exposure > 0) { crest += horizontal; crests++ } else { hollow += horizontal; hollows++ }
            }
            if (crests > 40 && hollows > 40) break
        }
        assertTrue("need both: $crests crests, $hollows hollows", crests > 10 && hollows > 10)
        assertTrue("crests ${crest / crests} m/s should beat hollows ${hollow / hollows} m/s", crest / crests > hollow / hollows * 1.2)
    }

    @Test
    fun `thermals lift under their cumulus, and never over the sea`() {
        val w = weather()
        val s = AirSample()
        val found = ArrayList<Convection.Thermal>()
        var tested = 0
        val time = 2_000.0
        for (i in 0 until 400) {
            found.clear()
            w.cumulus(randomDirection(i), 2, time, found)
            for (th in found) {
                assertTrue("a thermal over the sea", terrain.elevation(th.origin) >= 0.0)
                val column = w.convectionModel.columnAt(th, 500.0, Vec3())
                if (w.convectionModel.envelope(th, time) < 0.5) continue
                w.sample(column.copy().mulInPlace(radius + th.ground + 500.0), time, s)
                assertTrue("lift in the column: ${s.lift} of ${th.strength}", s.lift > 0.3 * th.strength * w.convectionModel.envelope(th, time))
                tested++
            }
            if (tested > 10) break
        }
        assertTrue("found only $tested thermals to test", tested > 5)
    }

    @Test
    fun `calm skies have no storms and no lightning`() {
        val w = weather(WeatherIntensity.CALM)
        val s = AirSample()
        val strikes = ArrayList<Strike>()
        for (i in 0 until 300) {
            val dir = randomDirection(i)
            w.sample(above(dir, 800.0), 5_000.0, s)
            assertEquals(0.0, s.precipitation, 0.0)
            assertTrue(s.cloudType != CloudType.CUMULONIMBUS)
            w.strikes(dir, 0.0, 3_000.0, strikes)
        }
        assertTrue(strikes.isEmpty())
    }

    @Test
    fun `wild skies have storms that travel, rain and strike`() {
        val w = weather(WeatherIntensity.WILD)
        val storms = ArrayList<Storms.Storm>()
        val e = Vec3(); val n = Vec3()
        var chosen: Storms.Storm? = null
        var t = 0.0
        for (i in 0 until 400) {
            val dir = randomDirection(i)
            frame(dir, e, n)
            storms.clear()
            t = 1_000.0 + 300.0 * (i % 7)
            w.stormModel.around(dir, e, n, 1, t, storms)
            chosen = storms.firstOrNull { w.stormModel.envelope(it, t) > 0.7 }
            if (chosen != null) break
        }
        val storm = chosen ?: throw AssertionError("no mature storm found in a wild sky")
        val here = w.stormModel.centreAt(storm, t, Vec3())
        val later = w.stormModel.centreAt(storm, t + 600.0, Vec3())
        val moved = here.distanceTo(later) * radius
        assertEquals("it moves with its steering wind", storm.steer.length * 600.0, moved, 50.0)

        // Rain below it, a cumulonimbus in it.
        val s = AirSample()
        val shaft = here.copy().mulInPlace(radius).addScaledInPlace(storm.steer.copy().normalizeInPlace(), 0.5 * storm.core)
        val ground = maxOf(terrain.elevation(shaft.copy().normalizeInPlace()), 0.0)
        shaft.normalizeInPlace().mulInPlace(radius + ground + 300.0)
        w.sample(shaft, t, s)
        assertTrue("rain under the shaft: ${s.precipitation}", s.precipitation > 0.3)
        w.sample(here.copy().mulInPlace(radius + storm.base + 2_000.0), t, s)
        assertEquals(CloudType.CUMULONIMBUS, s.cloudType)
        assertTrue("an updraught in the tower: ${s.lift}", s.lift > 3.0)

        val strikes = ArrayList<Strike>()
        w.strikes(here, t - 120.0, t + 120.0, strikes)
        assertTrue("lightning from a mature storm", strikes.isNotEmpty())
        val again = ArrayList<Strike>()
        weather(WeatherIntensity.WILD).strikes(here, t - 120.0, t + 120.0, again)
        assertEquals("the same strikes, computed anew", strikes.map { it.id to it.time }, again.map { it.id to it.time })
    }

    @Test
    fun `turbulence varies over a craft's length`() {
        val w = weather()
        val a = Vec3(); val b = Vec3()
        val p = above(randomDirection(5), 200.0)
        val mean = Vec3(5.0, 0.0, 0.0)
        w.turbulence(p, 10.0, mean, 0.8, a)
        w.turbulence(p.copy().addInPlace(Vec3(8.0, 0.0, 0.0)), 10.0, mean, 0.8, b)
        assertTrue("gusts: ${a.length}", a.length > 0.05)
        assertTrue("different at each end", a.distanceTo(b) > 1e-3)
        w.turbulence(p, 10.0, mean, 0.0, a)
        assertEquals(0.0, a.length, 0.0)
    }

    /** Cheap enough to sample once per craft per tick, after warming its caches. */
    @Test
    fun `a sample is cheap`() {
        val w = weather()
        val s = AirSample()
        val start = above(randomDirection(9), 400.0)
        val east = Vec3(); val north = Vec3()
        frame(start.copy().normalizeInPlace(), east, north)
        // A craft flying along at 100 m/s: warm up, then time.
        fun fly(steps: Int, t0: Double) {
            val p = start.copy()
            for (i in 0 until steps) {
                p.addScaledInPlace(east, 100.0 / 60.0)
                w.sample(p, t0 + i / 60.0, s)
            }
        }
        fly(3_000, 0.0)
        val begin = System.nanoTime()
        fly(6_000, 50.0)
        val micros = (System.nanoTime() - begin) / 1_000.0 / 6_000.0
        println("weather sample: %.1f us".format(micros))
        assertTrue("a sample took $micros us", micros < 80.0)
    }
}
