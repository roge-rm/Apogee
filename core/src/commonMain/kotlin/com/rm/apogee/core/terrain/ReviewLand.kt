package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Math
import com.rm.apogee.core.math.StrictMath
import kotlin.math.abs
import kotlin.math.sqrt

/** Generation-10 local relief, used only when migrating review-build saves. */
internal class ReviewLand(
    private val seed: Int,
    private val radius: Double,
    homeX: Double, homeY: Double, homeZ: Double,
    padX: Double = homeX, padY: Double = homeY, padZ: Double = homeZ,
) {
    private val px = padX * radius
    private val py = padY * radius
    private val pz = padZ * radius

    fun height(nx: Double, ny: Double, nz: Double, base: Double, landness: Double): Double {
        val x = nx * radius; val y = ny * radius; val z = nz * radius
        val frozen = maxOf(smooth((ny - 0.927) / 0.013), smooth((-ny - 0.819) / 0.047))
        val drifts = Noise.simplex(seed + 51, x / 500.0, y / 500.0, z / 500.0) * 1.5
        if (frozen >= 1.0) return base + drifts * landness
        val rocky = smooth((base - 200.0) / 2_000.0)
        val d = sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz))
        val calm = smooth((d - 2_000.0) / 12_000.0)
        var relief = fbm(x, y, z) * (8.0 + 75.0 * rocky)
        val ridge = 1.0 - abs(Noise.simplex(seed + 31, x / 1_200.0, y / 1_200.0, z / 1_200.0))
        relief += ridge * ridge * ridge * 100.0 * rocky
        val channel = abs(Noise.simplex(seed + 41, x / 4_000.0, y / 4_000.0, z / 4_000.0))
        relief -= (1.0 - smooth(channel / 0.08)) * minOf(80.0, base * 0.1) * rocky
        val dry = smooth((0.35 - moisture(nx, ny, nz)) / 0.2)
        if (dry > 0.0 && base < 1_600.0) {
            val dune = 1.0 - abs(Noise.simplex(seed + 34, x / 180.0, y / 1_400.0, z / 180.0))
            relief += dune * dune * 10.0 * dry * (1.0 - rocky)
        }
        relief += Noise.simplex(seed + 52, x / 45.0, y / 45.0, z / 45.0) * (0.3 + 2.5 * rocky)
        return maxOf(base * 0.2, base + (relief * (0.25 + 0.75 * calm) * (1.0 - frozen) + drifts * frozen) * landness)
    }

    /** Broad climate regions: equatorial forests, dry subtropics, temperate and boreal country. */
    private fun moisture(nx: Double, ny: Double, nz: Double): Double {
        val latitude = StrictMath.atan2(ny, sqrt(nx * nx + nz * nz)) * 180.0 / Math.PI
        val a = abs(latitude)
        // Global circulation: wet tropics, subtropical dry belts, wet temperate latitudes.
        val tropical = 1.0 - smooth(a / 18.0)
        val desert = 1.0 - smooth(abs(a - 27.0) / 15.0)
        val temperate = 1.0 - smooth(abs(a - 50.0) / 18.0)
        val regional = Noise.simplex(seed + 24, nx * 5.0, ny * 5.0, nz * 5.0)
        val weather = Noise.simplex(seed + 25, nx * 45.0, ny * 45.0, nz * 45.0)
        return (0.54 + 0.32 * tropical - 0.38 * desert + 0.17 * temperate +
            0.22 * regional + 0.06 * weather).coerceIn(0.0, 1.0)
    }

    private fun fbm(x: Double, y: Double, z: Double): Double {
        var frequency = 1.0 / 2_000.0
        var amplitude = 0.5
        var height = 0.0
        repeat(4) {
            height += Noise.simplex(seed + 32 + it, x * frequency, y * frequency, z * frequency) * amplitude
            frequency *= 2.03
            amplitude *= 0.5
        }
        return height
    }

    private fun smooth(value: Double) = Noise.smoothstep(value.coerceIn(0.0, 1.0))
}
