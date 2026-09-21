package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.Parachute
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Everything that pushes on a vessel.
 *
 * Kept as one stateless object with preallocated scratch rather than a force
 * hierarchy: this runs for every vessel every tick, and the indirection of
 * polymorphic force objects would cost more than the whole rest of the step.
 */
class Forces {

    /** Standard gravity, used only to convert specific impulse into mass flow. */
    private val g0 = 9.80665

    private val scratchDirection = Vec3()
    private val scratchOffset = Vec3()
    private val scratchForce = Vec3()
    private val scratchVelocity = Vec3()
    private val scratchSurface = Vec3()
    private val scratchTorque = Vec3()
    private val gimbalRotation = Quat()
    private val gimbalPitch = Quat()
    private val gimbalYaw = Quat()

    /** Newton-seconds of propellant burned this tick, for telemetry. */
    var lastMassFlow: Double = 0.0
        private set

    fun applyGravity(vessel: Vessel, attractor: CelestialBody) {
        attractor.gravityAt(vessel.body.position, scratchForce)
        scratchForce.mulInPlace(vessel.body.mass)
        vessel.body.applyCentralForce(scratchForce)
    }

    /**
     * Fires every lit engine, consuming propellant and applying thrust at each
     * engine's own position - so an asymmetric burn really does yaw the craft.
     *
     * Thrust and Isp are interpolated against ambient pressure. That is the
     * mechanic that makes staging matter: a sea-level-optimised lifter loses
     * efficiency in vacuum, and a vacuum engine is nearly useless on the pad.
     */
    fun applyThrust(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        lastMassFlow = 0.0
        val throttle = vessel.control.throttle
        if (throttle <= 0.0) return

        val altitude = attractor.altitudeOf(vessel.body.position)
        val pressureRatio = attractor.atmosphere?.pressureRatioAt(altitude) ?: 0.0

        for (partIndex in vessel.activeEngines()) {
            val engine = vessel.defs[partIndex].module<Engine>() ?: continue

            val effectiveThrottle =
                if (throttle < engine.minThrottle) engine.minThrottle else throttle

            val thrustMagnitude = lerp(engine.thrustVacuum, engine.thrustSeaLevel, pressureRatio) *
                effectiveThrottle
            val isp = lerp(engine.ispVacuum, engine.ispSeaLevel, pressureRatio)
            if (thrustMagnitude <= 0.0 || isp <= 0.0) continue

            // Mass flow from the rocket equation's definition of Isp.
            val massFlow = thrustMagnitude / (isp * g0)
            val unitsNeeded = massFlow * dt / engine.propellant.densityPerUnit
            val unitsDrawn = vessel.drainFromGroupOf(partIndex, engine.propellant, unitsNeeded)
            if (unitsDrawn <= 0.0) continue

            // A partially-fed engine produces proportionally less thrust rather
            // than cutting out, so the last fraction of a second of a tank is a
            // taper instead of a cliff.
            val feedFraction = if (unitsNeeded > 0.0) unitsDrawn / unitsNeeded else 0.0
            val actualThrust = thrustMagnitude * feedFraction
            lastMassFlow += massFlow * feedFraction

            gimballedDirection(vessel, engine, scratchDirection)
            vessel.body.orientation.rotate(scratchDirection, scratchDirection)

            scratchForce.setTo(scratchDirection).mulInPlace(actualThrust)
            vessel.partOffsetWorld(partIndex, scratchOffset)
            vessel.body.applyForceAtOffset(scratchForce, scratchOffset)
        }
    }

    /**
     * Deflects an engine's thrust axis by the control input, within its gimbal
     * range. This is what steers a rocket while it is still going fast enough
     * for fins to be irrelevant but too fast for reaction wheels to matter.
     */
    private fun gimballedDirection(vessel: Vessel, engine: Engine, out: Vec3): Vec3 {
        if (engine.gimbalRange <= 0.0) return out.setTo(engine.thrustDirection)

        val range = Math.toRadians(engine.gimbalRange)
        // Negated, and that sign is load-bearing. An engine sits *below* the
        // centre of mass, so deflecting its thrust toward +Z produces a torque
        // about -X - the opposite of what a reaction wheel does for the same
        // positive pitch command. Without the negation the two authorities
        // fight each other, and a craft with both is *less* controllable than
        // one with either. Pinned by ForcesTest.
        Quat.fromAxisAngle(Vec3.unitX(), -vessel.control.pitch * range, gimbalPitch)
        Quat.fromAxisAngle(Vec3.unitZ(), -vessel.control.yaw * range, gimbalYaw)
        gimbalRotation.setTo(gimbalPitch).mulInPlace(gimbalYaw)
        return gimbalRotation.rotate(engine.thrustDirection, out)
    }

