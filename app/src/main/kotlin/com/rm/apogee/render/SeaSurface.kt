package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3

/**
 * The sea round the camera as last built: a disc of rings about a centre,
 * finest in the middle, each vertex sampled from the one wave function the
 * physics floats boats on.
 *
 * Built off the frame thread at [time]; the shader carries each vertex on
 * by its rate of rise to the moment it is drawn, a few hundredths of a
 * second, so the water and the boats on it agree to the millimetre.
 */
class SeaSurface(
    /** Where the rings are laid out from: a point on the datum, body-fixed, m. */
    val origin: Vec3,
    /** Per vertex [STRIDE] floats: position from [origin] (body-fixed), up (unit), colour and opacity, rate of rise. */
    val vertices: FloatArray,
    val vertexCount: Int,
    /** The rings' triangles: the same for every surface with the same [layout]. */
    val indices: IntArray,
    val layout: Int,
    /** Universe time it was built for. */
    val time: Double,
) {
    companion object {
        /** Position(3), up(3), colour(4), rise(1). */
        const val STRIDE = 11
    }
}
