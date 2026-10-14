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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drawn sea is the one boats float on: near the craft every vertex is the physics' surface.
 * The grid is laid out on the ground, so moving the craft doesn't move the facets.
 */
class SeaSceneTest {

    /** Distance along the surface; storm-wave height must not shrink the sampled centre. */
    private fun across(p: Vec3, centre: Vec3, radius: Double) = p.normalized().distanceTo(centre.normalized()) * radius

    /** Intersect a radial ray with the drawn triangles, including the fine grid's collapsed ones. */
    private fun drawnRadius(surface: SeaSurface, direction: Vec3): Double {
        fun point(index: Int): Vec3 {
            val o = index * SeaSurface.STRIDE
            return Vec3(surface.vertices[o].toDouble(), surface.vertices[o + 1].toDouble(), surface.vertices[o + 2].toDouble()).addInPlace(surface.origin)
        }
        for (t in surface.indices.indices step 3) {
            val a = point(surface.indices[t]); val b = point(surface.indices[t + 1]); val c = point(surface.indices[t + 2])
            val e = b.subInPlace(a); val f = c.subInPlace(a)
            val normal = e.copy().crossInPlace(f)
            val along = normal dot direction
            if (kotlin.math.abs(along) < 1e-12) continue
            val radius = (normal dot a) / along
            val p = direction.copy().mulInPlace(radius).subInPlace(a)
            val ee = e dot e; val ff = f dot f; val ef = e dot f
            val determinant = ee * ff - ef * ef
            if (determinant <= 1e-12) continue
            val u = ((p dot e) * ff - (p dot f) * ef) / determinant
            val v = ((p dot f) * ee - (p dot e) * ef) / determinant
            if (u >= -1e-6 && v >= -1e-6 && u + v <= 1.000001) return radius
        }
        throw AssertionError("no sea triangle on $direction")
    }

    @Test
    fun `dropping the finest water grid with altitude keeps the same surface`() {
        val body = com.rm.apogee.core.orbit.CelestialBody("test-sea", "Sea", 3.5316e12, 600_000.0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val below = builtAt(SeaScene(body, null, null, QualityTier.LOW, scope), Vec3(body.radius + 199.99, 0.0, 0.0), 5_000.0)
            val above = builtAt(SeaScene(body, null, null, QualityTier.LOW, scope), Vec3(body.radius + 200.01, 0.0, 0.0), 5_000.0)
            assertTrue("different grid layouts", below.vertexCount != above.vertexCount)
            for (y in -4..4) {
                val direction = Vec3(body.radius, y + 0.3, 0.7).normalizeInPlace()
                assertEquals("surface at $y", drawnRadius(below, direction), drawnRadius(above, direction), 0.01)
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `moving a water grid boundary keeps the same surface`() {
        val body = com.rm.apogee.core.orbit.CelestialBody("test-sea", "Sea", 3.5316e12, 600_000.0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val before = builtAt(SeaScene(body, null, null, QualityTier.LOW, scope), Vec3(body.radius, 0.0, 0.999), 5_000.0)
            val after = builtAt(SeaScene(body, null, null, QualityTier.LOW, scope), Vec3(body.radius, 0.0, 1.001), 5_000.0)
            assertTrue("grid triangles shifted", !before.indices.contentEquals(after.indices))
            for (z in listOf(-11.5, -10.5, 12.5, 13.5)) {
                val direction = Vec3(body.radius, 0.3, z).normalizeInPlace()
                assertEquals("surface at $z", drawnRadius(before, direction), drawnRadius(after, direction), 0.01)
            }
        } finally { scope.cancel() }
    }

    /** Somewhere deep on Terra, as a unit direction. */
    private fun deep(terra: com.rm.apogee.core.orbit.CelestialBody): Vec3 {
        for (i in 0 until 5_000) {
            val lat = (com.rm.apogee.core.terrain.Noise.hash(9, i, 0, 0) - 0.5) * 1.2
            val lon = com.rm.apogee.core.terrain.Noise.hash(9, i, 1, 0) * 2 * Math.PI
            val at = Vec3(Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon))
            if (terra.terrain!!.elevation(at) < -500.0) return at
        }
        throw AssertionError("no deep sea")
    }

    /** A sea built around body-fixed [centre] at [time]. */
    private fun builtAt(scene: SeaScene, centre: Vec3, time: Double): SeaSurface {
        scene.update(centre, time)
        val deadline = System.currentTimeMillis() + 30_000
        while (!scene.built && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            scene.update(centre, time)
        }
        if (!scene.built) throw AssertionError("no sea built")
        return scene.latest!!
    }

    @Test
    fun `moving the craft a little doesn't move the water's facets`() {
        val system = SolarSystem.defaultSystem()
        val terra = system.body("terra")
        val config = WeatherConfig(intensity = WeatherIntensity.WILD)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val at = deep(terra)
            val east = Vec3(0.0, 1.0, 0.0).crossInPlace(at).normalizeInPlace()
            val here = at.copy().mulInPlace(terra.radius)
            val there = here.copy().addScaledInPlace(east, 3.3)
            // Same moment, so the same point has the same wave.
            val a = builtAt(SeaScene(terra, system.body("luna"), config, QualityTier.LOW, scope), here, 5_000.0)
            val b = builtAt(SeaScene(terra, system.body("luna"), config, QualityTier.LOW, scope), there, 5_000.0)
            fun points(s: SeaSurface) = (0 until s.vertexCount).map { v ->
                val o = v * SeaSurface.STRIDE
                Vec3(s.origin.x + s.vertices[o], s.origin.y + s.vertices[o + 1], s.origin.z + s.vertices[o + 2])
            }
            val before = points(a).filter { across(it, here, terra.radius) < 12.0 }
            val after = points(b)
            var matched = 0
            for (p in before) {
                // Both samples must lie inside the full-detail centre, outside the morph band.
                if (across(p, here, terra.radius) > 4.0 || across(p, there, terra.radius) > 4.0) continue
                val nearest = after.minOf { it.distanceTo(p) }
                assertTrue("a vertex moved $nearest m", nearest < 0.01)
                matched++
            }
            assertTrue("matched $matched", matched > 10)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the drawn sea around a craft is the surface it floats on`() {
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
            // Flat water to draw straight away while the sea is built.
            scene.update(centre, 5_000.0)
            assertTrue("nothing to draw on the first frame", scene.latest != null)
            val deadline = System.currentTimeMillis() + 30_000
            while (!scene.built && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                scene.update(centre, 5_000.0)
            }
            if (!scene.built) throw AssertionError("no sea built")
            val built = scene.latest!!
            val truth = Sea(terra, system.body("luna"), Weather(terra, config), config.seed)
            var worst = 0.0
            var checked = 0
            for (v in 0 until built.vertexCount) {
                val o = v * SeaSurface.STRIDE
                val p = Vec3(
                    built.origin.x + built.vertices[o], built.origin.y + built.vertices[o + 1], built.origin.z + built.vertices[o + 2],
                )
                // The LOW grid keeps full detail within 4 m, including at cube-face corners.
                if (across(p, centre, terra.radius) > 4.0) continue
                val drawn = p.length - terra.radius
                worst = maxOf(worst, kotlin.math.abs(drawn - truth.height(p, built.time)))
                checked++
            }
            assertTrue("checked $checked vertices", checked > 30)
            assertTrue("drawn water off the physics' by $worst m", worst < 0.02)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a camera below the waves is underwater, and above them or ashore it isn't`() {
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
