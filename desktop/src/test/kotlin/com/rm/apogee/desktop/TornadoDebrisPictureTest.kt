package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.weather.*
import com.rm.apogee.game.CloudScene
import com.rm.apogee.game.DebrisKind
import com.rm.apogee.game.Effects
import com.rm.apogee.game.TornadoScene
import com.rm.apogee.render.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*
import org.lwjgl.opengl.GL30C
import java.io.File

/**
 * Tornado debris: bits thrown round a land tornado's foot within the tier's budget, none off a
 * waterspout or from far off, the clatter heard only near the foot. With a display, writes
 * pictures to /srv/downloads/temp/debug/apogee/tornado-debris-*.png.
 */
class TornadoDebrisPictureTest {
    private val terra = SolarSystem.defaultSystem().body("terra")
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val radius = terra.radius

    private fun all(weather: Weather, time: Double) =
        ArrayList<LiveTornado>().also { weather.tornadoes(Vec3.unitY(), time, Double.MAX_VALUE, it) }

    /** Every tornado seen over a few hours on Extreme, each in the middle of its life, and when. */
    private val seen: List<Pair<Double, LiveTornado>> by lazy {
        val w = Weather(terra, config)
        val best = LinkedHashMap<Long, Pair<Double, LiveTornado>>()
        var t = 0.0
        while (t < 8 * 3_600.0) {
            for (k in all(w, t)) {
                val f = k.age / k.life
                if (f < 0.3 || f > 0.6) continue
                val old = best[k.id]
                if (old == null || k.strength > old.second.strength) best[k.id] = t to k
            }
            t += 120.0
        }
        best.values.toList()
    }

    private val land by lazy { seen.filter { !it.second.overWater && !it.second.inHurricane }.maxBy { it.second.strength } }
    private val spout by lazy { seen.filter { it.second.overWater }.maxBy { it.second.strength } }

    private fun listed(scene: TornadoScene, camera: Vec3, time: Double) {
        scene.update(camera, time)
        val field = scene.javaClass.getDeclaredField("listing").also { it.isAccessible = true }
        val until = System.nanoTime() + 20_000_000_000L
        while (field.get(scene) as Boolean && System.nanoTime() < until) Thread.sleep(5)
    }

    private fun beside(t: LiveTornado, distance: Double, above: Double): Vec3 {
        val east = Vec3(t.centre.z, 0.0, -t.centre.x).normalizeInPlace()
        val d = Vec3().setTo(t.centre).addScaledInPlace(east, distance / radius).normalizeInPlace()
        return d.mulInPlace(radius + maxOf(terra.terrain!!.elevation(d), 0.0) + above)
    }

