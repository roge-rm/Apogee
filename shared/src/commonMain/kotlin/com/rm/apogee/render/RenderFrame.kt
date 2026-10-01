package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.platform.AtomicReference

/**
 * One thing to draw this frame.
 *
 * It carries the part's [MeshSpec] instead of a mesh handle. The simulation has no business knowing
 * what's on the GPU, and the renderer builds and caches a mesh for each different shape the first
 * time it sees one.
 */
class RenderItem(
    /** A physics [MeshSpec] or a leaf of a part model. */
    val shape: com.rm.apogee.core.part.Shape,
    val position: Vec3,
    val rotation: Quat,
    val color: FloatArray,
    /**
     * Which end caps to draw, from [com.rm.apogee.render.StackCaps].
     *
     * It's per item instead of per shape, because the same tank draws both caps standing alone and
     * neither in the middle of a stack.
     */
    val caps: Int = StackCaps.BOTH,
    /** Stretch along the shape's own axes, or null for none. For cloud lobes. */
    val scale: Vec3? = null,
    /** The light it has without the sun: 0.28 for a part, much more for a cloud. */
    val ambient: Float = 0.28f,
    /**
     * Whether it wraps its light round and thins at the edges, like cloud and vapour do. Not a
     * part, however squashed a crash has left it.
     */
    val wrap: Boolean = scale != null && ambient < 1f,
    /**
     * Which thing this is from frame to frame (this craft's, this part's, this piece), so the
     * renderer eases it from where the same thing was last frame. Without one it's matched by its
     * place among the items with no key, which only works while nothing before it comes or goes. A
     * flame lighting or a craft dropping out of a frame shifted every part after it onto another
     * part's position, and the craft got drawn tens of metres off for a frame. 0 for none.
     */
    val key: Long = 0L,
    /**
     * Laid on the ground (paving), drawn over it by this many steps of depth bias, each over the
     * ones below. It casts no shadow. 0 for anything else.
     */
    val decal: Int = 0,
    /**
     * Another world seen across space, like a moon or a planet. It's lit by the sun, and not hazed
     * away with distance the way the ground is, only paled a little by a daytime sky.
     */
    val sky: Boolean = false,
    /**
     * A column of rain seen from afar. It thins toward its outline by its round shape rather than
     * by its facets, so its sides are soft instead of twelve hard strips, and it fades out into the
     * cloud at its top.
     */
    val curtain: Boolean = false,
) {
    companion object {
        /** A piece of a craft's part. */
        fun partKey(vessel: Long, part: Int, piece: Int): Long =
            ((vessel * 1_000_003L + part) * 1_024L + piece) and KEY_MASK or PART_BIT

        /** One of a craft's effects (flame, vapour, shock), by a seed of its own and a slot. */
        fun effectKey(seed: Long, slot: Int): Long =
            (seed * 64L + slot) and KEY_MASK or EFFECT_BIT

        private const val KEY_MASK = (1L shl 60) - 1
        private const val PART_BIT = 1L shl 61
        private const val EFFECT_BIT = 1L shl 60
    }
}

/**
 * A complete description of one instant that never changes, ready to draw.
 *
 * The game thread makes it and the GL thread uses it. Because it never changes and is handed over
 * by a single atomic reference swap, the two threads never fight over it and there's no lock
 * anywhere in the render path.
 */
