package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Landforms.at
import com.rm.apogee.core.terrain.Landforms.distance
import kotlin.math.abs

/**
 * Fornax: squeezed and kneaded by Magna on every orbit until it melts inside. No crater survives
 * here, because lava resurfaces everything too fast. It has sulfur plains in yellows and oranges,
 * **paterae** (volcanic pits with dark floors, a few of them holding open **lava lakes**), and here
 * and there a **lone mountain** standing out of the plain by itself, sheer-sided and taller than
 * anything on Terra.
 */
internal class FornaxLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val pits = Noise.hashInt(seed, 2, 0, 0)
    private val peaks = Noise.hashInt(seed, 3, 0, 0)

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 25_000.0, 4) * 250.0
        Landforms.scattered(peaks, px, py, pz, radius, 60_000.0, 0.18) { d, size, _ ->
            h += Landforms.mesa(d, 6_000.0 + 8_000.0 * size, 4_000.0 + 5_000.0 * size, 3_000.0) *
                (0.85 + 0.15 * Landforms.ridged(peaks + 9, px, py, pz, 1.0 / 2_000.0, 3))
        }
        Landforms.scattered(pits, px, py, pz, radius, 25_000.0, 0.35) { d, size, _ ->
            h -= Landforms.mesa(d, 3_000.0 + 5_000.0 * size, 300.0 + 300.0 * size, 800.0)
        }
        for (l in LAVA_LAKES) h -= Landforms.mesa(distance(nx, ny, nz, l, radius), 6_000.0, 500.0, 1_500.0)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        for (l in LAVA_LAKES) if (distance(nx, ny, nz, l, radius) < 5_000.0) return SurfaceMaterial.LAVA
        if (slope > 0.3) return SurfaceMaterial.ROCK
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var floor = false
        Landforms.scattered(pits, px, py, pz, radius, 25_000.0, 0.35) { d, size, _ -> if (d < (3_000.0 + 5_000.0 * size) * 0.9) floor = true }
        if (floor) return SurfaceMaterial.BASALT
        return SurfaceMaterial.SULFUR
    }

    companion object {
        /**
         * The open lakes: the brightest spots, and the first ones a lander has to steer clear of.
         */
        val LAVA_LAKES = listOf(at(-12.0, 50.0), at(25.0, -140.0), at(-40.0, 170.0))
    }
}

/**
 * Crusta: a shell of ice over a hidden ocean. It's almost perfectly smooth, with nothing standing
 * more than a few hundred metres high, and bright, except where it's **cracked**. Long
 * reddish-brown lines criss-cross the whole world, each one a double ridge with a trough down the
 * middle. There's also **chaos**, patches where the crust broke into blocks, drifted and froze
 * again. There are hardly any craters, because the surface is young.
 */
internal class CrustaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val cracks = Noise.hashInt(seed, 2, 0, 0)
    private val chaos = Noise.hashInt(seed, 3, 0, 0)
    // Only small ones, because nothing large has hit since the surface last renewed.
    private val craters = Craters(Noise.hashInt(seed, 4, 0, 0), radius, Craters.scaledFrom(radius, 0.3).drop(2))
    private val near = ThreadLocal.withInitial { DoubleArray(1) }

    private fun chaosAmount(px: Double, py: Double, pz: Double) =
        Landforms.smooth((Landforms.fbm(chaos + 5, px, py, pz, 1.0 / 35_000.0, 2) - 0.45) / 0.1)

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 15_000.0, 4) * 60.0
        h += Landforms.lineae(cracks, nx, ny, nz, LINEAE, radius, 1_500.0, 180.0, near.get())
        val c = chaosAmount(px, py, pz)
        if (c > 0.0) h += c * (Landforms.chaos(chaos, px, py, pz, 2_500.0, 250.0) - 100.0)
        h += craters.height(px, py, pz, 1.0)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val n = near.get()
        Landforms.lineae(cracks, nx, ny, nz, LINEAE, radius, 1_500.0, 180.0, n)
        // The cracks and the chaos are stained by what welled up through them.
        if (n[0] > 0.4 || chaosAmount(px, py, pz) > 0.5) return SurfaceMaterial.THOLIN
        return if (slope > 0.3) SurfaceMaterial.SNOW else SurfaceMaterial.ICE
    }

    companion object {
        const val LINEAE = 18
        /** Where two big cracks cross, near the middle of the lit face. */
        val CROSSING = at(5.0, 0.0)
    }
}

/**
 * Maxima: the biggest moon of all. It has two kinds of ground: **dark terrain**, old, rock-stained
 * and densely cratered, in big polygons, and between them **bright grooved terrain**, younger ice
 * torn into parallel ridges and troughs as the moon stretched. There are frost caps at both poles.
 */
internal class MaximaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val bands = Noise.hashInt(seed, 2, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 3, 0, 0), radius, Craters.scaledFrom(radius, 0.9))

    /** 1 on bright grooved ground, 0 on dark. */
    private fun bright(px: Double, py: Double, pz: Double) =
        Landforms.smooth((abs(Landforms.fbm(bands, px, py, pz, 1.0 / 90_000.0, 2)) - 0.12) / -0.08 + 1.0)

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val b = bright(px, py, pz)
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 40_000.0, 4) * 500.0 - b * 400.0
        // The grooves run along each band, with the axis turning slowly across the world.
        val axis = doubleArrayOf(
            Landforms.fbm(bands + 3, px, py, pz, 1.0 / 400_000.0, 1),
            Landforms.fbm(bands + 4, px, py, pz, 1.0 / 400_000.0, 1),
            Landforms.fbm(bands + 5, px, py, pz, 1.0 / 400_000.0, 1),
        ).let { a -> val l = kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-6); doubleArrayOf(a[0] / l, a[1] / l, a[2] / l) }
        h += b * Landforms.grooves(px, py, pz, axis, 5_000.0, 350.0)
        h += craters.height(px, py, pz, 1.0 - 0.6 * b)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (abs(ny) > 0.85) return SurfaceMaterial.SNOW
        if (slope > 0.3) return SurfaceMaterial.ROCK
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        return if (bright(px, py, pz) > 0.5) SurfaceMaterial.ICE else SurfaceMaterial.REGOLITH
    }

    companion object {
        /** A band of grooved ground crossing the dark, as somewhere to land between the two. */
        val GROOVED = at(10.0, 35.0)
    }
}

/**
 * Cicatrix: the most battered face in the system. It's dark and cratered shoulder to shoulder, and
 * nothing has happened here since except more craters. Their fresh rims are bright ice. One blow
 * outdid all the rest: **the Great Scar**, a basin ringed by ridge after ridge in circles, reaching
 * across a quarter of the world.
 */
internal class CicatrixLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius, 1.4))

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 30_000.0, 4) * 350.0
        val d = distance(nx, ny, nz, GREAT_SCAR, radius)
        h += Landforms.basin(d, 45_000.0, 1_200.0, rings = 7, ringHeight = 500.0)
        // The scar's bright floor was smoothed, so there are fewer craters in it.
        h += craters.height(px, py, pz, 1.0 - 0.6 * Landforms.within(d, 45_000.0, 20_000.0))
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (slope > 0.3) return SurfaceMaterial.ROCK
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        if (distance(nx, ny, nz, GREAT_SCAR, radius) < 40_000.0) return SurfaceMaterial.SNOW
        if (craters.onRim(px, py, pz, 1.0)) return SurfaceMaterial.ICE
        return SurfaceMaterial.REGOLITH
    }

    companion object {
        val GREAT_SCAR = at(15.0, -60.0)
    }
}
