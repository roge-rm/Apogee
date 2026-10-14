package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Math
import com.rm.apogee.core.math.StrictMath
import com.rm.apogee.core.resourceText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.math.floor
import kotlin.math.sqrt

/** Measured Earth relief in Earth metres; the caller scales horizontal and vertical distances alike. */
internal object EarthRelief {
    const val EARTH_RADIUS = 6_371_000.0
    @Serializable private class Definition(val south: Double, val north: Double, val west: Double, val east: Double,
        val step: Double, val width: Int, val height: Int, val data: String)
    @Serializable private class Relief(val grids: List<Definition>)
    @Serializable private class Ice(val grid: Definition)
    private val json = Json { ignoreUnknownKeys = true }
    private val grids = json.decodeFromString<Relief>(requireNotNull(resourceText("/terrain/earth-relief.json"))).grids.map(::Grid)
    private val ice by lazy { Grid(json.decodeFromString<Ice>(requireNotNull(resourceText("/terrain/arctic-ice.json"))).grid) }

    private class Grid(d: Definition) {
        val south = d.south; val north = d.north; val west = d.west; val east = d.east
        private val step = d.step; private val width = d.width; private val height = d.height
        private val periodic = east - west == 360.0
        private val values = ShortArray(width * height)
        init {
            val bytes = Base64.decode(d.data)
            var offset = 0
            for (i in values.indices) {
                var code = 0; var shift = 0
                do {
                    val byte = bytes[offset++].toInt() and 255
                    code = code or ((byte and 127) shl shift)
                    shift += 7
                } while (byte >= 128)
                val delta = (code ushr 1) xor -(code and 1)
                val col = i % width
                val left = if (col > 0) values[i - 1].toInt() else 0
                val above = if (i >= width) values[i - width].toInt() else 0
                val diagonal = if (col > 0 && i >= width) values[i - width - 1].toInt() else 0
                values[i] = (left + above - diagonal + delta).toShort()
            }
            require(offset == bytes.size)
        }
        private fun at(col: Int, row: Int): Double {
            val x = if (periodic) ((col % (width - 1)) + width - 1) % (width - 1) else col.coerceIn(0, width - 1)
            return values[row.coerceIn(0, height - 1) * width + x].toDouble()
        }
        fun sample(latitude: Double, longitude: Double): Double {
            val x = ((longitude - west) / step).coerceIn(0.0, (width - 1).toDouble())
            val y = ((north - latitude) / step).coerceIn(0.0, (height - 1).toDouble())
            val col = floor(x).toInt(); val row = floor(y).toInt()
            val tx = x - col; val ty = y - row
            fun line(r: Int) = cubic(at(col - 1, r), at(col, r), at(col + 1, r), at(col + 2, r), tx)
            return cubic(line(row - 1), line(row), line(row + 1), line(row + 2), ty)
        }
        fun weight(latitude: Double, longitude: Double): Double {
            val edge = minOf(latitude - south, north - latitude, longitude - west, east - longitude)
            return Landforms.smooth(edge / 1.0)
        }
    }
    private fun cubic(a: Double, b: Double, c: Double, d: Double, t: Double): Double {
        val value = b + 0.5 * t * (c - a + t * (2.0 * a - 5.0 * b + 4.0 * c - d + t * (3.0 * (b - c) + d - a)))
        return value.coerceIn(minOf(a, b, c, d), maxOf(a, b, c, d))
    }
    fun elevation(nx: Double, ny: Double, nz: Double): Double {
        val latitude = StrictMath.atan2(ny, sqrt(nx * nx + nz * nz)) * 180.0 / Math.PI
        val longitude = StrictMath.atan2(nz, nx) * 180.0 / Math.PI
        var height = grids[0].sample(latitude, longitude)
        for (i in 1 until grids.size) {
            val grid = grids[i]
            val weight = grid.weight(latitude, longitude)
            if (weight > 0.0) height += (grid.sample(latitude, longitude) - height) * weight
        }
        return height
    }
    /** Signed Earth metres inside the observed northern sea-ice boundary. */
    fun iceDistance(nx: Double, ny: Double, nz: Double): Double {
        val latitude = StrictMath.atan2(ny, sqrt(nx * nx + nz * nz)) * 180.0 / Math.PI
        if (latitude < 40.0) return -3_000_000.0
        val longitude = StrictMath.atan2(nz, nx) * 180.0 / Math.PI
        return ice.sample(latitude, longitude) * 100.0
    }
}
