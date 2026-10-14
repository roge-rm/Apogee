package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3

/** Carry the visible sky across worker listings and budget changes, using wall time even when paused. */
class CloudTransitions {
    private class State(val shape: com.rm.apogee.core.part.Shape, var from: RenderItem,
        var last: RenderItem, var target: RenderItem, var started: Long, var leaving: Boolean = false,
        var alphaAt: Long = started, var alphaRate: Double = maxOf(from.color[3], target.color[3]).toDouble() * 2.0)
    private val states = LinkedHashMap<Long, State>()
    private val current = HashMap<Long, RenderItem>()
    private var generation: Any? = null

    fun append(items: List<RenderItem>, revision: Any, now: Long, out: MutableList<RenderItem>) {
        current.clear()
        for (item in items) {
            require(item.key != 0L && current.put(item.key, item) == null) { "Cloud identities must be unique" }
        }
        if (generation !== revision) {
            generation = revision
            for ((key, state) in states) {
                val next = current[key]
                if (next == null && state.leaving) continue
                val nextAlpha = next?.color?.get(3) ?: 0f
                if (kotlin.math.abs(nextAlpha - state.target.color[3]) > .02f) state.alphaAt = now
                state.alphaRate = maxOf(state.alphaRate, nextAlpha * 2.0)
                state.from = state.last
                state.started = now
                state.leaving = next == null
                state.target = next ?: copy(state.last, state.last.position, state.last.rotation, state.last.scale,
                    state.last.color.copyOf().also { it[3] = 0f }, state.shape)
            }
            for ((key, item) in current) if (key !in states) {
                val clear = copy(item, item.position, item.rotation, item.scale, item.color.copyOf().also { it[3] = 0f }, item.shape)
                states[key] = State(item.shape, clear, clear, item, now)
            }
        }
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val (key, state) = iterator.next()
            val u = ((now - state.started).toDouble() / DURATION_NANOS).coerceIn(0.0, 1.0)
            if (state.leaving && u >= 1.0) { iterator.remove(); continue }
            val t = u * u * (3.0 - 2.0 * u)
            val target = if (state.leaving) state.target else current[key] ?: state.target
            val alphaStep = ((now - state.alphaAt).coerceAtLeast(0L) / 1e9 * state.alphaRate).toFloat()
            state.alphaAt = now
            val alpha = state.last.color[3] + (target.color[3] - state.last.color[3]).coerceIn(-alphaStep, alphaStep)
            if (u >= 1.0) {
                val colour = if (alpha == target.color[3]) target.color else target.color.copyOf().also { it[3] = alpha }
                val shown = if (target.shape == state.shape && colour === target.color) target else copy(target,
                    target.position, target.rotation, target.scale, colour, state.shape)
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
            val colour = FloatArray(4) { (target.color[it] + (from.color[it] - anchor.color[it]) * (1.0 - t)).toFloat().coerceIn(0f, 1f) }
            colour[3] = alpha
            val rotation = Quat.slerp(from.rotation, target.rotation, t, Quat())
            val shown = copy(target, position, rotation, scale, colour, state.shape)
            state.last = shown
            out.add(shown)
        }
    }

    private fun copy(item: RenderItem, position: Vec3, rotation: Quat, scale: Vec3?, colour: FloatArray,
        shape: com.rm.apogee.core.part.Shape) = RenderItem(shape, position, rotation, colour, item.caps, scale,
        item.ambient, item.wrap, item.key, item.decal, item.sky, item.curtain, item.orderedTransparency)

    companion object { const val DURATION_NANOS = 500_000_000L }
}
