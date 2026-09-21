package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import java.util.concurrent.atomic.AtomicReference

/** One thing to draw this frame. Immutable: it crosses a thread boundary. */
class RenderItem(
    val meshId: MeshId,
    val position: Vec3,
    val rotation: Quat,
    val scale: Double,
    val color: FloatArray,
)

enum class MeshId { CUBE }

/**
 * An immutable, complete description of one simulation instant.
 *
 * Produced by the simulation thread, consumed by the GL thread. Because it is
 * immutable and handed over by a single atomic reference swap, the two threads
 * never contend and no lock is involved anywhere in the render path.
 */
class RenderFrame(
    val simTick: Long,
    /** Wall-clock nanos when this frame's state was current, for interpolation. */
    val timestampNanos: Long,
    val cameraPosition: Vec3,
    val cameraRotation: Quat,
    val fovYRadians: Double,
    val items: List<RenderItem>,
)

/**
 * The hand-off between the simulation thread and the GL thread.
 *
 * Keeps the two most recent frames so the renderer can interpolate between
 * them: the simulation runs at a fixed 60 Hz, the display may be at 60, 90 or
 * 120, and without interpolation the mismatch shows up as judder even though
 * the physics is perfectly smooth.
 *
 * Lock-free by construction - one atomic swap in, one atomic read out. This is
 * the payoff for the simulation being plain Kotlin data rather than state owned
 * by a native engine that both threads have to take a mutex to touch.
 */
class FrameBus {

    class Pair(val previous: RenderFrame?, val latest: RenderFrame)

    private val frames = AtomicReference<Pair?>(null)

    fun publish(frame: RenderFrame) {
        // getAndUpdate is not available below API 24-era java.util.concurrent on
        // all devices in our range; the explicit CAS loop is equivalent and has
        // no version caveats.
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
