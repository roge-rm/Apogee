package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Landforms.at
import com.rm.apogee.core.terrain.Landforms.distance
import kotlin.math.abs
import com.rm.apogee.core.PerThread

/**
 * Fornax: kneaded by Magna's tides until it melts inside. Lava resurfaces it too fast for craters.
 * Yellow and orange sulfur plains, paterae (volcanic pits with dark floors, a few with open lava
 * lakes), and lone sheer mountains taller than anything on Terra.
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
        /** The open lava lakes: the brightest spots, and the first a lander must avoid. */
        val LAVA_LAKES = listOf(at(-12.0, 50.0), at(25.0, -140.0), at(-40.0, 170.0))
    }
}

/**
 * Crusta: a shell of ice over a hidden ocean. Almost perfectly smooth and bright, except for long
 * reddish-brown cracks across the whole world (each a double ridge with a trough down the middle)
 * and patches of chaos, where the crust broke into blocks, drifted and refroze. Hardly any craters.
 */
internal class CrustaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val cracks = Noise.hashInt(seed, 2, 0, 0)
    private val chaos = Noise.hashInt(seed, 3, 0, 0)
    // Small ones only: the surface is young.
    private val craters = Craters(Noise.hashInt(seed, 4, 0, 0), radius, Craters.scaledFrom(radius, 0.3).drop(2))
    private val near = PerThread { DoubleArray(1) }

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
        // Cracks and chaos are stained by what welled up through them.
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
 * Maxima: the biggest moon. Old, dark, densely cratered polygons, and between them bright younger
 * ice torn into parallel grooves as the moon stretched. Frost caps at both poles.
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
        // Grooves run along each band, with the axis turning slowly across the world.
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
        /** A band of grooved ground crossing the dark, to land between the two. */
        val GROOVED = at(10.0, 35.0)
    }
}

/**
 * Cicatrix: the most battered face in the system, dark and cratered shoulder to shoulder, with
 * bright ice on fresh rims. The Great Scar, a basin ringed by ridge after ridge, reaches across a
 * quarter of the world.
 */
internal class CicatrixLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius, 1.4))

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 30_000.0, 4) * 350.0
        val d = distance(nx, ny, nz, GREAT_SCAR, radius)
        h += Landforms.basin(d, 45_000.0, 1_200.0, rings = 7, ringHeight = 500.0)
        // The scar's floor was smoothed, so fewer craters in it.
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
