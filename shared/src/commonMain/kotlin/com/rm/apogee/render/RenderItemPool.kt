package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec

/**
 * Render items the frame builder fills again instead of making new ones, once the GL thread is
 * done with the frame they went out in. Frame-build thread only.
 *
 * Each build's items make one batch. [published] tags it with the number [FrameBus.publish] gave
 * its frame. The GL thread draws two frames in a row, then sets [FrameBus.releasedBelow] to the
 * older one's number; anything it holds after that is that new or newer, since the bus only moves
 * forward. A batch tagged below that is free to fill again. A batch that never went out is let go,
 * and so is the oldest when more than [MOST_WAITING] wait, so a GL thread that stops drawing only
 * costs the allocation this saves.
 *
 * An item taken here must only go into this build's frame: anything kept from build to build has
 * to be copied out of it.
 */
class RenderItemPool(private val bus: FrameBus) {
    private class Batch {
        val items = ArrayList<RenderItem>()
        /** The frame's own list, which goes out and comes back with the items. */
        val list = ArrayList<RenderItem>()
        var used = 0
        var sequence = 0L

        /** Lets go of the shapes, colours and frame it pointed at. */
        fun emptied(): Batch {
            list.clear()
            for (i in items.indices) items[i].refill(NOTHING, NO_COLOUR)
            return this
        }
    }

    /** Items per build lately, eased. */
    private var recent = 0.0

    private val waiting = ArrayDeque<Batch>()
    private val free = ArrayList<Batch>()
    private var current: Batch? = null

    /** Starts a build's batch, from the free ones where it can. */
    fun begin() {
        // One that never went out is only reused if nothing was taken from it.
        current?.let { if (it.used == 0) free.add(it) }
        val released = bus.releasedBelow
        while (waiting.isNotEmpty() && waiting.first().sequence < released) {
            val batch = waiting.removeFirst()
            // One much bigger than builds lately is let go, so a busy scene once doesn't hold its
            // items for the rest of the session.
            if (free.size < MOST_FREE && batch.items.size <= recent * 1.5 + 64) free.add(batch.emptied())
        }
        current = (if (free.isEmpty()) Batch() else free.removeAt(free.size - 1)).also { it.used = 0 }
    }

    /** An empty list for this build's frame, to publish with it. A new one outside a build. */
    fun list(): ArrayList<RenderItem> = current?.list ?: ArrayList()

    /**
     * An item to fill: set its [RenderItem.position] and [RenderItem.rotation] in place and the rest
     * with [RenderItem.refill]. A new one outside a build.
     */
    fun take(): RenderItem {
        val batch = current ?: return blank()
        val i = batch.used++
        if (i < batch.items.size) return batch.items[i]
        return blank().also { batch.items.add(it) }
    }

    /** This build's frame went out as number [sequence]. */
    fun published(sequence: Long) {
        val batch = current ?: return
        current = null
        batch.sequence = sequence
        recent += (batch.used - recent) / 8.0
        waiting.addLast(batch)
        while (waiting.size > MOST_WAITING) waiting.removeFirst()
    }

    companion object {
        /** Builds waiting on the GL thread. It holds two, and the builder runs a little ahead. */
        const val MOST_WAITING = 8
        /** Enough for the builder running a few builds ahead of the GL thread. */
        private const val MOST_FREE = 3
        private val NOTHING = MeshSpec.Sphere(0.0)
        private val NO_COLOUR = FloatArray(4)

        /** An item to fill, as [take] gives. */
        fun blank() = RenderItem(NOTHING, Vec3(), Quat(), FloatArray(4))
    }
}
