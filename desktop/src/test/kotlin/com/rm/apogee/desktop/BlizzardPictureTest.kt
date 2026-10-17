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
import com.rm.apogee.game.GameSession
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
 * Blizzards by eye: one coming from the ground, a whiteout round a craft, snow lifted off the ice
 * under a clear sky, one from the air and from high up, and the map icon. Writes pictures to
 * build/blizzard-review.
 */
class BlizzardPictureTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")
    private val luna = system.body("luna")
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun along(body: CelestialBody, c: Vec3, heading: Vec3, metres: Double) =
        Vec3().setTo(c).addScaledInPlace(heading, metres / body.radius).normalizeInPlace()
    private fun above(body: CelestialBody, d: Vec3, height: Double) =
        Vec3().setTo(d).mulInPlace(body.radius + body.terrain!!.elevation(d).let { if (body.terrain!!.hasOcean) maxOf(it, 0.0) else it } + height)

    /** A strong blizzard over cold country part way through its life, and when. */
    private fun blizzard(): Pair<Double, LiveBlizzard> {
        val w = Weather(terra, config)
        // Over lying snow if any is, else over any cold country.
        for (snowy in listOf(true, false)) {
        var t = 6 * 3_600.0
        while (t < 300 * 3_600.0) {
            val live = ArrayList<LiveBlizzard>()
            w.blizzards(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (z in live) {
                if (z.strength < 0.6 || z.age < 2_400.0 || z.age > 0.6 * z.life) continue
                if (listOf(-0.5, 0.0, 0.5, 1.4).any { w.blizzardCold(along(terra, z.centre, z.heading, it * z.halfLength)) < 0.95 }) continue
                // Dry land ahead, so the camera stands on the ground.
                if (terra.terrain!!.elevation(along(terra, z.centre, z.heading, 1.4 * z.halfLength)) < 5.0) continue
                if (snowy && listOf(0.0, 1.4).any { w.snowCover(along(terra, z.centre, z.heading, it * z.halfLength)) < 0.7 }) continue
                return t to z
            }
            t += 1_800.0
        }
        }
        error("no blizzard over cold country")
    }

    /** Snow lifted off ice or snow by the wind with no storm near and a clear sky, and the wind's way. */
    private fun groundBlizzard(): Triple<Double, Vec3, Vec3> {
        val w = Weather(terra, config)
        val air = AirSample()
        val sample = AirSample()
        val n = 3_000
        var t = 3 * 3_600.0
        while (t < 400 * 3_600.0) {
            val live = ArrayList<LiveBlizzard>()
            w.blizzards(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (i in 0 until n) {
                val y = 1.0 - 2.0 * (i + 0.5) / n
                if (kotlin.math.abs(y) < 0.8) continue
                val r = kotlin.math.sqrt(1.0 - y * y)
                val a = i * 2.399963
                val d = Vec3(r * kotlin.math.cos(a), y, r * kotlin.math.sin(a))
                if (w.snowCover(d) < 0.8) continue
                if (live.any { it.centre.distanceTo(d) * terra.radius < it.halfLength + 150_000.0 }) continue
                w.sample(above(terra, d, 2.0), t, air)
                if (air.snow < 0.35 || air.snowTop > 400.0) continue
                if ((1..8).any { h -> w.sample(above(terra, d, h * 1_500.0), t, sample); sample.cloudDensity > 0.0 }) continue
                return Triple(t, d, air.wind.copy().addScaledInPlace(d, -(air.wind dot d)).normalizeInPlace())
            }
            t += 3 * 3_600.0
        }
        error("no snow lifted under a clear sky")
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
        while (t < time) { scene.update(camera, t); scene.snow.settle(); t += 1.0 / 30.0 }
        scene.update(camera, time); scene.snow.settle(); scene.settle()
        scene.update(camera, time); scene.snow.settle(); scene.settle()
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
        val out = File("../build/blizzard-review").apply { mkdirs() }
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
                scene: CloudScene?, sun: Vec3, veilAlpha: Float = 0f, flakes: Pair<FloatArray, Int>? = null, shadowAt: Vec3? = null,
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
                        flatLight = scene?.flatLight ?: 0f,
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

            /** The scene's curtains and the storm clouds round [camera]. */
            fun dressed(scene: CloudScene, camera: Vec3, time: Double): List<RenderItem> {
                val snow = ArrayList<RenderItem>(); scene.snow.append(camera, Quat.identity(), time, snow)
                val clouds = cloudsOf(scene, camera, time)
                println("  ${snow.size} snow items, ${clouds.size} clouds, haze %.2f sight %.0f m fog %.0f m layer fog %.0f top %.0f flat %.2f light %.2f".format(
                    scene.snow.haze, scene.snow.sight, scene.fogDistance, scene.dustFog, scene.dustTop, scene.flatLight, scene.lightScale))
                return clouds + snow
            }

            /** Driven snow round [camera] looking along [rotation], as the game makes it over two seconds. */
            fun flakes(scene: CloudScene, camera: Vec3, rotation: Quat, time: Double): Pair<FloatArray, Int> {
                val fx = Effects(tier)
                val forward = rotation.rotate(Vec3(0.0, 0.0, -1.0), Vec3())
                var t = time - 2.0
                while (t < time) { fx.step(1.0 / 30.0, t, terra, Quat.identity(), emptyList(), scene.weather, camera, scene.air, forward); t += 1.0 / 30.0 }
                println("  ${fx.particleCount} flakes")
                return fx.vertices(Quat.identity(), camera, rotation, scene.lightScale, time)
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

            val (time, z) = blizzard()
            println("blizzard: strength %.2f, %.0f by %.0f km, %.0f m tall, %.1f m/s, age %.0f s".format(
                z.strength, 2 * z.halfLength / 1000, 2 * z.halfWidth / 1000, z.height, z.speed, z.age))
            val right = Vec3().setTo(z.heading).crossInPlace(z.centre).normalizeInPlace()
            // The sun behind the camera and to one side, fairly low: a winter sun.
            fun sunFor(up: Vec3, toward: Vec3) = Vec3().setTo(up).mulInPlace(0.6).addScaledInPlace(toward, -0.7).addScaledInPlace(right, 0.5).normalizeInPlace()
            val back = Vec3().setTo(z.heading).mulInPlace(-1.0)
            run {
                // From the ground ahead of it as it comes.
                val from = along(terra, z.centre, z.heading, 1.15 * z.halfLength)
                val camera = above(terra, from, 20.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val target = above(terra, z.centre, 1_200.0)
                shoot("blizzard-approaching", terra, camera, look(camera, target, from), time, dressed(scene, camera, time), scene, sunFor(from, back))
            }
            run {
                // Inside, near a rover: whiteout, flat light.
                val from = z.centre
                val camera = above(terra, from, 3.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val roverAt = along(terra, from, z.heading, 14.0)
                val view = look(camera, above(terra, roverAt, 1.0), from)
                val air = scene.air
                println("  inside: snow %.2f wind %.1f m/s sight %.0f m".format(air.snow, air.wind.length, air.visibility))
                shoot("blizzard-inside", terra, camera, view, time, dressed(scene, camera, time) + rover(roverAt, right), scene, sunFor(from, z.heading),
                    flakes = flakes(scene, camera, view, time), shadowAt = above(terra, roverAt, 1.0))
            }
            run {
                // From the air, beside it.
                // Beside it, out of cloud: the first side and height with clear air.
                val w = Weather(terra, config); val probe = AirSample()
                val (from, camera) = listOf(1.0, -1.0).flatMap { side -> listOf(1_200.0, 700.0, 400.0).map { side to it } }.map { (side, h) ->
                    val f = along(terra, z.centre, Vec3().setTo(right).mulInPlace(side), 1.3 * z.halfWidth + 20_000.0)
                    f to above(terra, f, h)
                }.firstOrNull { (f, c) ->
                    // Clear air there and no cloud close overhead, so the shot isn't under a storm's base.
                    w.sample(c, time, probe); probe.cloudDensity <= 0.0 &&
                        (1..8).none { k -> w.sample(above(terra, f, k * 500.0), time, probe); probe.cloudDensity > 0.0 }
                } ?: error("no clear air beside it")
                val scene = clouds(terra, scope, camera, time, tier)
                val target = above(terra, z.centre, 500.0)
                shoot("blizzard-air", terra, camera, look(camera, target, from), time, dressed(scene, camera, time), scene,
                    sunFor(from, Vec3().setTo(z.centre).subInPlace(from)), veilAlpha = scene.snow.veilAlpha(scene.snow.height))
            }
            run {
                // High over it, where the curtains give way to the veil.
                val from = along(terra, z.centre, z.heading, 90_000.0)
                val camera = above(terra, from, 40_000.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val target = above(terra, z.centre, 0.0)
                println("  veil ${scene.snow.veil != null}")
                shoot("blizzard-high", terra, camera, look(camera, target, from), time, dressed(scene, camera, time), scene,
                    sunFor(from, back), veilAlpha = scene.snow.veilAlpha(40_000.0))
            }

            // Snow lifted off the ice under a clear sky.
            val (groundTime, place, wind) = groundBlizzard()
            run {
                val camera = above(terra, place, 2.0)
                val scene = clouds(terra, scope, camera, groundTime, tier)
                // Looking across the wind, a little up, so the low snow and the sky over it both show,
                // the side with the fewest clouds in view.
                val stormClouds = cloudsOf(scene, camera, groundTime)
                val across = listOf(1.0, -1.0).map { k -> Vec3().setTo(wind).crossInPlace(place).normalizeInPlace().mulInPlace(k) }.minBy { f ->
                    stormClouds.count { c -> Vec3().setTo(c.position).subInPlace(camera).let { (it dot f) / it.length > 0.3 } }
                }
                val target = above(terra, along(terra, place, across, 1_000.0), 380.0)
                val view = look(camera, target, place)
                println("ground blizzard: snow %.2f top %.0f m wind %.1f m/s".format(scene.air.snow, scene.air.snowTop, scene.air.wind.length))
                shoot("blizzard-ground", terra, camera, view, groundTime, dressed(scene, camera, groundTime), scene,
                    Vec3().setTo(place).mulInPlace(0.35).addScaledInPlace(across, -0.6).addScaledInPlace(wind, 0.4).normalizeInPlace(),
                    flakes = flakes(scene, camera, view, groundTime))
                assertTrue("a low layer, sky clear over it", scene.dustTop in 0.0..500.0)
            }

            // The map icon, large, as the map draws it, over a piece of its oval.
            val icon = java.awt.image.BufferedImage(160, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = icon.createGraphics()
            g.color = java.awt.Color(0x10, 0x18, 0x28); g.fillRect(0, 0, 160, 160)
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = java.awt.BasicStroke(3f)
            g.color = java.awt.Color(209, 230, 255, 90)
            g.drawOval(4, 24, 152, 112)
            val glyph = GameSession.BLIZZARD_GLYPH
            for (pass in 0..1) {
                g.stroke = java.awt.BasicStroke(if (pass == 0) 12f else 6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g.color = if (pass == 0) java.awt.Color(0, 0, 0, 140) else java.awt.Color(224, 240, 255)
                for (k in 0 until glyph.size / 4) {
                    g.drawLine((80 + glyph[4 * k] * 60).toInt(), (80 + glyph[4 * k + 1] * 60).toInt(), (80 + glyph[4 * k + 2] * 60).toInt(), (80 + glyph[4 * k + 3] * 60).toInt())
                }
            }
            g.dispose()
            javax.imageio.ImageIO.write(icon, "png", File(out, "blizzard-map-icon.png"))
            println("wrote ${File(out, "blizzard-map-icon.png").canonicalPath}")
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
