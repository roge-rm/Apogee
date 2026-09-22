package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.Wheel

/** What a contact resolution pass found. */
class ContactReport {
    var contactCount: Int = 0
    /** Fastest normal-direction impact this tick, m/s. */
    var worstImpactSpeed: Double = 0.0
    /** Index of the part that took [worstImpactSpeed], or -1. */
    var worstPartIndex: Int = -1

    /**
     * Parts that hit harder than they can take, this tick.
     *
     * A fixed array rather than a list because `World.step` runs this for
     * every vessel every tick and must not allocate. Sixteen is far more
     * failures than any landing produces; past that the craft is scrap
     * regardless of which part is named.
     */
    val failedParts = IntArray(MAX_FAILURES)
    var failureCount: Int = 0
        private set

    fun reset() {
        contactCount = 0
        worstImpactSpeed = 0.0
        worstPartIndex = -1
        failureCount = 0
        anchored = false
    }

    /** Records a failure, ignoring one already recorded this tick. */
    fun recordFailure(partIndex: Int) {
        for (i in 0 until failureCount) if (failedParts[i] == partIndex) return
        if (failureCount >= MAX_FAILURES) return
        failedParts[failureCount++] = partIndex
    }

    val hadContact: Boolean get() = contactCount > 0

    /**
     * The craft is being held in place by friction rather than merely touching.
     *
     * Set when the contact pass finished with the craft's ground-relative
     * motion inside what friction can cancel in a single tick.
     */
    var anchored: Boolean = false

    private companion object {
        const val MAX_FAILURES = 16
    }
}

/**
 * Resolves a vessel against a celestial body's surface.
 *
 * The surface comes from the body's own height field, sampled in the
 * body-fixed frame - the same function the renderer builds its mesh from, so
 * a craft lands on the ground it can see. Sampling in the inertial frame
 * instead is a mistake worth naming: the planet turns underneath, so a craft
 * parked on the pad slowly climbs an imaginary hill.
 *
 * Contacts are per *part*, not per vessel, which is what makes a craft tip
 * over when it lands on one leg rather than settling flat like a ball. Parts
 * with a working [LandingLeg] contact through a spring instead of rigidly;
 * everything else arrives all at once.
 */
class GroundContact {

    private val partPosition = Vec3()
    private val offset = Vec3()
    private val normal = Vec3()
    private val pointVelocity = Vec3()
    private val surfaceVelocity = Vec3()
    private val bodyFixedDirection = Vec3()
    private val bodyRotation = com.rm.apogee.core.math.Quat.identity()
    private val tangent = Vec3()
    private val rollAxis = Vec3()
    /**
     * Its own vector, not [scratch]: solveImpulse uses scratch internally, so
     * handing it scratch as the direction would have it cross a vector with
     * itself and return nonsense.
     */
    private val frictionDirection = Vec3()
    private val driveForce = Vec3()
    private val restPosition = Vec3()
    private val restOrientation = com.rm.apogee.core.math.Quat.identity()
    private val entryLinear = Vec3()
    private val entryAngular = Vec3()
    private val approachVelocity = Vec3()
    private val impulse = Vec3()
    private val scratch = Vec3()
    private val inverseInertiaWorld = Mat3()

    val report = ContactReport()

