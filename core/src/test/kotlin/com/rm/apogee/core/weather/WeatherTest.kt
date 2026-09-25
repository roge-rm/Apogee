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

        // And the gust front: low down, a core or so out beyond the shaft,
        // a wind to knock things over, whatever the wind was before.
        val steer = storm.steer.copy().normalizeInPlace()
        var strongest = 0.0
        for (k in 0 until 16) {
            val a = k * Math.PI / 8
            val side = here.copy().crossInPlace(steer).normalizeInPlace()
            val at = here.copy().mulInPlace(radius).addScaledInPlace(steer, 0.5 * storm.core)
                .addScaledInPlace(steer, kotlin.math.cos(a) * 1.3 * storm.core)
                .addScaledInPlace(side, kotlin.math.sin(a) * 1.3 * storm.core)
            val g = maxOf(terrain.elevation(at.copy().normalizeInPlace()), 0.0)
            at.normalizeInPlace().mulInPlace(radius + g + 100.0)
            w.sample(at, t, s)
            strongest = maxOf(strongest, s.wind.length)
        }
        assertTrue("a storm's gust front blows hard: $strongest m/s", strongest > 20.0 * storm.strength)

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

    /**
     * A deck is cloudy where its puffs are drawn and clear between them:
     * climbing through a gap in the drawn deck, there is no fog.
     */
    @Test
    fun `deck air is cloudy only inside the drawn puffs`() {
        val w = weather()
        val s = AirSample()
        val shapes = ArrayList<CloudShape>()
        var inside = 0; var gaps = 0
        for (i in 0 until 300) {
            val dir = randomDirection(i)
            shapes.clear()
            w.clouds(dir, 6_000.0, 3_000.0, shapes)
            val decks = shapes.filter { it.type == CloudType.STRATUS || it.type == CloudType.ALTOSTRATUS }
            if (decks.isEmpty()) continue
            for (shape in decks) for (lobe in shape.lobes) {
                // Only puffs well inside what was listed: beside one at the
                // edge, a "gap" can be under a puff of a cell never listed.
                if (lobe.centre.copy().normalizeInPlace().distanceTo(dir) * w.body.radius > 2_500.0) continue
                // Its heart is cloud of its own kind.
                w.sample(lobe.centre, 3_000.0, s)
                assertTrue("no cloud at the heart of a drawn ${shape.type} puff", s.cloudDensity > 0.0)
                inside++
                // Well beyond its edge, at its height, between puffs: this deck is not there.
                val out = lobe.centre.copy()
                val e = Vec3(); val n = Vec3()
                frame(out.copy().normalizeInPlace(), e, n)
                out.addScaledInPlace(e, lobe.horizontal * 1.2)
                val clearOfAll = decks.all { d -> d.lobes.all { it.centre.distanceTo(out) > it.horizontal * 1.05 } }
                if (clearOfAll) {
                    w.sample(out, 3_000.0, s)
                    assertTrue("fog in a gap in the ${shape.type} deck", s.cloudType != shape.type || s.cloudDensity == 0.0)
                    gaps++
                }
            }
            if (inside > 20 && gaps > 10) break
        }
        assertTrue("tested $inside puffs, $gaps gaps", inside > 5 && gaps > 3)
    }

    /** A fresh sky lists quickly: nothing slow on a cold start. */
    @Test
    fun `a fresh sky lists quickly`() {
        val w = weather()
        val start = System.nanoTime()
        w.clouds(randomDirection(42), 45_000.0, 1_000.0, ArrayList())
        val ms = (System.nanoTime() - start) / 1e6
        println("fresh cloud listing: %.0f ms".format(ms))
        assertTrue("took $ms ms", ms < 1_500.0)
    }

    @Test
    fun `a storm is a billowing tower with a flat anvil and a rain curtain under it`() {
        val terra = com.rm.apogee.core.orbit.SolarSystem.defaultSystem().body(com.rm.apogee.core.orbit.SolarSystem.HOMEWORLD_ID)
        val weather = Weather(terra, WeatherConfig(intensity = WeatherIntensity.WILD))
        // Find a storm anywhere, then look at it close to.
        var storm: CloudShape? = null
        for (t in listOf(1_200.0, 5_000.0, 9_000.0, 14_000.0)) {
            val all = ArrayList<CloudShape>()
            weather.globalCover(400_000.0, t, all)
            for (shape in all.filter { it.type == CloudType.CUMULONIMBUS && it.amount > 0.6 }) {
                val at = shape.lobes.last().centre.copy().normalizeInPlace()
                val near = ArrayList<CloudShape>()
                weather.clouds(at, 30_000.0, t, near)
                storm = near.firstOrNull { it.type == CloudType.CUMULONIMBUS && it.amount > 0.6 }
                if (storm != null) break
            }
            if (storm != null) break
        }
        val s = storm ?: throw AssertionError("no mature storm found in a wild sky")
        assertTrue("many lobes, not a few big ones: ${s.lobes.size}", s.lobes.size >= 30)
        assertTrue("a flat anvil and base", s.lobes.count { it.flat } >= 5)
        assertTrue("rain falling out of it", s.rain.isNotEmpty())
        val curtain = s.rain.first()
        val bottom = curtain.centre.length - terra.radius - curtain.vertical
        assertTrue("the curtain reaches down to the ground: $bottom m", bottom < 2_500.0)
    }
}
