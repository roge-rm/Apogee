package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.TerrainField
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
 * Terra's desert from a suborbital height on every tier, still and flying: no dark lines along the
 * chunks' seams, from holes or from skirts drawn as if under the sea. Needs a display; writes
 * pictures to build/terrain-seam-review.
 */
class TerrainSeamPictureTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")
    private val radius = terra.radius
    private val field = terra.terrain as TerrainField

    /** The middle of the largest patch of dry sand found on a coarse latitude/longitude scan. */
    private val desert: Vec3 by lazy {
        fun at(lat: Double, lon: Double) = Vec3(kotlin.math.cos(lat) * kotlin.math.cos(lon), kotlin.math.sin(lat), kotlin.math.cos(lat) * kotlin.math.sin(lon))
        fun sand(d: Vec3): Boolean {
            val h = field.elevation(d)
            return h > 20.0 && field.material(d, h, 0.0) == SurfaceMaterial.SAND
        }
        var best = at(0.0, 0.0); var most = -1
        val step = Math.toRadians(3.0)
        var lat = -1.2
        while (lat < 1.2) {
            var lon = -Math.PI
            while (lon < Math.PI) {
                val centre = at(lat, lon)
                if (sand(centre)) {
                    var count = 0
                    for (a in -3..3) for (b in -3..3) if (sand(at(lat + a * step * 0.5, lon + b * step * 0.5))) count++
                    if (count > most) { most = count; best = centre }
                }
                lon += step
            }
            lat += step
        }
        best
    }

    private fun east(up: Vec3) = Vec3(up.z, 0.0, -up.x).normalizeInPlace()
    private fun north(up: Vec3) = Vec3().setTo(up).crossInPlace(east(up))

    private fun look(from: Vec3, at: Vec3, upHint: Vec3): Quat {
        val forward = Vec3().setTo(at).subInPlace(from).normalizeInPlace()
        val q1 = quatFromTo(Vec3(0.0, 0.0, -1.0), forward)
        val current = q1.rotate(Vec3.unitY(), Vec3())
        val wanted = Vec3().setTo(upHint).addScaledInPlace(forward, -(upHint dot forward)).normalizeInPlace()
        return quatFromTo(current, wanted) * q1
    }

    private val width = 1280
    private val height = 800

    /**
     * Nearly black pixels with ground either side across or down: a hole or a black skirt. Only in
     * the lower half, where the ground fills the view.
     */
    private fun holes(pixels: IntArray): Int {
        fun dark(c: Int) = ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255) < 40
        var count = 0
        for (y in height / 2 until height - 3) for (x in 3 until width - 3) {
            val c = pixels[y * width + x]
            if (!dark(c)) continue
            val across = !dark(pixels[y * width + x - 2]) && !dark(pixels[y * width + x + 2])
            val down = !dark(pixels[(y - 2) * width + x]) && !dark(pixels[(y + 2) * width + x])
            if (across || down) count++
        }
        return count
    }

    @Test fun noDarkSeamsFromHighUp() {
        TestGl.current()
        val out = File("../build/terrain-seam-review").apply { mkdirs() }
        val results = ArrayList<List<String>>()
        // The chase camera a metre or so from a part: the near plane at its closest, 0.5 m.
        for (tier in QualityTier.entries) for (altitude in listOf(50_000.0, 86_000.0)) {
            val name = "seam-${tier.name.lowercase()}-${(altitude / 1000).toInt()}km"
            results += shoot(out, name, tier, altitude, 1.0)
        }
        results.flatten().forEach(::println)
        for (line in results.flatten()) assertTrue(line, line.substringAfter(": ").substringBefore(" ").toInt() < 20)
    }

    /** Settles over the desert, then flies north at suborbital speed, shooting as it goes. */
    private fun shoot(out: File, name: String, tier: QualityTier, altitude: Double, nearest: Double): List<String> {
        // As the desktop draws: 4x MSAA but on LOW, resolved into a plain buffer.
        val samples = if (tier == QualityTier.LOW) 0 else minOf(4, glGetInteger(GL30C.GL_MAX_SAMPLES))
        val fbo = GL30C.glGenFramebuffers(); val resolved = GL30C.glGenFramebuffers()
        val colourBuffer = GL30C.glGenRenderbuffers(); val depthBuffer = GL30C.glGenRenderbuffers()
        val plainBuffer = GL30C.glGenRenderbuffers()
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, colourBuffer)
        GL30C.glRenderbufferStorageMultisample(GL30C.GL_RENDERBUFFER, samples, GL_RGBA8, width, height)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, colourBuffer)
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, depthBuffer)
        GL30C.glRenderbufferStorageMultisample(GL30C.GL_RENDERBUFFER, samples, GL30C.GL_DEPTH24_STENCIL8, width, height)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_RENDERBUFFER, depthBuffer)
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, resolved)
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, plainBuffer)
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL_RGBA8, width, height)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, plainBuffer)
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
        com.rm.apogee.render.gl.GLES30.screenFramebuffer = fbo
        fun resolve() {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo)
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, resolved)
            GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST)
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, resolved)
        }
        val bus = FrameBus()
        val renderer = GlRenderer({ tier }, bus)
        renderer.onSurfaceCreated(); renderer.onSurfaceChanged(width, height)
        val terrain = TerrainBuilder(renderer.terrainSource, tier)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val up = Vec3().setTo(desert)
            val camera = Vec3()
            var rotation = Quat.identity()
            val start = Vec3().setTo(desert)
            fun place(travelled: Double) {
                up.setTo(start).addScaledInPlace(north(start), travelled / radius).normalizeInPlace()
                camera.setTo(up).mulInPlace(radius + maxOf(field.elevation(start), 0.0) + altitude)
                // Looking ahead and down at about 40 degrees.
                val ahead = Vec3().setTo(up).addScaledInPlace(north(up), altitude * 1.2 / radius).normalizeInPlace()
                val target = Vec3().setTo(ahead).mulInPlace(radius + maxOf(field.elevation(ahead), 0.0))
                rotation = look(camera, target, up)
            }
            place(0.0)
            val sun = Vec3().setTo(up).addScaledInPlace(north(up), -0.6).addScaledInPlace(east(up), 0.3).normalizeInPlace()
            fun frame(): RenderFrame {
                val world = WorldView(
                    radius, terra.atmosphereHeight, terra.atmosphere?.scaleHeight ?: 1.0, sun, Vec3.unitY(), camera.length - radius, Quat.identity(),
                    terra.terrain!!.maxElevation, drawFarSurface = terrain.farSurfaceNeeded, chunkRange = terrain.chunkRange, time = 1000.0,
                    // As in flight over Terra: the sea's dark below its level.
                    seaRadius = radius,
                )
                return RenderFrame(1, System.nanoTime(), camera, rotation, Math.toRadians(60.0), emptyList(), world = world, nearestDistance = nearest)
            }
            fun step() {
                terrain.collect()
                terrain.followCraft(terra, camera, altitude, scope)
                terrain.requestGlobe(terra, scope)
                bus.clear(); bus.publish(frame())
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
                renderer.onDrawFrame()
            }
            val deadline = System.nanoTime() + 90_000_000_000L
            var readyAt = 0L
            while (System.nanoTime() < deadline) {
                step()
                if (terrain.patchReady) {
                    if (readyAt == 0L) readyAt = System.nanoTime()
                    if (System.nanoTime() - readyAt > 5_000_000_000L) break
                }
                Thread.sleep(10)
            }
            assertTrue("ground built", terrain.patchReady)
            val lines = ArrayList<String>()
            resolve()
            lines += "$name: ${read(out, name)} hole pixels"
            // 2.2 km/s north, by the clock, shooting after 3 s and 6 s.
            val flown = System.nanoTime()
            for (shot in 1..2) {
                while (System.nanoTime() - flown < shot * 3_000_000_000L) {
                    place((System.nanoTime() - flown) * 1e-9 * 2_200.0)
                    step()
                    Thread.sleep(5)
                }
                resolve()
                lines += "$name-fly$shot: ${read(out, "$name-fly$shot")} hole pixels"
            }
            return lines
        } finally {
            scope.cancel(); terrain.stop(); renderer.release(); bus.clear()
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0)
            com.rm.apogee.render.gl.GLES30.screenFramebuffer = 0
            GL30C.glDeleteFramebuffers(fbo); GL30C.glDeleteFramebuffers(resolved)
            GL30C.glDeleteRenderbuffers(colourBuffer); GL30C.glDeleteRenderbuffers(depthBuffer); GL30C.glDeleteRenderbuffers(plainBuffer)
        }
    }

    /** Writes the bound framebuffer to [name] and counts its holes. */
    private fun read(out: File, name: String): Int {
        val data = BufferUtils.createByteBuffer(width * height * 4)
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, data)
        val pixels = IntArray(width * height)
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val i = ((height - 1 - y) * width + x) * 4
            val c = ((data.get(i).toInt() and 255) shl 16) or ((data.get(i + 1).toInt() and 255) shl 8) or (data.get(i + 2).toInt() and 255)
            pixels[y * width + x] = c
            image.setRGB(x, y, c)
        }
        javax.imageio.ImageIO.write(image, "png", File(out, "$name.png"))
        println("wrote ${File(out, "$name.png").canonicalPath}")
        assertEquals(GL_NO_ERROR, glGetError())
        return holes(pixels)
    }
}
