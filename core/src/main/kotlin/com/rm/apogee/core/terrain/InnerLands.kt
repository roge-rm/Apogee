package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Landforms.at
import com.rm.apogee.core.terrain.Landforms.distance
import kotlin.math.abs

/**
 * Celer: nearest the sun, airless and scorched. A battered face much like
 * Luna's but darker and more densely struck; one immense impact basin, the
 * **Great Basin**, its floor flooded smooth and ringed by mountains, and
 * opposite it the jumbled "weird ground" its shock threw up; long lobate
 * scarps where the whole world shrank as it cooled, cliffs a kilometre
 * high running hundreds of kilometres; and ice in the shadowed floors of
 * its polar craters, where the sun never reaches.
 */
internal class CelerLand(seed: Int, private val radius: Double) : WorldLand {
    private val craters = Craters(Noise.hashInt(seed, 1, 0, 0), radius, Craters.scaledFrom(radius, 1.15))
    private val detail = Noise.hashInt(seed, 2, 0, 0)
    private val scarpSeed = Noise.hashInt(seed, 3, 0, 0)

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val fromBasin = distance(nx, ny, nz, GREAT_BASIN, radius)
        val inBasin = Landforms.within(fromBasin, BASIN_RADIUS * 0.9, BASIN_RADIUS * 0.2)
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 20_000.0, 4) * 500.0
        h += Landforms.basin(fromBasin, BASIN_RADIUS, 2_000.0, rings = 2, ringHeight = 900.0)
        // The antipode: hilly, broken ground where the shock met itself.
        val fromAntipode = distance(nx, ny, nz, ANTIPODE, radius)
        h += Landforms.within(fromAntipode, 40_000.0, 25_000.0) * Landforms.ridged(detail + 5, px, py, pz, 1.0 / 3_000.0, 3) * 900.0
        // The basin's floor was flooded smooth: fewer craters there.
        h += craters.height(px, py, pz, 1.0 - 0.7 * inBasin)
        for (k in 0 until SCARPS) {
            val pole = Landforms.pole(scarpSeed, k)
            val d = Landforms.signedFromCircle(nx, ny, nz, pole, radius)
            // A cliff along a stretch of the circle, not all the way round.
            val along = Landforms.fbm(scarpSeed + k, px, py, pz, 1.0 / 150_000.0, 1)
            if (along > 0.1) h += Landforms.scarp(d, 3_000.0, 900.0 * Landforms.smooth((along - 0.1) / 0.2))
        }
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (slope > 0.25) return SurfaceMaterial.ROCK
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // Ice in the polar craters' shade.
        if (abs(ny) > 0.985 && craters.onFloor(px, py, pz, 1.0)) return SurfaceMaterial.ICE
        if (distance(nx, ny, nz, GREAT_BASIN, radius) < BASIN_RADIUS * 0.85) return SurfaceMaterial.BASALT
        if (craters.onRim(px, py, pz, 1.0)) return SurfaceMaterial.SCREE
        return SurfaceMaterial.REGOLITH
    }

    companion object {
        val GREAT_BASIN = at(30.0, 170.0)
        val ANTIPODE = at(-30.0, -10.0)
        const val BASIN_RADIUS = 60_000.0
        const val SCARPS = 7
    }
}

/**
 * Caligo: a world under a lid. Beneath the crushing air, mostly volcanic
 * plains - dark basalt, young, few craters, crossed by long lava channels
 * and dotted with **pancake domes**, flat-topped blisters of thick lava.
 * Two highland continents stand out of them: **Ishtar**, in the far north,
 * a high plateau walled by mountain ranges and crowned by **Maxwell**, the
 * tallest peak on the world; and **Aphrodite**, a long ragged highland along
 * the equator. Both are **tesserae**: rock folded and refolded into
 * criss-crossing ridges. A few great shield volcanoes, one still
 * glowing: a lava lake in its caldera.
 */
