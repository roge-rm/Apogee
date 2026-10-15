package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.weather.*
import com.rm.apogee.game.CloudScene
import com.rm.apogee.game.GameSession
import com.rm.apogee.render.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*
import java.io.File

/**
 * Hurricanes drawn: their lobes keep identities across listings and fit LOW's budget, their veil
 * has a clear eye and a solid wall, and the map marks only live ones. With a display, writes
 * pictures from orbit and from under one to build/hurricane-review.
 */
class HurricaneCloudTest {
    private val terra = SolarSystem.defaultSystem().body("terra")
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun all(weather: Weather, time: Double) =
        ArrayList<LiveHurricane>().also { weather.hurricanes(Vec3.unitY(), time, Double.MAX_VALUE, it) }

    /** A strong one over the sea, and when. */
    private val strong: Pair<Double, LiveHurricane> by lazy {
        val w = Weather(terra, config)
        var best: Pair<Double, LiveHurricane>? = null
        var t = 0.0
        while (t < 200 * 3_600.0) {
            for (h in all(w, t)) {
                if (terra.terrain!!.elevation(h.centre) > -300.0) continue
                if (best == null || h.strength > best.second.strength) best = t to h
            }
            t += 900.0
        }
        best ?: error("no hurricane")
    }

    /** A strong supercell over the sea at its best, and when, found through the storms' model. */
    private val supercell: Pair<Double, Vec3> by lazy {
        val w = Weather(terra, config)
        val model = Weather::class.java.declaredMethods.first { it.name.startsWith("getStormModel") }.also { it.isAccessible = true }.invoke(w)
        val around = model.javaClass.methods.first { it.name == "around" }
        val centreAt = model.javaClass.methods.first { it.name == "centreAt" }
        var best: Triple<Double, Vec3, Double>? = null
        for (i in 0 until 400) {
            val lat = (com.rm.apogee.core.terrain.Noise.hash(7, i, 0, 0) - 0.5) * 1.6
            val lon = com.rm.apogee.core.terrain.Noise.hash(7, i, 1, 0) * 2 * Math.PI
            val dir = Vec3(kotlin.math.cos(lat) * kotlin.math.sin(lon), kotlin.math.sin(lat), kotlin.math.cos(lat) * kotlin.math.cos(lon))
            val east = Vec3(dir.z, 0.0, -dir.x).normalizeInPlace(); val north = Vec3().setTo(dir).crossInPlace(east)
            for (t in listOf(1_000.0, 4_000.0, 9_000.0)) {
                val list = ArrayList<Any>()
                around.invoke(model, dir, east, north, 1, t, list)
                for (st in list) {
                    if (st.javaClass.getMethod("getKind").invoke(st) != StormKind.SUPERCELL) continue
                    val strength = st.javaClass.getMethod("getStrength").invoke(st) as Double
                    val at = (st.javaClass.getMethod("getStart").invoke(st) as Double) + 0.45 * 2_400.0
                    val centre = centreAt.invoke(model, st, at, Vec3()) as Vec3
                    if (terra.terrain!!.elevation(centre) > -50.0) continue
                    if (best == null || strength > best.third) best = Triple(at, centre, strength)
                }
            }
        }
        println("supercell strength %.2f".format(best!!.third))
        best!!.first to best!!.second
    }

    private class Lister(val scene: CloudScene) {
        val list = scene.javaClass.getDeclaredMethod("list", Vec3::class.java, java.lang.Double.TYPE,
            java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Boolean.TYPE).also { it.isAccessible = true }
        val merge = scene.javaClass.getDeclaredMethod("merged", List::class.java, List::class.java, Vec3::class.java).also { it.isAccessible = true }
        val field = scene.javaClass.getDeclaredField("lobes").also { it.isAccessible = true }
        fun list(camera: Vec3, time: Double, inner: Double, reach: Double) = list.invoke(scene, camera, time, inner, reach, false) as List<*>
        /** The sky as the scene would publish it, from near and far listings as LOW makes them. */
        fun sky(camera: Vec3, time: Double): List<*> {
            val near = list(camera, time, 0.0, 20_000.0)
            val far = list(camera, time, 14_000.0, 25_000.0)
            return (merge.invoke(scene, near, far, camera) as List<*>).also { field.set(scene, it) }
        }
    }

