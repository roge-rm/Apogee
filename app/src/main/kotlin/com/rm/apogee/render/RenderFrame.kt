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
    val meshSpec: MeshSpec,
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
)

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
)

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
