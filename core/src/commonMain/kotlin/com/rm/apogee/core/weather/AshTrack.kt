package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.sea.DetMath
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The way an eruption's cloud goes over the ground: a line of [segments] great-circle pieces of
 * [step] metres from the vent, each turned toward the wind at its start. Made once per eruption
 * and never changed after, so one can be read from any thread.
 *
 * Plain arithmetic past its making, and DetMath in it, so server and replicas agree.
 */
class AshTrack private constructor(
    /** Metres over the ground per piece. */
    val step: Double,
    /** The world's radius, metres. */
    val radius: Double,
    /** x y z of each piece's start and the last one's end, unit and body-fixed; the first is the vent. */
    private val points: DoubleArray,
    /** x y z of each piece's unit way at its start, and its side (point x way). */
    private val ways: DoubleArray,
    private val sides: DoubleArray,
    /**
     * x y z at each point of the unit way halfway between the pieces meeting there: the line
     * across the track at a point, square to this, splits the ground between the two pieces.
     */
    private val splits: DoubleArray,
) {
    val segments: Int get() = ways.size / 3

    /** Metres from the vent to the end of the last piece. */
    val length: Double get() = step * segments

    private fun piece(s: Double): Int = floor(s / step).toInt().coerceIn(0, segments - 1)

    /** Unit point [s] metres along from the vent, into [out]; before the vent or past the end, on along the end pieces. */
    fun pointAt(s: Double, out: Vec3): Vec3 {
        val k = piece(s)
        val a = (s - k * step) / radius
        val c = DetMath.cos(a); val n = DetMath.sin(a)
        val i = 3 * k
        return out.setTo(points[i] * c + ways[i] * n, points[i + 1] * c + ways[i + 1] * n, points[i + 2] * c + ways[i + 2] * n)
    }

    /** The unit way the track goes [s] metres along, into [out]: blended between pieces, so it turns smoothly. */
    fun wayAt(s: Double, out: Vec3): Vec3 = blended(ways, s, out)

    /** The unit side of the track [s] metres along (point x way), into [out], blended between pieces. */
    fun sideAt(s: Double, out: Vec3): Vec3 = blended(sides, s, out)

    private fun blended(v: DoubleArray, s: Double, out: Vec3): Vec3 {
        val x = (s / step - 0.5).coerceIn(0.0, (segments - 1).toDouble())
        val k = kotlin.math.min(x.toInt(), kotlin.math.max(segments - 2, 0))
        val f = if (segments < 2) 0.0 else x - k
        val i = 3 * k
        val j = if (segments < 2) i else i + 3
        out.setTo(v[i] + (v[j] - v[i]) * f, v[i + 1] + (v[j + 1] - v[i + 1]) * f, v[i + 2] + (v[j + 2] - v[i + 2]) * f)
        return out.normalizeInPlace()
    }

    /**
     * Where unit [up] is against the track, into [out]: [0] metres along it from the vent (below
     * 0 behind the vent, past [length] beyond its end) and [1] metres off it. Each piece owns the
     * ground between the split lines at its ends, and along goes evenly from one to the other, so
     * both change smoothly from piece to piece. Each piece is taken as flat, which is close enough
     * at its length.
     */
    fun project(up: Vec3, out: DoubleArray): DoubleArray {
        val last = segments - 1
        var bestOff = Double.MAX_VALUE
        var along = 0.0
        for (k in 0..last) {
            val i = 3 * k; val j = i + 3
            // The split planes all pass through the world's centre, so only the near half counts.
            if (up.x * points[i] + up.y * points[i + 1] + up.z * points[i + 2] <= 0.0 ||
                up.x * points[j] + up.y * points[j + 1] + up.z * points[j + 2] <= 0.0) continue
            val dx = up.x - points[i]; val dy = up.y - points[i + 1]; val dz = up.z - points[i + 2]
            val f0 = (dx * splits[i] + dy * splits[i + 1] + dz * splits[i + 2]) * radius
            if (k > 0 && f0 < 0.0) continue
            val ex = up.x - points[j]; val ey = up.y - points[j + 1]; val ez = up.z - points[j + 2]
            val f1 = (ex * splits[j] + ey * splits[j + 1] + ez * splits[j + 2]) * radius
            if (k < last && f1 >= 0.0) continue
            if (f0 - f1 < 1e-6) continue
            val off = kotlin.math.abs((dx * sides[i] + dy * sides[i + 1] + dz * sides[i + 2]) * radius)
            if (off < bestOff) { bestOff = off; along = (k + f0 / (f0 - f1)) * step }
        }
        if (bestOff == Double.MAX_VALUE) {
            // Far off on the inside of a tight bend, where the split lines cross, or on the far side
            // of the world: the nearest point, by straight line through the world so behind is far.
            for (k in 0..last) {
                val i = 3 * k
                val dx = up.x - points[i]; val dy = up.y - points[i + 1]; val dz = up.z - points[i + 2]
                val a = (dx * ways[i] + dy * ways[i + 1] + dz * ways[i + 2]) * radius
                val c = (dx * sides[i] + dy * sides[i + 1] + dz * sides[i + 2]) * radius
                val h = (dx * points[i] + dy * points[i + 1] + dz * points[i + 2]) * radius
                val t = a.coerceIn(0.0, step)
                val off = sqrt((a - t) * (a - t) + c * c + h * h)
                if (off < bestOff) { bestOff = off; along = k * step + t }
            }
        }
        out[0] = along; out[1] = bestOff
        return out
    }

    companion object {
        /** Pieces per track. */
        const val SEGMENTS = 24
        /** The most a track turns from one piece to the next, radians. */
        const val MOST_TURN = 0.25
        private val TURN_COS = DetMath.cos(MOST_TURN)
        private val TURN_SIN = DetMath.sin(MOST_TURN)

        /**
         * A track from unit [vent] going first along unit tangent [first], [segments] pieces of
         * [step] metres on a world of [radius]. [wind] gives the wind at a unit point [s] metres
         * along into its out and returns it, or null to go on straight.
         */
        fun trace(vent: Vec3, first: Vec3, step: Double, radius: Double, segments: Int = SEGMENTS,
                  wind: (point: Vec3, s: Double, out: Vec3) -> Vec3?): AshTrack {
            val points = DoubleArray(3 * (segments + 1))
            val ways = DoubleArray(3 * segments)
            val sides = DoubleArray(3 * segments)
            val splits = DoubleArray(3 * (segments + 1))
            val p = Vec3().setTo(vent).normalizeInPlace()
            val w = Vec3().setTo(first).addScaledInPlace(p, -(first dot p)).normalizeInPlace()
            val next = Vec3(); val blown = Vec3(); val bend = Vec3(); val came = Vec3()
            val c = DetMath.cos(step / radius); val n = DetMath.sin(step / radius)
            for (k in 0 until segments) {
                val i = 3 * k
                val side = Vec3().setTo(p).crossInPlace(w).normalizeInPlace()
                points[i] = p.x; points[i + 1] = p.y; points[i + 2] = p.z
                ways[i] = w.x; ways[i + 1] = w.y; ways[i + 2] = w.z
                sides[i] = side.x; sides[i + 1] = side.y; sides[i + 2] = side.z
                if (k == 0) { splits[0] = w.x; splits[1] = w.y; splits[2] = w.z }
                // Along the great circle to the next point, carrying the way with it.
                next.setTo(p).mulInPlace(c).addScaledInPlace(w, n).normalizeInPlace()
                w.mulInPlace(c).addScaledInPlace(p, -n)
                p.setTo(next)
                w.addScaledInPlace(p, -(w dot p)).normalizeInPlace()
                came.setTo(w)
                if (k < segments - 1) turn(wind(p, (k + 1) * step, blown), p, w, bend)
                came.addInPlace(w).normalizeInPlace()
                val j = i + 3
                points[j] = p.x; points[j + 1] = p.y; points[j + 2] = p.z
                splits[j] = came.x; splits[j + 1] = came.y; splits[j + 2] = came.z
            }
            return AshTrack(step, radius, points, ways, sides, splits)
        }

        /** Turns unit tangent [w] at unit [p] toward [got] (or not, for null or still air), by [MOST_TURN] at most. */
        private fun turn(got: Vec3?, p: Vec3, w: Vec3, bend: Vec3) {
            if (got == null) return
            got.addScaledInPlace(p, -(got dot p))
            val speed = got.length
            if (speed < 1e-3) return
            got.mulInPlace(1.0 / speed)
            val cos = got dot w
            if (cos >= TURN_COS) { w.setTo(got); return }
            // Turning too hard: only [MOST_TURN] toward it.
            bend.setTo(got).addScaledInPlace(w, -cos)
            if (bend.lengthSq < 1e-18) bend.setTo(p).crossInPlace(w)
            bend.normalizeInPlace()
            w.mulInPlace(TURN_COS).addScaledInPlace(bend, TURN_SIN).normalizeInPlace()
        }
    }
}
