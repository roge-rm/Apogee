package com.rm.apogee.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.rm.apogee.core.math.Mat4
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * Draws whatever the simulation thread last published to the [FrameBus].
 *
 * Runs on GLSurfaceView's own thread in RENDERMODE_CONTINUOUSLY. It never
 * touches simulation state directly - it reads one immutable [RenderFrame] pair
 * and interpolates - so no amount of work here can stall the physics, and no
 * physics tick can stall a frame.
 */
class GlRenderer(
    private val context: Context,
    private val frameBus: FrameBus,
    /**
     * Called once, on the GL thread, as soon as a context exists and the
     * device can actually be judged. The caller persists it so the Settings
     * screen can report a real tier rather than a placeholder.
     */
    private val onTierDetected: (QualityTier) -> Unit = {},
) : GLSurfaceView.Renderer {

    /** Published for the debug overlay; read from the UI thread. */
    val lastFrameTimeNanos = AtomicLong(0)
    @Volatile var qualityTier: QualityTier = QualityTier.MEDIUM
        private set

    private var program: ShaderProgram? = null

    /**
     * One mesh per distinct shape, built on first sight.
     *
     * Keyed by the MeshSpec value itself, so a rocket with three identical fuel
     * tanks uploads one mesh and draws it three times. Cleared whenever the GL
     * context is recreated, because every handle in it is then dangling.
     */
    private val meshes = HashMap<MeshSpec, Mesh>()

    // Preallocated: allocating per draw call would put the GC on the render path.
    private val modelMatrix = Mat4()
    private val viewMatrix = Mat4()
    private val projectionMatrix = Mat4()
    private val viewProjection = Mat4()
    private val interpolatedPosition = Vec3()
    private val interpolatedRotation = Quat()
    private val interpolatedCameraPos = Vec3()
    private val interpolatedCameraRot = Quat()

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var lastDrawNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        qualityTier = QualityTier.detect(context)
        onTierDetected(qualityTier)

        GLES30.glClearColor(0.035f, 0.047f, 0.094f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        // The EGL context can be lost and recreated (surface teardown, some
        // driver events), so every GL object is rebuilt here rather than in the
        // constructor. Anything cached across this boundary is a dangling name.
        program?.release()
        meshes.values.forEach { it.release() }
        meshes.clear()

        program = ShaderProgram(Shaders.VESSEL_VERTEX, Shaders.VESSEL_FRAGMENT, "vessel")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(1, width)
        viewportHeight = max(1, height)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastDrawNanos != 0L) lastFrameTimeNanos.set(now - lastDrawNanos)
        lastDrawNanos = now

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val pair = frameBus.latest() ?: return
        val shader = program ?: return

        val latest = pair.latest
        val previous = pair.previous

        // Interpolation factor between the two most recent simulation states.
        // The simulation is a fixed 60 Hz and the display is whatever it is, so
        // rendering the newest state verbatim would judder even though the
        // physics is smooth.
        val alpha = if (previous == null) {
            1.0
        } else {
            val span = (latest.timestampNanos - previous.timestampNanos).toDouble()
            if (span <= 0.0) 1.0
            else ((now - previous.timestampNanos).toDouble() / span).coerceIn(0.0, 1.0)
        }

        val cameraPos = interpolateCamera(previous, latest, alpha)

        projectionMatrix.setPerspective(
            fovYRadians = latest.fovYRadians,
            aspect = viewportWidth.toDouble() / viewportHeight.toDouble(),
            // Wide enough for a part at arm's length and a horizon kilometres
            // away. A craft in orbit looking at the planet needs far more than
            // this, which is what the logarithmic-depth work in the renderer's
            // next pass is for.
            near = 0.2,
            far = 200_000.0,
        )
        viewMatrix.setViewFromCameraRotation(interpolatedCameraRot)
        viewProjection.setMultiplied(projectionMatrix, viewMatrix)

        shader.use()
        shader.setMat4("uViewProjection", viewProjection.m)
        // A fixed key light until there is a real sun direction from :core.
        shader.setVec3("uLightDirection", -0.42f, -0.57f, -0.71f)

        for ((index, item) in latest.items.withIndex()) {
            val prevItem = previous?.items?.getOrNull(index)
            val position: Vec3
            val rotation: Quat
            if (prevItem != null && prevItem.meshSpec == item.meshSpec) {
                interpolatedPosition.setTo(
                    lerp(prevItem.position.x, item.position.x, alpha),
                    lerp(prevItem.position.y, item.position.y, alpha),
                    lerp(prevItem.position.z, item.position.z, alpha),
                )
                Quat.slerp(prevItem.rotation, item.rotation, alpha, interpolatedRotation)
                position = interpolatedPosition
                rotation = interpolatedRotation
            } else {
                position = item.position
                rotation = item.rotation
            }

            modelMatrix.setFromTrs(position, rotation, cameraPos)
            shader.setMat4("uModel", modelMatrix.m)
            shader.setVec4("uColor", item.color)
            meshFor(item.meshSpec).draw()
        }
    }

    /** Builds and caches the GPU mesh for a shape the first time it is drawn. */
    private fun meshFor(spec: MeshSpec): Mesh = meshes.getOrPut(spec) {
        when (spec) {
            is MeshSpec.Cylinder ->
                MeshBuilder.cylinder(spec.radius.toFloat(), spec.height.toFloat())
            is MeshSpec.Cone -> MeshBuilder.frustum(
                spec.bottomRadius.toFloat(),
                spec.topRadius.toFloat(),
                spec.height.toFloat(),
            )
            is MeshSpec.Box -> MeshBuilder.box(
                (spec.width * 0.5).toFloat(),
                (spec.height * 0.5).toFloat(),
                (spec.depth * 0.5).toFloat(),
            )
            is MeshSpec.Sphere -> MeshBuilder.sphere(spec.radius.toFloat())
        }
    }

    private fun interpolateCamera(previous: RenderFrame?, latest: RenderFrame, alpha: Double): Vec3 {
        if (previous == null) {
            interpolatedCameraRot.setTo(latest.cameraRotation)
            return interpolatedCameraPos.setTo(latest.cameraPosition)
        }
        Quat.slerp(previous.cameraRotation, latest.cameraRotation, alpha, interpolatedCameraRot)
        return interpolatedCameraPos.setTo(
            lerp(previous.cameraPosition.x, latest.cameraPosition.x, alpha),
            lerp(previous.cameraPosition.y, latest.cameraPosition.y, alpha),
            lerp(previous.cameraPosition.z, latest.cameraPosition.z, alpha),
        )
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
}
