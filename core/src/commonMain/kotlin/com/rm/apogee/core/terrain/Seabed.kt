package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import com.rm.apogee.core.PerThread
import com.rm.apogee.core.math.Math

/**
 * The sea floor below the datum: abyssal hills, a mid-ocean ridge with a rift, and drowned
 * volcanoes. Off the Cape it's shaped by hand within a slow sub's reach: shelf, a canyon from the
 * harbour, slope, a nodule terrace, then the trench, Terra's deepest. Farrow Seamount rises from
 * the slope with vents on its side. Only called for ground already under the sea.
 */
class Seabed(
    private val seed: Int,
    private val bodyRadius: Double,
    /** The Cape: the pad's direction, unit length, body-fixed. Null for a world with no Cape. */
    pad: Vec3? = null,
) {
    private val padUnit: Vec3? = pad?.normalized()
    private val east: Vec3? = padUnit?.let { Vec3(0.0, 1.0, 0.0).crossInPlace(it).normalizeInPlace() }
    private val north: Vec3? = padUnit?.let { it.copy().crossInPlace(east!!) }

    /** The ridge's great circle: its pole. */
    private val ridgePole: Vec3 = Vec3(
        Noise.hash(seed, 11, 0, 0) - 0.5, Noise.hash(seed, 12, 0, 0) - 0.5, Noise.hash(seed, 13, 0, 0) - 0.5,
    ).normalizeInPlace()

    /**
     * The open ocean floor at unit direction ([nx], [ny], [nz]), where [base] is the plain from the
     * world's own shaping, at or below the datum.
     */
    fun global(nx: Double, ny: Double, nz: Double, base: Double): Double {
        if (base > 0.0) return base
        val depth = -base
        var ground = base
        // Abyssal hills: none on the shelf, low and rolling on the plains.
        val deepness = Noise.smoothstep(((depth - HILLS_FROM) / HILLS_OVER).coerceIn(0.0, 1.0))
        if (deepness > 0.0) {
            val f = bodyRadius / HILL_SCALE
            ground += HILL_METRES * deepness * Noise.simplex(seed + 1, nx * f, ny * f, nz * f)
        }
        // The ridge: a broad swell up to its crest, split by the rift.
        val across = abs(nx * ridgePole.x + ny * ridgePole.y + nz * ridgePole.z) * bodyRadius
        if (across < RIDGE_HALF_WIDTH) {
            val wobble = RIDGE_WOBBLE * Noise.simplex(seed + 2, nx * 4.0, ny * 4.0, nz * 4.0)
            val x = abs(across + wobble)
            val swell = RIDGE_CREST - (RIDGE_CREST - base) * Noise.smoothstep((x / RIDGE_HALF_WIDTH).coerceIn(0.0, 1.0))
            val rift = RIFT_DEPTH * exp(-(x / RIFT_WIDTH) * (x / RIFT_WIDTH))
            ground = maxOf(ground, swell - rift)
        }
        // Seamounts: a volcano in some cells of a coarse lattice.
        ground = maxOf(ground, seamounts(nx, ny, nz, ground))
        return ground
    }

    private fun seamounts(nx: Double, ny: Double, nz: Double, ground: Double): Double {
        val cell = SEAMOUNT_CELL / bodyRadius
        val gx = kotlin.math.floor(nx / cell).toInt(); val gy = kotlin.math.floor(ny / cell).toInt(); val gz = kotlin.math.floor(nz / cell).toInt()
        var best = ground
        for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
            val cx = gx + dx; val cy = gy + dy; val cz = gz + dz
            if (Noise.hash(seed + 5, cx, cy, cz) > SEAMOUNT_ODDS) continue
            val px = (cx + Noise.hash(seed + 6, cx, cy, cz)) * cell
            val py = (cy + Noise.hash(seed + 7, cx, cy, cz)) * cell
            val pz = (cz + Noise.hash(seed + 8, cx, cy, cz)) * cell
            val ex = nx - px; val ey = ny - py; val ez = nz - pz
            val r = sqrt(ex * ex + ey * ey + ez * ez) * bodyRadius
            val base = SEAMOUNT_BASE * (0.6 + 0.6 * Noise.hash(seed + 9, cx, cy, cz))
            if (r > base) continue
            // Some reach almost to the surface, flat-topped; most stop well short.
            val top = -60.0 - 1_800.0 * Noise.hash(seed + 10, cx, cy, cz)
            val rise = top - (top - ground) * Noise.smoothstep((r / base).coerceIn(0.0, 1.0))
            best = maxOf(best, rise)
        }
        return best
    }

    // --- off the Cape ----------------------------------------------------------------

    /**
     * Metres east and north of the pad for unit direction ([nx], [ny], [nz]), into [out]. False when
     * it's far away or there's no Cape.
     */
    private fun capeOffset(nx: Double, ny: Double, nz: Double, out: DoubleArray): Boolean {
        val pad = padUnit ?: return false
        val e = east!!; val n = north!!
        val ox = nx - pad.x; val oy = ny - pad.y; val oz = nz - pad.z
        if (ox * ox + oy * oy + oz * oz > (CAPE_REACH / bodyRadius) * (CAPE_REACH / bodyRadius)) return false
        out[0] = (e.x * ox + e.y * oy + e.z * oz) * bodyRadius
        out[1] = (n.x * ox + n.y * oy + n.z * oz) * bodyRadius
        return true
    }

    private val offset = PerThread { DoubleArray(2) }

    /**
     * The Cape's sea floor over [ground]. Only where [ground] is already under the sea, and only
     * lowers it, except Farrow Seamount, which rises from the slope.
     */
    fun cape(nx: Double, ny: Double, nz: Double, ground: Double): Double {
        if (ground >= 0.0) return ground
        val at = offset.get()
        if (!capeOffset(nx, ny, nz, at)) return ground
        val x = at[0]; val y = at[1]
        var shaped = ground
        // The slope, terrace and trench, round the seaward side of the pad.
        val r = sqrt(x * x + y * y)
        // Seaward only, easing back to the open sea floor far out so the deep ends in a rise, not
        // a cliff.
        val w = seaward(x, y) * (1.0 - Noise.smoothstep(((r - OUTER_RISE) / OUTER_RISE_WIDTH).coerceIn(0.0, 1.0)))
        if (w > 0.0 && r > SHELF_EDGE) {
            val target = offshore(r)
            if (target < shaped) shaped += w * (target - shaped)
        }
        // The canyon, cut down from the harbour mouth.
        val (along, off) = canyon(x, y)
        if (along >= 0.0) {
            val floor = canyonFloor(along)
            val wall = floor + maxOf(0.0, off - CANYON_FLOOR_HALF) * CANYON_WALL
            shaped = minOf(shaped, wall)
        }
        // Farrow Seamount, flat-topped, standing up out of the slope.
        val fx = x - FARROW_EAST; val fy = y - FARROW_NORTH
        val fr = sqrt(fx * fx + fy * fy)
        if (fr < FARROW_BASE) {
            val cone = if (fr < FARROW_TOP_RADIUS) FARROW_TOP else FARROW_TOP - (fr - FARROW_TOP_RADIUS) * FARROW_FLANK
            shaped = maxOf(shaped, cone)
        }
        return minOf(shaped, -1.0)
    }

    /**
     * How far round to the sea side a point [x], [y] from the pad is, 0..1. Slope and trench lie
     * only there.
     */
    private fun seaward(x: Double, y: Double): Double {
        // The bearing from north, clockwise. The sea is to the north-west.
        val bearing = Math.toDegrees(kotlin.math.atan2(x, y))
        var off = bearing - SEAWARD_BEARING
        while (off > 180.0) off -= 360.0
        while (off < -180.0) off += 360.0
        return 1.0 - Noise.smoothstep(((abs(off) - SEAWARD_HALF) / SEAWARD_FADE).coerceIn(0.0, 1.0))
    }

    /**
     * The floor off the shelf by distance from the pad, in metres: slope, terrace, trench, then
     * ocean plain.
     */
    private fun offshore(r: Double): Double {
        val slope = -150.0 + (-TERRACE + 150.0) * Noise.smoothstep(((r - SHELF_EDGE) / (TERRACE_FROM - SHELF_EDGE)).coerceIn(0.0, 1.0))
        val trench = (TRENCH_BOTTOM + TERRACE) * exp(-((r - TRENCH_R) / TRENCH_WIDTH) * ((r - TRENCH_R) / TRENCH_WIDTH))
        val beyond = Noise.smoothstep(((r - TRENCH_R - 3_000.0) / 8_000.0).coerceIn(0.0, 1.0))
        // Past the trench, the ocean plain, a little deeper than the terrace.
        return slope + trench - beyond * 500.0
    }

    /**
     * How far along the canyon a point is, in metres, and how far off its axis. Along is -1 when
     * nowhere near.
     */
    private fun canyon(x: Double, y: Double): Pair<Double, Double> {
        var bestOff = Double.MAX_VALUE
        var bestAlong = -1.0
        var run = 0.0
        for (k in 0 until CANYON.size / 2 - 1) {
            val ax = CANYON[2 * k]; val ay = CANYON[2 * k + 1]; val bx = CANYON[2 * k + 2]; val by = CANYON[2 * k + 3]
            val dx = bx - ax; val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            val t = (((x - ax) * dx + (y - ay) * dy) / (len * len)).coerceIn(0.0, 1.0)
            val px = ax + dx * t - x; val py = ay + dy * t - y
            val off = sqrt(px * px + py * py)
            if (off < bestOff) { bestOff = off; bestAlong = run + t * len }
            run += len
        }
        return if (bestOff < CANYON_REACH) bestAlong to bestOff else -1.0 to bestOff
    }

    /**
     * The canyon floor along its length, in metres, from its head down to where it opens on the
     * slope.
     */
    private fun canyonFloor(along: Double): Double =
        CANYON_HEAD - (CANYON_HEAD - CANYON_MOUTH) * Noise.smoothstep((along / canyonLength).coerceIn(0.0, 1.0))

    private val canyonLength: Double = (0 until CANYON.size / 2 - 1).sumOf { k ->
        val dx = CANYON[2 * k + 2] - CANYON[2 * k]; val dy = CANYON[2 * k + 3] - CANYON[2 * k + 1]
        sqrt(dx * dx + dy * dy)
    }

    // --- what the floor is --------------------------------------------------------------

    /**
     * How much vent field there is at unit direction ([nx], [ny], [nz]), 0..1: the Chimneys on
     * Farrow's side, and patches along the ridge's rift.
     */
    fun ventField(nx: Double, ny: Double, nz: Double): Double {
        val at = offset.get()
        if (capeOffset(nx, ny, nz, at)) {
            val dx = at[0] - CHIMNEYS_EAST; val dy = at[1] - CHIMNEYS_NORTH
            val d = sqrt(dx * dx + dy * dy)
            if (d < CHIMNEYS_RADIUS) return 1.0 - Noise.smoothstep(d / CHIMNEYS_RADIUS)
        }
        val across = abs(nx * ridgePole.x + ny * ridgePole.y + nz * ridgePole.z) * bodyRadius
        if (across < RIFT_WIDTH) {
            val f = bodyRadius / VENT_PATCH_SCALE
            val patch = Noise.simplex(seed + 20, nx * f, ny * f, nz * f)
            if (patch > 0.55) return ((patch - 0.55) / 0.45).coerceIn(0.0, 1.0)
        }
        return 0.0
    }

    /**
     * Whether unit direction ([nx], [ny], [nz]) is on a nodule field: the Cape's terrace, and
     * patches of the deep plains.
     */
    fun nodules(nx: Double, ny: Double, nz: Double, elevation: Double): Boolean {
        if (elevation > -NODULES_BELOW) return false
        val at = offset.get()
        if (capeOffset(nx, ny, nz, at)) {
            val r = sqrt(at[0] * at[0] + at[1] * at[1])
            if (r in TERRACE_FROM..TERRACE_TO && seaward(at[0], at[1]) > 0.5) return true
        }
        val f = bodyRadius / NODULE_PATCH_SCALE
        return Noise.simplex(seed + 30, nx * f, ny * f, nz * f) > 0.35
    }

    /**
     * What the sea floor is at unit direction ([nx], [ny], [nz]), [elevation] deep and [slope]
     * steep.
     */
    fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial = when {
        ventField(nx, ny, nz) > 0.2 -> SurfaceMaterial.VENT_CRUST
        slope > STEEP -> SurfaceMaterial.BASALT
        elevation > -SHELF_SAND -> SurfaceMaterial.SAND
        nodules(nx, ny, nz, elevation) -> SurfaceMaterial.NODULES
        else -> SurfaceMaterial.OOZE
    }

    companion object {
        // The open ocean.
        const val HILLS_FROM = 400.0
        const val HILLS_OVER = 1_200.0
        const val HILL_METRES = 140.0
        const val HILL_SCALE = 6_000.0
        const val RIDGE_CREST = -1_800.0
        const val RIDGE_HALF_WIDTH = 160_000.0
        const val RIDGE_WOBBLE = 25_000.0
        const val RIFT_DEPTH = 450.0
        const val RIFT_WIDTH = 5_000.0
        const val SEAMOUNT_CELL = 90_000.0
        const val SEAMOUNT_ODDS = 0.22
        const val SEAMOUNT_BASE = 14_000.0
        const val VENT_PATCH_SCALE = 3_000.0
        const val NODULE_PATCH_SCALE = 40_000.0
        const val NODULES_BELOW = 1_800.0
        const val STEEP = 0.4
        const val SHELF_SAND = 250.0
        /** How many metres under the datum another world's shore gives way to its sea floor. */
        const val SEA_EDGE = 3.0

        // Off the Cape: metres from the pad, and depths as heights (negative).
        const val CAPE_REACH = 40_000.0
        /**
         * The bearing of open sea from the pad, degrees clockwise from north, and how far either
         * side the deep lies.
         */
        const val SEAWARD_BEARING = -45.0
        const val SEAWARD_HALF = 55.0
        const val SEAWARD_FADE = 20.0
        const val SHELF_EDGE = 7_000.0
        const val TERRACE_FROM = 16_000.0
        const val TERRACE_TO = 19_500.0
        const val TERRACE = 2_500.0
        const val TRENCH_R = 22_000.0
        const val TRENCH_WIDTH = 1_300.0
        const val TRENCH_BOTTOM = -7_000.0
        /** Past the trench, from here out, the deep rises back to the open sea floor. */
        const val OUTER_RISE = 29_000.0
        const val OUTER_RISE_WIDTH = 9_000.0

        /**
         * The canyon's axis, east and north of the pad, from its head off the harbour mouth to the
         * slope.
         */
        val CANYON = doubleArrayOf(
            1_500.0, 6_000.0,
            -500.0, 9_000.0,
            -2_500.0, 11_500.0,
            -5_500.0, 14_000.0,
            -9_500.0, 17_500.0,
        )
        const val CANYON_HEAD = -70.0
        const val CANYON_MOUTH = -1_300.0
        const val CANYON_FLOOR_HALF = 220.0
        const val CANYON_WALL = 0.7
        const val CANYON_REACH = 2_500.0

        const val FARROW_EAST = -11_500.0
        const val FARROW_NORTH = 9_000.0
        const val FARROW_TOP = -60.0
        const val FARROW_TOP_RADIUS = 450.0
        const val FARROW_FLANK = 0.62
        const val FARROW_BASE = 5_000.0

        /** The Chimneys: on Farrow's north-east side, about 900 m down. */
        const val CHIMNEYS_EAST = FARROW_EAST + 1_340.0
        const val CHIMNEYS_NORTH = FARROW_NORTH + 1_340.0
        const val CHIMNEYS_RADIUS = 260.0
    }
}
