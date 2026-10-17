package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.weather.*
import com.rm.apogee.game.CloudScene
import com.rm.apogee.game.GameSession
import com.rm.apogee.game.TornadoScene
import com.rm.apogee.render.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*
import java.io.File

/**
 * Tornadoes drawn: their items keep their keys from frame to frame, move smoothly, fit LOW's
 * budget and stop past the reach, the roar fades out with distance, and the map marks each live
 * one. With a display, writes pictures to build/tornado-review.
 */
class TornadoCloudTest {
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

    /** A scene's near list at [time] for a camera at body-fixed [camera], waiting for its worker. */
    private fun listed(scene: TornadoScene, camera: Vec3, time: Double) {
        scene.update(camera, time)
        val field = scene.javaClass.getDeclaredField("listing").also { it.isAccessible = true }
        val until = System.nanoTime() + 20_000_000_000L
        while (field.get(scene) as Boolean && System.nanoTime() < until) Thread.sleep(5)
    }

    /** Body-fixed, [distance] metres from [t]'s foot across its track, [above] over the ground there. */
    private fun beside(t: LiveTornado, distance: Double, above: Double): Vec3 {
        val east = Vec3(t.centre.z, 0.0, -t.centre.x).normalizeInPlace()
        val d = Vec3().setTo(t.centre).addScaledInPlace(east, distance / radius).normalizeInPlace()
        return d.mulInPlace(radius + maxOf(terra.terrain!!.elevation(d), 0.0) + above)
    }

