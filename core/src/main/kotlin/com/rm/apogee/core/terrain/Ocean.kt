package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3

/**
 * The sea: where its surface is, and what it is made of.
 *
 * The one definition of the water surface, in the same sense that
 * [TerrainField] is the one definition of the ground. Buoyancy samples it at
 * every submerged point of every hull, and when there are waves the renderer
 * will build the sea's geometry by sampling it too - so a craft can never
 * float on water that is not where it is drawn. Waves are therefore a change
 * to [surfaceHeight] and nowhere else, and they must stay a function of
 * position, time and seed, deterministic on every machine, for the same
 * reason terrain is: the server and every client evaluate it independently.
 *
 * Calm for now. The surface is the datum.
 */
class Ocean(
    /** Kilograms per cubic metre. Sea water, not fresh. */
    val density: Double = 1_025.0,
) {
    /**
     * Height of the water surface above the datum, metres, at a
     * **body-fixed** direction and a world time. See
     * [com.rm.apogee.core.orbit.CelestialBody.surfaceRadiusInBodyFrame] for
     * why body-fixed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun surfaceHeight(bodyFixedDirection: Vec3, time: Double): Double = 0.0
}
