package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody

/** What a contact resolution pass found. */
class ContactReport {
    var contactCount: Int = 0
    /** Fastest normal-direction impact this tick, m/s. */
    var worstImpactSpeed: Double = 0.0
    /** Index of the part that took [worstImpactSpeed], or -1. */
    var worstPartIndex: Int = -1

    fun reset() {
        contactCount = 0
        worstImpactSpeed = 0.0
        worstPartIndex = -1
    }

    val hadContact: Boolean get() = contactCount > 0
}

/**
 * Resolves a vessel against a celestial body's surface.
 *
 * The surface is treated as a sphere at the body's datum radius. That is the
 * honest limit of M1: real terrain is a heightfield sampled from a quadtree,
 * and this resolver is written so that swapping in a height query changes one
 * line - [surfaceRadiusBelow] - rather than the contact mathematics.
 *
 * Contacts are per *part*, not per vessel, which is what makes a craft tip over
 * when it lands on one leg rather than settling flat like a ball.
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
    private val impulse = Vec3()
    private val scratch = Vec3()
    private val inverseInertiaWorld = Mat3()

    val report = ContactReport()

    /**
     * @param time universe time, needed because terrain turns with the planet.
     */
    fun resolve(
        vessel: Vessel,
        attractor: CelestialBody,
        dt: Double,
        time: Double,
    ): ContactReport {
        report.reset()
        // Once per vessel, not once per contact point.
        attractor.rotationAt(time, bodyRotation)
        val body = vessel.body
        if (body.inverseMass <= 0.0) return report

        for (partIndex in vessel.defs.indices) {
            val pointCount = vessel.defs[partIndex].contactPoints.size
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
            val impactSpeed = -normalSpeed
            if (impactSpeed > report.worstImpactSpeed) {
                report.worstImpactSpeed = impactSpeed
                report.worstPartIndex = partIndex
            }

            // Positional correction, applied as a fraction per tick. Correcting
            // the whole penetration at once makes a resting craft jitter, because
            // gravity pushes it back in every step and the full correction throws
            // it back out.
            scratch.setTo(normal).mulInPlace(penetration * POSITION_CORRECTION)
            body.position.addInPlace(scratch)

            if (normalSpeed >= 0.0) continue

            inverseInertiaWorld.setRotated(body.inverseInertiaLocal, body.orientation)

            val normalImpulse = solveImpulse(body, normal, normalSpeed, RESTITUTION)
            impulse.setTo(normal).mulInPlace(normalImpulse)
            body.applyImpulseAtOffset(impulse, offset)

            applyFriction(body, attractor, normalImpulse)
            }
        }
        return report
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
