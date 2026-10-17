package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.weather.DustKind
import com.rm.apogee.core.weather.LiveBlizzard
import com.rm.apogee.core.weather.LiveDust
import com.rm.apogee.core.weather.LiveHurricane
import com.rm.apogee.core.weather.LiveQuake
import com.rm.apogee.core.weather.LiveTornado
import com.rm.apogee.core.weather.LiveTsunami
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Weather warnings: a tornado, hurricane or tsunami near or coming gets one, with its distance or
 * time; nothing far off, high up or on a calm setting; and a second instance, as on a replica,
 * gives the same.
 */
class WeatherWarningsTest {
    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val radius = terra.radius
    private val weather = Weather(terra, config)

    private fun warnings() = WeatherWarnings(terra, config, null)

    /** Body-fixed, [distance] metres east of unit [centre], [above] over the datum or the ground there. */
    private fun beside(centre: Vec3, distance: Double, above: Double): Vec3 {
        val east = Vec3(centre.z, 0.0, -centre.x).normalizeInPlace()
        val d = Vec3().setTo(centre).addScaledInPlace(east, distance / radius).normalizeInPlace()
        return d.mulInPlace(radius + maxOf(terra.terrain!!.elevation(d), 0.0) + above)
    }

    /** A strong tornado in the middle of its life, and when. */
    private val tornado: Pair<Double, LiveTornado> by lazy {
        var t = 6 * 3_600.0
        while (true) {
            val out = ArrayList<LiveTornado>()
            weather.tornadoes(Vec3.unitY(), t, Double.MAX_VALUE, out)
            out.firstOrNull { it.strength > 0.3 && it.age / it.life in 0.3..0.6 }?.let { return@lazy t to it }
            t += 300.0
        }
        @Suppress("UNREACHABLE_CODE") error("")
    }

    private val hurricane: Pair<Double, LiveHurricane> by lazy {
        var t = 6 * 3_600.0
        while (true) {
            val out = ArrayList<LiveHurricane>()
            weather.hurricanes(Vec3.unitY(), t, Double.MAX_VALUE, out)
            out.firstOrNull { it.wet > 0.5 }?.let { return@lazy t to it }
            t += 1_800.0
        }
        @Suppress("UNREACHABLE_CODE") error("")
    }

    @Test
    fun `the words are short`() {
        assertEquals("800 m", WeatherWarnings.distanceText(812.0))
        assertEquals("3.2 km", WeatherWarnings.distanceText(3_160.0))
        assertEquals("120 km", WeatherWarnings.distanceText(119_700.0))
        assertEquals("4 min", WeatherWarnings.minutes(200.0))
        assertEquals("40 s", WeatherWarnings.minutes(38.0))
    }

    @Test
    fun `a tornado a few km off gets a warning with its distance, none far off or high up`() {
        val (time, t) = tornado
        val near = warnings().all(beside(t.centre, 3_000.0, 20.0), time).firstOrNull { it.id == t.id }
        assertNotNull("warned at 3 km", near)
        near!!
        assertTrue("its distance: ${near.distance}", near.distance in 2_900.0..3_100.0)
        val word = if (t.overWater) "WATERSPOUT" else "TORNADO"
        assertEquals("$word 3.0 km", near.text)
        assertEquals(t.reach + WeatherWarnings.TORNADO_DANGER > 3_000.0, near.danger)
        val inside = warnings().all(beside(t.centre, t.coreRadius, 20.0), time).first { it.id == t.id }
        assertTrue("a danger inside its reach", inside.danger)
        val far = warnings().all(beside(t.centre, t.reach + WeatherWarnings.TORNADO_WARN + 5_000.0, 20.0), time)
        assertTrue("none far off: ${far.map { it.text }}", far.none { it.id == t.id })
        val high = warnings().find(beside(t.centre, 3_000.0, WeatherWarnings.TOP + 1_000.0), time)
        assertNull("none high up", high)
        println("tornado at ${time / 3_600.0} h: ${near.text}, inside ${inside.text}")
    }

    @Test
    fun `a hurricane warns from past its edge and is a danger near the eyewall`() {
        val (time, h) = hurricane
        val edge = warnings().all(beside(h.centre, h.outerRadius + 50_000.0, 20.0), time).firstOrNull { it.id == h.id }
        assertNotNull("warned past its edge", edge)
        assertFalse("not yet a danger", edge!!.danger)
        assertTrue(edge.text, edge.text.startsWith("HURRICANE ") && edge.text.endsWith(" km"))
        val wall = warnings().all(beside(h.centre, h.maxWindRadius, 20.0), time).first { it.id == h.id }
        assertTrue("a danger at the eyewall", wall.danger)
        val far = warnings().all(beside(h.centre, h.outerRadius + WeatherWarnings.HURRICANE_WARN + 100_000.0, 20.0), time)
        assertTrue("none far off", far.none { it.id == h.id })
        println("hurricane at ${time / 3_600.0} h: ${edge.text}, eyewall ${wall.text}")
    }

