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
    /** 0 while no one has it, 1 once the GPU has taken it, 2 once handed back without being drawn. */
    private var held = 0

    /** For the GPU: whether it may take this one. False if it was handed back unused. */
    fun claim(): Boolean = com.rm.apogee.platform.synchronized(this) {
        if (held == 0) held = 1
        held == 1
    }

    /**
     * For the builder, once a newer one replaces it: whether the GPU never took it, so its
     * vertices can be built into again. The GPU won't take it after this returns true.
     */
    fun drop(): Boolean = com.rm.apogee.platform.synchronized(this) {
        if (held == 0) held = 2
        held == 2
    }

    companion object {
        /** Position(3), up(3), colour(4), rise(1). */
        const val STRIDE = 11
    }
}
