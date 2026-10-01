package com.rm.apogee.core.sea

import com.rm.apogee.core.math.Math
import com.rm.apogee.core.math.StrictMath

/**
 * Sine, cosine and exp that give the same bits on every machine, since each machine works out the
 * sea itself and wave sums must agree exactly. Range reduction and a fixed polynomial using only
 * IEEE add and multiply. [StrictMath] is too slow for tens of thousands of terms a frame.
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
