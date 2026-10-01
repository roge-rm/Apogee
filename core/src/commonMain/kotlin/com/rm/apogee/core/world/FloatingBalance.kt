package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/**
 * How a craft floats at rest: its height in still water and how it lies, displacing its weight with
 * the push right under its centre of mass.
 *
 * A craft that sleeps afloat, or is founded on the sea, rides the sea as it lay at that moment,
 * which in a swell can be halfway through a roll. So it's set at its balance first. A craft loaded
 * to one side keeps its list, since that's its balance.
 *
 * Uses the same cells the hydrostatics floats it on, against a flat sea. At each attitude it sits
 * where it displaces its weight, and it's turned the way the water turns it until the push is
 * right under its centre.
 */
internal class FloatingBalance {
    /** The craft's centre of mass above the water, in metres, once [solve] has found it. */
    var height = 0.0
        private set

    /** How it lies at its balance, in world axes, once [solve] has found it. */
    val orientation = Quat()

    private var cells = DoubleArray(0)
    private var count = 0
    private val across = Vec3()
    private val along = Vec3()
    private val scratch = Vec3()
    private val turn = Quat()
    private val residual = DoubleArray(2)
    private val trial = DoubleArray(2)
    private val jacobian = Array(2) { DoubleArray(2) }

    /**
     * Finds [vessel]'s balance in water of [density] whose surface faces [up] (world, unit),
     * starting from how it lies now with its centre [from] metres above the surface. False if it
     * doesn't float at the surface or the balance is far from how it lies.
     */
    fun solve(vessel: Vessel, up: Vec3, density: Double, from: Double): Boolean {
        gather(vessel)
        if (count == 0) return false
        weighs = vessel.body.mass / density
        reach = vessel.contactRadius + 1.0
        // Two level directions to turn it about.
        across.setTo(if (abs(up.x) < 0.9) Vec3.unitX() else Vec3.unitY()).crossInPlace(up).normalizeInPlace()
        along.setTo(up).crossInPlace(across)
        val start = vessel.body.orientation
        val x = DoubleArray(2)
        if (!moments(start, up, x, residual)) return false
        var error = hypot(residual[0], residual[1])
        repeat(ITERATIONS) {
            if (error < MOMENT_DONE) return finish(vessel, start, up, x, from)
            // How the moments change with each turn, to find the turn that zeroes them.
            for (j in 0 until 2) {
                trial[0] = x[0]; trial[1] = x[1]
                trial[j] += TURN_STEP
                if (!moments(start, up, trial, scratchResidual)) return false
                for (i in 0 until 2) jacobian[i][j] = (scratchResidual[i] - residual[i]) / TURN_STEP
            }
            val det = jacobian[0][0] * jacobian[1][1] - jacobian[0][1] * jacobian[1][0]
            // The moments are how the water turns it. Near a stable balance turning that way eases
            // them and Newton's step is safe. Further off Newton can find the craft on its side, so
            // it's rolled the way the water would, a bit at a time, until it settles.
            val settles = jacobian[0][0] < 0.0 && jacobian[1][1] < 0.0 && det > 1e-12
            var d0: Double
            var d1: Double
            if (settles) {
                d0 = -(jacobian[1][1] * residual[0] - jacobian[0][1] * residual[1]) / det
                d1 = -(jacobian[0][0] * residual[1] - jacobian[1][0] * residual[0]) / det
            } else {
                d0 = residual[0] / error * MOST_TURN
                d1 = residual[1] / error * MOST_TURN
            }
            val size = hypot(d0, d1)
            if (size > MOST_TURN) { d0 *= MOST_TURN / size; d1 *= MOST_TURN / size }
            // Only a step that leaves it nearer balance, or still turning the same way.
            var share = 1.0
            while (true) {
                trial[0] = x[0] + share * d0; trial[1] = x[1] + share * d1
                if (moments(start, up, trial, scratchResidual)) {
                    val nearer = hypot(scratchResidual[0], scratchResidual[1]) < error
                    val sameWay = !settles && scratchResidual[0] * residual[0] + scratchResidual[1] * residual[1] > 0.0
                    if (nearer || sameWay) break
                }
                share *= 0.5
                if (share < SMALLEST_SHARE) return false
            }
            x[0] = trial[0]; x[1] = trial[1]
            residual[0] = scratchResidual[0]; residual[1] = scratchResidual[1]
            error = hypot(residual[0], residual[1])
            if (share * size < TURN_DONE) return finish(vessel, start, up, x, from)
        }
        return false
    }

    private fun finish(vessel: Vessel, start: Quat, up: Vec3, x: DoubleArray, from: Double): Boolean {
        pose(start, x, orientation)
        height = heaveFor(orientation, up)
        if (height.isNaN() || !partlyWet) return false
        // Nor one far above or below where it is, so a submarine hanging at depth stays there.
        if (abs(height - from) > max(MOST_HEAVE, HEAVE_SHARE * vessel.contactRadius)) return false
        return angleBetween(start, orientation, vessel) < MOST_CHANGE
    }

    private var weighs = 0.0
    private var reach = 0.0
    private var partlyWet = false
    private val scratchResidual = DoubleArray(2)
    private val probe = Quat()

    /**
     * The water's moment about the centre of mass each way, over its weight: how far off centre the
     * push is, in metres. With it turned by [x] from [start] and sat where it displaces its weight.
     * False if there's no such height.
     */
    private fun moments(start: Quat, up: Vec3, x: DoubleArray, out: DoubleArray): Boolean {
        pose(start, x, probe)
        val h = heaveFor(probe, up)
        if (h.isNaN()) return false
        displace(probe, up, h)
        out[0] = momentB / weighs
        out[1] = -momentA / weighs
        return true
    }