class RenderFrame(
    val simTick: Long,
    /** The wall-clock nanos this frame's state was current at, for interpolation. */
    val timestampNanos: Long,
    /**
     * The camera position in the same frame as [RenderItem.position], relative to the vessel's
     * attractor. Everything is made camera-relative in double before being narrowed to float. See
     * Mat4.setFromTrs.
     */
    val cameraPosition: Vec3,
    val cameraRotation: Quat,
    val fovYRadians: Double,
    val items: List<RenderItem>,
    /** The world to draw around the craft, or null in the assembly building. */
    val world: WorldView? = null,
    /** Paths to draw, in the attractor's frame. Map view only. */
    val lines: List<RenderLine> = emptyList(),
    /**
     * Metres to the nearest thing in view (a part, or the ground below the camera), or 0 when it
     * isn't known. The near plane goes at half of it, because depth precision gets spent in
     * proportion to how close the near plane is. Pinned at half a metre with ground out to 250 km,
     * two surfaces ten kilometres off couldn't be told apart within about twelve metres, and the
     * strips hiding chunk seams fought the ground beside them for every pixel along the seam,
     * flickering as the camera moved.
     */
    val nearestDistance: Double = 0.0,
    /**
     * Smoke, dust, spray, rain and bolts: [particleShapes] shapes of six camera-relative vertices
     * each, with [com.rm.apogee.game.Effects.VERTEX_FLOATS] floats per vertex. It's shared with the
     * game thread's triple buffer, so only read it this frame.
     */
    val particles: FloatArray? = null,
    val particleShapes: Int = 0,
    /**
     * Things at planet scale, drawn in the far pass with the globe, like the map's cloud. Not
     * interpolated.
     */
    val farItems: List<RenderItem> = emptyList(),
    /** The other worlds big enough in the sky to be drawn as themselves. See [FarGlobe]. */
    val farGlobes: List<FarGlobe> = emptyList(),
    /**
     * Where the craft being flown is, absolute, and how far around it things cast shadows onto each
     * other and the ground, in metres. Null for no shadows (the map, the assembly building).
     */
    val shadowFocus: Vec3? = null,
    val shadowRadius: Double = 0.0,
    /** How far the far pass reaches, in metres. Further on a map of the whole system. */
    val farReach: Double = FAR_REACH,
) {
    companion object {
        /** The far pass's usual reach: a planet and its moons. */
        const val FAR_REACH = 1.0e8
    }
}

/**
 * A polyline in the attractor's frame, like an orbit or a marker cross.
 *
 * Points are absolute in that frame, not camera-relative. The renderer does the floating-origin
 * subtraction in double, the same as it does for everything else. Subtracting here first would
 * throw away the precision that makes the subtraction worth doing.
 */
class RenderLine(
    val points: List<Vec3>,
    val color: FloatArray,
)

/**
 * The celestial body the camera is near, for the sky and planet passes.
 *
 * Positions are in the attractor's own frame, which is the same frame [RenderFrame.cameraPosition]
 * and [RenderItem.position] use. So the planet's centre is simply the origin, and its
 * camera-relative position is `-cameraPosition`.
 */
