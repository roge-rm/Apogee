package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.terrain.TerrainField
import com.rm.apogee.core.weather.*
import com.rm.apogee.game.GameSession
import com.rm.apogee.game.SeaScene
import com.rm.apogee.game.TerrainBuilder
import com.rm.apogee.game.TsunamiScene
import com.rm.apogee.render.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*
import org.lwjgl.opengl.GL30C
import java.io.File

/**
 * Tsunamis drawn and heard by the Cape: the field is there near one and gone far off, the map
 * marks each live one with its front, the roar falls off with distance, and flooded land and the
 * drawn-back bed are drawn without holes at the coast. With a display, writes pictures to
 * build/tsunami-review.
 */
class TsunamiSceneTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")
    private val luna = system.body("luna")
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val radius = terra.radius
    private val field = terra.terrain as TerrainField
    private val berth = field.worksDirection(2_700.0, 330.0).normalizeInPlace()

    /** The biggest tsunami at the Cape berth over a few weeks on Extreme, with its times there. */
    private class Event(val tsunami: LiveTsunami, val arrives: Double, val period: Double, val high: Double) {
        /** When the sea is drawn furthest back at the berth, and the crest there, from its first cycle. */
        var trough = 0.0
        var crest = 0.0

        /** When low land a little inland is flooded: the flood runs in from the coast. */
        val flood get() = crest + 120.0
    }

    private val event: Event by lazy {
        val w = Weather(terra, config)
        val bed = field.elevation(berth)
        val seen = HashMap<Long, LiveTsunami>()
        val out = ArrayList<LiveTsunami>()
        var t = 0.0
        while (t < 600 * 3_600.0) {
            out.clear()
            w.tsunamis(berth, t, 1e9, out)
            for (k in out) if (!k.arrivesHere.isNaN() && k.arrivesHere < 1e29) seen.putIfAbsent(k.id, k)
            t += 3 * 3_600.0
        }
        val best = seen.values.map { k ->
            var high = 0.0
            var s = k.arrivesHere
            while (s < k.arrivesHere + 2 * k.period) { high = maxOf(high, w.tsunamiHeight(berth, s, bed)); s += 15.0 }
            Event(k, k.arrivesHere, k.period, high)
        }.maxBy { it.high }
        var low = Double.MAX_VALUE; var high = -Double.MAX_VALUE
        var s = best.arrives
        while (s < best.arrives + best.period) {
            val h = w.tsunamiHeight(berth, s, bed)
            if (h < low) { low = h; best.trough = s }
            if (h > high) { high = h; best.crest = s }
            s += 1.0
        }
        println("event ${best.tsunami.id}: strength %.2f, period %.0f s, arrives %.0f s, berth bed %.1f m, crest %.2f m".format(
            best.tsunami.strength, best.period, best.arrives, bed, best.high))
        best
    }

    private fun east(up: Vec3) = Vec3(up.z, 0.0, -up.x).normalizeInPlace()
    private fun north(up: Vec3) = Vec3().setTo(up).crossInPlace(east(up))

    /** Unit direction [x] metres east and [y] north of unit [up]. */
    private fun offset(up: Vec3, x: Double, y: Double) =
        Vec3().setTo(up).addScaledInPlace(east(up), x / radius).addScaledInPlace(north(up), y / radius).normalizeInPlace()

    /** A scene's near working out at [time] for a camera at body-fixed [camera], waiting for its worker. */
    private fun listed(scene: TsunamiScene, camera: Vec3, time: Double) {
        scene.update(camera, time)
        val listing = scene.javaClass.getDeclaredField("listing").also { it.isAccessible = true }
        val until = System.nanoTime() + 30_000_000_000L
        while (listing.get(scene) as Boolean && System.nanoTime() < until) Thread.sleep(5)
    }

    private fun above(up: Vec3, height: Double) = Vec3().setTo(up).mulInPlace(radius + maxOf(field.elevation(up), 0.0) + height)

    @Test fun theFieldIsThereNearOneAndNothingPastTheReach() {
        val e = event
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val near = TsunamiScene(terra, config, QualityTier.LOW, scope)
            listed(near, above(berth, 100.0), e.crest)
            val f = near.field
            assertNotNull("a field at the berth", f)
            var most = 0.0
            for (j in 0 until TsunamiField.SIDE) for (i in 0 until TsunamiField.SIDE) most = maxOf(most, kotlin.math.abs(f!!.height(i, j)))
            println("field: most %.2f m, shore %.2f m, worked out in %.2f ms".format(most, f!!.shore, near.lastFieldMillis))
            assertTrue("moves the sea: $most", most > 0.3)
            // The same scene taken high up drops it.
            listed(near, above(berth, 150_000.0), e.crest + 2.0)
            assertNull("none from 150 km up", near.field)
            // Far inland, none.
            val inland = TsunamiScene(terra, config, QualityTier.LOW, scope)
            listed(inland, above(offset(berth, -200_000.0, 0.0), 100.0), e.crest)
            assertNull("none 200 km inland", inland.field)
            // Long before it comes, none.
            val before = TsunamiScene(terra, config, QualityTier.LOW, scope)
            listed(before, above(berth, 100.0), e.tsunami.start - 600.0)
            assertNull("none before the quake", before.field)
        } finally { scope.cancel() }
    }

    @Test fun theMapMarksEachLiveOneWithItsFront() {
        val e = event
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scene = TsunamiScene(terra, config, QualityTier.LOW, scope)
            val time = e.arrives - 0.5 * (e.arrives - e.tsunami.start)
            scene.forMap(above(berth, 100.0), time)
            val listing = scene.javaClass.getDeclaredField("listing").also { it.isAccessible = true }
            val until = System.nanoTime() + 30_000_000_000L
            while ((listing.get(scene) as Boolean || scene.all.isEmpty()) && System.nanoTime() < until) {
                Thread.sleep(5); scene.forMap(above(berth, 100.0), time)
            }
            val entries = scene.all
            assertTrue("listed", entries.any { it.tsunami.id == e.tsunami.id })
            val icons = ArrayList<GameSession.MapIcon>()
            val lines = ArrayList<RenderLine>()
            GameSession.tsunamiMarks(entries, Quat.identity(), radius, icons, lines)
            assertTrue(icons.all { it.kind == GameSession.IconKind.TSUNAMI })
            assertEquals(entries.size, icons.size)
            val origin = Vec3().setTo(e.tsunami.origin).mulInPlace(radius)
            assertEquals("one where it began", 1, icons.count { it.at.distanceTo(origin) < 1.0 })
            val ours = entries.first { it.tsunami.id == e.tsunami.id }
            assertTrue("a front", ours.front.isNotEmpty() && lines.isNotEmpty())
            // Every front point over the sea, at the front's distance from where it began.
            for (run in ours.front) for (p in run) {
                assertTrue("over the sea", field.elevation(p) < 0.0)
                val d = kotlin.math.acos((p dot e.tsunami.origin).coerceIn(-1.0, 1.0)) * radius
                assertEquals("on the front", ours.tsunami.frontRadius, d, 2_000.0)
            }
            println("map: ${entries.size} live, ${ours.front.size} runs, ${ours.front.sumOf { it.size }} points, front %.0f km".format(ours.tsunami.frontRadius / 1000))
        } finally { scope.cancel() }
    }

    /** Where the crest comes in loudest near the berth, and a scene listed for it. */
    @Test fun theRoarIsLoudOverTheBoreAndGoneAFewKilometresOff() {
        val e = event
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Find the loudest moment over the berth as the crest comes in.
            var best = 0.0; var bestTime = e.crest
            var t = e.crest - 200.0
            while (t < e.crest + 100.0) {
                val scene = TsunamiScene(terra, config, QualityTier.LOW, scope)
                val ear = above(berth, 20.0)
                listed(scene, ear, t)
                scene.heard(ear, t)
                if (scene.loudness > best) { best = scene.loudness; bestTime = t }
                t += 10.0
            }
            println("loudest over the berth: %.2f at %.0f s (crest %.0f s)".format(best, bestTime, e.crest))
            assertTrue("heard near: $best", best > 0.2)
            var last = Double.MAX_VALUE
            for (h in listOf(20.0, 500.0, 1_500.0, 3_000.0, 5_000.0)) {
                val scene = TsunamiScene(terra, config, QualityTier.LOW, scope)
                val ear = above(berth, h)
                listed(scene, ear, bestTime)
                scene.heard(ear, bestTime)
                println("roar from %.0f m up: %.2f, closeness %.2f".format(h, scene.loudness, scene.closeness))
                assertTrue("quieter higher at $h", scene.loudness <= last + 1e-9)
                last = scene.loudness
            }
            assertEquals("nothing past the reach", 0.0, last, 1e-9)
            // Far inland, nothing.
            val inland = TsunamiScene(terra, config, QualityTier.LOW, scope)
            val far = Vec3().setTo(offset(berth, -30_000.0, 0.0)).let { above(it, 20.0) }
            listed(inland, far, bestTime)
            inland.heard(far, bestTime)
            assertEquals("none 30 km off", 0.0, inland.loudness, 1e-9)
        } finally { scope.cancel() }
    }

    // --- drawn -----------------------------------------------------------------

    private val width = 960
    private val height = 600

    /** A renderer into an off-screen buffer with depth, the ground and sea round a camera, and the tsunami field. */
    private inner class Harness(val tier: QualityTier) : AutoCloseable {
        val fbo = GL30C.glGenFramebuffers()
        val colourBuffer = GL30C.glGenRenderbuffers()
        val depthBuffer = GL30C.glGenRenderbuffers()
        val bus = FrameBus()
        val renderer: GlRenderer
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terrain: TerrainBuilder

        init {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, colourBuffer)
            GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL_RGBA8, width, height)
            GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, colourBuffer)
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, depthBuffer)
            GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_DEPTH24_STENCIL8, width, height)
            GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_RENDERBUFFER, depthBuffer)
            renderer = GlRenderer({ tier }, bus)
            renderer.onSurfaceCreated(); renderer.onSurfaceChanged(width, height)
            terrain = TerrainBuilder(renderer.terrainSource, tier)
        }

        /** What a shot saw: colours as ARGB, top row first, and each pixel's depth, bottom row first. */
        inner class Shot(val pixels: IntArray, val depth: FloatArray, val near: Double, val camera: Vec3, val rotation: Quat) {
            /** Body-fixed point drawn at pixel ([x], [y]) from the top left, or null where nothing near is. */
            fun point(x: Int, y: Int): Vec3? {
                val d = depth[(height - 1 - y) * width + x].toDouble()
                if (d >= 1.0) return null
                val far = RenderFrameFar
                val zn = 2.0 * d - 1.0
                val eye = 2.0 * near * far / ((far + near) - zn * (far - near))
                val tanY = kotlin.math.tan(FOV * 0.5)
                val nx = (x + 0.5) / width * 2.0 - 1.0
                val ny = 1.0 - (y + 0.5) / height * 2.0
                val ray = Vec3(nx * tanY * width / height * eye, ny * tanY * eye, -eye)
                return rotation.rotate(ray, Vec3()).addInPlace(camera)
            }
        }

        /**
         * Draws the ground and sea round body-fixed [camera] at [time], looking along [rotation],
         * with [items] too, once everything near is built. Writes [name] if given. Without
         * [waves], no near sea, as in flight above its reach.
         */
        fun shoot(name: String?, camera: Vec3, rotation: Quat, time: Double, items: List<RenderItem> = emptyList(), near: Double = 10.0, waves: Boolean = true): Shot {
            val up = Vec3().setTo(camera).normalizeInPlace()
            val altitude = camera.length - radius - maxOf(field.elevation(up), 0.0)
            val sea = SeaScene(terra, luna, config, tier, scope).also { s ->
                s.javaClass.getDeclaredField("outerBudgetNanos").also { it.isAccessible = true }.setLong(s, Long.MAX_VALUE / 4)
            }
            val tsunamis = TsunamiScene(terra, config, tier, scope)
            listed(tsunamis, camera, time)
            // The sun high behind the camera, so the water shows its colour, not the sun's glint.
            val back = rotation.rotate(Vec3(0.0, 0.0, 1.0), Vec3()).let { it.addScaledInPlace(up, -(it dot up)) }
            val sun = Vec3().setTo(up).addScaledInPlace(if (back.length > 0.1) back.normalizeInPlace() else north(up), 0.6).normalizeInPlace()
            fun frame(): RenderFrame {
                val here = sea.sampleAt(camera, time)
                val tf = tsunamis.field
                val world = WorldView(
                    radius, terra.atmosphereHeight, terra.atmosphere?.scaleHeight ?: 1.0, sun, Vec3.unitY(), camera.length - radius, Quat.identity(),
                    terra.terrain!!.maxElevation, drawFarSurface = terrain.farSurfaceNeeded, chunkRange = terrain.chunkRange, time = time,
                    seaReach = if (waves && sea.built) sea.reach else 0.0, tide = if (tf != null) here.tide - here.tsunami else here.tide, sea = if (waves) sea.latest else null,
                    seaRadius = radius + (if (tf != null) here.tide - here.tsunami + tf.shore else here.tide), tsunami = tf,
                )
                val drawn = ArrayList(items)
                tsunamis.append(camera, Quat.identity(), time, drawn)
                return RenderFrame(1, System.nanoTime(), camera, rotation, FOV, drawn, world = world, nearestDistance = 2.0 * near)
            }
            val deadline = System.nanoTime() + 60_000_000_000L
            var readyAt = 0L
            while (System.nanoTime() < deadline) {
                terrain.collect()
                terrain.followCraft(terra, camera, altitude, scope)
                terrain.requestGlobe(terra, scope)
                sea.tsunamiField = tsunamis.field
                if (waves) sea.update(camera, time)
                bus.clear(); bus.publish(frame())
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
                renderer.onDrawFrame()
                if (terrain.patchReady && (sea.built || !waves)) {
                    if (readyAt == 0L) readyAt = System.nanoTime()
                    // A few seconds more for the rest of the chunks.
                    if (System.nanoTime() - readyAt > 4_000_000_000L) break
                }
                Thread.sleep(10)
            }
            // A few more builds once everything near is prepared, to time one that only retides.
            var most = 0.0
            if (waves) repeat(5) {
                Thread.sleep(150)
                sea.tsunamiField = tsunamis.field
                sea.update(camera, time + 0.2 * (it + 1))
                Thread.sleep(5)
                val until = System.nanoTime() + 5_000_000_000L
                while (System.nanoTime() < until) {
                    val building = sea.javaClass.getDeclaredField("building").also { f -> f.isAccessible = true }.getBoolean(sea)
                    if (!building) break
                    Thread.sleep(2)
                }
                most = maxOf(most, sea.lastBuildMillis)
            }
            println("${name ?: "shot"}: ${frame().items.size - items.size} spray items; sea build up to %.1f ms, field %.2f ms".format(most, tsunamis.lastFieldMillis))
            assertTrue("ground built", terrain.patchReady)
            assertTrue("sea built", sea.built || !waves)
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
            val data = BufferUtils.createByteBuffer(width * height * 4)
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, data)
            val depths = BufferUtils.createFloatBuffer(width * height)
            glReadPixels(0, 0, width, height, GL_DEPTH_COMPONENT, GL_FLOAT, depths)
            val depth = FloatArray(width * height).also { depths.get(it) }
            val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until height) for (x in 0 until width) {
                val i = ((height - 1 - y) * width + x) * 4
                image.setRGB(x, y, ((data.get(i).toInt() and 255) shl 16) or ((data.get(i + 1).toInt() and 255) shl 8) or (data.get(i + 2).toInt() and 255))
            }
            if (name != null) {
                val out = File("../build/tsunami-review").apply { mkdirs() }
                javax.imageio.ImageIO.write(image, "png", File(out, "$name.png"))
                println("wrote ${File(out, "$name.png").canonicalPath}")
            }
            assertEquals(GL_NO_ERROR, glGetError())
            return Shot(image.getRGB(0, 0, width, height, null, 0, width), depth, near, camera, rotation)
        }

        override fun close() {
            scope.cancel(); terrain.stop(); renderer.release(); bus.clear()
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0)
            GL30C.glDeleteFramebuffers(fbo)
            GL30C.glDeleteRenderbuffers(colourBuffer); GL30C.glDeleteRenderbuffers(depthBuffer)
        }
    }

    private fun look(from: Vec3, at: Vec3, upHint: Vec3): Quat {
        val forward = Vec3().setTo(at).subInPlace(from).normalizeInPlace()
        val q1 = quatFromTo(Vec3(0.0, 0.0, -1.0), forward)
        val current = q1.rotate(Vec3.unitY(), Vec3())
        val wanted = Vec3().setTo(upHint).addScaledInPlace(forward, -(upHint dot forward)).normalizeInPlace()
        return quatFromTo(current, wanted) * q1
    }

    /** Straight down from [height] metres over unit [over], north up the picture. */
    private fun down(over: Vec3, height: Double): Pair<Vec3, Quat> {
        val camera = Vec3().setTo(over).mulInPlace(radius + height)
        return camera to look(camera, Vec3().setTo(over).mulInPlace(radius - 1_000.0), north(over))
    }

    /**
     * A beach near the berth with a wide shallow shelf off it, for the bore coming in: a point on
     * the land just by the sea and the unit way out over the shelf.
     */
    private val beach: Pair<Vec3, Vec3> by lazy {
        var best = -1; var at = berth; var way = Vec3()
        for (j in -24..24) for (i in -24..24) {
            val p = offset(berth, i * 250.0, j * 250.0)
            val bed = field.elevation(p)
            if (bed < 0.5 || bed > 4.0) continue
            for (k in 0 until 16) {
                val a = 2.0 * Math.PI * k / 16
                val d = Vec3().setTo(east(p)).mulInPlace(kotlin.math.cos(a)).addScaledInPlace(north(p), kotlin.math.sin(a))
                var n = 0
                for (m in 1..14) {
                    val q = Vec3().setTo(p).addScaledInPlace(d, m * 110.0 / radius).normalizeInPlace()
                    val b = field.elevation(q)
                    if (b > -0.5 || b < -7.0) break
                    n++
                }
                if (n > best) { best = n; at = p; way = d }
            }
        }
        println("beach %.0f m from the berth, shelf %d m wide".format(at.distanceTo(berth) * radius, best * 110))
        at to way
    }

    /** When the bore's front (or out at sea, its pale crest line at least [least] white) reaches unit [p], near the crest at the berth. */
    private fun frontAt(p: Vec3, least: Double = 0.99): Double {
        val e = event
        val w = Weather(terra, config)
        val bed = field.elevation(p)
        var t = e.crest - 900.0
        while (t < e.crest + 900.0) {
            w.tsunamiHeight(p, t, bed)
            if (w.tsunamiFoam() >= least) return t
            t += 1.0
        }
        return e.crest
    }

    /** Low land near the berth the crest floods, and sea floor by it the trough leaves dry. */
    private val places: Pair<Vec3, Vec3> by lazy {
        val e = event
        val w = Weather(terra, config)
        var flooded: Vec3? = null; var floodDepth = 0.0
        var bare: Vec3? = null; var bareBy = 0.0
        for (j in -40..40) for (i in -40..40) {
            val p = offset(berth, i * 100.0, j * 100.0)
            val bed = field.elevation(p)
            if (bed > 0.3) {
                val water = w.tsunamiHeight(p, e.flood, bed) - bed
                if (water > floodDepth) { floodDepth = water; flooded = p }
            } else if (bed < 0.0) {
                val by = bed - w.tsunamiHeight(p, e.trough, bed)
                if (by > bareBy) { bareBy = by; bare = p }
            }
        }
        println("flooded %.2f m deep at %s, bed bared by %.2f m at %s".format(floodDepth, flooded, bareBy, bare))
        assertNotNull("some land floods", flooded)
        assertNotNull("some bed is bared", bare)
        flooded!! to bare!!
    }

    /**
     * Straight down over the coast near the berth at the trough, the crest and the front: every
     * pixel has near ground or water under it, flooded land is drawn at the water's height (off the
     * bore's face), and bared bed at the bed's.
     */
    @Test fun floodedLandAndBaredBedAreDrawnWithoutGaps() {
        TestGl.current()
        val e = event
        val (flooded, bare) = places
        val sea = com.rm.apogee.core.sea.Sea(terra, luna, Weather(terra, config), config.seed)
        val near = com.rm.apogee.core.sea.SeaSample()
        // On the bore's steep face the drawn facets can't follow the step; it's checked off it.
        fun atFace(up: Vec3, time: Double, height: Double): Boolean {
            for ((de, dn) in listOf(15.0 to 0.0, -15.0 to 0.0, 0.0 to 15.0, 0.0 to -15.0)) {
                val q = offset(up, de, dn)
                val bed = field.elevation(q)
                sea.sample(Vec3().setTo(q).mulInPlace(radius + maxOf(bed, 0.0)), time, near)
                // Only against water: the edge of the flood isn't its face.
                if (near.height > bed + 0.3 && kotlin.math.abs(near.height - height) > 1.0) return true
            }
            return false
        }
        Harness(QualityTier.MEDIUM).use { h ->
            val front = frontAt(berth) - 10.0
            for ((name, over, time) in listOf(Triple("gaps-flooded", flooded, frontAt(flooded) + 120.0), Triple("gaps-bared", bare, e.trough), Triple("gaps-front", berth, front))) {
                val apart = ArrayList<Double>()
                val (camera, rotation) = down(over, 700.0)
                val shot = h.shoot(name, camera, rotation, time)
                var holes = 0; var floodChecked = 0; var floodWrong = 0; var bareChecked = 0; var bareWrong = 0
                val sample = com.rm.apogee.core.sea.SeaSample()
                for (y in 4 until height step 8) for (x in 4 until width step 8) {
                    val p = shot.point(x, y)
                    if (p == null) { holes++; continue }
                    val up = Vec3().setTo(p).normalizeInPlace()
                    val bed = field.elevation(up)
                    val drawn = p.length - radius
                    sea.sample(Vec3().setTo(up).mulInPlace(radius + maxOf(bed, 0.0)), time, sample)
                    val water = radius + sample.height
                    // Drawn water against what craft float on.
                    if (sample.depth > 0.5 && drawn > bed + 0.3) apart += kotlin.math.abs(drawn - sample.height)
                    if (bed > 0.5 && sample.height > bed + 0.5 && !atFace(up, time, sample.height)) {
                        floodChecked++
                        if (kotlin.math.abs(drawn - sample.height) > 1.5) floodWrong++
                    } else if (bed < -0.5 && sample.height < bed - 0.5 && water > 0.0) {
                        bareChecked++
                        if (kotlin.math.abs(drawn - bed) > 2.5) bareWrong++
                    }
                }
                apart.sort()
                val p50 = if (apart.isEmpty()) 0.0 else apart[apart.size / 2]
                val p95 = if (apart.isEmpty()) 0.0 else apart[apart.size * 95 / 100]
                println("$name: $holes holes, flooded $floodWrong of $floodChecked off, bared $bareWrong of $bareChecked off, water drawn from floated on: median %.2f m, 95%% %.2f m of ${apart.size}".format(p50, p95))
                assertEquals("$name: no holes", 0, holes)
                if (name == "gaps-flooded") assertTrue("$name: flooded land seen", floodChecked > 20)
                if (name == "gaps-bared") assertTrue("$name: bared bed seen", bareChecked > 20)
                assertTrue("$name: flooded land at the water's height", floodWrong <= floodChecked / 20)
                assertTrue("$name: bared bed at its own height", bareWrong <= bareChecked / 20)
            }
        }
    }


    /** Toward the open sea from the berth: the bearing with the deepest water 4 to 16 km out. */
    private val toward: Vec3 by lazy {
        (0 until 24).map { k ->
            val a = 2.0 * Math.PI * k / 24
            Vec3().setTo(east(berth)).mulInPlace(kotlin.math.cos(a)).addScaledInPlace(north(berth), kotlin.math.sin(a))
        }.minBy { d -> (4..16).sumOf { km -> maxOf(field.elevation(Vec3().setTo(berth).addScaledInPlace(d, km * 1_000.0 / radius).normalizeInPlace()), -50.0) } }
    }

    private fun out(metres: Double) = Vec3().setTo(berth).addScaledInPlace(toward, metres / radius).normalizeInPlace()

    /**
     * The far water's crest (TsunamiField.crest, as the terrain shader has it) picks the same of
     * two overlapping tsunamis as the waves do and is at the same time over open water; and toward
     * the waves' edge their crest goes over to it, so the line is the same where the far water
     * takes over.
     */
    @Test fun theFarLineMeetsTheWavesAtTheSeam() {
        val e = event
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val w = Weather(terra, config)
            val crest = DoubleArray(TsunamiField.CREST_OUT)
            val camera = above(out(-3_000.0), 1_500.0)
            val up0 = Vec3().setTo(camera).normalizeInPlace()
            var owned = 0; var same = 0
            val apart = ArrayList<Double>()
            var seamChecked = 0; var seamWrong = 0
            for (dt in listOf(0.0, 120.0, 300.0)) {
                val time = frontAt(out(5_000.0), 0.5) + dt
                val live = ArrayList<LiveTsunami>(); w.tsunamis(up0, time, 1e6, live)
                val scene = TsunamiScene(terra, config, QualityTier.MEDIUM, scope)
                listed(scene, camera, time)
                val f = scene.field!!
                assertTrue("two drawn where ${live.size} overlap", live.size < 2 || f.period2 > 0.0)
                // Inside the waves' reach, against the model.
                for (dist in listOf(4_000.0, 7_200.0)) for (k in 0 until 360) {
                    val a = 2.0 * Math.PI * k / 360
                    val p = offset(up0, dist * kotlin.math.cos(a), dist * kotlin.math.sin(a))
                    val bed = field.elevation(p)
                    if (bed >= 0.0 || !f.crest(p, -bed, time, crest)) continue
                    w.tsunamiHeight(p, time, bed, blur = dist / 16.0)
                    if (kotlin.math.abs(w.tsunamiWhiteness()) < 0.01) continue
                    owned++
                    if (crest[2] != w.tsunamiPeriod()) continue
                    same++
                    if (-bed > 6.0) apart += kotlin.math.abs(crest[0] - w.tsunamiCrest())
                }
                // The waves at their edge, against the far water.
                val sea = SeaScene(terra, luna, config, QualityTier.MEDIUM, scope).also { s ->
                    s.javaClass.getDeclaredField("outerBudgetNanos").also { it.isAccessible = true }.setLong(s, Long.MAX_VALUE / 4)
                }
                sea.tsunamiField = f
                val until = System.nanoTime() + 60_000_000_000L
                while (!sea.built && System.nanoTime() < until) { sea.update(camera, time); Thread.sleep(20) }
                Thread.sleep(150)
                sea.update(camera, time)
                val building = sea.javaClass.getDeclaredField("building").also { it.isAccessible = true }
                while (building.getBoolean(sea) && System.nanoTime() < until) Thread.sleep(5)
                val surface = sea.latest!!
                val d = Vec3()
                for (v in 0 until surface.vertexCount) {
                    val o = v * SeaSurface.STRIDE
                    d.setTo(surface.origin).addInPlace(Vec3(surface.vertices[o].toDouble(), surface.vertices[o + 1].toDouble(), surface.vertices[o + 2].toDouble())).normalizeInPlace()
                    val away = d.distanceTo(up0) * radius
                    if (away < 0.85 * sea.reach || away > sea.reach) continue
                    val bed = field.elevation(d)
                    if (bed > -40.0 || !f.crest(d, -bed, surface.time, crest)) continue
                    val packed = surface.vertices[o + 9]
                    val white = packed - 4f * kotlin.math.floor((packed + 2f) * 0.25f)
                    if (kotlin.math.abs(white) < 0.01 && kotlin.math.abs(crest[1]) < 0.01) continue
                    // Off the coast's steep change in time: vertices the grids morph or the
                    // coast cells mix take their neighbours' times, as the far water does.
                    val there = crest[0]
                    fun at(x: Double, y: Double): Double {
                        val q = offset(d, x, y)
                        val qb = field.elevation(q)
                        return if (qb < -6.0 && f.crest(q, -qb, surface.time, crest)) crest[0] else Double.NaN
                    }
                    // Times along a line, where a morphed or mixed vertex's lands near its own.
                    val smooth = kotlin.math.abs(at(400.0, 0.0) + at(-400.0, 0.0) - 2.0 * there) < 4.0 &&
                        kotlin.math.abs(at(0.0, 400.0) + at(0.0, -400.0) - 2.0 * there) < 4.0
                    if (!smooth) continue
                    seamChecked++
                    if (kotlin.math.abs(surface.vertices[o + 8] - there) > 2.0) seamWrong++
                }
            }
            apart.sort()
            val p50 = apart[apart.size / 2]; val p90 = apart[apart.size * 9 / 10]
            println("far crest: same tsunami as the waves at $same of $owned, over 6 m deep %.1f s apart (median), %.1f s (90%%) of ${apart.size}; at the seam $seamWrong of $seamChecked off".format(p50, p90))
            assertTrue("the same tsunami: $same of $owned", same >= owned * 0.9)
            assertTrue("at the same time: $p50", p50 < 2.0)
            assertTrue("at the seam: $seamWrong of $seamChecked", seamChecked > 15 && seamWrong <= seamChecked / 10)
        } finally { scope.cancel() }
    }

    /**
     * Low land a tsunami floods further out than the waves reach: the flood grid has the model's
     * level, and the ground there is drawn at the water's height.
     */
    @Test fun farFloodedLandIsDrawnAsWater() {
        TestGl.current()
        val e = event
        val (flooded, _) = places
        val time = frontAt(flooded) + 240.0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // 15 km off, inland, so the flood is past the waves (12 km on MEDIUM), and high, to look
        // down on it.
        val inland = (0 until 16).map { k ->
            val a = 2.0 * Math.PI * k / 16
            offset(flooded, 15_000.0 * kotlin.math.cos(a), 15_000.0 * kotlin.math.sin(a))
        }.maxBy { field.elevation(it) }
        val camera = Vec3().setTo(inland).mulInPlace(radius + maxOf(field.elevation(inland), 0.0) + 8_000.0)
        try {
            val scene = TsunamiScene(terra, config, QualityTier.MEDIUM, scope)
            listed(scene, camera, time)
            val flood = scene.field?.flood
            assertNotNull("a flood grid", flood)
            val w = Weather(terra, config)
            var wet = 0; var off = 0
            for (j in 0 until TsunamiFlood.SIDE) for (i in 0 until TsunamiFlood.SIDE) {
                val level = flood!!.level(i, j)
                if (level <= 0.0) continue
                wet++
                val m = (TsunamiFlood.SIDE - 1) * 0.5
                val p = Vec3().setTo(flood.centre).addScaledInPlace(flood.east, (i - m) * flood.spacing / radius).addScaledInPlace(flood.north, (j - m) * flood.spacing / radius).normalizeInPlace()
                if (kotlin.math.abs(w.tsunamiHeight(p, time, field.elevation(p)) - level) > 0.01) off++
            }
            println("far-flood grid: $wet flooded points, $off off the model")
            assertTrue("flooded points: $wet", wet > 10)
            assertEquals(0, off)
        } finally { scope.cancel() }
        Harness(QualityTier.MEDIUM).use { h ->
            val shot = h.shoot("far-flood", camera, look(camera, Vec3().setTo(flooded).mulInPlace(radius), flooded), time, near = 100.0)
            val w = Weather(terra, config)
            var lifted = 0
            val apart = ArrayList<Double>()
            for (y in 1 until height step 2) for (x in 1 until width step 2) {
                val p = shot.point(x, y) ?: continue
                // Past the waves, near enough for the ground's detail to follow the flood.
                val away = p.distanceTo(camera)
                if (away < 12_500.0 || away > 30_000.0) continue
                val drawn = p.length - radius
                // Through the sea to its bed.
                if (drawn < -2.0) continue
                val up = Vec3().setTo(p).normalizeInPlace()
                val bed = field.elevation(up)
                if (bed < 0.3) continue
                val level = w.tsunamiHeight(up, time, bed)
                // Well inside the flood, off its edge.
                if (level - bed < 1.0) continue
                apart += kotlin.math.abs(drawn - level)
                if (drawn > bed + 0.3) lifted++
            }
            apart.sort()
            val median = apart.getOrElse(apart.size / 2) { 99.0 }
            // Ground triangles hundreds of metres across span the flood's edges, so only most
            // are lifted, and the water's level varies between the points the grid has.
            println("far-flood: $lifted of ${apart.size} flooded points drawn over the ground, %.2f m from the water (median)".format(median))
            assertTrue("far flooded land seen: ${apart.size}", apart.size > 10)
            assertTrue("drawn over the ground: $lifted of ${apart.size}", lifted >= apart.size * 3 / 4)
            assertTrue("drawn at the water: $median", median < 1.0)
        }
    }

    /**
     * From 30 km above a flooded coast, over the waves' reach so they aren't drawn, the flood grid
     * holds the land straight below too, as the model floods it.
     */
    @Test fun floodGridCoversTheLandBelowFromHighUp() {
        val e = event
        val (flooded, _) = places
        val time = frontAt(flooded) + 240.0
        val camera = Vec3().setTo(flooded).mulInPlace(radius + 30_000.0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scene = TsunamiScene(terra, config, QualityTier.MEDIUM, scope)
            listed(scene, camera, time)
            val flood = scene.field?.flood
            assertNotNull("a flood grid", flood)
            val w = Weather(terra, config)
            var wet = 0; var near = 0; var off = 0
            val m = (TsunamiFlood.SIDE - 1) * 0.5
            for (j in 0 until TsunamiFlood.SIDE) for (i in 0 until TsunamiFlood.SIDE) {
                val x = (i - m) * flood!!.spacing; val y = (j - m) * flood.spacing
                // Within the waves' reach, under the camera.
                if (x * x + y * y > 12_000.0 * 12_000.0) continue
                val p = Vec3().setTo(flood.centre).addScaledInPlace(flood.east, x / radius).addScaledInPlace(flood.north, y / radius).normalizeInPlace()
                val bed = field.elevation(p)
                if (bed <= 0.0) continue
                val level = w.tsunamiHeight(p, time, bed)
                if (level - bed < 0.2) continue
                wet++
                if (x * x + y * y < 6_000.0 * 6_000.0) near++
                if (kotlin.math.abs(flood.level(i, j) - level) > 0.01) off++
            }
            println("flood grid below: $wet flooded points, $near within 6 km, $off off the model")
            assertTrue("flooded points below: $wet, $near within 6 km", wet > 3 && near > 0)
            assertEquals(0, off)
        } finally { scope.cancel() }
    }

    /** Pictures for a look by eye, written only with a display. */
    @Test fun pictures() {
        TestGl.current()
        val e = event
        val (flooded, bare) = places
        Harness(QualityTier.MEDIUM).use { h ->
            // The drawn-back sea from a hill above the bared bed, looking out to sea.
            run {
                val seaward = offset(bare, 0.0, 0.0)
                val from = offset(bare, -1_500.0, -600.0)
                val camera = Vec3().setTo(from).mulInPlace(radius + maxOf(field.elevation(from), 0.0) + 250.0)
                val target = Vec3().setTo(seaward).mulInPlace(radius - 20.0)
                h.shoot("drawback", camera, look(camera, target, from), e.trough)
                h.shoot("drawback-before", camera, look(camera, target, from), e.tsunami.start)
            }
            // The crest coming in from where the quake was, seen from the shore and from higher up.
            run {
                // On a beach with a shelf off it, the bore coming in over the shelf.
                val (sand, way) = beach
                fun seaward(m: Double) = Vec3().setTo(sand).addScaledInPlace(way, m / radius).normalizeInPlace()
                // Standing at the water's edge as it was, the bore 150 m off and then 50 m off.
                val camera = above(seaward(40.0), 2.0)
                val ahead = Vec3().setTo(seaward(1_500.0)).mulInPlace(radius)
                val time = frontAt(seaward(190.0))
                println("crest-from-shore at %.0f s".format(time))
                h.shoot("crest-from-shore", camera, look(camera, ahead, sand), time, near = 0.5)
                h.shoot("crest-from-shore-later", camera, look(camera, ahead, sand), frontAt(seaward(90.0)), near = 0.5)
                // From 60 m up beside it.
                val side = Vec3().setTo(sand).crossInPlace(way).normalizeInPlace()
                val beside = Vec3().setTo(seaward(200.0)).addScaledInPlace(side, 400.0 / radius).normalizeInPlace()
                val sideCamera = Vec3().setTo(beside).mulInPlace(radius + 60.0)
                h.shoot("crest-side", sideCamera, look(sideCamera, Vec3().setTo(seaward(200.0)).mulInPlace(radius), sand), frontAt(seaward(200.0)))
            }
            run {
                val camera = above(out(-3_000.0), 1_500.0)
                h.shoot("crest-from-afar", camera, look(camera, Vec3().setTo(out(3_000.0)).mulInPlace(radius), berth), frontAt(out(5_000.0), 0.5))
            }
            run {
                val camera = above(out(-1_000.0), 3_500.0)
                h.shoot("crest-from-air", camera, look(camera, Vec3().setTo(out(7_000.0)).mulInPlace(radius), berth), frontAt(out(7_000.0), 0.5))
            }
            // Two overlapping: the second's front running in behind the first's, from the air.
            run {
                val live = ArrayList<LiveTsunami>()
                Weather(terra, config).tsunamis(berth, e.crest, 1e9, live)
                val other = live.filter { it.id != e.tsunami.id && !it.arrivesHere.isNaN() && it.arrivesHere < 1e29 }
                    .minByOrNull { kotlin.math.abs(it.arrivesHere - e.arrives) }
                if (other != null) {
                    val time = other.arrivesHere + 0.5 * other.period
                    println("overlap: ${e.tsunami.id} arrives %.0f s, ${other.id} %.0f s, picture at %.0f s".format(e.arrives, other.arrivesHere, time))
                    val camera = above(out(-2_000.0), 2_500.0)
                    h.shoot("overlap", camera, look(camera, Vec3().setTo(out(5_000.0)).mulInPlace(radius), berth), time)
                }
            }
            // A boat carried in on the flood, the bore running on inland past it.
            run {
                val bed = field.elevation(flooded)
                val time = frontAt(flooded) + 12.0
                val water = Weather(terra, config).tsunamiHeight(flooded, time, bed)
                val hull = RenderItem(MeshSpec.Box(4.0, 2.0, 12.0), Vec3().setTo(flooded).mulInPlace(radius + maxOf(water, bed) + 0.4),
                    quatFromTo(Vec3.unitY(), flooded), floatArrayOf(0.85f, 0.35f, 0.20f, 1f))
                // From inland, uphill of it, looking back at the water coming.
                val ge = field.elevation(offset(flooded, 50.0, 0.0)) - field.elevation(offset(flooded, -50.0, 0.0))
                val gn = field.elevation(offset(flooded, 0.0, 50.0)) - field.elevation(offset(flooded, 0.0, -50.0))
                val l = maxOf(kotlin.math.hypot(ge, gn), 1e-6)
                val from = offset(flooded, 70.0 * ge / l, 70.0 * gn / l)
                val camera = Vec3().setTo(from).mulInPlace(radius + maxOf(field.elevation(from), 0.0) + 12.0)
                println("flood-boat at %.0f s, water %.2f m over a bed of %.2f m".format(time, water - bed, bed))
                h.shoot("flood-boat", camera, look(camera, Vec3().setTo(flooded).mulInPlace(radius + maxOf(water, bed)), flooded), time, listOf(hull), near = 1.0)
            }
        }
        // The map icon, large, as the map draws it.
        val out = File("../build/tsunami-review").apply { mkdirs() }
        val icon = java.awt.image.BufferedImage(160, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = icon.createGraphics()
        g.color = java.awt.Color(0x10, 0x18, 0x28); g.fillRect(0, 0, 160, 160)
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        val glyph = GameSession.TSUNAMI_GLYPH
        for (pass in 0..1) {
            g.stroke = java.awt.BasicStroke(if (pass == 0) 14f else 8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            g.color = if (pass == 0) java.awt.Color(0, 0, 0, 140) else java.awt.Color(179, 224, 255)
            for (k in 0 until glyph.size / 2 - 1) {
                g.drawLine((80 + glyph[2 * k] * 60).toInt(), (80 + glyph[2 * k + 1] * 60).toInt(), (80 + glyph[2 * k + 2] * 60).toInt(), (80 + glyph[2 * k + 3] * 60).toInt())
            }
            g.drawLine(20, 130, 140, 130)
        }
        g.dispose()
        javax.imageio.ImageIO.write(icon, "png", File(out, "map-icon.png"))
        println("wrote ${File(out, "map-icon.png").canonicalPath}")
    }

    private companion object {
        val FOV = Math.toRadians(55.0)
        const val RenderFrameFar = 250_000.0
    }
}