    /**
     * The height of its centre above the water, turned to [q], at which it displaces its weight,
     * or NaN if it can't. Found by halving.
     */
    private fun heaveFor(q: Quat, up: Vec3): Double {
        var low = -reach
        var high = reach
        if (displace(q, up, low) < weighs) return Double.NaN
        repeat(HEAVE_HALVINGS) {
            val middle = 0.5 * (low + high)
            if (displace(q, up, middle) > weighs) low = middle else high = middle
        }
        val h = 0.5 * (low + high)
        displace(q, up, h)
        return h
    }

    private var momentA = 0.0
    private var momentB = 0.0

    /**
     * Each cell's offset from the centre of mass in the craft's own axes, its volume, and its size.
     */
    private fun gather(vessel: Vessel) {
        count = 0
        for (def in vessel.defs) count += def.volumeCells.size
        if (cells.size < count * STRIDE) cells = DoubleArray(count * STRIDE)
        var c = 0
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (def.volumeCells.isEmpty()) continue
            val volume = def.displacedVolume / def.volumeCells.size
            val size = def.volumeCellSize
            val rotation = vessel.design.parts[i].rotation
            for (cell in def.volumeCells) {
                vessel.partPointOffsetWorld(i, cell, scratch)
                vessel.body.orientation.inverseRotate(scratch, scratch)
                val o = c * STRIDE
                cells[o] = scratch.x; cells[o + 1] = scratch.y; cells[o + 2] = scratch.z
                cells[o + 3] = volume
                // The cell's edges in the craft's axes, for how tall it stands however it's turned.
                for ((k, edge) in listOf(Vec3.unitX(), Vec3.unitY(), Vec3.unitZ()).withIndex()) {
                    rotation.rotate(edge, scratch)
                    val length = when (k) { 0 -> size.x; 1 -> size.y; else -> size.z }
                    cells[o + 4 + k * 3] = scratch.x * length
                    cells[o + 5 + k * 3] = scratch.y * length
                    cells[o + 6 + k * 3] = scratch.z * length
                }
                c++
            }
        }
    }

    private fun pose(start: Quat, x: DoubleArray, out: Quat): Quat {
        scratch.setTo(across).mulInPlace(x[0]).addScaledInPlace(along, x[1])
        val angle = scratch.length
        if (angle < 1e-12) return out.setTo(start)
        Quat.fromAxisAngle(scratch.mulInPlace(1.0 / angle), angle, turn)
        return out.setTo(turn).mulInPlace(start).normalizeInPlace()
    }

    /**
     * The water displaced with it turned to [q] and its centre [h] metres above the surface, and
     * that water's moment about its centre each way into [momentA] and [momentB].
     */
    private fun displace(q: Quat, up: Vec3, h: Double): Double {
        // The surface's up in the craft's own axes, so the cells needn't be turned.
        q.inverseRotate(up, u)
        q.inverseRotate(across, a)
        q.inverseRotate(along, b)
        var volume = 0.0
        momentA = 0.0
        momentB = 0.0
        partlyWet = false
        for (c in 0 until count) {
            val o = c * STRIDE
            val px = cells[o]; val py = cells[o + 1]; val pz = cells[o + 2]
            val above = h + px * u.x + py * u.y + pz * u.z
            val tall = abs(cells[o + 4] * u.x + cells[o + 5] * u.y + cells[o + 6] * u.z) +
                abs(cells[o + 7] * u.x + cells[o + 8] * u.y + cells[o + 9] * u.z) +
                abs(cells[o + 10] * u.x + cells[o + 11] * u.y + cells[o + 12] * u.z)
            // The same share under water the hydrostatics takes.
            val fraction = (-above / max(tall, 1e-6) + 0.5).coerceIn(0.0, 1.0)
            if (fraction <= 0.0) continue
            if (fraction < 1.0) partlyWet = true
            val v = cells[o + 3] * fraction
            volume += v
            momentA += v * (px * a.x + py * a.y + pz * a.z)
            momentB += v * (px * b.x + py * b.y + pz * b.z)
        }
        return volume
    }

    private val u = Vec3()
    private val a = Vec3()
    private val b = Vec3()

    /** How far [end]'s up is from [start]'s, in degrees, for [vessel]'s design up. */
    private fun angleBetween(start: Quat, end: Quat, vessel: Vessel): Double {
        val was = start.rotate(vessel.design.orientation.up, Vec3())
        val now = end.rotate(vessel.design.orientation.up, Vec3())
        return acos((was dot now).coerceIn(-1.0, 1.0)) * 180.0 / PI
    }

    private companion object {
        /** Per cell: offset (3), volume, and its three edges in the craft's axes (9). */
        const val STRIDE = 13
        const val ITERATIONS = 60
        const val HEAVE_HALVINGS = 40

        /** The nudge for working out how the moments change, in radians. */
        const val TURN_STEP = 1e-3

        /** The most one step turns it, in radians. */
        const val MOST_TURN = 0.1

        /** How small a share of a step is still worth taking. */
        const val SMALLEST_SHARE = 1.0 / 64.0

        /**
         * Close enough: the push this near under its centre in metres, or a step this small in
         * radians.
         */
        const val MOMENT_DONE = 1e-4
        const val TURN_DONE = 1e-6

        /**
         * Further than this from where it floats, in metres or as a share of its reach, isn't a
         * balance.
         */
        const val MOST_HEAVE = 1.0
        const val HEAVE_SHARE = 0.3

        /** Further than this from how it lay, in degrees, it isn't a balance it was near. */
        const val MOST_CHANGE = 30.0
    }
}