    /**
     * @param time universe time, needed because terrain turns with the planet.
     * @param accumulate keep what the report already holds, for a caller that
     *   is subdividing a tick and wants the worst of all its substeps rather
     *   than whatever the last one happened to see.
     */
    fun resolve(
        vessel: Vessel,
        attractor: CelestialBody,
        dt: Double,
        time: Double,
        accumulate: Boolean = false,
    ): ContactReport {
        if (!accumulate) report.reset()
        // Once per vessel, not once per contact point.
        attractor.rotationAt(time, bodyRotation)
        val body = vessel.body
        if (body.inverseMass <= 0.0) return report

        // Nothing within reach of the ground, so nothing to sample.
        //
        // Worth an explicit test because the alternative is evaluating the
        // height field under every contact point of every craft in the
        // system, every tick, including the ones in orbit - and the height
        // field is fifteen octaves of noise, which makes it comfortably the
        // most expensive thing in the step.
        val ceiling = attractor.radius +
            (attractor.terrain?.maxElevation ?: 0.0) + vessel.contactRadius
        if (body.position.length > ceiling) return report

        // One sample decides whether the other hundred are worth taking.
        //
        // The ceiling above only rules out craft higher than the tallest
        // mountain the *planet* can produce, which leaves a rocket climbing
        // through five kilometres of empty air evaluating fifteen octaves of
        // noise under every contact point, eight times a tick once
        // substepping kicks in. A single query for the ground directly
        // beneath the craft rules that out for a hundredth of the cost.
        //
        // The margin is generous on purpose: the ground under a contact point
        // at the edge of the craft is not the ground under its centre, and
        // terrain here reaches slopes past forty-five degrees.
        attractor.toBodyFixed(body.position, bodyRotation, bodyFixedDirection)
        val groundBelow = attractor.solidRadiusInBodyFrame(bodyFixedDirection)
        val lowestPossible = body.position.length - vessel.contactRadius
        if (lowestPossible > groundBelow + vessel.contactRadius + TERRAIN_PROXIMITY_MARGIN) {
            return report
        }

        // The velocity the craft arrived with, before any contact is solved.
        //
        // Damage is judged against this rather than against the running
        // velocity, because contacts are solved one part at a time in part
        // order: the first one to be solved absorbs the arrival and every
        // later contact sees a craft that has already stopped. That made the
        // blame fall on whichever part happened to come first in the design,
        // and a lander's legs - which touch the ground first and are indexed
        // last - were recorded as touching down at nought metres per second
        // while the engine above them was written off.
        entryLinear.setTo(body.linearVelocity)
        entryAngular.setTo(body.angularVelocity)

        for (partIndex in vessel.defs.indices) {
            val def = vessel.defs[partIndex]
            // A stowed leg has no foot on the ground. Gear left up is a way to
            // land badly, not a part that quietly still works.
            val leg = def.module<LandingLeg>()
            if (leg != null && !vessel.isWorking(partIndex)) continue

            // A wheel is a leg that rolls, so it borrows the leg's suspension
            // wholesale rather than growing a second, near-identical one.
            val wheel = def.module<Wheel>()
            val suspensionTravel = leg?.suspensionTravel ?: wheel?.suspensionTravel
            val springRate = leg?.springRate ?: wheel?.springRate
            val damping = leg?.damping ?: wheel?.damping
            val pointCount = def.contactPoints.size
            for (pointIndex in 0 until pointCount) {
            vessel.contactPointWorld(partIndex, pointIndex, partPosition)

            val distance = partPosition.length
            if (distance < 1e-6) continue

            attractor.toBodyFixed(partPosition, bodyRotation, bodyFixedDirection)
            val surfaceRadius = attractor.solidRadiusInBodyFrame(bodyFixedDirection)
            val penetration = surfaceRadius - distance
            if (penetration <= 0.0) continue

            normal.setTo(partPosition).mulInPlace(1.0 / distance)
            vessel.contactOffsetWorld(partIndex, pointIndex, offset)

            relativeVelocityAt(body, attractor, partPosition, pointVelocity)
            val normalSpeed = pointVelocity dot normal

            report.contactCount++
            val impactSpeed = -approachSpeedAt(attractor, partPosition)
            if (impactSpeed > report.worstImpactSpeed) {
                report.worstImpactSpeed = impactSpeed
                report.worstPartIndex = partIndex
            }
            if (impactSpeed > def.crashTolerance) report.recordFailure(partIndex)

            inverseInertiaWorld.setRotated(body.inverseInertiaLocal, body.orientation)

            // The compliant case: a working leg, still within its travel.
            //
            // Applied as an impulse of force x dt rather than as a force,
            // because contacts are resolved *after* integration - a force
            // added here would not move anything until the next tick, and a
            // suspension that responds a tick late is a suspension that
            // oscillates.
            if (suspensionTravel != null && penetration < suspensionTravel) {
                val spring = springRate!! * penetration - damping!! * normalSpeed
                if (spring <= 0.0) continue
                val normalImpulse = spring * dt
                impulse.setTo(normal).mulInPlace(normalImpulse)
                body.applyImpulseAtOffset(impulse, offset)

                if (wheel != null) {
                    driveWheel(vessel, attractor, wheel, normalImpulse, dt)
                } else {
                    applyFriction(body, attractor, normalImpulse)
                }
                continue
            }

            // Everything else, and a leg that has bottomed out: rigid.
            //
            // Positional correction is a fraction per tick. Correcting the
            // whole penetration at once makes a resting craft jitter, because
            // gravity pushes it back in every step and the full correction
            // throws it back out.
            scratch.setTo(normal).mulInPlace(penetration * POSITION_CORRECTION)
            body.position.addInPlace(scratch)

            if (normalSpeed >= 0.0) continue

            val normalImpulse = solveImpulse(body, normal, normalSpeed, RESTITUTION)
            impulse.setTo(normal).mulInPlace(normalImpulse)
            body.applyImpulseAtOffset(impulse, offset)

            if (wheel != null) {
                driveWheel(vessel, attractor, wheel, normalImpulse, dt)
            } else {
                applyFriction(body, attractor, normalImpulse)
            }
            }
        }

        anchorIfResting(vessel, attractor, dt)
        return report
    }

