package com.rm.apogee.core.sea

import com.rm.apogee.core.math.Math
import com.rm.apogee.core.math.StrictMath

/**
 * Sine, cosine and exp that give the same bits on every machine, since each machine works out the
 * sea itself and wave sums must agree exactly. Range reduction and a fixed polynomial using only
 * IEEE add and multiply. [StrictMath] is too slow for tens of thousands of terms a frame.
 * Weather uses it too, as wind, storms and ash feed physics on server and replica alike.
 */
internal object DetMath {

    private const val PI = 3.141592653589793
    private const val HALF_PI = 1.5707963267948966
    private const val TWO_PI_HI = 6.283185307179586
    private const val TWO_PI_LO = 2.4492935982947064E-16
    private const val INV_TWO_PI = 0.15915494309189535

    private const val LN2_HI = 0.6931471803691238
    private const val LN2_LO = 1.9082149292705877E-10
    private const val INV_LN2 = 1.4426950408889634

    fun sin(x: Double): Double {
        val k = Math.floor(x * INV_TWO_PI + 0.5)
        var r = (x - k * TWO_PI_HI) - k * TWO_PI_LO
        if (r > HALF_PI) r = PI - r else if (r < -HALF_PI) r = -PI - r
        val r2 = r * r
        return r * (1.0 + r2 * (S3 + r2 * (S5 + r2 * (S7 + r2 * (S9 + r2 * (S11 + r2 * (S13 + r2 * S15)))))))
    }

    fun cos(x: Double): Double = sin(x + HALF_PI)

    /** The sine and cosine of [x] together into [out], with one range reduction for both. */
    fun sinCos(x: Double, out: SinCos) {
        val k = Math.floor(x * INV_TWO_PI + 0.5)
        val r = (x - k * TWO_PI_HI) - k * TWO_PI_LO   // -pi..pi
        // Fold into -pi/2..pi/2 for the sine. The cosine flips sign with it.
        var a = r
        var sign = 1.0
        if (a > HALF_PI) { a = PI - a; sign = -1.0 } else if (a < -HALF_PI) { a = -PI - a; sign = -1.0 }
        val a2 = a * a
        out.sin = a * (1.0 + a2 * (S3 + a2 * (S5 + a2 * (S7 + a2 * (S9 + a2 * (S11 + a2 * (S13 + a2 * S15)))))))
        out.cos = sign * (1.0 + a2 * (C2 + a2 * (C4 + a2 * (C6 + a2 * (C8 + a2 * (C10 + a2 * (C12 + a2 * (C14 + a2 * C16))))))))
    }

    class SinCos { var sin = 0.0; var cos = 1.0 }

    private const val C2 = -1.0 / 2.0
    private const val C4 = 1.0 / 24.0
    private const val C6 = -1.0 / 720.0
    private const val C8 = 1.0 / 40_320.0
    private const val C10 = -1.0 / 3_628_800.0
    private const val C12 = 1.0 / 479_001_600.0
    private const val C14 = -1.0 / 87_178_291_200.0
    private const val C16 = 1.0 / 20_922_789_888_000.0

    /** e^x, to about 1e-15 relative. */
    fun exp(x: Double): Double {
        if (x < -700.0) return 0.0
        if (x > 700.0) return Double.MAX_VALUE
        val n = Math.floor(x * INV_LN2 + 0.5)
        val r = (x - n * LN2_HI) - n * LN2_LO
        val p = 1.0 + r * (1.0 + r * (E2 + r * (E3 + r * (E4 + r * (E5 + r * (E6 + r * (E7 + r * (E8 + r * (E9 + r * (E10 + r * (E11 + r * E12)))))))))))
        // Times 2^n built from its bits, so it's exact. n is in the normal range here.
        return p * Double.fromBits((n.toLong() + 1023L) shl 52)
    }

    /** Hyperbolic tangent, from [exp]. */
    fun tanh(x: Double): Double {
        if (x > 20.0) return 1.0
        if (x < -20.0) return -1.0
        val e = exp(2.0 * x)
        return (e - 1.0) / (e + 1.0)
    }

