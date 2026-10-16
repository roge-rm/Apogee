package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3

/**
 * A tsunami's sea round the camera, for the flat water past the waves: a square of [SIDE] by
 * [SIDE] points [spacing] metres apart along [east] and [north] from unit, body-fixed [centre].
 * Each holds how far the tsunami moves the sea there, in metres, and how high it is in deep water
 * there, packed as [pack] has it, and in [tau] the seconds since it arrived there by its coarse
 * arrival times, for drawing its crest per pixel. Land points hold the sea as if it ran over them,
 * so the water by the coast reads smoothly.
 */
class TsunamiField(
    val centre: Vec3,
    val east: Vec3,
    val north: Vec3,
    val spacing: Double,
    val values: FloatArray,
    /** How far the tsunami moves the sea at the shore nearest [centre], in metres. */
    val shore: Double,
    /** Seconds since it arrived at each point as of [time], or a long time before it for never. */
    val tau: FloatArray = FloatArray(VEC4S * 4),
    /** The world time it was worked out for. */
    val time: Double = 0.0,
    /** Seconds between its crests. */
    val period: Double = 0.0,
    /** The most it reaches on a coast, in metres. */
    val coastCap: Double = 0.0,
    /** The shelf's delay in seconds per (sqrt 6 - sqrt depth), as Tsunamis has it. */
    val shoalScale: Double = 0.0,
) {
    /** The height and deep-water height at point ([i], [j]), as [unpackHeight] and [unpackDeep] read them. */
    fun height(i: Int, j: Int): Double = unpackHeight(values[j * SIDE + i])
    fun deep(i: Int, j: Int): Double = unpackDeep(values[j * SIDE + i])

    companion object {
        /** Points along each side; odd, so one sits on the centre. */
        const val SIDE = 17

        /** Uniform vec4s the shader takes them in. */
        const val VEC4S = (SIDE * SIDE + 3) / 4

        /** The deep-water height packed as a share of this, in metres. */
        const val DEEP_SCALE = 4.0

        /** [height] in whole centimetres, and [deep] over [DEEP_SCALE] in what's left over, under 1. */
        fun pack(height: Double, deep: Double): Float =
            (kotlin.math.round(height.coerceIn(-300.0, 300.0) * 100.0) + (deep / DEEP_SCALE).coerceIn(0.0, 0.99)).toFloat()

        fun unpackHeight(v: Float): Double = kotlin.math.floor(v.toDouble()) / 100.0
        fun unpackDeep(v: Float): Double = (v.toDouble() - kotlin.math.floor(v.toDouble())) * DEEP_SCALE
    }
}
