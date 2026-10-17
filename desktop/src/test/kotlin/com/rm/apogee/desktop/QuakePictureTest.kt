package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.terrain.TerrainField
import com.rm.apogee.core.weather.*
import com.rm.apogee.game.GameSession
import com.rm.apogee.game.QuakeScene
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
 * A strong quake by the Cape pad: dust rises off the ground round the camera within the tier's
 * budget, keeps its keys from frame to frame and stops past the reach, and the map marks the
 * shock with its ring. With a display, writes pictures to build/quake-review.
 */
class QuakePictureTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")
    private val luna = system.body("luna")
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val radius = terra.radius
    private val field = terra.terrain as TerrainField
    private val pad = field.worksDirection(0.0, 0.0).normalizeInPlace()

    private fun east(up: Vec3) = Vec3(up.z, 0.0, -up.x).normalizeInPlace()
    private fun north(up: Vec3) = Vec3().setTo(up).crossInPlace(east(up))
    private fun offset(up: Vec3, x: Double, y: Double) =
        Vec3().setTo(up).addScaledInPlace(east(up), x / radius).addScaledInPlace(north(up), y / radius).normalizeInPlace()
    private fun above(up: Vec3, height: Double) = Vec3().setTo(up).mulInPlace(radius + maxOf(field.elevation(up), 0.0) + height)

    /** An M7.8 inland of the pad, 5 km off, striking at [START]. */
    private val epicentre = offset(pad, -4_000.0, 3_000.0)
    private fun forced() = Quakes.of(terra, config).also { it.force(epicentre, START, 7.8, 4_000.0) }

    /** A scene for [tier], updated to [time] at body-fixed [camera] and its dust listed. */
    private fun scene(tier: QualityTier, scope: CoroutineScope, camera: Vec3, time: Double): QuakeScene {
        val s = QuakeScene(terra, config, tier, scope, forced(), ::forced)
        val listing = s.javaClass.getDeclaredField("listing").also { it.isAccessible = true }
        fun settle() {
            val until = System.nanoTime() + 20_000_000_000L
            while (listing.get(s) as Boolean && System.nanoTime() < until) Thread.sleep(5)
        }
        var t = time - 1.0
        // Into the shaking, waiting on the worker each step, so the listing sees it strong.
        while (t <= time) { s.update(camera, t); settle(); t += 0.25 }
        s.update(camera, time); settle()
        return s
    }

    /** When the strong part of the shaking is at the pad. */
    private val strong: Double by lazy {
        val w = forced()
        val v = Vec3()
        var best = START; var most = 0.0
        var t = START
        while (t < START + 30.0) {
            w.shake(pad, t, v)
            if (w.level() > most) { most = w.level(); best = t }
            t += 0.25
        }
        println("strongest at the pad %.1f m/s^2, %.1f s after it struck".format(most, best - START))
        best
    }

    @Test fun dustRisesNearTheCameraWithinBudgetAndKeepsItsKeys() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val camera = above(pad, 10.0)
            for (tier in QualityTier.entries) {
                val s = scene(tier, scope, camera, strong)
                val a = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), strong, a)
                val b = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), strong + 0.1, b)
                println("$tier: ${a.size} dust items, level %.2f".format(s.level))
                assertTrue("some dust on $tier", a.isNotEmpty())
                assertTrue("within budget on $tier: ${a.size}", a.size <= 3 * s.dustMost)
                assertEquals("same keys a frame on", a.map { it.key }.toSet(), b.map { it.key }.toSet())
                assertTrue("none past the reach", a.all { it.position.distanceTo(camera) < s.dustReach + 80.0 })
            }
            // From high up, none is looked for; far off, what's listed isn't drawn.
            val high = scene(QualityTier.LOW, scope, above(pad, 3_000.0), strong)
            val none = ArrayList<RenderItem>(); high.append(above(pad, 3_000.0), Quat.identity(), strong, none)
            assertTrue("none high up", none.isEmpty())
            val s = scene(QualityTier.LOW, scope, camera, strong)
            val far = ArrayList<RenderItem>(); s.append(above(offset(pad, 5_000.0, 0.0), 10.0), Quat.identity(), strong, far)
            assertTrue("none drawn far from where it was listed", far.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun theMapMarksTheShockWithItsRing() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val s = QuakeScene(terra, config, QualityTier.LOW, scope, forced(), ::forced)
            s.forMap(START + 10.0)
            val listing = s.javaClass.getDeclaredField("listing").also { it.isAccessible = true }
            val until = System.nanoTime() + 20_000_000_000L
            while (listing.get(s) as Boolean && System.nanoTime() < until) Thread.sleep(5)
            val all = s.all
            assertEquals(1, all.size)
            val icons = ArrayList<GameSession.MapIcon>(); val lines = ArrayList<RenderLine>()
            GameSession.quakeMarks(all, Quat.identity(), radius, icons, lines)
            assertEquals(GameSession.IconKind.QUAKE, icons.single().kind)
            assertTrue("at the epicentre", icons.single().at.distanceTo(Vec3().setTo(epicentre).mulInPlace(radius)) < 1.0)
            assertEquals("its felt ring and its waves going out", 2, lines.size)
            val reach = all[0].quake.reach
            val r = lines[0].points[0].distanceTo(Vec3().setTo(epicentre).mulInPlace(radius))
            assertEquals("ring at the reach", reach, r, reach * 0.01)
        } finally { scope.cancel() }
    }

    private val width = 960
    private val height = 600

    /** Pictures for a look by eye, written only with a display. */
    @Test fun pictures() {
        TestGl.current()
        val out = File("../build/quake-review").apply { mkdirs() }
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
        val terrain = TerrainBuilder(renderer.terrainSource, tier)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            fun look(from: Vec3, at: Vec3, upHint: Vec3): Quat {
                val forward = Vec3().setTo(at).subInPlace(from).normalizeInPlace()
                val q1 = quatFromTo(Vec3(0.0, 0.0, -1.0), forward)
                val current = q1.rotate(Vec3.unitY(), Vec3())
                val wanted = Vec3().setTo(upHint).addScaledInPlace(forward, -(upHint dot forward)).normalizeInPlace()
                return quatFromTo(current, wanted) * q1
            }

            fun shoot(name: String, camera: Vec3, rotation: Quat, time: Double, items: List<RenderItem>) {
                val up = Vec3().setTo(camera).normalizeInPlace()
                val altitude = camera.length - radius - maxOf(field.elevation(up), 0.0)
                val sea = SeaScene(terra, luna, config, tier, scope).also { s ->
                    s.javaClass.getDeclaredField("outerBudgetNanos").also { it.isAccessible = true }.setLong(s, Long.MAX_VALUE / 4)
                }
                val back = rotation.rotate(Vec3(0.0, 0.0, 1.0), Vec3()).let { it.addScaledInPlace(up, -(it dot up)) }
                val sun = Vec3().setTo(up).addScaledInPlace(if (back.length > 0.1) back.normalizeInPlace() else north(up), 0.7).normalizeInPlace()
                fun frame(): RenderFrame {
                    val here = sea.sampleAt(camera, time)
                    val world = WorldView(
                        radius, terra.atmosphereHeight, terra.atmosphere?.scaleHeight ?: 1.0, sun, Vec3.unitY(), camera.length - radius, Quat.identity(),
                        terra.terrain!!.maxElevation, drawFarSurface = terrain.farSurfaceNeeded, chunkRange = terrain.chunkRange, time = time,
                        seaReach = if (sea.built) sea.reach else 0.0, tide = here.tide, sea = sea.latest, seaRadius = radius + here.tide,
                    )
                    return RenderFrame(1, System.nanoTime(), camera, rotation, FOV, items, world = world, nearestDistance = 4.0)
                }
                val deadline = System.nanoTime() + 60_000_000_000L
                var readyAt = 0L
                while (System.nanoTime() < deadline) {
                    terrain.collect()
                    terrain.followCraft(terra, camera, altitude, scope)
                    terrain.requestGlobe(terra, scope)
                    sea.update(camera, time)
                    bus.clear(); bus.publish(frame())
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
                    renderer.onDrawFrame()
                    if (terrain.patchReady && sea.built) {
                        if (readyAt == 0L) readyAt = System.nanoTime()
                        if (System.nanoTime() - readyAt > 4_000_000_000L) break
                    }
                    Thread.sleep(10)
                }
                assertTrue("ground built", terrain.patchReady)
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
                assertEquals(GL_NO_ERROR, glGetError())
            }

            // A rocket standing on the pad, for scale.
            val padUp = Vec3().setTo(pad)
            val padGround = radius + field.elevation(pad)
            val rocket = RenderItem(MeshSpec.Cylinder(1.6, 28.0), Vec3().setTo(pad).mulInPlace(padGround + 14.0), quatFromTo(Vec3.unitY(), padUp),
                floatArrayOf(0.92f, 0.92f, 0.90f, 1f))
            // Dust round the pad as listed from over it, and the nearest puff.
            val probe = scene(tier, scope, above(pad, 10.0), strong)
            val dust = ArrayList<RenderItem>(); probe.append(above(pad, 10.0), Quat.identity(), strong, dust)
            val toward = Vec3()
            for (d in dust) toward.addInPlace(Vec3().setTo(d.position).normalizeInPlace().subInPlace(pad))
            if (toward.length < 1e-12) toward.setTo(east(pad)) else toward.normalizeInPlace()
            val nearest = dust.minByOrNull { it.position.distanceTo(above(pad, 10.0)) }?.position?.let { Vec3().setTo(it).normalizeInPlace() } ?: pad
            // 80 m behind the pad, 20 m up, looking past the rocket at the nearest dust.
            val way = Vec3().setTo(nearest).subInPlace(pad).let { if (it.length < 1e-12) toward else it.normalizeInPlace() }
            val from = Vec3().setTo(pad).addScaledInPlace(way, -80.0 / radius).normalizeInPlace()
            val camera = above(from, 20.0)
            val s = scene(tier, scope, camera, strong)
            val items = ArrayList<RenderItem>(); s.append(camera, Quat.identity(), strong, items)
            println("pad: ${items.size} dust items, level %.2f, shake %.2f".format(s.level, s.shake))
            for (d in items) println("  dust %.0f m off, alpha %.2f".format(d.position.distanceTo(camera), d.color[3]))
            val target = Vec3().setTo(nearest).mulInPlace(radius + maxOf(field.elevation(nearest), 0.0) + 10.0)
            shoot("quake-pad", camera, look(camera, target, from), strong, listOf(rocket) + items)
            shoot("quake-pad-before", camera, look(camera, target, from), START - 5.0, listOf(rocket))
            // Close to the dust, 10 m up and 150 m from the nearest puff.
            val closeFrom = Vec3().setTo(nearest).addScaledInPlace(Vec3().setTo(nearest).subInPlace(pad).normalizeInPlace(), -150.0 / radius).normalizeInPlace()
            val closeCamera = above(closeFrom, 10.0)
            val cs = scene(tier, scope, closeCamera, strong)
            val close = ArrayList<RenderItem>(); cs.append(closeCamera, Quat.identity(), strong, close)
            shoot("quake-dust-close", closeCamera, look(closeCamera, target, closeFrom), strong, listOf(rocket) + close)
            // Higher, to see how far round it rises.
            val wideFrom = Vec3().setTo(pad).addScaledInPlace(toward, -500.0 / radius).normalizeInPlace()
            val wideCamera = above(wideFrom, 120.0)
            val ws = scene(tier, scope, wideCamera, strong)
            val wide = ArrayList<RenderItem>(); ws.append(wideCamera, Quat.identity(), strong, wide)
            shoot("quake-wide", wideCamera, look(wideCamera, Vec3().setTo(pad).mulInPlace(padGround), wideFrom), strong, listOf(rocket) + wide)

            // The map icon, large, as the map draws it, over a piece of its ring.
            val icon = java.awt.image.BufferedImage(160, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = icon.createGraphics()
            g.color = java.awt.Color(0x10, 0x18, 0x28); g.fillRect(0, 0, 160, 160)
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = java.awt.BasicStroke(3f)
            g.color = java.awt.Color(255, 179, 115, 90)
            g.drawOval(10, 10, 140, 140)
            val glyph = GameSession.QUAKE_GLYPH
            for (pass in 0..1) {
                g.stroke = java.awt.BasicStroke(if (pass == 0) 14f else 8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g.color = if (pass == 0) java.awt.Color(0, 0, 0, 140) else java.awt.Color(255, 199, 133)
                for (k in 0 until glyph.size / 2 - 1) {
                    g.drawLine((80 + glyph[2 * k] * 60).toInt(), (80 + glyph[2 * k + 1] * 60).toInt(), (80 + glyph[2 * k + 2] * 60).toInt(), (80 + glyph[2 * k + 3] * 60).toInt())
                }
            }
            g.dispose()
            javax.imageio.ImageIO.write(icon, "png", File(out, "quake-map-icon.png"))
            println("wrote ${File(out, "quake-map-icon.png").canonicalPath}")
        } finally {
            scope.cancel(); terrain.stop(); renderer.release(); bus.clear()
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0)
            GL30C.glDeleteFramebuffers(fbo)
            GL30C.glDeleteRenderbuffers(colourBuffer); GL30C.glDeleteRenderbuffers(depthBuffer)
        }
    }

    private companion object {
        const val START = 10_000.0
        val FOV = Math.toRadians(55.0)
    }
}
