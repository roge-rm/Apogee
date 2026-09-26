package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import java.util.concurrent.atomic.AtomicReference

/**
 * One thing to draw this frame.
 *
 * Carries the part's [MeshSpec] rather than a mesh handle: the simulation has
 * no business knowing what is on the GPU, and the renderer builds and caches a
 * mesh per distinct shape the first time it sees one.
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
     * Per-item rather than per-shape: the same tank draws both caps standing
     * alone and neither in the middle of a stack.
     */
    val caps: Int = StackCaps.BOTH,
    /** Stretch along the shape's own axes, or null for none: cloud lobes. */
    val scale: Vec3? = null,
    /** Light it has without the sun: 0.28 for a part, much more for a cloud. */
    val ambient: Float = 0.28f,
    /**
     * Whether it wraps its light round and thins at the edges, as cloud and
     * vapour do; not a part, however squashed a crash has left it.
     */
    val wrap: Boolean = scale != null && ambient < 1f,
    /**
     * Which thing this is from frame to frame - this craft's this part's
     * this piece - so the renderer eases it from where the same thing was
     * last frame. Without one it is matched by its place among the unkeyed
     * items, which holds only while nothing before it comes or goes: a
     * flame lighting or a craft dropping out of a frame shifted every part
     * after it onto another part's position, and the craft was drawn tens
     * of metres off for a frame. 0 for none.
     */
    val key: Long = 0L,
    /**
     * Laid on the ground - paving - drawn over it by this many steps of
     * depth bias, each over the ones below; casts no shadow. 0 for anything
     * else.
     */
    val decal: Int = 0,
    /**
     * Another world seen across space - a moon, a planet: lit by the sun,
     * not hazed away with distance as the ground is, only paled a little by
     * a daytime sky.
     */
    val sky: Boolean = false,
) {
    companion object {
        /** A craft's part's piece. */
        fun partKey(vessel: Long, part: Int, piece: Int): Long =
            ((vessel * 1_000_003L + part) * 1_024L + piece) and KEY_MASK or PART_BIT

        /** One of a craft's effects - flame, vapour, shock - by a seed of its own and a slot. */
        fun effectKey(seed: Long, slot: Int): Long =
            (seed * 64L + slot) and KEY_MASK or EFFECT_BIT

        private const val KEY_MASK = (1L shl 60) - 1
        private const val PART_BIT = 1L shl 61
        private const val EFFECT_BIT = 1L shl 60
    }
}

/**
 * An immutable, complete description of one instant, ready to draw.
 *
 * Produced by the game thread, consumed by the GL thread. Because it is
 * immutable and handed over by a single atomic reference swap, the two threads
 * never contend and no lock is involved anywhere in the render path.
 */
class RenderFrame(
    val simTick: Long,
    /** Wall-clock nanos this frame's state was current, for interpolation. */
    val timestampNanos: Long,
    /**
     * Camera position in the same frame as [RenderItem.position] - relative to
     * the vessel's attractor. Everything is made camera-relative in double
     * before being narrowed to float; see Mat4.setFromTrs.
     */
    val cameraPosition: Vec3,
    val cameraRotation: Quat,
    val fovYRadians: Double,
    val items: List<RenderItem>,
    /** The world to draw around the craft, or null in the assembly building. */
    val world: WorldView? = null,
    /** Trajectories to draw, in the attractor's frame. Map view only. */
    val lines: List<RenderLine> = emptyList(),
    /**
     * Metres to the nearest thing in view - a part, or the ground below the
     * camera - or 0 when unknown. The near plane goes at half of it, because
     * depth precision is spent in proportion to how close the near plane is:
     * pinned at half a metre with ground out to 250 km, two surfaces ten
     * kilometres off could not be told apart within about twelve metres, and
     * the strips hiding chunk seams fought the ground beside them for every
     * pixel along the seam, flickering as the camera moved.
     */
    val nearestDistance: Double = 0.0,
    /**
     * Smoke, dust, spray, rain and bolts: [particleShapes] shapes of six
     * camera-relative vertices each, [com.rm.apogee.game.Effects.VERTEX_FLOATS]
     * floats a vertex. Shared with the game thread's triple buffer, so read
     * only this frame.
     */
    val particles: FloatArray? = null,
    val particleShapes: Int = 0,
    /**
     * Things at planet scale, drawn in the far pass with the globe: the
     * map's cloud. Not interpolated.
     */
    val farItems: List<RenderItem> = emptyList(),
    /**
     * Where the craft being flown is, absolute, and how far round it things
     * cast shadows onto each other and the ground, m; null for no shadows
     * (the map, the assembly building).
     */
    val shadowFocus: Vec3? = null,
    val shadowRadius: Double = 0.0,
)

