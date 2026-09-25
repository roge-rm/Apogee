package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.SeaSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sea drawn is the sea the boats float on: round the craft, where hull
 * meets water, every vertex is the physics' own surface.
 */
class SeaSceneTest {

    @Test
    fun `the drawn sea round a craft is the surface it floats on`() {
        val system = SolarSystem.defaultSystem()
        val terra = system.body("terra")
        val config = WeatherConfig(intensity = WeatherIntensity.WILD)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scene = SeaScene(terra, system.body("luna"), config, QualityTier.LOW, scope)
            // Somewhere deep.
            var at = Vec3()
            for (i in 0 until 5_000) {
                val lat = (com.rm.apogee.core.terrain.Noise.hash(9, i, 0, 0) - 0.5) * 1.2
                val lon = com.rm.apogee.core.terrain.Noise.hash(9, i, 1, 0) * 2 * Math.PI
                at = Vec3(Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon))
                if (terra.terrain!!.elevation(at) < -500.0) break
            }
            val centre = at.copy().mulInPlace(terra.radius)
            var surface: SeaSurface? = null
            val deadline = System.currentTimeMillis() + 30_000
            while (surface == null && System.currentTimeMillis() < deadline) {
                scene.update(centre, 5_000.0)
                Thread.sleep(20)
                surface = scene.latest
            }
            val built = surface ?: throw AssertionError("no sea built")
            val truth = Sea(terra, system.body("luna"), Weather(terra, config), config.seed)
            var worst = 0.0
            var checked = 0
            for (v in 0 until built.vertexCount) {
                val o = v * SeaSurface.STRIDE
                val p = Vec3(
                    built.origin.x + built.vertices[o], built.origin.y + built.vertices[o + 1], built.origin.z + built.vertices[o + 2],
                )
                // The hull's neighbourhood: every wave train is in these.
                if (p.distanceTo(built.origin) > 8.0) continue
                val drawn = p.length - terra.radius
                worst = maxOf(worst, kotlin.math.abs(drawn - truth.height(p, built.time)))
                checked++
            }
            assertTrue("checked $checked vertices", checked > 100)
            assertTrue("drawn water off the physics' by $worst m", worst < 0.02)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a camera below the waves is underwater, above them or ashore it is not`() {
        val system = SolarSystem.defaultSystem()
        val terra = system.body("terra")
        val config = WeatherConfig(intensity = WeatherIntensity.WILD)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scene = SeaScene(terra, system.body("luna"), config, QualityTier.LOW, scope)
            var sea: Vec3? = null
            var land: Vec3? = null
            for (i in 0 until 5_000) {
                val lat = (com.rm.apogee.core.terrain.Noise.hash(9, i, 0, 0) - 0.5) * 1.2
                val lon = com.rm.apogee.core.terrain.Noise.hash(9, i, 1, 0) * 2 * Math.PI
                val at = Vec3(Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon))
                val e = terra.terrain!!.elevation(at)
                if (sea == null && e < -500.0) sea = at
                if (land == null && e > 50.0) land = at
                if (sea != null && land != null) break
            }
            val time = 5_000.0
            val wet = sea!!.copy().mulInPlace(terra.radius)
            val height = scene.sampleAt(wet, time).height
            fun at(direction: Vec3, altitude: Double) = direction.copy().mulInPlace(terra.radius + altitude)
            val below = at(sea, height - 0.5)
            assertTrue("half a metre under", scene.isUnder(below, scene.sampleAt(below, time)))
            val above = at(sea, height + 0.5)
            assertTrue("half a metre over", !scene.isUnder(above, scene.sampleAt(above, time)))
            // Below the datum on a hill is inside the hill, not the sea.
            val ashore = at(land!!, -1.0)
            assertTrue("ashore", !scene.isUnder(ashore, scene.sampleAt(ashore, time)))
        } finally {
            scope.cancel()
        }
    }
}
