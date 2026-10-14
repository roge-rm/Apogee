package com.rm.apogee.core.terrain

import kotlin.math.abs
import kotlin.math.sqrt

/** Generation-10 cap heights, only for migrating its parked craft. */
internal object ReviewIce {
    fun height(seed: Int, radius: Double, nx: Double, ny: Double, nz: Double, ground: Double): Double {
        val radial = sqrt(nx * nx + nz * nz) * radius
        if (ny < -0.82) {
            val edge = radius * (0.36 + 0.055 * Noise.simplex(seed + 84, nx * 14.0, ny * 14.0, nz * 14.0))
            val outside = (radial - edge).coerceAtLeast(0.0)
            if (outside >= radius * 0.065) return ground
            val t = outside / (radius * 0.065)
            val plateau = 700.0 + 1_900.0 * Landforms.smooth((edge - radial) / (radius * 0.22))
            val folds = Noise.simplex(seed + 85, nx * 35.0, ny * 35.0, nz * 35.0) * 180.0
            return maxOf(ground, plateau + folds - 400.0 * t * t / ((1.0 - t) * (1.0 - t)))
        }
        if (ny < 0.94) return ground
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val edge = radius * (0.24 + 0.025 * Noise.simplex(seed + 81, nx * 18.0, ny * 18.0, nz * 18.0))
        val outside = (radial - edge).coerceAtLeast(0.0)
        if (outside >= radius * 0.025) return ground
        val t = outside / (radius * 0.025)
        val sink = 40.0 * t * t / ((1.0 - t) * (1.0 - t))
        val r = 1.0 - abs(Noise.simplex(seed + 82, px / 700.0, py / 700.0, pz / 700.0))
        val drift = Noise.simplex(seed + 83, px / 2_000.0, py / 2_000.0, pz / 2_000.0)
        return maxOf(ground, 12.0 + 5.0 * r * r * r + 1.5 * drift - sink)
    }
}
