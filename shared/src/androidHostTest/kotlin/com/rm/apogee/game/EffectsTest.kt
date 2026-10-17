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
        // A second of burn, then four of drifting, inside smoke's lifetime.
        repeat(60) { fx.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), listOf(emitterAt(nozzle)), weather, nozzle, null) }
        val start = fx.centroid(Vec3())
        repeat(240) { fx.step(1.0 / 60.0, 1.0 + it / 60.0, terra, Quat.identity(), emptyList(), weather, nozzle, null) }
        assertTrue("smoke still there", fx.particleCount > 10)
        val moved = fx.centroid(Vec3()).subInPlace(start)
        moved.addScaledInPlace(up, -(moved dot up))
        assertTrue("need wind: $wind", wind.length > 1.0)
        val along = (moved dot wind) / wind.length
        // It takes up the wind over the first second or so, then gets carried by it.
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

    /** A fuselage along the flow with a wing across it, flying along +Y at [speed]. */
    private fun planeAt(altitude: Double, speed: Double): List<com.rm.apogee.render.RenderItem> {
        val fx = Effects(QualityTier.MEDIUM)
        val items = ArrayList<com.rm.apogee.render.RenderItem>()
        val centre = Vec3(terra.radius + altitude, 0.0, 0.0)
        // Span along Z, chord along the flow, thin along X: edge-on to the air.
        val parts = listOf(
            AeroPart(centre.copy(), Vec3.unitX(), Vec3.unitY(), Vec3.unitZ(), Vec3(0.6, 3.0, 0.6), round = true),
            AeroPart(centre.copy(), Vec3.unitZ(), Vec3.unitY(), Vec3.unitX(), Vec3(3.5, 1.0, 0.08), round = false, wing = true),
        )
        repeat(30) {
            fx.aero(centre, Vec3(0.0, speed, 0.0), 4.0, terra, Quat.identity(), 1.0 / 60.0, it / 60.0, 7, items.also { l -> l.clear() }, parts = parts)
        }
        return items
    }

    @Test
    fun `wings show vapour near Mach 1 and glow at re-entry, as wide as their span and thin edge-on`() {
        val vapour = planeAt(1_000.0, 340.0).filter { it.shape == Effects.VAPOUR_CONE }
        assertEquals("the nose collar and a sheet on the wing", 2, vapour.size)
        assertTrue("the sheet spans the wing: ${vapour.map { it.scale }}", vapour.any { it.scale!!.x > 3.0 })

        val sheaths = planeAt(35_000.0, 2_200.0).filter { it.shape == Effects.SHEATH }
        val wide = sheaths.filter { it.scale!!.x > 3.0 }
        assertTrue("the wing glows along its span: ${sheaths.map { it.scale }}", wide.isNotEmpty())
        assertTrue("and thin, edge-on: ${wide.map { it.scale }}", wide.all { it.scale!!.z < 0.5 })
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
    fun `rain falls around the camera`() {
        val fx = Effects(QualityTier.MEDIUM)
        val up = Vec3(1.0, 0.0, 0.0)
        val camera = up.copy().mulInPlace(terra.radius + maxOf(terra.terrain!!.elevation(up), 0.0) + 50.0)
        val air = AirSample().also { it.precipitation = 0.9 }
        repeat(60) { fx.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), null, camera, air) }
        assertTrue("rain: ${fx.particleCount}", fx.particleCount > 50)
    }

    @Test
    fun `blown sand keeps to the edges of the view`() {
        val weather = Weather(terra, WeatherConfig(intensity = com.rm.apogee.core.weather.WeatherIntensity.EXTREME))
        val sample = AirSample()
        // A windy place.
        val up = (0 until 400).asSequence().map { i ->
            val lat = (com.rm.apogee.core.terrain.Noise.hash(5, i, 0, 0) - 0.5) * 1.6
            val lon = com.rm.apogee.core.terrain.Noise.hash(5, i, 1, 0) * 2 * Math.PI
            Vec3(kotlin.math.cos(lat) * kotlin.math.cos(lon), kotlin.math.sin(lat), kotlin.math.cos(lat) * kotlin.math.sin(lon))
        }.first { d ->
            weather.sample(d.copy().mulInPlace(terra.radius + maxOf(terra.terrain!!.elevation(d), 0.0) + 20.0), 0.0, sample)
            sample.wind.length > 10.0
        }
        val camera = up.copy().mulInPlace(terra.radius + maxOf(terra.terrain!!.elevation(up), 0.0) + 2.0)
        val air = AirSample().also { it.dust = 0.9 }
        val fx = Effects(QualityTier.HIGH)
        val px = Effects::class.java.getDeclaredField("px").also { it.isAccessible = true }
        val py = Effects::class.java.getDeclaredField("py").also { it.isAccessible = true }
        val pz = Effects::class.java.getDeclaredField("pz").also { it.isAccessible = true }
        // Looking across the ground each of four ways, sand blows past, none in the middle of the view.
        var seen = 0
        for (k in 0 until 4) {
            val east = Vec3(up.z, 0.0, -up.x).normalizeInPlace()
            val north = up.copy().crossInPlace(east)
            val a = k * Math.PI / 2
            val forward = east.copy().mulInPlace(kotlin.math.cos(a)).addScaledInPlace(north, kotlin.math.sin(a))
            val f = Effects(QualityTier.HIGH)
            repeat(120) { f.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), weather, camera, air, forward) }
            val x = px.get(f) as DoubleArray; val y = py.get(f) as DoubleArray; val z = pz.get(f) as DoubleArray
            for (i in 0 until f.particleCount) {
                val d = Vec3(x[i], y[i], z[i]).subInPlace(camera)
                assertTrue("sand off the middle: ${(d dot forward) / d.length}", (d dot forward) / d.length < 0.8)
            }
            seen += f.particleCount
        }
        assertTrue("some sand: $seen", seen > 0)
        // With no view given, none.
        repeat(60) { fx.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), weather, camera, air) }
        assertEquals(0, fx.particleCount)
    }

    @Test
    fun `blizzard snow blows past off the middle of the view, and no rain with it`() {
        val weather = Weather(terra, WeatherConfig(intensity = com.rm.apogee.core.weather.WeatherIntensity.EXTREME))
        val up = Vec3(0.0, 1.0, 0.0)
        val camera = up.copy().mulInPlace(terra.radius + maxOf(terra.terrain!!.elevation(up), 0.0) + 2.0)
        val air = AirSample().also { it.snow = 0.9; it.snowfall = 0.8; it.precipitation = 0.8 }
        val px = Effects::class.java.getDeclaredField("px").also { it.isAccessible = true }
        val py = Effects::class.java.getDeclaredField("py").also { it.isAccessible = true }
        val pz = Effects::class.java.getDeclaredField("pz").also { it.isAccessible = true }
        val forward = Vec3(1.0, 0.0, 0.0)
        val f = Effects(QualityTier.HIGH)
        repeat(120) { f.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), weather, camera, air, forward) }
        val x = px.get(f) as DoubleArray; val y = py.get(f) as DoubleArray; val z = pz.get(f) as DoubleArray
        for (i in 0 until f.particleCount) {
            val d = Vec3(x[i], y[i], z[i]).subInPlace(camera)
            assertTrue("snow off the middle: ${(d dot forward) / d.length}", (d dot forward) / d.length < 0.97)
            assertTrue("near the camera, no rain from 35 m up: ${d.length}", d.length < 60.0)
        }
        println("snow flakes after 2 s: ${f.particleCount}")
        assertTrue("snow: ${f.particleCount}", f.particleCount > 100)
    }

    @Test
    fun `ash flecks drift down near the camera, off the middle of the view and within their share`() {
        val up = Vec3(0.0, 1.0, 0.0)
        val camera = up.copy().mulInPlace(terra.radius + maxOf(terra.terrain!!.elevation(up), 0.0) + 2.0)
        val air = AirSample().also { it.ash = 1.0 }
        val px = Effects::class.java.getDeclaredField("px").also { it.isAccessible = true }
        val py = Effects::class.java.getDeclaredField("py").also { it.isAccessible = true }
        val pz = Effects::class.java.getDeclaredField("pz").also { it.isAccessible = true }
        val forward = Vec3(1.0, 0.0, 0.0)
        for (tier in QualityTier.entries) {
            val f = Effects(tier)
            repeat(180) { f.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), null, camera, air, forward) }
            val x = px.get(f) as DoubleArray; val y = py.get(f) as DoubleArray; val z = pz.get(f) as DoubleArray
            for (i in 0 until f.particleCount) {
                val d = Vec3(x[i], y[i], z[i]).subInPlace(camera)
                assertTrue("off the middle: ${(d dot forward) / d.length}", (d dot forward) / d.length < 0.97)
                assertTrue("near the camera: ${d.length}", d.length < Effects.ASH_FAR + 5.0)
            }
            println("$tier: ${f.particleCount} ash flecks of ${tier.particleBudget}")
            assertTrue("flecks on $tier", f.particleCount > 20)
            assertTrue("within its share on $tier", f.particleCount <= tier.particleBudget * Effects.ASH_SHARE * 1.2 + 5)
        }
        val none = Effects(QualityTier.HIGH)
        repeat(60) { none.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), null, camera, AirSample(), forward) }
        assertEquals("none with no ash", 0, none.particleCount)
    }

    @Test
    fun `hail falls fast off the middle of the view, bounces and keeps to its share`() {
        val up = Vec3(0.0, 1.0, 0.0)
        val camera = up.copy().mulInPlace(terra.radius + maxOf(terra.terrain!!.elevation(up), 0.0) + 2.0)
        val air = AirSample().also { it.hail = 1.0 }
        val px = Effects::class.java.getDeclaredField("px").also { it.isAccessible = true }
        val py = Effects::class.java.getDeclaredField("py").also { it.isAccessible = true }
        val pz = Effects::class.java.getDeclaredField("pz").also { it.isAccessible = true }
        val vx = Effects::class.java.getDeclaredField("vx").also { it.isAccessible = true }
        val vy = Effects::class.java.getDeclaredField("vy").also { it.isAccessible = true }
        val vz = Effects::class.java.getDeclaredField("vz").also { it.isAccessible = true }
        val forward = Vec3(1.0, 0.0, 0.0)
        for (tier in QualityTier.entries) {
            val f = Effects(tier)
            var most = 0
            var bounced = false
            repeat(180) {
                f.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), null, camera, air, forward)
                most = maxOf(most, f.particleCount)
                val y = vy.get(f) as DoubleArray; val x = vx.get(f) as DoubleArray; val z = vz.get(f) as DoubleArray
                for (i in 0 until f.particleCount) if (Vec3(x[i], y[i], z[i]) dot up > 0.5) bounced = true
            }
            val x = px.get(f) as DoubleArray; val y = py.get(f) as DoubleArray; val z = pz.get(f) as DoubleArray
            val v = vy.get(f) as DoubleArray
            var fast = 0
            for (i in 0 until f.particleCount) {
                val d = Vec3(x[i], y[i], z[i]).subInPlace(camera)
                assertTrue("near the camera: ${d.length}", d.length < 40.0)
                if (-v[i] > 10.0) fast++
            }
            println("$tier: hail %d at most of %d (%.0f%%), %d falling fast, bounced %b".format(
                most, tier.particleBudget, 100.0 * most / tier.particleBudget, fast, bounced))
            assertTrue("hail: $most", most > tier.particleBudget * 0.1)
            assertTrue("within its share: $most", most <= tier.particleBudget * (Effects.HAIL_SHARE + 0.05))
            assertTrue("most falling fast: $fast of ${f.particleCount}", fast > f.particleCount / 2)
            assertTrue("some bounce off the ground", bounced)
        }
        // And none in light rain.
        val f = Effects(QualityTier.MEDIUM)
        repeat(60) { f.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), null, camera, AirSample().also { a -> a.hail = 0.02 }, forward) }
        assertEquals(0, f.particleCount)
    }

    @Test
    fun `lightning on the craft flashes full and cracks once`() {
        val up = Vec3(0.0, 1.0, 0.0)
        val craft = up.copy().mulInPlace(terra.radius + 500.0)
        val fx = Effects(QualityTier.MEDIUM)
        fx.struck(craft, 42L, 15.0, 10.0, own = true)
        assertEquals(1f, fx.flash, 1e-6f)
        assertEquals("its thunder", 1, fx.thunder.size)
        assertEquals(15.0, fx.thunder[0][0], 1e-9)
        fx.thunder.clear()
        fx.struck(craft, 42L, 15.0, 10.05, own = true)
        assertEquals("not twice", 0, fx.thunder.size)
        val bolts = Effects::class.java.getDeclaredField("bolts").also { it.isAccessible = true }.get(fx) as List<*>
        assertEquals(1, bolts.size)
        // Drawn down to the craft.
        val (vertices, shapes) = fx.vertices(Quat.identity(), craft.copy().addScaledInPlace(Vec3(1.0, 0.0, 0.0), 30.0), Quat.identity(), 1f, 10.1)
        assertTrue("bolt drawn: $shapes", shapes >= 12 && vertices.isNotEmpty())
        // Someone else's craft far off only lights the sky a little.
        val other = Effects(QualityTier.MEDIUM)
        other.struck(craft, 43L, 20_000.0, 10.0, own = false)
        assertTrue("dimmer far off: ${other.flash}", other.flash < 0.3f)
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
        // A rocket 1 km up at Mach 1 in damp air, nose 8 m ahead of the middle, 1 m radius.
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
            // A tenth of the way along it's barely wider.
            val near = profile.filter { it[1] >= -0.1 }.maxOf { it[0] }
            assertTrue("vacuum $vacuum: $near near the nozzle", near <= 1.3)
        }
        // In thick air it's hardly wider than the bell anywhere.
        assertTrue(Effects.rocketPlume(0.0).profile.maxOf { it[0] } <= 1.1)
        // In vacuum it fans out well past the bell downstream.
        assertTrue(Effects.rocketPlume(1.0).profile.maxOf { it[0] } >= 2.5)
    }

    @Test
    fun `a thruster's puffs leave opposite its push, in vacuum too`() {
        val fx = Effects(QualityTier.HIGH)
        val at = Vec3(terra.radius + 200_000.0, 0.0, 0.0)
        val out = Vec3(0.0, 0.0, 1.0) // the gas goes +z, so the block pushes -z
        repeat(20) {
            fx.rcsPuff(at, out, Vec3(), 1.0, inAir = false, dt = 1.0 / 60.0, seed = it)
            fx.step(1.0 / 60.0, it / 60.0, terra, Quat.identity(), emptyList(), null, at, null)
        }
        assertTrue("some puffs: ${fx.particleCount}", fx.particleCount > 0)
        val moved = fx.centroid(Vec3()).subInPlace(at)
        assertTrue("out along +z: $moved", moved.z > 1.0 && kotlin.math.abs(moved.x) < moved.z * 0.3 && kotlin.math.abs(moved.y) < moved.z * 0.3)
        // Stopped, they're gone within a second.
        repeat(60) { fx.step(1.0 / 60.0, 1.0 + it / 60.0, terra, Quat.identity(), emptyList(), null, at, null) }
        assertEquals(0, fx.particleCount)
    }

    /** A joint at its limit on a craft doing a kilometre a second. */
    @Test
    fun `sparks off a straining seam stay with a fast craft`() {
        val fx = Effects(QualityTier.HIGH)
        val up = Vec3(1.0, 0.0, 0.0)
        val start = up.copy().mulInPlace(terra.radius + 18_000.0)
        val velocity = Vec3(0.0, 1_000.0, 0.0)
        val seam = start.copy()
        // As the game does each frame: move the craft, throw sparks, step the effects.
        repeat(30) { k ->
            if (k > 0) seam.addScaledInPlace(velocity, 1.0 / 60.0)
            fx.strain(seam, velocity, 0.6, 40.0, inAir = true, colour = null, dt = 1.0 / 60.0, seed = 7)
            fx.step(1.0 / 60.0, k / 60.0, terra, Quat.identity(), emptyList(), null, seam, null)
        }
        assertTrue("some sparks: ${fx.particleCount}", fx.particleCount > 5)
        // Centred on this frame's seam, not strewn behind it or a frame ahead.
        val off = fx.centroid(Vec3()).distanceTo(seam)
        assertTrue("with the seam ($off m)", off < 3.0)
    }

    @Test
    fun `a flame in air thicker than Terra's, on Caligo, points out the bell like one at sea level`() {
        fun plume(body: com.rm.apogee.core.orbit.CelestialBody): com.rm.apogee.render.RenderItem {
            val up = Vec3(1.0, 0.0, 0.0)
            val nozzle = up.copy().mulInPlace(body.radius + maxOf(body.terrain?.elevation(up) ?: 0.0, 0.0) + 2.0)
            val emitter = EngineEmitter(nozzle = nozzle, out = up.copy().mulInPlace(-1.0), radius = 0.5, kind = Exhaust.ROCKET, throttle = 1.0, velocity = Vec3(), seed = 1)
            val out = ArrayList<com.rm.apogee.render.RenderItem>()
            Effects(QualityTier.MEDIUM).flames(listOf(emitter), body, 0.0, out)
            return out.first()
        }
        val caligo = SolarSystem.defaultSystem().body("caligo")!!
        val thick = plume(caligo).scale!!.y
        val sea = plume(terra).scale!!.y
        assertTrue("$thick m on Caligo, $sea m at sea level", thick > 0.0 && kotlin.math.abs(thick - sea) < 0.25 * sea)
    }
}
