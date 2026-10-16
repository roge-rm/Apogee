package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.weather.LiveHurricane
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
        val soon = warnings().all(at, arrives - 600.0).first { it.kind == WeatherWarnings.Kind.TSUNAMI && it.id == s.id }
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