    private fun value(lobe: Any, name: String) = lobe.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(lobe)

    /** Whether a drawn lobe's [key] belongs to the hurricane shape [shapeKey]. */
    private fun ofShape(key: Long, shapeKey: Long) = ((key and ((1L shl 60) - 1)) ushr 18) == (shapeKey and ((1L shl 42) - 1))

    private fun shapeKey(h: LiveHurricane): Long {
        val shapes = ArrayList<CloudShape>()
        Weather(terra, config).clouds(h.centre, 20_000.0, strong.first, shapes, 70_000.0, 0.45)
        return shapes.first { it.hurricane }.key
    }

    @Test fun lowTierFitsItsBudgetWithAHurricaneOverhead() {
        val (time, h) = strong
        val key = shapeKey(h)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lister = Lister(CloudScene(terra, config, QualityTier.LOW, scope))
        try {
            val rm = h.maxWindRadius
            val places = listOf("eye" to 0.0, "eyewall" to 1.15 * rm, "bands" to 4.0 * rm, "edge" to 0.8 * h.outerRadius)
            for ((name, r) in places) for (altitude in listOf(20.0, 3_000.0, 20_000.0, 40_000.0)) {
                val camera = h.pointAt(r, 0.6, 0.8, terra.radius, Vec3()).mulInPlace(terra.radius + altitude)
                val sky = lister.sky(camera, time).filterNotNull()
                val puffs = sky.filter { !(value(it, "rain") as Boolean) }
                val ours = puffs.count { ofShape(value(it, "key") as Long, key) }
                val curtains = sky.count { (value(it, "rain") as Boolean) && ofShape(value(it, "key") as Long, key) }
                println("LOW at $name, ${altitude.toInt()} m: $ours hurricane lobes, $curtains curtains, ${puffs.size} in all")
                assertTrue("budget kept at $name", puffs.size <= 320)
                assertTrue("hurricane lobes at $name: $ours", ours <= 230)
                assertTrue("curtains at $name: $curtains", curtains <= 16)
                // Nearer storms come first in the budget, so out in the bands they can crowd it out.
                if (altitude < 1_000.0 && r < 2.0 * rm) assertTrue("hurricane drawn at $name", ours > 60)
                if (altitude > 35_000.0) assertEquals("none listed high up, where the veil is", 0, ours)
                assertEquals("unique keys at $name", sky.size, sky.map { value(it, "key") }.toSet().size)
            }
        } finally { scope.cancel() }
    }

    @Test fun nearAndFarListingsAgreeAndAFlightUnderItIsContinuous() {
        val (time, h) = strong
        val key = shapeKey(h)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scene = CloudScene(terra, config, QualityTier.LOW, scope)
        val lister = Lister(scene)
        try {
            val start = h.pointAt(3.0 * h.maxWindRadius, 1.0, 0.0, terra.radius, Vec3())
            val east = Vec3().setTo(h.east)
            val medium = Lister(CloudScene(terra, config, QualityTier.MEDIUM, scope))
            val near = medium.list(Vec3().setTo(start).mulInPlace(terra.radius + 500.0), time, 0.0, 20_000.0).filterNotNull().associateBy { value(it, "key") as Long }
            val far = medium.list(Vec3().setTo(start).mulInPlace(terra.radius + 500.0), time, 14_000.0, 45_000.0).filterNotNull().associateBy { value(it, "key") as Long }
            val shared = near.keys.intersect(far.keys).filter { ofShape(it, key) }
            assertTrue("near and far share hurricane lobes: ${shared.size}", shared.size > 5)
            for (k in shared) {
                assertEquals((value(near.getValue(k), "colour") as FloatArray)[3], (value(far.getValue(k), "colour") as FloatArray)[3], 1e-6f)
                assertTrue((value(near.getValue(k), "centre") as Vec3).distanceTo(value(far.getValue(k), "centre") as Vec3) < 0.001)
            }
            // A plane at 150 m/s, 500 m up, for a minute across the bands, listed each second.
            var previous = emptyMap<Long, RenderItem>()
            var seen = 0
            for (step in 0..600) {
                val seconds = step / 10.0
                val t = time + seconds
                val camera = Vec3().setTo(start).mulInPlace(terra.radius + 500.0).addScaledInPlace(east, 150.0 * seconds)
                if (step % 10 == 0) lister.sky(camera, t)
                val frame = ArrayList<RenderItem>()
                scene.append(Quat.identity(), t, frame, step * 100_000_000L)
                assertEquals("unique identities at $seconds", frame.size, frame.map { it.key }.toSet().size)
                for (item in frame) previous[item.key]?.let { old ->
                    assertEquals("same mesh at $seconds", old.shape, item.shape)
                    assertTrue("no opacity pop at $seconds", kotlin.math.abs(old.color[3] - item.color[3]) < .35f)
                    if (ofShape(item.key, key)) {
                        seen++
                        assertTrue("no jump at $seconds: ${old.position.distanceTo(item.position)} m", old.position.distanceTo(item.position) < 50.0)
                    }
                }
                previous = frame.associateBy { it.key }
            }
            assertTrue("hurricane lobes followed through the flight: $seen", seen > 1_000)
        } finally { scope.cancel() }
    }