    /**
     * Holds a resting craft still, the way friction actually does.
     *
     * Without this a craft parked on a pad never stops moving. The solver
     * corrects a fraction of its penetration each tick and gravity puts it
     * straight back, and with eight contact points resolved one after another
     * the residuals do not cancel - a settled lander jitters at up to
     * 0.065 m/s and 0.032 rad/s forever. Every consumer downstream then has to
     * carry a tolerance for motion that is not real.
     *
     * The rule is the static friction condition itself. Friction can deliver
     * at most `mu * N` and a resting craft's normal force is its weight, so
     * over one tick it can cancel a ground-relative speed of up to
     * `mu * g * dt`. If the craft is moving slower than that, friction wins
     * and it does not move: say so exactly, by removing the motion. If it is
     * moving faster, friction loses and this does nothing.
     *
     * That reproduces the slope behaviour for free. Gravity adds
     * `g * sin(theta) * dt` of down-slope motion each tick against a budget of
     * `mu * g * cos(theta) * dt`, so a craft sticks while `tan(theta) < mu` and
     * slides once it is steeper - which is the textbook result, arrived at
     * without anywhere to put a fudge factor.
     *
     * The constraint it creates is real and easy to trip over: the contact
     * solver's own residual jitter has to stay under one tick's friction
     * budget, about 0.1 m/s. Stiffening the landing legs to carry a heavier
     * craft pushed that jitter above it and craft simply stopped settling.
     * Spring rates are part of this balance, not a free parameter.
     *
     * Known limit, measured rather than suspected: a craft standing on
     * *deployed* landing legs does not anchor. A sprung contact resolves after
     * gravity and before the next tick's, so it finishes every tick holding
     * the impulse that cancelled that tick's gravity - 0.163 m/s, against a
     * budget of 0.098 - even though its height above the ground is unchanged
     * to a tenth of a millimetre. Adding the acceleration back before the test
     * reads correctly in isolation and then fights the spring, which pushes
     * the craft off the ground once the motion it was balancing is removed.
     * Rigid contacts - gear up, or resting on the hull - anchor correctly.
     * `./gradlew :core:restSurvey` prints what each craft actually settles to.
     */
    private fun anchorIfResting(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        if (!report.hadContact) return
        val body = vessel.body

        // Under power is not at rest, however slowly it happens to be moving.
        if (vessel.control.throttle > 0.0) return

        attractor.gravityAt(body.position, scratch)
        val budget = FRICTION * scratch.length * dt
        if (budget <= 0.0) return

        // Two questions, because they catch different things.
        //
        // First: is it moving slowly enough for friction to stop it outright?
        // If so, stop it - that is what removes the contact solver's residual
        // jitter, which a craft resting on its hull carries for ever
        // otherwise.
        attractor.surfaceVelocityAt(body.position, surfaceVelocity)
        pointVelocity.setTo(body.linearVelocity).subInPlace(surfaceVelocity)
        attractor.angularVelocity(scratch)
        tangent.setTo(body.angularVelocity).subInPlace(scratch)
        val rimSpeed = tangent.length * vessel.contactRadius

        if (pointVelocity.length <= budget && rimSpeed <= budget) {
            body.linearVelocity.setTo(surfaceVelocity)
            body.angularVelocity.setTo(scratch)
        }

        // Second, and this is what decides whether it is at rest: has it
        // actually gone anywhere? A craft balanced on sprung legs never passes
        // the velocity test - contacts resolve after gravity, so it finishes
        // every tick holding the impulse that cancelled that tick's gravity,
        // 0.163 m/s against a budget of 0.098 - while its height above the
        // ground does not change in five decimal places. Asking what it did
        // rather than what it is doing gets the same answer for both kinds of
        // contact, and needs no correction for where in the tick it is asked.
        attractor.toBodyFixed(body.position, bodyRotation, restPosition)
        restOrientation.setTo(bodyRotation).conjugateInPlace().mulInPlace(body.orientation)
        val moved = vessel.groundMovementSince(restPosition, restOrientation)

        // A craft creeping at the fastest speed friction could still cancel
        // covers this much in a tick; anything less is not going anywhere.
        report.anchored = moved <= budget * dt
    }

