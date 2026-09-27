package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.ScatterKind
import java.util.concurrent.atomic.AtomicReference

/**
 * One block of scatter, ready to draw: instances grouped by kind.
 *
 * Instances are [INSTANCE_FLOATS] each: position relative to [centre] (body-fixed), size, and yaw.
 * [offsets] and [counts] say where each kind's run starts, so a block is one buffer drawn once for
 * each kind in it.
 */
class ScatterDraw(
    val key: Long,
    val revision: Int,
    val centre: Vec3,
    /** East, up and north at the block, body-fixed: the frame the instances turn in. */
    val basis: FloatArray,
    val boundingRadius: Double,
    @Volatile var instances: FloatArray?,
    val offsets: IntArray,
    val counts: IntArray,
) {
    companion object {
        const val INSTANCE_FLOATS = 5
        val KINDS = ScatterKind.entries.size
    }
}

/** The hand-off from the scatter streamer to the GL thread. */
class ScatterSource {
    private val drawListRef = AtomicReference<List<ScatterDraw>>(emptyList())
    fun publish(list: List<ScatterDraw>) = drawListRef.set(list)
    fun drawList(): List<ScatterDraw> = drawListRef.get()
    fun clear() = drawListRef.set(emptyList())
}
