package com.rm.apogee.desktop

import com.rm.apogee.platform.Log
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.gl.GLES30
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL30C.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The world, drawn off screen on its own thread and handed to Compose as an image each frame.
 *
 * A GL view in a Compose window always sits on top of the screens, so the world draws into a
 * framebuffer of a hidden GLFW window's context instead. Each frame is read back through two pixel
 * buffers, one frame behind, so reading never waits for the GPU. The image is upside down (GL's
 * rows go up); [WorldImage] turns it over as it draws.
 */
class WorldView {

    /** One frame read back: [image] is [width] by [height], and [number] counts up. */
    class Frame(val image: Image, val width: Int, val height: Int, val number: Long)

    /** The latest frame, for the window to draw. */
    val latest = AtomicReference<Frame?>(null)

    /** What the GL card is, once there's a context. */
    @Volatile var rendererName: String = ""
        private set

    @Volatile private var renderer: GlRenderer? = null
    @Volatile private var wantWidth = 1
    @Volatile private var wantHeight = 1
    @Volatile private var running = true

    /** The window's frames: one lets the world draw one, so it keeps to the screen's rate. */
    private val ticks = Semaphore(0)

    private val thread = thread(name = "world", isDaemon = true, priority = Thread.NORM_PRIORITY + 1) { run() }

    /** Called once, on the world thread, if there's no OpenGL 3.3 to draw with. */
    @Volatile var onNoGl: () -> Unit = {}

    /** Called on the world thread each frame after GLFW's events, for the controllers. */
    @Volatile var onPoll: () -> Unit = {}

    fun show(renderer: GlRenderer) { this.renderer = renderer }

    fun hide() {
        renderer = null
        latest.set(null)
    }

    val showing: Boolean get() = renderer != null

    /** The size to draw at, in pixels. */
    fun size(width: Int, height: Int) {
        wantWidth = width.coerceAtLeast(1)
        wantHeight = height.coerceAtLeast(1)
    }

    /** Called each window frame. */
    fun tick() {
        if (ticks.availablePermits() < 2) ticks.release()
    }

    fun close() {
        running = false
        ticks.release()
    }

    // --- on the world thread ---------------------------------------------------

    private var window = 0L
    private var current: GlRenderer? = null
    private var width = 0
    private var height = 0
    private var samples = 0
    private val fbo = IntArray(2)
    private val colour = IntArray(2)
    private var depth = 0
    private val pbo = IntArray(2)
    private var pboFilled = BooleanArray(2)
    private var pboIndex = 0
    private var pixels = ByteArray(0)
    private var frames = 0L

    /** The last few images, closed once Compose can no longer be drawing them. */
    private val recent = ArrayDeque<Image>()

    private fun run() {
        if (!glfwInit()) {
            Log.e(TAG, "GLFW won't start")
            onNoGl()
            return
        }
        while (running) {
            ticks.tryAcquire(100, TimeUnit.MILLISECONDS)
            if (!running) break
            glfwPollEvents()
            onPoll()
            val r = renderer
            if (r !== current) {
                // One context for the app's life, handed from one renderer to the next: making a
                // new one for each scene crashes NVIDIA's threaded driver.
                current?.release()
                current = r
                if (r == null) continue
                if (window == 0L && !createContext()) { renderer = null; current = null; continue }
                freeBuffers()
                r.onSurfaceCreated()
            }
            if (r == null) continue
            try {
                drawFrame(r)
            } catch (e: Throwable) {
                Log.e(TAG, "frame: $e")
                e.printStackTrace()
            }
        }
        current?.release()
        if (window != 0L) destroyContext()
        glfwTerminate()
    }

