package com.rm.apogee.render

import kotlin.math.floor

/** Samples the same two triangles per cell as the ground and water meshes. */
internal object GridMorph {
    fun sample(source: FloatArray, side: Int, stride: Int, x: Double, y: Double, out: FloatArray, into: Int, base: Int = 0) {
        val i = floor(x).toInt().coerceIn(0, side - 2)
        val j = floor(y).toInt().coerceIn(0, side - 2)
        val u = (x - i).toFloat(); val v = (y - j).toFloat()
        val a: Int; val b: Int; val c: Int
        val wa: Float; val wb: Float; val wc: Float
        if (u + v <= 1f) {
            a = j * side + i; b = a + 1; c = a + side
            wa = 1f - u - v; wb = u; wc = v
        } else {
            a = (j + 1) * side + i + 1; b = a - 1; c = a - side
            wa = u + v - 1f; wb = 1f - u; wc = 1f - v
        }
        for (n in 0 until stride) {
            out[into + n] = source[base + a * stride + n] * wa + source[base + b * stride + n] * wb + source[base + c * stride + n] * wc
        }
    }

    fun smooth(from: Double, to: Double, value: Double): Double {
        val t = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
