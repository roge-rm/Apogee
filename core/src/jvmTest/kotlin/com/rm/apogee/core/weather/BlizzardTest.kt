package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blizzards: none on Calm or Normal and a tenth as many on Wild, snow only over cold or snowy
 * ground, storm blizzards moving along with whiteout and strong wind inside, ground blizzards low
 * over snow with clear air above, no steps at their edges, and the same air however it's asked.
 */
class BlizzardTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val rubra = system.body("rubra")
    private val extreme = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun direction(latDeg: Double, lonDeg: Double) = SolarSystem.surfaceDirection(Math.toRadians(latDeg), Math.toRadians(lonDeg))

    private fun bits(a: Double) = java.lang.Double.doubleToLongBits(a)

    private fun over(body: CelestialBody, d: Vec3, agl: Double): Vec3 =
        Vec3().setTo(d).mulInPlace(body.radius + maxOf(body.terrain!!.elevation(d), 0.0) + agl)

    private fun step(body: CelestialBody, d: Vec3, heading: Vec3, along: Double, across: Double): Vec3 {
        val right = Vec3().setTo(heading).crossInPlace(d).normalizeInPlace()
        return Vec3().setTo(d).mulInPlace(body.radius).addScaledInPlace(heading, along).addScaledInPlace(right, across).normalizeInPlace()
    }

    private fun live(w: Weather, time: Double): List<LiveBlizzard> =
        ArrayList<LiveBlizzard>().also { w.blizzards(Vec3.unitY(), time, Double.MAX_VALUE, it) }

    /** Blizzards on Terra on Extreme, each once, a third of the way through its life. */
    private val blizzards: List<Pair<Double, LiveBlizzard>> by lazy {
        val w = Weather(terra, extreme)
        val seen = HashSet<Long>()
        val out = ArrayList<Pair<Double, LiveBlizzard>>()
        var t = 6 * 3_600.0
        while (t < 60 * 3_600.0) {
            for (b in live(w, t)) {
                if (b.age < 0.3 * b.life || b.age > 0.6 * b.life || !seen.add(b.id)) continue
                out += t to b
            }
            t += 1_800.0
        }
        out
    }

    @Test
    fun `none on Calm or Normal, a tenth as many on Wild`() {
        for (intensity in listOf(WeatherIntensity.CALM, WeatherIntensity.NORMAL)) {
            for (body in listOf(terra, rubra)) {
                val w = Weather(body, WeatherConfig(intensity = intensity))
                assertTrue(!w.snowPossible)
                val air = AirSample()
                for (k in 0 until 200) {
                    val t = k * 1_700.0
                    assertTrue(live(w, t).isEmpty())
                    for (d in listOf(direction(-80.0, 0.0), direction(75.0, -40.0), direction(85.0, 0.0))) {
                        w.sample(over(body, d, 5.0), t, air)
                        assertEquals(0.0, air.snow, 0.0)
                    }
                }
            }
        }
        fun count(intensity: WeatherIntensity): Int {
            val w = Weather(terra, WeatherConfig(intensity = intensity))
            val seen = HashSet<Long>()
            var t = 0.0
            while (t < 200 * 3_600.0) { for (b in live(w, t)) seen += b.id; t += 1_800.0 }
            return seen.size
        }
        val e = count(WeatherIntensity.EXTREME); val wild = count(WeatherIntensity.WILD)
        println("blizzards in 200 h: Extreme $e, Wild $wild")
        assertTrue(e > 200)
        assertTrue("Wild about a tenth: $wild of $e", wild > 0.04 * e && wild < 0.2 * e)
    }

    @Test
    fun `cold and snowy ground`() {
        val w = Weather(terra, extreme)
        val m = w.blizzardModel
        for ((lat, lon) in listOf(-80.0 to 0.0, 75.0 to -40.0, 85.0 to 0.0, 30.0 to 85.0)) {
            val d = direction(lat, lon)
            assertEquals("cold $lat, $lon", 1.0, m.coldness(d), 1e-9)
            assertTrue("snow $lat, $lon", w.snowCover(d) > 0.9)
        }
        for ((lat, lon) in listOf(23.0 to 10.0, 0.0 to -30.0, -5.0 to -62.0, 45.0 to 10.0)) {
            val d = direction(lat, lon)
            assertEquals("warm $lat, $lon", 0.0, m.coldness(d), 0.0)
            assertEquals("no snow $lat, $lon", 0.0, w.snowCover(d), 0.0)
        }
        // Tundra: cold, but nothing lying for the wind to lift.
        assertTrue(m.coldness(direction(66.0, 100.0)) > 0.9)
        assertEquals(0.0, w.snowCover(direction(66.0, 100.0)), 0.0)
    }

    @Test
    fun `snow only over cold or snowy ground`() {
        val w = Weather(terra, extreme)
        val m = w.blizzardModel
        assertTrue("blizzards: ${blizzards.size}", blizzards.size > 30)
        val air = AirSample()
        var snowy = 0; var warm = 0
        for ((t, b) in blizzards.take(40)) {
            for (a in -5..5) for (c in -4..4) {
                val d = step(terra, b.centre, b.heading, a * b.halfLength / 4.0, c * b.halfWidth / 3.0)
                w.sample(over(terra, d, 20.0), t, air)
                val cold = m.coldAround(d); val cover = w.snowCover(d)
                if (air.snow > 0.0) {
                    snowy++
                    assertTrue("snow over warm ground", cold > 0.0 || cover > 0.0)
                    assertTrue("falling snow over warm ground", air.snowfall <= Blizzards.FALL_DENSITY * cold + 1e-12)
                } else if (cold == 0.0) warm++
            }
        }
        println("blizzard points: $snowy snowy, $warm over warm ground or sea")
        assertTrue(snowy > 500)
    }

    @Test
    fun `a blizzard moves along with whiteout and strong wind inside`() {
        val w = Weather(terra, extreme)
        val plain = Weather(terra, extreme).also { it.blizzardModel.enabled = false }
        val a = AirSample(); val b = AirSample()
        var checked = 0; var whiteout = 0; var windy = 0
        var leastSight = Double.MAX_VALUE; var mostWind = 0.0
        for ((t, z) in blizzards) {
            // Where its middle is a little later.
            val later = live(w, t + 600.0).firstOrNull { it.id == z.id } ?: continue
            assertEquals("moves at its speed", z.speed * 600.0, later.centre.distanceTo(z.centre) * terra.radius, 0.05 * z.speed * 600.0 + 50.0)
            val d = z.centre
            if (w.blizzardModel.coldAround(d) < 0.9 || w.snowCover(d) < 0.9) continue
            checked++
            w.sample(over(terra, d, 5.0), t, a); plain.sample(over(terra, d, 5.0), t, b)
            leastSight = minOf(leastSight, a.visibility)
            if (z.strength > 0.6 && a.visibility < 200.0) whiteout++
            mostWind = maxOf(mostWind, a.wind.length)
            if ((a.wind dot Vec3().setTo(b.wind).normalizeInPlace()) > 0.6 * Blizzards.WIND * z.strength) windy++
            // Nothing far above it.
            w.sample(over(terra, d, Blizzards.TOP + 100.0), t, a)
            assertEquals(0.0, a.snow, 0.0)
        }
        println("$checked blizzards over snow: $whiteout whiteouts, $windy windy, least sight $leastSight m, most wind $mostWind m/s")
        assertTrue(checked >= 5)
        assertTrue(whiteout >= checked / 3)
        assertTrue(windy >= checked / 2)
        assertTrue(leastSight in 50.0..120.0)
    }

    @Test
    fun `ground blizzards lie low over snow with clear air above`() {
        val w = Weather(terra, extreme)
        val air = AirSample()
        val rnd = java.util.Random(4)
        var n = 0; var drifting = 0; var thick = 0
        repeat(4_000) {
            val lat = if (rnd.nextBoolean()) -70.0 - 18 * rnd.nextDouble() else 72.0 + 15 * rnd.nextDouble()
            val d = direction(lat, 360 * rnd.nextDouble())
            if (w.snowCover(d) < 0.5) return@repeat
            val t = rnd.nextDouble() * 400 * 3_600.0
            // Away from storm blizzards.
            if (live(w, t).any { it.centre.distanceTo(d) * terra.radius < it.reach + 10_000.0 }) return@repeat
            w.sample(over(terra, d, 3.0), t, air)
            n++
            if (air.snow <= 0.02) return@repeat
            drifting++
            if (air.snow > 0.25) thick++
            assertTrue(air.snowTop in Blizzards.DRIFT_DRAWN * Blizzards.GROUND_LOWEST..Blizzards.DRIFT_DRAWN * Blizzards.GROUND_TOP)
            assertEquals(0.0, air.snowfall, 0.0)
            w.sample(over(terra, d, Blizzards.GROUND_TOP + 50.0), t, air)
            assertEquals("clear above", 0.0, air.snow, 0.0)
        }
        println("polar snow: $n points, ${drifting * 100 / n}% drifting, ${thick * 100 / n}% thick")
        assertTrue(drifting > n / 20)
        assertTrue(thick > 0)
    }

    @Test
    fun `wind and rough air have no steps at its edge`() {
        val w = Weather(terra, extreme)
        val plain = Weather(terra, extreme).also { it.blizzardModel.enabled = false }
        val a = AirSample(); val b = AirSample()
        // The wind with and without blizzards, and the rough air and snow they add.
        class Here(val with: Vec3, val without: Vec3, val rough: Double, val snow: Double)
        fun here(d: Vec3, t: Double): Here {
            w.sample(over(terra, d, 20.0), t, a); plain.sample(over(terra, d, 20.0), t, b)
            return Here(a.wind.copy(), b.wind.copy(), a.turbulence - b.turbulence, a.snow)
        }
        var walked = 0
        var most = 0.0; var mostRough = 0.0; var mostSnow = 0.0
        for ((t, z) in blizzards.take(12)) {
            // Out through its front and its side edges.
            for ((dir, from) in listOf(z.heading to 0.6 * z.halfLength, Vec3().setTo(z.heading).crossInPlace(z.centre).normalizeInPlace() to 0.4 * z.halfWidth)) {
                val to = if (dir === z.heading) 1.05 * z.halfLength else 1.05 * z.halfWidth
                var x = from
                var before = here(step(terra, z.centre, dir, x, 0.0), t)
                while (x < to) {
                    x += 5.0
                    val now = here(step(terra, z.centre, dir, x, 0.0), t)
                    // Its wind tops up what's there, so it steps only where the wind under it does.
                    most = maxOf(most, now.with.distanceTo(before.with) - now.without.distanceTo(before.without))
                    mostRough = maxOf(mostRough, kotlin.math.abs(now.rough - before.rough))
                    mostSnow = maxOf(mostSnow, kotlin.math.abs(now.snow - before.snow))
                    before = now
                }
            }
            walked++
        }
        println("walked $walked blizzards: biggest change per 5 m past the wind's own ${most} m/s, rough air $mostRough, snow $mostSnow")
        assertTrue(walked >= 10)
        assertTrue("wind step $most", most < 0.3)
        assertTrue("rough air step $mostRough", mostRough < 0.01)
    }

    @Test
    fun `rough air has no steps where lying snow ends in a ground blizzard`() {
        val w = Weather(terra, extreme)
        val plain = Weather(terra, extreme).also { it.blizzardModel.enabled = false }
        val a = AirSample(); val b = AirSample()
        val rnd = java.util.Random(9)
        var edges = 0
        var mostRough = 0.0
        var tries = 0
        while (edges < 6 && tries < 20_000) {
            tries++
            val lat = if (rnd.nextBoolean()) -60.0 - 25 * rnd.nextDouble() else 62.0 + 25 * rnd.nextDouble()
            val d = direction(lat, 360 * rnd.nextDouble())
            if (w.snowCover(d) < 0.5) continue
            val t = rnd.nextDouble() * 400 * 3_600.0
            if (live(w, t).any { it.centre.distanceTo(d) * terra.radius < it.reach + 10_000.0 }) continue
            w.sample(over(terra, d, 20.0), t, a)
            if (a.snow < 0.05) continue
            // Out each of four ways to where the snow ends, then across that edge in 5 m steps.
            for (k in 0 until 4) {
                val way = Vec3(d.z, 0.0, -d.x).normalizeInPlace()
                val north = Vec3().setTo(d).crossInPlace(way)
                val ang = k * Math.PI / 2
                val dir = way.mulInPlace(kotlin.math.cos(ang)).addScaledInPlace(north, kotlin.math.sin(ang))
                val end = (1..100).map { it * 500.0 }.firstOrNull { w.snowCover(step(terra, d, dir, it, 0.0)) <= 0.0 } ?: continue
                fun rough(x: Double): Double {
                    val p = over(terra, step(terra, d, dir, x, 0.0), 20.0)
                    w.sample(p, t, a); plain.sample(p, t, b)
                    return a.turbulence - b.turbulence
                }
                var x = end - 1_000.0
                var before = rough(x)
                while (x < end + 200.0) {
                    x += 5.0
                    val now = rough(x)
                    mostRough = maxOf(mostRough, kotlin.math.abs(now - before))
                    before = now
                }
                edges++
            }
        }
        println("crossed $edges lying-snow edges in ground blizzards: biggest rough air step per 5 m $mostRough")
        assertTrue("found edges: $edges", edges >= 4)
        assertTrue("rough air step $mostRough", mostRough < 0.01)
    }

    @Test
    fun `the same air however it's asked`() {
        val stepped = Weather(terra, extreme)
        val time = blizzards[10].first
        val spots = buildList {
            for (z in live(stepped, time)) for (a in -2..1) for (c in -1..1) add(step(terra, z.centre, z.heading, a * z.halfLength / 2.0, c * z.halfWidth / 2.0))
            val rnd = java.util.Random(3)
            repeat(30) { add(Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalizeInPlace()) }
        }
        val air = AirSample()
        var t = time - 3 * 3_600.0
        while (t < time) { for (p in spots) stepped.sample(over(terra, p, 30.0), t, air); t += 700.0 }
        val expected = spots.map { p -> AirSample().also { stepped.sample(over(terra, p, 30.0), time, it) } }
        val fresh = Weather(terra, extreme)
        for (k in spots.indices.reversed()) {
            val got = fresh.sample(over(terra, spots[k], 30.0), time, AirSample())
            val e = expected[k]
            assertEquals("$k snow", bits(e.snow), bits(got.snow))
            assertEquals("$k snowfall", bits(e.snowfall), bits(got.snowfall))
            assertEquals("$k sight", bits(e.visibility), bits(got.visibility))
            assertEquals("$k wind", listOf(bits(e.wind.x), bits(e.wind.y), bits(e.wind.z)), listOf(bits(got.wind.x), bits(got.wind.y), bits(got.wind.z)))
            assertEquals("$k turbulence", bits(e.turbulence), bits(got.turbulence))
        }
        println("${spots.size} points agree, ${expected.count { it.snow > 0.0 }} with snow")
        assertTrue(expected.count { it.snow > 0.0 } > 5)
    }

    @Test
    fun `craft far apart sampled in turn don't relist every sample`() {
        val w = Weather(terra, extreme)
        val model = w.blizzardModel
        val places = listOf(direction(-80.0, 0.0), direction(75.0, -40.0), direction(66.0, 100.0), direction(-75.0, 120.0), direction(70.0, -100.0))
            .map { over(terra, it, 300.0) }
        val air = AirSample()
        val start = 50_000.0
        var t = start
        val n0 = System.nanoTime()
        repeat(4_000) {
            for (p in places) w.sample(p, t, air)
            t += 0.05
        }
        val us = (System.nanoTime() - n0) / 1e3 / (4_000 * places.size)
        val slots = (kotlin.math.floor(t / Blizzards.SLOT) - kotlin.math.floor(start / Blizzards.SLOT) + 1).toLong()
        println("${places.size} places, ${4_000 * places.size} samples: ${model.relisted} lists made, $us us a sample")
        assertTrue(model.relisted <= places.size * slots)
    }

    @Test
    fun `a coming wind over snow is seen before it lifts the snow, a few looks a check`() {
        val w = Weather(terra, extreme)
        val air = AirSample()
        val rnd = java.util.Random(11)
        var found = 0
        var worst = 0
        var quietSeen = false
        repeat(300) {
            if (found >= 5) return@repeat
            val lat = if (rnd.nextBoolean()) -70.0 - 18 * rnd.nextDouble() else 72.0 + 15 * rnd.nextDouble()
            val d = direction(lat, 360 * rnd.nextDouble())
            if (w.snowCover(d) < 0.5) return@repeat
            val from = rnd.nextDouble() * 400 * 3_600.0
            // The first minute in the next two hours with snow drifting and no storm blizzard near.
            var t = from
            while (t < from + 7_200.0) {
                val storm = live(w, t).any { it.centre.distanceTo(d) * terra.radius < it.reach + 20_000.0 }
                w.sample(over(terra, d, 3.0), t, air)
                if (!storm && air.snow > 0.02) break
                t += 60.0
            }
            if (t >= from + 7_200.0 || t - from < 1_200.0) return@repeat
            found++
            val stormNear = (0..20).any { k -> live(w, t - 1_000.0 + 50.0 * k).any { it.centre.distanceTo(d) * terra.radius < it.reach + 20_000.0 } }
            if (stormNear) { found--; return@repeat }
            // Well before the wind arrives nothing is due; 900 s before, with the wind inside the look, it is.
            // No drift in the minutes before it, so a short look from there has nothing to find.
            if (generateSequence(from) { it + 60.0 }.takeWhile { it <= t - 300.0 }.any { !w.blizzardDue(d, it, 200.0) }) quietSeen = true
            val before = w.windLooks
            assertTrue("seen coming at ${t - 900.0}", w.blizzardDue(d, t - 900.0, 1_000.0))
            worst = maxOf(worst, w.windLooks - before)
        }
        println("$found places, at most $worst free-wind looks a check")
        assertTrue(found > 0)
        assertTrue("something due at every quiet minute: an always-true check", quietSeen)
        assertTrue("looks a check: $worst", worst <= (1_000.0 / Weather.WIND_LOOK_STEP).toInt() + 2)
        // Off snow nothing is looked at.
        val before = w.windLooks
        val bare = direction(0.0, 20.0)
        assertEquals(0.0, w.snowCover(bare), 0.0)
        w.blizzardDue(bare, 5_000.0, 1_000.0)
        assertEquals(before, w.windLooks)
    }

    @Test
    fun `a steady wind just under the threshold over snow is not due`() {
        val w = Weather(terra, extreme)
        val rnd = java.util.Random(5)
        var found = 0
        repeat(20_000) {
            if (found >= 3) return@repeat
            val lat = if (rnd.nextBoolean()) -70.0 - 18 * rnd.nextDouble() else 72.0 + 15 * rnd.nextDouble()
            val d = direction(lat, 360 * rnd.nextDouble())
            if (w.snowCover(d) < 0.5) return@repeat
            val t = rnd.nextDouble() * 400 * 3_600.0
            // Every look over the next 1000 s is between 17 and the threshold.
            val winds = (0..7).map { w.groundWindLook(d, t + it * Weather.WIND_LOOK_STEP) }
            if (winds.any { it < 17.0 || it >= Blizzards.GROUND_FROM }) return@repeat
            // No storm blizzard anywhere near over the window, so only the wind is in question.
            if ((-4..24).any { k -> live(w, t + 50.0 * k).any { b -> b.centre.distanceTo(d) * terra.radius < b.reach + 2_000_000.0 } }) return@repeat
            found++
            assertTrue("not due at $t with winds $winds", !w.blizzardDue(d, t, 1_000.0))
        }
        println("$found steady sub-threshold places")
        assertTrue(found > 0)
    }
}
