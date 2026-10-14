package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Math
import com.rm.apogee.core.math.StrictMath
import kotlin.math.abs
import kotlin.math.sqrt

/** Generation-10 relief, only for recognising parked craft in that review build. */
internal class TerraContinents(private val seed: Int, private val radius: Double, private val peaks: Double, private val depth: Double) {
    private class Plate(lat: Double, lon: Double, east: Double, north: Double) {
        val centre = Landforms.at(lat, lon)
        private val longitude = Math.toRadians(lon)
        private val latitude = Math.toRadians(lat)
        private val ex = -StrictMath.sin(longitude)
        private val ez = StrictMath.cos(longitude)
        private val ux = -StrictMath.sin(latitude) * StrictMath.cos(longitude)
        private val uy = StrictMath.cos(latitude)
        private val uz = -StrictMath.sin(latitude) * StrictMath.sin(longitude)
        private val rx = StrictMath.tan(Math.toRadians(east * 0.88))
        private val ry = StrictMath.tan(Math.toRadians(north * 0.88))
        fun inland(x: Double, y: Double, z: Double, radius: Double): Double {
            val dot = x * centre[0] + y * centre[1] + z * centre[2]
            if (dot <= 0.2) return -radius
            val e = (x * ex + z * ez) / (dot * rx)
            val n = (x * ux + y * uy + z * uz) / (dot * ry)
            return (1.0 - sqrt(e * e + n * n)) * minOf(rx, ry) * radius
        }
    }
    // Joined plates make branching continents rather than copies of terrestrial coastlines.
    private val plates = arrayOf(
        Plate(38.0, -112.0, 24.0, 26.0), Plate(57.0, -86.0, 28.0, 17.0),
        Plate(-8.0, -92.0, 18.0, 23.0), Plate(-35.0, -108.0, 12.0, 22.0),
        Plate(20.0, 25.0, 26.0, 27.0), Plate(49.0, 40.0, 32.0, 20.0),
        Plate(37.0, 79.0, 24.0, 25.0), Plate(9.0, 94.0, 13.0, 24.0),
        Plate(-19.0, 30.0, 17.0, 24.0), Plate(-29.0, 138.0, 20.0, 15.0),
        Plate(68.0, -25.0, 9.0, 13.0), Plate(-18.0, 62.0, 5.0, 10.0),
        Plate(-43.0, 173.0, 5.0, 12.0), Plate(15.0, -157.0, 5.0, 10.0),
    )
    private val belts = arrayOf(
        Landforms.at(-48.0, -113.0) to Landforms.at(56.0, -124.0),
        Landforms.at(40.0, 18.0) to Landforms.at(39.0, 103.0),
        Landforms.at(-38.0, 36.0) to Landforms.at(17.0, 45.0),
        Landforms.at(-39.0, 151.0) to Landforms.at(-17.0, 143.0),
    )

    fun height(x: Double, y: Double, z: Double): Double {
        var inland = -radius
        for (plate in plates) {
            val d = plate.inland(x, y, z, radius)
            val h = ((22_000.0 - abs(inland - d)).coerceAtLeast(0.0) / 22_000.0)
            inland = maxOf(inland, d) + h * h * 5_500.0
        }
        // Two scales of warping produce peninsulas, bays and smaller ragged shorelines.
        inland += Noise.simplex(seed + 101, x * 12.0, y * 12.0, z * 12.0) * 20_000.0 +
            Noise.simplex(seed + 102, x * 53.0, y * 53.0, z * 53.0) * 4_000.0 +
            Noise.simplex(seed + 103, x * 180.0, y * 180.0, z * 180.0) * 900.0
        if (inland <= 0.0) {
            val offshore = -inland
            val shelf = 180.0 * Landforms.smooth(offshore / 18_000.0)
            val basin = (depth - 180.0) * Landforms.smooth((offshore - 18_000.0) / 75_000.0)
            return -shelf - basin
        }
        val coastal = Landforms.smooth(inland / 12_000.0)
        val uplands = Landforms.smooth(inland / 65_000.0)
        val rolling = 0.5 + 0.5 * Noise.simplex(seed + 104, x * 16.0, y * 16.0, z * 16.0)
        var mountainDistance = radius
        for ((a, b) in belts) mountainDistance = minOf(mountainDistance, Landforms.arcDistance(x, y, z, a, b, radius))
        val warp = Noise.simplex(seed + 105, x * 30.0, y * 30.0, z * 30.0) * 9_000.0
        val belt = 1.0 - Landforms.smooth((mountainDistance + warp) / 48_000.0)
        val ridges = Landforms.ridged(seed + 106, x * radius, y * radius, z * radius, 1.0 / 14_000.0, 3)
        return coastal * (30.0 + 650.0 * uplands * rolling + peaks * 0.86 * belt * belt * ridges)
    }
}
