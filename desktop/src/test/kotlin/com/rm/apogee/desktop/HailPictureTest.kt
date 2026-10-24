package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.weather.*
import com.rm.apogee.game.CloudScene
import com.rm.apogee.game.Effects
import com.rm.apogee.game.SeaScene
import com.rm.apogee.game.TerrainBuilder
import com.rm.apogee.render.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*
import org.lwjgl.opengl.GL30C
import java.io.File

/**
 * Hail and lightning by eye: inside heavy hail by a rover, a hail shaft from afar, and the rover
 * struck (the flash frame). Writes pictures to build/hail-review.
 */
class HailPictureTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")
    private val luna = system.body("luna")
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun along(body: CelestialBody, c: Vec3, heading: Vec3, metres: Double) =
        Vec3().setTo(c).addScaledInPlace(heading, metres / body.radius).normalizeInPlace()
    private fun above(body: CelestialBody, d: Vec3, height: Double) =
        Vec3().setTo(d).mulInPlace(body.radius + body.terrain!!.elevation(d).let { if (body.terrain!!.hasOcean) maxOf(it, 0.0) else it } + height)

    /** A shaft hailing hard over land, with land toward it from 25 km off, by day, and when. */
    private fun shaft(): Pair<Double, LiveHail> {
        val w = Weather(terra, config)
        val found = ArrayList<LiveHail>()
        for (i in 0 until 20_000) {
            val lat = (com.rm.apogee.core.terrain.Noise.hash(7, i, 0, 0) - 0.5) * 1.4
            val lon = com.rm.apogee.core.terrain.Noise.hash(7, i, 1, 0) * 2 * Math.PI
            val d = Vec3(kotlin.math.cos(lat) * kotlin.math.cos(lon), kotlin.math.sin(lat), kotlin.math.cos(lat) * kotlin.math.sin(lon))
            val t = 6 * 3_600.0 + com.rm.apogee.core.terrain.Noise.hash(7, i, 2, 0) * 100 * 3_600.0
            found.clear()
            w.hailShafts(d, t, 100_000.0, found)
            for (h in found) {
                if (h.hail < 0.7) continue
                if (listOf(0.0, 6_000.0, 14_000.0).any { terra.terrain!!.elevation(along(terra, h.centre, h.heading, it)) < 5.0 }) continue
                return t to h
            }
        }
        error("no hail over land")
    }

    private fun Any.settle() {
        val listing = javaClass.getDeclaredField("listing").also { it.isAccessible = true }
        val until = System.nanoTime() + 20_000_000_000L
        while (listing.get(this) as Boolean && System.nanoTime() < until) Thread.sleep(2)
    }

    /** A cloud scene on [body] at [camera], eased into [time] so its haze has settled, with its storm clouds listed. */
    private fun clouds(body: CelestialBody, scope: CoroutineScope, camera: Vec3, time: Double, tier: QualityTier): CloudScene {
        val scene = CloudScene(body, config, tier, scope)
        var t = time - 8.0
        while (t < time) { scene.update(camera, t); t += 1.0 / 30.0 }
        scene.update(camera, time); scene.settle()
        scene.update(camera, time); scene.settle()
        return scene
    }

    private fun cloudsOf(scene: CloudScene, camera: Vec3, time: Double): List<RenderItem> = try {
        val lister = scene.javaClass.getDeclaredMethod("list", Vec3::class.java, java.lang.Double.TYPE,
            java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Boolean.TYPE).also { it.isAccessible = true }
        val merge = scene.javaClass.getDeclaredMethod("merged", List::class.java, List::class.java, Vec3::class.java).also { it.isAccessible = true }
        val field = scene.javaClass.getDeclaredField("lobes").also { it.isAccessible = true }
        scene.weather.sample(camera, time, scene.air)
        val near = lister.invoke(scene, camera, time, 0.0, 30_000.0, false)
        val far = lister.invoke(scene, camera, time, 21_000.0, 100_000.0, false)
        field.set(scene, merge.invoke(scene, near, far, camera))
        scene.append(Quat.identity(), time, ArrayList(), 0L)
        ArrayList<RenderItem>().also { scene.append(Quat.identity(), time, it, 2_000_000_000L) }
    } catch (e: Exception) {
        println("no storm clouds: ${e.cause ?: e}"); emptyList()
    }

    private val width = 960
    private val height = 600


    @Test fun pictures() {
        TestGl.current()
        val out = File("../build/hail-review").apply { mkdirs() }
        val tier = QualityTier.MEDIUM
        val fbo = GL30C.glGenFramebuffers()
        val colourBuffer = GL30C.glGenRenderbuffers(); val depthBuffer = GL30C.glGenRenderbuffers()
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, colourBuffer)
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL_RGBA8, width, height)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, colourBuffer)
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, depthBuffer)
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_DEPTH24_STENCIL8, width, height)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_RENDERBUFFER, depthBuffer)
        val bus = FrameBus()
        val renderer = GlRenderer({ tier }, bus)
        renderer.onSurfaceCreated(); renderer.onSurfaceChanged(width, height)
        var terrain = TerrainBuilder(renderer.terrainSource, tier)
        var terrainOf = terra.id
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            fun look(from: Vec3, at: Vec3, upHint: Vec3): Quat {
                val forward = Vec3().setTo(at).subInPlace(from).normalizeInPlace()
                val q1 = quatFromTo(Vec3(0.0, 0.0, -1.0), forward)
                val current = q1.rotate(Vec3.unitY(), Vec3())
                val wanted = Vec3().setTo(upHint).addScaledInPlace(forward, -(upHint dot forward)).normalizeInPlace()
                return quatFromTo(current, wanted) * q1
            }

            fun shoot(
                name: String, body: CelestialBody, camera: Vec3, rotation: Quat, time: Double, items: List<RenderItem>,
                scene: CloudScene?, sun: Vec3, veilAlpha: Float = 0f, flakes: Pair<FloatArray, Int>? = null, shadowAt: Vec3? = null, flash: Float = 0f,
            ): java.awt.image.BufferedImage {
                if (terrainOf != body.id) {
                    terrain.stop(); terrain = TerrainBuilder(renderer.terrainSource, tier); terrainOf = body.id
                }
                val up = Vec3().setTo(camera).normalizeInPlace()
                val altitude = camera.length - body.radius - body.terrain!!.elevation(up).let { if (body.terrain!!.hasOcean) maxOf(it, 0.0) else it }
                val sea = if (body.id == terra.id && altitude < 50_000.0) SeaScene(terra, luna, config, tier, scope).also { s ->
                    s.javaClass.getDeclaredField("outerBudgetNanos").also { it.isAccessible = true }.setLong(s, Long.MAX_VALUE / 4)
                } else null
                fun frame(): RenderFrame {
                    val here = sea?.sampleAt(camera, time)
                    val world = WorldView(
                        body.radius, body.atmosphereHeight, body.atmosphere?.scaleHeight ?: 1.0, sun, Vec3.unitY(), camera.length - body.radius, Quat.identity(),
                        body.terrain!!.maxElevation, drawFarSurface = terrain.farSurfaceNeeded, chunkRange = terrain.chunkRange, time = time,
                        fogDistance = scene?.fogDistance ?: 1.0e9, fogColor = scene?.fogColor ?: floatArrayOf(0.75f, 0.77f, 0.8f),
                        skyFog = scene?.skyFog ?: 0f, lightScale = scene?.lightScale ?: 1f,
                        dustFog = scene?.dustFog ?: 0.0, dustTop = scene?.dustTop ?: 0.0,
                        seaReach = if (sea != null && sea.built) sea.reach else 0.0, tide = here?.tide ?: 0.0, sea = sea?.latest,
                        seaRadius = if (body.terrain!!.hasOcean) body.radius + (here?.tide ?: 0.0) else 0.0,
                        snowVeil = if (veilAlpha > 0f) scene?.snow?.veil else null, snowVeilAlpha = veilAlpha,
                        flatLight = scene?.flatLight ?: 0f, flash = flash,
                        sky = SkyColours.of(body.id),
                    )
                    return RenderFrame(1, System.nanoTime(), camera, rotation, FOV, items, world = world, nearestDistance = 4.0,
                        particles = flakes?.first, particleShapes = flakes?.second ?: 0, shadowFocus = shadowAt, shadowRadius = if (shadowAt != null) 60.0 else 0.0)
                }
                val start = System.nanoTime()
                val deadline = start + 60_000_000_000L
                var readyAt = 0L
                while (System.nanoTime() < deadline) {
                    terrain.collect()
                    terrain.followCraft(body, camera, altitude, scope)
                    terrain.requestGlobe(body, scope)
                    sea?.update(camera, time)
                    bus.clear(); bus.publish(frame())
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
                    renderer.onDrawFrame()
                    val ready = (terrain.patchReady || altitude > 100_000.0) && (sea == null || sea.built)
                    if (ready) {
                        if (readyAt == 0L) readyAt = System.nanoTime()
                        if (System.nanoTime() - readyAt > (if (altitude > 100_000.0) 8_000_000_000L else 4_000_000_000L)) break
                    }
                    Thread.sleep(10)
                }
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
                val data = BufferUtils.createByteBuffer(width * height * 4)
                glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, data)
                val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                for (y in 0 until height) for (x in 0 until width) {
                    val i = ((height - 1 - y) * width + x) * 4
                    image.setRGB(x, y, ((data.get(i).toInt() and 255) shl 16) or ((data.get(i + 1).toInt() and 255) shl 8) or (data.get(i + 2).toInt() and 255))
                }
                if (name.isNotEmpty()) {
                    javax.imageio.ImageIO.write(image, "png", File(out, "$name.png"))
                    println("wrote ${File(out, "$name.png").canonicalPath}")
                }
                assertEquals(GL_NO_ERROR, glGetError())
                return image
            }

            /** The storm clouds round [camera]. */
            fun dressed(scene: CloudScene, camera: Vec3, time: Double): List<RenderItem> {
                val clouds = cloudsOf(scene, camera, time)
                println("  ${clouds.size} clouds, hail %.2f rain %.2f fog %.0f m light %.2f".format(scene.air.hail, scene.air.precipitation, scene.fogDistance, scene.lightScale))
                return clouds
            }

            /** Hail and rain round [camera] looking along [rotation], as the game makes it over two seconds, and [strike] on it at the end. */
            fun flakes(scene: CloudScene, camera: Vec3, rotation: Quat, time: Double, strike: Vec3? = null): Pair<FloatArray, Int> {
                val fx = Effects(tier)
                val forward = rotation.rotate(Vec3(0.0, 0.0, -1.0), Vec3())
                var t = time - 2.0
                while (t < time) { fx.step(1.0 / 30.0, t, terra, Quat.identity(), emptyList(), scene.weather, camera, scene.air, forward); t += 1.0 / 30.0 }
                if (strike != null) fx.struck(strike, 77L, strike.distanceTo(camera), time, own = true)
                println("  ${fx.particleCount} particles")
                return fx.vertices(Quat.identity(), camera, rotation, scene.lightScale, time, flash = if (strike != null) fx.flash else 0f)
            }

            /** A small rover on the ground at [d], facing [way]. */
            fun rover(d: Vec3, way: Vec3): List<RenderItem> {
                val ground = above(terra, d, 0.0)
                val r = quatFromTo(Vec3.unitY(), d)
                val turn = quatFromTo(r.rotate(Vec3(0.0, 0.0, 1.0), Vec3()), way)
                val q = turn * r
                fun at(x: Double, y: Double, z: Double) = Vec3().setTo(ground).addInPlace(q.rotate(Vec3(x, y, z), Vec3()))
                val body = floatArrayOf(0.85f, 0.42f, 0.18f, 1f)
                val dark = floatArrayOf(0.15f, 0.15f, 0.17f, 1f)
                return listOf(
                    RenderItem(MeshSpec.Box(2.2, 0.9, 3.6), at(0.0, 1.2, 0.0), q, body),
                    RenderItem(MeshSpec.Box(1.6, 0.7, 1.4), at(0.0, 2.0, -0.4), q, floatArrayOf(0.9f, 0.9f, 0.92f, 1f)),
                ) + listOf(-1.3 to -1.2, 1.3 to -1.2, -1.3 to 1.2, 1.3 to 1.2).map { (x, z) -> RenderItem(MeshSpec.Sphere(0.55), at(x, 0.55, z), q, dark) }
            }


            val (time, h) = shaft()
            println("hail: %.2f, %.0f m wide, %.1f m/s".format(h.hail, h.width, h.speed))
            val right = Vec3().setTo(h.heading).crossInPlace(h.centre).normalizeInPlace()
            // A sun high behind the camera, so it's day under the storm.
            fun sunFor(up: Vec3, toward: Vec3) = Vec3().setTo(up).mulInPlace(0.8).addScaledInPlace(toward, -0.5).addScaledInPlace(right, 0.3).normalizeInPlace()
            run {
                // Inside heavy hail, near a rover on the ground.
                val from = h.centre
                val camera = above(terra, from, 3.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val roverAt = along(terra, from, h.heading, 12.0)
                val view = look(camera, above(terra, roverAt, 1.0), from)
                val dressing = dressed(scene, camera, time)
                shoot("hail-inside", terra, camera, view, time, dressing + rover(roverAt, right), scene, sunFor(from, h.heading),
                    flakes = flakes(scene, camera, view, time))
                assertTrue("hail at the camera: ${scene.air.hail}", scene.air.hail > 0.3)
            }
            run {
                // Near the shaft's side, looking out toward the clear sky with the sun behind: stones near and far in bright light.
                val air = AirSample()
                val w = Weather(terra, config)
                val from = (1..12).map { along(terra, h.centre, right, h.width * it / 24.0) }
                    .lastOrNull { w.sample(above(terra, it, 3.0), time, air); air.hail > 0.25 } ?: h.centre
                val camera = above(terra, from, 3.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val view = look(camera, above(terra, along(terra, from, right, 3_000.0), 300.0), from)
                shoot("hail-bright", terra, camera, view, time, dressed(scene, camera, time), scene, sunFor(from, right),
                    flakes = flakes(scene, camera, view, time))
            }
            run {
                // The shaft from 14 km off, ahead of its storm, from a little way up.
                val from = along(terra, h.centre, h.heading, 14_000.0)
                val camera = above(terra, from, 150.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val target = above(terra, h.centre, 900.0)
                shoot("hail-shaft", terra, camera, look(camera, target, from), time, dressed(scene, camera, time), scene,
                    sunFor(from, Vec3().setTo(h.centre).subInPlace(from)))
            }
            // A low sun, so it's dark under the storm.
            fun lowSun(up: Vec3, toward: Vec3) = Vec3().setTo(up).mulInPlace(0.25).addScaledInPlace(toward, -0.6).addScaledInPlace(right, 0.5).normalizeInPlace()
            run {
                // The rover struck under the storm, looking back into it: the flash frame, its bolt down to it.
                val from = along(terra, h.centre, h.heading, 3_000.0)
                val roverAt = along(terra, from, h.heading, -60.0)
                val camera = above(terra, from, 4.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val view = look(camera, above(terra, roverAt, 28.0), from)
                val fx = flakes(scene, camera, view, time, strike = above(terra, roverAt, 2.4))
                shoot("lightning-struck", terra, camera, view, time, dressed(scene, camera, time) + rover(roverAt, right), scene, lowSun(from, h.heading),
                    flakes = fx, flash = 1f)
            }
            run {
                // A rover struck 150 m off, looking up its bolt: the kinks up close.
                val from = along(terra, h.centre, h.heading, 3_000.0)
                val roverAt = along(terra, from, h.heading, -150.0)
                val camera = above(terra, from, 2.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val view = look(camera, above(terra, roverAt, 260.0), from)
                val fx = flakes(scene, camera, view, time, strike = above(terra, roverAt, 2.4))
                shoot("lightning-close-up", terra, camera, view, time, dressed(scene, camera, time) + rover(roverAt, right), scene, lowSun(from, h.heading),
                    flakes = fx, flash = 1f)
            }
            run {
                // A ground strike near the storm from 3 km off, on the frame it lands.
                val weather = Weather(terra, config)
                val found = ArrayList<Strike>()
                weather.strikes(h.centre, time - 1_800.0, time + 1_800.0, found)
                val strike = found.filter { terra.terrain!!.elevation(it.direction) > 5.0 }.minByOrNull { kotlin.math.abs(it.time - time) }
                    ?: error("no ground strike near the storm")
                val side = Vec3().setTo(h.heading).addScaledInPlace(right, 0.6).normalizeInPlace()
                val from = along(terra, strike.direction, side, 3_000.0)
                val camera = above(terra, from, 30.0)
                val scene = clouds(terra, scope, camera, strike.time, tier)
                val view = look(camera, above(terra, strike.direction, 450.0), from)
                val fx = Effects(tier)
                val forward = view.rotate(Vec3(0.0, 0.0, -1.0), Vec3())
                var t = strike.time - 1.0
                while (fx.thunder.isEmpty() || t < strike.time) {
                    fx.thunder.clear()
                    fx.step(1.0 / 30.0, t, terra, Quat.identity(), emptyList(), scene.weather, camera, scene.air, forward); t += 1.0 / 30.0
                    if (t > strike.time + 1.0) error("strike not seen")
                }
                println("  ground strike %.2f energy, %.0f m off".format(strike.energy, strike.direction.copy().mulInPlace(terra.radius).distanceTo(camera)))
                shoot("lightning-ground", terra, camera, view, strike.time, dressed(scene, camera, strike.time), scene, lowSun(from, side),
                    flakes = fx.vertices(Quat.identity(), camera, view, scene.lightScale, t - 1.0 / 30.0, flash = fx.flash), flash = fx.flash)
            }
        } finally {
            scope.cancel(); terrain.stop(); renderer.release(); bus.clear()
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0)
            GL30C.glDeleteFramebuffers(fbo)
            GL30C.glDeleteRenderbuffers(colourBuffer); GL30C.glDeleteRenderbuffers(depthBuffer)
        }
    }

    private companion object {
        val FOV = Math.toRadians(55.0)
    }
}
