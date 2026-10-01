package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * When to leave one planet for another and what it costs, by a Hohmann transfer between circular
 * orbits. A rule of thumb, good to a few days and a few percent.
 */
class TransferWindow(
    /**
     * How far the target is ahead of the departure planet round the star now, in radians (0..2 pi).
     */
    val phase: Double,
    /** Where it has to be at departure, in radians. */
    val phaseNeeded: Double,
    /** Seconds to the next departure. */
    val waitFor: Double,
    /** Seconds the crossing takes. */
    val flight: Double,
    /** Speed to add leaving from [parkedAt] metres from the planet's centre, in m/s. */
    val departure: Double,
    /** Speed to lose for a low orbit on arrival, in m/s. */
    val arrival: Double,
    /** Seconds between windows. */
    val synodic: Double,
) {
    companion object {
        /**
         * From planet [fromId], parked [parkedAt] metres from its centre, to planet [toId] at
         * [time]. Null unless both are different planets of the same star.
         */
        fun between(system: SolarSystem, fromId: String, toId: String, time: Double, parkedAt: Double): TransferWindow? {
            if (fromId == toId) return null
            val from = system.body(fromId)
            val to = system.body(toId)
            val starId = from.parentId ?: return null
            if (to.parentId != starId) return null
            val mu = system.body(starId).gravitationalParameter
            val r1 = system.positionOf(fromId, time).subInPlace(system.positionOf(starId, time))
            val r2 = system.positionOf(toId, time).subInPlace(system.positionOf(starId, time))
            val a1 = from.orbit?.semiMajorAxis ?: return null
            val a2 = to.orbit?.semiMajorAxis ?: return null
            val transfer = (a1 + a2) / 2.0
            val flight = PI * sqrt(transfer * transfer * transfer / mu)
            val n1 = sqrt(mu / (a1 * a1 * a1))
            val n2 = sqrt(mu / (a2 * a2 * a2))

            // Angles measured in the departure planet's direction of travel.
            val normal = from.orbit!!.angularMomentum.normalized()
            val phase = angle(r1, r2, normal)
            val needed = wrap(PI - n2 * flight)
            val closing = n2 - n1
            val synodic = 2.0 * PI / abs(closing)
            val wait = wrap(if (closing < 0.0) phase - needed else needed - phase) / abs(closing)

            val v1 = sqrt(mu / a1)
            val v2 = sqrt(mu / a2)
            val leave = abs(sqrt(mu * (2.0 / a1 - 1.0 / transfer)) - v1)
            val come = abs(v2 - sqrt(mu * (2.0 / a2 - 1.0 / transfer)))
            val muFrom = from.gravitationalParameter
            val park = parkedAt.coerceAtLeast(from.radius)
            val departure = sqrt(leave * leave + 2.0 * muFrom / park) - sqrt(muFrom / park)
            val muTo = to.gravitationalParameter
            val low = to.radius * 1.2
            val arrival = sqrt(come * come + 2.0 * muTo / low) - sqrt(muTo / low)
            return TransferWindow(phase, needed, wait, flight, departure, arrival, synodic)
        }

        /** Angle from [a] to [b] about [normal], 0..2 pi. */
        private fun angle(a: Vec3, b: Vec3, normal: Vec3): Double =
            wrap(atan2(a.cross(b) dot normal, a dot b))

        private fun wrap(x: Double): Double {
            val y = x % (2.0 * PI)
            return if (y < 0.0) y + 2.0 * PI else y
        }
    }
}
