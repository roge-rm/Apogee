package com.rm.apogee.core.sea

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.Storms
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.weather.frame
import com.rm.apogee.core.world.LaunchSite
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Rogue waves: three sisters that build, run with the sea and die, as tall as the setting allows. */
class RogueWaveTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)

    private class Found(val sea: Sea, val rogues: List<Rogue>, val candidates: List<Rogue>)

    private fun sea(intensity: WeatherIntensity) =
        Sea(terra, system.body("luna"), Weather(terra, WeatherConfig(intensity = intensity)), WeatherConfig().seed)

    /** Rogues, and every cell's roll, round the middles of strong storms over deep sea. */
    private fun find(intensity: WeatherIntensity): Found = found.getOrPut(intensity) {
        val weather = Weather(terra, WeatherConfig(intensity = intensity))
        val sea = Sea(terra, system.body("luna"), weather, WeatherConfig().seed)
        val terrain = terra.terrain!!
        val e = Vec3(); val n = Vec3()
        val list = ArrayList<Storms.Storm>()
        val seen = HashSet<Pair<Double, Double>>()
        val rogues = ArrayList<Rogue>()
        val candidates = ArrayList<Rogue>()
        var storms = 0
        for (i in 0 until 3_000) {
            if (storms >= 25) break
            val lat = (Noise.hash(23, i, 0, 0) - 0.5) * 1.4
            val lon = Noise.hash(23, i, 1, 0) * 2 * Math.PI
            val d = Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon)).normalizeInPlace()
            if (terrain.elevation(d) > -300.0) continue
            frame(d, e, n)
            list.clear()
            weather.stormModel.around(d, e, n, 1, 20_000.0, list)
            val st = list.firstOrNull { it.strength >= 0.6 } ?: continue
            val t = st.start + 0.5 * Storms.CYCLE
            val c = weather.stormModel.centreAt(st, t, Vec3())
            if (terrain.elevation(c) > -300.0) continue
            storms++
            val slot = kotlin.math.floor(t / 600.0).toInt()
            val p = c.copy().mulInPlace(terra.radius)
            val cx = kotlin.math.floor(p.x / 6_000.0).toInt(); val cy = kotlin.math.floor(p.y / 6_000.0).toInt(); val cz = kotlin.math.floor(p.z / 6_000.0).toInt()
            for (s in slot - 2..slot + 2) for (a in -3..3) for (b in -3..3) for (cc in -3..3) {
                val r = sea.rogueIn(cx + a, cy + b, cz + cc, s, true) ?: continue
                candidates.add(r)
                if (r.exists && seen.add(r.focus.x to r.time)) rogues.add(r)
            }
        }
        Found(sea, rogues, candidates)
    }

    /** A point [x] m along a rogue's run from where it peaks, and [y] across, on the surface. */
    private fun at(r: Rogue, x: Double, y: Double = 0.0): Vec3 =
        r.focus.copy().addScaledInPlace(r.along, x).addScaledInPlace(r.across, y).normalizeInPlace().mulInPlace(terra.radius)

    private fun length(r: Rogue) = 2.0 * Math.PI / r.k

    /** The rogue's own surface along its run at [time], from two wavelengths behind its middle to two ahead. */
    private fun profile(sea: Sea, r: Rogue, time: Double): Pair<DoubleArray, DoubleArray> {
        val l = length(r)
        val middle = r.speed * (time - r.time)
        val xs = DoubleArray(401) { middle - 2.0 * l + it * l / 100.0 }
        return xs to DoubleArray(xs.size) { sea.rogueAt(at(r, xs[it]), time) }
    }

    /** Crest to trough of the middle wave and the hole in front, at its peak, in metres. */
    private fun peakHeight(sea: Sea, r: Rogue): Double {
        val l = length(r)
        val (xs, h) = profile(sea, r, r.time)
        val crest = h.indices.filter { abs(xs[it]) < 0.25 * l }.maxOf { h[it] }
        val trough = h.indices.filter { xs[it] > 0.2 * l && xs[it] < 0.8 * l }.minOf { h[it] }
        return crest - trough
    }

    /** The sea's significant height where [r] peaks, just before it comes, in metres. */
    private fun hs(sea: Sea, r: Rogue): Double = sea.sample(r.focus, r.time - 210.0, SeaSample()).significantHeight

    /** Whether [r] stands as tall as its sea makes it where it peaks: not cut by shelter, shallows or calm. */
    private fun full(sea: Sea, r: Rogue): Boolean {
        val expected = minOf(r.ratio * hs(sea, r), r.cap)
        return abs(peakHeight(sea, r) - expected) < 0.05 * expected
    }

    /** The tallest of [rogues] that stands at full height where it peaks. */
    private fun tallest(sea: Sea, rogues: List<Rogue>): Rogue =
        rogues.sortedByDescending { it.height }.first { full(sea, it) }

    @Test
    fun `rogues stand two to three times the sea round them, tallest on Extreme`() {
        val normal = find(WeatherIntensity.NORMAL)
        val wild = find(WeatherIntensity.WILD)
        val extreme = find(WeatherIntensity.EXTREME)
        assertTrue("no Normal open-sea rogues", normal.rogues.none { !it.storm })
        val tallest = HashMap<WeatherIntensity, Double>()
        for ((intensity, f) in listOf(WeatherIntensity.NORMAL to normal, WeatherIntensity.WILD to wild, WeatherIntensity.EXTREME to extreme)) {
            for (storm in listOf(true, false)) {
                val standing = f.rogues.filter { it.storm == storm }.sortedByDescending { it.height }.take(30).filter { full(f.sea, it) }
                if (intensity != WeatherIntensity.NORMAL || storm) assertTrue("$intensity storm $storm: ${standing.size} standing", standing.size >= 3)
                for (r in standing) {
                    val h = peakHeight(f.sea, r)
                    val sea = hs(f.sea, r)
                    println("$intensity ${if (storm) "storm" else "open"}: $h m in a ${"%.1f".format(sea)} m sea")
                    assertTrue("$h m in a $sea m sea", h <= r.cap + 0.5 && (h / sea in 2.15..2.85 || h > r.cap - 0.5))
                    assertTrue("$h m open-sea rogue", storm || h <= 25.5)
                }
                if (storm) tallest[intensity] = standing.maxOf { peakHeight(f.sea, it) }
            }
        }
        println("tallest $tallest")
        assertTrue("tallest $tallest", tallest[WeatherIntensity.EXTREME]!! > tallest[WeatherIntensity.WILD]!! && tallest[WeatherIntensity.EXTREME]!! in 30.0..38.5)
    }

    @Test
    fun `a rogue is the middle of three sisters with a hole in front`() {
        val f = find(WeatherIntensity.EXTREME)
        val r = tallest(f.sea, f.rogues.filter { it.storm })
        val l = length(r)
        val (xs, h) = profile(f.sea, r, r.time)
        fun top(from: Double, to: Double) = h.indices.filter { xs[it] > from * l && xs[it] < to * l }.maxOf { h[it] }
        fun bottom(from: Double, to: Double) = h.indices.filter { xs[it] > from * l && xs[it] < to * l }.minOf { h[it] }
        val middle = top(-0.25, 0.25)
        val before = top(0.6, 1.4)
        val after = top(-1.4, -0.6)
        assertTrue("sisters ${before / middle} and ${after / middle}", before / middle in 0.6..0.75 && after / middle in 0.6..0.75)
        assertTrue("the hole in front ${bottom(0.2, 0.8)} against behind ${bottom(-0.8, -0.2)}", bottom(0.2, 0.8) < 1.2 * bottom(-0.8, -0.2))
        // The group outlives the middle wave: two minutes on, the sisters have the middle's height.
        val (xs2, h2) = profile(f.sea, r, r.time + 125.0)
        val m2 = h2.indices.filter { abs(xs2[it] - r.speed * 125.0) < 0.25 * l }.maxOf { h2[it] }
        val s2 = h2.indices.filter { xs2[it] - r.speed * 125.0 in 0.6 * l..1.4 * l }.maxOf { h2[it] }
        assertTrue("after its peak, middle $m2 m and sister $s2 m", m2 > 2.0 && abs(m2 - s2) < 0.15 * m2)
    }

    @Test
    fun `a rogue runs with the sea, building and dying`() {
        val f = find(WeatherIntensity.EXTREME)
        val r = tallest(f.sea, f.rogues.filter { it.storm })
        val l = length(r)
        val crests = listOf(-60.0, 0.0, 60.0).map { dt ->
            val (xs, h) = profile(f.sea, r, r.time + dt)
            val i = h.indices.filter { abs(xs[it] - r.speed * dt) < 0.4 * l }.maxBy { h[it] }
            xs[i] to h[i]
        }
        for ((i, dt) in listOf(-60.0, 0.0, 60.0).withIndex()) {
            assertEquals("crest at $dt s", r.speed * dt, crests[i].first, 0.05 * l)
        }
        assertTrue("built from ${crests[0].second} to ${crests[1].second} m", crests[0].second > 0.5 * crests[1].second && crests[0].second < 0.85 * crests[1].second)
        assertTrue("died to ${crests[2].second} m", crests[2].second < 0.85 * crests[1].second)
    }

    @Test
    fun `a rogue's sea is smooth in time and space, across slots and cells`() {
        val f = find(WeatherIntensity.EXTREME)
        val r = tallest(f.sea, f.rogues.filter { it.storm })
        val sample = SeaSample()
        // At one point through the rogue's whole life, a quarter of a second at a time: the surface
        // never moves faster than it rises.
        val p = at(r, 0.0)
        var last = f.sea.sample(p, r.time - 220.0, sample).height
        var lastRise = sample.rise
        var t = r.time - 220.0
        while (t < r.time + 220.0) {
            t += 0.25
            val h = f.sea.sample(p, t, sample).height
            val fastest = maxOf(abs(lastRise), abs(sample.rise))
            assertTrue("a jump of ${h - last} m at $t", abs(h - last) < 0.25 * 1.5 * fastest + 0.1)
            last = h; lastRise = sample.rise
        }
        // Along its run at the peak, a metre at a time, rogue and all.
        var prior = f.sea.rogueAt(at(r, -3.0 * length(r)), r.time)
        var x = -3.0 * length(r)
        while (x < 3.0 * length(r)) {
            x += 1.0
            val h = f.sea.rogueAt(at(r, x), r.time)
            assertTrue("a step of ${h - prior} m at $x", abs(h - prior) < r.k * r.cap + 0.01)
            prior = h
        }
    }

    @Test
    fun `two seas agree on a rogue, worked out apart, with or without a base nearby`() {
        val f = find(WeatherIntensity.EXTREME)
        val r = tallest(f.sea, f.rogues.filter { it.storm })
        val points = (0 until 60).map { i -> at(r, (i - 30) * 37.0, (i % 7 - 3) * 150.0) to r.time + (i - 30) * 3.0 }
        fun surface(sea: Sea, order: List<Pair<Vec3, Double>>) = order.map { (p, t) -> sea.height(p, t) to sea.rogueAt(p, t) }
        Sea.forgetRogues()
        val first = surface(sea(WeatherIntensity.EXTREME), points)
        Sea.forgetRogues()
        val second = surface(sea(WeatherIntensity.EXTREME), points.reversed()).reversed()
        assertEquals(first, second)
        assertTrue(first.any { abs(it.second) > 5.0 })

        // A sea with a base where the rogue peaks works its rogues out first; one without, after,
        // gets the same as when it works them out itself.
        Sea.forgetRogues()
        val based = sea(WeatherIntensity.EXTREME).also { it.calmBases = listOf(r.focus.normalized() to Sea.CALM_BASE) }
        val basedSurface = surface(based, points)
        assertEquals(first, surface(sea(WeatherIntensity.EXTREME), points))
        // The base calms it round itself, and nowhere else.
        assertTrue("by the base ${based.rogueAt(r.focus, r.time)} m", abs(based.rogueAt(r.focus, r.time)) < 0.01)
        assertTrue(basedSurface.zip(first).any { (a, b) -> abs(a.second) < abs(b.second) - 5.0 })
        val away = at(r, 4_000.0)
        val later = r.time + 4_000.0 / r.speed
        assertEquals(sea(WeatherIntensity.EXTREME).rogueAt(away, later), based.rogueAt(away, later), 1e-9)
    }

    @Test
    fun `crossing seas and an opposing current raise the odds`() {
        val candidates = find(WeatherIntensity.EXTREME).candidates
        fun rate(list: List<Rogue>) = list.count { it.exists }.toDouble() / list.size
        val crossed = candidates.filter { it.cross > 0.5 }
        val plain = candidates.filter { it.cross < 0.1 && it.against < 0.1 }
        assertTrue("${crossed.size} crossed, ${plain.size} plain", crossed.size > 100 && plain.size > 100)
        assertTrue("crossed ${rate(crossed)}, plain ${rate(plain)}", rate(crossed) > 2.0 * rate(plain))
        val opposed = candidates.filter { it.cross < 0.1 && it.against > 0.5 }
        if (opposed.size > 100) assertTrue("opposed ${rate(opposed)}, plain ${rate(plain)}", rate(opposed) > 1.5 * rate(plain))
    }

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun world(time: Double): World = World.default(catalog).also {
        it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.EXTREME)
        it.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = time, nextVesselId = 1L))
    }

    /** Where an Extreme rogue peaks, as a site, and the rogue. */
    private fun meeting(): Pair<LaunchSite, Rogue> {
        val f = find(WeatherIntensity.EXTREME)
        val r = tallest(f.sea, f.rogues.filter { it.storm })
        val at = r.focus.normalized()
        return LaunchSite("rogue", "Rogue", "terra", SolarSystem.latitudeOf(at), SolarSystem.longitudeOf(at)) to r
    }

    @Test
    fun `a small boat meets an Extreme rogue and is thrown up it or buried`() {
        val (site, r) = meeting()
        val world = world(r.time - 90.0)
        val boat = world.spawnOnSurface(StockCraft.minnow(catalog), site)
        var low = Double.MAX_VALUE; var high = -Double.MAX_VALUE
        var flooded = false
        repeat((150.0 / dt).toInt()) {
            world.step(dt)
            val radius = boat.body.position.length
            low = minOf(low, radius); high = maxOf(high, radius)
            if (boat.submerged || boat.flooded.any { it > 0.0 }) flooded = true
        }
        println("Minnow rose ${high - low} m, flooded $flooded")
        assertTrue("it rose ${high - low} m and wasn't flooded", high - low > 20.0 || flooded)
    }

    @Test
    fun `a ship rides an Extreme rogue`() {
        val (site, r) = meeting()
        val world = world(r.time - 90.0)
        val ship = world.spawnOnSurface(StockCraft.coaster(catalog), site)
        val parts = ship.defs.size
        var low = Double.MAX_VALUE; var high = -Double.MAX_VALUE
        repeat((150.0 / dt).toInt()) {
            world.step(dt)
            val radius = ship.body.position.length
            low = minOf(low, radius); high = maxOf(high, radius)
        }
        println("Coaster rose ${high - low} m")
        assertTrue("it lost parts", ship.defs.size == parts && ship.broken.none { it })
        assertTrue("it rose ${high - low} m", high - low > 15.0)
        assertTrue("it ended ${ship.body.position.length - terra.radius} m from the datum", abs(ship.body.position.length - terra.radius) < 20.0)
    }

    private companion object {
        val found = HashMap<WeatherIntensity, Found>()
    }
}