class WorldView(
    val radius: Double,
    val atmosphereHeight: Double,
    val atmosphereScaleHeight: Double,
    /** A unit vector from the planet toward the star. */
    val sunDirection: Vec3,
    /**
     * The surface normal at the launch complex.
     *
     * The shader raises terrain around it so the pad is on land. The simulation collides against a
     * sphere and doesn't care about coastlines, so this is purely cosmetic, but a launch complex
     * floating in the middle of an ocean is a detail nobody would let pass.
     */
    val homeDirection: Vec3,
    /** The camera's altitude above the datum, in metres. */
    val cameraAltitude: Double,
    /** The body's rotation now, so terrain gets drawn where it actually is. */
    val bodyRotation: Quat,
    /** The highest terrain, for colouring by height. */
    val maxElevation: Double,
    /**
     * Whether the distant surface (the coarse whole-body mesh and the sea sphere) is needed at all.
     *
     * It's false when the near patch already reaches past the horizon, because then both are
     * completely hidden behind ground the patch has already drawn. Skipping them saves real frame
     * time, since each is a full-screen fill of a screen that's about to be painted over.
     */
    val drawFarSurface: Boolean = true,
    /**
     * How far out the chunks reach, in metres, or 0 when there aren't any. The globe is only drawn
     * beyond this. Nearer, the chunks have the ground, and the globe's coarse surface (higher than
     * a valley floor, say) would otherwise show through above them like a ceiling.
     */
    val chunkRange: Double = 0.0,
    /**
     * How far the weather lets the camera see, in metres. Cloud and rain close it in. It's very
     * large in clear air.
     */
    val fogDistance: Double = CLEAR_FOG,
    /** What the fog is: bright white in cumulus, dark grey under a storm. */
    val fogColor: FloatArray = floatArrayOf(0.75f, 0.77f, 0.8f),
    /** 1 inside cloud, where the sky itself is lost. */
    val skyFog: Float = 0f,
    /** The sunlight that's left, 0..1. A storm overhead dims it. */
    val lightScale: Float = 1f,
    /** Lightning's own light this frame, 0 for none. It lights the scene day or night. */
    val flash: Float = 0f,
    /** The clouds' shadows on the ground around the camera, or null. */
    val cloudShadow: CloudShadowGrid? = null,
    /** The wind near the ground, in the body's frame, for trees to lean in. */
    val surfaceWind: Vec3 = Vec3(),
    /** Universe time, for anything that sways. */
    val time: Double = 0.0,
    /** How fast that time is running, as a multiple of real time: the warp. */
    val warp: Double = 1.0,
    /**
     * How far around the camera the sea is drawn as waves, in metres. The terrain draws flat water
     * beyond that, and the seabed within it. 0 for no sea drawn.
     */
    val seaReach: Double = 0.0,
    /** The tide under the camera, in metres above the datum, where flat water stands. */
    val tide: Double = 0.0,
    /** The sea around the camera, as built this frame. Null for none. */
    val sea: SeaSurface? = null,
    /** The camera is under the water. */
    val underwater: Boolean = false,
    /** The planet's cloud as a veil, for the map. Null for none drawn. */
    val cloudShell: CloudShell? = null,
    /**
     * The sea's surface, in metres from the body's centre, for the light under it. 0 for no sea.
     */
    val seaRadius: Double = 0.0,
    /** How far each of red, green and blue light gets through the sea, in metres per e-fold. */
    val water: FloatArray = TERRA_WATER,
    /**
     * Lit lamps lighting what's around them, nearest the camera first: body-fixed x, y, z and
     * reach, four per lamp, at most [MAX_LAMPS] of them.
     */
    val lamps: DoubleArray = NO_LAMPS,
    /** The colours of this world's air. */
    val sky: SkyColours = SkyColours.TERRA,
    /** How big the star looks, as its angular radius in radians. 0 for none drawn. */
    val sunSize: Double = 0.0,
    /** Its light, as a share of what it is at Terra. */
    val sunStrength: Double = 1.0,
) {
    companion object {
        const val CLEAR_FOG = 1.0e9

        /** Sea water: red is gone in the first few tens of metres, and blue lasts longest. */
        val TERRA_WATER = floatArrayOf(12f, 40f, 55f)

        /** Liquid methane: murkier, and the reds and browns last. */
        val AURANTIA_WATER = floatArrayOf(28f, 20f, 12f)
        val NO_LAMPS = DoubleArray(0)

        /** Lamps the renderer can light with at once: the shaders' LAMPS. */
        const val MAX_LAMPS = 8
    }
}

/**
 * The hand-off between the game thread and the GL thread.
 *
 * It keeps the two most recent frames so the renderer can interpolate between them. The simulation
 * runs at a fixed 60 Hz and the server streams at 20, while the display might be at 60, 90 or 120.
 * Without interpolation that mismatch shows up as judder, even though the physics is perfectly
 * smooth.
 *
 * It's lock-free by design: one atomic swap in, and one atomic read out.
 */
class FrameBus {

    class Pair(val previous: RenderFrame?, val latest: RenderFrame)

    private val frames = AtomicReference<Pair?>(null)

    fun publish(frame: RenderFrame) {
        while (true) {
            val current = frames.get()
            val next = Pair(current?.latest, frame)
            if (frames.compareAndSet(current, next)) return
        }
    }

    fun latest(): Pair? = frames.get()

    fun clear() {
        frames.set(null)
    }
}

/**
 * Another world, drawn as itself in the far pass: its own ground and seas, or its bands if it's a
 * giant, flat per triangle, lit by the sun. [position] is from the world the craft is at, turned
 * [rotation], [radius] metres across its middle, and [globe] is its mesh, the same for every frame.
 */
class FarGlobe(
    val id: String,
    val position: Vec3,
    val rotation: com.rm.apogee.core.math.Quat,
    val radius: Double,
    val hasAir: Boolean,
    val globe: PlanetMesh.Data,
    /** The glow of its air round its edge, rgb. */
    val rim: FloatArray = floatArrayOf(0.25f, 0.45f, 0.78f),
)
