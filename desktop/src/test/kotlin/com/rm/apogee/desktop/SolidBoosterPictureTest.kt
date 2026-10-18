package com.rm.apogee.desktop

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.CloudCover
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.world.LaunchTime
import com.rm.apogee.core.world.World
import com.rm.apogee.game.GameSession
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.QualityTier
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*
import org.lwjgl.opengl.GL30C
import java.io.File

/**
 * The Sparkler by eye: on the Cape pad, then lit just after liftoff with its Flints' flame and
 * smoke, and a little later from further off. The same for a Pyre with two Torches. Writes
 * pictures to build/srb-review.
 * `SRB_PICTURES=1 ./gradlew :desktop:test --tests '*SolidBoosterPicture*'`
 */
class SolidBoosterPictureTest {
    private val width = 960
    private val height = 720

    @Test fun pictures() {
        assumeTrue(System.getenv("SRB_PICTURES") == "1")
        shots(StockCraft.sparkler(StockParts.catalog), "srb", 13.0, 30.0, 70.0)
    }

    /** A Pyre with a Torch on a side clamp either side, on the pad and lifting off. */
    @Test fun torchAndPyre() {
        assumeTrue(System.getenv("SRB_PICTURES") == "1")
        val catalog = StockParts.catalog
        val builder = com.rm.apogee.core.craft.CraftBuilder(catalog)
        check(builder.placeRoot("srb-pyre"))
        builder.symmetry = com.rm.apogee.core.craft.SymmetryMode.MIRROR
        val clamps = builder.attach("decoupler-side", builder.openNodes().first { it.partIndex == 0 && it.node.id == "surface-0" })
        builder.symmetry = com.rm.apogee.core.craft.SymmetryMode.NONE
        for (clamp in clamps) builder.attach("srb-torch", builder.openNodes().first { it.partIndex == clamp && it.node.id == "mount" })
        builder.restage()
        shots(builder.design.copy(name = "Torch and Pyre"), "srb-torch-pyre", 30.0, 60.0, 120.0)
    }

    private fun shots(design: com.rm.apogee.core.craft.CraftDesign, prefix: String, near: Double, lit: Double, far: Double) {
        TestGl.current()
        val out = File("../build/srb-review").apply { mkdirs() }
        val fbo = GL30C.glGenFramebuffers()
        val colourBuffer = GL30C.glGenRenderbuffers(); val depthBuffer = GL30C.glGenRenderbuffers()
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, colourBuffer)
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL_RGBA8, width, height)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, colourBuffer)
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, depthBuffer)
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_DEPTH24_STENCIL8, width, height)
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_RENDERBUFFER, depthBuffer)

        val frameBus = FrameBus()
        val renderer = GlRenderer({ QualityTier.MEDIUM }, frameBus)
        // The shadow pass ends bound to the screen, so off here, where it draws into a target.
        renderer.shadowChoice = com.rm.apogee.render.ShadowQuality.OFF
        renderer.onSurfaceCreated()
        renderer.onSurfaceChanged(width, height)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val world = World.default(StockParts.catalog).also { it.ensureStructures() }
        val session = GameSession.hostLocal(
            frameBus = frameBus, perfHints = null, playerName = "Srb", clientId = "srb-client",
            design = design, scope = scope, world = world,
            freshFlight = true, weather = WeatherIntensity.CALM, clouds = CloudCover.LIGHT, launchTime = LaunchTime.NOON,
        )
        session.attachTerrain(renderer.terrainSource, QualityTier.MEDIUM)
        session.start(scope)
        try {
            fun draw(seconds: Double) {
                val end = System.nanoTime() + (seconds * 1e9).toLong()
                while (System.nanoTime() < end) {
                    GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
                    renderer.onDrawFrame()
                    Thread.sleep(15)
                }
            }
            fun shoot(name: String) {
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
                val frame = frameBus.latest()?.latest
                println("$name: frame ${frame != null}, items ${frame?.items?.size}, particles ${frame?.particles?.size}")
                javax.imageio.ImageIO.write(image, "png", File(out, "$name.png"))
                println("wrote ${File(out, "$name.png").canonicalPath}")
            }

            // Settled on the pad, the ground built round it.
            draw(25.0)
            session.camera.yaw = 0.9; session.camera.pitch = 0.12; session.camera.distance = near
            draw(2.0)
            shoot("$prefix-pad")

            runBlocking { session.setThrottle(1.0); session.stage() }
            session.camera.distance = lit; session.camera.pitch = 0.05
            draw(2.5)
            shoot("$prefix-liftoff")
            session.camera.distance = far; session.camera.pitch = -0.05
            draw(3.5)
            shoot("$prefix-climb")
        } finally {
            session.stop()
            scope.cancel()
            renderer.release()
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0)
            GL30C.glDeleteFramebuffers(fbo)
        }
    }
}
