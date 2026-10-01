package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.LiftGas

/**
 * The lift of gas cells in air, as [Hydrostatics] is the lift of hulls in water.
 *
 * Each cell lifts by the weight of air it displaces less its own gas, straight up from where it is.
 * Air thins with height, so a light craft settles where lift matches weight: above it, it sinks;
 * below it, it rises. Ballonets take in air to lower that height and let it out to raise it.
 * Lifting per cell, not from the craft's middle, keeps a gondola upright under its envelope.
 */
class Aerostatics {
    private val gravity = Vec3()
    private val offset = Vec3()
    private val point = Vec3()
    private val force = Vec3()

    /** Lifts [vessel]'s gas cells for this tick. */
    fun apply(vessel: Vessel, attractor: CelestialBody) {
        vessel.gasLift = 0.0
        val air = attractor.atmosphere ?: return
        val defs = vessel.defs
        val trim = 1.0 - BALLONET_MOST * vessel.ballonet
        for (i in defs.indices) {
            val gas = defs[i].module<LiftGas>() ?: continue
            if (vessel.isBroken(i)) continue
            vessel.partOffsetWorld(i, offset)
            point.setTo(vessel.body.position).addInPlace(offset)
            val density = air.densityAt(attractor.altitudeOf(point))
            if (density <= 0.0) continue
            attractor.gravityAt(point, gravity)
            val lift = gas.volume * density * (1.0 - GAS_SHARE) * trim
            // Against the pull, wherever that is.
            force.setTo(gravity).mulInPlace(-lift)
            vessel.body.applyForceAtOffset(force, offset)
            vessel.recordForce(i, force)
            vessel.gasLift += force.length
        }
    }

    companion object {
        /**
         * A light gas's density as a share of the air round it: about hydrogen's and helium's in
         * Terra's air. Same everywhere, since the gas matches the air's pressure and warmth.
         */
        const val GAS_SHARE = 0.14

        /** The share of a cell's lift a full ballonet takes away. */
        const val BALLONET_MOST = 0.4

        /**
         * The ballonet [vessel] wants, 0..1, to hold a height it's [below] metres under (negative
         * above) while climbing at [climb] m/s in gravity [g], over a tick of [dt] seconds. It's
         * damped so it settles, and slowly learns whatever else holds it off the height.
         */
        fun trimFor(vessel: Vessel, below: Double, climb: Double, g: Double, dt: Double): Double {
            val empty = vessel.gasLift / (1.0 - BALLONET_MOST * vessel.ballonet)
            if (empty <= 0.0) return vessel.ballonet
            // Learn only near the height, so a long climb doesn't wind it up.
            if (kotlin.math.abs(below) < LEARN_WITHIN) {
                vessel.heightIntegral = (vessel.heightIntegral + below * HEIGHT_INTEGRAL * dt).coerceIn(-MOST_INTEGRAL, MOST_INTEGRAL)
            }
            val accel = (below * HEIGHT_SPRING - climb * HEIGHT_DAMPING + vessel.heightIntegral).coerceIn(-MOST_ACCEL, MOST_ACCEL)
            val lift = vessel.body.mass * (g + accel)
            return ((1.0 - lift / empty) / BALLONET_MOST).coerceIn(0.0, 1.0)
        }

        /** How hard a height is held: m/s² per metre off it, per m/s of climb, and the most. */
        const val HEIGHT_SPRING = 0.004
        const val HEIGHT_DAMPING = 0.09
        const val MOST_ACCEL = 0.4

        /**
         * How fast the height hold learns what it's missing, m/s² per metre-second, and the most.
         */
        const val HEIGHT_INTEGRAL = 0.0001
        const val MOST_INTEGRAL = 0.15

        /** Metres off the height within which the hold learns. */
        const val LEARN_WITHIN = 20.0

        /** Ballonet within this of the fill wanted is near enough. */
        const val TRIM_NEAR = 0.005

        /** What [vessel]'s gas cells could lift at [altitude] with empty ballonets, in newtons. */
        fun liftAt(vessel: Vessel, attractor: CelestialBody, altitude: Double): Double {
            val air = attractor.atmosphere ?: return 0.0
            val volume = vessel.defs.indices.sumOf { i -> if (vessel.isBroken(i)) 0.0 else vessel.defs[i].module<LiftGas>()?.volume ?: 0.0 }
            val g = attractor.gravitationalParameter / ((attractor.radius + altitude) * (attractor.radius + altitude))
            return volume * air.densityAt(altitude) * (1.0 - GAS_SHARE) * g
        }
    }
}
