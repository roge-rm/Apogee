package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** A hurricane's lobes: a clear eye, a dense wall, and slots that hold as it moves and strengthens. */
class HurricaneLookTest {
    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
    private val radius = terra.radius
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun all(weather: Weather, time: Double): List<LiveHurricane> =
        ArrayList<LiveHurricane>().also { weather.hurricanes(Vec3(1.0, 0.0, 0.0), time, 1e9, it) }

    /** A strong one over the sea, and when, that's still going 20 minutes on. */
    private val strong: Pair<Double, LiveHurricane> by lazy {
        val w = Weather(terra, config)
        var best: Pair<Double, LiveHurricane>? = null
        var t = 0.0
        while (t < 200 * 3_600.0) {
            for (h in all(w, t)) {
                if (terra.terrain!!.elevation(h.centre) > -300.0) continue
                if (all(w, t + 1_200.0).none { it.id == h.id }) continue
                if (best == null || h.strength > best.second.strength) best = t to h
            }
            t += 900.0
        }
        best ?: error("no hurricane")
    }

    private fun shapeOf(weather: Weather, h: LiveHurricane, camera: Vec3, time: Double, detail: Double = 0.45, reach: Double = 70_000.0): CloudShape? {
        val shapes = ArrayList<CloudShape>()
        weather.clouds(camera, 25_000.0, time, shapes, reach, detail)
        return shapes.firstOrNull { it.hurricane && it.key == (com.rm.apogee.core.terrain.Noise.hashInt(config.seed * 31 + 0x5EA7 + 995, h.id.toInt(), (h.id ushr 32).toInt(), 0).toLong() and 0xffffffffL) }
    }

    private fun groundDistance(a: Vec3, b: Vec3) = Vec3().setTo(a).normalizeInPlace().distanceTo(Vec3().setTo(b).normalizeInPlace()) * radius

    @Test
    fun `the eye is clear and the eyewall a dense ring`() {
        val (time, h) = strong
        println("strength %.2f, eyewall %.0f km, outer %.0f km".format(h.strength, h.maxWindRadius / 1e3, h.outerRadius / 1e3))
        val shape = shapeOf(Weather(terra, config), h, h.centre, time)
        assertNotNull(shape)
        val rm = h.maxWindRadius
        for (lobe in shape!!.lobes) {
            val r = groundDistance(lobe.centre, h.centre)
            assertTrue("a lobe reaches into the eye: ${r - lobe.horizontal} m from the middle", r - lobe.horizontal > 0.45 * rm)
        }
        val wall = shape.lobes.filter { it.visualSlot in HurricaneShapes.EYEWALL_SLOT until HurricaneShapes.SCUD_SLOT }
        println("lobes ${shape.lobes.size}, eyewall ${wall.size}, curtains ${shape.rain.size}")
        assertTrue("eyewall has lobes all round: ${wall.size}", wall.size >= 16)
        // All the way round, at the wall's radius, the lowest billows cover it.
        val p = Vec3()
        for (k in 0 until 72) {
            val b = 2.0 * Math.PI * k / 72
            h.pointAt(1.15 * rm, cos(b), sin(b), radius, p)
            assertTrue("a gap in the eyewall at bearing $k", wall.any { groundDistance(it.centre, p) < it.horizontal })
        }
        // And thick everywhere round it, thin in the eye.
        assertTrue(h.cloudAt(0.1 * rm, 1.0, 0.0, radius) < 0.01)
        for (k in 0 until 12) assertTrue(h.cloudAt(1.15 * rm, cos(k * 0.5), sin(k * 0.5), radius) > 0.9)
    }

