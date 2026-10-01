package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.abs

/**
 * What damage and heat look like on a part: scorched darker as it's hurt, glowing red to orange to
 * near white as it heats, and squashed along the line it was hit.
 */
object ConditionLook {

    /** Where a part starts to glow, in K. Dull red, barely. */
    const val GLOW_START = 750.0

    /** Where it's as bright as it gets, in K. */
    const val GLOW_FULL = 2_200.0

    /** How far a part fully dented along an axis gets squashed along it. */
    const val DENT = 0.4

    private val char = floatArrayOf(0.10f, 0.09f, 0.08f)

    /** [base] scorched by [health] and lit by [temperature], as a new array. */
    fun colour(base: FloatArray, health: Float, temperature: Float): FloatArray {
        val out = base.copyOf()
        val scorch = (1f - health).coerceIn(0f, 1f) * 0.8f
        for (c in 0..2) out[c] += (char[c] - out[c]) * scorch
        val glow = glow(temperature)
        if (glow > 0f) {
            val hot = heatColour(glow)
            val mix = 0.25f + 0.65f * glow
            for (c in 0..2) out[c] += (hot[c] - out[c]) * mix
        }
        return out
    }

    /** How much it lights itself: a part's usual ambient, rising to a glow. */
    fun ambient(base: Float, temperature: Float): Float {
        val glow = glow(temperature)
        return base + (1.1f - base) * glow
    }

    /** 0 below [GLOW_START] and 1 at [GLOW_FULL], eased. */
    fun glow(temperature: Float): Float {
        val t = ((temperature - GLOW_START) / (GLOW_FULL - GLOW_START)).toFloat().coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Dull red to orange to yellow-white along [glow]. */
    private fun heatColour(glow: Float): FloatArray = when {
        glow < 0.5f -> {
            val k = glow / 0.5f
            floatArrayOf(0.55f + 0.45f * k, 0.06f + 0.36f * k, 0.02f + 0.06f * k)
        }
        else -> {
            val k = (glow - 0.5f) / 0.5f
            floatArrayOf(1f, 0.42f + 0.48f * k, 0.08f + 0.62f * k)
        }
    }

    /**
     * The squash of a part dented by [crumple] (its three axes, from [offset] in the array), as a
     * scale in its own axes, or null for none.
     */
    fun dent(crumple: FloatArray, offset: Int): Vec3? {
        val x = abs(crumple[offset]); val y = abs(crumple[offset + 1]); val z = abs(crumple[offset + 2])
        if (x + y + z < 0.02f) return null
        return Vec3(squash(x), squash(y), squash(z))
    }

    /** [partScale] as seen in a leaf turned by [leafRotation] within the part. */
    fun inLeaf(partScale: Vec3, leafRotation: Quat): Vec3 {
        val ax = leafRotation.rotate(Vec3(1.0, 0.0, 0.0))
        val ay = leafRotation.rotate(Vec3(0.0, 1.0, 0.0))
        val az = leafRotation.rotate(Vec3(0.0, 0.0, 1.0))
        fun along(axis: Vec3) = abs(axis.x) * partScale.x + abs(axis.y) * partScale.y + abs(axis.z) * partScale.z
        return Vec3(along(ax), along(ay), along(az))
    }

    private fun squash(amount: Float): Double = (1.0 - DENT * amount).coerceAtLeast(0.5)
}
