package com.rm.apogee.render

import com.rm.apogee.platform.AtomicReference

/**
 * The hand-off for terrain geometry, from the game side to the GL thread.
 *
 * Building a mesh means sampling the height field thousands of times, which is far too slow for the
 * GL thread. Builders publish finished work here, and the renderer picks it up on its next frame.
 * It's lock-free, like the frame bus.
 *
 * Two things cross: the globe, built once, and chunks. A chunk crosses twice. First as data waiting
 * to be uploaded, and then as part of the *draw list*, which the game side publishes again whenever
 * what should be on screen changes. The draw list only ever names chunks that have been built, so
 * there's never a hole where one is still being worked on. Its coarser parent stands in.
 */
class TerrainSource {

    /** Scatter travels alongside the ground it stands on. */
    val scatter = ScatterSource()

    class PendingGlobe(val revision: Int, val data: PlanetMesh.Data)

    private val globeRef = AtomicReference<PendingGlobe?>(null)

    /** Built, not uploaded yet. The GL thread drains it. */
    private val pendingChunks = com.rm.apogee.core.concurrentMapOf<ChunkKey, ChunkData>()

    /**
     * Chunks built and not thrown away since by the GL thread, which are the ones the builder can
     * put in a draw list. It holds the data until the GPU has it.
     */
    private val available: MutableSet<ChunkKey> = com.rm.apogee.core.concurrentSetOf()

    /**
     * Chunks the GPU actually has. The builder only draws these, so a chunk is never listed before
     * it can be drawn. When chunks were listed straight away, as they used to be, a freshly split
     * square showed sky for the frames its children waited for upload, and coarse neighbours stood
     * around it like slabs.
     */
    private val uploaded: MutableSet<ChunkKey> = com.rm.apogee.core.concurrentSetOf()

    /** Keys in the order they were built, which is nearest first: the upload order. */
    private val uploadOrder = com.rm.apogee.core.ConcurrentQueue<ChunkKey>()

    private val drawListRef = AtomicReference<List<DrawEntry>>(emptyList())

    fun publishGlobe(revision: Int, data: PlanetMesh.Data) {
        globeRef.set(PendingGlobe(revision, data))
    }

    fun globe(alreadyUploaded: Int): PendingGlobe? =
        globeRef.get()?.takeIf { it.revision != alreadyUploaded }

    /** Counts every chunk built, uploaded, let go or lost, so the builder can tell when nothing has. */
    val changes = com.rm.apogee.platform.AtomicInteger()

    fun publishChunk(data: ChunkData) {
        changes.incrementAndGet()
        pendingChunks[data.key] = data
        available += data.key
        uploadOrder.add(data.key)
    }

    fun isAvailable(key: ChunkKey): Boolean = key in available

    fun isUploaded(key: ChunkKey): Boolean = key in uploaded

    /** Built but not on the GPU yet. */
    fun isWaitingForUpload(key: ChunkKey): Boolean = key in available && key !in uploaded

    /** The next built chunk to upload, oldest first, or null. GL thread. */
    fun nextToUpload(): ChunkData? {
        while (true) {
            val key = uploadOrder.poll() ?: return null
            // Gone if it was thrown away in the meantime.
            pendingChunks.remove(key)?.let { return it }
        }
    }

    /** Chunks the builder has let go of, for the GL thread to free. */
    private val releaseQueue = com.rm.apogee.core.ConcurrentQueue<ChunkKey>()

    /**
     * The builder is done with [key], and won't list it again unless it builds it afresh. This is
     * the only way a chunk leaves the GPU, short of losing the context. The renderer used to evict
     * on its own judgement, against whichever draw list it happened to hold, and now and then freed
     * a chunk in the very frame the builder listed it again. The detailed ground vanished for a few
     * frames, every few seconds, while climbing.
     */
    fun release(key: ChunkKey) {
        changes.incrementAndGet()
        available -= key
        uploaded -= key
        pendingChunks.remove(key)
        releaseQueue.add(key)
    }

    /**
     * Lets go of every chunk, for a new builder starting over with this source. The new one knows
     * nothing about anything the old one left here, and would wait on it without ever building it.
     */
    fun releaseAllChunks() {
        for (key in available.toList() + uploaded.toList()) release(key)
        uploadOrder.clear()
        drawListRef.set(emptyList())
    }

    /** The next chunk to free, or null. GL thread. Drained before uploads. */
    fun nextReleased(): ChunkKey? = releaseQueue.poll()

    /** The GL thread has [key] on the GPU. */
    fun markUploaded(key: ChunkKey) {
        changes.incrementAndGet()
        uploaded += key
    }

    /** The GL thread dropped [key] from the GPU, so it has to be built again to be drawn. */
    fun discarded(key: ChunkKey) {
        changes.incrementAndGet()
        available -= key
        uploaded -= key
        pendingChunks.remove(key)
    }

    /**
     * What to draw, as [ChunkData]. The centre and bounds are what the draw needs, and the vertex
     * arrays in it might already have gone to the GPU.
     */
    fun publishDrawList(list: List<DrawEntry>) = drawListRef.set(list)

    fun drawList(): List<DrawEntry> = drawListRef.get()

    fun clear() {
        globeRef.set(null)
        pendingChunks.clear()
        available.clear()
        uploaded.clear()
        uploadOrder.clear()
        releaseQueue.clear()
        drawListRef.set(emptyList())
    }
}
