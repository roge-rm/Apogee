package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import java.util.concurrent.atomic.AtomicReference

/**
 * The hand-off for terrain geometry, game thread to GL thread.
 *
 * Building a mesh means sampling the height field tens of thousands of times,
 * which is far too slow to do on the GL thread - a frame would be dropped
 * every time the craft moved far enough to need a new patch. The producer
 * builds off-thread and swaps the finished mesh in here; the renderer picks it
 * up on its next frame.
 *
 * Lock-free, like the frame bus: one atomic swap in, one read out. Each mesh
 * carries a revision so the renderer can tell a new one from the one it has
 * already uploaded, without comparing arrays.
 */
class TerrainSource {

    class Pending(
        val revision: Int,
        val data: PlanetMesh.Data,
        /** Patch centre in the body-fixed frame; unused for the globe. */
        val centre: Vec3,
    )

    private val globeRef = AtomicReference<Pending?>(null)
    private val patchRef = AtomicReference<Pending?>(null)

    fun publishGlobe(revision: Int, data: PlanetMesh.Data) {
        globeRef.set(Pending(revision, data, Vec3.zero()))
    }

    fun publishPatch(revision: Int, data: PlanetMesh.Data, centre: Vec3) {
        patchRef.set(Pending(revision, data, centre.copy()))
    }

    /** The pending globe, if it is newer than [alreadyUploaded]. */
    fun globe(alreadyUploaded: Int): Pending? =
        globeRef.get()?.takeIf { it.revision != alreadyUploaded }

    fun patch(alreadyUploaded: Int): Pending? =
        patchRef.get()?.takeIf { it.revision != alreadyUploaded }

    fun clear() {
        globeRef.set(null)
        patchRef.set(null)
    }
}
