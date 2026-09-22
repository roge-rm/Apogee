package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.Parachute
import com.rm.apogee.core.part.Rcs
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
    private val scratchLocalVelocity = Vec3()
    private val scratchAxis = Vec3()
    private val scratchCrossFlow = Vec3()
    private val scratchTorque = Vec3()
    private val gimbalRotation = Quat()
    private val gimbalPitch = Quat()
    private val gimbalYaw = Quat()

    /** Newton-seconds of propellant burned this tick, for telemetry. */
    var lastMassFlow: Double = 0.0
        private set

    /**
     * Parts of parachutes torn off by over-speed deployment this call.
     *
     * Indices rather than events, because [Forces] applies forces and should
     * not know what a world event is; the world turns these into events. A
     * fixed array for the same reason [com.rm.apogee.core.physics.ContactReport]
     * uses one - this runs for every vessel every tick and must not allocate.
     */
    val tornParachutes = IntArray(MAX_TORN)
    var tornCount: Int = 0
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
     * Fires the thruster blocks, for translation rather than for going places.
     *
     * The net force goes through the centre of mass, so translating does not
     * also rotate.
     *
     * Applying each block's thrust at its own offset was tried first, on the
     * theory that placement should matter. It does not survive contact with
     * the geometry: four blocks ringing a craft *below* its centre of mass all
     * push sideways with the same lever arm, so their torques add rather than
     * cancel, and a perfectly symmetric set tumbled at 0.045 rad/s. Real
     * thruster blocks carry nozzles facing several ways and a control law
     * picks the combination that translates cleanly; this models the outcome
     * of that law rather than the plumbing underneath it.
     *
     * Draws monopropellant from each block's own fuel group, and tapers with
     * what it actually gets rather than cutting out, so running dry is a fade.
     */
    fun applyRcs(vessel: Vessel, dt: Double) {
        val control = vessel.control
        if (!control.rcsEnabled) return

        scratchDirection.setTo(control.translateX, control.translateY, control.translateZ)
        val demand = scratchDirection.length
        if (demand < 1e-6) return
        // Never more than one block's worth of thrust however the axes are
        // combined; a diagonal is a direction, not extra propellant.
        scratchDirection.mulInPlace(1.0 / demand)
        val commanded = demand.coerceAtMost(1.0)
        var total = 0.0

        for (partIndex in vessel.defs.indices) {
            // Not gated on staging, unlike an engine. A thruster block is
            // plumbing rather than a step in a sequence - it works from the
            // moment it is bolted on, the way reaction wheels do, and having
            // to remember to stage it before nudging a module into place
            // would be a puzzle with no answer worth finding.
            if (vessel.isBroken(partIndex)) continue
            val rcs = vessel.defs[partIndex].module<Rcs>() ?: continue

            val thrust = rcs.thrust * commanded
            val massFlow = thrust / (rcs.isp * g0)
            val unitsNeeded = massFlow * dt / rcs.propellant.densityPerUnit
            val unitsDrawn =
                vessel.drainFromGroupOf(partIndex, rcs.propellant, unitsNeeded)
            val feedFraction = if (unitsNeeded > 0.0) unitsDrawn / unitsNeeded else 0.0
            if (feedFraction <= 0.0) continue

            total += thrust * feedFraction
        }
        if (total <= 0.0) return

        vessel.body.orientation.rotate(scratchDirection, scratchAxis)
        scratchForce.setTo(scratchAxis).mulInPlace(total)
        vessel.body.applyCentralForce(scratchForce)
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
     * Atmospheric drag, applied part by part.
     *
     * Each part gets its own effective drag area and its force is applied at
     * its own offset, rather than one force through the centre of mass. That
     * difference is what makes fins work: a central force produces no torque
     * wherever the fins are, so a finned rocket flew exactly like a finless one
     * and the fins were pure mass.
     *
     * Three kinds of area, because they behave differently:
     *
     *  - **Bodies** are occlusion-corrected. Summing every tank's frontal area
     *    would count a ten-tank stack as ten tanks of frontal area when nine sit
     *    in the first one's wake, so the stack's total is taken from its largest
     *    cross-section and shared out among them.
     *  - **Aerodynamic surfaces** use their declared planform area, not their
     *    frontal cross-section. A fin is millimetres thick edge-on; judged by
     *    its bounding box it contributes almost nothing, which is exactly the
     *    wrong answer for the part whose entire job is aerodynamic authority.
     *    They are not occluded either - they stick out into clean air.
     *  - **Parachutes** use their own, and dominate everything when open.
     *
     * Each part's local velocity includes the craft's rotation, so aerodynamic
     * damping falls out of the same loop.
     */
    fun applyDrag(vessel: Vessel, attractor: CelestialBody) {
        tornCount = 0
        val atmosphere = attractor.atmosphere ?: return
        val altitude = attractor.altitudeOf(vessel.body.position)
        val density = atmosphere.densityAt(altitude)
        if (density <= 0.0) return

        attractor.surfaceVelocityAt(vessel.body.position, scratchSurface)
        scratchVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurface)
        if (scratchVelocity.lengthSq < 1e-6) return

        // Pass one: the stack's occlusion-corrected body drag.
        var maxRadius = 0.0
        var bodySum = 0.0
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (def.module<AeroSurface>() != null) continue
            val extents = def.boundsHalfExtents
            maxRadius = maxOf(maxRadius, extents.x, extents.z)
            bodySum += def.referenceArea * def.dragCoefficient
        }
        val bodyCdA = PI * maxRadius * maxRadius * AVERAGE_BODY_CD
        val bodyScale = if (bodySum > 0.0) bodyCdA / bodySum else 0.0

        // The stack axis, for splitting airflow into along-body and cross-body.
        vessel.body.orientation.rotate(Vec3.unitY(), scratchAxis)

        // Pass two: each part's own force, at its own place on the craft.
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]

            vessel.partOffsetWorld(i, scratchOffset)
            vessel.body.velocityAtOffset(scratchOffset, scratchLocalVelocity)
            scratchLocalVelocity.subInPlace(scratchSurface)
            val localSpeed = scratchLocalVelocity.length
            if (localSpeed < 1e-6) continue

            val surface = def.module<AeroSurface>()

            // Drag along the airflow.
            var cdA = if (surface != null) {
                // A fin edge-on to the airflow is nearly drag-free. Charging it
                // its planform area times a *lift* coefficient - which is what
                // the first version of this did - made four fins cost five times
                // the drag of the entire rocket body, and the stock craft stopped
                // reaching orbit.
                surface.area * FIN_PARASITIC_CD
            } else {
                def.referenceArea * def.dragCoefficient * bodyScale
            }
            def.module<Parachute>()?.let { parachute ->
                if (vessel.isWorking(i)) {
                    if (localSpeed > parachute.maxDeploymentSpeed) {
                        // Torn away. Checked here rather than in a pass of its
                        // own because this is the only place a part's airspeed
                        // is already known, and a second loop over every part
                        // of every vessel every tick to find out is not worth
                        // the tidier separation.
                        if (vessel.breakPart(i) && tornCount < MAX_TORN) {
                            tornParachutes[tornCount++] = i
                        }
                    } else {
                        cdA += parachute.deployedDragCoefficient * def.referenceArea
                    }
                }
            }

            if (cdA > 0.0) {
                val magnitude = 0.5 * density * localSpeed * localSpeed * cdA
                scratchForce.setTo(scratchLocalVelocity).mulInPlace(-magnitude / localSpeed)
                vessel.body.applyForceAtOffset(scratchForce, scratchOffset)
            }

            // Cross-flow force on aerodynamic surfaces.
            //
            // This is where a fin earns its mass. Only the component of airflow
            // *across* the stack acts on it, so a craft flying straight feels
            // nothing and one at an angle of attack feels a force pushing the
            // fin back into line. Applied at the fin's offset - behind the
            // centre of mass - it becomes the restoring torque that keeps the
            // pointy end forward.
            if (surface != null) {
                val along = scratchLocalVelocity dot scratchAxis
                scratchCrossFlow.setTo(scratchLocalVelocity)
                    .addScaledInPlace(scratchAxis, -along)
                val crossSpeed = scratchCrossFlow.length
                if (crossSpeed > 1e-6) {
                    val normalForce =
                        0.5 * density * crossSpeed * crossSpeed * surface.area * surface.liftCoefficient
                    scratchForce.setTo(scratchCrossFlow)
                        .mulInPlace(-normalForce / crossSpeed)
                    vessel.body.applyForceAtOffset(scratchForce, scratchOffset)
                }
            }
        }
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
        /** More chutes than any sane craft carries. */
        const val MAX_TORN = 8

        /** Angular rate, rad/s, at which SAS applies full authority. */
        const val SAS_SATURATION_RATE = 0.35

        /**
         * Drag coefficient for a stack of hull parts.
         *
         * One figure rather than a per-part average: the stack's drag is
         * dominated by its nose and its base, and averaging the coefficients of
         * the tanks in between describes nothing physical.
         */
        const val AVERAGE_BODY_CD = 0.3

        /** Drag coefficient of a fin edge-on to the airflow. Small, on purpose. */
        const val FIN_PARASITIC_CD = 0.03
    }
}

/** Speed of a circular orbit at the given radius. Shared by spawn and telemetry. */
fun circularSpeed(mu: Double, radius: Double): Double = sqrt(mu / radius)