    @Test fun debrisKeepsToItsBudgetAndToLandTornadoesNearBy() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (time, t) = land
            for (tier in QualityTier.entries) {
                val scene = TornadoScene(terra, config, tier, scope)
                val camera = beside(t, 1_500.0, 30.0)
                listed(scene, camera, time)
                var most = 0
                for (step in 0..40) {
                    scene.append(camera, Quat.identity(), time + step * 0.1, ArrayList())
                    most = maxOf(most, scene.debris.count)
                }
                println("$tier, 1.5 km from one: $most bits of ${scene.debris.capacity}")
                assertTrue("$tier throws some: $most", most > scene.debris.capacity / 5)
                assertTrue("$tier within its budget: $most", most <= scene.debris.capacity)
                // Heard clattering close, not 3 km off.
                scene.heard(beside(t, 2.0 * t.coreRadius, 10.0), time)
                val close = scene.rattle
                scene.heard(beside(t, 3_000.0 + 3.0 * t.coreRadius, 10.0), time)
                assertTrue("clatter close: $close", close > 0.3)
                assertEquals("none far", 0.0, scene.rattle, 0.0)
            }
            // None from far off, and none off a waterspout.
            val far = TornadoScene(terra, config, QualityTier.HIGH, scope)
            val away = beside(t, 9_000.0, 30.0)
            listed(far, away, time)
            far.append(away, Quat.identity(), time, ArrayList())
            assertEquals("none 9 km off", 0, far.debris.count)
            val (spoutTime, s) = spout
            val sea = TornadoScene(terra, config, QualityTier.HIGH, scope)
            val camera = beside(s, 1_500.0, 30.0)
            listed(sea, camera, spoutTime)
            sea.append(camera, Quat.identity(), spoutTime, ArrayList())
            assertEquals("none off a waterspout", 0, sea.debris.count)
            sea.heard(beside(s, 2.0 * s.coreRadius, 10.0), spoutTime)
            assertEquals("no clatter off a waterspout", 0.0, sea.rattle, 0.0)
        } finally { scope.cancel() }
    }

    @Test fun pictures() {
        TestGl.current()
        val out = File("/srv/downloads/temp/debug/apogee").apply { mkdirs() }
        val width = 960; val height = 600
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
        val renderer = GlRenderer({ QualityTier.MEDIUM }, bus)
        renderer.onSurfaceCreated(); renderer.onSurfaceChanged(width, height)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ball = RenderItem(MeshSpec.Sphere(radius), Vec3(), Quat.identity(), floatArrayOf(0.10f, 0.22f, 0.42f, 1f))

            fun look(from: Vec3, at: Vec3, upHint: Vec3): Quat {
                val forward = Vec3().setTo(at).subInPlace(from).normalizeInPlace()
                val q1 = quatFromTo(Vec3(0.0, 0.0, -1.0), forward)
                val current = q1.rotate(Vec3.unitY(), Vec3())
                val wanted = Vec3().setTo(upHint).addScaledInPlace(forward, -(upHint dot forward)).normalizeInPlace()
                return quatFromTo(current, wanted) * q1
            }

            fun shoot(name: String, camera: Vec3, rotation: Quat, items: List<RenderItem>, scene: CloudScene, time: Double, sun: Vec3, particles: Pair<FloatArray, Int>) {
                val world = WorldView(
                    radius, 70_000.0, 5_600.0, sun, Vec3.unitY(), camera.length - radius, Quat.identity(), 6_000.0,
                    fogDistance = scene.fogDistance, fogColor = scene.fogColor, skyFog = scene.skyFog, lightScale = scene.lightScale, time = time,
                )
                bus.clear()
                bus.publish(RenderFrame(1, System.nanoTime(), camera, rotation, Math.toRadians(55.0), items, world = world, farItems = listOf(ball), nearestDistance = 20.0,
                    particles = particles.first, particleShapes = particles.second))
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
                renderer.onDrawFrame()
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
                val data = BufferUtils.createByteBuffer(width * height * 4)
                glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, data)
                val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                for (y in 0 until height) for (x in 0 until width) {
                    val i = ((height - 1 - y) * width + x) * 4
                    image.setRGB(x, y, ((data.get(i).toInt() and 255) shl 16) or ((data.get(i + 1).toInt() and 255) shl 8) or (data.get(i + 2).toInt() and 255))
                }
                javax.imageio.ImageIO.write(image, "png", File(out, "$name.png"))
                println("wrote ${File(out, "$name.png").canonicalPath}")
            }

            /**
             * [t] (listed at [time]) from [distance] metres off across its track, [above] over the
             * ground, looking at [lookUp] metres up its funnel. The ground a flat disc of
             * [kind]'s colour through its foot.
             */
            fun from(name: String, t: LiveTornado, time: Double, distance: Double, above: Double, lookUp: Double, kind: DebrisKind? = null) {
                val scene = CloudScene(terra, config, QualityTier.MEDIUM, scope)
                scene.tornadoes.kindOverride = kind
                val up = Vec3().setTo(t.centre)
                val groundHeight = maxOf(terra.terrain!!.elevation(t.centre), 0.0)
                val foot = Vec3().setTo(up).mulInPlace(radius + groundHeight)
                val east = Vec3(up.z, 0.0, -up.x).normalizeInPlace()
                val side = listOf(1.0, -1.0).maxBy { k ->
                    scene.weather.sample(Vec3().setTo(foot).addScaledInPlace(east, k * distance).addScaledInPlace(up, above), time, scene.air)
                    scene.air.visibility
                }
                val camera = Vec3().setTo(foot).addScaledInPlace(east, side * distance).addScaledInPlace(up, above)
                val lister = scene.javaClass.getDeclaredMethod("list", Vec3::class.java, java.lang.Double.TYPE,
                    java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Boolean.TYPE).also { it.isAccessible = true }
                val merge = scene.javaClass.getDeclaredMethod("merged", List::class.java, List::class.java, Vec3::class.java).also { it.isAccessible = true }
                val field = scene.javaClass.getDeclaredField("lobes").also { it.isAccessible = true }
                scene.weather.sample(camera, time, scene.air)
                val near = lister.invoke(scene, camera, time, 0.0, 30_000.0, false)
                val far = lister.invoke(scene, camera, time, 21_000.0, 100_000.0, false)
                field.set(scene, merge.invoke(scene, near, far, camera))
                scene.append(Quat.identity(), time, ArrayList(), 0L)
                val clouds = ArrayList<RenderItem>()
                scene.append(Quat.identity(), time, clouds, 2_000_000_000L)
                listed(scene.tornadoes, camera, time)
                val funnel = ArrayList<RenderItem>()
                scene.tornadoes.append(camera, Quat.identity(), time, funnel)
                val discColour = when (kind) {
                    DebrisKind.SAND -> floatArrayOf(0.62f, 0.52f, 0.36f, 1f)
                    DebrisKind.SNOW -> floatArrayOf(0.86f, 0.88f, 0.92f, 1f)
                    else -> floatArrayOf(0.30f, 0.40f, 0.22f, 1f)
                }
                val disc = RenderItem(MeshSpec.Cylinder(60_000.0, 4.0), Vec3().setTo(foot).addScaledInPlace(up, -2.0), quatFromTo(Vec3.unitY(), up), discColour)
                val target = Vec3().setTo(foot).addScaledInPlace(up, lookUp)
                val rotation = look(camera, target, up)
                val sun = Vec3().setTo(up).addScaledInPlace(east, 0.6 * side).addScaledInPlace(Vec3().setTo(up).crossInPlace(east), 0.3).normalizeInPlace()
                val bits = scene.tornadoes.debris.count
                val particles = Effects(QualityTier.MEDIUM).vertices(Quat.identity(), camera, rotation, scene.lightScale, time, debris = scene.tornadoes.debris)
                println("$name: strength %.2f, core %.0f m, $bits bits, ${funnel.size} items".format(t.strength, t.coreRadius))
                shoot(name, camera, rotation, listOf(disc) + clouds + funnel, scene, time, sun, particles)
            }

            val (time, t) = land
            from("tornado-debris-ground", t, time, 1_500.0, 20.0, 250.0)
            // A middling one close up, so its foot fits the picture.
            val (midTime, mid) = seen.filter { !it.second.overWater && !it.second.inHurricane && it.second.strength > 0.5 }
                .minBy { kotlin.math.abs(it.second.coreRadius - 130.0) }
            from("tornado-debris-foot", mid, midTime, 5.0 * mid.coreRadius, 12.0, 90.0)
            from("tornado-debris-air", t, time, 2_200.0, 900.0, 0.0)
            from("tornado-debris-sand", t, time, 1_200.0, 20.0, 200.0, DebrisKind.SAND)
            from("tornado-debris-snow", t, time, 1_200.0, 20.0, 200.0, DebrisKind.SNOW)
            assertEquals(GL_NO_ERROR, glGetError())
        } finally {
            scope.cancel(); renderer.release(); bus.clear()
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0)
            GL30C.glDeleteFramebuffers(fbo)
            GL30C.glDeleteRenderbuffers(colourBuffer); GL30C.glDeleteRenderbuffers(depthBuffer)
        }
    }
}