    @Test
    fun `lobes keep their slots and move with it as it goes and strengthens`() {
        val (time, h0) = strong
        val weather = Weather(terra, config)
        val later = time + 600.0
        val h1 = all(weather, later).single { it.id == h0.id }
        println("moved %.0f m, strength %.3f to %.3f".format(groundDistance(h0.centre, h1.centre), h0.strength, h1.strength))
        // A camera a few eyewall radii out, staying put. Lobes far out shear a little as east and
        // north turn under the moving centre, as the air does.
        val camera = h0.pointAt(3.0 * h0.maxWindRadius, 0.6, 0.8, radius, Vec3())
        val a = shapeOf(weather, h0, camera, time)!!
        val b = shapeOf(Weather(terra, config), h1, camera, later)!!
        assertEquals(a.key, b.key)
        val before = a.lobes.associateBy { it.visualSlot }
        val after = b.lobes.associateBy { it.visualSlot }
        assertEquals("slots are unique", a.lobes.size, before.size)
        val shared = before.keys.intersect(after.keys)
        println("lobes ${before.size} then ${after.size}, shared ${shared.size}")
        assertTrue("most lobes kept: ${shared.size} of ${before.size}", shared.size > 0.85 * minOf(before.size, after.size))
        // Those gone are at the edge of the reach, where they've faded.
        for (slot in before.keys - after.keys) assertTrue(before.getValue(slot).centre.distanceTo(camera.copy().mulInPlace(radius)) > 45_000.0)
        val moved = Vec3().setTo(h1.centre).subInPlace(h0.centre).mulInPlace(radius)
        for (slot in shared) {
            val step = Vec3().setTo(after.getValue(slot).centre).normalizeInPlace().subInPlace(Vec3().setTo(before.getValue(slot).centre).normalizeInPlace()).mulInPlace(radius)
            assertTrue("slot $slot moved ${step.distanceTo(moved)} m off the storm's track", step.distanceTo(moved) < 0.25 * moved.length + 300.0)
        }
        // Drawing carries them on at the storm's speed between listings.
        assertEquals(moved.length / 600.0, a.drift.length, 0.25 * moved.length / 600.0)
        assertEquals(h0.wet, a.amount, 1e-9)
    }

    @Test
    fun `none listed when asked not to, and none on Normal`() {
        val (time, h) = strong
        val shapes = ArrayList<CloudShape>()
        Weather(terra, config).clouds(h.centre, 25_000.0, time, shapes, 70_000.0, 0.45, hurricanes = false)
        assertTrue(shapes.none { it.hurricane })
        shapes.clear()
        Weather(terra, WeatherConfig(intensity = WeatherIntensity.NORMAL)).clouds(h.centre, 25_000.0, time, shapes, 70_000.0, 0.45)
        assertTrue(shapes.none { it.hurricane })
    }

    @Test
    fun `ordinary storms are held back inside one, smoothly, and left alone outside`() {
        val (time, h) = strong
        val weather = Weather(terra, config)
        val storms = weather.stormModel
        val hurricanes = weather.hurricaneModel
        val e = Vec3(); val n = Vec3()
        frame(h.centre, e, n)
        val found = ArrayList<Storms.Storm>()
        // The storms showing over the whole hurricane and round it, at times through its passing.
        var inside = 0; var outside = 0
        for (dt in listOf(0.0, 1_800.0, 3_600.0)) {
            val t = time + dt
            found.clear()
            storms.around(h.centre, e, n, (2.0 * h.outerRadius / Storms.CELL).toInt() + 2, t, found)
            val now = all(weather, t).firstOrNull { it.id == h.id } ?: continue
            for (s in found) {
                val c = storms.centreAt(s, t, Vec3())
                val r = c.distanceTo(now.centre) * radius
                val calm = hurricanes.calm(c, t)
                // Only storms still showing are listed, so none should be well inside.
                if (r < 0.6 * now.outerRadius && now.wet > 0.99) inside++
                if (r > 1.05 * now.outerRadius) {
                    assertEquals(0.0, calm, 1e-9)
                    outside++
                }
            }
        }
        println("storms showing: $inside well inside, $outside outside")
        assertEquals("storms showing well inside", 0, inside)
        assertTrue("storms still round it", outside > 0)
        // Holding back fades in as one passes: no step of more than a few per cent a minute.
        val path = Vec3()
        for (k in 0..200) {
            val r = h.outerRadius * k / 200.0
            val a = hurricanes.calm(h.pointAt(r, 1.0, 0.0, radius, path), time)
            val b = hurricanes.calm(h.pointAt(r + h.outerRadius / 200.0, 1.0, 0.0, radius, path), time)
            assertTrue("calm jumps at $r m", kotlin.math.abs(a - b) < 0.05)
        }
        assertTrue(hurricanes.calm(h.centre, time) > 0.99)
        // And none of their rain or wind is left in the eye.
        val air = AirSample()
        val eye = Vec3().setTo(h.centre).mulInPlace(radius + 500.0)
        weather.sample(eye, time, air)
        assertTrue("eye rain ${air.precipitation}", air.precipitation < 0.05)
    }
}
