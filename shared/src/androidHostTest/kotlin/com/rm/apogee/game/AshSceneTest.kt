package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.LiveEruption
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.RenderItem
import com.rm.apogee.render.RenderLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Volcanic ash as seen and heard: an eruption's column and cloud drawn within each tier's budget
 * with keys that hold, none far off or high up, a glow at the vent only by night; the haze easing
 * in without a pop and dimming the light; nothing on Normal; the rumble; and the map's marks.
 */
class AshSceneTest {
    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val radius = terra.radius

    private fun above(d: Vec3, height: Double) = Vec3().setTo(d).mulInPlace(radius + maxOf(terra.terrain!!.elevation(d), 0.0) + height)

    private val found: Pair<Double, LiveEruption> by lazy { eruption(Weather(terra, config)) }

    private fun AshScene.settle() {
        val listing = javaClass.getDeclaredField("listing").also { it.isAccessible = true }
        val until = System.nanoTime() + 20_000_000_000L
        while (listing.get(this) as Boolean && System.nanoTime() < until) Thread.sleep(1)
    }

    private fun scene(tier: QualityTier, scope: CoroutineScope, camera: Vec3, time: Double): AshScene {
        val w = Weather(terra, config)
        val s = AshScene(terra, config, tier, scope, w)
        val air = AirSample()
        w.sample(camera, time, air)
        s.update(camera, time, air); s.settle()
        return s
    }

    /** Unit point [ahead] metres along [e]'s track and [aside] across. */
    private fun from(e: LiveEruption, ahead: Double, aside: Double): Vec3 =
        e.track.pointAt(ahead, Vec3()).addScaledInPlace(e.track.sideAt(ahead, Vec3()), aside / radius).normalizeInPlace()