/**
 * A polyline in the attractor's frame - an orbit, or a marker cross.
 *
 * Points are absolute in that frame rather than camera-relative; the renderer
 * does the floating-origin subtraction in double, as it does for everything
 * else. Pre-subtracting here would throw away the precision that makes the
 * subtraction worth doing.
 */
class RenderLine(
    val points: List<Vec3>,
    val color: FloatArray,
)

/**
 * The celestial body the camera is near, for the sky and planet passes.
 *
 * Positions are in the attractor's own frame, which is the same frame
 * [RenderFrame.cameraPosition] and [RenderItem.position] use - so the planet's
 * centre is simply the origin, and its camera-relative position is
 * `-cameraPosition`.
 */
class WorldView(
    val radius: Double,
    val atmosphereHeight: Double,
    val atmosphereScaleHeight: Double,
    /** Unit vector from the planet toward the star. */
    val sunDirection: Vec3,
    /**
     * Surface normal at the launch complex.
     *
     * The shader raises terrain around it so the pad is on land. The
     * simulation collides against a sphere and has no opinion about
     * coastlines, so this is purely cosmetic - but a launch complex floating
     * in the middle of an ocean is a detail nobody will let pass.
     */
    val homeDirection: Vec3,
    /** Altitude of the camera above the datum, metres. */
    val cameraAltitude: Double,
    /** The body's rotation now, so terrain is drawn where it actually is. */
    val bodyRotation: Quat,
    /** Highest terrain, for colouring by height. */
    val maxElevation: Double,
    /**
     * Whether the distant surface - the coarse whole-body mesh and the sea
     * sphere - is needed at all.
     *
     * False when the near patch already reaches past the horizon, at which
     * point both are entirely hidden behind ground the patch has already
     * drawn. Skipping them is worth real frame time: each is a full-screen
     * fill of a screen that is about to be painted over.
     */
    val drawFarSurface: Boolean = true,
    /**
     * How far out the chunks reach, metres, or 0 when there are none. The
     * globe is drawn only beyond this: nearer, the chunks have the ground,
     * and the globe's coarse surface - higher than a valley floor, say -
     * would otherwise show through above them like a ceiling.
     */
    val chunkRange: Double = 0.0,
    /**
     * How far the weather lets the camera see, metres: cloud and rain close
     * it in. Very large in clear air.
     */
    val fogDistance: Double = CLEAR_FOG,
    /** What the fog is: bright white in cumulus, dark grey under a storm. */
    val fogColor: FloatArray = floatArrayOf(0.75f, 0.77f, 0.8f),
    /** 1 inside cloud, where the sky itself is lost. */
    val skyFog: Float = 0f,
    /** Sunlight left, 0..1: a storm overhead dims it. */
    val lightScale: Float = 1f,
    /** Lightning's own light this frame, 0 for none: lights the scene day or night. */
    val flash: Float = 0f,
    /** The clouds' shadows on the ground round the camera, or null. */
    val cloudShadow: CloudShadowGrid? = null,
    /** The wind near the ground, in the body's frame, for trees to lean in. */
    val surfaceWind: Vec3 = Vec3(),
    /** Universe time, for anything that sways. */
    val time: Double = 0.0,
    /**
     * How far round the camera the sea is drawn as waves, m; the terrain
     * draws flat water beyond, and the sea bed within. 0 for no sea drawn.
     */
    val seaReach: Double = 0.0,
    /** The tide under the camera, m above the datum: where flat water stands. */
    val tide: Double = 0.0,
    /** The sea round the camera, as built this frame; null for none. */
    val sea: SeaSurface? = null,
    /** The camera is under the water. */
    val underwater: Boolean = false,
    /**
     * Lit lamps lighting what is round them, nearest the camera first:
     * body-fixed x, y, z and reach, four to a lamp, at most
     * [MAX_LAMPS] of them.
     */
    val lamps: DoubleArray = NO_LAMPS,
) {
    companion object {
        const val CLEAR_FOG = 1.0e9
        val NO_LAMPS = DoubleArray(0)

        /** Lamps the renderer lights with at once: the shaders' LAMPS. */
        const val MAX_LAMPS = 8
    }
}

/**
 * The hand-off between the game thread and the GL thread.
 *
 * Keeps the two most recent frames so the renderer can interpolate between
 * them: the simulation runs at a fixed 60 Hz and the server streams at 20, while
 * the display may be at 60, 90 or 120. Without interpolation that mismatch
 * shows up as judder even though the physics is perfectly smooth.
 *
 * Lock-free by construction: one atomic swap in, one atomic read out.
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