    /**
     * How fast this point was closing on the ground when the tick's contact
     * pass began, along the current contact normal.
     *
     * Rebuilt from the saved entry velocity rather than read from the body,
     * which has already been pushed about by earlier contacts this tick.
     */
    private fun approachSpeedAt(attractor: CelestialBody, worldPoint: Vec3): Double {
        approachVelocity.setTo(entryAngular).crossInPlace(offset).addInPlace(entryLinear)
        attractor.surfaceVelocityAt(worldPoint, surfaceVelocity)
        approachVelocity.subInPlace(surfaceVelocity)
        return approachVelocity dot normal
    }

    /**
     * Impulse magnitude to cancel the approach, including the rotational term.
     *
     * `j = -(1+e) v_n / (1/m + n . (I⁻¹ (r x n)) x r)` - the second term is
     * what makes a hit far from the centre of mass spin the craft rather than
     * just stopping it.
     */
    private fun solveImpulse(
        body: RigidBody,
        contactNormal: Vec3,
        normalSpeed: Double,
        restitution: Double,
    ): Double {
        scratch.setTo(offset).crossInPlace(contactNormal)
        inverseInertiaWorld.transform(scratch, scratch)
        scratch.crossInPlace(offset)

        val angularTerm = scratch dot contactNormal
        val denominator = body.inverseMass + angularTerm
        if (denominator <= 1e-12) return 0.0

        return -(1.0 + restitution) * normalSpeed / denominator
    }

    /**
     * Velocity of the body's contact point *relative to the ground it is
     * touching*, written into [out].
     *
     * Subtracting the surface velocity is not a refinement, it is the
     * difference between a game where things can land and one where they
     * cannot. A planet's equator moves at ~175 m/s in the inertial frame, so a
     * craft parked on the pad is, inertially, travelling at 175 m/s - and
     * friction computed in that frame brakes it at mu*g until it topples. The
     * first unpowered pad test slid the stock rocket over and buried it.
     */
    private fun relativeVelocityAt(
        body: RigidBody,
        attractor: CelestialBody,
        worldPoint: Vec3,
        out: Vec3,
    ): Vec3 {
        body.velocityAtOffset(offset, out)
        attractor.surfaceVelocityAt(worldPoint, surfaceVelocity)
        return out.subInPlace(surfaceVelocity)
    }

    /** Coulomb friction along the contact tangent, capped by the normal impulse. */
    /**
     * A grounded wheel: rolls along its axis, grips across it, and drives.
     *
     * The rolling axis is the craft's own forward, turned by the steering
     * input for a steerable wheel and flattened into the ground plane - a
     * wheel on a slope rolls along the slope, not into it.
     *
     * Friction is split rather than scaled. Across the axis it is the full
     * ground friction, which is what stops a rover sliding sideways out of a
     * turn; along it, only [Wheel.rollingResistance], which is what lets a
     * motor measured in hundreds of newtons move a tonne that ordinary
     * friction would pin in place.
     */
    private fun driveWheel(
        vessel: Vessel,
        attractor: CelestialBody,
        wheel: Wheel,
        normalImpulse: Double,
        dt: Double,
    ) {
        val body = vessel.body
        val control = vessel.control

        // Craft forward, steered, then projected onto the ground plane.
        //
        // The design says which way forward is. Taking the nose (+Y) for a
        // craft that stands on its tail leaves a rolling direction pointing
        // at the sky, with nothing left once it is flattened into the ground -
        // which is how the first version of this failed: the wheels turned,
        // and the rover crept along at a fifth of a metre per second on
        // rounding error.
        body.orientation.rotate(vessel.design.orientation.forward, rollAxis)
        if (wheel.steerable && control.yaw != 0.0) {
            // Which end of the craft this wheel is on. Front wheels turn into
            // the corner and rear wheels away from it, which is what produces
            // a yaw moment at all: steering every wheel the same way just
            // crabs the whole craft sideways with its own grip fighting it,
            // and the first version of this steered without ever turning.
            val ahead = offset dot rollAxis
            val end = if (ahead >= 0.0) 1.0 else -1.0
            val angle = Math.toRadians(wheel.steeringRange * control.yaw) * end
            // Turn about the contact normal, which is the local vertical.
            rotateAbout(rollAxis, normal, angle)
        }
        val intoGround = rollAxis dot normal
        rollAxis.addScaledInPlace(normal, -intoGround)
        val axisLength = rollAxis.length
        if (axisLength < 1e-6) {
            applyFriction(body, attractor, normalImpulse)
            return
        }
        rollAxis.mulInPlace(1.0 / axisLength)

        // Braked, a wheel grips along its rolling axis at its brake friction:
        // the craft stops, or stays put where it was left.
        val rolling = if (control.brakes) {
            maxOf(wheel.brakeFriction, wheel.rollingResistance)
        } else {
            wheel.rollingResistance
        }
        applyFriction(body, attractor, normalImpulse, rollAxis, rolling)

        // Traction. Torque follows from where the wheel is, as for every other
        // force on a craft, so a rover with all its drive at one end pitches
        // under power exactly as it should.
        if (wheel.motorForce > 0.0 && control.throttle != 0.0 && !control.brakes) {
            // How fast this wheel is already rolling, relative to the ground.
            relativeVelocityAt(body, attractor, partPosition, pointVelocity)
            val rolling = pointVelocity dot rollAxis
            val fade = if (wheel.topSpeed <= 0.0) 1.0
                else (1.0 - rolling / wheel.topSpeed).coerceIn(0.0, 1.0)

            val tractive = (wheel.motorForce * control.throttle * fade)
                .coerceAtMost(FRICTION * normalImpulse / dt)
            driveForce.setTo(rollAxis).mulInPlace(tractive * dt)
            body.applyImpulseAtOffset(driveForce, offset)
        }
    }

