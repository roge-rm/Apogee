package com.rm.apogee.core.terrain

import com.rm.apogee.core.resourceText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.math.floor

/** Immutable sampled fields with bilinear interpolation and optional longitude wrapping. */
internal class TerrainGrid(path: String) {
    @Serializable private class Definition(val width: Int, val height: Int, val west: Double,
        val north: Double, val dx: Double, val dy: Double, val unit: Double,
        val periodic: Boolean = false, val data: String)
    private val d = Json { ignoreUnknownKeys = true }.decodeFromString<Definition>(requireNotNull(resourceText(path)))
    private val values = ShortArray(d.width * d.height)
    val west get() = d.west
    val east get() = d.west + (d.width - 1) * d.dx
    val north get() = d.north
    val south get() = d.north - (d.height - 1) * d.dy
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
            val col = i % d.width
            val left = if (col > 0) values[i - 1].toInt() else 0
            val above = if (i >= d.width) values[i - d.width].toInt() else 0
            val diagonal = if (col > 0 && i >= d.width) values[i - d.width - 1].toInt() else 0
            values[i] = (left + above - diagonal + ((code ushr 1) xor -(code and 1))).toShort()
        }
        require(offset == bytes.size)
    }
    private fun at(x: Int, y: Int): Double {
        val col = if (d.periodic) ((x % d.width) + d.width) % d.width else x.coerceIn(0, d.width - 1)
        return values[y.coerceIn(0, d.height - 1) * d.width + col] * d.unit
    }
    fun sample(east: Double, north: Double): Double {
        val x = (east - d.west) / d.dx
        val y = ((d.north - north) / d.dy).coerceIn(0.0, (d.height - 1).toDouble())
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        val tx = x - ix; val ty = y - iy
        val a = at(ix, iy); val b = at(ix + 1, iy)
        val c = at(ix, iy + 1); val e = at(ix + 1, iy + 1)
        return (a + (b - a) * tx) * (1.0 - ty) + (c + (e - c) * tx) * ty
    }
}

internal val EarthMoisture = TerrainGrid("/terrain/earth-moisture.json")