    @Test fun veilHasAClearEyeAndASolidWall() {
        val (_, h) = strong
        val veil = HurricaneVeil.build(listOf(h), terra.radius, 1)!!
        val per = HurricaneVeil.SPOKES + 1
        fun ringAlpha(ring: Int) = (0 until HurricaneVeil.SPOKES).map { veil.vertices[(ring * per + it) * CloudShell.STRIDE + 6] }
        // Rings 0 to 3 are inside a third of the eyewall's radius.
        for (ring in 0..3) assertTrue("eye clear at ring $ring", ringAlpha(ring).all { it < 0.02f })
        val wall = (0..HurricaneVeil.RINGS).minByOrNull { kotlin.math.abs(h.outerRadius * Math.pow(it / HurricaneVeil.RINGS.toDouble(), 1.5) - 1.15 * h.maxWindRadius) }!!
        assertTrue("wall solid at ring $wall", ringAlpha(wall).all { it > 0.8f * h.wet })
        // Bands: round a ring well out, thick and thin by turns.
        val band = ringAlpha(HurricaneVeil.RINGS * 2 / 3)
        assertTrue("bands vary round: ${band.minOrNull()} to ${band.maxOrNull()}", band.max() - band.min() > 0.3f)
        assertNull(HurricaneVeil.build(emptyList(), terra.radius, 2))
    }

