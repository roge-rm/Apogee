package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Math
import com.rm.apogee.core.math.StrictMath
import kotlin.math.abs
import kotlin.math.sqrt

/** Generation-11 Earth detail for save migration. */
internal class LegacyEarthLand(
    private val seed: Int,
    private val radius: Double,
    homeX: Double, homeY: Double, homeZ: Double,
    padX: Double = homeX, padY: Double = homeY, padZ: Double = homeZ,
) {
    private val heightScale = radius / EarthRelief.EARTH_RADIUS
    private val px = padX * radius
    private val py = padY * radius
    private val pz = padZ * radius
    private val ice = PolarIce(seed, radius)

    fun height(nx: Double, ny: Double, nz: Double, base: Double, landness: Double): Double {
        val x = nx * radius; val y = ny * radius; val z = nz * radius
        val frozen = maxOf(smooth((ny - 0.927) / 0.013), smooth((-ny - 0.819) / 0.047))
        val drifts = Noise.simplex(seed + 51, x / 500.0, y / 500.0, z / 500.0) * 0.15
        if (frozen >= 1.0) return base + drifts * landness
        val rocky = smooth((base / heightScale - 800.0) / 3_000.0)
        val d = sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz))
        val calm = smooth((d - 2_000.0) / 12_000.0)
        var relief = fbm(x, y, z) * (0.2 + 1.5 * rocky)
        val ridge = 1.0 - abs(Noise.simplex(seed + 31, x / 1_200.0, y / 1_200.0, z / 1_200.0))
        relief += ridge * ridge * ridge * 1.0 * rocky
        val channel = abs(Noise.simplex(seed + 41, x / 4_000.0, y / 4_000.0, z / 4_000.0))
        relief -= (1.0 - smooth(channel / 0.08)) * minOf(1.5, base * 0.02) * rocky
        val dry = smooth((0.35 - moisture(nx, ny, nz)) / 0.2)
        if (dry > 0.0 && base < 1_600.0 * heightScale) {
            val dune = 1.0 - abs(Noise.simplex(seed + 34, x / 180.0, y / 1_400.0, z / 180.0))
            relief += dune * dune * 0.3 * dry * (1.0 - rocky)
        }
        relief += Noise.simplex(seed + 52, x / 45.0, y / 45.0, z / 45.0) * (0.03 + 0.15 * rocky)
        return maxOf(base * 0.2, base + (relief * (0.25 + 0.75 * calm) * (1.0 - frozen) + drifts * frozen) * landness)
    }

    fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double, landness: Double, watered: Double = 0.0): SurfaceMaterial {
        if (ny > 0.6428 && elevation < 3.0 && EarthRelief.iceDistance(nx, ny, nz) > -4_000.0) return ice.material(nx, ny, nz)
        val latitude = StrictMath.atan2(ny, sqrt(nx * nx + nz * nz)) * 180.0 / Math.PI
        val longitude = StrictMath.atan2(nz, nx) * 180.0 / Math.PI
        val coldLatitude = latitude + Noise.simplex(seed + 80, nx * 8.0, ny * 8.0, nz * 8.0) * 6.0
        val greenland = ny > 0.866 && longitude in -75.0..-10.0 && elevation > 600.0 * heightScale
        if (ny < -0.866 || coldLatitude > 73.5 || greenland) return if (slope > 0.25) SurfaceMaterial.ICE else SurfaceMaterial.SNOW
        val natural = moisture(nx, ny, nz)
        val arid = smooth((0.5 - natural) / 0.35)
        val snowLine = (5_500.0 - 60.0 * abs(latitude) + 1_500.0 * arid) * heightScale
        if (elevation > snowLine) return if (slope > 0.35) SurfaceMaterial.ROCK else SurfaceMaterial.SNOW
        if (slope > 0.30) return SurfaceMaterial.ROCK
        if (elevation < 2.0 && landness < 0.35) return SurfaceMaterial.SAND
        val wet = natural + (0.55 - natural) * watered
        if (wet < 0.32 && elevation > 2_000.0 * heightScale) return SurfaceMaterial.SCREE
        if (wet < 0.32) return if (slope > 0.12) SurfaceMaterial.CLAY else SurfaceMaterial.SAND
        if (slope > 0.15) return SurfaceMaterial.SCREE
        if (coldLatitude > 65.0 || latitude < -55.0) return SurfaceMaterial.DIRT
        val patch = Noise.simplex(seed + 71, nx * radius / 2_800.0, ny * radius / 2_800.0, nz * radius / 2_800.0)
        if (wet > 0.8 && elevation < 8.0 && patch < -0.2) return SurfaceMaterial.MUD
        if (wet > 0.64 + patch * 0.15) return SurfaceMaterial.FOREST
        if (wet < 0.42 || patch < -0.6) return SurfaceMaterial.DIRT
        return SurfaceMaterial.GRASS
    }

    /** Broad climate regions: equatorial forests, dry subtropics, temperate and boreal country. */
    private fun moisture(nx: Double, ny: Double, nz: Double): Double {
        val latitude = StrictMath.atan2(ny, sqrt(nx * nx + nz * nz)) * 180.0 / Math.PI
        val longitude = StrictMath.atan2(nz, nx) * 180.0 / Math.PI
        val a = abs(latitude)
        var wet = 0.55 + 0.22 * (1.0 - smooth(a / 18.0))
        val desert = maxOf(
            region(latitude, longitude, 23.0, 15.0, 11.0, 34.0),
            region(latitude, longitude, 23.0, 45.0, 12.0, 17.0),
            region(latitude, longitude, 40.0, 103.0, 11.0, 24.0),
            region(latitude, longitude, 32.0, 86.0, 8.0, 16.0),
            region(latitude, longitude, 35.0, -112.0, 11.0, 17.0),
            region(latitude, longitude, -23.0, -70.0, 13.0, 6.0),
            region(latitude, longitude, -24.0, 17.0, 12.0, 6.0),
            region(latitude, longitude, -25.0, 135.0, 13.0, 21.0),
        )
        wet += (0.12 - wet) * desert
        val forest = maxOf(
            region(latitude, longitude, -5.0, -62.0, 17.0, 22.0),
            region(latitude, longitude, 0.0, 23.0, 13.0, 20.0),
            region(latitude, longitude, 6.0, 110.0, 20.0, 25.0),
        )
        wet += (0.88 - wet) * forest
        val temperate = maxOf(
            region(latitude, longitude, 51.0, -5.0, 12.0, 25.0),
            region(latitude, longitude, 56.0, -104.0, 10.0, 32.0),
            region(latitude, longitude, 58.0, 85.0, 10.0, 60.0),
        )
        wet += (0.7 - wet) * temperate
        wet += Noise.simplex(seed + 24, nx * 45.0, ny * 45.0, nz * 45.0) * 0.06
        return wet.coerceIn(0.0, 1.0)
    }

    private fun region(latitude: Double, longitude: Double, lat: Double, lon: Double, north: Double, east: Double): Double {
        val dy = (latitude - lat) / north
        var dx = longitude - lon
        if (dx > 180.0) dx -= 360.0 else if (dx < -180.0) dx += 360.0
        dx /= east
        return smooth((1.0 - dx * dx - dy * dy) / 0.4)
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
