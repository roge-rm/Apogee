package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.sea.SeaSample
import kotlin.concurrent.Volatile

/**
 * The sea: where its surface is and its density. The one definition of the water surface, sampled
 * by buoyancy and the renderer alike, and the same on every machine. Tides and waves come from
 * [sea]; without it the sea lies flat at the datum.
 */
class Ocean(
    /** Kilograms per cubic metre. Sea water, not fresh. */
    val density: Double = 1_025.0,
) {
    /** Tides and waves, or null for a flat sea at the datum. */
    @Volatile var sea: Sea? = null

    /**
     * The height of the water surface above the datum, in metres, at a body-fixed position (any
     * length; only direction counts) and world time. See
     * [com.rm.apogee.core.orbit.CelestialBody.surfaceRadiusInBodyFrame] for why body-fixed.
     */
    fun surfaceHeight(bodyFixed: Vec3, time: Double): Double = sea?.height(bodyFixed, time) ?: 0.0

    /**
     * The waves over a craft at body-fixed [bodyFixed] at [time], into [out]. See
     * [com.rm.apogee.core.sea.WavePatch].
     */
    fun patch(bodyFixed: Vec3, time: Double, out: com.rm.apogee.core.sea.WavePatch): com.rm.apogee.core.sea.WavePatch {
        val s = sea
        if (s != null) return s.patch(bodyFixed, time, out)
        sample(bodyFixed, time, out.middle)
        out.middle.depth = 1.0
        out.n = 0
        out.time = time
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
