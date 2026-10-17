package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.weather.Quakes
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.world.World
import com.rm.apogee.render.QualityTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A quake as seen and heard: the camera shakes on shaking ground, less higher up and not at all
 * high over it, far off or with no quake live; the rumble falls off the same way.
 */
class QuakeSceneTest {
    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val cape = World.launchSites.first { it.id == "cape" }.let { SolarSystem.surfaceDirection(it.latitude, it.longitude) }

    private fun east(up: Vec3) = Vec3(up.z, 0.0, -up.x).normalizeInPlace()

    private fun above(up: Vec3, height: Double) =
        Vec3().setTo(up).mulInPlace(terra.radius + maxOf(terra.terrain!!.elevation(up), 0.0) + height)

    @Test
    fun `the camera shake fades with height and is gone high up`() {
        var last = Double.MAX_VALUE
        for (h in listOf(0.0, 15.0, 50.0, 100.0, 200.0, 250.0)) {
            val s = QuakeScene.shakeAmount(4.0, h)
            assertTrue("less higher up at $h: $s", s <= last)
            last = s
        }
        assertTrue("strong near the ground", QuakeScene.shakeAmount(4.0, 5.0) > 0.8)
        assertTrue("some at 100 m", QuakeScene.shakeAmount(4.0, 100.0) in 0.1..0.8)
        assertEquals("none past 250 m", 0.0, QuakeScene.shakeAmount(10.0, 300.0), 0.0)
        assertEquals("none for a tremor too light to feel", 0.0, QuakeScene.shakeAmount(0.15, 2.0), 0.0)
        assertTrue("stronger shaking, more", QuakeScene.shakeAmount(1.0, 2.0) < QuakeScene.shakeAmount(3.0, 2.0))
        assertEquals("full at most", 1.0, QuakeScene.shakeAmount(20.0, 0.0), 1e-12)
        assertTrue("rumble heard higher than it shakes", QuakeScene.rumble(3.0, 300.0) > 0.5)
        assertEquals("no rumble high up", 0.0, QuakeScene.rumble(3.0, 2_000.0), 0.0)
    }

    /** Waits for the scene's worker, so a listing or check started this frame is in by the next. */
    private fun QuakeScene.settle() {
        val listing = javaClass.getDeclaredField("listing").also { it.isAccessible = true }
        val until = System.nanoTime() + 20_000_000_000L
        while (listing.get(this) as Boolean && System.nanoTime() < until) Thread.sleep(1)
    }

    /** Whether the scene's worker last found shaking reaching the camera. */
    private fun QuakeScene.felt() = javaClass.getDeclaredField("felt").also { it.isAccessible = true }.get(this) as Boolean

    /** The camera's largest jolt and turn over [seconds] from [from], 30 frames a second, at body-fixed [camera]. */
    private fun shaken(scene: QuakeScene, camera: Vec3, from: Double, seconds: Double): Triple<Double, Double, Double> {
        var most = 0.0; var turn = 0.0; var amount = 0.0
        var t = from
        while (t < from + seconds) {
            scene.update(camera, t)
            scene.settle()
            val p = Vec3().setTo(camera); val q = Quat.identity()
            scene.shakeCamera(p, q, t)
            most = maxOf(most, p.distanceTo(camera))
            turn = maxOf(turn, 2.0 * kotlin.math.acos(kotlin.math.abs(q.w).coerceAtMost(1.0)))
            amount = maxOf(amount, scene.shake)
            t += 1.0 / 30.0
        }
        return Triple(most, turn, amount)
    }

    @Test
    fun `a strong quake shakes the camera at the pad, not high up, far off or after`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val epicentre = Vec3().setTo(cape).addScaledInPlace(east(cape), 6_000.0 / terra.radius).normalizeInPlace()
            val start = 1_000.0
            fun forced() = Quakes.of(terra, config).also { it.force(epicentre, start, 7.5) }
            fun scene() = QuakeScene(terra, config, QualityTier.LOW, scope, forced(), ::forced)
            val ground = shaken(scene(), above(cape, 2.0), start, 40.0)
            println("at the pad: jolt %.3f m, turn %.2f deg, shake %.2f".format(ground.first, Math.toDegrees(ground.second), ground.third))
            assertTrue("shakes: ${ground.third}", ground.third > 0.5)
            assertTrue("a few cm: ${ground.first}", ground.first in 0.01..QuakeScene.SHAKE_METRES * 1.6)
            assertTrue("under a degree: ${ground.second}", ground.second > 0.0 && ground.second < Math.toRadians(1.0))
            val low = shaken(scene(), above(cape, 120.0), start, 40.0)
            assertTrue("less at 120 m: ${low.third}", low.third > 0.0 && low.third < ground.third)
            val high = shaken(scene(), above(cape, 400.0), start, 40.0)
            assertEquals("none at 400 m", 0.0, high.third, 0.0)
            val far = Vec3().setTo(cape).addScaledInPlace(east(cape), -600_000.0 / terra.radius).normalizeInPlace()
            val farScene = scene()
            assertEquals("none far off", 0.0, shaken(farScene, above(far, 2.0), start, 200.0).third, 0.0)
            assertTrue("not looked up every frame far off", !farScene.felt())
            val before = shaken(scene(), above(cape, 2.0), start - 30.0, 30.0)
            assertEquals("none before it strikes", 0.0, before.third, 0.0)
            val after = scene()
            shaken(after, above(cape, 2.0), start, 40.0)
            assertEquals("still after it's over", 0.0, shaken(after, above(cape, 2.0), start + 400.0, 2.0).third, 0.0)
            // And none on a setting without quakes.
            val calm = WeatherConfig(intensity = WeatherIntensity.NORMAL)
            val quiet = QuakeScene(terra, calm, QualityTier.LOW, scope, Quakes.of(terra, calm))
            assertEquals(0.0, shaken(quiet, above(cape, 2.0), start, 10.0).third, 0.0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the rumble follows the shaking and is gone after`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val epicentre = Vec3().setTo(cape).addScaledInPlace(east(cape), 6_000.0 / terra.radius).normalizeInPlace()
            fun forced() = Quakes.of(terra, config).also { it.force(epicentre, 0.0, 7.0) }
            val scene = QuakeScene(terra, config, QualityTier.LOW, scope, forced(), ::forced)
            var loudest = 0.0
            var t = 0.0
            while (t < 60.0) {
                scene.update(above(cape, 2.0), t); scene.settle(); scene.heard(t)
                loudest = maxOf(loudest, scene.loudness)
                t += 0.1
            }
            println("rumble at the pad: %.2f".format(loudest))
            assertTrue("heard: $loudest", loudest > 0.4)
            t = 600.0
            repeat(30) { scene.update(above(cape, 2.0), t); scene.heard(t); t += 0.1 }
            assertEquals("gone after", 0.0, scene.loudness, 0.0)
        } finally {
            scope.cancel()
        }
    }
}