    /**
     * Reaction-wheel authority: torque without reaction mass.
     *
     * Convention, matching the mesh axes: +Y is the nose, so roll is about Y,
     * pitch about X and yaw about Z.
     */
    fun applyReactionWheels(vessel: Vessel) {
        var authority = 0.0
        for (i in vessel.defs.indices) {
            authority += vessel.defs[i]
                .module<com.rm.apogee.core.part.Command>()
                ?.reactionTorque ?: 0.0
        }
        if (authority <= 0.0) return

        val control = vessel.control
        if (control.sasEnabled && control.pitch == 0.0 && control.yaw == 0.0 && control.roll == 0.0) {
            dampRotation(vessel, authority)
            return
        }

        scratchTorque.setTo(
            control.pitch * authority,
            control.roll * authority,
            control.yaw * authority,
        )
        vessel.body.orientation.rotate(scratchTorque, scratchTorque)
        vessel.body.applyTorque(scratchTorque)
    }

    /**
     * Stability assist with no input: bleed off rotation.
     *
     * Torque opposes angular velocity and is capped at the craft's authority,
     * so SAS settles a tumble rather than instantly freezing it - and cannot
     * overshoot into an oscillation by applying more torque than it has.
     */
    private fun dampRotation(vessel: Vessel, authority: Double) {
        val omega = vessel.body.angularVelocity
        val magnitude = omega.length
        if (magnitude < 1e-6) return

        // Scale in as rotation grows, saturating at full authority.
        val strength = (magnitude / SAS_SATURATION_RATE).coerceAtMost(1.0)
        scratchTorque.setTo(omega)
            .mulInPlace(-authority * strength / magnitude)
        vessel.body.applyTorque(scratchTorque)
    }

    /**
     * Atmospheric drag, and parachutes.
     *
     * Drag uses the craft's largest frontal cross-section rather than the sum
     * of every part's area. Summing would count a ten-tank stack as ten tanks
     * of frontal area when nine of them are in the first one's wake; real
     * occlusion modelling is a later problem, and for a stack this is much
     * closer to right than the sum is.
     */
    fun applyDrag(vessel: Vessel, attractor: CelestialBody) {
        val atmosphere = attractor.atmosphere ?: return
        val altitude = attractor.altitudeOf(vessel.body.position)
        val density = atmosphere.densityAt(altitude)
        if (density <= 0.0) return

        // Velocity relative to the air, which rotates with the planet.
        attractor.surfaceVelocityAt(vessel.body.position, scratchSurface)
        scratchVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurface)
        val speed = scratchVelocity.length
        if (speed < 1e-3) return

        var maxRadius = 0.0
        var weightedCd = 0.0
        var totalArea = 0.0
        var parachuteCdA = 0.0

        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            val extents = def.boundsHalfExtents
            val radius = maxOf(extents.x, extents.z)
            if (radius > maxRadius) maxRadius = radius

            val area = def.referenceArea
            weightedCd += def.dragCoefficient * area
            totalArea += area

            val parachute = def.module<Parachute>()
            if (parachute != null && vessel.isActivated(i)) {
                parachuteCdA += parachute.deployedDragCoefficient * area
            }
        }

        val frontalArea = PI * maxRadius * maxRadius
        val averageCd = if (totalArea > 0.0) weightedCd / totalArea else 0.3
        val cdA = frontalArea * averageCd + parachuteCdA

        val dragMagnitude = 0.5 * density * speed * speed * cdA
        scratchForce.setTo(scratchVelocity).mulInPlace(-dragMagnitude / speed)
        vessel.body.applyCentralForce(scratchForce)
    }

    /** Dynamic pressure, Pa. The number that decides whether a craft survives ascent. */
    fun dynamicPressure(vessel: Vessel, attractor: CelestialBody): Double {
        val atmosphere = attractor.atmosphere ?: return 0.0
        val density = atmosphere.densityAt(attractor.altitudeOf(vessel.body.position))
        if (density <= 0.0) return 0.0
        attractor.surfaceVelocityAt(vessel.body.position, scratchSurface)
        scratchVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurface)
        return 0.5 * density * scratchVelocity.lengthSq
    }

    private fun lerp(vacuum: Double, seaLevel: Double, pressureRatio: Double): Double =
        vacuum + (seaLevel - vacuum) * pressureRatio.coerceIn(0.0, 1.0)

    private companion object {
        /** Angular rate, rad/s, at which SAS applies full authority. */
        const val SAS_SATURATION_RATE = 0.35
    }
}

/** Speed of a circular orbit at the given radius. Shared by spawn and telemetry. */
fun circularSpeed(mu: Double, radius: Double): Double = sqrt(mu / radius)