internal class CaligoLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val tessera = Noise.hashInt(seed, 2, 0, 0)
    private val domes = Noise.hashInt(seed, 3, 0, 0)
    private val flows = Noise.hashInt(seed, 4, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 5, 0, 0), radius, Craters.scaledFrom(radius, 0.12))

    /** 1 on a highland continent, 0 on the plains. */
    private fun highland(nx: Double, ny: Double, nz: Double, px: Double, py: Double, pz: Double): Double {
        val ishtar = Landforms.within(distance(nx, ny, nz, ISHTAR, radius), 110_000.0, 40_000.0)
        // Aphrodite is long: the nearest point of its spine.
        val aphrodite = Landforms.within(Landforms.pathDistance(nx, ny, nz, APHRODITE, radius), 55_000.0, 35_000.0)
        val ragged = 0.75 + 0.25 * Landforms.fbm(detail + 9, px, py, pz, 1.0 / 60_000.0, 3)
        return (maxOf(ishtar, aphrodite) * ragged).coerceIn(0.0, 1.0)
    }

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val high = highland(nx, ny, nz, px, py, pz)
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 80_000.0, 4) * 400.0
        h += high * (2_800.0 + Landforms.ridged(tessera, px, py, pz, 1.0 / 7_000.0, 4) * 1_400.0)
        // Maxwell.
        h += Landforms.shield(distance(nx, ny, nz, MAXWELL, radius), 45_000.0, 6_500.0)
        for (v in VOLCANOES) h += Landforms.shield(distance(nx, ny, nz, v, radius), 70_000.0, 3_000.0, 7_000.0, 400.0)
        // Lava channels across the plains.
        h += Landforms.rivers(flows, px, py, pz, 90_000.0, 0.03, 120.0) * (1.0 - high)
        // Pancake domes, only on the plains.
        if (high < 0.5) Landforms.scattered(domes, px, py, pz, radius, 30_000.0, 0.25) { d, size, _ ->
            h += Landforms.pancake(d, 6_000.0 + 6_000.0 * size, 600.0 + 400.0 * size) * (1.0 - high * 2)
        }
        h += craters.height(px, py, pz, 1.0)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // The live volcano's lake.
        if (distance(nx, ny, nz, VOLCANOES[0], radius) < 4_500.0) return SurfaceMaterial.LAVA
        if (slope > 0.3) return SurfaceMaterial.ROCK
        if (highland(nx, ny, nz, px, py, pz) > 0.5) return SurfaceMaterial.TESSERA
        return SurfaceMaterial.BASALT
    }

    companion object {
        val ISHTAR = at(68.0, 10.0)
        val MAXWELL = at(66.0, 5.0)
        val APHRODITE = listOf(at(-5.0, 60.0), at(-3.0, 90.0), at(-8.0, 120.0), at(-12.0, 150.0))
        val VOLCANOES = listOf(at(24.0, -110.0), at(-20.0, -60.0), at(32.0, 160.0))
    }
}

/**
 * Rubra: the red world. Its two halves differ: low, smooth **northern
 * plains** a few kilometres down, and old, high, **cratered southern
 * highlands** cut by dry river channels. On the rise between them stands
 * the **Great Mount**, the tallest volcano anywhere, and in a line beside
 * it **the Three**, shields nearly as vast; east of them the **Rift**, a
 * canyon a continent long and kilometres deep. Ice caps at both poles, the
 * north ringed by dune seas. Rust-red dust over everything, rock where the
 * slopes are too steep to hold it.
 */
