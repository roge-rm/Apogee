package com.rm.apogee.core.sea

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.ulp
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class DetMathTest {

    private fun worstUlps(from: Double, to: Double, steps: Int, det: (Double) -> Double, ref: (Double) -> Double): Double {
        var worst = 0.0
        for (i in 0..steps) {
            val x = from + (to - from) * i / steps
            val r = ref(x)
            worst = max(worst, abs(det(x) - r) / max(r.ulp, Double.MIN_VALUE))
        }
        return worst
    }

    @Test
    fun lnIsWithinAFewUlp() {
        assertTrue(worstUlps(1e-6, 10.0, 200_000, DetMath::ln, { kotlin.math.ln(it) }) <= 4.0)
        assertTrue(worstUlps(10.0, 1e9, 200_000, DetMath::ln, { kotlin.math.ln(it) }) <= 4.0)
        assertEquals(0.0, DetMath.ln(1.0), 0.0)
        assertEquals(kotlin.math.ln(1e-310), DetMath.ln(1e-310), 1e-13)
        assertEquals(Double.NEGATIVE_INFINITY, DetMath.ln(0.0), 0.0)
        assertTrue(DetMath.ln(-1.0).isNaN())
    }

    @Test
    fun arcSineAndCosineAreWithinAFewUlp() {
        assertTrue(worstUlps(-1.0, 1.0, 200_000, DetMath::asin, { kotlin.math.asin(it) }) <= 2.0)
        assertTrue(worstUlps(-1.0, 1.0, 200_000, DetMath::acos, { kotlin.math.acos(it) }) <= 2.0)
        assertEquals(kotlin.math.PI / 2.0, DetMath.asin(1.5), 0.0)
    }
}
