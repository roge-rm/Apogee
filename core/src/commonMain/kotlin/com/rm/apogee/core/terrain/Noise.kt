package com.rm.apogee.core.terrain

/**
 * The noise every terrain layer is built from. Server (JVM) and clients (ART) work out the ground
 * separately and must agree to the bit, or resting craft jitter. So only integer maths and IEEE
 * `+ - * /`: no tables, no library trig, nothing that could become a fused multiply-add.
 */
object Noise {

    /** An integer hash to 0..1. It wraps on purpose, so it depends only on the inputs. */
    fun hash(seed: Int, x: Int, y: Int, z: Int): Double = (hashInt(seed, x, y, z) ushr 8) / UNSIGNED_24_BIT

    fun hashInt(seed: Int, x: Int, y: Int, z: Int): Int {
        var h = seed
        h = h * 374761393 + x * 668265263
        h = h * 1274126177 + y * 2246822519.toInt()
        h = h * 2654435761.toInt() + z * 3266489917.toInt()
        h = h xor (h ushr 15)
        h *= 2246822519.toInt()
        h = h xor (h ushr 13)
        h *= 3266489917.toInt()
        h = h xor (h ushr 16)
        return h
    }

    /**
     * Value noise on an integer lattice, 0..1. Kept exactly as is for the continents, since changing
     * it would move every coastline. New layers use [simplex].
     */
    fun value(seed: Int, x: Double, y: Double, z: Double): Double {
        val xi = kotlin.math.floor(x).toInt()
        val yi = kotlin.math.floor(y).toInt()
        val zi = kotlin.math.floor(z).toInt()

        val fx = smoothstep(x - xi)
        val fy = smoothstep(y - yi)
        val fz = smoothstep(z - zi)

        val x00 = lerp(hash(seed, xi, yi, zi), hash(seed, xi + 1, yi, zi), fx)
        val x10 = lerp(hash(seed, xi, yi + 1, zi), hash(seed, xi + 1, yi + 1, zi), fx)
        val x01 = lerp(hash(seed, xi, yi, zi + 1), hash(seed, xi + 1, yi, zi + 1), fx)
        val x11 = lerp(hash(seed, xi, yi + 1, zi + 1), hash(seed, xi + 1, yi + 1, zi + 1), fx)

        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz)
    }

    /**
     * 3D simplex noise, about -1..1. Used for everything new: value noise shows lattice creases and
     * makes blobby ridges, and mountains, canyons and dunes need crisp ones. Gradients come from the
     * integer hash, so there's no permutation table.
     */
    fun simplex(seed: Int, x: Double, y: Double, z: Double): Double {
        val s = (x + y + z) * F3
        val i = kotlin.math.floor(x + s).toInt()
        val j = kotlin.math.floor(y + s).toInt()
        val k = kotlin.math.floor(z + s).toInt()
        val t = (i + j + k) * G3
        val x0 = x - (i - t)
        val y0 = y - (j - t)
        val z0 = z - (k - t)

        // Which of the six tetrahedra of the skewed cube this point is in.
        val i1: Int; val j1: Int; val k1: Int
        val i2: Int; val j2: Int; val k2: Int
        if (x0 >= y0) {
            if (y0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0 }
            else if (x0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1 }
            else { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1 }
        } else {
            if (y0 < z0) { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1 }
            else if (x0 < z0) { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1 }
            else { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0 }
        }

        val x1 = x0 - i1 + G3; val y1 = y0 - j1 + G3; val z1 = z0 - k1 + G3
        val x2 = x0 - i2 + 2.0 * G3; val y2 = y0 - j2 + 2.0 * G3; val z2 = z0 - k2 + 2.0 * G3
        val x3 = x0 - 1.0 + 3.0 * G3; val y3 = y0 - 1.0 + 3.0 * G3; val z3 = z0 - 1.0 + 3.0 * G3

        // Scaled so the output covers about -1..1 with the 0.5 kernel.
        return 76.0 * (
            corner(seed, i, j, k, x0, y0, z0) +
                corner(seed, i + i1, j + j1, k + k1, x1, y1, z1) +
                corner(seed, i + i2, j + j2, k + k2, x2, y2, z2) +
                corner(seed, i + 1, j + 1, k + 1, x3, y3, z3)
            )
    }

    private fun corner(seed: Int, i: Int, j: Int, k: Int, x: Double, y: Double, z: Double): Double {
        // 0.5, not the usual 0.6. At 0.6 a corner's reach is cut off at its simplex's edge, leaving
        // small steps that become metre-high cliffs in terrain.
        var t = 0.5 - x * x - y * y - z * z
        if (t <= 0.0) return 0.0
        t *= t
        return t * t * gradient(hashInt(seed, i, j, k), x, y, z)
    }

    /** Dot product with one of the twelve cube-edge gradients. */
    private fun gradient(hash: Int, x: Double, y: Double, z: Double): Double =
        when ((hash ushr 4) % 12) {
            0 -> x + y
            1 -> -x + y
            2 -> x - y
            3 -> -x - y
            4 -> x + z
            5 -> -x + z
            6 -> x - z
            7 -> -x - z
            8 -> y + z
            9 -> -y + z
            10 -> y - z
            else -> -y - z
        }

    fun smoothstep(t: Double) = t * t * (3.0 - 2.0 * t)

    fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private const val F3 = 1.0 / 3.0
    private const val G3 = 1.0 / 6.0
    private const val UNSIGNED_24_BIT = ((1 shl 24) - 1).toDouble()
}
