package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tsunamis' sea round the camera, for the flat water past the waves: a square of [SIDE] by [SIDE]
 * points [spacing] metres apart along [east] and [north] from unit, body-fixed [centre]. Each holds
 * in [values] how far the tsunamis move the sea there, in whole centimetres, and in [first] and
 * [second], for the two drawn, the seconds since each arrived there by its coarse arrival times
 * and how high it is in deep water there, packed as [packTau] has it, for drawing their crests per
 * pixel. Land points hold the sea as if it ran over them, so the water by the coast reads
 * smoothly. [flood] is the water over low land further out than the waves reach.
 */
class TsunamiField(
    val centre: Vec3,
    val east: Vec3,
    val north: Vec3,
    val spacing: Double,
    val values: FloatArray,
    /** How far the tsunami moves the sea at the shore nearest [centre], in metres. */
    val shore: Double,
    val first: FloatArray = FloatArray(VEC4S * 4) { packTau(NEVER, 0.0) },
    val second: FloatArray = FloatArray(VEC4S * 4) { packTau(NEVER, 0.0) },
    /** The world time it was worked out for. */
    val time: Double = 0.0,
    /** Seconds between each one's crests, 0 for none. */
    val period: Double = 0.0,
    val period2: Double = 0.0,
    /** The most each reaches on a coast, in metres. */
    val coastCap: Double = 0.0,
    val coastCap2: Double = 0.0,
    /** The shelf's delay in seconds per (sqrt 6 - sqrt depth), as Tsunamis has it. */
    val shoalScale: Double = 0.0,
    /** The body's radius, in metres. */
    val radius: Double = 1.0,
    val flood: TsunamiFlood? = null,
) {
    /** The height at point ([i], [j]), in metres. */
    fun height(i: Int, j: Int): Double = values[j * SIDE + i].toDouble() / 100.0

    /** The first's deep-water height and seconds since it arrived at point ([i], [j]). */
    fun deep(i: Int, j: Int): Double = unpackDeep(first[j * SIDE + i])
    fun tau(i: Int, j: Int): Double = unpackTau(first[j * SIDE + i])

    /**
     * As the terrain shader has it at unit, body-fixed [up] over water [depth] metres deep, at
     * [time]: seconds since the drawn crest, how white it is and its period into out[0..2], from
     * whichever of the two Tsunamis.drawn would pick. [out] needs [CREST_OUT] places; the rest is
     * scratch. False outside the field.
     */
    fun crest(up: Vec3, depth: Double, time: Double, out: DoubleArray): Boolean {
        val middle = (SIDE - 1) * 0.5
        val dx = up.x - centre.x; val dy = up.y - centre.y; val dz = up.z - centre.z
        val x = (dx * east.x + dy * east.y + dz * east.z) * radius / spacing + middle
        val y = (dx * north.x + dy * north.y + dz * north.z) * radius / spacing + middle
        if (x < 0.0 || y < 0.0 || x >= SIDE - 1.0 || y >= SIDE - 1.0) return false
        val i = x.toInt(); val j = y.toInt()
        val fx = x - i; val fy = y - j
        val k = j * SIDE + i
        fun lerp(a: FloatArray, tau: Boolean): Double {
            fun at(v: Float) = if (tau) unpackTau(v) else unpackDeep(v)
            val bottom = at(a[k]) + (at(a[k + 1]) - at(a[k])) * fx
            val top = at(a[k + SIDE]) + (at(a[k + SIDE + 1]) - at(a[k + SIDE])) * fx
            return bottom + (top - bottom) * fy
        }
        val age = time - this.time
        // Gone by the square's edge.
        val fade = 1.0 - smooth(0.7, 1.0, max(kotlin.math.abs(x - middle), kotlin.math.abs(y - middle)) / middle)
        one(lerp(first, true) + age, lerp(first, false) * fade, depth, period, coastCap, out, 3)
        one(lerp(second, true) + age, lerp(second, false) * fade, depth, period2, coastCap2, out, 7)
        if (out[9] > out[5] || out[9] == out[5] && out[10] > out[6]) {
            out[0] = out[7]; out[1] = out[8]; out[2] = period2
        } else {
            out[0] = out[3]; out[1] = out[4]; out[2] = if (out[6] < 0.0) 0.0 else period
        }
        return true
    }

    /** Crest, whiteness, tier and size into [out] from [at], as the shader's tsunamiOne. */
    private fun one(tauRaw: Double, deep: Double, depth: Double, period: Double, cap: Double, out: DoubleArray, at: Int) {
        val tau = tauRaw - shoalScale * (SQRT_SHOAL - sqrt(depth.coerceIn(0.0, SHOAL_DEPTH)))
        if (period <= 0.0 || tau >= TRAIN || tauRaw <= 0.0) {
            out[at] = NO_CREST; out[at + 1] = 0.0; out[at + 2] = 0.0; out[at + 3] = -1.0
            return
        }
        val d = max(depth, SHALLOWEST)
        val a = min(cap, deep * sqrt(sqrt(DEEP_DEPTH / d)))
        val steep = smooth(BORE_FROM, BORE_FULL, a / d)
        val envelope = kotlin.math.exp(-max(tau, 0.0) / DECAY) * (1.0 - smooth(0.8 * TRAIN, TRAIN, tau))
        val rise = 0.5 + (min(BORE_RISE / period, 0.5) - 0.5) * steep
        val white = (smooth(0.6, 0.9, steep) - CREST_WHITE * (1.0 - smooth(0.0, CREST_SHIFT, 0.5 * (0.5 - rise) * period))) * smooth(BORE_LEAST, BORE_LEAST * 3.0, a * envelope)
        val crest = tau - (0.5 + 0.5 * rise) * period
        val since = sinceCrest(crest, period)
        out[at] = crest; out[at + 1] = white
        out[at + 2] = if (kotlin.math.abs(white) < 0.01 || since <= -FOAM_AHEAD || since >= FOAM_BEHIND) 0.0 else if (since < BORE_TRAIL) 2.0 else 1.0
        out[at + 3] = a * envelope
    }

    companion object {
        /** Places [crest] needs in its out. */
        const val CREST_OUT = 11

        /** Points along each side; odd, so one sits on the centre. */
        const val SIDE = 15

        /** Uniform vec4s the shader takes each array in. */
        const val VEC4S = (SIDE * SIDE + 3) / 4

        /** The deep-water height packed as a share of this, in metres. */
        const val DEEP_SCALE = 4.0

        /** Seconds since arrival where one never arrives: long before any crest. */
        const val NEVER = -10_000.0

        /** A height in whole centimetres. */
        fun packHeight(height: Double): Float = kotlin.math.round(height.coerceIn(-300.0, 300.0) * 100.0).toFloat()

        /** Seconds since arrival in quarter seconds, and [deep] over [DEEP_SCALE] in what's left over, under 1. */
        fun packTau(tau: Double, deep: Double): Float =
            (floor(tau.coerceIn(NEVER, 20_000.0) * 4.0) + (deep / DEEP_SCALE).coerceIn(0.0, 0.99)).toFloat()

        fun unpackTau(v: Float): Double = floor(v.toDouble()) * 0.25
        fun unpackDeep(v: Float): Double = (v.toDouble() - floor(v.toDouble())) * DEEP_SCALE

        /** As Tsunamis has them; the shader's TSUNAMI_FIELD uses the same. */
        const val SHOAL_DEPTH = 6.0
        const val SQRT_SHOAL = 2.449489742783178
        const val SHALLOWEST = 2.0
        const val DEEP_DEPTH = 3000.0
        const val DECAY = 2400.0
        const val TRAIN = 7200.0
        const val BORE_FROM = 0.1
        const val BORE_FULL = 0.35
        const val BORE_RISE = 3.0
        const val BORE_LEAST = 0.3
        const val BORE_TRAIL = 70.0
        const val CREST_WHITE = 0.8
        const val CREST_SHIFT = 4.0
        const val FOAM_AHEAD = 30.0
        const val FOAM_BEHIND = 240.0
        const val NO_CREST = -10_000.0

        /** Tsunamis.sinceCrest. */
        fun sinceCrest(crest: Double, period: Double): Double {
            if (period <= 0.0) return crest
            val k = floor((crest + period - FOAM_BEHIND - 10.0) / period)
            return if (k > 0.0) crest - k * period else crest
        }

        private fun smooth(a: Double, b: Double, x: Double): Double {
            val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }
    }
}

