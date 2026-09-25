package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.Exhaust
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.render.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smoke stays within the device's budget, and goes where the wind goes. */
class EffectsTest {

    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)

    private fun emitterAt(position: Vec3) = EngineEmitter(
        nozzle = position, out = position.copy().normalizeInPlace().mulInPlace(-1.0), radius = 0.5,
        kind = Exhaust.ROCKET, throttle = 1.0, velocity = terra.surfaceVelocityAt(position, Vec3()), seed = 1,
    )

    @Test
    fun `smoke keeps within the budget`() {
        val fx = Effects(QualityTier.LOW)
        val up = Vec3(1.0, 0.0, 0.0)
        val nozzle = up.copy().mulInPlace(terra.radius + terra.terrain!!.elevation(up) + 20.0)
        repeat(1_200) { fx.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), listOf(emitterAt(nozzle)), null, nozzle, null) }
        assertTrue("some smoke: ${fx.particleCount}", fx.particleCount > 20)
        assertTrue("within LOW's budget: ${fx.particleCount}", fx.particleCount <= QualityTier.LOW.particleBudget)
    }

    @Test
    fun `smoke drifts downwind`() {
        val fx = Effects(QualityTier.MEDIUM)
        val weather = Weather(terra, WeatherConfig())
        val up = Vec3(1.0, 0.0, 0.0)
        val ground = terra.radius + maxOf(terra.terrain!!.elevation(up), 0.0)
        val nozzle = up.copy().mulInPlace(ground + 150.0)
        val air = AirSample()
        weather.sample(up.copy().mulInPlace(ground + 150.0), 0.0, air)
        val wind = air.wind.copy().addScaledInPlace(up, -(air.wind dot up))
        // A second of burn, then four of drifting - inside smoke's lifetime.
        repeat(60) { fx.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), listOf(emitterAt(nozzle)), weather, nozzle, null) }
        val start = fx.centroid(Vec3())
        repeat(240) { fx.step(1.0 / 60.0, 1.0 + it / 60.0, terra, Quat.identity(), emptyList(), weather, nozzle, null) }
        assertTrue("smoke still there", fx.particleCount > 10)
        val moved = fx.centroid(Vec3()).subInPlace(start)
        moved.addScaledInPlace(up, -(moved dot up))
        assertTrue("need wind: $wind", wind.length > 1.0)
        val along = (moved dot wind) / wind.length
        // Taking up the wind over the first second or so, then carried by it.
        assertTrue("drifted $moved in a wind of $wind", along > 0.5 * wind.length * 4.0 * 0.5)
    }

    private fun aeroAt(altitude: Double, speed: Double): Pair<List<com.rm.apogee.render.RenderItem>, Effects> {
        val fx = Effects(QualityTier.MEDIUM)
        val items = ArrayList<com.rm.apogee.render.RenderItem>()
        val centre = Vec3(terra.radius + altitude, 0.0, 0.0)
        repeat(30) { fx.aero(centre, Vec3(0.0, speed, 0.0), 3.0, terra, Quat.identity(), 1.0 / 60.0, it / 60.0, 7, items.also { l -> l.clear() }) }
        return items to fx
    }

    @Test
    fun `a vapour cone forms at the speed of sound down low`() {
        val (items, _) = aeroAt(1_000.0, 340.0)
        assertTrue("a cone: $items", items.any { it.shape == Effects.VAPOUR_CONE })
        val (slow, _) = aeroAt(1_000.0, 200.0)
        assertTrue("not at 200 m/s", slow.isEmpty())
        val (high, _) = aeroAt(25_000.0, 340.0)
        assertTrue("not in the thin dry air at 25 km", high.none { it.shape == Effects.VAPOUR_CONE })
    }

    @Test
    fun `re-entry glows and throws sparks`() {
        val (items, fx) = aeroAt(35_000.0, 2_200.0)
        assertTrue("a glowing shock: $items", items.any { it.shape == Effects.BOW_SHOCK })
        assertTrue("sparks: ${fx.particleCount}", fx.particleCount > 5)
        val (orbit, _) = aeroAt(80_000.0, 2_200.0)
        assertTrue("none above the air", orbit.isEmpty())
    }

    @Test
    fun `rain falls round the camera`() {
        val fx = Effects(QualityTier.MEDIUM)
        val up = Vec3(1.0, 0.0, 0.0)
        val camera = up.copy().mulInPlace(terra.radius + maxOf(terra.terrain!!.elevation(up), 0.0) + 50.0)
        val air = AirSample().also { it.precipitation = 0.9 }
        repeat(60) { fx.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), null, camera, air) }
        assertTrue("rain: ${fx.particleCount}", fx.particleCount > 50)
    }

    @Test
    fun `lightning lights the sky`() {
        val weather = Weather(terra, WeatherConfig(intensity = com.rm.apogee.core.weather.WeatherIntensity.WILD))
        val strikes = ArrayList<com.rm.apogee.core.weather.Strike>()
        var strike: com.rm.apogee.core.weather.Strike? = null
        for (i in 0 until 2_000) {
            val lat = (com.rm.apogee.core.terrain.Noise.hash(3, i, 0, 0) - 0.5) * 1.2
            val lon = com.rm.apogee.core.terrain.Noise.hash(3, i, 1, 0) * 2 * Math.PI
            val dir = Vec3(kotlin.math.cos(lat) * kotlin.math.cos(lon), kotlin.math.sin(lat), kotlin.math.cos(lat) * kotlin.math.sin(lon))
            strikes.clear()
            weather.strikes(dir, 500.0, 3_000.0, strikes)
            strike = strikes.firstOrNull()
            if (strike != null) break
        }
        val s = strike ?: throw AssertionError("no strike found")
        val fx = Effects(QualityTier.MEDIUM)
        val camera = s.direction.copy().mulInPlace(terra.radius + 3_000.0)
        var brightest = 0f
        var t = s.time - 0.5
        while (t < s.time + 0.5) {
            fx.step(1.0 / 60.0, t, terra, Quat.identity(), emptyList(), weather, camera, null)
            brightest = maxOf(brightest, fx.flash)
            t += 1.0 / 60.0
        }
        assertTrue("a flash: $brightest", brightest > 0.2f)
    }

    @Test
    fun `the vapour collar sits just behind the nose, sized to the body`() {
        val fx = Effects(QualityTier.MEDIUM)
        // A rocket's middle 1 km up, climbing through Mach 1 in damp low air;
        // its nose 8 m ahead of the middle, its body 1 m in radius.
        val up = Vec3(1.0, 0.0, 0.0)
        val centre = up.copy().mulInPlace(terra.radius + 1_000.0)
        val air = up.copy().mulInPlace(Effects.SPEED_OF_SOUND)
        val nose = centre.copy().addScaledInPlace(up, 8.0)
        val items = ArrayList<com.rm.apogee.render.RenderItem>()
        fx.aero(centre, air, 9.0, terra, Quat.identity(), 1.0 / 60.0, 0.0, 7, items, nose, 1.0)
        val vapour = items.firstOrNull { it.shape == Effects.VAPOUR_CONE }
        assertTrue("vapour at Mach 1 in damp air", vapour != null)
        vapour!!
        val behind = (nose.copy().subInPlace(vapour.position)).dot(up)
        assertTrue("behind the nose, not round the middle: $behind m", behind > 0.5 && behind < 4.0)
        assertTrue("as wide as the body, give or take: ${vapour.scale}", vapour.scale!!.x in 1.5..3.0)
    }

    @Test
    fun `a rocket's flame leaves the bell at the bell's width, in air and in vacuum`() {
        for (vacuum in listOf(0.0, 0.5, 1.0)) {
            val profile = Effects.rocketPlume(vacuum).profile
            val atNozzle = profile.single { it[1] == 0.0 }[0]
            assertTrue("vacuum $vacuum: $atNozzle at the nozzle", kotlin.math.abs(atNozzle - 1.0) < 1e-9)
            // Spreading only downstream: a tenth of the way along, barely wider.
            val near = profile.filter { it[1] >= -0.1 }.maxOf { it[0] }
            assertTrue("vacuum $vacuum: $near near the nozzle", near <= 1.3)
        }
        // In thick air it stays a flame, hardly wider than the bell anywhere.
        assertTrue(Effects.rocketPlume(0.0).profile.maxOf { it[0] } <= 1.1)
        // In vacuum it fans out well past it downstream.
        assertTrue(Effects.rocketPlume(1.0).profile.maxOf { it[0] } >= 2.5)
    }

    @Test
    fun `a thruster's puffs leave opposite its push, in vacuum too`() {
        val fx = Effects(QualityTier.HIGH)
        val at = Vec3(terra.radius + 200_000.0, 0.0, 0.0)
        val out = Vec3(0.0, 0.0, 1.0) // the gas goes +z: the block pushes -z
        repeat(20) {
            fx.rcsPuff(at, out, Vec3(), 1.0, inAir = false, dt = 1.0 / 60.0, seed = it)
            fx.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), null, at, null)
        }
        assertTrue("some puffs: ${fx.particleCount}", fx.particleCount > 0)
        val moved = fx.centroid(Vec3()).subInPlace(at)
        assertTrue("out along +z: $moved", moved.z > 1.0 && kotlin.math.abs(moved.x) < moved.z * 0.3 && kotlin.math.abs(moved.y) < moved.z * 0.3)
        // Stopped, they are gone within a second.
        repeat(60) { fx.step(1.0 / 60.0, 1.0 + it / 60.0, terra, Quat.identity(), emptyList(), null, at, null) }
        assertEquals(0, fx.particleCount)
    }

    /** A joint at its limit on a craft doing a kilometre a second: its sparks stay with it, not strewn behind. */
    @Test
    fun `sparks off a straining seam stay with a fast craft`() {
        val fx = Effects(QualityTier.HIGH)
        val up = Vec3(1.0, 0.0, 0.0)
        val start = up.copy().mulInPlace(terra.radius + 18_000.0)
        val velocity = Vec3(0.0, 1_000.0, 0.0)
        val seam = start.copy()
        // As the game does it, a frame at a time: the craft moved to this
        // frame's place, sparks thrown from there, then the effects stepped.
        repeat(30) { k ->
            if (k > 0) seam.addScaledInPlace(velocity, 1.0 / 60.0)
            fx.strain(seam, velocity, 0.6, 40.0, inAir = true, colour = null, dt = 1.0 / 60.0, seed = 7)
            fx.step(1.0 / 60.0, k / 60.0, terra, Quat.identity(), emptyList(), null, seam, null)
        }
        assertTrue("some sparks: ${fx.particleCount}", fx.particleCount > 5)
        // Drawn with the craft where it is this frame: centred on the seam,
        // not strewn behind it nor a frame ahead of it.
        val off = fx.centroid(Vec3()).distanceTo(seam)
        assertTrue("with the seam ($off m)", off < 3.0)
    }
}
