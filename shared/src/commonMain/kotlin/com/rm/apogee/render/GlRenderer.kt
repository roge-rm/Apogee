package com.rm.apogee.render

import com.rm.apogee.render.gl.GLES30
import com.rm.apogee.render.gl.GlData
import com.rm.apogee.core.math.Mat4
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.platform.AtomicLong
import kotlin.math.max
import kotlin.math.tan
import com.rm.apogee.platform.System
import com.rm.apogee.platform.synchronized
import com.rm.apogee.platform.format
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile

/**
 * Draws whatever the game thread last published to the [FrameBus].
 *
 * It runs on GLSurfaceView's own thread in RENDERMODE_CONTINUOUSLY. It never touches simulation
 * state directly (it reads one [RenderFrame] pair that never changes, and interpolates), so no
 * amount of work here can stall the physics, and no physics tick can stall a frame.
 *
 * **Two depth passes, not one.** A 600 km planet and a 1 m fuel tank can't share a depth buffer.
 * Any near/far pair that resolves centimetres on a part can't reach the horizon, and any pair that
 * reaches the horizon quantises the craft into z-fighting mush. So the world is drawn first against
 * a far frustum, the depth buffer is cleared, and craft are drawn against a near one. GLES has no
 * reliable `glClipControl`, so reversed-Z isn't available, and logarithmic depth misbehaves across
 * the very large triangles a planet mesh is made of. That leaves this, which is what space sims
 * have done for years.
 *
 * The cost is that a craft is always drawn in front of the planet, even when it's behind it. With a
 * chase camera on one craft that never comes up. When it does (other players' craft on the far side
 * of a world), they'll need sorting into the far pass by distance.
 */
