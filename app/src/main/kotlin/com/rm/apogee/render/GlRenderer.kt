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
import kotlin.math.tan

/**
 * Draws whatever the game thread last published to the [FrameBus].
 *
 * Runs on GLSurfaceView's own thread in RENDERMODE_CONTINUOUSLY. It never
 * touches simulation state directly - it reads one immutable [RenderFrame] pair
 * and interpolates - so no amount of work here can stall the physics, and no
 * physics tick can stall a frame.
 *
 * **Two depth passes, not one.** A 600 km planet and a 1 m fuel tank cannot
 * share a depth buffer: any near/far pair that resolves centimetres on a part
 * cannot reach the horizon, and any pair that reaches the horizon quantises the
 * craft into z-fighting mush. So the world is drawn first against a far
 * frustum, the depth buffer is cleared, and craft are drawn against a near one.
 * GLES has no reliable `glClipControl`, so reversed-Z is not available, and
 * logarithmic depth misbehaves across the very large triangles a planet mesh is
 * made of - which leaves this, the approach space sims have used for years.
 *
 * The cost is that a craft is always drawn in front of the planet, including
 * when it is behind it. With a chase camera on one craft that never arises;
 * when it does - other players' craft on the far side of a world - they will
 * need binning into the far pass by distance.
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

    private var vesselProgram: ShaderProgram? = null
    private var skyProgram: ShaderProgram? = null
    private var terrainProgram: ShaderProgram? = null

    /**
     * Terrain geometry, handed over by the game thread.
     *
     * Built by sampling the simulation's own height field, which takes long
     * enough that it cannot happen on the GL thread. The producer swaps a
     * finished mesh in here and the renderer uploads it on the next frame.
     */
    val terrainSource = TerrainSource()
    private var globeMesh: TerrainMesh? = null
    private var uploadedGlobe = 0

    /** The triangle list every chunk shares. */
    private var chunkIndices: SharedIndexBuffer? = null
    private var scatterRenderer: ScatterRenderer? = null

    /** Chunks on the GPU, and the frame each was last drawn in. */
    private val chunkMeshes = HashMap<ChunkKey, TerrainMesh>()
    private val chunkLastDrawn = HashMap<ChunkKey, Long>()
    private var frameCounter = 0L

    /**
     * One mesh per distinct shape, built on first sight.
     *
     * Keyed by the MeshSpec value itself, so a rocket with three identical fuel
     * tanks uploads one mesh and draws it three times. Cleared whenever the GL
     * context is recreated, because every handle in it is then dangling.
     */
    private val meshes = HashMap<Pair<MeshSpec, Int>, Mesh>()
    private var lineProgram: ShaderProgram? = null
    /** Reused across frames; orbits are re-uploaded, not reallocated. */
    private val lineMeshes = ArrayList<LineMesh>()
    private var emptyVao = IntArray(1)
    private var lineScratch = FloatArray(0)

    // Preallocated: allocating per draw call would put the GC on the render path.
    private val modelMatrix = Mat4()
    private val viewMatrix = Mat4()
    private val nearProjection = Mat4()
    private val farProjection = Mat4()
    private val nearViewProjection = Mat4()
    private val farViewProjection = Mat4()
    private val interpolatedPosition = Vec3()
    private val interpolatedRotation = Quat()
    private val interpolatedCameraPos = Vec3()
    private val interpolatedCameraRot = Quat()
    private val cameraRight = Vec3()
    private val cameraUp = Vec3()
    private val cameraForward = Vec3()
    private val upDirection = Vec3()
    private val scratchChunkCentre = Vec3()

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var lastDrawNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        qualityTier = QualityTier.detect(context)
        onTierDetected(qualityTier)

        GLES30.glClearColor(0.01f, 0.012f, 0.03f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        // The EGL context can be lost and recreated (surface teardown, some
        // driver events), so every GL object is rebuilt here rather than in the
        // constructor. Anything cached across this boundary is a dangling name.
        releaseGlObjects()

        vesselProgram = ShaderProgram(Shaders.VESSEL_VERTEX, Shaders.VESSEL_FRAGMENT, "vessel")
        skyProgram = ShaderProgram(Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT, "sky")
        terrainProgram = ShaderProgram(Shaders.TERRAIN_VERTEX, Shaders.TERRAIN_FRAGMENT, "terrain")
        lineProgram = ShaderProgram(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT, "line")

        globeMesh = TerrainMesh()
        uploadedGlobe = 0
        chunkIndices = SharedIndexBuffer(TerrainChunk.indices)
        scatterRenderer = ScatterRenderer()

        // The sky shader generates its own vertices, but GLES still requires a
        // bound vertex array object to draw.
        GLES30.glGenVertexArrays(1, emptyVao, 0)
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
        val latest = pair.latest
        val previous = pair.previous

        // Interpolation factor between the two most recent published states.
        // The server streams at 20 Hz and the display may be at 60, 90 or 120,
        // so rendering the newest state verbatim would judder even though the
        // simulation is perfectly smooth.
        val alpha = if (previous == null) {
            1.0
        } else {
            val span = (latest.timestampNanos - previous.timestampNanos).toDouble()
            if (span <= 0.0) 1.0
            else ((now - previous.timestampNanos).toDouble() / span).coerceIn(0.0, 1.0)
        }

        val cameraPos = interpolateCamera(previous, latest, alpha)
        val aspect = viewportWidth.toDouble() / viewportHeight.toDouble()

        viewMatrix.setViewFromCameraRotation(interpolatedCameraRot)
        interpolatedCameraRot.rotate(Vec3.unitX(), cameraRight)
        interpolatedCameraRot.rotate(Vec3.unitY(), cameraUp)
        interpolatedCameraRot.rotate(Vec3(0.0, 0.0, -1.0), cameraForward)

        // --- far pass: sky and planet -------------------------------------
        val world = latest.world
        if (world != null) {
            farProjection.setPerspective(latest.fovYRadians, aspect, FAR_NEAR_PLANE, FAR_FAR_PLANE)
            farViewProjection.setMultiplied(farProjection, viewMatrix)

            val atmosphereFactor = atmosphereFactorAt(world)
            drawSky(latest, world, cameraPos, aspect, atmosphereFactor)
            uploadPendingTerrain()
            drawGlobe(world, cameraPos, atmosphereFactor)
            // Trajectories belong in the far pass: an orbit is hundreds of
            // kilometres across and would be clipped away by the near frustum.
            drawLines(latest, cameraPos)

            // Reclaim the whole depth range for the near pass.
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        }

        // --- near pass: craft ----------------------------------------------
        nearProjection.setPerspective(latest.fovYRadians, aspect, NEAR_NEAR_PLANE, NEAR_FAR_PLANE)
        nearViewProjection.setMultiplied(nearProjection, viewMatrix)
        // The patch belongs here, not in the far pass. The far pass starts at
        // a hundred metres, and clipping the nearest hundred metres of ground
        // leaves the craft standing at the edge of a hole with sky underneath
        // it - which is exactly what it looked like.
        if (world != null) drawChunks(world, cameraPos, atmosphereFactorAt(world))
        drawVessels(latest, previous, alpha, cameraPos)
    }

    /**
     * How much atmosphere is overhead: 1 at the datum, 0 in vacuum.
     *
     * On the same exponential the simulation uses for density, so the sky fades
     * out exactly where drag and engine performance say it should rather than
     * at some separately-tuned altitude.
     */
    private fun atmosphereFactorAt(world: WorldView): Float {
        if (world.atmosphereHeight <= 0.0) return 0f
        if (world.cameraAltitude >= world.atmosphereHeight) return 0f
        val density = kotlin.math.exp(
            -world.cameraAltitude.coerceAtLeast(0.0) / world.atmosphereScaleHeight
        )
        // Raised to a fractional power so the sky stays convincingly opaque
        // through the low atmosphere. Straight density has already dropped to
        // 0.87 at 800 m, which is enough for stars to show through in daylight
        // a few hundred metres off the pad.
        return Math.pow(density, 0.30).toFloat()
    }

    private fun drawSky(
        frame: RenderFrame,
        world: WorldView,
        cameraPos: Vec3,
        aspect: Double,
        atmosphereFactor: Float,
    ) {
        val shader = skyProgram ?: return
        shader.use()
        // The sky is behind everything, so it neither tests nor writes depth.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        shader.setVec3("uCameraRight", cameraRight.x.toFloat(), cameraRight.y.toFloat(), cameraRight.z.toFloat())
        shader.setVec3("uCameraUp", cameraUp.x.toFloat(), cameraUp.y.toFloat(), cameraUp.z.toFloat())
        shader.setVec3("uCameraForward", cameraForward.x.toFloat(), cameraForward.y.toFloat(), cameraForward.z.toFloat())
        shader.setFloat("uTanHalfFov", tan(frame.fovYRadians * 0.5).toFloat())
        shader.setFloat("uAspect", aspect.toFloat())

        upDirection.setTo(cameraPos).normalizeInPlace()
        if (upDirection.lengthSq < 0.5) upDirection.setTo(Vec3.unitY())
        shader.setVec3("uUpDirection", upDirection.x.toFloat(), upDirection.y.toFloat(), upDirection.z.toFloat())
        shader.setVec3(
            "uSunDirection",
            world.sunDirection.x.toFloat(),
            world.sunDirection.y.toFloat(),
            world.sunDirection.z.toFloat(),
        )
        shader.setFloat("uAtmosphereFactor", atmosphereFactor)

        GLES30.glBindVertexArray(emptyVao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)

        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** Takes whatever the game thread has finished building. */
    private fun uploadPendingTerrain() {
        terrainSource.globe(uploadedGlobe)?.let { pending ->
            globeMesh?.upload(pending.data.vertices, pending.data.indices)
            uploadedGlobe = pending.revision
        }
    }

    /** The whole body, for the view from any distance. */
    private fun drawGlobe(world: WorldView, cameraPos: Vec3, atmosphereFactor: Float) {
        val shader = terrainProgram ?: return
        val mesh = globeMesh ?: return
        if (!mesh.isReady || !world.drawFarSurface) return

        shader.use()
        // Globe vertices are in body radii, so the model matrix scales them -
        // and rotates them, because terrain turns with the planet.
        modelMatrix.setFromTrs(Vec3.zero(), world.bodyRotation, cameraPos, world.radius)
        applySurfaceUniforms(shader, world, atmosphereFactor)
        mesh.draw()
    }

    /**
     * The chunks the game side chose, drawn in the near pass over the globe.
     *
     * Each chunk's vertices are metres from its own centre, and the camera
     * subtraction happens here in double against numbers in the hundreds of
     * thousands - so what reaches float is a handful of metres, and the ground
     * does not shimmer at the equator.
     */
    private fun drawChunks(world: WorldView, cameraPos: Vec3, atmosphereFactor: Float) {
        val shader = terrainProgram ?: return
        val indices = chunkIndices ?: return
        val list = terrainSource.drawList()
        frameCounter++

        // Upload what is new, a few per frame: each is a buffer allocation and
        // a copy, and a burst of dozens on the first frame over new ground is
        // a visible hitch. Anything not yet uploaded is skipped this frame;
        // the builder only lists built chunks, so it is a frame late, not a hole
        // that lasts.
        var uploads = 0
        for (chunk in list) {
            if (chunkMeshes.containsKey(chunk.key)) continue
            if (uploads >= MAX_CHUNK_UPLOADS_PER_FRAME) break
            val vertices = chunk.vertices ?: terrainSource.takePending(chunk.key)?.vertices ?: continue
            val mesh = TerrainMesh(indices)
            mesh.upload(vertices)
            chunk.vertices = null
            terrainSource.takePending(chunk.key)
            chunkMeshes[chunk.key] = mesh
            uploads++
        }

        shader.use()
        applySurfaceUniforms(shader, world, atmosphereFactor)
        shader.setMat4("uViewProjection", nearViewProjection.m)
        for (chunk in list) {
            val mesh = chunkMeshes[chunk.key] ?: continue
            world.bodyRotation.rotate(chunk.centre, scratchChunkCentre)
            // Behind the camera by more than the chunk's own size: nothing of
            // it can be on screen. Cheap, and usually half the chunks.
            scratchChunkCentre.subInPlace(cameraPos)
            if ((scratchChunkCentre dot cameraForward) < -chunk.boundingRadius) continue
            scratchChunkCentre.addInPlace(cameraPos)
            modelMatrix.setFromTrs(scratchChunkCentre, world.bodyRotation, cameraPos)
            shader.setMat4("uModel", modelMatrix.m)
            mesh.draw()
            chunkLastDrawn[chunk.key] = frameCounter
        }

        evictChunks(list)

        scatterRenderer?.draw(
            terrainSource.scatter.drawList(),
            world.bodyRotation,
            cameraPos,
            cameraForward,
            nearViewProjection.m,
            world.sunDirection,
            atmosphereFactor,
            (world.atmosphereScaleHeight * 8.0).toFloat(),
        )
    }

    /** Drops the longest-unused chunks once the GPU holds more than its budget. */
    private fun evictChunks(current: List<ChunkData>) {
        val budget = qualityTier.terrainChunkBudget
        if (chunkMeshes.size <= budget) return
        val inUse = current.mapTo(HashSet()) { it.key }
        val candidates = chunkMeshes.keys.filter { it !in inUse }
            .sortedBy { chunkLastDrawn[it] ?: 0L }
        for (key in candidates.take(chunkMeshes.size - budget)) {
            chunkMeshes.remove(key)?.release()
            chunkLastDrawn.remove(key)
            terrainSource.discarded(key)
        }
    }

    private fun applySurfaceUniforms(
        shader: ShaderProgram,
        world: WorldView,
        atmosphereFactor: Float,
    ) {
        shader.setMat4("uModel", modelMatrix.m)
        shader.setMat4("uViewProjection", farViewProjection.m)
        shader.setVec3(
            "uSunDirection",
            world.sunDirection.x.toFloat(),
            world.sunDirection.y.toFloat(),
            world.sunDirection.z.toFloat(),
        )
        shader.setFloat("uAtmosphereFactor", atmosphereFactor)
        shader.setFloat("uHazeDistance", (world.atmosphereScaleHeight * 8.0).toFloat())
        shader.setFloat("uHasAtmosphere", if (world.atmosphereHeight > 0.0) 1f else 0f)
    }

    /**
     * Draws trajectory polylines.
     *
     * Depth writes are off so a conic that passes behind the planet still
     * reads as a continuous path rather than being sliced into arcs by its own
     * far side - which is what a map view is for.
     */
    private fun drawLines(frame: RenderFrame, cameraPos: Vec3) {
        if (frame.lines.isEmpty()) return
        val shader = lineProgram ?: return
        shader.use()
        shader.setMat4("uViewProjection", farViewProjection.m)
        modelMatrix.setIdentity()
        shader.setMat4("uModel", modelMatrix.m)
        GLES30.glDepthMask(false)

        while (lineMeshes.size < frame.lines.size) lineMeshes.add(LineMesh())

        frame.lines.forEachIndexed { index, line ->
            val needed = line.points.size * 3
            if (lineScratch.size < needed) lineScratch = FloatArray(needed)
            line.points.forEachIndexed { i, point ->
                // Camera-relative, subtracted in double before narrowing.
                lineScratch[i * 3] = (point.x - cameraPos.x).toFloat()
                lineScratch[i * 3 + 1] = (point.y - cameraPos.y).toFloat()
                lineScratch[i * 3 + 2] = (point.z - cameraPos.z).toFloat()
            }
            val mesh = lineMeshes[index]
            mesh.upload(lineScratch.copyOf(needed))
            shader.setVec4("uColor", line.color)
            mesh.draw()
        }

        GLES30.glDepthMask(true)
    }

    private fun drawVessels(
        latest: RenderFrame,
        previous: RenderFrame?,
        alpha: Double,
        cameraPos: Vec3,
    ) {
        val shader = vesselProgram ?: return
        shader.use()
        shader.setMat4("uViewProjection", nearViewProjection.m)

        // Light from the star when there is one, else a fixed key light so the
        // assembly building is not lit from nowhere.
        val sun = latest.world?.sunDirection
        if (sun != null) {
            shader.setVec3("uLightDirection", (-sun.x).toFloat(), (-sun.y).toFloat(), (-sun.z).toFloat())
        } else {
            shader.setVec3("uLightDirection", -0.42f, -0.57f, -0.71f)
        }

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
            meshFor(item.meshSpec, item.caps).draw()
        }
    }

    /**
     * Builds and caches the GPU mesh for a shape the first time it is drawn.
     *
     * Keyed on the cap mask as well as the shape, because a tank buried in a
     * stack and the same tank standing alone are different meshes. At most
     * four variants per shape, and in practice two.
     */
    private fun meshFor(spec: MeshSpec, caps: Int): Mesh = meshes.getOrPut(spec to caps) {
        when (spec) {
            is MeshSpec.Cylinder ->
                MeshBuilder.cylinder(spec.radius.toFloat(), spec.height.toFloat(), caps = caps)
            is MeshSpec.Cone -> MeshBuilder.frustum(
                spec.bottomRadius.toFloat(),
                spec.topRadius.toFloat(),
                spec.height.toFloat(),
                caps = caps,
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

    private fun releaseGlObjects() {
        vesselProgram?.release(); vesselProgram = null
        skyProgram?.release(); skyProgram = null
        terrainProgram?.release(); terrainProgram = null
        globeMesh?.release(); globeMesh = null
        uploadedGlobe = 0
        // Every chunk on the GPU is gone with the context; say so, so they are
        // built again rather than drawn from names that no longer exist.
        for (key in chunkMeshes.keys) terrainSource.discarded(key)
        chunkMeshes.values.forEach { it.release() }
        chunkMeshes.clear()
        chunkLastDrawn.clear()
        chunkIndices?.release(); chunkIndices = null
        scatterRenderer?.release(); scatterRenderer = null
        meshes.values.forEach { it.release() }
        meshes.clear()
        lineProgram?.release(); lineProgram = null
        lineMeshes.forEach { it.release() }
        lineMeshes.clear()
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private companion object {
        /**
         * Terrain chunks uploaded per frame at most. Each is ~50 KB; a dozen
         * is well inside a frame, and first arrival over new ground spreads
         * over a few frames rather than landing in one.
         */
        const val MAX_CHUNK_UPLOADS_PER_FRAME = 12

        /**
         * Near pass: parts and the ground underfoot.
         *
         * Half a metre rather than twenty centimetres: nothing is drawn closer
         * than the camera's own minimum stand-off, and every doubling of the
         * near plane is a doubling of depth precision across the whole range -
         * which this pass now needs, because it carries terrain out to
         * the terrain patch as well as parts at arm's length.
         */
        const val NEAR_NEAR_PLANE = 0.5

        /**
         * Far enough for the largest patch. Depth resolution at the far end
         * works out around twenty metres, which would matter for two surfaces
         * meeting at a shallow angle and does not for a heightfield, where
         * nothing is coplanar with anything.
         */
        const val NEAR_FAR_PLANE = 250_000.0

        /** Far pass: the planet and anything else at world scale. */
        const val FAR_NEAR_PLANE = 100.0
        const val FAR_FAR_PLANE = 1.0e8
    }
}
