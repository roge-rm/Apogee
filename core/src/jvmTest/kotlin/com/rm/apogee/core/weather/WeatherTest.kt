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
 * The weather as a pure function, and whether the shapes in it are the ones a pilot would expect:
 * faster up high, faster on crests than in hollows, lift under cumulus, no thermals at sea, storms
 * only when the world allows them, and all of it the same wherever it's worked out.
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
    fun `the same place and time give the same air wherever it's worked out`() {
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
    fun `wind picks up with height, and there's none in space`() {
        val w = weather()
        val s = AirSample()
        // Averaged over lots of places, because any one of them can be a hollow or a thermal.
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
     * The terrain's hand in it. Over lots of places, crests are windier than hollows at the same
     * height above them.
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

        // Rain under its biggest tower's shaft, and a cumulonimbus in the tower.
        val s = AirSample()
        val model = w.stormModel
        val centre = Vec3(); val track = Vec3(); val side = Vec3()
        model.frameAt(storm, t, centre, track, side)
        val main = storm.mainCell
        val r = storm.cellRadius[main]
        val shaft = model.place(centre, track, side, storm.cellAlong[main] + storm.shaftAlong * r, storm.cellAcross[main], 1.0, Vec3())
        val ground = maxOf(terrain.elevation(shaft), 0.0)
        w.sample(shaft.copy().mulInPlace(radius + ground + 300.0), t, s)
        assertTrue("rain under the shaft: ${s.precipitation}", s.precipitation > 0.3)
        val tower = model.place(centre, track, side, storm.cellAlong[main], storm.cellAcross[main], radius + storm.base + 2_000.0, Vec3())
        w.sample(tower, t, s)
        assertEquals(CloudType.CUMULONIMBUS, s.cloudType)
        assertTrue("an updraught in the tower: ${s.lift}", s.lift > 3.0)

        // And the gust front: low down, somewhere around it, a wind to knock things over, whatever
        // the wind was before.
        var strongest = 0.0
        val reach = storm.reach
        for (i in -12..12) for (j in -12..12) {
            val at = model.place(centre, track, side, i * reach / 12.0, j * reach / 12.0, 1.0, Vec3())
            val g = maxOf(terrain.elevation(at), 0.0)
            w.sample(at.mulInPlace(radius + g + 100.0), t, s)
            strongest = maxOf(strongest, s.wind.length)
        }
        assertTrue("a storm's gust front blows hard: $strongest m/s", strongest > 20.0 * storm.strength)

        val strikes = ArrayList<Strike>()
        w.strikes(here, t - 300.0, t + 300.0, strikes)
        assertTrue("lightning from a mature storm", strikes.isNotEmpty())
        val again = ArrayList<Strike>()
        weather(WeatherIntensity.WILD).strikes(here, t - 300.0, t + 300.0, again)
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

    /** Cheap enough to sample once per craft per tick, after warming up its caches. */
    @Test
    fun `a sample is cheap`() {
        val w = weather()
        val s = AirSample()
        val start = above(randomDirection(9), 400.0)
        val east = Vec3(); val north = Vec3()
        frame(start.copy().normalizeInPlace(), east, north)
        // A craft flying along at 100 m/s: warm up, then time it.
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
     * A deck is cloudy where its puffs are drawn and clear between them, so climbing through a gap
     * in the drawn deck, there's no fog.
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
                // Only puffs well inside what was listed. Beside one at the edge, a "gap" can be
                // under a puff of a cell that was never listed.
                if (lobe.centre.copy().normalizeInPlace().distanceTo(dir) * w.body.radius > 2_500.0) continue
                // Its heart is cloud of its own kind.
                w.sample(lobe.centre, 3_000.0, s)
                assertTrue("no cloud at the heart of a drawn ${shape.type} puff", s.cloudDensity > 0.0)
                inside++
                // Well beyond its edge, at its height, between puffs, this deck isn't there.
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

    /** A fresh sky lists quickly, with nothing slow on a cold start. */
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
        // Find a storm anywhere, then look at it up close.
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

    /** Every storm a wild sky has over a stretch of the planet, at its best. */
    private fun manyStorms(w: Weather): List<Pair<Storms.Storm, Double>> {
        val found = LinkedHashMap<Long, Pair<Storms.Storm, Double>>()
        val e = Vec3(); val n = Vec3()
        val list = ArrayList<Storms.Storm>()
        for (i in 0 until 300) {
            val dir = randomDirection(i)
            frame(dir, e, n)
            for (t in listOf(1_000.0, 4_000.0, 9_000.0)) {
                list.clear()
                w.stormModel.around(dir, e, n, 1, t, list)
                for (st in list) {
                    val key = st.cx.toLong() * 1_000_003L + st.cy * 97L + st.cycle
                    // At its best means a third of the way through its life and more.
                    found.getOrPut(key) { st to (st.start + 0.45 * Storms.CYCLE) }
                }
            }
        }
        return found.values.toList()
    }

    @Test
    fun `storms come in every kind, and the big ones are tens of kilometres across`() {
        val all = manyStorms(weather(WeatherIntensity.WILD)).map { it.first }
        val kinds = all.groupingBy { it.kind }.eachCount()
        for (kind in StormKind.entries) assertTrue("no $kind among ${all.size}: $kinds", (kinds[kind] ?: 0) > 0)
        for (st in all) {
            val across = 2.0 * maxOf(st.halfAlong, st.halfAcross)
            when (st.kind) {
                StormKind.SINGLE -> assertTrue("a single cell's base $across m", across in 6_000.0..20_000.0)
                StormKind.MULTICELL -> assertTrue("a multicell's base $across m", across >= 14_000.0)
                StormKind.SUPERCELL -> assertTrue("a supercell's base $across m", across >= 12_000.0)
                StormKind.SQUALL -> assertTrue("a squall line $across m long", across >= 40_000.0)
            }
            assertTrue("${st.kind} has towers", st.cellCount >= 1)
        }
        // Squall lines run across their track, much longer than they are deep.
        for (st in all.filter { it.kind == StormKind.SQUALL }) assertTrue("${st.halfAcross} by ${st.halfAlong}", st.halfAcross > 1.4 * st.halfAlong)
    }

    @Test
    fun `under a storm's base the sky is covered right across it, not just in one spot`() {
        val w = weather(WeatherIntensity.WILD)
        val big = manyStorms(w).first { (st, _) -> st.kind == StormKind.SQUALL || st.kind == StormKind.SUPERCELL }
        val (st, t) = big
        val model = w.stormModel
        val centre = Vec3(); val track = Vec3(); val side = Vec3()
        model.frameAt(st, t, centre, track, side)
        val s = AirSample()
        // Well out towards the edge of the base, all round.
        for (k in 0 until 8) {
            val a = k * Math.PI / 4
            val along = st.deckAlong + cos(a) * st.halfAlong * 0.7
            val across = sin(a) * st.halfAcross * 0.7
            val up = model.place(centre, track, side, along, across, 1.0, Vec3())
            w.sample(up.copy().mulInPlace(radius + st.base + 300.0), t, s)
            assertEquals("cloud in the base at $along, $across of a ${st.kind}", CloudType.CUMULONIMBUS, s.cloudType)
            val sky = w.overcastAbove(up, 50.0, t)
            assertTrue("the sky overhead at $along, $across: $sky", sky > 0.6)
        }
    }

    @Test
    fun `a storm is wider than it is tall`() {
        val w = weather(WeatherIntensity.WILD)
        var wide = 0
        var total = 0
        for ((st, t) in manyStorms(w).take(40)) {
            val shapes = ArrayList<CloudShape>()
            val centre = w.stormModel.centreAt(st, t, Vec3())
            w.clouds(centre, 10_000.0, t, shapes, stormReach = 150_000.0)
            val shape = shapes.filter { it.type == CloudType.CUMULONIMBUS }.minByOrNull {
                it.lobes.first().centre.copy().normalizeInPlace().distanceTo(centre)
            } ?: continue
            var span = 0.0
            var lowest = Double.MAX_VALUE; var highest = 0.0
            for (a in shape.lobes) {
                lowest = minOf(lowest, a.centre.length - a.vertical)
                highest = maxOf(highest, a.centre.length + a.vertical)
                span = maxOf(span, a.centre.distanceTo(shape.lobes.first().centre) + a.horizontal)
            }
            total++
            if (span > highest - lowest) wide++
        }
        assertTrue("wider than tall: $wide of $total", total > 10 && wide >= total * 0.7)
    }

    @Test
    fun `a squall line is one wall of towers under a flat anvil, not a row of chimneys`() {
        val w = weather(WeatherIntensity.WILD)
        val lines = manyStorms(w).filter { it.first.kind == StormKind.SQUALL }.take(8)
        assertTrue("no squall lines found", lines.isNotEmpty())
        for ((st, t) in lines) {
            // Each tower reaches its neighbour along the line. A drawn tower is 0.95 of its radius.
            val order = (0 until st.cellCount).sortedBy { st.cellAcross[it] }
            for ((a, b) in order.zipWithNext()) {
                val apart = kotlin.math.hypot(st.cellAcross[b] - st.cellAcross[a], st.cellAlong[b] - st.cellAlong[a])
                val reach = 0.95 * (st.cellRadius[a] + st.cellRadius[b])
                assertTrue("towers $a and $b are $apart m apart and reach $reach m", apart <= reach + 300.0)
            }
            // At its best, an anvil sheet spreads over it, high up.
            val shapes = ArrayList<CloudShape>()
            val centre = w.stormModel.centreAt(st, t, Vec3())
            w.clouds(centre, 10_000.0, t, shapes, stormReach = 150_000.0)
            val line = shapes.filter { it.type == CloudType.CUMULONIMBUS }.maxByOrNull { it.lobes.size } ?: continue
            val high = st.base + 0.6 * (st.top - st.base)
            val anvil = line.lobes.count { it.flat && it.centre.length - radius > high }
            assertTrue("an anvil over the line: $anvil flat lobes up high", anvil >= 6)
        }
    }
}
