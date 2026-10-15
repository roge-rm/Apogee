package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Shape

/** Carry the visible sky across worker listings and budget changes, using wall time even when paused. */
class CloudTransitions {
    /**
     * One cloud as listed this frame. The caller reuses these frame to frame, so nothing here is kept
     * or drawn as it is: [position], [rotation] and [grown] are copied out. [colour] and a [scale]
     * other than [grown] are shared and never changed.
     */
    class Cloud {
        lateinit var shape: Shape
        val position = Vec3()
        val rotation = Quat()
        var scale: Vec3? = null
        /** Room for a scale worked out this frame. */
        val grown = Vec3()
        var colour: FloatArray = CLEAR
        /** Its opacity, drawn in place of [colour]'s own. */
        var alpha = 0f
        var caps = StackCaps.BOTH
        var ambient = 0.28f
        var wrap = false
        var key = 0L
        var decal = 0
        var sky = false
        var curtain = false
        var orderedTransparency = false

        fun setFrom(item: RenderItem): Cloud {
            shape = item.shape; position.setTo(item.position); rotation.setTo(item.rotation)
            scale = item.scale; colour = item.color; alpha = item.color[3]
            caps = item.caps; ambient = item.ambient; wrap = item.wrap; key = item.key; decal = item.decal
            sky = item.sky; curtain = item.curtain; orderedTransparency = item.orderedTransparency
            return this
        }

        /** Its scale to keep in an item, copied if it's only room for this frame. */
        fun keptScale(): Vec3? = scale?.let { if (it === grown) it.copy() else it }

        /** Its colour with [alpha], shared if that's its own. */
        fun keptColour(alpha: Float = this.alpha): FloatArray =
            if (alpha == colour[3]) colour else colour.copyOf().also { it[3] = alpha }
    }

    private class State(val shape: Shape, var from: RenderItem,
        var last: RenderItem, var target: RenderItem, var started: Long, var leaving: Boolean = false,
        var alphaAt: Long = started, var alphaRate: Double = maxOf(from.color[3], target.color[3]).toDouble() * 2.0)
    private val states = LinkedHashMap<Long, State>()
    private val current = LongMap<Cloud>(512)
    private var generation: Any? = null
    private val fallback = Cloud()
    private val adopted = ArrayList<Cloud>()

    /** As [append] below, from items. */
    fun append(items: List<RenderItem>, revision: Any, now: Long, out: MutableList<RenderItem>) {
        while (adopted.size < items.size) adopted.add(Cloud())
        for (i in items.indices) adopted[i].setFrom(items[i])
        append(adopted, items.size, revision, now, out)
    }

    /** Appends the first [count] of [clouds], eased from what was shown, to [out]. */
    fun append(clouds: List<Cloud>, count: Int, revision: Any, now: Long, out: MutableList<RenderItem>) {
        current.clear()
        for (i in 0 until count) {
            val cloud = clouds[i]
            require(cloud.key != 0L && current.put(cloud.key, cloud) == null) { "Cloud identities must be unique" }
        }
        if (generation !== revision) {
            generation = revision
            for ((key, state) in states) {
                val next = current[key]
                if (next == null && state.leaving) continue
                val nextAlpha = next?.alpha ?: 0f
                if (kotlin.math.abs(nextAlpha - state.target.color[3]) > .02f) state.alphaAt = now
                state.alphaRate = maxOf(state.alphaRate, nextAlpha * 2.0)
                state.from = state.last
                state.started = now
                state.leaving = next == null
                state.target = next?.let { kept(it) } ?: copy(state.last, state.last.position, state.last.rotation,
                    state.last.scale, state.last.color.copyOf().also { it[3] = 0f }, state.shape)
            }
            // New ones in listing order.
            for (i in 0 until count) {
                val cloud = clouds[i]
                if (cloud.key in states) continue
                val target = kept(cloud)
                val clear = copy(target, target.position, target.rotation, target.scale,
                    target.color.copyOf().also { it[3] = 0f }, target.shape)
                states[cloud.key] = State(cloud.shape, clear, clear, target, now)
            }
        }
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val state = entry.value
            val u = ((now - state.started).toDouble() / DURATION_NANOS).coerceIn(0.0, 1.0)
            if (state.leaving && u >= 1.0) { iterator.remove(); continue }
            val t = u * u * (3.0 - 2.0 * u)
            val listed = if (state.leaving) null else current[entry.key]
            val target = listed ?: fallback.setFrom(state.target)
            val alphaStep = ((now - state.alphaAt).coerceAtLeast(0L) / 1e9 * state.alphaRate).toFloat()
            state.alphaAt = now
            val alpha = state.last.color[3] + (target.alpha - state.last.color[3]).coerceIn(-alphaStep, alphaStep)
            if (u >= 1.0) {
                val kept = state.target
                val shown = if (listed == null && kept.shape == state.shape && alpha == kept.color[3]) kept
                    else RenderItem(state.shape, target.position.copy(), target.rotation.copy(), target.keptColour(alpha),
                        target.caps, target.keptScale(), target.ambient, target.wrap, target.key, target.decal,
                        target.sky, target.curtain, target.orderedTransparency)
                state.last = shown
                out.add(shown)
                continue
            }
            val from = state.from
            // Keep carrying the cloud along its drift while correcting the worker's new snapshot.
            val anchor = state.target
            val position = Vec3(target.position.x + (from.position.x - anchor.position.x) * (1.0 - t),
                target.position.y + (from.position.y - anchor.position.y) * (1.0 - t),
                target.position.z + (from.position.z - anchor.position.z) * (1.0 - t))
            val scale = target.scale?.let { b ->
                val a = from.scale ?: b
                val c = anchor.scale ?: b
                Vec3(b.x + (a.x - c.x) * (1.0 - t), b.y + (a.y - c.y) * (1.0 - t), b.z + (a.z - c.z) * (1.0 - t))
            }
            val colour = FloatArray(4) { (target.colour[it] + (from.color[it] - anchor.color[it]) * (1.0 - t)).toFloat().coerceIn(0f, 1f) }
            colour[3] = alpha
            val rotation = Quat.slerp(from.rotation, target.rotation, t, Quat())
            val shown = RenderItem(state.shape, position, rotation, colour, target.caps, scale, target.ambient,
                target.wrap, target.key, target.decal, target.sky, target.curtain, target.orderedTransparency)
            state.last = shown
            out.add(shown)
        }
    }

    /** [cloud] as an item that can be kept: nothing in it is reused. */
    private fun kept(cloud: Cloud) = RenderItem(cloud.shape, cloud.position.copy(), cloud.rotation.copy(),
        cloud.keptColour(), cloud.caps, cloud.keptScale(), cloud.ambient, cloud.wrap, cloud.key, cloud.decal,
        cloud.sky, cloud.curtain, cloud.orderedTransparency)

    private fun copy(item: RenderItem, position: Vec3, rotation: Quat, scale: Vec3?, colour: FloatArray,
        shape: Shape) = RenderItem(shape, position, rotation, colour, item.caps, scale,
        item.ambient, item.wrap, item.key, item.decal, item.sky, item.curtain, item.orderedTransparency)

    companion object {
        const val DURATION_NANOS = 500_000_000L
        private val CLEAR = FloatArray(4)
    }
}
