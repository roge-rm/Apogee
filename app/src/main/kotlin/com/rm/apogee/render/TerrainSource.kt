package com.rm.apogee.render

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The hand-off for terrain geometry, game side to GL thread.
 *
 * Building a mesh means sampling the height field thousands of times, far too
 * slow for the GL thread. Builders publish finished work here; the renderer
 * picks it up on its next frame. Lock-free, like the frame bus.
 *
 * Two things cross: the globe, built once, and chunks. A chunk crosses twice -
 * once as data waiting to be uploaded, and then as part of the *draw list*,
 * which the game side republishes whenever what should be on screen changes.
 * The draw list only ever names chunks that have been built, so there is never
 * a hole where one is still being worked on: its coarser parent stands in.
 */
class TerrainSource {

    /** Scatter travels alongside the ground it stands on. */
    val scatter = ScatterSource()

    class PendingGlobe(val revision: Int, val data: PlanetMesh.Data)

    private val globeRef = AtomicReference<PendingGlobe?>(null)

    /** Built, not yet uploaded. The GL thread drains it. */
    private val pendingChunks = ConcurrentHashMap<ChunkKey, ChunkData>()

    /**
     * Chunks built and not since discarded by the GL thread: the ones the
     * builder may put in a draw list. Holds the data until the GPU has it.
     */
    private val available: MutableSet<ChunkKey> = ConcurrentHashMap.newKeySet()

    /**
     * Chunks the GPU actually has. The builder draws only these, so a chunk
     * is never listed before it can be drawn - listed at once, as it used to
     * be, a freshly split square showed sky for the frames its children
     * waited for upload, and coarse neighbours stood around it as slabs.
     */
    private val uploaded: MutableSet<ChunkKey> = ConcurrentHashMap.newKeySet()

    /** Keys in the order they were built, which is nearest first: the upload order. */
    private val uploadOrder = java.util.concurrent.ConcurrentLinkedQueue<ChunkKey>()

    private val drawListRef = AtomicReference<List<DrawEntry>>(emptyList())

    fun publishGlobe(revision: Int, data: PlanetMesh.Data) {
        globeRef.set(PendingGlobe(revision, data))
    }

    fun globe(alreadyUploaded: Int): PendingGlobe? =
        globeRef.get()?.takeIf { it.revision != alreadyUploaded }

    fun publishChunk(data: ChunkData) {
        pendingChunks[data.key] = data
        available += data.key
        uploadOrder.add(data.key)
    }

    fun isAvailable(key: ChunkKey): Boolean = key in available

    fun isUploaded(key: ChunkKey): Boolean = key in uploaded

    /** Built but not yet on the GPU. */
    fun isWaitingForUpload(key: ChunkKey): Boolean = key in available && key !in uploaded

    /** The next built chunk to upload, oldest first, or null. GL thread. */
    fun nextToUpload(): ChunkData? {
        while (true) {
            val key = uploadOrder.poll() ?: return null
            // Gone if it was discarded meanwhile.
            pendingChunks.remove(key)?.let { return it }
        }
    }

    /** Chunks the builder has let go of, for the GL thread to free. */
    private val releaseQueue = java.util.concurrent.ConcurrentLinkedQueue<ChunkKey>()

    /**
     * The builder is done with [key]: it will not list it again unless it
     * builds it afresh. The only way a chunk leaves the GPU short of the
     * context going - the renderer used to evict by its own lights, against
     * whichever draw list it happened to hold, and now and then freed a chunk
     * in the very frame the builder listed it again: the detailed ground
     * gone for a few frames, every few seconds, while climbing.
     */
    fun release(key: ChunkKey) {
        available -= key
        uploaded -= key
        pendingChunks.remove(key)
        releaseQueue.add(key)
    }

    /** The next chunk to free, or null. GL thread; drained before uploads. */
    fun nextReleased(): ChunkKey? = releaseQueue.poll()

    /** The GL thread has [key] on the GPU. */
    fun markUploaded(key: ChunkKey) {
        uploaded += key
    }

    /** The GL thread dropped [key] from the GPU; it must be built again to be drawn. */
    fun discarded(key: ChunkKey) {
        available -= key
        uploaded -= key
        pendingChunks.remove(key)
    }

    /**
     * What to draw, as [ChunkData] (the centre and bounds are what the draw
     * needs; the vertex arrays in it may already be gone to the GPU).
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