    @Test fun keysHoldAndItemsMoveSmoothlyWithinLowsBudget() {
        val (time, t) = land
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scene = TornadoScene(terra, config, QualityTier.LOW, scope)
            val camera = beside(t, 3_000.0, 30.0)
            listed(scene, camera, time)
            assertTrue("listed", scene.near.any { it.tornado.id == t.id })
            var previous = emptyMap<Long, RenderItem>()
            var most = 0
            for (step in 0..100) {
                val now = time + step * 0.05
                if (step % 20 == 0) listed(scene, camera, now)
                val items = ArrayList<RenderItem>()
                scene.append(camera, Quat.identity(), now, items)
                most = maxOf(most, items.size)
                assertEquals("unique keys at $step", items.size, items.map { it.key }.toSet().size)
                assertTrue("drawn at $step", items.isNotEmpty())
                if (previous.isNotEmpty()) assertEquals("same keys at $step", previous.keys, items.map { it.key }.toSet())
                for (item in items) previous[item.key]?.let { old ->
                    assertEquals("same mesh at $step", old.shape, item.shape)
                    assertTrue("no jump at $step, slot ${(item.key ushr 2) and 0xffff}: ${old.position.distanceTo(item.position)} m", old.position.distanceTo(item.position) < 15.0)
                }
                previous = items.associateBy { it.key }
            }
            println("LOW, 3 km from one: $most items")
            assertTrue("one tornado fits: $most", most <= 12)
            // Nothing past the reach.
            val far = TornadoScene(terra, config, QualityTier.LOW, scope)
            val away = beside(t, 0.0, 0.0).also { it.addScaledInPlace(Vec3(t.centre.z, 0.0, -t.centre.x).normalizeInPlace(), 70_000.0) }
            listed(far, away, time)
            val items = ArrayList<RenderItem>()
            far.append(away, Quat.identity(), time, items)
            val key = far.near.firstOrNull { it.tornado.id == t.id }?.key
            val ours = if (key == null) 0 else items.count { item -> (0 until 32).any { RenderItem.cloudKey(key, it) == item.key } }
            assertEquals("none past ${far.reach} m", 0, ours)
        } finally { scope.cancel() }
    }

    @Test fun theRoarRisesNearAndIsGoneAFewKilometresOff() {
        val (time, t) = land
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scene = TornadoScene(terra, config, QualityTier.LOW, scope)
            listed(scene, beside(t, 0.0, 30.0), time)
            var last = Double.MAX_VALUE
            for (d in listOf(300.0, 1_000.0, 2_000.0, 3_000.0, 3_900.0)) {
                scene.heard(beside(t, d, 30.0), time)
                println("roar at ${d.toInt()} m: %.2f, closeness %.2f".format(scene.loudness, scene.closeness))
                assertTrue("quieter further at $d", scene.loudness <= last)
                last = scene.loudness
            }
            scene.heard(beside(t, 300.0, 30.0), time)
            assertTrue("loud near", scene.loudness > 0.3)
            scene.heard(beside(t, 4_500.0, 30.0), time)
            assertEquals("nothing past the reach", 0.0, scene.loudness, 1e-9)
        } finally { scope.cancel() }
    }

    @Test fun theMapMarksLiveTornadoes() {
        val (time, t) = land
        val live = all(Weather(terra, config), time)
        val icons = ArrayList<GameSession.MapIcon>()
        GameSession.tornadoIcons(live, Quat.identity(), radius, icons)
        assertTrue(icons.all { it.kind == GameSession.IconKind.TORNADO })
        assertEquals("one at the strong one", 1, icons.count { it.at.distanceTo(Vec3().setTo(t.centre).mulInPlace(radius)) < 1.0 })
        assertEquals(live.count { it.strength > 0.02 }, icons.size)
        // None on Normal.
        assertTrue(all(Weather(terra, WeatherConfig(intensity = WeatherIntensity.NORMAL)), time).isEmpty())
    }

    /** Pictures for a look by eye, written only with a display. */
    @Test fun pictures() {
        TestGl.current()
        val out = File("../build/tornado-review").apply { mkdirs() }
        val width = 960; val height = 600
        val fbo = org.lwjgl.opengl.GL30C.glGenFramebuffers()
        val colourBuffer = org.lwjgl.opengl.GL30C.glGenRenderbuffers(); val depthBuffer = org.lwjgl.opengl.GL30C.glGenRenderbuffers()
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, fbo)
        org.lwjgl.opengl.GL30C.glBindRenderbuffer(org.lwjgl.opengl.GL30C.GL_RENDERBUFFER, colourBuffer)
        org.lwjgl.opengl.GL30C.glRenderbufferStorage(org.lwjgl.opengl.GL30C.GL_RENDERBUFFER, GL_RGBA8, width, height)
        org.lwjgl.opengl.GL30C.glFramebufferRenderbuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL30C.GL_RENDERBUFFER, colourBuffer)
        org.lwjgl.opengl.GL30C.glBindRenderbuffer(org.lwjgl.opengl.GL30C.GL_RENDERBUFFER, depthBuffer)
        org.lwjgl.opengl.GL30C.glRenderbufferStorage(org.lwjgl.opengl.GL30C.GL_RENDERBUFFER, org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8, width, height)
        org.lwjgl.opengl.GL30C.glFramebufferRenderbuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL_ATTACHMENT, org.lwjgl.opengl.GL30C.GL_RENDERBUFFER, depthBuffer)
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

            fun shoot(name: String?, camera: Vec3, rotation: Quat, items: List<RenderItem>, scene: CloudScene, time: Double, sun: Vec3): IntArray {
                val altitude = camera.length - radius
                val world = WorldView(
                    radius, 70_000.0, 5_600.0, sun, Vec3.unitY(), altitude, Quat.identity(), 6_000.0,
                    fogDistance = scene.fogDistance, fogColor = scene.fogColor, skyFog = scene.skyFog, lightScale = scene.lightScale, time = time,
                )
                bus.clear()
                bus.publish(RenderFrame(1, System.nanoTime(), camera, rotation, Math.toRadians(55.0), items, world = world, farItems = listOf(ball), nearestDistance = 20.0))
                org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, fbo)
                renderer.onDrawFrame()
                org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, fbo)
                val data = BufferUtils.createByteBuffer(width * height * 4)
                glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, data)
                val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                for (y in 0 until height) for (x in 0 until width) {
                    val i = ((height - 1 - y) * width + x) * 4
                    image.setRGB(x, y, ((data.get(i).toInt() and 255) shl 16) or ((data.get(i + 1).toInt() and 255) shl 8) or (data.get(i + 2).toInt() and 255))
                }
                if (name != null) {
                    javax.imageio.ImageIO.write(image, "png", File(out, "$name.png"))
                    println("wrote ${File(out, "$name.png").canonicalPath}")
                }
                return image.getRGB(0, 0, width, height, null, 0, width)
            }

            /**
             * [t] (listed at [time]) from [distance] metres off across its track, 30 m over the
             * ground, looking at its middle. The ground is a flat disc through its foot, green over
             * land, blue over the sea. Returns how much of the picture the tornado is, 0..1.
             */
            fun from(name: String, id: Long, time: Double, distance: Double): Double {
                val t = all(Weather(terra, config), time).first { it.id == id }
                val scene = CloudScene(terra, config, QualityTier.MEDIUM, scope)
                val up = Vec3().setTo(t.centre)
                val groundHeight = maxOf(terra.terrain!!.elevation(t.centre), 0.0)
                val foot = Vec3().setTo(up).mulInPlace(radius + groundHeight)
                val east = Vec3(up.z, 0.0, -up.x).normalizeInPlace()
                // On the disc's plane, so the camera stands on the drawn ground.
                // From whichever side has the clearer air.
                val side = listOf(1.0, -1.0).maxBy { k ->
                    scene.weather.sample(Vec3().setTo(foot).addScaledInPlace(east, k * distance).addScaledInPlace(up, 30.0), time, scene.air)
                    scene.air.visibility
                }
                val camera = Vec3().setTo(foot).addScaledInPlace(east, side * distance).addScaledInPlace(up, 30.0)
                val bodyFixed = Vec3().setTo(camera)
                // The storm's clouds over it, as the scene lists them.
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
                listed(scene.tornadoes, bodyFixed, time)
                val funnel = ArrayList<RenderItem>()
                scene.tornadoes.append(bodyFixed, Quat.identity(), time, funnel)
                val disc = RenderItem(MeshSpec.Cylinder(60_000.0, 4.0), Vec3().setTo(foot).addScaledInPlace(up, -2.0), quatFromTo(Vec3.unitY(), up),
                    if (t.overWater) floatArrayOf(0.12f, 0.26f, 0.40f, 1f) else floatArrayOf(0.30f, 0.40f, 0.22f, 1f))
                val target = Vec3().setTo(foot).addScaledInPlace(up, 450.0)
                val rotation = look(camera, target, up)
                // The sun high and a little behind the camera.
                val sun = Vec3().setTo(up).addScaledInPlace(east, 0.6 * side).addScaledInPlace(Vec3().setTo(up).crossInPlace(east), 0.3).normalizeInPlace()
                val picture = shoot(name, camera, rotation, listOf(disc) + clouds + funnel, scene, time, sun)
                val bare = shoot(null, camera, rotation, listOf(disc) + clouds, scene, time, sun)
                // The funnel alone over the ground, to judge its shape without the storm round it.
                if (name == "land-near") shoot("$name-funnel-only", camera, rotation, listOf(disc) + funnel, scene, time, sun)
                var changed = 0
                for (i in picture.indices) {
                    val p = picture[i]; val q = bare[i]
                    val d = maxOf(kotlin.math.abs((p shr 16 and 255) - (q shr 16 and 255)), kotlin.math.abs((p shr 8 and 255) - (q shr 8 and 255)), kotlin.math.abs((p and 255) - (q and 255)))
                    if (d > 12) changed++
                }
                println("$name: strength %.2f, core %.0f m, %s, life %.2f, ${funnel.size} items, ${clouds.size} cloud lobes, ${100.0 * changed / picture.size}%% tornado, fog %.0f m"
                    .format(t.strength, t.coreRadius, if (t.overWater) "water" else "land", t.age / t.life, scene.fogDistance))
                return changed.toDouble() / picture.size
            }

            val (landTime, landOne) = land
            val nearShot = from("land-near", landOne.id, landTime, 3_000.0)
            val farShot = from("land-far", landOne.id, landTime, 15_000.0)
            val (spoutTime, spoutOne) = spout
            val spoutShot = from("waterspout", spoutOne.id, spoutTime, 2_500.0)
            // The same land one roping out, near the end of its life.
            val ropeTime = landTime + 0.86 * landOne.life - landOne.age
            val ropeShot = from("roping-out", landOne.id, ropeTime, 3_000.0)
            // Measured 2026-10-17: near 0.0119, far 0.000167 of the picture.
            assertTrue("near one shows: $nearShot", nearShot > 0.008)
            assertTrue("far one shows, smaller: $farShot", farShot > 0.00008 && farShot < nearShot)
            assertTrue("waterspout shows: $spoutShot", spoutShot > 0.005)
            assertTrue("rope shows: $ropeShot", ropeShot > 0.002)

            // The map icon, large, as the map draws it.
            val icon = java.awt.image.BufferedImage(160, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = icon.createGraphics()
            g.color = java.awt.Color(0x10, 0x18, 0x28); g.fillRect(0, 0, 160, 160)
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            val glyph = GameSession.TORNADO_GLYPH
            for (pass in 0..1) {
                g.stroke = java.awt.BasicStroke(if (pass == 0) 14f else 8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g.color = if (pass == 0) java.awt.Color(0, 0, 0, 140) else java.awt.Color(217, 224, 242)
                for (k in 0 until glyph.size / 4) {
                    g.drawLine((80 + glyph[4 * k] * 70).toInt(), (80 + glyph[4 * k + 1] * 70).toInt(), (80 + glyph[4 * k + 2] * 70).toInt(), (80 + glyph[4 * k + 3] * 70).toInt())
                }
            }
            g.dispose()
            javax.imageio.ImageIO.write(icon, "png", File(out, "map-icon.png"))
            println("wrote ${File(out, "map-icon.png").canonicalPath}")
            assertEquals(GL_NO_ERROR, glGetError())
        } finally {
            scope.cancel(); renderer.release(); bus.clear()
            org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, 0)
            org.lwjgl.opengl.GL30C.glDeleteFramebuffers(fbo)
            org.lwjgl.opengl.GL30C.glDeleteRenderbuffers(colourBuffer); org.lwjgl.opengl.GL30C.glDeleteRenderbuffers(depthBuffer)
        }
    }
}
