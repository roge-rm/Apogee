package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.StateVector
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A burn planned for a craft: at universe [time], this much change of velocity along its orbit's
 * own axes there. [prograde] is along its motion, [normal] is out of its orbit's plane (the
 * navball's normal), and [radial] is straight out from the body, each in m/s.
 */
@Serializable
data class PlannedBurn(
    val time: Double,
    val prograde: Double = 0.0,
    val normal: Double = 0.0,
    val radial: Double = 0.0,
) {
    /** How much in total, in m/s. */
    val deltaV: Double get() = sqrt(prograde * prograde + normal * normal + radial * radial)
}

/** The sums a planned burn is flown by. See [PlannedBurn]. */
object Burns {

    /** With this little left, in m/s, the burn is done. */
    const val DONE = 0.1

    /**
     * Opened this long before a burn starts, in seconds. From then on its direction is fixed in
     * space and whatever the engines give counts toward it.
     */
    const val WINDOW = 60.0

    /** The most burns planned for one craft at once. */
    const val MOST = 8

    /**
     * [burn] as a change of velocity in the world's axes, for a craft on [orbit], using its axes
     * where the craft will be at the burn's time.
     */
    fun vectorOf(burn: PlannedBurn, orbit: Orbit, out: Vec3 = Vec3()): Vec3 =
        vectorAt(burn, orbit.stateAt(burn.time), out)

    /** [burn]'s change of velocity for a craft at [state], in the world's axes. */
    fun vectorAt(burn: PlannedBurn, state: StateVector, out: Vec3 = Vec3()): Vec3 {
        val prograde = state.velocity.normalized()
        val normal = state.position.cross(prograde)
        if (normal.lengthSq < 1e-12) normal.setTo(1.0, 0.0, 0.0)
        normal.normalizeInPlace()
        val radial = prograde.cross(normal).normalizeInPlace()
        return out.setTo(prograde).mulInPlace(burn.prograde)
            .addScaledInPlace(normal, burn.normal)
            .addScaledInPlace(radial, burn.radial)
    }

    /** The orbit after [burn], done all at once at its time. This is what the map draws. */
    fun after(burn: PlannedBurn, orbit: Orbit): Orbit {
        val state = orbit.stateAt(burn.time)
        val velocity = state.velocity.copy().addInPlace(vectorAt(burn, state))
        return Orbit(state.position.copy(), velocity, orbit.mu, burn.time)
    }

    /**
     * How long [deltaV] takes at full throttle, in seconds, stage by stage from the one lit now,
     * the way [CraftStats] has them. Or +inf if the craft can't give that much.
     */
    fun duration(vessel: Vessel, deltaV: Double): Double = duration(CraftStats.analyzeLive(vessel), deltaV)

    fun duration(stages: List<com.rm.apogee.core.craft.StageStats>, deltaV: Double): Double {
        var left = deltaV
        var seconds = 0.0
        for (stage in stages) {
            if (left <= 0.0) break
            if (stage.thrustVacuum <= 0.0 || stage.deltaVVacuum <= 0.0) continue
            if (left >= stage.deltaVVacuum) {
                seconds += stage.burnTime
                left -= stage.deltaVVacuum
                continue
            }
            // Part of this stage: exhaust speed from its own mass ratio.
            val exhaust = stage.deltaVVacuum / ln(stage.startMass / stage.endMass)
            val burnt = stage.startMass * (1.0 - exp(-left / exhaust))
            seconds += burnt * exhaust / stage.thrustVacuum
            left = 0.0
        }
        return if (left > 1e-6) Double.POSITIVE_INFINITY else seconds
    }

    /**
     * When to light up for [burn] taking [duration]. Half of it goes before, so it's centred on its
     * time.
     */
    fun startOf(burn: PlannedBurn, duration: Double): Double =
        burn.time - (if (duration.isFinite()) duration * 0.5 else 0.0)
}