/**
 * Water over low land round the camera, for the ground past the waves: [side] by [side] points
 * [spacing] metres apart along [east] and [north] from unit, body-fixed [centre], each the water's
 * level over the datum where a tsunami floods it, as two bytes for an RG8 texture ([level]), 0
 * where it's dry. Made anew each time, so a renderer uploads it when it sees a new one.
 */
class TsunamiFlood(
    val centre: Vec3,
    val east: Vec3,
    val north: Vec3,
    val spacing: Double,
    val texels: ByteArray,
    val side: Int = SIDE,
) {
    /** The level at point ([i], [j]) in metres, or 0 where it's dry. */
    fun level(i: Int, j: Int): Double {
        val k = 2 * (j * side + i)
        return ((texels[k].toInt() and 255) * 256 + (texels[k + 1].toInt() and 255)) / PER_METRE
    }

    // Built with the field on its worker; terrain selection only reads four counts per patch.
    private val wetCounts = run {
        val stride = side + 1
        IntArray(stride * stride).also { counts ->
            for (j in 0 until side) for (i in 0 until side) {
                val k = (j + 1) * stride + i + 1
                counts[k] = (if (level(i, j) > 0.0) 1 else 0) +
                    counts[k - 1] + counts[k - stride] - counts[k - stride - 1]
            }
        }
    }

    /** Whether a surface patch's projected bounds touch flooded samples or their neighbours. */
    fun touches(up: Vec3, extent: Double, radius: Double): Boolean {
        val middle = (side - 1) * 0.5
        val x = ((up dot east) - (centre dot east)) * radius / spacing + middle
        val y = ((up dot north) - (centre dot north)) * radius / spacing + middle
        val reach = extent / spacing + 1.0
        val left = kotlin.math.floor(x - reach).toInt().coerceAtLeast(0)
        val bottom = kotlin.math.floor(y - reach).toInt().coerceAtLeast(0)
        val right = kotlin.math.ceil(x + reach).toInt().coerceAtMost(side - 1)
        val top = kotlin.math.ceil(y + reach).toInt().coerceAtMost(side - 1)
        if (left > right || bottom > top) return false
        val stride = side + 1
        val counts = wetCounts
        return counts[(top + 1) * stride + right + 1] - counts[bottom * stride + right + 1] -
            counts[(top + 1) * stride + left] + counts[bottom * stride + left] > 0
    }

    companion object {
        const val SIDE = 128

        /** Steps per metre of level: two bytes reach 32 m. */
        const val PER_METRE = 2_000.0

        /** Largest level the two-byte flood grid can encode, in metres. */
        const val MAX_LEVEL = 65_535.0 / PER_METRE

        /** Writes [level] metres for point [k] into [texels]. */
        fun put(texels: ByteArray, k: Int, level: Double) {
            val v = kotlin.math.round(level * PER_METRE).toInt().coerceIn(1, 65_535)
            texels[2 * k] = (v shr 8).toByte()
            texels[2 * k + 1] = (v and 255).toByte()
        }
    }
}