    @Test
    fun `an eruption is drawn within budget, keeps its keys and is gone far off and high up`() {
        val (time, e) = found
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Off to the side of the cloud, 40 km from the vent.
            val camera = above(from(e, 10_000.0, -40_000.0), 2.0)
            for (tier in QualityTier.entries) {
                val s = scene(tier, scope, camera, time)
                val a = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), time, a)
                val b = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), time + 0.1, b)
                println("$tier: ${a.size} ash items of ${s.itemsMost}, ${s.plumes.size} eruptions")
                assertTrue("drawn on $tier", a.size >= s.columnPieces)
                assertTrue("within budget on $tier: ${a.size}", a.size <= s.itemsMost)
                val same = a.map { it.key }.toSet().intersect(b.map { it.key }.toSet()).size
                assertTrue("keys hold a frame on: $same of ${a.size}", same >= a.size - 2)
                assertEquals("keys all different", a.size, a.map { it.key }.toSet().size)
                assertTrue("none past the reach", a.all { it.position.distanceTo(camera) < s.reach + 60_000.0 })
                assertTrue("no glow without a sun", a.none { kotlin.math.abs(it.ambient) >= 1f })
            }
            val far = above(from(e, 0.0, -1_500_000.0), 2.0)
            val fs = scene(QualityTier.HIGH, scope, far, time)
            val none = ArrayList<RenderItem>(); fs.append(far, Quat.identity(), time, none)
            assertTrue("none far off: ${none.size}", none.isEmpty())
            val high = above(e.vent, AshScene.LIST_TOP + 1_000.0)
            val hs = scene(QualityTier.HIGH, scope, high, time)
            val up = ArrayList<RenderItem>(); hs.append(high, Quat.identity(), time, up)
            assertTrue("none high up", up.isEmpty())
        } finally { scope.cancel() }
    }

    @Test
    fun `the vent glows by night only`() {
        val (time, e) = found
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val camera = above(from(e, -20_000.0, -20_000.0), 2.0)
            val s = scene(QualityTier.MEDIUM, scope, camera, time)
            val night = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), time, night, sun = Vec3().setTo(e.vent).mulInPlace(-1.0))
            val day = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), time, day, sun = e.vent.copy())
            assertTrue("a glow by night", night.any { kotlin.math.abs(it.ambient) >= 1f })
            assertTrue("none by day", day.none { kotlin.math.abs(it.ambient) >= 1f })
        } finally { scope.cancel() }
    }

    @Test
    fun `the haze eases in without a pop, dims the light, and none on Normal`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val camera = above(Vec3(0.0, 1.0, 0.0), 2.0)
            val s = AshScene(terra, config, QualityTier.LOW, scope, Weather(terra, config))
            val air = AirSample()
            var t = 0.0
            s.update(camera, t, air)
            assertEquals(0.0, s.haze, 0.0)
            assertTrue(s.sight.isInfinite())
            assertEquals(0.0, s.dimming, 0.0)
            air.ash = 0.8; air.ashTop = 9_000.0
            var last = 0.0
            var most = 0.0
            while (t < 10.0) {
                t += 1.0 / 60.0
                s.update(camera, t, air)
                most = maxOf(most, s.haze - last)
                last = s.haze
            }
            println("ash in: largest step a frame %.4f, after 10 s %.3f, sight %.0f m, top %.0f m, dimming %.2f".format(most, s.haze, s.sight, s.top, s.dimming))
            assertTrue("no pop going in", most < 0.02)
            assertEquals("there in the end", 0.8, s.haze, 0.01)
            assertEquals("sight as the model has it", 100.0 / 0.8, s.sight, 2.0)
            assertTrue("the layer up to the ash's top", s.top > 7_000.0)
            assertTrue("dim under it", s.dimming > 0.6)
            air.ash = 0.0
            while (t < 25.0) { t += 1.0 / 60.0; s.update(camera, t, air) }
            assertEquals("gone again", 0.0, s.haze, 0.0)
            val normal = WeatherConfig(intensity = WeatherIntensity.NORMAL)
            assertFalse("off on Normal", AshScene(terra, normal, QualityTier.LOW, scope, Weather(terra, normal)).active)
            val luna = SolarSystem.defaultSystem().body("luna")
            assertFalse("off with no air", AshScene(luna, config, QualityTier.LOW, scope, Weather(luna, config)).active)
        } finally { scope.cancel() }
    }

    @Test
    fun `the rumble falls off from the vent and the grit grows with the ash`() {
        var last = 2.0
        for (d in listOf(1_000.0, 4_000.0, 10_000.0, 30_000.0, 60_000.0, 89_000.0)) {
            val r = AshScene.rumble(1.0, d)
            assertTrue("quieter further off", r <= last)
            last = r
        }
        assertEquals("full close by", 1.0, AshScene.rumble(1.0, 2_000.0), 1e-9)
        assertEquals("none far off", 0.0, AshScene.rumble(1.0, 100_000.0), 0.0)
        assertTrue("quieter for a weak one", AshScene.rumble(0.3, 10_000.0) < AshScene.rumble(1.0, 10_000.0))
        assertEquals("no grit without ash", 0.0, AshScene.gritOf(0.0), 0.0)
        assertTrue(AshScene.gritOf(0.2) < AshScene.gritOf(0.6))
    }

    @Test
    fun `the map marks the vent and the cloud's outline`() {
        val (_, e) = found
        val icons = ArrayList<GameSession.MapIcon>(); val lines = ArrayList<RenderLine>()
        GameSession.ashMarks(listOf(e), Quat.identity(), radius, icons, lines)
        val icon = icons.single()
        assertEquals(GameSession.IconKind.ASH, icon.kind)
        assertTrue("at the vent", icon.at.distanceTo(Vec3().setTo(e.vent).mulInPlace(radius)) < 1.0)
        val outline = lines.single().points
        val far = outline.maxOf { it.distanceTo(icon.at) }
        val end = e.track.pointAt(e.length, Vec3()).mulInPlace(radius)
        val toEnd = outline.minOf { it.distanceTo(end) }
        println("cloud %.0f km long, outline reaches %.0f km, %.1f km from its track's far end".format(e.length / 1e3, far / 1e3, toEnd / 1e3))
        assertTrue("out to its far end: $toEnd", toEnd < 1.1 * e.halfWidth)
        assertTrue("no further: $far", far < e.length + 2 * e.halfWidth)
    }

    companion object {
        /** A strong eruption a couple of hours in, with its cloud well out, on [weather], and when. */
        fun eruption(weather: Weather): Pair<Double, LiveEruption> {
            var t = 6 * 3_600.0
            while (t < 600 * 3_600.0) {
                val live = ArrayList<LiveEruption>()
                weather.eruptions(Vec3.unitY(), t, Double.MAX_VALUE, live)
                for (e in live) if (e.strength > 0.5 && e.age > 5_400.0 && e.length > 60_000.0 && e.drift.length > 5.0) return t to e
                t += 1_800.0
            }
            error("no eruption")
        }
    }
}
