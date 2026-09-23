package com.rm.apogee.core.world

import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.Parachute
import com.rm.apogee.core.part.Rcs
import kotlin.math.PI
import kotlin.math.abs
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
    private val scratchWind = Vec3()
    private val scratchGust = Vec3()
    private val scratchGustPoint = Vec3()
    private val scratchLocalVelocity = Vec3()
    private val scratchAxis = Vec3()
    private val scratchCrossFlow = Vec3()
    private val scratchRadial = Vec3()
    private val scratchNormal = Vec3()
    private val scratchTorqueAxis = Vec3()
    private val scratchCommand = Vec3()
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

    /** Wings and control surfaces that failed under load last drag pass, [overstressedCount] of them. */
    val overstressed = IntArray(MAX_TORN)
    var overstressedCount = 0
        private set
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
    fun applyThrust(vessel: Vessel, attractor: CelestialBody, dt: Double, time: Double = 0.0) {
        lastMassFlow = 0.0
        val throttle = vessel.control.throttle
        if (throttle <= 0.0) return

        val altitude = attractor.altitudeOf(vessel.body.position)
        val pressureRatio = attractor.atmosphere?.pressureRatioAt(altitude) ?: 0.0

        for (partIndex in vessel.activeEngines()) {
            val engine = vessel.defs[partIndex].module<Engine>() ?: continue

            // A water propeller pushes only for as much of it as is under.
            val immersion = engine.waterProp?.let { immersion(vessel, partIndex, it, attractor, time) } ?: 1.0
            if (immersion <= 0.0) continue

            val effectiveThrottle =
                (if (throttle < engine.minThrottle) engine.minThrottle else throttle) * immersion

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
    /**
     * How far under the water part-local [point] of part [partIndex] is,
     * 0 in air to 1 at a propeller's depth - [PROP_IMMERSION_DEPTH] - or more.
     * No sea, or ground above the water here, is 0.
     */
    private fun immersion(vessel: Vessel, partIndex: Int, point: Vec3, attractor: CelestialBody, time: Double): Double {
        val ocean = attractor.ocean ?: return 0.0
        vessel.partPointOffsetWorld(partIndex, point, scratchWaterPoint).addInPlace(vessel.body.position)
        attractor.rotationAt(time, scratchWaterRotation)
        attractor.toBodyFixed(scratchWaterPoint, scratchWaterRotation, scratchWaterDirection)
        val surface = ocean.surfaceHeight(scratchWaterDirection, time)
        val terrain = attractor.terrain
        if (terrain != null && terrain.elevation(scratchWaterDirection) >= surface) return 0.0
        val depth = attractor.radius + surface - scratchWaterPoint.length
        return (depth / PROP_IMMERSION_DEPTH).coerceIn(0.0, 1.0)
    }

    private val scratchWaterPoint = Vec3()
    private val scratchWaterDirection = Vec3()
    private val scratchWaterRotation = Quat.identity()

    private fun gimballedDirection(vessel: Vessel, engine: Engine, out: Vec3): Vec3 {
        if (engine.gimbalRange <= 0.0) return out.setTo(engine.thrustDirection)

        val range = Math.toRadians(engine.gimbalRange)
        // Negated, and that sign is load-bearing. An engine sits *below* the
        // centre of mass, so deflecting its thrust toward +Z produces a torque
        // about -X - the opposite of what a reaction wheel does for the same
        // positive pitch command. Without the negation the two authorities
        // fight each other, and a craft with both is *less* controllable than
        // one with either. Pinned by ForcesTest.
        Quat.fromAxisAngle(Vec3.unitX(), -vessel.control.commandPitch * range, gimbalPitch)
        Quat.fromAxisAngle(Vec3.unitZ(), -vessel.control.commandYaw * range, gimbalYaw)
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
        // SAS with nothing to hold - on the ground, or before a hold has been
        // taken - falls back to bleeding off rotation.
        if (control.sasEnabled && !control.hasAttitudeInput && !vessel.assistHolding) {
            dampRotation(vessel, authority)
            return
        }

        scratchTorque.setTo(
            control.commandPitch * authority,
            control.commandRoll * authority,
            control.commandYaw * authority,
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
    fun applyDrag(
        vessel: Vessel,
        attractor: CelestialBody,
        /** The air's weather, when it has any; [Vessel.air] holds this tick's sample of it. */
        weather: Weather? = null,
        /** The body's rotation now: weather is in its turning frame. */
        bodyRotation: Quat? = null,
        time: Double = 0.0,
    ) {
        tornCount = 0
        overstressedCount = 0
        val atmosphere = attractor.atmosphere ?: return
        val altitude = attractor.altitudeOf(vessel.body.position)
        // Cloud water and ice thicken the air a craft has to push through.
        val air = vessel.air
        val density = atmosphere.densityAt(altitude) * air.loading
        if (density <= 0.0) return

        // The wind, in the world's frame: the air moves with the ground, and
        // on top of that with the weather.
        val windy = weather != null && bodyRotation != null
        if (windy) bodyRotation!!.rotate(air.wind, scratchWind) else scratchWind.setZero()
        val gusty = windy && air.turbulence > 0.0

        attractor.surfaceVelocityAt(vessel.body.position, scratchSurface)
        scratchSurface.addInPlace(scratchWind)
        scratchVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurface)
        // Still air and a still craft: nothing to do. A craft standing in a
        // gale is not still relative to the air, and is not skipped.
        if (scratchVelocity.lengthSq < 1e-6 && !gusty) return

        // Rain: a load straight down, on everything it falls on.
        if (air.precipitation > 0.0) {
            var catchment = 0.0
            for (def in vessel.defs) {
                catchment += def.module<AeroSurface>()?.area ?: (def.boundsHalfExtents.x * def.boundsHalfExtents.z * 4.0)
            }
            scratchForce.setTo(vessel.body.position).normalizeInPlace()
                .mulInPlace(-air.precipitation * RAIN_PRESSURE * catchment)
            vessel.body.applyCentralForce(scratchForce)
        }

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
            // Gusts, part by part: a wing tip and a tail each meet their own.
            if (gusty) {
                scratchGustPoint.setTo(vessel.body.position).addInPlace(scratchOffset)
                bodyRotation!!.inverseRotate(scratchGustPoint, scratchGustPoint)
                weather!!.turbulence(scratchGustPoint, time, air.wind, air.turbulence, scratchGust)
                bodyRotation.rotate(scratchGust, scratchGust)
                scratchLocalVelocity.subInPlace(scratchGust)
            }
            val localSpeed = scratchLocalVelocity.length
            if (localSpeed < 1e-6) continue

            // A surface that has failed makes no lift: it hangs there as drag.
            val surface = def.module<AeroSurface>()?.takeIf { !vessel.isBroken(i) }

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
                // Cross-flow times along-flow, not cross-flow squared.
                //
                // This is the flat-plate normal force, proportional to
                // sin(a)cos(a) rather than sin(a)^2, and the difference is
                // not a refinement: at the two or three degrees a wing
                // actually cruises at, squaring the cross-flow gives
                // roughly a thirtieth of the real force. Fins got away
                // with it because a rocket only needs them when it is
                // already badly out of line. A wing has to hold an
                // aircraft up at small angles, and could not.
                //
                // It also stalls for free: the product peaks near 45
                // degrees and falls away past it.
                val normalForce = if (crossSpeed > 1e-6) {
                    0.5 * density * crossSpeed * abs(along) * surface.area * surface.liftCoefficient
                } else 0.0
                if (crossSpeed > 1e-6) {
                    scratchForce.setTo(scratchCrossFlow)
                        .mulInPlace(-normalForce / crossSpeed)
                    vessel.body.applyForceAtOffset(scratchForce, scratchOffset)
                }

                val controlForce = if (surface.controllable) {
                    deflect(vessel, i, surface, density, localSpeed)
                } else 0.0

                // Past what it was built for - a gust at speed, a hard pull
                // in rough air - it fails.
                if (abs(normalForce) + abs(controlForce) > surface.loadLimit &&
                    vessel.breakPart(i) && overstressedCount < MAX_TORN
                ) {
                    overstressed[overstressedCount++] = i
                }
            }
        }
    }

    /**
     * A control surface deflecting with the stick.
     *
     * Without this an aircraft cannot fly at all, and not for want of lift: a
     * tail fin weathervanes the nose into the airflow, which drives angle of
     * attack to nothing, and a wing at no angle of attack makes no lift. The
     * plane mushes down at forty degrees nose-low with the fin doing exactly
     * what it was built to do. Holding an angle of attack is the elevator's
     * job, and nothing was doing it - [AeroSurface.controllable] and
     * [AeroSurface.controlAuthority] were carried in the schema and read by
     * nobody.
     *
     * Which way a surface deflects follows from where it is bolted, rather
     * than from a flag saying "elevator". The force it can make is
     * perpendicular to both the fuselage and its own mounting radius; the
     * torque that produces is r x F; and the deflection is the pilot's
     * command projected onto that torque. So a surface behind the centre of
     * mass pitches, one out on a wing rolls, and one that can do neither sits
     * still - all of it falling out of the geometry, the way the thrusters do.
     */
    private fun deflect(
        vessel: Vessel,
        partIndex: Int,
        surface: AeroSurface,
        density: Double,
        airspeed: Double,
    ): Double {
        // How far: worked out once per tick by [controlDeflection], so the
        // force and the surface the players see move by the same amount.
        val deflection = vessel.surfaceDeflection.getOrElse(partIndex) { 0.0 }
        if (abs(deflection) < 1e-6) return 0.0

        // The mounting radius: how far off the fuselage axis this surface is.
        val axial = scratchOffset dot scratchAxis
        scratchRadial.setTo(scratchOffset).addScaledInPlace(scratchAxis, -axial)
        val radialLength = scratchRadial.length
        if (radialLength < 1e-6) return 0.0
        scratchRadial.mulInPlace(1.0 / radialLength)

        // The direction it can push: across both the fuselage and its radius.
        scratchNormal.setTo(scratchAxis).crossInPlace(scratchRadial)
        val normalLength = scratchNormal.length
        if (normalLength < 1e-6) return 0.0
        scratchNormal.mulInPlace(1.0 / normalLength)

        // sin(d)cos(d) of the actual deflection, the same flat-plate form the
        // lift uses. Charging the surface's full broadside force made four
        // rocket fins worth tens of kilonewtons at max q.
        val angle = Math.toRadians(surface.maxDeflection) * deflection
        val force = 0.5 * density * airspeed * airspeed *
            surface.area * surface.liftCoefficient * surface.controlAuthority *
            kotlin.math.sin(angle) * kotlin.math.cos(angle)
        scratchForce.setTo(scratchNormal).mulInPlace(force)
        vessel.body.applyForceAtOffset(scratchForce, scratchOffset)
        return force
    }

    /**
     * How far control surface [partIndex] deflects for the craft's command
     * this tick, -1..1 of its travel; 0 for anything that is not a control
     * surface.
     *
     * Which way follows from where it is bolted, as [deflect] explains: the
     * push it can make is across both the fuselage and its own mounting
     * radius, the torque that push makes is r x F, and the deflection is the
     * command projected onto that torque. Worked in the craft's own axes, so
     * it needs no airflow and no orientation - it is what the stick asks of
     * this surface, on the runway as much as in flight.
     *
     * Not normalised: a hair of stick is a hair of deflection, which is what
     * gentle corrections, by a thumb or by stability assist, are made of.
     */
    fun controlDeflection(vessel: Vessel, partIndex: Int): Double {
        val def = vessel.defs[partIndex]
        val controllable = def.module<AeroSurface>()?.controllable == true ||
            def.module<com.rm.apogee.core.part.HydroSurface>()?.controllable == true
        if (!controllable) return 0.0
        val control = vessel.control
        val pitch = control.commandPitch
        val roll = control.commandRoll
        val yaw = control.commandYaw
        if (pitch == 0.0 && yaw == 0.0 && roll == 0.0) return 0.0

        val offset = vessel.centerOfMass(poseOffset)
            .mulInPlace(-1.0).addInPlace(vessel.design.parts[partIndex].position)
        // The mounting radius: how far off the fuselage axis, +Y, it is.
        poseRadial.setTo(offset.x, 0.0, offset.z)
        val radialLength = poseRadial.length
        if (radialLength < 1e-6) return 0.0
        poseRadial.mulInPlace(1.0 / radialLength)
        // The way it can push: across the fuselage and the radius.
        poseNormal.setTo(0.0, 1.0, 0.0).crossInPlace(poseRadial)
        if (poseNormal.length < 1e-6) return 0.0
        poseNormal.normalizeInPlace()
        // The torque a unit push there makes.
        poseTorque.setTo(offset).crossInPlace(poseNormal)
        val torqueLength = poseTorque.length
        if (torqueLength < 1e-6) return 0.0
        poseTorque.mulInPlace(1.0 / torqueLength)
        // Pitch about X, roll about Y, yaw about Z, as the reaction wheels.
        return (pitch * poseTorque.x + roll * poseTorque.y + yaw * poseTorque.z).coerceIn(-1.0, 1.0)
    }

    private val poseOffset = Vec3()
    private val poseRadial = Vec3()
    private val poseNormal = Vec3()
    private val poseTorque = Vec3()

    /**
     * Dynamic pressure, Pa. The number that decides whether a craft survives
     * ascent - against the air as it moves, [Vessel.air]'s wind, turned into
     * the world's frame by [bodyRotation].
     */
    fun dynamicPressure(vessel: Vessel, attractor: CelestialBody, bodyRotation: Quat? = null): Double {
        val atmosphere = attractor.atmosphere ?: return 0.0
        val density = atmosphere.densityAt(attractor.altitudeOf(vessel.body.position)) * vessel.air.loading
        if (density <= 0.0) return 0.0
        attractor.surfaceVelocityAt(vessel.body.position, scratchSurface)
        if (bodyRotation != null) scratchSurface.addInPlace(bodyRotation.rotate(vessel.air.wind, scratchWind))
        scratchVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurface)
        return 0.5 * density * scratchVelocity.lengthSq
    }

    private fun lerp(vacuum: Double, seaLevel: Double, pressureRatio: Double): Double =
        vacuum + (seaLevel - vacuum) * pressureRatio.coerceIn(0.0, 1.0)

    private companion object {
        /** Metres under water at which a propeller has its full bite. */
        const val PROP_IMMERSION_DEPTH = 0.3

        /** More chutes than any sane craft carries. */
        const val MAX_TORN = 8

        /** Pascals on a horizontal surface in the heaviest rain. */
        const val RAIN_PRESSURE = 30.0

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