    @Test fun theMapMarksLiveHurricanesOnly() {
        val (time, h) = strong
        val icons = ArrayList<GameSession.MapIcon>()
        GameSession.hurricaneIcons(all(Weather(terra, config), time), Quat.identity(), terra.radius, icons)
        assertTrue(icons.all { it.kind == GameSession.IconKind.HURRICANE })
        assertTrue("one at the strong one", icons.count { it.at.distanceTo(Vec3().setTo(h.centre).mulInPlace(terra.radius)) < 1.0 } == 1)
        assertEquals(h.turn < 0.0, icons.first { it.at.distanceTo(Vec3().setTo(h.centre).mulInPlace(terra.radius)) < 1.0 }.mirrored)
        // A dead or not yet strong one has none.
        val faint = LiveHurricane(1L, h.centre, h.maxWindRadius, h.outerRadius, 0.05, h.heading, h.speed, h.turn)
        icons.clear(); GameSession.hurricaneIcons(listOf(faint), Quat.identity(), terra.radius, icons)
        assertTrue(icons.isEmpty())
        // The scenes list them on a worker: none on Normal, the live ones on Extreme.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val camera = Vec3().setTo(h.centre).mulInPlace(terra.radius + 100.0)
            val normal = CloudScene(terra, WeatherConfig(intensity = WeatherIntensity.NORMAL), QualityTier.LOW, scope)
            val extreme = CloudScene(terra, config, QualityTier.LOW, scope)
            normal.update(camera, time); extreme.update(camera, time)
            val until = System.nanoTime() + 20_000_000_000L
            while (extreme.hurricanes.isEmpty() && System.nanoTime() < until) Thread.sleep(20)
            assertEquals(all(Weather(terra, config), time).map { it.id }.toSet(), extreme.hurricanes.map { it.id }.toSet())
            assertNotNull(extreme.hurricaneVeil)
            assertTrue(normal.hurricanes.isEmpty())
            assertNull(normal.hurricaneVeil)
        } finally { scope.cancel() }
    }

    /** Pictures for a look by eye, written only with a display. */
    @Test fun picturesFromOrbitAndFromBelow() {
        TestGl.current()
        val (time, h) = strong
        val out = File("../build/hurricane-review").apply { mkdirs() }
        val width = 960; val height = 600
        // The test window is small, so draw into a framebuffer of the picture's size.
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
        val renderer = GlRenderer({ QualityTier.LOW }, bus)
        renderer.onSurfaceCreated(); renderer.onSurfaceChanged(width, height)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val radius = terra.radius
            val up = Vec3().setTo(h.centre)
            // Sun high over it, a little to one side.
            val overhead = Vec3().setTo(up).addScaledInPlace(h.east, 0.5).normalizeInPlace()
            val veil = HurricaneVeil.build(all(Weather(terra, config), time), radius, 1)
            val ball = RenderItem(MeshSpec.Sphere(radius), Vec3(), Quat.identity(), floatArrayOf(0.10f, 0.22f, 0.42f, 1f))

            fun look(from: Vec3, at: Vec3, upHint: Vec3): Quat {
                val forward = Vec3().setTo(at).subInPlace(from).normalizeInPlace()
                val q1 = quatFromTo(Vec3(0.0, 0.0, -1.0), forward)
                val current = q1.rotate(Vec3.unitY(), Vec3())
                val wanted = Vec3().setTo(upHint).addScaledInPlace(forward, -(upHint dot forward)).normalizeInPlace()
                return quatFromTo(current, wanted) * q1
            }

            fun shoot(name: String?, camera: Vec3, rotation: Quat, items: List<RenderItem>, veilAlpha: Float, scene: CloudScene?, time: Double = strong.first, sun: Vec3 = overhead): IntArray {
                val altitude = camera.length - radius
                val world = com.rm.apogee.render.WorldView(
                    radius, 70_000.0, 5_600.0, sun, Vec3.unitY(), altitude, Quat.identity(), 6_000.0,
                    fogDistance = scene?.fogDistance ?: com.rm.apogee.render.WorldView.CLEAR_FOG, fogColor = scene?.fogColor ?: floatArrayOf(.75f, .77f, .8f),
                    skyFog = scene?.skyFog ?: 0f, lightScale = scene?.lightScale ?: 1f, time = time,
                    hurricaneVeil = veil, hurricaneVeilAlpha = veilAlpha,
                )
                bus.clear()
                bus.publish(RenderFrame(1, System.nanoTime(), camera, rotation, Math.toRadians(55.0), items, world = world, farItems = listOf(ball), nearestDistance = altitude))
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

            // From orbit, 900 km up and off to one side.
            val orbit = Vec3().setTo(up).mulInPlace(radius + 900_000.0).addScaledInPlace(h.north, -500_000.0)
            shoot("orbit", orbit, look(orbit, Vec3().setTo(up).mulInPlace(radius), h.north), emptyList(), 1f, null)
            // Closer, 250 km up, to see the wall stand up round the eye.
            val close = Vec3().setTo(up).mulInPlace(radius + 250_000.0).addScaledInPlace(h.north, -200_000.0)
            shoot("orbit-close", close, look(close, Vec3().setTo(up).mulInPlace(radius), h.north), emptyList(), 1f, null)
            val scene = CloudScene(terra, config, QualityTier.MEDIUM, scope)
            val lister = Lister(scene)

            /**
             * From [above] metres over [ground] (unit) at [time], level toward [toward] and tilted
             * up by [pitch] degrees. Returns how much of the picture is cloud, 0..1.
             */
            fun from(name: String, ground: Vec3, toward: Vec3, pitch: Double, above: Double, time: Double): Double {
                val sea = maxOf(terra.terrain!!.elevation(ground), 0.0)
                val camera = Vec3().setTo(ground).mulInPlace(radius + sea + above)
                // The camera's air, for fog and light. Not update(), whose worker listing would race these.
                scene.weather.sample(camera, time, scene.air)
                val near = lister.list(camera, time, 0.0, 30_000.0)
                val far = lister.list(camera, time, 21_000.0, 100_000.0)
                lister.field.set(scene, lister.merge.invoke(scene, near, far, camera))
                // New clouds fade in over half a second, so draw once and again a second later.
                scene.append(Quat.identity(), time, ArrayList(), 0L)
                val items = ArrayList<RenderItem>()
                scene.append(Quat.identity(), time, items, 2_000_000_000L)
                val level = Vec3().setTo(toward).subInPlace(ground).addScaledInPlace(ground, -((Vec3().setTo(toward).subInPlace(ground)) dot ground)).normalizeInPlace()
                val a = Math.toRadians(pitch)
                val target = Vec3().setTo(camera).addScaledInPlace(level, 1_000.0 * kotlin.math.cos(a)).addScaledInPlace(ground, 1_000.0 * kotlin.math.sin(a))
                val rotation = look(camera, target, ground)
                val air = scene.air
                // The sun high over the ground, a little to the east.
                val sun = Vec3().setTo(ground).addScaledInPlace(Vec3(ground.z, 0.0, -ground.x).normalizeInPlace(), 0.5).normalizeInPlace()
                val picture = shoot(name, camera, rotation, items, 0f, scene, time, sun)
                val bare = shoot(null, camera, rotation, emptyList(), 0f, scene, time, sun)
                // Cloud: pixels the empty sky doesn't have.
                var changed = 0
                for (y in 0 until height) for (x in 0 until width) {
                    val p = picture[y * width + x]; val q = bare[y * width + x]
                    val d = maxOf(kotlin.math.abs((p shr 16 and 255) - (q shr 16 and 255)), kotlin.math.abs((p shr 8 and 255) - (q shr 8 and 255)), kotlin.math.abs((p and 255) - (q and 255)))
                    if (d > 20) changed++
                }
                var sum = 0L
                for (p in picture) sum += (p shr 16 and 255) + (p shr 8 and 255) + (p and 255)
                println("$name: ${items.size} items, rain %.2f, cloud %.2f, gloom %.2f, light %.2f, fog %.0f m, ${100 * changed / (width * height)}%% cloud, mean %.0f"
                    .format(air.precipitation, air.cloudDensity, air.gloom, scene.lightScale, scene.fogDistance, sum / (3.0 * width * height)))
                return changed.toDouble() / (width * height)
            }

            val rm = h.maxWindRadius
            // From the sea in the bands, toward the eyewall; from the eye, across it; and from
            // outside the wall, toward it.
            val bands = from("below-bands", h.pointAt(3.5 * rm, 0.0, -1.0, radius, Vec3()), up, 20.0, 30.0, time)
            val eye = from("below-eye", h.pointAt(0.2 * rm, 0.0, -1.0, radius, Vec3()), h.pointAt(1.2 * rm, 0.0, 1.0, radius, Vec3()), 28.0, 30.0, time)
            println("eyewall radius %.0f km, strength %.2f".format(rm / 1e3, h.strength))
            // In the moat, a little over twice the eyewall's radius out.
            from("outside-eyewall", h.pointAt(2.2 * rm, 0.0, -1.0, radius, Vec3()), up, 30.0, 300.0, time)
            assertTrue("cloud in the below-bands picture", bands > 0.2)
            assertTrue("cloud in the below-eye picture", eye > 0.2)

            // An ordinary supercell from the same places: 15 km off toward it, and under it.
            val (cellTime, cell) = supercell
            val off = Vec3().setTo(cell).addScaledInPlace(Vec3(cell.z, 0.0, -cell.x).normalizeInPlace(), -15_000.0 / radius).normalizeInPlace()
            from("supercell", off, cell, 20.0, 30.0, cellTime)
            val under = Vec3().setTo(cell).addScaledInPlace(Vec3(cell.z, 0.0, -cell.x).normalizeInPlace(), -2_000.0 / radius).normalizeInPlace()
            from("supercell-below", under, cell, 28.0, 30.0, cellTime)
            assertEquals(GL_NO_ERROR, glGetError())
        } finally {
            scope.cancel(); renderer.release(); bus.clear()
            org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, 0)
            org.lwjgl.opengl.GL30C.glDeleteFramebuffers(fbo)
            org.lwjgl.opengl.GL30C.glDeleteRenderbuffers(colourBuffer); org.lwjgl.opengl.GL30C.glDeleteRenderbuffers(depthBuffer)
        }
    }
}
