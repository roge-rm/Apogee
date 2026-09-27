package com.rm.apogee.core.orbit

import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Stumpff functions c2 and c3, the machinery that lets one Kepler solver handle every conic
 * section.
 *
 * The classical approach needs a different equation for ellipses (`M = E - e sin E`), parabolas
 * (Barker's) and hyperbolas (`M = e sinh H - H`). Picking between them means a branch exactly where
 * orbits actually live, because a craft raising its apoapsis passes close to parabolic on the way
 * to escape. These functions are smooth across that boundary, so the solver is too.
 */
internal object Stumpff {

    /**
     * Below this the exact forms lose precision badly. Both are `0/0` at psi = 0, and the
     * subtraction in the numerator cancels almost completely just either side of it, so the Taylor
     * series is used instead.
     */
    private const val SERIES_THRESHOLD = 1e-6

    fun c2(psi: Double): Double = when {
        psi > SERIES_THRESHOLD -> {
            val s = sqrt(psi)
            (1.0 - cos(s)) / psi
        }
        psi < -SERIES_THRESHOLD -> {
            val s = sqrt(-psi)
            (cosh(s) - 1.0) / -psi
        }
        // 1/2! - psi/4! + psi^2/6! - ...
        else -> 0.5 - psi / 24.0 + psi * psi / 720.0
    }

    fun c3(psi: Double): Double = when {
        psi > SERIES_THRESHOLD -> {
            val s = sqrt(psi)
            (s - sin(s)) / (psi * s)
        }
        psi < -SERIES_THRESHOLD -> {
            val s = sqrt(-psi)
            (sinh(s) - s) / (-psi * s)
        }
        // 1/3! - psi/5! + psi^2/7! - ...
        else -> 1.0 / 6.0 - psi / 120.0 + psi * psi / 5040.0
    }
}
