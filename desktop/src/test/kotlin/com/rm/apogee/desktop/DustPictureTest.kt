package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.weather.*
import com.rm.apogee.game.CloudScene
import com.rm.apogee.game.DustScene
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
 * Dust storms by eye: a haboob coming from the ground, from the air and from inside it, broad
 * dust on a windy day, and a world storm on Rubra from its surface, from orbit and from far
 * off. Writes pictures to build/dust-review.
 */
class DustPictureTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")
    private val luna = system.body("luna")
    private val rubra = system.body("rubra")
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun along(body: CelestialBody, c: Vec3, heading: Vec3, metres: Double) =
        Vec3().setTo(c).addScaledInPlace(heading, metres / body.radius).normalizeInPlace()
    private fun above(body: CelestialBody, d: Vec3, height: Double) =
        Vec3().setTo(d).mulInPlace(body.radius + body.terrain!!.elevation(d).let { if (body.terrain!!.hasOcean) maxOf(it, 0.0) else it } + height)

    /** A strong haboob over desert part way through its life, and when. */
    private fun haboob(): Pair<Double, LiveDust> {
        val w = Weather(terra, config)
        var t = 6 * 3_600.0
        while (t < 200 * 3_600.0) {
            val live = ArrayList<LiveDust>()
            w.dustStorms(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (d in live) {
                if (d.kind != DustKind.HABOOB || d.strength < 0.6 || d.age < 1_200.0 || d.age > 0.6 * d.life) continue
                if (listOf(-3_000.0, 0.0, 25_000.0).any { w.dustiness(along(terra, d.centre, d.heading, it)) < 0.9 }) continue
                return t to d
            }
            t += 1_800.0
        }
        error("no haboob over desert")
    }

    /** A dry place and time with broad windy-day dust and no haboob near, and the way the wind goes. */
    private fun windy(): Triple<Double, Vec3, Double> {
        val w = Weather(terra, config)
        val air = AirSample()
        val sample = AirSample()
        val n = 600
        var t = 9 * 3_600.0
        var best: Triple<Double, Vec3, Double>? = null
        while (t < 400 * 3_600.0) {
            val live = ArrayList<LiveDust>()
            w.dustStorms(Vec3.unitY(), t, Double.MAX_VALUE, live)
            for (i in 0 until n) {
                val y = 1.0 - 2.0 * (i + 0.5) / n
                val r = kotlin.math.sqrt(1.0 - y * y)
                val a = i * 2.399963
                val d = Vec3(r * kotlin.math.cos(a), y, r * kotlin.math.sin(a))
                if (w.dustiness(d) < 0.9) continue
                if (live.any { it.kind == DustKind.HABOOB && it.centre.distanceTo(d) * terra.radius < 200_000.0 }) continue
                w.sample(above(terra, d, 2.0), t, air)
                if (air.dust < 0.03 || air.dust > 0.5 || (best != null && air.dust <= best.third)) continue
                // No storm cloud near, so the haze is seen alone.
                val east = Vec3(d.z, 0.0, -d.x).normalizeInPlace()
                val north = Vec3().setTo(d).crossInPlace(east)
                val cloudy = (0 until 9).any { k ->
                    val a = k * Math.PI / 4
                    val p = if (k == 8) Vec3().setTo(d) else Vec3().setTo(d).addScaledInPlace(east, kotlin.math.cos(a) * 25_000.0 / terra.radius)
                        .addScaledInPlace(north, kotlin.math.sin(a) * 25_000.0 / terra.radius).normalizeInPlace()
                    (1..4).any { h -> w.sample(above(terra, p, h * 2_000.0), t, sample); sample.cloudDensity > 0.0 }
                }
                if (cloudy) continue
                if (air.dust in 0.03..0.5 && (best == null || air.dust > best.third)) best = Triple(t, d, air.dust)
            }
            if (best != null && best.third > 0.1) return best
            t += 3 * 3_600.0
        }
        return best ?: error("no windy dust")
    }

    /** When a world storm on Rubra is well spread, and a place under it. */
    private fun worldStorm(): Pair<Double, Vec3> {
        val w = Weather(rubra, config)
        var t = 0.0
        while (t < 400 * 86_400.0) {
            val live = ArrayList<LiveDust>()
            w.dustStorms(Vec3.unitY(), t, Double.MAX_VALUE, live)
            val s = live.firstOrNull { it.kind == DustKind.WORLD }
            if (s != null && s.age > 0.45 * s.life && s.age < 0.6 * s.life) {
                val n = 400
                for (i in 0 until n) {
                    val y = 1.0 - 2.0 * (i + 0.5) / n
                    val r = kotlin.math.sqrt(1.0 - y * y)
                    val a = i * 2.399963
                    val d = Vec3(r * kotlin.math.cos(a), y, r * kotlin.math.sin(a))
                    if (kotlin.math.abs(y) < 0.5 && w.worldDustCover(d, t) > 0.8) return t to d
                }
            }
            t += 3 * 3_600.0
        }
        error("no world storm on Rubra")
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
        while (t < time) { scene.update(camera, t); scene.dust.settle(); t += 1.0 / 30.0 }
        scene.update(camera, time); scene.dust.settle(); scene.settle()
        scene.update(camera, time); scene.dust.settle(); scene.settle()
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
        val out = File("../build/dust-review").apply { mkdirs() }
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
                scene: CloudScene?, sun: Vec3, veilAlpha: Float = 0f, farGlobes: List<FarGlobe> = emptyList(), dust: Boolean = true,
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
                        dustFog = if (dust) scene?.dustFog ?: 0.0 else 0.0, dustTop = scene?.dustTop ?: 0.0,
                        seaReach = if (sea != null && sea.built) sea.reach else 0.0, tide = here?.tide ?: 0.0, sea = sea?.latest,
                        seaRadius = if (body.terrain!!.hasOcean) body.radius + (here?.tide ?: 0.0) else 0.0,
                        dustVeil = if (veilAlpha > 0f) scene?.dust?.veil else null, dustVeilAlpha = veilAlpha,
                        sky = SkyColours.of(body.id),
                    )
                    return RenderFrame(1, System.nanoTime(), camera, rotation, FOV, items, world = world, farGlobes = farGlobes, nearestDistance = 4.0)
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

            /** The scene's dust (wall or haze puffs) and the storm clouds round [camera]. */
            fun dressed(scene: CloudScene, camera: Vec3, time: Double): List<RenderItem> {
                val dust = ArrayList<RenderItem>(); scene.dust.append(camera, Quat.identity(), time, dust)
                val clouds = cloudsOf(scene, camera, time)
                println("  ${dust.size} dust items, ${clouds.size} clouds, haze %.2f sight %.0f m fog %.0f m sky %.2f light %.2f".format(
                    scene.dust.haze, scene.dust.sight, scene.fogDistance, scene.skyFog, scene.lightScale))
                return clouds + dust
            }

            // A haboob.
            val (time, d) = haboob()
            println("haboob: strength %.2f, %.0f m tall, %.0f km wide, %.1f m/s, age %.0f s".format(d.strength, d.height, 2 * d.halfWidth / 1000, d.speed, d.age))
            val right = Vec3().setTo(d.heading).crossInPlace(d.centre).normalizeInPlace()
            fun sunFor(up: Vec3, toward: Vec3) = Vec3().setTo(up).mulInPlace(0.55).addScaledInPlace(toward, -0.6).addScaledInPlace(right, 0.45).normalizeInPlace()
            run {
                val from = along(terra, d.centre, d.heading, 15_000.0)
                val camera = above(terra, from, 20.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val target = above(terra, d.centre, 900.0)
                val back = Vec3().setTo(d.heading).mulInPlace(-1.0)
                shoot("dust-haboob-ground", terra, camera, look(camera, target, from), time, dressed(scene, camera, time), scene, sunFor(from, back))
            }
            run {
                // Close, as it nears: 4 km ahead of the face.
                val from = along(terra, d.centre, d.heading, 4_000.0)
                val camera = above(terra, from, 20.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val target = above(terra, d.centre, 700.0)
                shoot("dust-haboob-near", terra, camera, look(camera, target, from), time, dressed(scene, camera, time), scene,
                    sunFor(from, Vec3().setTo(d.heading).mulInPlace(-1.0)))
            }
            run {
                val from = along(terra, d.centre, d.heading, 12_000.0).let { Vec3().setTo(it).addScaledInPlace(right, 6_000.0 / terra.radius).normalizeInPlace() }
                val camera = above(terra, from, 2_500.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val target = above(terra, d.centre, 600.0)
                shoot("dust-haboob-air", terra, camera, look(camera, target, from), time, dressed(scene, camera, time), scene,
                    sunFor(from, Vec3().setTo(d.heading).mulInPlace(-1.0)), veilAlpha = scene.dust.veilAlpha(scene.dust.aboveGround))
            }
            run {
                val from = along(terra, d.centre, d.heading, -3_000.0)
                val camera = above(terra, from, 20.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val target = above(terra, along(terra, from, d.heading, 2_000.0), 20.0)
                shoot("dust-haboob-inside", terra, camera, look(camera, target, from), time, dressed(scene, camera, time), scene, sunFor(from, d.heading))
            }
            run {
                // High over it, where the puffs give way to the veil.
                val from = along(terra, d.centre, d.heading, 60_000.0)
                val camera = above(terra, from, 40_000.0)
                val scene = clouds(terra, scope, camera, time, tier)
                val target = above(terra, along(terra, d.centre, d.heading, -10_000.0), 0.0)
                println("  veil ${scene.dust.veil != null}")
                shoot("dust-haboob-high", terra, camera, look(camera, target, from), time, dressed(scene, camera, time), scene,
                    sunFor(from, Vec3().setTo(d.heading).mulInPlace(-1.0)), veilAlpha = scene.dust.veilAlpha(40_000.0))
            }

            // Broad dust on a windy day.
            val (windyTime, place, level) = windy()
            println("windy: dust %.2f".format(level))
            run {
                val camera = above(terra, place, 20.0)
                val scene = clouds(terra, scope, camera, windyTime, tier)
                // Looking the way with the fewest storm clouds in view, so the haze is seen alone.
                val stormClouds = cloudsOf(scene, camera, windyTime)
                val east = Vec3(place.z, 0.0, -place.x).normalizeInPlace()
                val north = Vec3().setTo(place).crossInPlace(east)
                val way = (0 until 12).map { k ->
                    val a = k * Math.PI / 6
                    Vec3().setTo(east).mulInPlace(kotlin.math.cos(a)).addScaledInPlace(north, kotlin.math.sin(a))
                }.minBy { f ->
                    stormClouds.count { c ->
                        val to = Vec3().setTo(c.position).subInPlace(camera)
                        val flat = Vec3().setTo(to).addScaledInPlace(place, -(to dot place))
                        flat.length > 1.0 && (flat dot f) / flat.length > 0.5
                    }
                }
                val target = above(terra, along(terra, place, way, 3_000.0), -60.0)
                shoot("dust-windy-ground", terra, camera, look(camera, target, place), windyTime, dressed(scene, camera, windyTime), scene, sunFor(place, way))
                val high = above(terra, along(terra, place, way, -3_000.0), 1_200.0)
                val hs = clouds(terra, scope, high, windyTime, tier)
                shoot("dust-windy-air", terra, high, look(high, above(terra, along(terra, place, way, 6_000.0), 0.0), place), windyTime,
                    dressed(hs, high, windyTime), hs, sunFor(place, way))

                // A white cloud above the layer, 50 degrees up and 4 km off, keeps its colour mostly;
                // the ground a kilometre off inside the layer is mostly dust.
                println("  windy layer: top %.0f m over the camera, dust fog %.0f m".format(scene.dustTop, scene.dustFog))
                assertTrue("a layer top over the camera", scene.dustTop > 0.0 && scene.dustFog > 0.0)
                val upAt = Vec3().setTo(place).normalizeInPlace()
                val skyward = Vec3().setTo(way).mulInPlace(kotlin.math.cos(Math.toRadians(50.0))).addScaledInPlace(upAt, kotlin.math.sin(Math.toRadians(50.0)))
                val puffAt = Vec3().setTo(camera).addScaledInPlace(skyward, 4_000.0)
                val puff = RenderItem(com.rm.apogee.core.part.MeshSpec.Sphere(250.0), puffAt, Quat.identity(), floatArrayOf(0.95f, 0.95f, 0.97f, 1f))
                val view = look(camera, puffAt, upAt)
                val withDust = shoot("", terra, camera, view, windyTime, listOf(puff), scene, sunFor(place, way))
                val without = shoot("", terra, camera, view, windyTime, listOf(puff), scene, sunFor(place, way), dust = false)
                fun rgb(i: java.awt.image.BufferedImage, x: Int, y: Int) = i.getRGB(x, y).let { intArrayOf(it shr 16 and 255, it shr 8 and 255, it and 255) }
                val a = rgb(withDust, width / 2, height / 2); val b = rgb(without, width / 2, height / 2)
                // How far toward the fog's colour it went, 0..1.
                val fog = scene.fogColor.map { (it * 255).toDouble() }
                val toward = (0..2).sumOf { (a[it] - b[it]) * (fog[it] - b[it]) } / (0..2).sumOf { (fog[it] - b[it]).let { d -> d * d } }.coerceAtLeast(1.0)
                println("  cloud above the layer: ${a.toList()} with dust, ${b.toList()} without, %.0f%% toward the fog".format(100 * toward))
                assertTrue("cloud above the layer keeps its colour mostly: %.2f toward the fog".format(toward), toward < 0.5)
                // The ground 1 km off, along the layer.
                val groundAt = above(terra, along(terra, place, way, 1_000.0), 0.0)
                val down = look(camera, groundAt, upAt)
                val g1 = rgb(shoot("", terra, camera, down, windyTime, emptyList(), scene, sunFor(place, way)), width / 2, height / 2)
                val g0 = rgb(shoot("", terra, camera, down, windyTime, emptyList(), scene, sunFor(place, way), dust = false), width / 2, height / 2)
                val fogColour = scene.fogColor.map { (it * 255).toInt() }
                println("  ground 1 km off: ${g1.toList()} with dust, ${g0.toList()} without, fog ${fogColour}")
            }

            // A world storm on Rubra.
            val (redTime, redPlace) = worldStorm()
            println("rubra: cover %.2f, share %.2f".format(Weather(rubra, config).worldDustCover(redPlace, redTime), Weather(rubra, config).worldDustShare(redTime)))
            run {
                val camera = above(rubra, redPlace, 20.0)
                val scene = clouds(rubra, scope, camera, redTime, tier)
                val way = Vec3().setTo(redPlace).crossInPlace(Vec3.unitY()).normalizeInPlace()
                val target = above(rubra, along(rubra, redPlace, way, 3_000.0), 40.0)
                println("  rubra layer: top %.0f m over the camera, dust fog %.0f m, fog colour %s".format(scene.dustTop, scene.dustFog, scene.fogColor.toList()))
                // The sun low ahead, to see it dimmed.
                shoot("dust-rubra-surface", rubra, camera, look(camera, target, redPlace), redTime, dressed(scene, camera, redTime), scene,
                    Vec3().setTo(redPlace).mulInPlace(0.22).addScaledInPlace(way, 0.97).addScaledInPlace(Vec3().setTo(redPlace).crossInPlace(way), 0.15).normalizeInPlace())
                val clear = CloudScene(rubra, WeatherConfig(intensity = WeatherIntensity.NORMAL), tier, scope).also { it.update(camera, redTime) }
                shoot("dust-rubra-surface-clear", rubra, camera, look(camera, target, redPlace), redTime, emptyList(), clear,
                    Vec3().setTo(redPlace).addScaledInPlace(way, -0.8).normalizeInPlace())
            }
            run {
                val camera = Vec3().setTo(redPlace).mulInPlace(rubra.radius * 3.2)
                val scene = clouds(rubra, scope, camera, redTime, tier)
                println("  rubra veil ${scene.dust.veil != null}, alpha %.2f".format(scene.dust.veilAlpha(camera.length - rubra.radius)))
                val sun = Vec3().setTo(redPlace).addScaledInPlace(Vec3().setTo(redPlace).crossInPlace(Vec3.unitY()).normalizeInPlace(), 0.6).normalizeInPlace()
                shoot("dust-rubra-orbit", rubra, camera, look(camera, Vec3(), Vec3.unitY()), redTime, emptyList(), scene, sun,
                    veilAlpha = scene.dust.veilAlpha(camera.length - rubra.radius))
            }
            run {
                // Rubra as a far globe from high over Terra, with the storm hiding its face.
                val globe = PlanetMesh.buildGlobe(rubra.terrain!!, rubra.radius, 48, rubra.id, blend = true)
                val up = Vec3(0.0, 0.0, 1.0)
                val camera = Vec3().setTo(up).mulInPlace(terra.radius + 3_000_000.0)
                val at = Vec3().setTo(camera).addScaledInPlace(up, 6.0 * rubra.radius)
                val share = Weather(rubra, config).worldDustShare(redTime)
                val sun = Vec3(0.6, 0.3, -0.75).normalizeInPlace()
                for ((name, dust) in listOf("dust-rubra-far" to (0.85 * share).toFloat(), "dust-rubra-far-clear" to 0f)) {
                    val far = FarGlobe(rubra.id, at, Quat.identity(), rubra.radius, true, globe, rim = SkyColours.of(rubra.id).rim, dust = dust)
                    shoot(name, terra, camera, look(camera, at, Vec3.unitY()), redTime, emptyList(), null, sun, farGlobes = listOf(far))
                }
            }

            // The map icon, large, as the map draws it, over a piece of a world storm's ring.
            val icon = java.awt.image.BufferedImage(160, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = icon.createGraphics()
            g.color = java.awt.Color(0x10, 0x18, 0x28); g.fillRect(0, 0, 160, 160)
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = java.awt.BasicStroke(3f)
            g.color = java.awt.Color(242, 179, 115, 90)
            g.drawOval(10, 10, 140, 140)
            val glyph = GameSession.DUST_GLYPH
            for (pass in 0..1) {
                g.stroke = java.awt.BasicStroke(if (pass == 0) 14f else 8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g.color = if (pass == 0) java.awt.Color(0, 0, 0, 140) else java.awt.Color(242, 194, 133)
                for (k in 0 until glyph.size / 4) {
                    g.drawLine((80 + glyph[4 * k] * 60).toInt(), (80 + glyph[4 * k + 1] * 60).toInt(), (80 + glyph[4 * k + 2] * 60).toInt(), (80 + glyph[4 * k + 3] * 60).toInt())
                }
            }
            g.dispose()
            javax.imageio.ImageIO.write(icon, "png", File(out, "dust-map-icon.png"))
            println("wrote ${File(out, "dust-map-icon.png").canonicalPath}")
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
