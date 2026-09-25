package com.rm.apogee.core.sea

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.StormKind
import com.rm.apogee.core.weather.Storms
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.weather.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sea: tides from Luna, waves from the weather, the same wherever they
 * are worked out.
 */
class SeaTest {

    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val luna = system.body("luna")
    private val terrain = terra.terrain!!

    private fun sea(intensity: WeatherIntensity? = WeatherIntensity.NORMAL): Sea {
        val weather = intensity?.let { Weather(terra, WeatherConfig(intensity = it)) }
        return Sea(terra, luna, weather, WeatherConfig().seed)
    }

    private fun direction(lat: Double, lon: Double) =
        Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon)).normalizeInPlace()

    private fun randomDirection(i: Int): Vec3 {
        val lat = (Noise.hash(11, i, 0, 0) - 0.5) * 1.6
        val lon = Noise.hash(11, i, 1, 0) * 2 * Math.PI
        return direction(lat, lon)
    }

    /** A place where the sea bed is between [shallowest] and [deepest] m down. */
    private fun seaWhere(shallowest: Double, deepest: Double): Vec3 {
        for (i in 0 until 20_000) {
            val d = randomDirection(i)
            val depth = -terrain.elevation(d)
            if (depth in shallowest..deepest) return d
        }
        throw AssertionError("no sea $shallowest-$deepest m deep")
    }

    @Test
    fun `the deterministic functions are accurate`() {
        for (i in 0 until 2_000) {
            val x = (Noise.hash(3, i, 0, 0) - 0.5) * 2.0e6
            assertEquals(Math.sin(x), DetMath.sin(x), 1e-9)
            assertEquals(Math.cos(x), DetMath.cos(x), 1e-9)
            val sc = DetMath.SinCos()
            DetMath.sinCos(x, sc)
            assertEquals(Math.sin(x), sc.sin, 1e-9)
            assertEquals(Math.cos(x), sc.cos, 1e-9)
            val y = (Noise.hash(3, i, 1, 0) - 0.8) * 40.0
            assertEquals(1.0, DetMath.exp(y) / Math.exp(y), 1e-13)
        }
    }

    @Test
    fun `the same place and time give the same sea, wherever it is worked out`() {
        val a = sea(WeatherIntensity.WILD)
        val b = sea(WeatherIntensity.WILD)
        val points = (0 until 60).map { randomDirection(it).mulInPlace(terra.radius) to 1_000.0 + 37.0 * it }
        val first = points.map { (p, t) -> a.height(p, t) }
        // The other in reverse order, so its caches fill differently.
        val second = points.reversed().map { (p, t) -> b.height(p, t) }.reversed()
        assertEquals(first, second)
    }

    // --- tides ---------------------------------------------------------------------

    /** Luna's direction over [at] in Terra's turning frame. */
    private fun lunaOver(time: Double): Vec3 {
        val p = luna.orbit!!.stateAt(time).position
        return terra.toBodyFixed(p, terra.rotationAt(time)).normalizeInPlace()
    }

    @Test
    fun `the tide is high under Luna and opposite, low between, twice a lunar day`() {
        val s = sea(null)
        val deep = seaWhere(1_500.0, 3_000.0)
        // Its height through two days, every two minutes.
        val heights = ArrayList<Double>()
        var t = 0.0
        while (t < 2 * 86_400.0) { heights.add(s.height(deep, t)); t += 120.0 }
        val highs = ArrayList<Double>()
        for (i in 1 until heights.size - 1) if (heights[i] > heights[i - 1] && heights[i] >= heights[i + 1]) highs.add(i * 120.0)
        val period = (highs.last() - highs.first()) / (highs.size - 1)
        assertEquals("semidiurnal: $highs", 12_750.0, period, 600.0)
        val range = heights.max() - heights.min()
        assertTrue("open ocean range $range m", range in 0.8..2.4)

        // At one moment: highest nearly under Luna (it trails a little), low at right angles.
        val time = 50_000.0
        val under = lunaOver(time - 1_000.0)
        val side = Vec3(-under.z, 0.0, under.x).normalizeInPlace()
        val tides = s.tides
        assertTrue(tides.height(under, time, -3_000.0) > tides.height(side, time, -3_000.0) + 1.5)
    }

    @Test
    fun `the tide piles up over the shallows`() {
        val s = sea(null)
        val shelf = seaWhere(3.0, 15.0)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        var t = 0.0
        while (t < 14_000.0) { val h = s.height(shelf, t); lo = minOf(lo, h); hi = maxOf(hi, h); t += 60.0 }
        assertTrue("shelf range ${hi - lo} m", hi - lo in 3.0..6.5)
    }

    // --- waves ---------------------------------------------------------------------

    @Test
    fun `waves grow over the open sea and stay small in the lee of land`() {
        val s = sea(WeatherIntensity.WILD)
        val weather = Weather(terra, WeatherConfig(intensity = WeatherIntensity.WILD))
        val sample = SeaSample()
        val open = ArrayList<Double>(); val lee = ArrayList<Double>()
        val time = 20_000.0
        for (i in 0 until 20_000) {
            val d = randomDirection(i)
            if (terrain.elevation(d) > -30.0) continue
            val wind = weather.boundaryWind(d, time, Vec3())
            wind.addScaledInPlace(d, -(wind dot d))
            val speed = wind.length
            if (speed !in 8.0..14.0) continue
            // How far upwind to the first land.
            val from = wind.mulInPlace(-1.0 / speed)
            var land = Double.MAX_VALUE
            for (k in 1..24) {
                val probe = d.copy().mulInPlace(terra.radius).addScaledInPlace(from, k * 10_000.0).normalizeInPlace()
                if (terrain.elevation(probe) > 0.0) { land = k * 10_000.0; break }
            }
            s.sample(d, time, sample)
            if (sample.stormHeight > 0.5) continue
            if (land <= 30_000.0) lee.add(sample.significantHeight) else if (land > 240_000.0) open.add(sample.significantHeight)
            if (open.size > 30 && lee.size > 30) break
        }
        assertTrue("found open ${open.size} and lee ${lee.size}", open.size > 5 && lee.size > 5)
        val o = open.average(); val l = lee.average()
        assertTrue("open sea $o m beats the lee $l m", o > 1.5 * l)
        assertTrue("a wind of 8-14 m/s over open sea: $o m", o in 1.0..5.0)
    }

    /** A grown storm over deep water, and when. */
    private fun stormOverSea(w: Weather): Pair<Storms.Storm, Double> {
        val e = Vec3(); val n = Vec3()
        val list = ArrayList<Storms.Storm>()
        for (i in 0 until 800) {
            val d = randomDirection(i)
            if (terrain.elevation(d) > -500.0) continue
            frame(d, e, n)
            for (t in listOf(2_000.0, 7_000.0, 12_000.0)) {
                list.clear()
                w.stormModel.around(d, e, n, 1, t, list)
                for (st in list) {
                    val when_ = st.start + 0.5 * Storms.CYCLE
                    if (st.strength < 0.8) continue
                    val c = w.stormModel.centreAt(st, when_, Vec3())
                    if (terrain.elevation(c) > -500.0) continue
                    return st to when_
                }
            }
        }
        throw AssertionError("no strong storm over the sea")
    }

    @Test
    fun `a strong storm raises a sea of ten metres, and throws swell far off later`() {
        val weather = Weather(terra, WeatherConfig(intensity = WeatherIntensity.WILD))
        val s = Sea(terra, luna, weather, WeatherConfig().seed)
        val (storm, t) = stormOverSea(weather)
        val centre = weather.stormModel.centreAt(storm, t, Vec3())
        val sample = s.sample(centre, t, SeaSample())
        assertTrue("storm sea ${sample.significantHeight} m under a ${storm.kind} of strength ${storm.strength}", sample.significantHeight in 7.0..16.0)
        // Its storm sea is wider than one tower: halfway out to the base's edge too.
        val track = Vec3(); val right = Vec3(); val c = Vec3()
        weather.stormModel.frameAt(storm, t, c, track, right)
        val out = weather.stormModel.place(c, track, right, storm.deckAlong, storm.halfAcross * 0.5, 1.0, Vec3())
        if (terrain.elevation(out) < -50.0) {
            assertTrue("out under the base too", s.sample(out, t, sample).significantHeight > 4.0)
        }

        // Swell: 150 km off across open water, arriving about 150 km / 9 m/s later.
        val stormSea = Storms.StormSea()
        val land = { p: Vec3 -> terrain.elevation(p) > 0.0 }
        var found = false
        for (a in 0 until 16) {
            val angle = a * Math.PI / 8
            val far = weather.stormModel.place(c, track, right, cos(angle) * 150_000.0, sin(angle) * 150_000.0, 1.0, Vec3())
            if (terrain.elevation(far) > -200.0) continue
            weather.stormModel.seaAt(far, t + 150_000.0 / Storms.SWELL_GROUP_SPEED, stormSea, land)
            if (stormSea.swellHs < 0.5) continue
            // Coming from this storm's way - where no bigger one elsewhere drowns it out.
            val toward = c.copy().subInPlace(far)
            toward.addScaledInPlace(far, -(toward dot far)).normalizeInPlace()
            if ((stormSea.swellDirection dot toward) > -0.7) continue
            found = true
            break
        }
        assertTrue("swell arrived somewhere 150 km off", found)
        assertTrue(storm.kind in StormKind.entries)
    }

    @Test
    fun `waves over the shallows stand no higher than the water allows, and break`() {
        val s = sea(WeatherIntensity.WILD)
        val sample = SeaSample()
        var broke = false
        var checked = 0
        for (i in 0 until 20_000) {
            val d = randomDirection(i)
            val bed = terrain.elevation(d)
            if (bed !in -6.0..-1.0) continue
            for (t in listOf(3_000.0, 9_000.0, 15_000.0)) {
                s.sample(d, t, sample)
                if (sample.depth <= 0.0) continue
                checked++
                assertTrue("${sample.significantHeight} m of sea in ${sample.depth} m of water", sample.significantHeight <= Sea.BREAKING * sample.depth + 1e-6)
                if (sample.breaking > 0.3) broke = true
            }
            if (checked > 60 && broke) break
        }
        assertTrue("checked $checked shallows", checked > 10)
        assertTrue("surf somewhere", broke)
    }

    @Test
    fun `ashore there is no sea`() {
        val s = sea(WeatherIntensity.WILD)
        val sample = SeaSample()
        for (i in 0 until 5_000) {
            val d = randomDirection(i)
            if (terrain.elevation(d) < 50.0) continue
            s.sample(d, 5_000.0, sample)
            assertTrue(sample.depth <= 0.0)
            assertEquals(sample.tide, sample.height, 0.0)
            return
        }
    }

    @Test
    fun `a sample is quick enough for every cell of every hull`() {
        val s = sea(WeatherIntensity.WILD)
        val d = seaWhere(500.0, 3_000.0)
        val p = d.copy().mulInPlace(terra.radius)
        repeat(2_000) { s.height(p, 100.0 + it * 0.016) } // warm
        val start = System.nanoTime()
        val n = 20_000
        var sink = 0.0
        repeat(n) { sink += s.height(p.addScaledInPlace(d, 0.001), 200.0 + it * 0.016) }
        val each = (System.nanoTime() - start) / 1e3 / n
        assertTrue("$each us a sample ($sink)", each < 40.0)
    }

    @Test
    fun `a craft's wave patch is the surface itself, across a hull`() {
        val s = sea(WeatherIntensity.WILD)
        val d = seaWhere(500.0, 3_000.0)
        val centre = d.copy().mulInPlace(terra.radius)
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(d).normalizeInPlace()
        val north = d.copy().crossInPlace(east).normalizeInPlace()
        for (t in listOf(1_000.0, 20_000.0, 55_555.5)) {
            val patch = s.patch(centre, t, WavePatch())
            var worst = 0.0
            for (i in -6..6) for (j in -6..6) {
                val p = centre.copy().addScaledInPlace(east, i * 1.2).addScaledInPlace(north, j * 1.2).addScaledInPlace(d, 1.5)
                worst = maxOf(worst, kotlin.math.abs(patch.height(p) - s.height(p, t)))
            }
            // Within a few millimetres; a rogue's edge crossing the hull, a couple of centimetres.
            assertTrue("patch off the surface by $worst m", worst < 0.03)
        }
    }
}