    @Test
    fun `a tsunami warns minutes ahead at a place it reaches, then while it passes`() {
        var found: Triple<Double, LiveTsunami, Vec3>? = null
        var t = 6 * 3_600.0
        search@ while (t < 200 * 3_600.0) {
            val live = ArrayList<LiveTsunami>()
            weather.tsunamis(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (s in live) {
                // A sea point well ahead of its front, on any bearing that has one.
                for (k in 0 until 16) {
                    val east = Vec3(s.origin.z, 0.0, -s.origin.x).normalizeInPlace()
                    val north = Vec3().setTo(s.origin).crossInPlace(east)
                    val a = 2.0 * Math.PI * k / 16
                    val angle = (s.frontRadius + 300_000.0) / radius
                    val d = Vec3().setTo(s.origin).addScaledInPlace(east, kotlin.math.cos(a) * angle).addScaledInPlace(north, kotlin.math.sin(a) * angle).normalizeInPlace()
                    if (terra.terrain!!.elevation(d) > -50.0) continue
                    val here = ArrayList<LiveTsunami>()
                    weather.tsunamis(d, t, Double.MAX_VALUE, here)
                    val arrives = here.first { it.id == s.id }.arrivesHere
                    if (arrives.isFinite() && arrives - t > 900.0) { found = Triple(arrives, s, d); break@search }
                }
            }
            t += 1_800.0
        }
        val (arrives, s, d) = found ?: error("no tsunami ahead of a sea point")
        val at = Vec3().setTo(d).mulInPlace(radius)
        // Just under 10 min, so rounding in the arrival can't make it 11.
        val soon = warnings().all(at, arrives - 590.0).first { it.kind == WeatherWarnings.Kind.TSUNAMI && it.id == s.id }
        assertEquals("TSUNAMI IN 10 min", soon.text)
        assertFalse(soon.danger)
        val close = warnings().all(at, arrives - 120.0).first { it.id == s.id }
        assertTrue("a danger 2 min out", close.danger)
        val passing = warnings().all(at, arrives + 30.0).first { it.id == s.id }
        assertEquals("TSUNAMI", passing.text)
        val early = warnings().all(at, arrives - WeatherWarnings.TSUNAMI_LEAD - 300.0)
        assertTrue("none long before", early.none { it.id == s.id })
        val high = warnings().all(Vec3().setTo(d).mulInPlace(radius + WeatherWarnings.TSUNAMI_TOP + 500.0), arrives - 600.0)
        assertTrue("none for a plane over it", high.none { it.id == s.id })
    }

    @Test
    fun `a quake warns as its waves come, is a danger while it shakes, then warns of aftershocks`() {
        // A strong main shock on land with an aftershock to come, and a place on land near it where
        // that aftershock will be felt.
        var found: Triple<LiveQuake, Vec3, LiveQuake>? = null
        var t = 6 * 3_600.0
        search@ while (t < 600 * 3_600.0) {
            val live = ArrayList<LiveQuake>()
            weather.quakes(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (q in live) {
                if (q.id != q.mainId || q.underSea || q.magnitude < 7.0) continue
                val e = Vec3(q.epicentre.z, 0.0, -q.epicentre.x).normalizeInPlace()
                val n = Vec3().setTo(q.epicentre).crossInPlace(e)
                for (k in 0 until 16) for (r in listOf(15_000.0, 10_000.0, 6_000.0)) {
                    val a = 2.0 * Math.PI * k / 16
                    val d = Vec3().setTo(q.epicentre).addScaledInPlace(e, kotlin.math.cos(a) * r / radius)
                        .addScaledInPlace(n, kotlin.math.sin(a) * r / radius).normalizeInPlace()
                    if (terra.terrain!!.elevation(d) < 5.0) continue
                    val here = ArrayList<LiveQuake>()
                    weather.quakes(d, q.start, 0.0, here)
                    val main = here.firstOrNull { it.id == q.id } ?: continue
                    if (main.peakHere < 1.0 || main.arrivesHere - q.start < 2.5) continue
                    val after = weather.nextAftershock(d, main.start + 600.0, WeatherWarnings.QUAKE_WARN) ?: continue
                    if (after.mainId != q.id) continue
                    found = Triple(main, d, after)
                    break@search
                }
            }
            t += 1_800.0
        }
        val (q, d, after) = found ?: error("no strong quake on land with an aftershock to come")
        println("quake M%.1f at %.1f h, %.1f km off, peak here %.2f, aftershock M%.1f %.0f s later"
            .format(q.magnitude, q.start / 3_600.0, q.epicentre.distanceTo(d) * radius / 1_000.0, q.peakHere, after.magnitude, after.start - q.start))
        val ground = radius + terra.terrain!!.elevation(d)
        val at = Vec3().setTo(d).mulInPlace(ground + 2.0)
        fun quakeWarning(time: Double) = warnings().all(at, time).firstOrNull { it.kind == WeatherWarnings.Kind.QUAKE || it.kind == WeatherWarnings.Kind.AFTERSHOCKS }

        assertNull("none before it strikes", quakeWarning(q.start - 5.0))
        val coming = quakeWarning(q.start + 0.5)
        assertNotNull("warned as the waves come", coming)
        assertEquals(WeatherWarnings.Kind.QUAKE, coming!!.kind)
        assertTrue(coming.text, coming.text.startsWith("QUAKE IN ") && coming.text.endsWith(" s"))
        assertFalse(coming.danger)
        // In the strong part of its shaking here.
        var shaking: WeatherWarnings.Warning? = null
        var s = q.arrivesHere + 1.0
        while (s < q.arrivesHere + 30.0 && shaking?.danger != true) { shaking = quakeWarning(s); s += 0.5 }
        assertNotNull(shaking)
        assertTrue("a danger while it shakes", shaking!!.danger)
        assertEquals("QUAKE", shaking.text)
        val later = quakeWarning(after.start - 5.0)
        assertNotNull("aftershocks to come", later)
        assertEquals("AFTERSHOCKS", later!!.text)
        assertFalse(later.danger)
        assertEquals("one event, so one sound", coming.event, later.event)
        assertTrue("apart from other kinds' ids", coming.event != coming.id)
        // None far off or high up.
        val far = Vec3().setTo(q.epicentre).addScaledInPlace(Vec3(q.epicentre.z, 0.0, -q.epicentre.x).normalizeInPlace(), (q.reach + 60_000.0) / radius).normalizeInPlace()
        assertTrue(warnings().all(far.mulInPlace(radius + maxOf(terra.terrain!!.elevation(far), 0.0) + 2.0), s).none { it.kind == WeatherWarnings.Kind.QUAKE })
        val high = Vec3().setTo(d).mulInPlace(ground + WeatherWarnings.QUAKE_TOP + 500.0)
        assertTrue(warnings().all(high, s).none { it.kind == WeatherWarnings.Kind.QUAKE || it.kind == WeatherWarnings.Kind.AFTERSHOCKS })
    }

    @Test
    fun `a blizzard warns as it comes and is a danger inside its snow`() {
        fun along(c: Vec3, heading: Vec3, metres: Double) = Vec3().setTo(c).addScaledInPlace(heading, metres / radius).normalizeInPlace()
        var found: Pair<Double, LiveBlizzard>? = null
        var t = 6 * 3_600.0
        search@ while (t < 200 * 3_600.0) {
            val live = ArrayList<LiveBlizzard>()
            weather.blizzards(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (z in live) {
                if (z.strength < 0.6 || z.age < 1_800.0 || z.age > 0.6 * z.life) continue
                if (weather.blizzardCold(z.centre) < 0.95) continue
                found = t to z
                break@search
            }
            t += 1_800.0
        }
        val (time, z) = found ?: error("no blizzard over cold country")
        fun at(metres: Double): Vec3 {
            val p = along(z.centre, z.heading, metres)
            return p.mulInPlace(radius + maxOf(terra.terrain!!.elevation(p), 0.0) + 5.0)
        }
        fun blizzard(p: Vec3) = warnings().all(p, time).firstOrNull { it.kind == WeatherWarnings.Kind.BLIZZARD && it.id == z.id }
        val coming = blizzard(at(z.halfLength + 12_000.0))
        assertNotNull("warned ahead of it", coming)
        assertFalse(coming!!.danger)
        assertTrue("with how far: ${coming.text}", coming.text.startsWith("BLIZZARD ") && coming.text.endsWith(" km"))
        val inside = blizzard(at(0.0))
        assertNotNull("inside", inside)
        assertTrue("a danger inside", inside!!.danger)
        assertEquals("BLIZZARD", inside.text)
        assertEquals("one event", coming.event, inside.event)
        assertNull("none far ahead", blizzard(at(z.halfLength + 80_000.0)))
        val high = Vec3().setTo(at(0.0)).normalizeInPlace().mulInPlace(at(0.0).length + WeatherWarnings.BLIZZARD_TOP + 500.0)
        assertNull("none high up", blizzard(high))
        assertTrue("none on Normal", WeatherWarnings(terra, WeatherConfig(intensity = WeatherIntensity.NORMAL), null).all(at(0.0), time)
            .none { it.kind == WeatherWarnings.Kind.BLIZZARD })
    }

    @Test
    fun `a haboob warns while its front comes, is a danger inside its dust, and a world storm says so`() {
        // A strong haboob over desert, part way through its life, with desert ahead and behind.
        fun along(c: Vec3, heading: Vec3, metres: Double) = Vec3().setTo(c).addScaledInPlace(heading, metres / radius).normalizeInPlace()
        var found: Pair<Double, LiveDust>? = null
        var t = 6 * 3_600.0
        search@ while (t < 200 * 3_600.0) {
            val live = ArrayList<LiveDust>()
            weather.dustStorms(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (d in live) {
                if (d.kind != DustKind.HABOOB || d.strength < 0.6 || d.age < 900.0 || d.age > 0.6 * d.life) continue
                if (listOf(-3_000.0, 0.0, 12_000.0).any { weather.dustiness(along(d.centre, d.heading, it)) < 0.9 }) continue
                found = t to d
                break@search
            }
            t += 1_800.0
        }
        val (time, d) = found ?: error("no haboob over desert")
        fun at(metres: Double): Vec3 {
            val p = along(d.centre, d.heading, metres)
            return p.mulInPlace(radius + maxOf(terra.terrain!!.elevation(p), 0.0) + 5.0)
        }
        fun dust(p: Vec3) = warnings().all(p, time).firstOrNull { it.kind == WeatherWarnings.Kind.DUST && it.id == d.id }
        val coming = dust(at(12_000.0))
        assertNotNull("warned ahead of its front", coming)
        assertFalse(coming!!.danger)
        assertEquals("DUST 12 km", coming.text)
        val inside = dust(at(-3_000.0))
        assertNotNull("inside", inside)
        assertTrue("a danger inside", inside!!.danger)
        assertEquals("DUST", inside.text)
        assertEquals("one event", coming.event, inside.event)
        assertNull("none far ahead", dust(at(60_000.0)))
        val high = Vec3().setTo(at(12_000.0)).normalizeInPlace().mulInPlace(at(12_000.0).length + WeatherWarnings.DUST_TOP + 500.0)
        assertNull("none high up", dust(high))

        // A world storm on Rubra over a lander there.
        val rubra = SolarSystem.defaultSystem().body("rubra")
        val red = Weather(rubra, config)
        var w = 0.0
        while (w < 400 * 86_400.0) {
            val live = ArrayList<LiveDust>()
            red.dustStorms(Vec3.unitY(), w, Double.MAX_VALUE, live)
            val s = live.firstOrNull { it.kind == DustKind.WORLD }
            if (s != null && s.age > 0.45 * s.life && s.age < 0.6 * s.life) break
            w += 3 * 3_600.0
        }
        val storm = (0 until 64).asSequence().map { k ->
            val p = SolarSystem.surfaceDirection(Math.toRadians(-30.0 + k % 8 * 8.0), Math.toRadians(k * 47.0))
            p.mulInPlace(rubra.radius + maxOf(rubra.terrain!!.elevation(p), 0.0) + 5.0)
        }.mapNotNull { p -> WeatherWarnings(rubra, config, null).all(p, w).firstOrNull { it.kind == WeatherWarnings.Kind.DUST_STORM } }.firstOrNull()
        assertNotNull("a world storm warned of on Rubra", storm)
        assertEquals("DUST STORM", storm!!.text)
        assertFalse("a caution, not a danger, since it lasts days", storm.danger)
    }

    @Test
    fun `a second instance gives the same, and calm weather none`() {
        val (time, t) = tornado
        val at = beside(t.centre, 3_000.0, 20.0)
        val a = warnings().all(at, time)
        // The other from empty shared caches, as a replica in another process would be.
        Weather.forgetShared(terra)
        val b = WeatherWarnings(SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID), config, null).all(at, time)
        assertEquals(a.map { it.text to it.danger }, b.map { it.text to it.danger })
        assertEquals(a.map { it.distance.toRawBits() }, b.map { it.distance.toRawBits() })
        val calm = WeatherWarnings(terra, WeatherConfig(intensity = WeatherIntensity.NORMAL), null)
        assertTrue(calm.all(at, time).isEmpty())
        // Update with no worker works it out there and then.
        val w = warnings()
        w.update(at, time)
        assertEquals(warnings().find(at, time), w.current)
    }
}
