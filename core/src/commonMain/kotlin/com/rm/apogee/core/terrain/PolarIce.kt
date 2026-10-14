package com.rm.apogee.core.terrain

import kotlin.math.abs

/** Observed Arctic sea-ice extent; Antarctica and Greenland come from the relief dataset. */
internal class PolarIce(private val seed: Int, private val radius: Double) {
    fun height(nx: Double, ny: Double, nz: Double, ground: Double): Double {
        if (ny < 0.6427876096865394 || ground >= 0.0) return ground
        val distance = EarthRelief.iceDistance(nx, ny, nz) * radius / EarthRelief.EARTH_RADIUS
        if (distance < -20_000.0) return ground
        val outside = (-distance).coerceAtLeast(0.0)
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val ridge = 1.0 - abs(Noise.simplex(seed + 82, px / 200.0, py / 200.0, pz / 200.0))
        val ice = 2.0 + 0.2 * ridge * ridge - (outside / 300.0) * (outside / 300.0)
        return maxOf(ground, ice)
    }

    fun material(nx: Double, ny: Double, nz: Double): SurfaceMaterial {
        val seam = abs(Noise.simplex(seed + 82, nx * radius / 700.0, ny * radius / 700.0, nz * radius / 700.0))
        return if (seam < 0.08) SurfaceMaterial.ICE else SurfaceMaterial.SNOW
    }
}
