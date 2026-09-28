package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.sea.SeaSample
import kotlin.concurrent.Volatile

/**
 * The sea: where its surface is, and what it's made of.
 *
 * This is the one definition of the water surface, the same way [TerrainField] is the one
 * definition of the ground. Buoyancy samples it at every point under water on every hull, and the
 * renderer builds the sea's shape by sampling it too, so a craft can never float on water that
 * isn't where it's drawn. It's a function of position, time and seed that gives the same answer on
 * every machine, for the same reason terrain does: the server and every client work it out on their
 * own.
 *
 * Its tides and waves are its [sea], tied by the world that owns it to that world's weather and
 * moon. Without that, it lies flat at the datum.
 */
class Ocean(
    /** Kilograms per cubic metre. Sea water, not fresh. */
    val density: Double = 1_025.0,
) {
    /** Tides and waves, or null for a flat sea at the datum. */
    @Volatile var sea: Sea? = null

    /**
     * The height of the water surface above the datum, in metres, at a **body-fixed** position (any
     * length, since only its direction counts) and a world time. See
     * [com.rm.apogee.core.orbit.CelestialBody.surfaceRadiusInBodyFrame] for why it's body-fixed.
     */
    fun surfaceHeight(bodyFixed: Vec3, time: Double): Double = sea?.height(bodyFixed, time) ?: 0.0

    /** The waves over a craft at body-fixed [bodyFixed] at [time], into [out]. See [com.rm.apogee.core.sea.WavePatch]. */
    fun patch(bodyFixed: Vec3, time: Double, out: com.rm.apogee.core.sea.WavePatch): com.rm.apogee.core.sea.WavePatch {
        val s = sea
        if (s != null) return s.patch(bodyFixed, time, out)
        sample(bodyFixed, time, out.middle)
        out.middle.depth = 1.0
        out.n = 0
        return out
    }

    /**
     * Everything about the sea at body-fixed [bodyFixed] and [time], into [out], with the water's
     * motion [below] metres down. See [Sea.sample].
     */
    fun sample(bodyFixed: Vec3, time: Double, out: SeaSample, below: Double = 0.0, spacing: Double = 0.0): SeaSample {
        val s = sea
        if (s != null) return s.sample(bodyFixed, time, out, below, spacing)
        out.height = 0.0; out.tide = 0.0
        out.normal.setTo(bodyFixed).normalizeInPlace()
        out.velocity.setZero()
        out.steepness = 0.0; out.breaking = 0.0; out.depth = 0.0
        out.significantHeight = 0.0; out.wind = 0.0; out.stormHeight = 0.0
        return out
    }
}
