package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dust storms: none on Calm or Normal, dust only over dry ground, haboobs rolling out ahead of
 * their storms with a gust front, world-wide storms on Rubra, weaker near launch sites, and the
 * same air however it's asked.
 */
class DustStormTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val rubra = system.body("rubra")
    private val extreme = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun direction(latDeg: Double, lonDeg: Double) = SolarSystem.surfaceDirection(Math.toRadians(latDeg), Math.toRadians(lonDeg))

    private fun bits(a: Double) = java.lang.Double.doubleToLongBits(a)

    /** A point [agl] metres over the ground (or sea) at unit [d]. */
    private fun over(body: CelestialBody, d: Vec3, agl: Double): Vec3 =
        Vec3().setTo(d).mulInPlace(body.radius + maxOf(body.terrain!!.elevation(d), 0.0) + agl)

    /** [d] moved [along] metres along [heading] (unit, tangent) and [across] metres to its right. */
    private fun step(body: CelestialBody, d: Vec3, heading: Vec3, along: Double, across: Double): Vec3 {
        val right = Vec3().setTo(heading).crossInPlace(d).normalizeInPlace()
        return Vec3().setTo(d).mulInPlace(body.radius).addScaledInPlace(heading, along).addScaledInPlace(right, across).normalizeInPlace()
    }

    private fun live(w: Weather, time: Double): List<LiveDust> =
        ArrayList<LiveDust>().also { w.dustStorms(Vec3.unitY(), time, Double.MAX_VALUE, it) }

    /** Haboobs on Terra on Extreme over [hours], each once, part way through its life. */
    private val haboobs: List<Pair<Double, LiveDust>> by lazy {
        val w = Weather(terra, extreme)
        val seen = HashSet<Long>()
        val out = ArrayList<Pair<Double, LiveDust>>()
        var t = 6 * 3_600.0
        while (t < 60 * 3_600.0) {
            for (d in live(w, t)) {
                if (d.kind != DustKind.HABOOB || d.age < 900.0 || d.age > 0.6 * d.life || !seen.add(d.id)) continue
                out += t to d
            }
            t += 900.0
        }
        out
    }

    @Test
    fun `none on Calm or Normal`() {
        for (intensity in listOf(WeatherIntensity.CALM, WeatherIntensity.NORMAL)) {
            for (body in listOf(terra, rubra)) {
                val w = Weather(body, WeatherConfig(intensity = intensity))
                assertTrue(!w.dustModel.active)
                val air = AirSample()
                for (k in 0 until 300) {
                    val t = k * 1_100.0
                    assertTrue(live(w, t).isEmpty())
                    for (d in listOf(direction(23.0, 10.0), direction(32.0, -113.0), direction(-25.0, 134.0))) {
                        w.sample(over(body, d, 20.0), t, air)
                        assertEquals(0.0, air.dust, 0.0)
                    }
                }
            }
        }
    }

    @Test
    fun `dust only over dry ground`() {
        val w = Weather(terra, extreme)
        for ((lat, lon) in listOf(23.0 to 10.0, 22.0 to 48.0, -25.0 to 134.0, 32.0 to -113.0, 43.0 to 105.0)) {
            assertTrue("desert $lat, $lon", w.dustiness(direction(lat, lon)) > 0.8)
        }
        for ((lat, lon) in listOf(-5.0 to -62.0, 0.0 to 22.0, 0.0 to -30.0, -80.0 to 0.0, 75.0 to -40.0, 50.0 to 10.0)) {
            assertEquals("wet, sea or ice $lat, $lon", 0.0, w.dustiness(direction(lat, lon)), 0.0)
        }
        assertTrue("haboobs: ${haboobs.size}", haboobs.size > 30)
        val air = AirSample()
        var dusty = 0; var wet = 0; var sea = 0
        for ((t, h) in haboobs.take(40)) {
            for (a in -6..2) for (c in -4..4) {
                val d = step(terra, h.centre, h.heading, a * 2_500.0, c * h.halfWidth / 4.0)
                w.sample(over(terra, d, 30.0), t, air)
                val ground = w.dustiness(d)
                if (air.dust > 0.0) {
                    dusty++
                    assertTrue("dust over ground giving none at $a, $c", ground > 0.0)
                    assertTrue("dust over sea", terra.terrain!!.elevation(d) > 0.0)
                    assertTrue("thicker than the ground gives", air.dust <= ground + 1e-12)
                } else if (ground == 0.0) {
                    wet++
                    if (terra.terrain!!.elevation(d) < 0.0) sea++
                }
            }
        }
        println("haboob points: $dusty dusty, $wet over wet ground or sea (sea $sea)")
        assertTrue(dusty > 500)
    }

    @Test
    fun `a haboob rolls out ahead of its storm with a gust front`() {
        val w = Weather(terra, extreme)
        val model = w.dustModel
        val storms = w.stormModel
        // Each listed haboob from its storm: starting under it, heading along its track.
        val cells = SphereCells(terra.radius, Storms.CELL)
        var checked = 0
        for (key in 0L until 6L * cells.perFace * cells.perFace) {
            for (c in 0L until 40L) {
                val b = model.haboob(key, c) ?: continue
                val s = storms.stormIn(key, c)
                assertTrue(s.exists)
                val centre = Vec3(); val track = Vec3(); val right = Vec3()
                storms.frameAt(s, b.start, centre, track, right)
                assertTrue("starts under its storm: ${b.origin.distanceTo(centre) * terra.radius}", b.origin.distanceTo(centre) * terra.radius < 30_000.0)
                assertTrue("heads along its track", (b.forward dot track) > kotlin.math.cos(0.5 * DustStorms.TURN + 0.06))
                assertTrue(b.start >= s.start + DustStorms.START_FROM * Storms.CYCLE - 1e-6)
                checked++
            }
        }
        println("haboobs checked against their storms: $checked")
        assertTrue(checked > 20)

        val plain = Weather(terra, extreme).also { it.dustModel.enabled = false }
        val air = AirSample(); val calm = AirSample()
        var fronts = 0
        for ((t, h) in haboobs) {
            if (h.strength < 0.5 || w.dustiness(h.centre) < 0.9) continue
            // On its own.
            val others = live(w, t).filter { it.id != h.id && it.kind == DustKind.HABOOB }
            if (others.any { it.centre.distanceTo(h.centre) * terra.radius < it.reach + h.reach + 10_000.0 }) continue
            val ahead = step(terra, h.centre, h.heading, 4_000.0, 0.0)
            val behind = step(terra, h.centre, h.heading, -3_000.0, 0.0)
            if (w.dustiness(ahead) < 0.9 || w.dustiness(behind) < 0.9) continue
            // It moves along its heading at its speed.
            val later = live(w, t + 60.0).first { it.id == h.id }
            val moved = Vec3().setTo(later.centre).subInPlace(h.centre).mulInPlace(terra.radius)
            assertEquals("speed", h.speed, moved.length / 60.0, 0.03 * h.speed)
            assertTrue("along its heading", (moved dot h.heading) > 0.99 * moved.length)
            assertTrue("10-25 m/s at first, slower later: ${h.speed}", h.speed in 3.0..25.0)
            // Clear ahead, thick behind, and the gust front at the face.
            // Ahead there may only be a windy day's dust.
            w.sample(over(terra, ahead, 20.0), t, air)
            val clear = air.dust
            assertTrue("ahead: $clear", clear <= DustStorms.BROAD_DENSITY + 1e-9)
            w.sample(over(terra, behind, 20.0), t, air)
            assertTrue("behind: ${air.dust}", air.dust > clear + 0.4 * h.strength)
            assertTrue("sight behind: ${air.visibility}", air.visibility < 1_000.0)
            val face = step(terra, h.centre, h.heading, -400.0, 0.0)
            w.sample(over(terra, face, 20.0), t, air)
            plain.sample(over(terra, face, 20.0), t, calm)
            val gust = (Vec3().setTo(air.wind).subInPlace(calm.wind) dot h.heading)
            assertTrue("gust front $gust at strength ${h.strength}", gust > 15.0 * h.strength)
            assertTrue("rough air", air.turbulence > calm.turbulence || air.turbulence == 1.0)
            assertTrue("lifts dust", air.lift > calm.lift)
            // Its wall stands 1-2 km tall and no more.
            w.sample(over(terra, behind, 2_700.0), t, air)
            assertEquals("above the wall", 0.0, air.dust, 0.0)
            fronts++
        }
        println("gust fronts checked: $fronts")
        assertTrue(fronts >= 5)
    }

    /** A time on Rubra on Extreme halfway through a world storm. */
    private fun worldStormTime(w: Weather): Double {
        var t = 0.0
        while (t < 400 * 86_400.0) {
            val s = live(w, t).firstOrNull { it.kind == DustKind.WORLD }
            if (s != null && s.age > 0.45 * s.life && s.age < 0.6 * s.life) return t
            t += 3 * 3_600.0
        }
        error("no world storm in 400 days")
    }

    @Test
    fun `world-wide dust storms on Rubra`() {
        val w = Weather(rubra, extreme)
        assertTrue(w.dustModel.worldStorms)
        assertTrue(!Weather(terra, extreme).dustModel.worldStorms)
        val t = worldStormTime(w)
        val storm = live(w, t).first { it.kind == DustKind.WORLD }
        assertTrue("lasts days: ${storm.life / 86_400.0}", storm.life >= 1.5 * 86_400.0)
        val air = AirSample()
        var dry = 0; var thick = 0; var gentle = 0
        val rnd = java.util.Random(7)
        repeat(300) {
            val d = Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalizeInPlace()
            if (w.dustiness(d) < 0.8) return@repeat
            dry++
            w.sample(over(rubra, d, 20.0), t, air)
            if (air.dust > 0.3) thick++
            if (air.wind.length < 40.0) gentle++
        }
        println("Rubra world storm at ${t / 86_400.0} days: $thick of $dry dry points thick, $gentle gentle")
        assertTrue(dry > 100)
        assertTrue(thick > 0.8 * dry)
        assertTrue(gentle > 0.9 * dry)
        // Far rarer on Wild.
        fun hours(intensity: WeatherIntensity): Int {
            val x = Weather(rubra, WeatherConfig(intensity = intensity))
            return (0 until 1_500).count { k -> live(x, k * 6 * 3_600.0).any { it.kind == DustKind.WORLD } }
        }
        val e = hours(WeatherIntensity.EXTREME); val wild = hours(WeatherIntensity.WILD)
        println("world storm share of the time: Extreme ${e / 15.0}%, Wild ${wild / 15.0}%")
        assertTrue(wild * 4 < e)
    }

    @Test
    fun `weaker near launch sites`() {
        val w = Weather(rubra, extreme)
        val t = worldStormTime(w)
        val free = Weather(rubra, extreme).also { it.dustModel.shySites = false }
        val site = World.launchSites.first { it.id == "rubra-rift" }
        val at = SolarSystem.surfaceDirection(site.latitude, site.longitude)
        val a = AirSample(); val b = AirSample()
        w.sample(over(rubra, at, 20.0), t, a)
        free.sample(over(rubra, at, 20.0), t, b)
        println("Rubra Rift: dust ${a.dust} vs ${b.dust} without the site")
        assertTrue(b.dust > 0.2)
        assertEquals(1.0 - DustStorms.SITE_SHY, a.dust / b.dust, 0.02)
        val far = step(rubra, at, Vec3(at.z, 0.0, -at.x).normalizeInPlace(), 60_000.0, 0.0)
        w.sample(over(rubra, far, 20.0), t, a)
        free.sample(over(rubra, far, 20.0), t, b)
        assertEquals(bits(b.dust), bits(a.dust))
    }

    @Test
    fun `the same air however it's asked`() {
        fun points(body: CelestialBody, w: Weather, time: Double): List<Vec3> = buildList {
            for (h in live(w, time)) {
                if (h.kind == DustKind.WORLD) continue
                for (a in -2..1) for (c in -1..1) add(step(body, h.centre, h.heading, a * 1_500.0, c * h.halfWidth / 2.0))
            }
            val rnd = java.util.Random(3)
            repeat(30) { add(Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalizeInPlace()) }
        }
        for (body in listOf(terra, rubra)) {
            val stepped = Weather(body, extreme)
            val time = if (body === rubra) worldStormTime(stepped) else haboobs[10].first
            // One asked along the way and then at the time; one fresh, points in reverse.
            val spots = points(body, stepped, time)
            val air = AirSample()
            var t = time - 3 * 3_600.0
            while (t < time) { for (p in spots) stepped.sample(over(body, p, 50.0), t, air); t += 700.0 }
            val expected = spots.map { p -> AirSample().also { stepped.sample(over(body, p, 50.0), time, it) } }
            val fresh = Weather(body, extreme)
            for (k in spots.indices.reversed()) {
                val got = fresh.sample(over(body, spots[k], 50.0), time, AirSample())
                val e = expected[k]
                assertEquals("${body.id} $k dust", bits(e.dust), bits(got.dust))
                assertEquals("${body.id} $k sight", bits(e.visibility), bits(got.visibility))
                assertEquals("${body.id} $k wind", listOf(bits(e.wind.x), bits(e.wind.y), bits(e.wind.z)), listOf(bits(got.wind.x), bits(got.wind.y), bits(got.wind.z)))
                assertEquals("${body.id} $k lift", bits(e.lift), bits(got.lift))
                assertEquals("${body.id} $k turbulence", bits(e.turbulence), bits(got.turbulence))
            }
            println("${body.id}: ${spots.size} points agree")
            assertTrue("${body.id} has dust", expected.count { it.dust > 0.0 } > 5)
        }
    }

    @Test
    fun `craft far apart sampled in turn don't relist every sample`() {
        val w = Weather(terra, extreme)
        val model = w.dustModel
        val places = listOf(direction(23.0, 10.0), direction(22.0, 48.0), direction(-25.0, 134.0), direction(32.0, -113.0), direction(0.0, 22.0))
            .map { over(terra, it, 300.0) }
        val air = AirSample()
        val start = 50_000.0
        var t = start
        repeat(4_000) {
            for (p in places) w.sample(p, t, air)
            t += 0.05
        }
        val slots = (kotlin.math.floor(t / DustStorms.SLOT) - kotlin.math.floor(start / DustStorms.SLOT) + 1).toLong()
        println("${places.size} places, ${4_000 * places.size} samples over ${t - start} s: ${model.relisted} lists made")
        assertTrue(model.relisted <= places.size * slots)
    }

    @Test
    fun `haboob wind and rough air have no steps at its back edge or ahead of its face`() {
        val w = Weather(terra, extreme)
        val plain = Weather(terra, extreme).also { it.dustModel.enabled = false }
        val a = AirSample(); val b = AirSample()
        fun extra(d: Vec3, t: Double): Pair<Vec3, Double> {
            w.sample(over(terra, d, 20.0), t, a); plain.sample(over(terra, d, 20.0), t, b)
            return Vec3().setTo(a.wind).subInPlace(b.wind) to (a.turbulence - b.turbulence)
        }
        var walked = 0
        var most = 0.0; var mostRough = 0.0
        for ((t, h) in haboobs.take(12)) {
            // Out from where it began through its back edge, and on through the air ahead of its face.
            val out = Vec3().setTo(h.centre).subInPlace(h.origin)
            out.addScaledInPlace(h.origin, -(out dot h.origin)).normalizeInPlace()
            val windows = listOf(
                (-2_600.0..-1_400.0) to { x: Double -> step(terra, h.origin, out, x, 0.0) },
                (800.0..2_200.0) to { x: Double -> step(terra, h.centre, h.heading, x, 0.0) },
            )
            for ((range, at) in windows) {
                var x = range.start
                var before = extra(at(x), t)
                while (x < range.endInclusive) {
                    x += 5.0
                    val now = extra(at(x), t)
                    most = maxOf(most, now.first.distanceTo(before.first))
                    mostRough = maxOf(mostRough, kotlin.math.abs(now.second - before.second))
                    before = now
                }
            }
            walked++
        }
        println("walked $walked haboobs: biggest change per 5 m ${most} m/s, rough air ${mostRough}")
        assertTrue(walked >= 10)
        assertTrue("wind step $most", most < 0.3)
        assertTrue("rough air step $mostRough", mostRough < 0.01)
    }

    @Test
    fun `no dust on ground that gives none beside a desert`() {
        val w = Weather(terra, extreme)
        val model = w.dustModel
        val air = AirSample()
        val t = haboobs.first().first
        var edge = 0; var dusty = 0
        // South out of the Sahara into the Sahel and forest, and west to the sea.
        for (start in listOf(18.0 to 10.0, 20.0 to -14.0)) {
            for (k in 0 until 6_000) {
                val d = if (start.second == 10.0) direction(start.first - k * 0.002, 10.0) else direction(20.0, start.second - k * 0.002)
                val elevation = terra.terrain!!.elevation(d)
                val blended = model.blend(d, elevation)
                if (blended <= 0.0) continue
                if (model.ownDryness(d) > 0.0) { dusty++; continue }
                edge++
                assertEquals("its own ground gives none", 0.0, w.dustiness(d), 0.0)
                if (edge % 20 == 0) {
                    w.sample(over(terra, d, 20.0), t, air)
                    assertEquals(0.0, air.dust, 0.0)
                }
            }
        }
        println("desert edge: $edge points giving none beside $dusty dusty ones")
        assertTrue(edge > 20)
    }
}
