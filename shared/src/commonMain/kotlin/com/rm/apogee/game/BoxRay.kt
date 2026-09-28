package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3

/** Where a line of sight meets a box, for picking parts under a finger. */
object BoxRay {

    /**
     * The distance along the ray from [origin] in [direction] (unit) to where it enters the box
     * centred on the origin with [half] extents, grown by [padding] each way, both in the box's own
     * frame. Null for a miss or a box behind the ray, and 0 from inside it.
     */
    fun hit(origin: Vec3, direction: Vec3, half: Vec3, padding: Double = 0.0): Double? {
        var near = Double.NEGATIVE_INFINITY
        var far = Double.POSITIVE_INFINITY
        for (axis in 0..2) {
            val o = when (axis) { 0 -> origin.x; 1 -> origin.y; else -> origin.z }
            val d = when (axis) { 0 -> direction.x; 1 -> direction.y; else -> direction.z }
            val h = when (axis) { 0 -> half.x; 1 -> half.y; else -> half.z } + padding
            if (kotlin.math.abs(d) < 1e-12) {
                if (o < -h || o > h) return null
                continue
            }
            var t0 = (-h - o) / d
            var t1 = (h - o) / d
            if (t0 > t1) { val t = t0; t0 = t1; t1 = t }
            if (t0 > near) near = t0
            if (t1 < far) far = t1
            if (near > far) return null
        }
        if (far < 0.0) return null
        return maxOf(near, 0.0)
    }
}
