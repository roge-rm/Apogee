package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.sea.SeaSample

/**
 * The sea: where its surface is, and what it is made of.
 *
 * The one definition of the water surface, in the same sense that
 * [TerrainField] is the one definition of the ground. Buoyancy samples it at
 * every submerged point of every hull, and the renderer builds the sea's
 * geometry by sampling it too - so a craft can never float on water that is
 * not where it is drawn. A function of position, time and seed,
 * deterministic on every machine, for the same reason terrain is: the
 * server and every client evaluate it independently.
 *
 * Its tides and waves are its [sea], bound by the world that owns it to
 * that world's weather and moon; unbound, it lies flat at the datum.
 */
class Ocean(
    /** Kilograms per cubic metre. Sea water, not fresh. */
    val density: Double = 1_025.0,
) {
    /** Tides and waves; null for a flat sea at the datum. */
    @Volatile var sea: Sea? = null

    /**
     * Height of the water surface above the datum, metres, at a
     * **body-fixed** position (any length - only its direction counts) and
     * a world time. See
     * [com.rm.apogee.core.orbit.CelestialBody.surfaceRadiusInBodyFrame] for
     * why body-fixed.
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
     * Everything about the sea at body-fixed [bodyFixed] and [time], into
     * [out], with the water's motion [below] metres down. See [Sea.sample].
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