internal class RubraLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius, 0.8))
    private val riverSeed = Noise.hashInt(seed, 3, 0, 0)
    private val duneSeed = Noise.hashInt(seed, 4, 0, 0)

    /** 1 in the northern lowlands, 0 in the southern highlands, the boundary wandering. */
    private fun lowland(ny: Double, px: Double, py: Double, pz: Double): Double {
        val wander = Landforms.fbm(detail + 7, px, py, pz, 1.0 / 120_000.0, 3) * 0.18
        return Landforms.smooth((ny - 0.05 + wander) / 0.25)
    }

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val low = lowland(ny, px, py, pz)
        var h = 1_200.0 * (1.0 - low) - 2_500.0 * low
        h += Landforms.fbm(detail, px, py, pz, 1.0 / 30_000.0, 5) * (300.0 + 500.0 * (1.0 - low))
        h += Landforms.shield(distance(nx, ny, nz, GREAT_MOUNT, radius), 32_000.0, 19_000.0, 4_000.0, 1_500.0)
        for (v in THE_THREE) h += Landforms.shield(distance(nx, ny, nz, v, radius), 22_000.0, 9_000.0, 3_000.0, 1_200.0)
        // The bulge they stand on.
        h += Landforms.within(distance(nx, ny, nz, THE_THREE[1], radius), 60_000.0, 80_000.0) * 3_000.0
        h += Landforms.canyon(Landforms.pathDistance(nx, ny, nz, RIFT, radius), 18_000.0, 5_000.0)
        // Dry rivers in the old south.
        h += Landforms.rivers(riverSeed, px, py, pz, 40_000.0, 0.04, 250.0) * (1.0 - low)
        // Craters: many in the south, few on the young northern plains.
        h += craters.height(px, py, pz, 1.0 - 0.75 * low)
        // Polar layered caps, and the dune seas round the north one.
        val polar = abs(ny)
        if (polar > 0.94) h += Landforms.smooth((polar - 0.94) / 0.04) * 1_500.0
        if (ny > 0.85 && ny < 0.95) {
            val wind = doubleArrayOf(nz, 0.0, -nx)
            h += Landforms.dunes(duneSeed, px, py, pz, wind[0], wind[1], wind[2], 400.0, 30.0)
        }
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (abs(ny) > 0.955) return SurfaceMaterial.ICE
        if (slope > 0.28) return SurfaceMaterial.ROCK
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        if (lowland(ny, px, py, pz) < 0.5 && craters.onRim(px, py, pz, 1.0)) return SurfaceMaterial.SCREE
        // The volcanoes' dark flanks, before the dust settles on them.
        if (distance(nx, ny, nz, GREAT_MOUNT, radius) < 26_000.0) return SurfaceMaterial.BASALT
        return SurfaceMaterial.RED_DUST
    }

    companion object {
        val GREAT_MOUNT = at(18.0, -134.0)
        val THE_THREE = listOf(at(12.0, -113.0), at(1.0, -104.0), at(-9.0, -121.0))
        val RIFT = listOf(at(-7.0, -90.0), at(-9.0, -75.0), at(-12.0, -60.0), at(-10.0, -45.0), at(-5.0, -35.0))
    }
}

/**
 * Rubra's two little moons: not round at all, too small for their own
 * gravity to have pulled them so. Lumpy, cratered, grey. **Timor**, the
 * larger and nearer, is marked by one crater nearly a third its own size,
 * and scored with long parallel grooves.
 */
internal class LumpLand(seed: Int, private val radius: Double, private val lumpiness: Double, private val bigCrater: Boolean) : WorldLand {
    private val shape = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius * 30.0, 1.2).map {
        // Scaled to the body: the small classes only, shrunk to fit.
        Craters.CraterClass(it.cell / 30.0, it.chance, it.minRadius / 30.0, it.maxRadius / 30.0)
    }.drop(1))

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // A potato: big, slow swellings, up to a good share of its size.
        var h = Landforms.fbm(shape, px, py, pz, 1.0 / (radius * 1.2), 3) * radius * lumpiness
        h += craters.height(px, py, pz, 1.0)
        if (bigCrater) {
            h += Landforms.basin(distance(nx, ny, nz, STICKNEY, radius), radius * 0.45, radius * 0.12)
            h += Landforms.grooves(px, py, pz, GROOVE_AXIS, radius * 0.08, radius * 0.006) *
                (1.0 - Landforms.within(distance(nx, ny, nz, STICKNEY, radius), radius * 0.5, radius * 0.2))
        }
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial =
        if (slope > 0.35) SurfaceMaterial.ROCK else SurfaceMaterial.REGOLITH

    companion object {
        val STICKNEY = at(0.0, 50.0)
        val GROOVE_AXIS = doubleArrayOf(0.64, 0.0, 0.77)
    }
}
