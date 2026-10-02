package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Noise.simplex
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The shapes a world's close-up relief is made of: what you drive on and land among, from a few
 * metres to a few kilometres across. Pure functions of a place in metres, like [Landforms], and
 * cheap, since every height sample runs them.
 */
internal object Closeup {

    /** 0..1 by place: rough country where it's high, smooth where it's low, patches [scale] metres across. */
    fun region(seed: Int, x: Double, y: Double, z: Double, scale: Double): Double =
        Landforms.smooth(0.5 + 1.4 * simplex(seed, x / scale, y / scale, z / scale))

    /**
     * Ridged hills [wavelength] metres apart, [height] from hollow to crest: crests sharp, hollows
     * round. About level on average, so ground blending into a site's flat doesn't climb.
     */
    fun hills(seed: Int, x: Double, y: Double, z: Double, wavelength: Double, height: Double): Double =
        (Landforms.ridged(seed, x, y, z, 1.0 / wavelength, 2) - 0.45) * height

    /** Rolling bumps [wavelength] metres across and about [height] tall, either way, [octaves] deep. */
    fun rough(seed: Int, x: Double, y: Double, z: Double, wavelength: Double, height: Double, octaves: Int = 2): Double =
        Landforms.fbm(seed, x, y, z, 1.0 / wavelength, octaves) * height

    /**
     * How much to add to [h] to step it into benches [step] metres tall: level treads, and risers
     * taking [riser] of each step. Gentle slopes become terraces, steep ones layered cliffs.
     */
    fun benches(h: Double, step: Double, riser: Double): Double {
        val x = h / step
        val f = floor(x)
        val stepped = (f + Landforms.smooth((x - f - (1.0 - riser)) / riser)) * step
        return stepped - h
    }

    /** Channels [depth] deep where noise crosses zero, [width] a share of [scale]: gullies and dry washes. */
    fun gullies(seed: Int, x: Double, y: Double, z: Double, scale: Double, width: Double, depth: Double): Double =
        Landforms.rivers(seed, x, y, z, scale, width, depth)

    /**
     * Buttes and knobs on a lattice of [cell]-metre cells, one at most per cell with [chance]: flat
     * tops up to [size] metres across and [height] tall, cliffs [skirt] wide. [radius] is the world's.
     */
    fun knobs(seed: Int, x: Double, y: Double, z: Double, radius: Double, cell: Double, chance: Double, size: Double, height: Double, skirt: Double): Double {
        var h = 0.0
        Landforms.scattered(seed, x, y, z, radius, cell, chance) { d, s, _ ->
            val top = size * (0.4 + 0.6 * s)
            if (d < top + skirt * 1.5) h = maxOf(h, Landforms.mesa(d, top, height * (0.5 + 0.5 * s), skirt))
        }
        return h
    }

    /** A pit [d] metres from its middle: [radius] across, [depth] deep, walls [wall] wide. */
    fun pit(d: Double, radius: Double, depth: Double, wall: Double): Double = -depth * (1.0 - Landforms.smooth((d - radius) / wall))

    /** A ridge [d] metres off its line: [width] across and [height] tall, rounded. */
    fun ridge(d: Double, width: Double, height: Double): Double {
        val x = d / (width / 2)
        if (x >= 1.0) return 0.0
        val q = 1.0 - x * x
        return height * q * q
    }

    /**
     * Wrinkled ground: low ridges where noise crosses zero, [scale] metres apart, [width] share
     * wide, [height] tall. Mare ridges, folds, the walls between cells.
     */
    fun wrinkles(seed: Int, x: Double, y: Double, z: Double, scale: Double, width: Double, height: Double): Double {
        val w = simplex(seed + 1, x / (scale / 4), y / (scale / 4), z / (scale / 4)) * scale * 0.12
        val c = abs(simplex(seed, (x + w) / scale, (y - w) / scale, (z + w) / scale))
        val t = (1.0 - c / width).coerceAtLeast(0.0)
        return height * t * t * (3 - 2 * t)
    }

    /** Metres between two unit directions on a world of [radius], by the chord. For small distances. */
    fun chord(nx: Double, ny: Double, nz: Double, c: DoubleArray, radius: Double): Double {
        val dx = nx - c[0]; val dy = ny - c[1]; val dz = nz - c[2]
        return sqrt(dx * dx + dy * dy + dz * dz) * radius
    }
}

/**
 * A world's everyday close-up relief, by its [Character]: hills where the country is rough, and
 * swells and bumps everywhere. Each world adds its own landmarks.
 */
internal class Relief(seed: Int, radius: Double, private val c: Character) {

    /**
     * Hills [hillWave] apart and [hillHeight] tall, a [smoothShare] of that even in smooth country,
     * patches [patch] metres across; swells [swellWave] apart and [swellHeight] tall, with bumps
     * down to an eighth of that on them.
     */
    class Character(
        val hillWave: Double,
        val hillHeight: Double,
        val swellWave: Double = 450.0,
        val swellHeight: Double = 6.0,
        val patch: Double = 30_000.0,
        val smoothShare: Double = 0.25,
    )

    private val seed = Noise.hashInt(seed, 0x2E11, 0, 0)

    /** 0..1: rough country, where the hills are. */
    fun region(x: Double, y: Double, z: Double): Double = Closeup.region(seed, x, y, z, c.patch)

    /** The relief at a place in metres, with hills weighted by [hills]. */
    fun height(x: Double, y: Double, z: Double, hills: Double = 1.0): Double {
        var h = Closeup.rough(seed + 2, x, y, z, c.swellWave, c.swellHeight, 4)
        if (hills > 0.01 && c.hillHeight > 0.0) {
            val rough = region(x, y, z)
            h += Closeup.hills(seed + 1, x, y, z, c.hillWave, c.hillHeight) * hills * (c.smoothShare + (1.0 - c.smoothShare) * rough)
        }
        return h
    }
}

/**
 * One value a land worked out for a place, kept per thread so its close-up relief, asked next
 * for the same place, needn't work it out again.
 */
internal class Remembered {
    private val last = com.rm.apogee.core.PerThread { DoubleArray(4).also { it[0] = Double.NaN } }

    inline fun at(nx: Double, ny: Double, nz: Double, work: () -> Double): Double {
        val l = get()
        if (l[0] == nx && l[1] == ny && l[2] == nz) return l[3]
        val v = work()
        l[0] = nx; l[1] = ny; l[2] = nz; l[3] = v
        return v
    }

    @PublishedApi internal fun get(): DoubleArray = last.get()
}