    /** Rotates [v] in place about the unit axis [axis] by [angle] radians. */
    private fun rotateAbout(v: Vec3, axis: Vec3, angle: Double) {
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        val dot = v dot axis
        val cx = axis.y * v.z - axis.z * v.y
        val cy = axis.z * v.x - axis.x * v.z
        val cz = axis.x * v.y - axis.y * v.x
        v.setTo(
            v.x * cos + cx * sin + axis.x * dot * (1.0 - cos),
            v.y * cos + cy * sin + axis.y * dot * (1.0 - cos),
            v.z * cos + cz * sin + axis.z * dot * (1.0 - cos),
        )
    }

    /**
     * Ground friction at the current contact.
     *
     * With [roll] given, the tangential velocity is split: the component along
     * that axis is opposed only at [rollingCoefficient], the rest at the full
     * ground friction. That split is the whole of what makes a wheel a wheel.
     */
    private fun applyFriction(
        body: RigidBody,
        attractor: CelestialBody,
        normalImpulse: Double,
        roll: Vec3? = null,
        rollingCoefficient: Double = FRICTION,
    ) {
        relativeVelocityAt(body, attractor, partPosition, pointVelocity)
        val normalComponent = pointVelocity dot normal
        tangent.setTo(pointVelocity).addScaledInPlace(normal, -normalComponent)

        if (roll != null) {
            val along = tangent dot roll
            // Across the rolling axis first, at full grip.
            tangent.addScaledInPlace(roll, -along)
            opposeAlong(body, tangent, tangent.length, FRICTION * normalImpulse)
            // Then along it, at whatever a free wheel costs.
            tangent.setTo(roll).mulInPlace(if (along < 0.0) -1.0 else 1.0)
            opposeAlong(
                body, tangent, kotlin.math.abs(along), rollingCoefficient * normalImpulse,
            )
            return
        }

        opposeAlong(body, tangent, tangent.length, FRICTION * normalImpulse)
    }

    /** Opposes motion of [speed] along [direction], up to [maxImpulse]. */
    private fun opposeAlong(
        body: RigidBody,
        direction: Vec3,
        speed: Double,
        maxImpulse: Double,
    ) {
        if (speed < 1e-6) return
        val length = direction.length
        if (length < 1e-9) return
        frictionDirection.setTo(direction).mulInPlace(1.0 / length)

        val stoppingImpulse = solveImpulse(body, frictionDirection, speed, 0.0)
        val frictionMagnitude = stoppingImpulse.coerceIn(-maxImpulse, maxImpulse)

        impulse.setTo(frictionDirection).mulInPlace(frictionMagnitude)
        body.applyImpulseAtOffset(impulse, offset)
    }

    private companion object {
        /** Fraction of penetration corrected per tick. */
        const val POSITION_CORRECTION = 0.35

        /** Structures do not bounce much. */
        const val RESTITUTION = 0.05

        const val FRICTION = 0.6

        /**
         * Metres of slack on the "is this craft near the ground" test.
         *
         * Covers the difference between the ground under the craft's centre
         * and the ground under a contact point at its edge. Too small and a
         * craft skims over a ridge it should have hit; too large and the
         * saving evaporates. Fifty metres is far more than the terrain varies
         * across a craft-sized footprint.
         */
        const val TERRAIN_PROXIMITY_MARGIN = 50.0
    }
}
