package com.rm.apogee.render

import com.rm.apogee.platform.AtomicReference

/**
 * The lock-free hand-off for terrain geometry, from the builders to the GL thread.
 *
 * The globe crosses once. A chunk crosses twice: as data to upload, then in the draw list, which
 * only names chunks already built, so a coarser parent stands in until children are ready.
 */
class TerrainSource {

    /** Scatter travels alongside the ground it stands on. */
    val scatter = ScatterSource()

    class PendingGlobe(val revision: Int, val data: PlanetMesh.Data)

    private val globeRef = AtomicReference<PendingGlobe?>(null)

    /** Built, not uploaded yet. The GL thread drains it. */
    private val pendingChunks = com.rm.apogee.core.concurrentMapOf<ChunkKey, ChunkData>()

    /** Chunks built and not since thrown away by the GL thread. */
    private val available: MutableSet<ChunkKey> = com.rm.apogee.core.concurrentSetOf()

    /** Chunks the GPU has. The builder only lists these, so nothing is listed before it can be drawn. */
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
     * The builder is done with [key] and won't list it again unless it builds it afresh. The only
     * way a chunk leaves the GPU, short of losing the context; the renderer never evicts on its own.
     */
    fun release(key: ChunkKey) {
        changes.incrementAndGet()
        available -= key
        uploaded -= key
        pendingChunks.remove(key)
        releaseQueue.add(key)
    }

    /** Lets go of every chunk, for a new builder starting over, which knows nothing of the old one's. */
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

    /** What to draw. Only the centre and bounds are used; the vertices may already be on the GPU. */
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
