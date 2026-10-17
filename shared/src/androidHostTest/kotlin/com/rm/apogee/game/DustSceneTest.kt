package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.DustKind
import com.rm.apogee.core.weather.LiveDust
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
 * Dust storms as seen and heard: a haboob's wall drawn within each tier's budget with keys that
 * hold, none far off or high up; the haze easing in without a pop; nothing at all on a calm
 * setting; the hiss falling off; and the map's icon.
 */
class DustSceneTest {
    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val radius = terra.radius

    private fun along(c: Vec3, heading: Vec3, metres: Double) = Vec3().setTo(c).addScaledInPlace(heading, metres / radius).normalizeInPlace()
    private fun above(d: Vec3, height: Double) = Vec3().setTo(d).mulInPlace(radius + maxOf(terra.terrain!!.elevation(d), 0.0) + height)

    /** A strong haboob over desert part way through its life, and when. */
    private val haboob: Pair<Double, LiveDust> by lazy {
        val w = Weather(terra, config)
        var t = 6 * 3_600.0
        while (t < 200 * 3_600.0) {
            val live = ArrayList<LiveDust>()
            w.dustStorms(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (d in live) {
                if (d.kind != DustKind.HABOOB || d.strength < 0.5 || d.age < 900.0 || d.age > 0.6 * d.life) continue
                if (listOf(-3_000.0, 0.0, 15_000.0).any { w.dustiness(along(d.centre, d.heading, it)) < 0.9 }) continue
                return@lazy t to d
            }
            t += 1_800.0
        }
        error("no haboob over desert")
    }

    private fun DustScene.settle() {
        val listing = javaClass.getDeclaredField("listing").also { it.isAccessible = true }
        val until = System.nanoTime() + 20_000_000_000L
        while (listing.get(this) as Boolean && System.nanoTime() < until) Thread.sleep(1)
    }

    /** A scene for [tier] at body-fixed [camera] at [time], listed. */
    private fun scene(tier: QualityTier, scope: CoroutineScope, camera: Vec3, time: Double): DustScene {
        val w = Weather(terra, config)
        val s = DustScene(terra, config, tier, scope, w)
        val air = AirSample()
        w.sample(camera, time, air)
        s.update(camera, time, air); s.settle()
        return s
    }

    @Test
    fun `a haboob's wall is drawn within budget, keeps its keys and is gone far off and high up`() {
        val (time, d) = haboob
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val camera = above(along(d.centre, d.heading, 15_000.0), 2.0)
            for (tier in QualityTier.entries) {
                val s = scene(tier, scope, camera, time)
                val a = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), time, a)
                val b = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), time + 0.1, b)
                println("$tier: ${a.size} wall items of ${s.itemsMost}, ${s.walls.size} walls")
                assertTrue("a wall on $tier", a.size >= s.columns)
                assertTrue("within budget on $tier: ${a.size}", a.size <= s.itemsMost)
                assertEquals("same keys a frame on", a.map { it.key }.toSet(), b.map { it.key }.toSet())
                assertEquals("keys all different", a.size, a.map { it.key }.toSet().size)
                assertTrue("none past the reach", a.all { it.position.distanceTo(camera) < s.reach + 3_000.0 })
                assertTrue("dust's soft shading", a.all { it.ambient < 0f })
                // Carried on along its heading.
                val moved = b[0].position.distanceTo(a[0].position)
                assertTrue("moving at its speed: $moved", moved in 0.05 * d.speed * 0.1..3.0 * d.speed * 0.1 + 1.0)
            }
            // Far off: listed from there, nothing.
            val far = above(along(d.centre, d.heading, 200_000.0), 2.0)
            val fs = scene(QualityTier.HIGH, scope, far, time)
            val none = ArrayList<RenderItem>(); fs.append(far, Quat.identity(), time, none)
            assertTrue("none far off: ${none.size}", none.isEmpty())
            // High up, from where the veil has it.
            val high = above(along(d.centre, d.heading, 15_000.0), DustScene.LIST_TOP + 1_000.0)
            val hs = scene(QualityTier.HIGH, scope, high, time)
            val up = ArrayList<RenderItem>(); hs.append(high, Quat.identity(), time, up)
            assertTrue("none high up", up.isEmpty())
            assertTrue("its veil instead", hs.veil != null)
            assertTrue("veil shown up there", hs.veilAlpha(20_000.0) > 0.99f && hs.veilAlpha(500.0) == 0f)
        } finally { scope.cancel() }
    }

    @Test
    fun `the haze eases in and out without a pop, and none on a calm setting`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val camera = above(Vec3(0.0, 0.0, 1.0), 2.0)
            val s = DustScene(terra, config, QualityTier.LOW, scope, Weather(terra, config))
            val air = AirSample()
            var t = 0.0
            s.update(camera, t, air)
            assertEquals(0.0, s.haze, 0.0)
            assertTrue(s.sight.isInfinite())
            air.dust = 0.8; air.dustLook = 0.8
            var last = 0.0
            var most = 0.0
            while (t < 10.0) {
                t += 1.0 / 60.0
                s.update(camera, t, air)
                most = maxOf(most, s.haze - last)
                last = s.haze
            }
            println("haze in: largest step a frame %.4f, after 10 s %.3f".format(most, s.haze))
            assertTrue("no pop going in", most < 0.02)
            assertEquals("there in the end", 0.8, s.haze, 0.01)
            assertEquals("sight as the model has it", 150.0 / 0.8, s.sight, 3.0)
            assertTrue("the sky mostly hidden", s.skyHidden > 0.9)
            air.dust = 0.0; air.dustLook = 0.0
            while (t < 25.0) { t += 1.0 / 60.0; s.update(camera, t, air) }
            assertEquals("gone again", 0.0, s.haze, 0.0)
            assertFalse("off on Normal", DustScene(terra, WeatherConfig(intensity = WeatherIntensity.NORMAL), QualityTier.LOW, scope, Weather(terra, WeatherConfig())).active)
        } finally { scope.cancel() }
    }

    @Test
    fun `the hiss falls off with the dust and is sand close only near the ground`() {
        var last = -1.0
        for (dust in listOf(0.0, 0.05, 0.15, 0.3, 0.6, 1.0)) {
            val h = DustScene.hiss(dust, 20.0)
            assertTrue("louder in thicker dust", h >= last)
            last = h
        }
        assertEquals("none with no dust", 0.0, DustScene.hiss(0.0, 30.0), 0.0)
        assertTrue("quieter in still air", DustScene.hiss(0.6, 2.0) < DustScene.hiss(0.6, 25.0))
        assertTrue("sand near the ground", DustScene.sandNear(0.8, 2.0) > 0.9)
        assertEquals("not high up", 0.0, DustScene.sandNear(0.8, 400.0), 0.0)
    }

    @Test
    fun `the map marks a haboob going its way`() {
        val (_, d) = haboob
        val icons = ArrayList<GameSession.MapIcon>(); val lines = ArrayList<RenderLine>()
        GameSession.dustMarks(listOf(d), Quat.identity(), radius, icons, lines)
        val icon = icons.single()
        assertEquals(GameSession.IconKind.DUST, icon.kind)
        assertTrue("at its front", icon.at.distanceTo(Vec3().setTo(d.centre).mulInPlace(radius)) < 1.0)
        assertTrue("going its way", (icon.heading!! dot d.heading) > 0.999)
    }
}
