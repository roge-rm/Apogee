package com.rm.apogee.core.orbit

import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Stumpff functions c2 and c3, which let one Kepler solver handle every conic. They're smooth
 * across the parabolic boundary that a craft passes near on the way to escape, so the solver
 * doesn't branch there.
 */
internal object Stumpff {

    /**
     * Below this the exact forms are `0/0` at psi = 0 and cancel badly nearby, so the Taylor series
     * is used.
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
