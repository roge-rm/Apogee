package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Landforms.at
import com.rm.apogee.core.terrain.Landforms.distance
import kotlin.math.abs

/**
 * Aurantia: the only moon with real air, thick and orange, and the only
 * other world with seas - of liquid methane, dark and glassy. The seas lie
 * in the north, in lowlands below the datum, and lakes stud the land round
 * them. Rivers wind down to them from rugged icy highlands. Round the
 * equator lie belts of **dunes**, long parallel crests of dark organic
 * sand, all aligned with the wind. Few craters: the weather wears them away.
 */
internal class AurantiaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val seas = Noise.hashInt(seed, 2, 0, 0)
    private val rivers = Noise.hashInt(seed, 3, 0, 0)
    private val duneSeed = Noise.hashInt(seed, 4, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 5, 0, 0), radius, Craters.scaledFrom(radius, 0.15))

    /** How deep the northern basins are: negative in the seas. */
    private fun basins(ny: Double, px: Double, py: Double, pz: Double): Double {
        val north = Landforms.smooth((ny - 0.72) / 0.18)
        val lakes = Landforms.fbm(seas, px, py, pz, 1.0 / 30_000.0, 4)
        return north * (-600.0 + lakes * 350.0) + (1.0 - north) * (lakes - 0.45).coerceAtLeast(0.0) * -1_200.0
    }

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = 250.0 + Landforms.fbm(detail, px, py, pz, 1.0 / 20_000.0, 5) * 450.0
        h += basins(ny, px, py, pz)
        h += Landforms.rivers(rivers, px, py, pz, 25_000.0, 0.035, 80.0) * Landforms.smooth((ny - 0.2) / 0.3)
        // The dune belt, along the prevailing west wind.
        val equator = 1.0 - Landforms.smooth((abs(ny) - 0.3) / 0.15)
        if (equator > 0.0) h += equator * Landforms.dunes(duneSeed, px, py, pz, -nz, 0.0, nx, 1_500.0, 90.0)
        h += craters.height(px, py, pz, 1.0)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (slope > 0.3) return SurfaceMaterial.ICE
        if (abs(ny) < 0.3) return SurfaceMaterial.ORGANIC_SAND
        // Sea floors and beaches: dark sediment.
        if (elevation < 30.0) return SurfaceMaterial.ORGANIC_SAND
        return SurfaceMaterial.THOLIN
    }

    companion object {
        /** On the shore of the great northern sea. */
        val NORTH_SHORE = at(72.0, 20.0)
        /** In the equatorial dune belt. */
        val DUNES = at(5.0, -40.0)
    }
}

/**
 * Fons: small, and the brightest thing in the system - fresh ice falling
 * back as snow from its own geysers. The north is old and cratered; the
 * south smooth and young, and at its pole four long parallel fractures,
 * **the Stripes**, venting ice into space.
 */
internal class FonsLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius * 4.0, 1.0).map {
        Craters.CraterClass(it.cell / 4.0, it.chance, it.minRadius / 4.0, it.maxRadius / 4.0)
    })

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 4_000.0, 4) * 150.0
        // Cratered north, smooth south.
        h += craters.height(px, py, pz, Landforms.smooth((ny + 0.2) / 0.4))
        if (ny < -0.6) {
            for (k in 0 until STRIPES) {
                val offset = (k - 1.5) * 0.09
                val d = abs(pz / radius - offset) * radius
                val alongOk = abs(px / radius) < 0.35
                if (alongOk) h += (Landforms.canyon(d, 700.0, 250.0) + 90.0 * Landforms.within(d, 450.0, 400.0)) * Landforms.smooth((-ny - 0.6) / 0.1)
            }
        }
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial =
        if (slope > 0.35) SurfaceMaterial.ICE else SurfaceMaterial.SNOW

    companion object {
        const val STRIPES = 4
        val SOUTH_POLE = at(-88.0, 0.0)
    }
}

/**
 * Aversa: a captured wanderer, going round its planet backwards. Its
 * western half is **melon-skin**: shallow round pits packed edge to edge,
 * ridged between. The south is capped in pinkish **nitrogen ice**, streaked
 * dark where geysers blew soot out across it. Hardly a crater: it too is
 * young.
 */
internal class AversaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val melon = Noise.hashInt(seed, 2, 0, 0)
    private val streaks = Noise.hashInt(seed, 3, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 4, 0, 0), radius, Craters.scaledFrom(radius, 0.1))

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 20_000.0, 4) * 250.0
        val west = Landforms.smooth((-nx + 0.1) / 0.3) * (1.0 - Landforms.smooth((-ny - 0.3) / 0.2))
        if (west > 0.0) h += west * (Landforms.cells(melon, px, py, pz, 12_000.0) * 400.0 - 200.0)
        h += craters.height(px, py, pz, 1.0)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (ny < -0.45) {
            val px = nx * radius; val py = ny * radius; val pz = nz * radius
            // Dark plumes' fallout, streaked downwind.
            val streak = abs(Landforms.fbm(streaks, px * 0.3, py, pz * 3.0, 1.0 / 20_000.0, 2))
            return if (streak < 0.05) SurfaceMaterial.THOLIN else SurfaceMaterial.NITROGEN_ICE
        }
        return if (slope > 0.3) SurfaceMaterial.ROCK else SurfaceMaterial.ICE
    }

    companion object {
        val SOUTH_CAP = at(-50.0, 30.0)
    }
}

