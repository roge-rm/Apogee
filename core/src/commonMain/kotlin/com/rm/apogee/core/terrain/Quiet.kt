package com.rm.apogee.core.terrain

import kotlin.math.sqrt

/**
 * Where a world's close-up relief stays out: the ground round each test site and prop keeps the
 * shape it had, so pads, bases and props sit where they were put. 0 on a spot, rising to 1 past its
 * blend.
 */
internal class Quiet(spots: List<Worlds.Spot>, private val radius: Double) {
    private val count = spots.size
    private val xs = DoubleArray(count) { spots[it].direction[0] }
    private val ys = DoubleArray(count) { spots[it].direction[1] }
    private val zs = DoubleArray(count) { spots[it].direction[2] }
    private val flats = DoubleArray(count) { spots[it].flat }
    private val blends = DoubleArray(count) { spots[it].blend }

    /** Past this dot product with a spot, the point is out of its reach. One multiply-add to rule each out. */
    private val reach = DoubleArray(count) { kotlin.math.cos(((spots[it].flat + spots[it].blend) / radius).coerceAtMost(3.0)) }

    /** How much relief the point gets, 0..1. */
    fun at(nx: Double, ny: Double, nz: Double): Double {
        var q = 1.0
        for (i in 0 until count) {
            if (nx * xs[i] + ny * ys[i] + nz * zs[i] < reach[i]) continue
            val dx = nx - xs[i]; val dy = ny - ys[i]; val dz = nz - zs[i]
            val d = sqrt(dx * dx + dy * dy + dz * dz) * radius
            q = minOf(q, Landforms.smooth((d - flats[i]) / blends[i]))
        }
        return q
    }

    /** Whether the point is within [metres] of any spot. */
    fun near(nx: Double, ny: Double, nz: Double, metres: Double): Boolean {
        for (i in 0 until count) {
            val dx = nx - xs[i]; val dy = ny - ys[i]; val dz = nz - zs[i]
            if (sqrt(dx * dx + dy * dy + dz * dz) * radius < metres) return true
        }
        return false
    }
}
