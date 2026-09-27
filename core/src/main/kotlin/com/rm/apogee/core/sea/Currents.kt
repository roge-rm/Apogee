package com.rm.apogee.core.sea

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.Noise
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.sin

/**
 * The sea's currents: slow, broad flows across the open ocean that carry whatever floats in them.
 *
 * They come from a stream function, so the flow runs along its contours and never piles up in
 * the open sea. It's made of broad bands by latitude (westward near the equator and eastward further
 * out, the way the trade winds and the westerlies drive the real oceans), with slow meanders laid
 * over them for gyres, eddies and a few faster streams.
 *
 * Then it's scaled by how much the water there is open sea: nothing at the shore, in the shallows,
 * in a bay or harbour cut off from the ocean, or in a protected place like a launch site at sea or
 * one of the sea's named places. Near a coast the part of the flow heading into it or away from it
 * is taken out, so a current runs along the shore instead. The currents fall away with depth, so a
 * submarine near the surface feels them and the deep floor is still.
 *
 * It's a pure function of place, like the tides, so every device works out the same water.
 */
internal class Currents(
    private val body: CelestialBody,
    /** How strong, as a share of Terra's: less for a sea under thick air and a weak sun. */
    private val strength: Double,
    private val seed: Int,
    /** How open the sea is at unit direction [d], 0..1, and how deep in metres: see [Sea]. */
    private val water: (d: Vec3, out: DoubleArray) -> Unit,
    /** Just how deep the sea is at unit direction [d], in metres, which is much quicker. */
    private val depth: (d: Vec3) -> Double,
    /** Places kept calm, as unit directions and radii in metres. */
    private val calm: List<Pair<Vec3, Double>>,
) {
    private val radius = body.radius

    /** How much the water here is open sea, 0..1, by depth and by how enclosed it is. */
    private val open = Lattice(radius, SPACING, 0.0, 1, CAPACITY, "current-open:${body.id}") { d, _, out ->
        water(d, sample)
        out[0] = smooth(SHALLOW, DEEP, sample[1]) * smooth(ENCLOSED, OPEN, sample[0])
    }
    private val sample = DoubleArray(2)
    private val shareOut = DoubleArray(1)
    private val point = Vec3()
    private val east = Vec3()
    private val north = Vec3()

    /**
     * The current at body-fixed unit [direction], [below] metres under the surface, in m/s along
     * the ground (body-fixed), into [out].
     *
     * [rough] is for a map of a whole world. It goes by depth alone, not by how enclosed the water
     * is, and doesn't turn the flow along the coast, which takes rays cast across the ground and is
     * far too slow for thousands of places at once. Out in the open sea, where the map's arrows are,
     * it's the same.
     */
    fun velocity(direction: Vec3, below: Double, out: Vec3, rough: Boolean = false): Vec3 {
        out.setZero()
        if (strength <= 0.0) return out
        val fade = exp(-below.coerceAtLeast(0.0) / DEPTH_FADE)
        if (fade < 0.01) return out
        val share = (if (rough) smooth(SHALLOW, DEEP, depth(direction)) else share(direction)) * calmness(direction)
        if (share <= 0.0) return out
        // East and north here, as the spin about +Y defines them.
        east.setTo(direction.z, 0.0, -direction.x)
        if (east.lengthSq < 1e-12) east.setTo(1.0, 0.0, 0.0)
        east.normalizeInPlace()
        north.setTo(direction).crossInPlace(east)
        // The stream function's slope, from either side, and the flow along its contours: up x
        // grad, which is east by -dNorth and north by +dEast.
        val step = STEP / radius
        val dEast = (stream(direction, east, step) - stream(direction, east, -step)) / (2.0 * STEP)
        val dNorth = (stream(direction, north, step) - stream(direction, north, -step)) / (2.0 * STEP)
        var flowEast = -dNorth
        var flowNorth = dEast
        // Near a coast, only along it: the part heading toward the open sea or away from it (the
        // way the share changes fastest) goes, more of it the nearer the shore.
        val gEast = if (rough) 0.0 else shareAlong(direction, east, step)
        val gNorth = if (rough) 0.0 else shareAlong(direction, north, step)
        val g = kotlin.math.sqrt(gEast * gEast + gNorth * gNorth)
        if (g > 1e-9) {
            val across = (flowEast * gEast + flowNorth * gNorth) / g
            val cut = 1.0 - share
            flowEast -= cut * across * gEast / g
            flowNorth -= cut * across * gNorth / g
        }
        val scale = share * fade * strength
        return out.setTo(east).mulInPlace(flowEast * scale).addScaledInPlace(north, flowNorth * scale)
    }

    /** How open the sea is at [direction], 0..1. */
    private fun share(direction: Vec3): Double {
        open.sample(direction, 0.0, shareOut)
        return shareOut[0].coerceIn(0.0, 1.0)
    }

    /** The change in [share] across [axis], either side by [by] radians. */
    private fun shareAlong(direction: Vec3, axis: Vec3, by: Double): Double {
        point.setTo(direction).addScaledInPlace(axis, by).normalizeInPlace()
        val ahead = share(point)
        point.setTo(direction).addScaledInPlace(axis, -by).normalizeInPlace()
        return ahead - share(point)
    }

    /** 0 in a calm place, rising to 1 past its edge. */
    private fun calmness(direction: Vec3): Double {
        var kept = 1.0
        for ((centre, reach) in calm) {
            val distance = centre.distanceTo(direction) * radius
            if (distance < reach + CALM_EDGE) kept = minOf(kept, smooth(reach, reach + CALM_EDGE, distance))
        }
        return kept
    }

    /** The stream function at [direction] moved [by] (in radians of the body) along [axis], in m²/s. */
    private fun stream(direction: Vec3, axis: Vec3, by: Double): Double {
        point.setTo(direction).addScaledInPlace(axis, by).normalizeInPlace()
        val latitude = asin(point.y.coerceIn(-1.0, 1.0))
        // Bands: -dpsi/dnorth is the eastward flow, -cos(3 lat) of BAND_SPEED, so westward at the
        // equator and eastward past thirty degrees.
        val bands = BAND_SPEED * radius * sin(3.0 * latitude) / 3.0
        val x = point.x * radius; val y = point.y * radius; val z = point.z * radius
        val meanders = BROAD_SPEED * BROAD / (2.0 * Math.PI) * Noise.simplex(seed, x / BROAD, y / BROAD, z / BROAD) +
            EDDY_SPEED * EDDY / (2.0 * Math.PI) * Noise.simplex(seed + 17, x / EDDY, y / EDDY, z / EDDY)
        return bands + meanders
    }

    private fun smooth(from: Double, to: Double, x: Double): Double {
        val t = ((x - from) / (to - from)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    companion object {
        /** Corners of the open-sea share, in metres apart, and how many are kept. */
        const val SPACING = 3_000.0
        const val CAPACITY = 60_000

        /** The step the slope of the stream function is taken over, in metres. */
        const val STEP = 1_500.0

        /** The bands' speed and the meanders', in m/s, and the meanders' sizes, in metres. */
        const val BAND_SPEED = 0.25
        const val BROAD_SPEED = 0.45
        const val BROAD = 180_000.0
        const val EDDY_SPEED = 0.25
        const val EDDY = 60_000.0

        /** Depths, in metres, over which currents come in off the shore. */
        const val SHALLOW = 15.0
        const val DEEP = 150.0

        /**
         * How open to the sea water has to be to have currents at all, and to have them fully, as
         * the share of directions it's open to.
         */
        const val ENCLOSED = 0.35
        const val OPEN = 0.7

        /** How far past a calm place's edge the currents take to come back, in metres. */
        const val CALM_EDGE = 1_500.0

        /** Metres under the surface over which the currents fall to a third. */
        const val DEPTH_FADE = 150.0
    }
}
