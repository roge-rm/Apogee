package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.LiveBlizzard
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
 * Blizzards as seen and heard: curtains of snow drawn within each tier's budget with keys that
 * hold, none far off or high up; the whiteout easing in without a pop and the light going flat;
 * nothing on a Normal setting; the howl; and the map's icon.
 */
class SnowSceneTest {
    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val radius = terra.radius

    private fun along(c: Vec3, heading: Vec3, metres: Double) = Vec3().setTo(c).addScaledInPlace(heading, metres / radius).normalizeInPlace()
    private fun above(d: Vec3, height: Double) = Vec3().setTo(d).mulInPlace(radius + maxOf(terra.terrain!!.elevation(d), 0.0) + height)

    /** A strong blizzard over cold country part way through its life, and when. */
    private val blizzard: Pair<Double, LiveBlizzard> by lazy {
        val w = Weather(terra, config)
        var t = 6 * 3_600.0
        while (t < 200 * 3_600.0) {
            val live = ArrayList<LiveBlizzard>()
            w.blizzards(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (z in live) {
                if (z.strength < 0.5 || z.age < 1_800.0 || z.age > 0.6 * z.life) continue
                if (w.blizzardCold(z.centre) < 0.95) continue
                return@lazy t to z
            }
            t += 1_800.0
        }
        error("no blizzard over cold country")
    }

    private fun SnowScene.settle() {
        val listing = javaClass.getDeclaredField("listing").also { it.isAccessible = true }
        val until = System.nanoTime() + 20_000_000_000L
        while (listing.get(this) as Boolean && System.nanoTime() < until) Thread.sleep(1)
    }

    private fun scene(tier: QualityTier, scope: CoroutineScope, camera: Vec3, time: Double): SnowScene {
        val w = Weather(terra, config)
        val s = SnowScene(terra, config, tier, scope, w)
        val air = AirSample()
        w.sample(camera, time, air)
        s.update(camera, time, air); s.settle()
        return s
    }

    @Test
    fun `a blizzard's curtain is drawn within budget, keeps its keys and is gone far off and high up`() {
        val (time, z) = blizzard
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val camera = above(along(z.centre, z.heading, 1.3 * z.halfLength), 2.0)
            for (tier in QualityTier.entries) {
                val s = scene(tier, scope, camera, time)
                val a = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), time, a)
                val b = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), time + 0.1, b)
                println("$tier: ${a.size} curtain items of ${s.itemsMost}, ${s.curtains.size} blizzards")
                assertTrue("a curtain on $tier", a.size >= s.columns / 2)
                assertTrue("within budget on $tier: ${a.size}", a.size <= s.itemsMost)
                assertEquals("same keys a frame on", a.map { it.key }.toSet(), b.map { it.key }.toSet())
                assertEquals("keys all different", a.size, a.map { it.key }.toSet().size)
                assertTrue("none past the reach", a.all { it.position.distanceTo(camera) < s.reach + 5_000.0 })
                assertTrue("soft shading", a.all { it.ambient < 0f })
            }
            val far = above(along(z.centre, z.heading, 400_000.0), 2.0)
            val fs = scene(QualityTier.HIGH, scope, far, time)
            val none = ArrayList<RenderItem>(); fs.append(far, Quat.identity(), time, none)
            assertTrue("none far off: ${none.size}", none.isEmpty())
            val high = above(z.centre, SnowScene.LIST_TOP + 1_000.0)
            val hs = scene(QualityTier.HIGH, scope, high, time)
            val up = ArrayList<RenderItem>(); hs.append(high, Quat.identity(), time, up)
            assertTrue("none high up", up.isEmpty())
            assertTrue("its veil instead", hs.veil != null)
            assertTrue("veil shown up there", hs.veilAlpha(20_000.0) > 0.99f && hs.veilAlpha(500.0) == 0f)
        } finally { scope.cancel() }
    }

    @Test
    fun `the whiteout eases in without a pop, the light goes flat, and none on Normal`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val camera = above(Vec3(0.0, 1.0, 0.0), 2.0)
            val s = SnowScene(terra, config, QualityTier.LOW, scope, Weather(terra, config))
            val air = AirSample()
            var t = 0.0
            s.update(camera, t, air)
            assertEquals(0.0, s.haze, 0.0)
            assertTrue(s.sight.isInfinite())
            assertEquals(0.0, s.flat, 0.0)
            air.snow = 0.9; air.snowTop = 2_500.0
            var last = 0.0
            var most = 0.0
            while (t < 10.0) {
                t += 1.0 / 60.0
                s.update(camera, t, air)
                most = maxOf(most, s.haze - last)
                last = s.haze
            }
            println("whiteout in: largest step a frame %.4f, after 10 s %.3f, sight %.0f m".format(most, s.haze, s.sight))
            assertTrue("no pop going in", most < 0.02)
            assertEquals("there in the end", 0.9, s.haze, 0.01)
            assertEquals("sight as the model has it", 60.0 / 0.9, s.sight, 2.0)
            assertTrue("flat light", s.flat > 0.99)
            air.snow = 0.0
            while (t < 25.0) { t += 1.0 / 60.0; s.update(camera, t, air) }
            assertEquals("gone again", 0.0, s.haze, 0.0)
            val normal = WeatherConfig(intensity = WeatherIntensity.NORMAL)
            assertFalse("off on Normal", SnowScene(terra, normal, QualityTier.LOW, scope, Weather(terra, normal)).active)
        } finally { scope.cancel() }
    }

    @Test
    fun `the howl grows with the snow and wind`() {
        var last = -1.0
        for (snow in listOf(0.0, 0.05, 0.15, 0.3, 0.6, 1.0)) {
            val h = SnowScene.howl(snow, 25.0)
            assertTrue("louder in thicker snow", h >= last)
            last = h
        }
        assertEquals("none with no snow", 0.0, SnowScene.howl(0.0, 30.0), 0.0)
        assertTrue("quieter in still air", SnowScene.howl(0.6, 2.0) < SnowScene.howl(0.6, 25.0))
        assertTrue("snow close near the ground", SnowScene.snowNear(0.8, 2.0) > 0.9)
        assertEquals("not high up", 0.0, SnowScene.snowNear(0.8, 400.0), 0.0)
    }

    @Test
    fun `the map marks a blizzard with its oval`() {
        val (_, z) = blizzard
        val icons = ArrayList<GameSession.MapIcon>(); val lines = ArrayList<RenderLine>()
        GameSession.blizzardMarks(listOf(z), Quat.identity(), radius, icons, lines)
        val icon = icons.single()
        assertEquals(GameSession.IconKind.BLIZZARD, icon.kind)
        assertTrue("at its middle", icon.at.distanceTo(Vec3().setTo(z.centre).mulInPlace(radius)) < 1.0)
        val ring = lines.single().points
        val far = ring.maxOf { it.distanceTo(icon.at) }
        assertEquals("out to its long edge", z.halfLength, far, 0.05 * z.halfLength)
    }
}
