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
    /** Radius of the body being orbited, for drawing its surface. */
    val attractorRadius: Double = 0.0,
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
