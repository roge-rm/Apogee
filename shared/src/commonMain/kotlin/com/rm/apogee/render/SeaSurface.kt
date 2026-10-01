package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3

/**
 * The sea round the camera as last built: nested square grids fixed on the ground, finest near the
 * camera, sampled from the same wave function the physics floats boats on. Built off the frame
 * thread at [time]; the shader carries each vertex on by its rate of rise to draw time, so water
 * and boats agree.
 */
class SeaSurface(
    /** What the vertices are measured from: a point on the datum, body-fixed, in metres. */
    val origin: Vec3,
    /** [STRIDE] floats per vertex: position from [origin] (body-fixed), unit up, colour, rise rate. */
    val vertices: FloatArray,
    val vertexCount: Int,
    /** The grids' triangles. They're the same for every surface with the same [layout]. */
    val indices: IntArray,
    val layout: Int,
    /** The universe time it was built for. */
    val time: Double,
    /** Where [vertices] goes back for reuse once a newer surface replaces it on the GPU, or null. */
    val giveBack: ((FloatArray) -> Unit)? = null,
) {
    companion object {
        /** Position(3), up(3), colour(4), rise(1). */
        const val STRIDE = 11
    }
}
