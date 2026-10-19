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
 * Volcanic ash by eye: an eruption's column from 30 km, its cloud from the air, inside the ash on
 * the ground by a rover, flecks up close in the cloud, the vent's glow by night, and the map icon.
 * Writes pictures to build/ash-review.
 */
class AshPictureTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")
    private val luna = system.body("luna")
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun along(body: CelestialBody, c: Vec3, heading: Vec3, metres: Double) =
        Vec3().setTo(c).addScaledInPlace(heading, metres / body.radius).normalizeInPlace()
    private fun above(body: CelestialBody, d: Vec3, height: Double) =
        Vec3().setTo(d).mulInPlace(body.radius + body.terrain!!.elevation(d).let { if (body.terrain!!.hasOcean) maxOf(it, 0.0) else it } + height)

    /** A strong eruption a couple of hours in on land, its cloud well out, and when. */
    private fun eruption(): Pair<Double, LiveEruption> {
        val w = Weather(terra, config)
        var t = 6 * 3_600.0
        while (t < 600 * 3_600.0) {
            val live = ArrayList<LiveEruption>()
            w.eruptions(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (e in live) {
                if (e.strength < 0.7 || e.age < 5_400.0 || e.age > e.emitting - 1_800.0 || e.length < 80_000.0 || e.drift.length < 5.0) continue
                return t to e
            }
            t += 1_800.0
        }
        error("no eruption")
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
        scene.update(camera, time); scene.settle(); scene.ash.settle()
        // Then a few seconds more, so the light under the cloud the listing found has eased in.
        t = time
        while (t < time + 3.0) { scene.update(camera, t); t += 1.0 / 30.0 }
        scene.ash.settle()
        scene.update(camera, time); scene.settle(); scene.ash.settle()
        scene.update(camera, time)
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
        val out = File("../build/ash-review").apply { mkdirs() }
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
                renderer.debugLegacyTerrainCulling = true
                renderer.onDrawFrame()
                val reference = BufferUtils.createByteBuffer(width * height * 4)
                glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, reference)
                renderer.debugLegacyTerrainCulling = false
                var different = 0
                for (i in 0 until width * height) {
                    if ((0..2).any { c -> kotlin.math.abs((data.get(i * 4 + c).toInt() and 255) - (reference.get(i * 4 + c).toInt() and 255)) > 2 }) different++
                }
                assertTrue("$name: $different pixels differ with terrain culling", different <= width * height / 10_000)
                assertEquals(GL_NO_ERROR, glGetError())
                return image
            }

            /** The clouds and the eruption round [camera], with the sun at [sun]. */
            fun dressed(scene: CloudScene, camera: Vec3, time: Double, sun: Vec3): List<RenderItem> {
                val clouds = cloudsOf(scene, camera, time)
                val ash = ArrayList<RenderItem>()
                scene.ash.append(camera, Quat.identity(), time, ash, sun = sun)
                println("  ${clouds.size} clouds, ${ash.size} ash items, ash %.2f haze %.2f fog %.0f m dust fog %.0f m top %.0f m light %.2f"
                    .format(scene.air.ash, scene.ash.haze, scene.fogDistance, scene.dustFog, scene.dustTop, scene.lightScale))
                return clouds + ash
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


            val (time, e) = eruption()
            println("eruption: strength %.2f, column %.0f m, top %.0f m, vent %.0f m, cloud %.0f km, %.1f m/s".format(
                e.strength, e.columnTop, e.top, e.ventHeight, e.length / 1e3, e.drift.length))
            val downwind = Vec3().setTo(e.drift).normalizeInPlace()
            val right = Vec3().setTo(e.vent).crossInPlace(downwind).normalizeInPlace()
            fun at(ahead: Double, aside: Double) = Vec3().setTo(e.vent).addScaledInPlace(downwind, ahead / terra.radius)
                .addScaledInPlace(right, aside / terra.radius).normalizeInPlace()
            fun over(d: Vec3, height: Double) = Vec3().setTo(d).mulInPlace(terra.radius + height)
            // A sun high behind the camera, so it's day.
            fun sunFor(up: Vec3, toward: Vec3) = Vec3().setTo(up).mulInPlace(0.8).addScaledInPlace(toward, -0.5).addScaledInPlace(right, 0.2).normalizeInPlace()
            fun toward(from: Vec3, to: Vec3) = Vec3().setTo(to).subInPlace(from).normalizeInPlace()
            run {
                // The column from 30 km upwind and to the side, on the ground.
                val from = at(-20_000.0, -22_000.0)
                val camera = above(terra, from, 3.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val target = over(e.vent, e.ventHeight + 0.45 * (e.columnTop - e.ventHeight))
                val sun = sunFor(from, toward(camera, target))
                shoot("ash-column", terra, camera, look(camera, target, from), time, dressed(scene, camera, time, sun), scene, sun)
                // The same by night: the vent's glow.
                val night = Vec3().setTo(from).mulInPlace(-0.9).addScaledInPlace(right, 0.3).normalizeInPlace()
                shoot("ash-night-glow", terra, camera, look(camera, over(e.vent, e.ventHeight + 0.3 * (e.columnTop - e.ventHeight)), from), time,
                    dressed(scene, camera, time, night), scene, night)
            }
            run {
                // The cloud from the air: 5 km up off to its side, 40 km downwind.
                val from = at(40_000.0, -(e.halfWidth + 35_000.0))
                val camera = over(from, 5_000.0 + maxOf(terra.terrain!!.elevation(from), 0.0))
                val scene = clouds(terra, scope, camera, time, tier)
                val target = over(at(70_000.0, 0.0), e.top - 0.3 * (e.top - e.ventHeight))
                val sun = sunFor(from, toward(camera, target))
                shoot("ash-plume-air", terra, camera, look(camera, target, from), time, dressed(scene, camera, time, sun), scene, sun)
            }
            run {
                // On the ground under the cloud where most ash has fallen out of it, by a rover.
                val air = AirSample()
                val w = Weather(terra, config)
                val from = (4 until 60).map { at(e.length * it / 60.0, 0.0) }.filter { terra.terrain!!.elevation(it) > 2.0 }
                    .maxBy { w.sample(above(terra, it, 3.0), time, air); air.ash }
                w.sample(above(terra, from, 3.0), time, air)
                println("  ground ash %.2f, %.0f km from the vent".format(air.ash, from.distanceTo(e.vent) * terra.radius / 1e3))
                val camera = above(terra, from, 3.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val roverAt = Vec3().setTo(from).addScaledInPlace(right, 25.0 / terra.radius).normalizeInPlace()
                val view = look(camera, above(terra, roverAt, 4.0), from)
                val sun = sunFor(from, right)
                shoot("ash-inside-ground", terra, camera, view, time, dressed(scene, camera, time, sun) + rover(roverAt, downwind), scene, sun,
                    flakes = flakes(scene, camera, view, time))
                assertTrue("ash on the ground under the cloud: ${scene.air.ash}", scene.air.ash > 0.02)
            }
            run {
                // In the thick of the cloud 30 km downwind, flecks going past a box 14 m off.
                val air = AirSample()
                val w = Weather(terra, config)
                val camera = (0 until 40).map { over(at(30_000.0, 0.0), e.ventHeight + (e.top - e.ventHeight) * (0.3 + 0.7 * it / 39.0)) }
                    .maxBy { w.sample(it, time, air); air.ash }
                val scene = clouds(terra, scope, camera, time, tier)
                val up = Vec3().setTo(camera).normalizeInPlace()
                val boxAt = Vec3().setTo(camera).addScaledInPlace(right, 14.0).addScaledInPlace(up, -2.0)
                val box = RenderItem(MeshSpec.Box(3.0, 1.2, 6.0), boxAt, quatFromTo(Vec3.unitY(), up), floatArrayOf(0.85f, 0.85f, 0.88f, 1f))
                val view = look(camera, boxAt, up)
                val sun = sunFor(up, right)
                shoot("ash-flecks-close", terra, camera, view, time, dressed(scene, camera, time, sun) + box, scene, sun,
                    flakes = flakes(scene, camera, view, time))
            }

            // The map icon, large, as the map draws it, over a piece of the cloud's outline.
            val icon = java.awt.image.BufferedImage(160, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = icon.createGraphics()
            g.color = java.awt.Color(0x10, 0x18, 0x28); g.fillRect(0, 0, 160, 160)
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = java.awt.BasicStroke(3f)
            g.color = java.awt.Color(199, 184, 168, 100)
            g.drawLine(95, 70, 158, 40); g.drawLine(95, 90, 158, 125)
            val glyph = com.rm.apogee.game.GameSession.ASH_GLYPH
            for (pass in 0..1) {
                g.stroke = java.awt.BasicStroke(if (pass == 0) 14f else 8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g.color = if (pass == 0) java.awt.Color(0, 0, 0, 140) else java.awt.Color(219, 204, 189)
                for (k in 0 until glyph.size / 4) {
                    g.drawLine((80 + glyph[4 * k] * 60).toInt(), (80 + glyph[4 * k + 1] * 60).toInt(), (80 + glyph[4 * k + 2] * 60).toInt(), (80 + glyph[4 * k + 3] * 60).toInt())
                }
            }
            g.dispose()
            javax.imageio.ImageIO.write(icon, "png", File(out, "ash-map-icon.png"))
            println("wrote ${File(out, "ash-map-icon.png").canonicalPath}")
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