/**
 * Ultima, the last of the worlds: small, and far stranger than it has any
 * right to be. Its face is marked by **the Heart**, a vast plain of frozen
 * nitrogen lying low and smooth, slowly churning in great cells; along its
 * western edge rise **mountains of water ice**, blocks kilometres high
 * floating in it like icebergs. A **dark belt** of reddened ground wraps
 * the equator; the east is **bladed**, ridged like knife-edges; frost
 * whitens the north. Elsewhere, old craters.
 */
internal class UltimaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val cellSeed = Noise.hashInt(seed, 2, 0, 0)
    private val blades = Noise.hashInt(seed, 3, 0, 0)
    private val peaks = Noise.hashInt(seed, 4, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 5, 0, 0), radius, Craters.scaledFrom(radius, 0.7))

    /** 1 inside the Heart, 0 outside: two round lobes meeting in a point below. */
    fun heart(nx: Double, ny: Double, nz: Double): Double {
        val a = distance(nx, ny, nz, LOBE_WEST, radius)
        val b = distance(nx, ny, nz, LOBE_EAST, radius)
        val tip = Landforms.arcDistance(nx, ny, nz, LOBE_WEST, TIP, radius).coerceAtMost(Landforms.arcDistance(nx, ny, nz, LOBE_EAST, TIP, radius))
        val lobes = minOf(a, b) - LOBE_RADIUS
        val point = tip - LOBE_RADIUS * 0.55 * (1.0 - (distance(nx, ny, nz, TIP, radius) / (LOBE_RADIUS * 2.2)).coerceIn(0.0, 1.0))
        return 1.0 - Landforms.smooth(minOf(lobes, point) / 3_000.0)
    }

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val inHeart = heart(nx, ny, nz)
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 12_000.0, 4) * 600.0 * (1.0 - inHeart)
        h += craters.height(px, py, pz, 1.0 - inHeart)
        // The Heart: low, flat, and its cells.
        h -= inHeart * (1_800.0 - Landforms.cells(cellSeed, px, py, pz, 8_000.0) * 60.0)
        // Ice mountains along its west edge.
        val west = distance(nx, ny, nz, LOBE_WEST, radius)
        if (west > LOBE_RADIUS * 0.8 && west < LOBE_RADIUS * 1.5) {
            Landforms.scattered(peaks, px, py, pz, radius, 9_000.0, 0.5) { d, size, _ ->
                h += Landforms.mesa(d, 1_500.0 + 2_500.0 * size, 2_000.0 + 1_500.0 * size, 1_200.0)
            }
        }
        // Bladed ground in the east.
        val east = Landforms.smooth((nz - 0.3) / 0.2) * (1.0 - inHeart)
        if (east > 0.0) h += east * Landforms.ridged(blades, px * 2.0, py * 0.4, pz, 1.0 / 4_000.0, 3) * 900.0
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (heart(nx, ny, nz) > 0.5) return SurfaceMaterial.NITROGEN_ICE
        if (slope > 0.3) return SurfaceMaterial.ICE
        if (abs(ny) < 0.3) return SurfaceMaterial.THOLIN
        if (ny > 0.6) return SurfaceMaterial.NITROGEN_ICE
        return SurfaceMaterial.REGOLITH
    }

    companion object {
        val LOBE_WEST = at(25.0, 166.0)
        val LOBE_EAST = at(25.0, 188.0)
        val TIP = at(-18.0, 177.0)
        const val LOBE_RADIUS = 28_000.0
        /** The middle of the Heart. */
        val HEART = at(15.0, 177.0)
    }
}

/**
 * Portitor, Ultima's companion, half its size: grey, cratered, and girdled
 * by **the Belt**, a band of canyons and fractures running the whole way
 * round near its equator, where it once split as its insides froze. Its
 * north pole is stained **dark red**.
 */
internal class PortitorLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius, 0.9))

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 8_000.0, 4) * 400.0
        // The Belt: three parallel canyons wandering round the world.
        val wander = Landforms.fbm(detail + 9, px, py, pz, 1.0 / 40_000.0, 2) * 0.05
        for (k in -1..1) {
            val d = abs(ny - 0.05 * k - wander) * radius
            h += Landforms.canyon(d, 2_500.0, 1_500.0 - 300.0 * abs(k))
        }
        // The south is smooth plains: fewer craters.
        h += craters.height(px, py, pz, if (ny < -0.1) 0.35 else 1.0)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (ny > 0.8) return SurfaceMaterial.THOLIN
        if (slope > 0.3) return SurfaceMaterial.ROCK
        return SurfaceMaterial.REGOLITH
    }

    companion object {
        val BELT = at(0.0, 0.0)
    }
}