    /** Natural log, within 4 ulp. Splits off the power of two from the bits, then a series in (m - 1) / (m + 1). */
    fun ln(x: Double): Double {
        if (x.isNaN() || x < 0.0) return Double.NaN
        if (x == 0.0) return Double.NEGATIVE_INFINITY
        if (x == Double.POSITIVE_INFINITY) return x
        val bits = x.toRawBits()
        var e = ((bits ushr 52) and 0x7FFL).toInt()
        if (e == 0) return ln(x * TWO_54) - 54.0 * LN2_HI - 54.0 * LN2_LO   // subnormal
        e -= 1023
        var m = Double.fromBits((bits and 0x000F_FFFF_FFFF_FFFFL) or 0x3FF0_0000_0000_0000L)   // 1..2
        if (m > SQRT2) { m *= 0.5; e += 1 }   // 0.71..1.41, so |s| is under 0.172
        val s = (m - 1.0) / (m + 1.0)
        val s2 = s * s
        val series = s * (2.0 + s2 * (L3 + s2 * (L5 + s2 * (L7 + s2 * (L9 + s2 * (L11 + s2 * (L13 + s2 * (L15 + s2 * (L17 + s2 * (L19 + s2 * L21))))))))))
        return e * LN2_HI + (series + e * LN2_LO)
    }

    /** Arc tangent of y / x in -pi..pi, from [StrictMath], which is the same on every JVM and Android. */
    fun atan2(y: Double, x: Double): Double = StrictMath.atan2(y, x)

    /** Arc sine, from [atan2], within 2 ulp. [x] is clamped to -1..1. */
    fun asin(x: Double): Double {
        val c = x.coerceIn(-1.0, 1.0)
        return StrictMath.atan2(c, kotlin.math.sqrt((1.0 - c) * (1.0 + c)))
    }

    /** Arc cosine, from [atan2], within 2 ulp. [x] is clamped to -1..1. */
    fun acos(x: Double): Double {
        val c = x.coerceIn(-1.0, 1.0)
        return StrictMath.atan2(kotlin.math.sqrt((1.0 - c) * (1.0 + c)), c)
    }

    /** Length of (x, y). Plain IEEE square root, so exact to the bit everywhere; no overflow guard. */
    fun hypot(x: Double, y: Double): Double = kotlin.math.sqrt(x * x + y * y)

    private const val TWO_54 = 18_014_398_509_481_984.0
    private const val SQRT2 = 1.4142135623730951
    private const val L3 = 2.0 / 3.0
    private const val L5 = 2.0 / 5.0
    private const val L7 = 2.0 / 7.0
    private const val L9 = 2.0 / 9.0
    private const val L11 = 2.0 / 11.0
    private const val L13 = 2.0 / 13.0
    private const val L15 = 2.0 / 15.0
    private const val L17 = 2.0 / 17.0
    private const val L19 = 2.0 / 19.0
    private const val L21 = 2.0 / 21.0

    private const val S3 = -1.0 / 6.0
    private const val S5 = 1.0 / 120.0
    private const val S7 = -1.0 / 5_040.0
    private const val S9 = 1.0 / 362_880.0
    private const val S11 = -1.0 / 39_916_800.0
    private const val S13 = 1.0 / 6_227_020_800.0
    private const val S15 = -1.0 / 1_307_674_368_000.0

    private const val E2 = 1.0 / 2.0
    private const val E3 = 1.0 / 6.0
    private const val E4 = 1.0 / 24.0
    private const val E5 = 1.0 / 120.0
    private const val E6 = 1.0 / 720.0
    private const val E7 = 1.0 / 5_040.0
    private const val E8 = 1.0 / 40_320.0
    private const val E9 = 1.0 / 362_880.0
    private const val E10 = 1.0 / 3_628_800.0
    private const val E11 = 1.0 / 39_916_800.0
    private const val E12 = 1.0 / 479_001_600.0
}