    private fun createContext(): Boolean {
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        if (GL_DEBUG) glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE)
        window = glfwCreateWindow(16, 16, "Apogee world", 0L, 0L)
        if (window == 0L) {
            Log.e(TAG, "No OpenGL 3.3 context")
            onNoGl()
            return false
        }
        glfwMakeContextCurrent(window)
        GL.createCapabilities()
        if (GL_DEBUG) {
            org.lwjgl.opengl.GLUtil.setupDebugMessageCallback(System.err)
            glEnable(org.lwjgl.opengl.GL43C.GL_DEBUG_OUTPUT_SYNCHRONOUS)
        }
        rendererName = glGetString(GL_RENDERER).orEmpty()
        samples = minOf(4, glGetInteger(GL_MAX_SAMPLES))
        Log.i(TAG, "GL ${glGetString(GL_VERSION)} on $rendererName, ${samples}x MSAA")
        width = 0; height = 0
        return true
    }

    private fun destroyContext() {
        freeBuffers()
        GLES30.screenFramebuffer = 0
        GL.setCapabilities(null)
        glfwMakeContextCurrent(0L)
        glfwDestroyWindow(window)
        window = 0L
        while (recent.isNotEmpty()) recent.removeFirst().close()
    }

    private fun drawFrame(r: GlRenderer) {
        val w = wantWidth
        val h = wantHeight
        if (w != width || h != height) {
            freeBuffers()
            makeBuffers(w, h)
            width = w; height = h
            GLES30.screenFramebuffer = fbo[0]
            r.onSurfaceChanged(w, h)
        }
        val started = System.nanoTime()
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
        r.onDrawFrame()
        val drawn = System.nanoTime()

        // The multisampled frame into a plain one, then into this frame's pixel buffer.
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo[0])
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo[1])
        glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo[1])
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[pboIndex])
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
        pboFilled[pboIndex] = true

        // Last frame's, which the GPU has finished by now.
        val other = 1 - pboIndex
        if (pboFilled[other]) {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[other])
            val mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, w.toLong() * h * 4, GL_MAP_READ_BIT)
            if (mapped != null) {
                mapped.get(pixels, 0, w * h * 4)
                glUnmapBuffer(GL_PIXEL_PACK_BUFFER)
                publish(w, h)
            }
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
        pboIndex = other
        timing(drawn - started, System.nanoTime() - drawn)
    }

    private fun publish(w: Int, h: Int) {
        val image = Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), pixels, w * 4)
        latest.set(Frame(image, w, h, ++frames))
        recent.addLast(image)
        while (recent.size > KEPT_IMAGES) recent.removeFirst().close()
    }

    private fun makeBuffers(w: Int, h: Int) {
        glGenFramebuffers(fbo)
        val ms = glGenRenderbuffers()
        colour[0] = ms
        glBindRenderbuffer(GL_RENDERBUFFER, ms)
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, w, h)
        depth = glGenRenderbuffers()
        glBindRenderbuffer(GL_RENDERBUFFER, depth)
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH24_STENCIL8, w, h)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, ms)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth)

        colour[1] = glGenRenderbuffers()
        glBindRenderbuffer(GL_RENDERBUFFER, colour[1])
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[1])
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colour[1])
        glBindRenderbuffer(GL_RENDERBUFFER, 0)

        glGenBuffers(pbo)
        for (b in pbo) {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, b)
            glBufferData(GL_PIXEL_PACK_BUFFER, w.toLong() * h * 4, GL_STREAM_READ)
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        pboFilled = BooleanArray(2)
        pixels = ByteArray(w * h * 4)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (status != GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "framebuffer incomplete: $status")
    }

    private fun freeBuffers() {
        if (width == 0) return
        glDeleteFramebuffers(fbo)
        glDeleteRenderbuffers(colour)
        glDeleteRenderbuffers(depth)
        glDeleteBuffers(pbo)
        width = 0; height = 0
    }

    // How long drawing and reading back take, logged every 5 seconds with APOGEE_PERF set.
    private var drawNanos = 0L
    private var readNanos = 0L
    private var timed = 0
    private var timedSince = System.nanoTime()

    private fun timing(draw: Long, read: Long) {
        if (!PERF) return
        drawNanos += draw; readNanos += read; timed++
        val now = System.nanoTime()
        if (now - timedSince > 5_000_000_000L) {
            val fps = timed * 1e9 / (now - timedSince)
            Log.i(TAG, "%dx%d %.1f fps, draw %.2f ms, read %.2f ms".format(width, height, fps, drawNanos / 1e6 / timed, readNanos / 1e6 / timed))
            drawNanos = 0; readNanos = 0; timed = 0; timedSince = now
        }
    }

    private companion object {
        const val TAG = "ApogeeWorld"
        const val KEPT_IMAGES = 3

        /** GL's own messages to standard error, for finding a bad call. */
        val GL_DEBUG = System.getenv("APOGEE_GL_DEBUG") != null
        val PERF = System.getenv("APOGEE_PERF") != null
    }
}
