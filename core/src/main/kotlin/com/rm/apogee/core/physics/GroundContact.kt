package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.LandingLeg

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
    }

    /** Records a failure, ignoring one already recorded this tick. */
    fun recordFailure(partIndex: Int) {
        for (i in 0 until failureCount) if (failedParts[i] == partIndex) return
        if (failureCount >= MAX_FAILURES) return
        failedParts[failureCount++] = partIndex
    }

    val hadContact: Boolean get() = contactCount > 0

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
            val pointCount = def.contactPoints.size
            for (pointIndex in 0 until pointCount) {
            vessel.contactPointWorld(partIndex, pointIndex, partPosition)

            val distance = partPosition.length
            if (distance < 1e-6) continue

            attractor.toBodyFixed(partPosition, bodyRotation, bodyFixedDirection)
            val surfaceRadius = attractor.surfaceRadiusInBodyFrame(bodyFixedDirection)
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
            if (leg != null && penetration < leg.suspensionTravel) {
                val spring = leg.springRate * penetration - leg.damping * normalSpeed
                if (spring <= 0.0) continue
                val normalImpulse = spring * dt
                impulse.setTo(normal).mulInPlace(normalImpulse)
                body.applyImpulseAtOffset(impulse, offset)
                applyFriction(body, attractor, normalImpulse)
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

            applyFriction(body, attractor, normalImpulse)
            }
        }
        return report
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
    private fun applyFriction(body: RigidBody, attractor: CelestialBody, normalImpulse: Double) {
        relativeVelocityAt(body, attractor, partPosition, pointVelocity)
        val normalComponent = pointVelocity dot normal
        tangent.setTo(pointVelocity).addScaledInPlace(normal, -normalComponent)

        val tangentSpeed = tangent.length
        if (tangentSpeed < 1e-6) return
        tangent.mulInPlace(1.0 / tangentSpeed)

        val stoppingImpulse = solveImpulse(body, tangent, tangentSpeed, 0.0)
        val maxFriction = FRICTION * normalImpulse
        val frictionMagnitude = stoppingImpulse.coerceIn(-maxFriction, maxFriction)

        impulse.setTo(tangent).mulInPlace(frictionMagnitude)
        body.applyImpulseAtOffset(impulse, offset)
    }

    private companion object {
        /** Fraction of penetration corrected per tick. */
        const val POSITION_CORRECTION = 0.35

        /** Structures do not bounce much. */
        const val RESTITUTION = 0.05

        const val FRICTION = 0.6
    }
}