class GlRenderer(
    /** Picks the device's quality tier, once there's a context to judge it by. */
    private val detectTier: () -> QualityTier,
    private val frameBus: FrameBus,
    /**
     * Called once, on the GL thread, as soon as a context exists and the device can really be
     * judged. The caller saves it so the Settings screen can show a real tier instead of a
     * placeholder.
     */
    private val onTierDetected: (QualityTier) -> Unit = {},
) {

    /** Published for the debug overlay, and read from the UI thread. */
    val lastFrameTimeNanos = AtomicLong(0)

    /** Frames drawn since the start, for the debug performance log. */
    val framesDrawn = AtomicLong(0)

    /**
     * Debug: time each pass, with the GPU finished before the clock is read. The frame is slower
     * while this is on, but each pass's share is its own.
     */
    @Volatile var timePasses = false
    private val passNanos = LongArray(PASS_NAMES.size)
    private var passFrames = 0
    private var passMark = 0L
    private var passItems = 0L
    private var passFarItems = 0L
    private var passParticles = 0L
    private var passSingles = 0L
    private var passBatches = 0L

    private fun mark(pass: Int) {
        if (!timePasses) return
        GLES30.glFinish()
        val now = System.nanoTime()
        passNanos[pass] += now - passMark
        passMark = now
    }

    /** The pass timings since the last call, as a line, or null if there are none. */
    fun takePassReport(): String? = synchronized(passNanos) {
        if (passFrames == 0) return null
        val line = PASS_NAMES.indices.joinToString(" ") { "%s %.1f".format(PASS_NAMES[it], passNanos[it] / 1e6 / passFrames) } +
            " ms | items %d far %d particles %d | see-through drawn singly %d, in batches %d".format(
                passItems / passFrames, passFarItems / passFrames, passParticles / passFrames, passSingles / passFrames, passBatches / passFrames,
            )
        passNanos.fill(0); passFrames = 0; passItems = 0; passFarItems = 0; passParticles = 0; passSingles = 0; passBatches = 0
        line
    }

    @Volatile var qualityTier: QualityTier = QualityTier.MEDIUM
        private set

    private var vesselProgram: ShaderProgram? = null
    private var skyProgram: ShaderProgram? = null

    // How much sun reaches the camera this frame, and the fog as it looks in it.
    private var frameDaylight = 1f

    /** This frame's lamps, camera-relative x, y, z and reach. See [WorldView.lamps]. */
    private val frameLamps = FloatArray(4 * WorldView.MAX_LAMPS)
    private var frameLampCount = 0

    /** The body's centre, camera-relative, and the sea's surface radius, and how far light gets through it. See [WorldView.seaRadius]. */
    private val frameSea = FloatArray(4)
    private val frameWater = FloatArray(3) { 1f }
    private val scratchLamp = Vec3()
    private val frameFog = FloatArray(3)
    private var terrainProgram: ShaderProgram? = null

    /**
     * Terrain geometry, handed over by the game thread.
     *
     * It's built by sampling the simulation's own height field, which takes long enough that it
     * can't happen on the GL thread. The producer swaps a finished mesh in here and the renderer
     * uploads it on the next frame.
     */
    val terrainSource = TerrainSource()
    private var globeMesh: TerrainMesh? = null
    private var particleRenderer: ParticleRenderer? = null
    private var uploadedGlobe = 0

    /** The triangle list every chunk shares. */
    private var chunkIndices: SharedIndexBuffer? = null
    private var scatterRenderer: ScatterRenderer? = null

    /** Chunks on the GPU. The builder decides when each one goes. See TerrainSource.release. */
    private val chunkMeshes = HashMap<ChunkKey, TerrainMesh>()

    /**
     * One mesh per different shape, built the first time it's seen.
     *
     * It's keyed by the MeshSpec value itself, so a rocket with three identical fuel tanks uploads
     * one mesh and draws it three times. It's cleared whenever the GL context is recreated, because
     * every handle in it is dangling then.
     */
    private val meshes = HashMap<Pair<com.rm.apogee.core.part.Shape, Int>, Mesh>()
    private var lineProgram: ShaderProgram? = null
    /** Reused across frames. Orbits are uploaded again, not reallocated. */
    private val lineMeshes = ArrayList<LineMesh>()
    private var emptyVao = IntArray(1)
    private var lineScratch = FloatArray(0)

    // Allocated up front, because allocating per draw call would put the GC on the render path.
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

    /**
     * The planet's rotation for this displayed frame, interpolated exactly the way the camera and
     * craft are. Taken from the newest published frame alone, the ground was up to one publish
     * interval ahead of the craft on it on every frame drawn between two (most of three metres at
     * the equator), and back in line on the next. That was the ground jittering under a still
     * craft.
     */
    private val interpolatedBodyRotation = Quat()
    private val cameraRight = Vec3()
    private val cameraUp = Vec3()
    private val cameraForward = Vec3()
    private val upDirection = Vec3()
    private val scratchChunkCentre = Vec3()

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var lastDrawNanos = 0L

    // --- the sea ------------------------------------------------------------

    /**
     * Sets [shader]'s model matrix and look-ahead for the sea built at [sea]. Warped, a build is
     * drawn for longer in the world's time, so it's carried further.
     */
    private fun placeSea(sea: SeaSurface, cameraPos: Vec3, shader: ShaderProgram, time: Double, warp: Double) {
        interpolatedBodyRotation.rotate(sea.origin, seaOrigin)
        modelMatrix.setFromTrs(seaOrigin, interpolatedBodyRotation, cameraPos)
        shader.setMat4("uModel", modelMatrix.m)
        val most = SEA_LOOKAHEAD * warp.coerceAtLeast(1.0)
        shader.setFloat("uAhead", (time - sea.time).coerceIn(-most, most).toFloat())
    }

    /**
     * The sea, after everything solid. It's see-through over the shallows, so the bottom and
     * anything under it show, and it has both faces, for looking up at it from beneath.
     */
    private fun drawSea(world: WorldView, cameraPos: Vec3) {
        val mesh = seaMesh ?: return
        val sea = mesh.surface ?: return
        val shader = seaProgram ?: return
        shader.use()
        shader.setMat4("uViewProjection", nearViewProjection.m)
        placeSea(sea, cameraPos, shader, world.time, world.warp)
        shader.setVec3("uSunDirection", world.sunDirection.x.toFloat(), world.sunDirection.y.toFloat(), world.sunDirection.z.toFloat())
        shader.setFloat("uAtmosphereFactor", atmosphereFactorAt(world))
        shader.setFloat("uHazeDistance", (world.atmosphereScaleHeight * 8.0).toFloat())
        shader.setVec3("uBodyCentre", (-cameraPos.x).toFloat(), (-cameraPos.y).toFloat(), (-cameraPos.z).toFloat())
        shader.setFloat("uDaylight", frameDaylight)
        shader.setFloat("uLightScale", world.lightScale)
        shader.setFloat("uFlash", world.flash)
        setHaze(shader, world)
        shader.setFloat("uFogDistance", world.fogDistance.toFloat())
        world.sky.seaSky.let { shader.setVec3("uSeaSky", it[0], it[1], it[2]) }
        shader.setVec3("uFogColor", frameFog[0], frameFog[1], frameFog[2])
        shader.setFloat("uSeaReach", world.seaReach.toFloat())
        shader.setFloat("uUnderwater", if (world.underwater) 1f else 0f)
        applyShadowUniforms(shader, true)
        applyLamps(shader, true)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        mesh.draw()
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    // --- shadows ------------------------------------------------------------

    /** The player's choice, or null to go by the device. Set from the UI thread. */
    @Volatile var shadowChoice: ShadowQuality? = null

    private var shadowQuality = ShadowQuality.OFF
    private var nearMap: ShadowMap? = null
    private var farMap: ShadowMap? = null
    private val nearFrustum = ShadowFrustum()
    private val farFrustum = ShadowFrustum()
    private var vesselDepth: ShaderProgram? = null

    /** The shadow pass's solid items many to a call, as the main pass draws them. See [drawShadowBatches]. */
    private var vesselDepthInstanced: ShaderProgram? = null
    private val shadowBuffer = IntArray(1)
    private val shadowGroups = LinkedHashMap<com.rm.apogee.core.part.Shape, Array<IntList?>>()
    private val shadowCommands = IntList()
    private val shadowShapes = ArrayList<com.rm.apogee.core.part.Shape>()
    private val shadowCaps = IntList()
    private var seaProgram: ShaderProgram? = null
    private var seaMesh: SeaMesh? = null
    private val seaOrigin = Vec3()
    private var terrainDepth: ShaderProgram? = null
    private var nearOn = false
    private var farOn = false
    private var farValid = false
    private var farDrawnNanos = 0L
    private var farDay = true
    private val farCameraFixed = Vec3()
    private var shadowStrength = 0f
    private val toLight = Vec3()
    private val shadowMatcher = ItemMatcher()
    private val cloudTexture = IntArray(1)
    private var cloudRevision = -1
    private val cloudMatrix = FloatArray(16)
    private var cloudOn = 0f
    private val scratchShadow = Vec3()
    private val scratchShadow2 = Vec3()

    /** A GL context exists, new or made again, so everything's built for it. On the GL thread. */
    fun onSurfaceCreated() {
        qualityTier = detectTier()
        onTierDetected(qualityTier)

        GLES30.glClearColor(0.01f, 0.012f, 0.03f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        // The EGL context can be lost and recreated (surface teardown, some driver events), so
        // every GL object is rebuilt here instead of in the constructor. Anything cached across
        // this boundary is a dangling name.
        releaseGlObjects()

        vesselProgram = ShaderProgram(Shaders.VESSEL_VERTEX, Shaders.VESSEL_FRAGMENT, "vessel")
        cloudProgram = ShaderProgram(Shaders.CLOUD_INSTANCED_VERTEX, Shaders.CLOUD_INSTANCED_FRAGMENT, "cloud")
        GLES30.glGenBuffers(1, instanceBuffer, 0)
        GLES30.glGenBuffers(1, solidBuffer, 0)
        skyProgram = ShaderProgram(Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT, "sky")
        terrainProgram = ShaderProgram(Shaders.TERRAIN_VERTEX, Shaders.TERRAIN_FRAGMENT, "terrain")
        lineProgram = ShaderProgram(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT, "line")

        globeMesh = TerrainMesh()
        uploadedGlobe = 0
        chunkIndices = SharedIndexBuffer(TerrainChunk.indices)
        scatterRenderer = ScatterRenderer().also { it.shadows = { program -> applyShadowUniforms(program, true); applyLamps(program, true) } }
        particleRenderer = ParticleRenderer()
        vesselDepth = ShaderProgram(Shaders.VESSEL_VERTEX, Shaders.DEPTH_FRAGMENT, "vessel-depth")
        vesselDepthInstanced = ShaderProgram(Shaders.CLOUD_INSTANCED_VERTEX, Shaders.DEPTH_FRAGMENT, "vessel-depth-instanced")
        GLES30.glGenBuffers(1, shadowBuffer, 0)
        seaProgram = ShaderProgram(Shaders.SEA_VERTEX, Shaders.SEA_FRAGMENT, "sea")
        terrainDepth = ShaderProgram(Shaders.TERRAIN_VERTEX, Shaders.DEPTH_FRAGMENT, "terrain-depth")

        // The sky shader makes its own vertices, but GLES still needs a bound vertex array object
        // to draw.
        GLES30.glGenVertexArrays(1, emptyVao, 0)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = max(1, width)
        viewportHeight = max(1, height)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    fun onDrawFrame() {
        val now = System.nanoTime()
        if (lastDrawNanos != 0L) lastFrameTimeNanos.set(now - lastDrawNanos)
        lastDrawNanos = now
        framesDrawn.incrementAndGet()

        drawThumbnails()
        if (timePasses) { GLES30.glFinish(); passMark = System.nanoTime() }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val pair = frameBus.latest() ?: return
        val latest = pair.latest
        val previous = pair.previous

        // The interpolation factor between the two most recent published states. The server streams
        // at 20 Hz and the display might be at 60, 90 or 120, so drawing the newest state as it is
        // would judder, even though the simulation is perfectly smooth.
        val alpha = if (previous == null) {
            1.0
        } else {
            val span = (latest.timestampNanos - previous.timestampNanos).toDouble()
            if (span <= 0.0) 1.0
            else ((now - previous.timestampNanos).toDouble() / span).coerceIn(0.0, 1.0)
        }

        val cameraPos = interpolateCamera(previous, latest, alpha)
        frameDaylight = latest.world?.let { daylightAt(it, cameraPos) } ?: 1f
        nightDim(latest.world?.fogColor ?: CLEAR_FOG_COLOR, frameDaylight, frameFog)
        latest.world?.let { now ->
            val before = previous?.world
            // Only between frames of the same body. Across a change of sphere of influence the two
            // rotations have nothing to do with each other.
            if (before != null && before.radius == now.radius) {
                Quat.slerp(before.bodyRotation, now.bodyRotation, alpha, interpolatedBodyRotation)
            } else {
                interpolatedBodyRotation.setTo(now.bodyRotation)
            }
        }
        frameLampCount = latest.world?.let { placeLamps(it, cameraPos) } ?: 0
        frameSea[0] = (-cameraPos.x).toFloat(); frameSea[1] = (-cameraPos.y).toFloat(); frameSea[2] = (-cameraPos.z).toFloat()
        frameSea[3] = (latest.world?.seaRadius ?: 0.0).toFloat()
        latest.world?.water?.copyInto(frameWater)
        val aspect = viewportWidth.toDouble() / viewportHeight.toDouble()

        viewMatrix.setViewFromCameraRotation(interpolatedCameraRot)
        interpolatedCameraRot.rotate(Vec3.unitX(), cameraRight)
        interpolatedCameraRot.rotate(Vec3.unitY(), cameraUp)
        interpolatedCameraRot.rotate(Vec3(0.0, 0.0, -1.0), cameraForward)

        // The newest sea onto the GPU first, because it casts shadows too.
        latest.world?.sea?.let { next -> (seaMesh ?: SeaMesh().also { seaMesh = it }).take(next) }
        if (latest.world?.sea == null) seaMesh?.let { it.release(); seaMesh = null }

        mark(0)
        prepareShadows(latest, previous, alpha, cameraPos)
        mark(1)

        // --- far pass: sky and planet -------------------------------------
        val world = latest.world
        if (world != null && world.underwater) {
            // Under the water there's no sky, only the murk.
            GLES30.glClearColor(frameFog[0], frameFog[1], frameFog[2], 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glClearColor(0.01f, 0.012f, 0.03f, 1f)
        } else if (world != null) {
            // A million to one, near to far, however far that is: the whole system on the map.
            val farFar = maxOf(FAR_FAR_PLANE, latest.farReach)
            farProjection.setPerspective(latest.fovYRadians, aspect, maxOf(FAR_NEAR_PLANE, farFar * 1e-6), farFar)
            farViewProjection.setMultiplied(farProjection, viewMatrix)

            val atmosphereFactor = atmosphereFactorAt(world)
            drawSky(latest, world, cameraPos, aspect, atmosphereFactor)
            mark(2)
            uploadPendingTerrain()
            mark(3)
            drawGlobe(world, cameraPos, atmosphereFactor)
            mark(4)
            drawItems(latest.farItems, null, latest, 0.0, cameraPos, farViewProjection.m)
            drawFarGlobes(latest.farGlobes, world, cameraPos, atmosphereFactor)
            drawCloudShell(world, cameraPos)
            mark(5)
            // Paths go through the far projection, since an orbit is hundreds of kilometres across
            // and the near frustum would clip it away. They're drawn last, over everything: close in
            // on the map, the ground drawn in the near pass covered a rover's course.
            linesDue = true

            // Take back the whole depth range for the near pass.
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        }

        // --- near pass: craft ----------------------------------------------
        val nearPlane = (latest.nearestDistance * 0.5).coerceIn(NEAR_NEAR_PLANE, MAX_NEAR_PLANE)
        nearProjection.setPerspective(latest.fovYRadians, aspect, nearPlane, NEAR_FAR_PLANE)
        nearViewProjection.setMultiplied(nearProjection, viewMatrix)
        // The patch belongs here, not in the far pass. The far pass starts at a hundred metres, and
        // clipping the nearest hundred metres of ground leaves the craft standing at the edge of a
        // hole with sky underneath it, which is exactly what it looked like.
        mark(6)
        if (world != null) drawChunks(world, cameraPos, atmosphereFactorAt(world))
        mark(8)
        drawVessels(latest, previous, alpha, cameraPos)
        mark(9)
        if (world != null) drawSea(world, cameraPos)
        // Cloud after the sea. Drawn before it, the sea (blended, since its shallows are clear) got
        // laid over the clouds below a high craft, a disc of blue on the cloud deck.
        drawDeferredTranslucent(latest, alpha, cameraPos)
        mark(10)
        latest.particles?.let { particles ->
            val world = latest.world
            particleRenderer?.draw(
                particles, latest.particleShapes, nearViewProjection.m,
                (world?.fogDistance ?: WorldView.CLEAR_FOG).toFloat(), frameFog,
            )
        }
        mark(11)
        if (linesDue) {
            linesDue = false
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            drawLines(latest, cameraPos)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        if (timePasses) synchronized(passNanos) {
            passFrames++
            passItems += latest.items.size
            passFarItems += latest.farItems.size
            passParticles += latest.particleShapes
        }
    }

    /**
     * How much atmosphere is overhead: 1 at the datum, 0 in vacuum.
     *
     * It's on the same exponential the simulation uses for density, so the sky fades out exactly
     * where drag and engine performance say it should, instead of at some separately tuned
     * altitude.
     */
    /** The air's colour over distance, for every lit shader. This world's. */
    private fun setHaze(shader: ShaderProgram, world: WorldView?) {
        val haze = (world?.sky ?: SkyColours.TERRA).haze
        shader.setVec3("uHaze", haze[0], haze[1], haze[2])
    }

    private fun atmosphereFactorAt(world: WorldView): Float {
        if (world.atmosphereHeight <= 0.0) return 0f
        if (world.cameraAltitude >= world.atmosphereHeight) return 0f
        val density = kotlin.math.exp(
            -world.cameraAltitude.coerceAtLeast(0.0) / world.atmosphereScaleHeight
        )
        // Raised to a fractional power so the sky stays convincingly solid through the low
        // atmosphere. Plain density has already dropped to 0.87 at 800 m, which is enough for stars
        // to show through in daylight a few hundred metres off the pad. A thin air only makes a
        // little of a sky: black overhead, with a glow at the rim.
        return Math.pow(density, 0.30).toFloat() * world.sky.depth
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
        shader.setFloat("uLightScale", frame.world?.lightScale ?: 1f)
        shader.setFloat("uFlash", frame.world?.flash ?: 0f)
        setHaze(shader, frame.world)
        (frame.world?.sky ?: SkyColours.TERRA).let { sky ->
            shader.setVec3("uZenith", sky.zenith[0], sky.zenith[1], sky.zenith[2])
            shader.setVec3("uHorizon", sky.horizon[0], sky.horizon[1], sky.horizon[2])
            shader.setVec3("uSunset", sky.sunset[0], sky.sunset[1], sky.sunset[2])
            shader.setVec3("uRim", sky.rim[0], sky.rim[1], sky.rim[2])
        }
        val sunSize = frame.world?.sunSize ?: 0.0
        shader.setFloat("uSunCos", if (sunSize > 0.0) kotlin.math.cos(sunSize).toFloat() else 2f)
        shader.setFloat("uSunGlow", kotlin.math.sqrt((frame.world?.sunStrength ?: 1.0).coerceIn(0.0, 1.0)).toFloat())
        shader.setFloat("uSkyFog", frame.world?.skyFog ?: 0f)
        shader.setVec3("uFogColor", frameFog[0], frameFog[1], frameFog[2])
        shader.setFloat("uDaylight", frameDaylight)

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

    private var cloudShellProgram: ShaderProgram? = null
    private var cloudShellMesh: CloudShellMesh? = null

    /**
     * The planet's cloud over the globe, on the map: a veil, blended, with the far half hidden
     * behind the planet by the depth already drawn.
     */
    private fun drawCloudShell(world: WorldView, cameraPos: Vec3) {
        val shell = world.cloudShell ?: return
        val shader = cloudShellProgram ?: ShaderProgram(Shaders.CLOUD_SHELL_VERTEX, Shaders.CLOUD_SHELL_FRAGMENT, "cloud-shell").also { cloudShellProgram = it }
        val mesh = cloudShellMesh ?: CloudShellMesh().also { cloudShellMesh = it }
        if (mesh.revision != shell.revision) mesh.upload(shell)
        shader.use()
        modelMatrix.setFromTrs(Vec3.zero(), interpolatedBodyRotation, cameraPos, world.radius)
        shader.setMat4("uModel", modelMatrix.m)
        shader.setMat4("uViewProjection", farViewProjection.m)
        shader.setVec3("uSunDirection", world.sunDirection.x.toFloat(), world.sunDirection.y.toFloat(), world.sunDirection.z.toFloat())
        // Cells about forty kilometres across, drifting one across in a couple of hours.
        shader.setFloat("uNoiseScale", (world.radius / CLOUD_SHELL_CELL).toFloat())
        shader.setFloat("uDrift", ((world.time / CLOUD_SHELL_DRIFT) % 1_000.0).toFloat())
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        mesh.draw()
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Other worlds' globe meshes, by body id, uploaded once each. */
    private val farGlobeMeshes = HashMap<String, TerrainMesh>()
    private val farGlobeCentre = Vec3()

    /**
     * The other worlds big enough in the sky, each as itself: its own globe, lit by the sun, where
     * it really is. Its sea is the flat water the globe already carries, and our own air's haze
     * isn't laid over it, only the day sky's wash.
     */
    private fun drawFarGlobes(globes: List<FarGlobe>, world: WorldView, cameraPos: Vec3, atmosphereFactor: Float) {
        if (globes.isEmpty()) return
        val shader = terrainProgram ?: return
        shader.use()
        for (far in globes) {
            val mesh = farGlobeMeshes.getOrPut(far.id) { TerrainMesh().also { it.upload(far.globe.vertices, far.globe.indices) } }
            if (!mesh.isReady) continue
            modelMatrix.setFromTrs(far.position, far.rotation, cameraPos, far.radius)
            applySurfaceUniforms(shader, world, atmosphereFactor, cameraPos)
            farGlobeCentre.setTo(far.position).subInPlace(cameraPos)
            shader.setVec3("uBodyCentre", farGlobeCentre.x.toFloat(), farGlobeCentre.y.toFloat(), farGlobeCentre.z.toFloat())
            shader.setFloat("uSeaReach", 0f)
            shader.setFloat("uTide", 0f)
            shader.setFloat("uHasAtmosphere", if (far.hasAir) 1f else 0f)
            shader.setFloat("uDiscardNearer", 0f)
            shader.setFloat("uSkyBody", 1f)
            shader.setVec3("uFarRim", far.rim[0], far.rim[1], far.rim[2])
            applyShadowUniforms(shader, false)
            mesh.draw()
        }
        shader.setFloat("uSkyBody", 0f)
    }

    /** The whole body, for the view from any distance. */
    private fun drawGlobe(world: WorldView, cameraPos: Vec3, atmosphereFactor: Float) {
        val shader = terrainProgram ?: return
        val mesh = globeMesh ?: return
        if (!mesh.isReady || !world.drawFarSurface) return

        shader.use()
        // Globe vertices are in body radii, so the model matrix scales them, and rotates them,
        // because terrain turns with the planet.
        modelMatrix.setFromTrs(Vec3.zero(), interpolatedBodyRotation, cameraPos, world.radius)
        applySurfaceUniforms(shader, world, atmosphereFactor, cameraPos)
        applyShadowUniforms(shader, false)
        applyLamps(shader, false)
        // A little inside the chunks' reach, so there's no gap between them.
        shader.setFloat("uDiscardNearer", (world.chunkRange * 0.85).toFloat())
        mesh.draw()
    }

    /**
     * The chunks the game side chose, drawn in the near pass over the globe.
     *
     * Each chunk's vertices are metres from its own centre, and the camera subtraction happens here
     * in double against numbers in the hundreds of thousands. So what reaches float is a handful of
     * metres, and the ground doesn't shimmer at the equator.
     */
    /** Chunk meshes that aren't drawn any more, kept to be written over. A few at most. */
    private val spareChunkMeshes = ArrayList<TerrainMesh>()

    private fun retire(mesh: TerrainMesh) {
        if (spareChunkMeshes.size < MAX_SPARE_CHUNK_MESHES) spareChunkMeshes.add(mesh) else mesh.release()
    }

    private fun drawChunks(world: WorldView, cameraPos: Vec3, atmosphereFactor: Float) {
        val shader = terrainProgram ?: return
        val indices = chunkIndices ?: return
        val list = terrainSource.drawList()

        // Upload what's new, a few per frame. Each one is a buffer allocation and a copy, and a
        // burst of dozens on the first frame over new ground is a visible hitch. Anything not
        // uploaded yet is skipped this frame. The builder only lists built chunks, so it's a frame
        // late, not a hole that lasts.
        //
        // Releases go first. A chunk released and then rebuilt is queued in that order, and freeing
        // after uploading would free the new one.
        while (true) {
            val key = terrainSource.nextReleased() ?: break
            chunkMeshes.remove(key)?.let(::retire)
        }
        // Up to a count, and a time. In fast flight low over new ground there are always more
        // waiting, and the frame isn't theirs alone.
        var uploads = 0
        val uploadStarted = System.nanoTime()
        while (uploads < MAX_CHUNK_UPLOADS_PER_FRAME && (uploads == 0 || System.nanoTime() - uploadStarted < CHUNK_UPLOAD_BUDGET_NANOS)) {
            val chunk = terrainSource.nextToUpload() ?: break
            val vertices = chunk.vertices ?: continue
            chunkMeshes.remove(chunk.key)?.let(::retire)
            // A mesh retired from a chunk that's gone is the same size as any other, so its buffers
            // are written over, not made again.
            val mesh = if (spareChunkMeshes.isNotEmpty()) spareChunkMeshes.removeAt(spareChunkMeshes.size - 1) else TerrainMesh(indices)
            mesh.upload(vertices)
            chunk.vertices = null
            chunkMeshes[chunk.key] = mesh
            terrainSource.markUploaded(chunk.key)
            uploads++
        }

        shader.use()
        applySurfaceUniforms(shader, world, atmosphereFactor, cameraPos)
        applyShadowUniforms(shader, true)
        applyLamps(shader, true)
        shader.setMat4("uViewProjection", nearViewProjection.m)
        shader.setFloat("uDiscardNearer", 0f)
        for (entry in list) {
            val chunk = entry.chunk
            val mesh = chunkMeshes[chunk.key] ?: continue
            interpolatedBodyRotation.rotate(chunk.centre, scratchChunkCentre)
            // Behind the camera by more than the chunk's own size, so nothing of it can be on
            // screen. It's cheap, and usually half the chunks.
            scratchChunkCentre.subInPlace(cameraPos)
            if ((scratchChunkCentre dot cameraForward) < -chunk.boundingRadius) continue
            scratchChunkCentre.addInPlace(cameraPos)
            modelMatrix.setFromTrs(scratchChunkCentre, interpolatedBodyRotation, cameraPos)
            shader.setMat4("uModel", modelMatrix.m)
            mesh.drawQuadrants(entry.quadrants)
        }

        mark(7)
        scatterRenderer?.draw(
            terrainSource.scatter.drawList(),
            interpolatedBodyRotation,
            cameraPos,
            cameraForward,
            nearViewProjection.m,
            world.sunDirection,
            atmosphereFactor,
            (world.atmosphereScaleHeight * 8.0).toFloat(),
            world,
            frameDaylight,
            frameFog,
        )
    }

    private fun applySurfaceUniforms(
        shader: ShaderProgram,
        world: WorldView,
        atmosphereFactor: Float,
        cameraPos: Vec3,
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
        shader.setFloat("uLightScale", world.lightScale)
        shader.setFloat("uFlash", world.flash)
        setHaze(shader, world)
        shader.setFloat("uFogDistance", world.fogDistance.toFloat())
        shader.setVec3("uFogColor", frameFog[0], frameFog[1], frameFog[2])
        shader.setFloat("uDaylight", frameDaylight)
        // The body's centre, camera-relative. The scene is drawn around the camera, and the body
        // sits at the world origin.
        shader.setVec3("uBodyCentre", (-cameraPos.x).toFloat(), (-cameraPos.y).toFloat(), (-cameraPos.z).toFloat())
        shader.setFloat("uSeaReach", if (world.sea != null) world.seaReach.toFloat() else 0f)
        shader.setFloat("uTide", world.tide.toFloat())
    }

    /**
     * Draws path polylines.
     *
     * Depth writes are off so a conic that passes behind the planet still looks like one continuous
     * path, instead of being sliced into arcs by its own far side, which is the whole point of a
     * map view.
     */
    /** Whether this frame's far pass set up the lines to be drawn at its end. */
    private var linesDue = false

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
    ) = drawItems(latest.items, previous?.items, latest, alpha, cameraPos, nearViewProjection.m, deferTranslucent = true)

    /** The see-through items [drawVessels] left for after the sea. */
    private fun drawDeferredTranslucent(latest: RenderFrame, alpha: Double, cameraPos: Vec3) {
        if (!translucentDeferred) return
        translucentDeferred = false
        if (translucent.isEmpty()) return
        val shader = vesselProgram ?: return
        setItemUniforms(shader, latest, nearViewProjection.m)
        cloudProgram?.let { setItemUniforms(it, latest, nearViewProjection.m); it.setFloat("uWrap", 1f); it.setFloat("uReceivesShadow", 0f) }
        shader.use()
        drawTranslucentPass(latest.items, alpha, cameraPos, shader)
    }

    private var translucentDeferred = false

    private fun drawTranslucentPass(items: List<RenderItem>, alpha: Double, cameraPos: Vec3, shader: ShaderProgram) {
        sortFarToNear(items, cameraPos)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        drawTranslucent(items, alpha, cameraPos, shader)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawItems(
        items: List<RenderItem>,
        previousItems: List<RenderItem>?,
        latest: RenderFrame,
        alpha: Double,
        cameraPos: Vec3,
        viewProjection: FloatArray,
        /** Leave the see-through ones for [drawDeferredTranslucent], after the sea. */
        deferTranslucent: Boolean = false,
    ) {
        if (items.isEmpty()) return
        val shader = vesselProgram ?: return
        setItemUniforms(shader, latest, viewProjection)
        cloudProgram?.let { setItemUniforms(it, latest, viewProjection); it.setFloat("uWrap", 1f); it.setFloat("uReceivesShadow", 0f) }
        shader.use()

        // Solid things first, then the see-through ones (cloud) far to near, with blending on and
        // depth writes off, so each layer shows through the ones in front of it and nothing solid
        // behind gets lost.
        matchPrevious(items, previousItems)
        translucent.clear()
        // Solid parts by shape, to be drawn many per call. The Cape's buildings alone are some
        // eight hundred pieces, and one call each was the frame's biggest pass.
        val batching = cloudProgram != null && solidBuffer[0] != 0
        for ((index, item) in items.withIndex()) {
            if (item.color[3] < 0.999f) { translucent.add(index); continue }
            if (batching && !item.wrap && item.decal == 0 && !item.sky) solidGroup(item.shape, item.caps).add(index)
            else drawItem(item, partners[index], alpha, cameraPos, shader)
        }
        if (batching) drawSolidBatches(items, alpha, cameraPos, shader)
        if (deferTranslucent) { translucentDeferred = true; return }
        if (translucent.isNotEmpty()) drawTranslucentPass(items, alpha, cameraPos, shader)
    }

    private val translucent = ArrayList<Int>()

    /** A growable list of ints, cleared instead of made again each frame. */
    private class IntList {
        var values = IntArray(8)
        var size = 0
        fun add(v: Int) { if (size == values.size) values = values.copyOf(size * 2); values[size++] = v }
    }

    /** This frame's solid items, by shape and then by caps. See [drawSolidBatches]. */
    private val solidGroups = LinkedHashMap<com.rm.apogee.core.part.Shape, Array<IntList?>>()
    private val solidBuffer = IntArray(1)
    private val solidCaps = ArrayList<Int>()

    private fun solidGroup(shape: com.rm.apogee.core.part.Shape, caps: Int): IntList {
        val byCaps = solidGroups.getOrPut(shape) { arrayOfNulls(4) }
        return byCaps[caps] ?: IntList().also { byCaps[caps] = it }
    }

    /**
     * The solid items [solidGroups] holds. A shape with one of it is drawn as it always was, and
     * every shape with more is drawn all at once, instanced, with each piece's place, colour and
     * glow in its instance.
     */
    private fun drawSolidBatches(items: List<RenderItem>, alpha: Double, cameraPos: Vec3, shader: ShaderProgram) {
        val instanced = cloudProgram ?: return
        commands.clear()
        commandShapes.clear()
        solidCaps.clear()
        var floats = 0
        for ((shape, byCaps) in solidGroups) {
            for (caps in byCaps.indices) {
                val group = byCaps[caps] ?: continue
                if (group.size == 0) continue
                if (group.size == 1) {
                    val index = group.values[0]
                    drawItem(items[index], partners[index], alpha, cameraPos, shader)
                    passSingles++
                } else {
                    commands.add(floats / Mesh.INSTANCE_FLOATS); commands.add(group.size)
                    commandShapes.add(shape); solidCaps.add(caps)
                    for (k in 0 until group.size) floats = writeInstance(items, group.values[k], alpha, cameraPos, floats)
                }
                group.size = 0
            }
        }
        if (commands.isEmpty()) return
        uploadInstances(solidBuffer[0], floats)
        instanced.use()
        instanced.setFloat("uWrap", 0f)
        instanced.setFloat("uReceivesShadow", 1f)
        for (k in commandShapes.indices) {
            meshFor(commandShapes[k], solidCaps[k]).drawInstanced(solidBuffer[0], commands[2 * k], commands[2 * k + 1])
            passBatches++
        }
        // Back the way the clouds want it.
        instanced.setFloat("uWrap", 1f)
        instanced.setFloat("uReceivesShadow", 0f)
        commandShapes.clear()
        commands.clear()
        shader.use()
    }

    /**
     * The shadow pass's solid items, grouped in [shadowGroups]: one alone drawn by [shader] as it
     * always was, and more of a shape drawn all at once.
     */
    private fun drawShadowBatches(items: List<RenderItem>, alpha: Double, cameraPos: Vec3, shader: ShaderProgram) {
        val instanced = vesselDepthInstanced ?: return
        shadowCommands.size = 0
        shadowCaps.size = 0
        shadowShapes.clear()
        var floats = 0
        for ((shape, byCaps) in shadowGroups) {
            for (caps in byCaps.indices) {
                val group = byCaps[caps] ?: continue
                if (group.size == 0) continue
                if (group.size == 1) {
                    val index = group.values[0]
                    placeItem(items[index], shadowMatcher.partners[index], alpha, cameraPos, shader)
                    meshFor(shape, caps).draw()
                } else {
                    shadowCommands.add(floats / Mesh.INSTANCE_FLOATS); shadowCommands.add(group.size)
                    shadowShapes.add(shape); shadowCaps.add(caps)
                    for (k in 0 until group.size) {
                        val index = group.values[k]
                        floats = writeInstanceOf(items[index], shadowMatcher.partners[index], alpha, cameraPos, floats)
                    }
                }
                group.size = 0
            }
        }
        if (shadowShapes.isEmpty()) return
        uploadInstances(shadowBuffer[0], floats)
        instanced.use()
        instanced.setMat4("uViewProjection", nearFrustum.viewProjection)
        for (k in shadowShapes.indices) {
            meshFor(shadowShapes[k], shadowCaps.values[k]).drawInstanced(shadowBuffer[0], shadowCommands.values[2 * k], shadowCommands.values[2 * k + 1])
        }
        shader.use()
    }

    /**
     * Item [index]'s instance (model matrix, 1/scale^2, glow, colour) into [instanceData] at
     * [floats]. Returns the floats after it.
     */
    private fun writeInstance(items: List<RenderItem>, index: Int, alpha: Double, cameraPos: Vec3, floats: Int): Int =
        writeInstanceOf(items[index], partners[index], alpha, cameraPos, floats)

    /** [item]'s instance, eased from [previous], into [instanceData] at [floats]. Returns the floats after it. */
    private fun writeInstanceOf(item: RenderItem, previous: RenderItem?, alpha: Double, cameraPos: Vec3, floats: Int): Int {
        modelOf(item, previous, alpha, cameraPos)
        val at = ensureInstanceRoom(floats)
        modelMatrix.m.copyInto(instanceData, at, 0, 0 + 16)
        instanceData[at + 16] = invScale[0]; instanceData[at + 17] = invScale[1]; instanceData[at + 18] = invScale[2]
        instanceData[at + 19] = item.ambient
        item.color.copyInto(instanceData, at + 20, 0, 0 + 4)
        return at + Mesh.INSTANCE_FLOATS
    }

    /** The first [floats] of [instanceData] up into [buffer]. */
    private fun uploadInstances(buffer: Int, floats: Int) {
        if (instanceBytes == null || instanceBytes!!.capacity < floats * 4) {
            instanceBytes = GlData(instanceData.size * 4)
        }
        val bytes = instanceBytes!!
        bytes.putFloats(instanceData, floats)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floats * 4, bytes, GLES30.GL_STREAM_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** What every item shader needs for the frame: projection, light, fog, haze and shadows. */
    private fun setItemUniforms(shader: ShaderProgram, latest: RenderFrame, viewProjection: FloatArray) {
        shader.use()
        shader.setMat4("uViewProjection", viewProjection)

        // Light from the star when there is one, otherwise a fixed key light so the assembly
        // building isn't lit from nowhere.
        val sun = latest.world?.sunDirection
        if (sun != null) {
            shader.setVec3("uLightDirection", (-sun.x).toFloat(), (-sun.y).toFloat(), (-sun.z).toFloat())
        } else {
            shader.setVec3("uLightDirection", -0.42f, -0.57f, -0.71f)
        }
        val world = latest.world
        shader.setFloat("uLightScale", world?.lightScale ?: 1f)
        shader.setFloat("uFlash", world?.flash ?: 0f)
        setHaze(shader, world)
        shader.setFloat("uDaylight", frameDaylight)
        shader.setFloat("uFogDistance", (world?.fogDistance ?: WorldView.CLEAR_FOG).toFloat())
        shader.setVec3("uFogColor", frameFog[0], frameFog[1], frameFog[2])
        shader.setFloat("uHazeDistance", ((world?.atmosphereScaleHeight ?: 1.0e6) * 8.0).toFloat())
        shader.setFloat("uAtmosphereFactor", if (world != null) atmosphereFactorAt(world) else 0f)
        applyShadowUniforms(shader, true)
        applyLamps(shader, world != null)
    }

    /** Each item's self in the previous frame. See [ItemMatcher]. */
    private val matcher = ItemMatcher()
    private val partners: List<RenderItem?> get() = matcher.partners

    private fun matchPrevious(items: List<RenderItem>, previousItems: List<RenderItem>?) =
        matcher.match(items, previousItems)

    /**
     * The see-through items, far to near. Cloud lobes are drawn many per call, one band of distance
     * at a time, with a call for each shape in it, and anything else one by one in its place.
     * Within a band the lobes aren't in strict order any more, which doesn't show between soft
     * lobes at about the same distance. A sky of two thousand draw calls did show.
     */
    private fun drawTranslucent(items: List<RenderItem>, alpha: Double, cameraPos: Vec3, shader: ShaderProgram) {
        val clouds = cloudProgram
        if (clouds == null || instanceBuffer[0] == 0) {
            for (index in translucent) drawItem(items[index], partners[index], alpha, cameraPos, shader)
            return
        }
        // Lay out every lobe's instance first, in drawing order, so the buffer goes up once, then
        // draw.
        commands.clear()
        var floats = 0
        var i = 0
        val n = translucent.size
        while (i < n) {
            val item = items[translucent[i]]
            if (!instanced(item)) { commands.add(-1 - translucent[i]); i++; continue }
            val floor = distances[i] / BAND_RATIO
            bands.clear()
            while (i < n) {
                val next = items[translucent[i]]
                if (!instanced(next) || distances[i] < floor) break
                bands.getOrPut(next.shape) { ArrayList() }.add(translucent[i])
                i++
            }
            for ((shape, members) in bands) {
                val first = floats / Mesh.INSTANCE_FLOATS
                for (index in members) floats = writeInstance(items, index, alpha, cameraPos, floats)
                commands.add(first); commands.add(members.size); commandShapes.add(shape)
            }
        }
        if (floats > 0) uploadInstances(instanceBuffer[0], floats)
        var current: ShaderProgram? = null
        var c = 0
        var shapeIndex = 0
        while (c < commands.size) {
            val command = commands[c]
            if (command < 0) {
                if (current !== shader) { shader.use(); current = shader }
                val index = -1 - command
                drawItem(items[index], partners[index], alpha, cameraPos, shader)
                passSingles++
                c++
            } else {
                passBatches++
                if (current !== clouds) { clouds.use(); current = clouds }
                meshFor(commandShapes[shapeIndex++], StackCaps.BOTH).drawInstanced(instanceBuffer[0], command, commands[c + 1])
                c += 2
            }
        }
        commandShapes.clear()
        shader.use()
    }

    /**
     * [translucent] far to near by distance from [cameraPos]. Each distance is worked out once,
     * into [distances] in the same order, and sorted as plain numbers. Sorting boxed distances,
     * each measured again at every comparison, took twelve milliseconds a frame over a HIGH sky.
     */
    private fun sortFarToNear(items: List<RenderItem>, cameraPos: Vec3) {
        val n = translucent.size
        if (sortKeys.size < n) { sortKeys = LongArray(n * 2); distances = DoubleArray(n * 2) }
        for (k in 0 until n) {
            val d = items[translucent[k]].position.distanceTo(cameraPos).toFloat()
            // Far first: the distance's bits, inverted, ahead of the index.
            sortKeys[k] = ((d.toRawBits().toLong() xor 0x7FFFFFFFL) shl 32) or translucent[k].toLong()
        }
        sortKeys.sort(0, n)
        for (k in 0 until n) {
            val index = (sortKeys[k] and 0xFFFFFFFFL).toInt()
            translucent[k] = index
            distances[k] = items[index].position.distanceTo(cameraPos)
        }
    }

    private var sortKeys = LongArray(1024)
    private var distances = DoubleArray(1024)

    /** Whether [item] can be drawn among many in one call, like a cloud lobe. */
    private fun instanced(item: RenderItem): Boolean =
        item.shape is CloudPuff && item.caps == StackCaps.BOTH && item.scale != null

    private fun ensureInstanceRoom(floats: Int): Int {
        if (floats + Mesh.INSTANCE_FLOATS > instanceData.size) instanceData = instanceData.copyOf(instanceData.size * 2)
        return floats
    }

    private var cloudProgram: ShaderProgram? = null
    private val instanceBuffer = IntArray(1)
    private var instanceData = FloatArray(Mesh.INSTANCE_FLOATS * 512)
    private var instanceBytes: GlData? = null
    private val commands = ArrayList<Int>()
    private val commandShapes = ArrayList<com.rm.apogee.core.part.Shape>()
    private val bands = LinkedHashMap<com.rm.apogee.core.part.Shape, ArrayList<Int>>()
    private val invScale = FloatArray(3)

    private fun drawItem(item: RenderItem, prevItem: RenderItem?, alpha: Double, cameraPos: Vec3, shader: ShaderProgram) {
        placeItem(item, prevItem, alpha, cameraPos, shader)
        shader.setVec4("uColor", item.color)
        shader.setFloat("uAmbient", item.ambient)
        // Clouds wrap their light and thin at the edges, and a flame (ambient of one or more) glows
        // whole.
        shader.setFloat("uWrap", if (item.wrap) 1f else 0f)
        // A part gets shaded by what's between it and the light, and a cloud or a flame doesn't.
        shader.setFloat("uReceivesShadow", if (item.wrap || item.ambient >= 1f || item.sky) 0f else 1f)
        shader.setFloat("uSkyBody", if (item.sky) 1f else 0f)
        if (item.decal > 0) {
            // Paving: over the ground it lies on, with later layers over earlier ones.
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-1f, -4f * item.decal)
            meshFor(item.shape, item.caps).draw()
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            return
        }
        meshFor(item.shape, item.caps).draw()
    }

    /** Sets where [item] is drawn this frame (eased from [prevItem]) as [shader]'s model matrix. */
    private fun placeItem(item: RenderItem, prevItem: RenderItem?, alpha: Double, cameraPos: Vec3, shader: ShaderProgram) {
        modelOf(item, prevItem, alpha, cameraPos)
        shader.setVec3("uInvScaleSq", invScale[0], invScale[1], invScale[2])
        shader.setMat4("uModel", modelMatrix.m)
    }

    /** Where [item] is drawn this frame (eased from [prevItem]) into [modelMatrix], and its 1/scale^2 into [invScale]. */
    private fun modelOf(item: RenderItem, prevItem: RenderItem?, alpha: Double, cameraPos: Vec3) {
        run {
            val position: Vec3
            val rotation: Quat
            if (prevItem != null && prevItem.shape == item.shape) {
                interpolatedPosition.setTo(
                    lerp(prevItem.position.x, item.position.x, alpha),
                    lerp(prevItem.position.y, item.position.y, alpha),
                    lerp(prevItem.position.z, item.position.z, alpha),
                )
                if (item.shape is CloudPuff) {
                    // A cloud only turns with the planet between two frames, so a normalised
                    // straight blend is as good, and far cheaper over two thousand of them.
                    val a = prevItem.rotation; val b = item.rotation
                    val sign = if (a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w < 0.0) -1.0 else 1.0
                    val t = alpha; val u = 1.0 - alpha
                    interpolatedRotation.x = u * a.x + t * sign * b.x
                    interpolatedRotation.y = u * a.y + t * sign * b.y
                    interpolatedRotation.z = u * a.z + t * sign * b.z
                    interpolatedRotation.w = u * a.w + t * sign * b.w
                    interpolatedRotation.normalizeInPlace()
                } else {
                    Quat.slerp(prevItem.rotation, item.rotation, alpha, interpolatedRotation)
                }
                position = interpolatedPosition
                rotation = interpolatedRotation
            } else {
                position = item.position
                rotation = item.rotation
            }

            val scale = item.scale
            if (scale == null) {
                modelMatrix.setFromTrs(position, rotation, cameraPos)
                invScale[0] = 1f; invScale[1] = 1f; invScale[2] = 1f
            } else {
                modelMatrix.setFromTrs(position, rotation, cameraPos, scale.x, scale.y, scale.z)
                // The shape only, relative to the largest axis. The shader normalises the result,
                // so the scale itself isn't needed, and a cloud a kilometre across has a 1/scale^2
                // near 1e-7, which a phone GPU's reduced precision rounds to nothing. A normal of
                // zero lit the facets in random colours and black.
                val largest = maxOf(scale.x, scale.y, scale.z)
                invScale[0] = ((largest / scale.x) * (largest / scale.x)).toFloat()
                invScale[1] = ((largest / scale.y) * (largest / scale.y)).toFloat()
                invScale[2] = ((largest / scale.z) * (largest / scale.z)).toFloat()
            }
        }
    }

    // --- shadows ------------------------------------------------------------

    /**
     * Draws this frame's shadow maps and binds them, with the clouds' shadows: the near map around
     * the craft every frame, and the mountains' when it's due. The light comes from the sun by day
     * and the moon by night, fading across twilight instead of jumping from one to the other.
     */
    private fun prepareShadows(latest: RenderFrame, previous: RenderFrame?, alpha: Double, cameraPos: Vec3) {
        shadowQuality = shadowChoice ?: ShadowQuality.defaultFor(qualityTier)
        nearOn = false; farOn = false; cloudOn = 0f
        // Never read a map while drawing into it.
        for (unit in 1..3) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val world = latest.world ?: return
        val focus = latest.shadowFocus ?: return
        if (!shadowQuality.on) return

        val day = frameDaylight >= 0.3f
        toLight.setTo(world.sunDirection)
        if (!day) toLight.mulInPlace(-1.0)
        shadowStrength = if (day) smooth01((frameDaylight - 0.3f) / 0.2f) else smooth01((0.3f - frameDaylight) / 0.2f)

        if (shadowStrength > 0.01f) {
            drawNearMap(latest, previous, alpha, cameraPos, world, focus)
            if (shadowQuality.mountains) drawFarMap(world, cameraPos, day)
        }
        world.cloudShadow?.let { grid ->
            if (grid.revision != cloudRevision) uploadCloudShadow(grid)
            grid.matrix(interpolatedBodyRotation, cameraPos, cloudMatrix)
            // Gone by the time the grid would look like a patch on the globe below. It reaches
            // tens of kilometres, and from orbit the clouds over the rest of the planet cast
            // nothing, so an overcast inside it was a dark square.
            val height = interpolatedBodyRotation.inverseRotate(cameraPos, scratchShadow).length - world.radius
            cloudOn = grid.strength * (1f - smooth01(((height - CLOUD_SHADOW_HIGH) / CLOUD_SHADOW_HIGH).toFloat()))
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (nearOn) nearMap?.texture ?: 0 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (farOn) farMap?.texture ?: 0 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (cloudOn > 0f) cloudTexture[0] else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /** The craft, and the trees and rocks around it, from the light's side. */
    private fun drawNearMap(latest: RenderFrame, previous: RenderFrame?, alpha: Double, cameraPos: Vec3, world: WorldView, focus: Vec3) {
        val size = shadowQuality.nearSize
        val map = nearMap?.takeIf { it.size == size } ?: run { nearMap?.release(); ShadowMap(size).also { nearMap = it } }
        val reach = latest.shadowRadius.coerceAtLeast(20.0)
        nearFrustum.update(toLight, focus, cameraPos, reach, reach + 400.0, size)
        map.begin()
        val shader = vesselDepth ?: return
        shader.use()
        shader.setMat4("uViewProjection", nearFrustum.viewProjection)
        shadowMatcher.match(latest.items, previous?.items)
        // By shape, many to a call where there's more than one, as the main pass draws them. One
        // call each, the launch complex alone was eight hundred calls a frame here.
        val batching = vesselDepthInstanced != null && shadowBuffer[0] != 0
        for ((index, item) in latest.items.withIndex()) {
            // Solid, lit things cast shadows, not cloud, flame, vapour or rain.
            if (item.color[3] < 0.999f || item.ambient >= 1f || item.wrap || item.shape is CloudPuff || item.decal > 0) continue
            if (item.position.distanceTo(focus) > reach * 1.5 + 30.0) continue
            if (batching) {
                val byCaps = shadowGroups.getOrPut(item.shape) { arrayOfNulls(4) }
                (byCaps[item.caps] ?: IntList().also { byCaps[item.caps] = it }).add(index)
            } else {
                placeItem(item, shadowMatcher.partners[index], alpha, cameraPos, shader)
                meshFor(item.shape, item.caps).draw()
            }
        }
        if (batching) drawShadowBatches(latest.items, alpha, cameraPos, shader)
        scatterRenderer?.drawDepth(
            terrainSource.scatter.drawList(), interpolatedBodyRotation, cameraPos,
            nearFrustum.viewProjection, world, focus, reach,
        )
        // Not the sea. It used to cast too, so a crest would shade the trough behind it, but then
        // the seabed under it was in the water's shadow wherever this map reached, and through
        // clear shallows that was a dark square round the craft that went where it went. The
        // waves are shaded by their slope anyway.
        map.end(viewportWidth, viewportHeight)
        nearOn = true
    }

    /**
     * The ground for kilometres around, from the light's side, for the hills' shadows. It's redrawn
     * every second or two, or when the camera has gone a fair way, since ground and sun barely move
     * in between. It's made in the planet's own turning frame, so it stays on its mountains as they
     * turn.
     */
    private fun drawFarMap(world: WorldView, cameraPos: Vec3, day: Boolean) {
        val size = shadowQuality.farSize
        val reach = shadowQuality.farReach
        val cameraFixed = interpolatedBodyRotation.inverseRotate(cameraPos, scratchShadow)
        // Not from high up. From orbit the ground on screen is the whole globe, not the terrain
        // this map is drawn from, and where the two shapes disagree the ground shaded itself: a
        // dark square tens of kilometres across followed the craft round the planet. A mountain's
        // shadow couldn't be seen from up there anyway.
        if (cameraFixed.length - world.radius > reach) return
        val due = !farValid || farMap?.size != size || farDay != day ||
            (System.nanoTime() - farDrawnNanos) / 1e9 > shadowQuality.farEvery ||
            cameraFixed.distanceTo(farCameraFixed) > reach * 0.2
        if (!due) {
            farFrustum.place(cameraFixed, interpolatedBodyRotation)
            farOn = true
            return
        }
        val map = farMap?.takeIf { it.size == size } ?: run { farMap?.release(); ShadowMap(size).also { farMap = it } }
        val lightFixed = interpolatedBodyRotation.inverseRotate(toLight, scratchShadow2)
        val ground = Vec3().setTo(cameraFixed).normalizeInPlace().mulInPlace(world.radius)
        farFrustum.aim(lightFixed, ground, reach, reach + 15_000.0, size)
        farFrustum.place(cameraFixed, interpolatedBodyRotation)
        val shader = terrainDepth ?: return
        // Barely pushed back. Ground in a low sun is steep as the light sees it (a texel's slope is
        // a hundred metres of depth at dawn), and the usual push hid every shadow within a few
        // hundred metres of the ridge that cast it. The receivers' offset along their normal does
        // the rest.
        map.begin(slope = 0.5f, units = 2f)
        shader.use()
        shader.setMat4("uViewProjection", farFrustum.viewProjection)
        // The sea as flat water to the light, because waves cast nothing it could see.
        shader.setFloat("uSeaReach", 0f)
        shader.setFloat("uTide", world.tide.toFloat())
        shader.setVec3("uBodyCentre", (-cameraPos.x).toFloat(), (-cameraPos.y).toFloat(), (-cameraPos.z).toFloat())
        val groundWorld = interpolatedBodyRotation.rotate(ground, Vec3())
        for (entry in terrainSource.drawList()) {
            val chunk = entry.chunk
            val mesh = chunkMeshes[chunk.key] ?: continue
            interpolatedBodyRotation.rotate(chunk.centre, scratchChunkCentre)
            if (scratchChunkCentre.distanceTo(groundWorld) > reach * 1.5 + chunk.boundingRadius) continue
            modelMatrix.setFromTrs(scratchChunkCentre, interpolatedBodyRotation, cameraPos)
            shader.setMat4("uModel", modelMatrix.m)
            mesh.drawQuadrants(entry.quadrants)
        }
        map.end(viewportWidth, viewportHeight)
        farValid = true
        farOn = true
        farDay = day
        farDrawnNanos = System.nanoTime()
        farCameraFixed.setTo(cameraFixed)
    }

    private fun uploadCloudShadow(grid: CloudShadowGrid) {
        if (cloudTexture[0] == 0) {
            GLES30.glGenTextures(1, cloudTexture, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cloudTexture[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cloudTexture[0])
        val buffer = GlData(grid.data.size)
        buffer.putBytes(grid.data)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, grid.size, grid.size, 0,
            GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, buffer,
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        cloudRevision = grid.revision
    }

    /**
     * Tells a lit program where the shadows are this frame. Its samplers always point at units 1, 2
     * and 3, even with shadows off, since two sampler types left on one unit is an error when it
     * draws.
     */
    /**
     * [world]'s lamps into [frameLamps], camera-relative, on the ground as it's turned this frame,
     * so a pool stays put on the concrete it lights.
     */
    private fun placeLamps(world: WorldView, cameraPos: Vec3): Int {
        val count = minOf(world.lamps.size / 4, WorldView.MAX_LAMPS)
        for (k in 0 until count) {
            scratchLamp.setTo(world.lamps[4 * k], world.lamps[4 * k + 1], world.lamps[4 * k + 2])
            interpolatedBodyRotation.rotate(scratchLamp, scratchLamp).subInPlace(cameraPos)
            frameLamps[4 * k] = scratchLamp.x.toFloat()
            frameLamps[4 * k + 1] = scratchLamp.y.toFloat()
            frameLamps[4 * k + 2] = scratchLamp.z.toFloat()
            frameLamps[4 * k + 3] = world.lamps[4 * k + 3].toFloat()
        }
        return count
    }

    /** The lamps' light, for a program lit by them. None where there's no world. */
    private fun applyLamps(shader: ShaderProgram, lit: Boolean) {
        val count = if (lit) frameLampCount else 0
        shader.setInt("uLampCount", count)
        if (count > 0) shader.setVec4Array("uLamps", frameLamps, count)
        // The sea's dark goes with the lamps, so wherever they light, it does too.
        shader.setVec4("uSea", if (lit) frameSea else NO_SEA)
        shader.setVec3("uWater", frameWater[0], frameWater[1], frameWater[2])
    }

    private fun applyShadowUniforms(shader: ShaderProgram, receives: Boolean) {
        shader.setInt("uNearShadow", 1)
        shader.setInt("uFarShadow", 2)
        shader.setInt("uCloudShadow", 3)
        shader.setFloat("uNearOn", if (receives && nearOn) 1f else 0f)
        shader.setFloat("uFarOn", if (receives && farOn) 1f else 0f)
        shader.setFloat("uCloudOn", if (receives) cloudOn else 0f)
        shader.setMat4("uNearShadowMatrix", nearFrustum.texture)
        shader.setMat4("uFarShadowMatrix", farFrustum.texture)
        shader.setMat4("uCloudMatrix", cloudMatrix)
        shader.setFloat("uNearTexel", 1f / maxOf(1, shadowQuality.nearSize))
        shader.setFloat("uFarTexel", 1f / maxOf(1, shadowQuality.farSize))
        shader.setFloat("uNearOffset", (nearFrustum.texelSize * 1.5).toFloat())
        shader.setFloat("uFarOffset", (farFrustum.texelSize * 1.0).toFloat())
        shader.setFloat("uKernel", shadowQuality.kernel.toFloat())
        shader.setFloat("uShadowStrength", shadowStrength)
    }

    private fun smooth01(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Builds and caches the GPU mesh for a shape the first time it's drawn.
     *
     * It's keyed on the cap mask as well as the shape, because a tank buried in a stack and the
     * same tank standing alone are different meshes. There are at most four variants per shape, and
     * in practice two.
     */
    private fun meshFor(spec: com.rm.apogee.core.part.Shape, caps: Int): Mesh = meshes.getOrPut(spec to caps) {
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
            is com.rm.apogee.core.part.ModelSpec -> ModelShapes.build(spec, caps).let { Mesh(it.vertices, it.indices) }
            is CloudPuff -> CloudShapes.puff(spec).let { Mesh(it.vertices, it.indices) }
            is PavingShape -> Mesh(spec.vertices, spec.indices)
            else -> throw IllegalArgumentException("Cannot draw $spec")
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

    // --- part pictures ---------------------------------------------------------

    /** Where the drawer's part pictures are asked for and handed back. */
    @Volatile var thumbnails: PartThumbnails? = null

    private val thumbFbo = IntArray(1)
    private val thumbColour = IntArray(1)
    private val thumbDepth = IntArray(1)
    private var thumbTarget = false
    private val thumbProjection = Mat4()
    private val thumbViewProjection = Mat4()
    private val thumbPixels = GlData(THUMB_RENDER * THUMB_RENDER * 4)
    private val thumbBytes = ByteArray(THUMB_RENDER * THUMB_RENDER * 4)

    /**
     * A few waiting part pictures, drawn off screen before the frame itself, at twice the size and
     * scaled down for smooth edges without multisampling, lit by the builder's key light with no
     * shadows.
     */
    private fun drawThumbnails() {
        val source = thumbnails ?: return
        var job = source.next() ?: return
        var drawn = 0
        if (!thumbTarget) {
            GLES30.glGenFramebuffers(1, thumbFbo, 0)
            GLES30.glGenRenderbuffers(1, thumbColour, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, thumbColour[0])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, THUMB_RENDER, THUMB_RENDER)
            GLES30.glGenRenderbuffers(1, thumbDepth, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, thumbDepth[0])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, THUMB_RENDER, THUMB_RENDER)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, thumbFbo[0])
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, thumbColour[0])
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, thumbDepth[0])
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
            thumbTarget = true
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, thumbFbo[0])
        GLES30.glViewport(0, 0, THUMB_RENDER, THUMB_RENDER)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        val near = nearOn; val far = farOn; val cloud = cloudOn
        nearOn = false; farOn = false; cloudOn = 0f
        frameDaylight = 1f
        nightDim(CLEAR_FOG_COLOR, 1f, frameFog)
        while (true) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            viewMatrix.setViewFromCameraRotation(job.cameraRotation)
            thumbProjection.setPerspective(job.fovY, 1.0, 0.02, 200.0)
            thumbViewProjection.setMultiplied(thumbProjection, viewMatrix)
            val frame = RenderFrame(0, 0, job.cameraPosition, job.cameraRotation, job.fovY, job.items)
            drawItems(job.items, null, frame, 1.0, job.cameraPosition, thumbViewProjection.m)
            GLES30.glReadPixels(0, 0, THUMB_RENDER, THUMB_RENDER, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, thumbPixels)
            thumbPixels.getBytes(thumbBytes)
            // Read bottom row first, so turned the right way up, and halved for smooth edges.
            source.done(job.key, halved(thumbBytes, THUMB_RENDER))
            // Only taken off the queue once there's time to draw it.
            if (++drawn >= THUMBS_PER_FRAME) break
            job = source.next() ?: break
        }
        nearOn = near; farOn = far; cloudOn = cloud
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glClearColor(0.01f, 0.012f, 0.03f, 1f)
    }

    /**
     * [rgba] ([size] square, bottom row first, as GL reads it) turned the right way up and halved,
     * each pixel the average of four, as 0xAARRGGBB.
     */
    private fun halved(rgba: ByteArray, size: Int): IntArray {
        val half = size / 2
        val out = IntArray(half * half)
        for (y in 0 until half) for (x in 0 until half) {
            var r = 0; var g = 0; var b = 0; var a = 0
            for (dy in 0..1) for (dx in 0..1) {
                val i = (((y * 2 + dy) * size) + x * 2 + dx) * 4
                r += rgba[i].toInt() and 0xFF
                g += rgba[i + 1].toInt() and 0xFF
                b += rgba[i + 2].toInt() and 0xFF
                a += rgba[i + 3].toInt() and 0xFF
            }
            out[(half - 1 - y) * half + x] = ((a / 4) shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
        }
        return out
    }

    private fun releaseGlObjects() {
        // Names from a lost context are gone with it, and made again when they're next needed.
        thumbTarget = false
        vesselProgram?.release(); vesselProgram = null
        cloudProgram?.release(); cloudProgram = null
        if (instanceBuffer[0] != 0) { GLES30.glDeleteBuffers(1, instanceBuffer, 0); instanceBuffer[0] = 0 }
        if (solidBuffer[0] != 0) { GLES30.glDeleteBuffers(1, solidBuffer, 0); solidBuffer[0] = 0 }
        skyProgram?.release(); skyProgram = null
        terrainProgram?.release(); terrainProgram = null
        cloudShellProgram?.release(); cloudShellProgram = null
        cloudShellMesh?.release(); cloudShellMesh = null
        globeMesh?.release(); globeMesh = null
        for (mesh in farGlobeMeshes.values) mesh.release()
        farGlobeMeshes.clear()
        uploadedGlobe = 0
        // Every chunk on the GPU is gone with the context. Say so, so they get built again instead
        // of drawn from names that don't exist any more.
        for (key in chunkMeshes.keys) terrainSource.discarded(key)
        chunkMeshes.values.forEach { it.release() }
        chunkMeshes.clear()
        spareChunkMeshes.forEach { it.release() }
        spareChunkMeshes.clear()
        chunkIndices?.release(); chunkIndices = null
        scatterRenderer?.release(); scatterRenderer = null
        particleRenderer?.release(); particleRenderer = null
        meshes.values.forEach { it.release() }
        meshes.clear()
        lineProgram?.release(); lineProgram = null
        lineMeshes.forEach { it.release() }
        lineMeshes.clear()
        nearMap?.release(); nearMap = null
        farMap?.release(); farMap = null
        vesselDepth?.release(); vesselDepth = null
        vesselDepthInstanced?.release(); vesselDepthInstanced = null
        if (shadowBuffer[0] != 0) { GLES30.glDeleteBuffers(1, shadowBuffer, 0); shadowBuffer[0] = 0 }
        seaProgram?.release(); seaProgram = null
        seaMesh = null
        terrainDepth?.release(); terrainDepth = null
        farValid = false
        if (cloudTexture[0] != 0) GLES30.glDeleteTextures(1, cloudTexture, 0)
        cloudTexture[0] = 0
        cloudRevision = -1
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private companion object {
        /** For what the sea's dark doesn't reach: the globe from far off. */
        val NO_SEA = FloatArray(4)

        /** The times of the passes [timePasses] measures, in order. */
        /** Cloud lobes within this ratio of distance share a band, drawn many per call. */
        const val BAND_RATIO = 1.6

        val PASS_NAMES = arrayOf("setup", "shadows", "sky", "upload", "globe", "far", "lines", "terrain", "scatter", "items", "sea", "particles")

        /** How big a cell of the map's cloud texture is, in metres, and how long it takes to drift one, in seconds. */
        private const val CLOUD_SHELL_CELL = 40_000.0
        private const val CLOUD_SHELL_DRIFT = 7_200.0

        /** Above this height, in metres, the clouds' shadows fade out, and they're gone at twice it. */
        private const val CLOUD_SHADOW_HIGH = 15_000.0

        /** The fog colour with no weather. It's never seen, since the fog distance is huge. */
        val CLEAR_FOG_COLOR = floatArrayOf(0.75f, 0.77f, 0.8f)

        /** Part pictures are drawn at this size, in pixels, and halved. */
        const val THUMB_RENDER = PartThumbnails.SIZE * 2
        const val THUMBS_PER_FRAME = 3

        /** The most the sea is carried on from when it was built, in seconds. */
        const val SEA_LOOKAHEAD = 0.25

        /**
         * Terrain chunks uploaded per frame at most. Each is about 50 KB. A dozen fits well inside
         * a frame, and the first arrival over new ground spreads over a few frames instead of
         * landing in one.
         */
        const val MAX_CHUNK_UPLOADS_PER_FRAME = 12

        /** And no more than this long a frame on them, in ns, after the first. */
        const val CHUNK_UPLOAD_BUDGET_NANOS = 3_000_000L

        /** Retired chunk meshes kept for reuse. */
        const val MAX_SPARE_CHUNK_MESHES = 48

        /**
         * Near pass: parts and the ground underfoot.
         *
         * Half a metre instead of twenty centimetres. Nothing is drawn closer than the camera's own
         * minimum stand-off, and every doubling of the near plane doubles depth precision across
         * the whole range, which this pass needs now, because it carries terrain out to the terrain
         * patch as well as parts at arm's length.
         */
        const val NEAR_NEAR_PLANE = 0.5

        /** The furthest the near plane is pushed out, in metres, however far away everything is. */
        const val MAX_NEAR_PLANE = 2_000.0

        /**
         * Far enough for the largest patch. Depth resolution at the far end works out at around
         * twenty metres, which would matter for two surfaces meeting at a shallow angle, and
         * doesn't for a heightfield, where nothing lies in the same plane as anything else.
         */
        const val NEAR_FAR_PLANE = 250_000.0

        /** Far pass: the planet and anything else at world scale. */
        const val FAR_NEAR_PLANE = 100.0
        const val FAR_FAR_PLANE = 1.0e8
    }

    /**
     * [colour] as it looks with [daylight] of the sun. Cloud and fog lit by the moon are a dim
     * blue-grey, not the white they are by day.
     */
    private fun nightDim(colour: FloatArray, daylight: Float, out: FloatArray) {
        val light = NightLight.NIGHT_AIR + (1f - NightLight.NIGHT_AIR) * daylight
        out[0] = colour[0] * light
        out[1] = colour[1] * light
        out[2] = colour[2] * (light + (1f - daylight) * 0.04f)
    }

    private fun daylightAt(world: WorldView, cameraPos: Vec3): Float =
        NightLight.daylight(cameraPos, world.radius, world.sunDirection)
}
