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

    private val drawListRef = AtomicReference<List<ChunkData>>(emptyList())

    fun publishGlobe(revision: Int, data: PlanetMesh.Data) {
        globeRef.set(PendingGlobe(revision, data))
    }

    fun globe(alreadyUploaded: Int): PendingGlobe? =
        globeRef.get()?.takeIf { it.revision != alreadyUploaded }

    fun publishChunk(data: ChunkData) {
        pendingChunks[data.key] = data
        available += data.key
    }

    fun isAvailable(key: ChunkKey): Boolean = key in available

    /** Built chunks the GL thread has not uploaded yet, removed as they are taken. */
    fun takePending(key: ChunkKey): ChunkData? = pendingChunks.remove(key)

    /** The GL thread dropped [key] from the GPU; it must be built again to be drawn. */
    fun discarded(key: ChunkKey) {
        available -= key
        pendingChunks.remove(key)
    }

    /**
     * What to draw, as [ChunkData] (the centre and bounds are what the draw
     * needs; the vertex arrays in it may already be gone to the GPU).
     */
    fun publishDrawList(list: List<ChunkData>) = drawListRef.set(list)

    fun drawList(): List<ChunkData> = drawListRef.get()

    fun clear() {
        globeRef.set(null)
        pendingChunks.clear()
        available.clear()
        drawListRef.set(emptyList())
    }
}
