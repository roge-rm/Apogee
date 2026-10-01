package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3

/**
 * The sea around the camera as it was last built: square grids fixed on the ground and nested one
 * inside the other, finest around the camera, with each vertex sampled from the same wave function
 * the physics floats boats on.
 *
 * It's built off the frame thread at [time]. The shader carries each vertex on by its rate of rise
 * to the moment it's drawn, a few hundredths of a second, so the water and the boats on it agree to
 * the millimetre.
 */
class SeaSurface(
    /** What the vertices are measured from: a point on the datum, body-fixed, in metres. */
    val origin: Vec3,
    /**
     * [STRIDE] floats per vertex: position from [origin] (body-fixed), up (unit), colour and
     * opacity, and rate of rise.
     */
    val vertices: FloatArray,
    val vertexCount: Int,
    /** The grids' triangles. They're the same for every surface with the same [layout]. */
    val indices: IntArray,
    val layout: Int,
    /** The universe time it was built for. */
    val time: Double,
    /**
     * Where [vertices] goes back to be used again once it's on the GPU and a newer surface has
     * taken its place. Null for nowhere.
     */
    val giveBack: ((FloatArray) -> Unit)? = null,
) {
    companion object {
        /** Position(3), up(3), colour(4), rise(1). */
        const val STRIDE = 11
    }
}
