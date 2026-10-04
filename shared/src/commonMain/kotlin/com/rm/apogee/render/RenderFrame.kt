package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.platform.AtomicReference

/**
 * One thing to draw this frame. It carries a [MeshSpec], not a GPU handle; the renderer builds and
 * caches a mesh per shape the first time it sees one.
 */
class RenderItem(
    /** A physics [MeshSpec] or a leaf of a part model. */
    val shape: com.rm.apogee.core.part.Shape,
    val position: Vec3,
    val rotation: Quat,
    val color: FloatArray,
    /**
     * Which end caps to draw, from [com.rm.apogee.render.StackCaps]. Per item, since the same tank
     * needs different caps alone and mid-stack.
     */
    val caps: Int = StackCaps.BOTH,
    /** Stretch along the shape's own axes, or null for none. For cloud lobes. */
    val scale: Vec3? = null,
    /** The light it has without the sun: 0.28 for a part, much more for a cloud. */
    val ambient: Float = 0.28f,
    /** Wraps its light round and thins at the edges, like cloud and vapour. Never a part. */
    val wrap: Boolean = scale != null && ambient < 1f,
    /**
     * Identifies this thing from frame to frame, so the renderer eases it from where it was. With
     * 0 it's matched by its place among unkeyed items, which breaks when anything before it comes
     * or goes.
     */
    val key: Long = 0L,
    /** Laid on the ground (paving): this many steps of depth bias over it. No shadow. 0 otherwise. */
    val decal: Int = 0,
    /** Another world seen across space. Sunlit, not hazed by distance, paled a little by day. */
    val sky: Boolean = false,
    /**
     * A column of rain seen from afar. It thins toward its round outline, so its sides are soft,
     * and fades into the cloud at its top.
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
 * One instant, ready to draw and never changed. The game thread makes it and hands it to the GL
 * thread by an atomic swap, so the render path has no locks.
 */
class RenderFrame(
    val simTick: Long,
    /** The wall-clock nanos this frame's state was current at, for interpolation. */
    val timestampNanos: Long,
    /**
     * The camera position in the same frame as [RenderItem.position], relative to the attractor.
     * Everything is made camera-relative in double before narrowing to float. See Mat4.setFromTrs.
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
     * Worlds that hide the parts of [lines] behind them, as centre x, y, z and radius, in the same
     * frame. The hidden parts are drawn dim and broken.
     */
    val occluders: DoubleArray = DoubleArray(0),
    /**
     * Metres to the nearest thing in view (a part, or the ground below), or 0 if unknown. The near
     * plane goes at half of it to keep depth precision for distant ground, where a fixed close
     * near plane made chunk seams flicker.
     */
    val nearestDistance: Double = 0.0,
    /**
     * Smoke, dust, spray, rain and bolts: [particleShapes] shapes of six camera-relative vertices,
     * [com.rm.apogee.game.Effects.VERTEX_FLOATS] floats each. Shared with the game thread's triple
     * buffer, so only read it this frame.
     */
    val particles: FloatArray? = null,
    val particleShapes: Int = 0,
    /** Planet-scale things drawn in the far pass with the globe, like the map's cloud. Not interpolated. */
    val farItems: List<RenderItem> = emptyList(),
    /** The other worlds big enough in the sky to be drawn as themselves. See [FarGlobe]. */
    val farGlobes: List<FarGlobe> = emptyList(),
    /**
     * Where the flown craft is, absolute, and how far round it shadows are cast, in metres. Null for
     * no shadows (the map, the assembly building).
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
 * A polyline in the attractor's frame, like an orbit or a marker cross. Points are absolute; the
 * renderer makes them camera-relative in double.
 */
class RenderLine(
    val points: List<Vec3>,
    val color: FloatArray,
)

/**
 * The body the camera is near, for the sky and planet passes. Positions are in the attractor's
 * frame, as [RenderFrame.cameraPosition] and [RenderItem.position] are, so the planet's centre is
 * the origin and its camera-relative position is `-cameraPosition`.
 */
class WorldView(
    val radius: Double,
    val atmosphereHeight: Double,
    val atmosphereScaleHeight: Double,
    /** A unit vector from the planet toward the star. */
    val sunDirection: Vec3,
    /** The surface normal at the launch complex. The shader raises terrain round it so the pad is on land. */
    val homeDirection: Vec3,
    /** The camera's altitude above the datum, in metres. */
    val cameraAltitude: Double,
    /** The body's rotation now, so terrain gets drawn where it actually is. */
    val bodyRotation: Quat,
    /** The highest terrain, for colouring by height. */
    val maxElevation: Double,
    /**
     * Whether the distant surface (coarse globe and sea sphere) is needed. False when the near patch
     * reaches past the horizon and hides it all, which saves a full-screen fill.
     */
    val drawFarSurface: Boolean = true,
    /**
     * How far out the chunks reach, in metres, or 0 for none. The globe is only drawn beyond this,
     * so its coarse surface can't show through above the chunks.
     */
    val chunkRange: Double = 0.0,
    /** How far the weather lets the camera see, in metres. Very large in clear air. */
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
     * How far round the camera the sea is drawn as waves, in metres. The terrain draws flat water
     * beyond and the seabed within. 0 for no sea.
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
    /** The sea's surface, metres from the body's centre, for the light under it. 0 for no sea. */
    val seaRadius: Double = 0.0,
    /** How far each of red, green and blue light gets through the sea, in metres per e-fold. */
    val water: FloatArray = TERRA_WATER,
    /**
     * Lit lamps, nearest the camera first, at most [MAX_LAMPS], [LAMP_FLOATS] each: body-fixed x,
     * y, z and reach, then the beam's way and the cosine of its edge (past -1 for all round).
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
        const val LAMP_FLOATS = 8
    }
}

/**
 * The lock-free hand-off from the game thread to the GL thread. It keeps the two latest frames so
 * the renderer can interpolate: the sim runs at 60 Hz and the server streams at 20, while the
 * display may run at 60, 90 or 120.
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
 * Another world drawn as itself in the far pass: ground and seas, or a giant's bands, flat per
 * triangle and sunlit. [position] is from the craft's world, [radius] in metres, and [globe] is a
 * mesh shared across frames.
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
