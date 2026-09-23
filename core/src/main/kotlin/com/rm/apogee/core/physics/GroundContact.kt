package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.Wheel
import com.rm.apogee.core.terrain.SurfaceMaterial

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
        friction = 0.0
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

    /**
     * The best grip among this tick's contacts. What decides whether friction
     * can hold the craft still - a rover with one wheel on rock and three on
     * ice is held by the rock.
     */
    var friction: Double = 0.0

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
    private val ground = com.rm.apogee.core.terrain.GroundPoint()
    private val groundLookup = com.rm.apogee.core.terrain.TerrainTileCache.Lookup()
    private val radialUp = Vec3()

    /**
     * Grip of the ground under the contact being resolved - its material's,
     * not one number for the whole planet. Set per contact, read by friction,
     * brakes and traction.
     */
    private var groundFriction = DEFAULT_FRICTION

    /** How far the contact being resolved has sunk into soft ground, metres. */
    private var sink = 0.0

    /** Normal impulse taken by hull (not wheel) contacts this pass. */
    private var hullNormalImpulse = 0.0

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

        // Ask for the ground ahead before arriving on it: under the craft, and
        // where it will be a couple of seconds from now at its current speed.
        prefetchGround(vessel, attractor)

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

        // The load under each contact point, from the craft's weight shared
        // among however many touched last tick - what decides how far soft
        // ground gives under it.
        attractor.gravityAt(body.position, scratch)
        val loadPerContact = body.mass * scratch.length / vessel.groundContacts.coerceAtLeast(1)
        hullNormalImpulse = 0.0

        vessel.fitPose()
        vessel.wheelCompression.fill(0.0)
        for (partIndex in vessel.defs.indices) {
            val def = vessel.defs[partIndex]
            // A leg meets the ground where its feet are: folded against the
            // hull, swinging down, or out. Only a fully deployed, unbroken leg
            // gives on its springs; stowed or on its way, it is rigid, and a
            // touchdown on it is judged like any other hard contact - gear
            // left up, or put down too late, is a way to land badly. A
            // collapsed leg has no foot at all.
            val leg = def.module<LandingLeg>()
            if (leg != null && vessel.broken[partIndex]) continue
            val sprung = leg == null || (vessel.isWorking(partIndex) && vessel.legDeploy.getOrElse(partIndex) { 1.0 } >= 1.0)
            // A leg that does not fold has no stowed pose to meet the ground
            // in: stowed, as before, it simply is not there.
            if (leg != null && leg.stowedAngle == 0.0 && !vessel.isWorking(partIndex)) continue

            // A wheel is a leg that rolls, so it borrows the leg's suspension
            // wholesale rather than growing a second, near-identical one.
            val wheel = def.module<Wheel>()
            val suspensionTravel = if (!sprung) null else leg?.suspensionTravel ?: wheel?.suspensionTravel
            val springRate = leg?.springRate ?: wheel?.springRate
            val damping = leg?.damping ?: wheel?.damping
            val pointCount = def.contactPoints.size
            for (pointIndex in 0 until pointCount) {
            vessel.contactPointWorld(partIndex, pointIndex, partPosition)

            val distance = partPosition.length
            if (distance < 1e-6) continue

            attractor.toBodyFixed(partPosition, bodyRotation, bodyFixedDirection)
            attractor.groundInBodyFrame(bodyFixedDirection, ground, groundLookup)
            if (ground.radius - distance <= -MAX_SINK_METRES) continue

            // Soft ground gives. The surface a contact meets in sand, mud,
            // snow or regolith sits below the one drawn, by more under more
            // load and by less the faster it is moving - a heavy rover bogs in
            // where a light one going quickly skims across.
            relativeVelocityAt(body, attractor, partPosition, pointVelocity)
            sink = sinkDepth(ground.material, loadPerContact, pointVelocity.length)
            val radialDepth = ground.radius - sink - distance
            if (radialDepth <= 0.0) continue

            // The face's own normal, not the radial direction. Radial treats
            // every surface as a floor, so a craft driven into a cliff was
            // lifted up it rather than stopped; the face normal pushes it back
            // the way the wall actually faces. Depth is measured along that
            // normal too - on a slope, a point a metre below the surface
            // vertically is less than a metre inside it.
            bodyRotation.rotate(ground.normal, normal)
            radialUp.setTo(partPosition).mulInPlace(1.0 / distance)
            val penetration = radialDepth * (normal dot radialUp).coerceAtLeast(0.05)
            groundFriction = ground.material.friction
            if (groundFriction > report.friction) report.friction = groundFriction
            vessel.contactOffsetWorld(partIndex, pointIndex, offset)

            relativeVelocityAt(body, attractor, partPosition, pointVelocity)
            val normalSpeed = pointVelocity dot normal

            report.contactCount++
            // Soft ground takes the sting out of an arrival: the same landing
            // that wrecks a craft on rock leaves it dented in snow. Without
            // this, sinking made landings harder, not softer - the craft fell
            // a little further before meeting the lowered surface.
            val impactSpeed = -approachSpeedAt(attractor, partPosition) /
                (1.0 + ground.material.softness * CUSHIONING)
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
            // How far the wheel's suspension is taken up, for everyone to
            // see it sit on its springs.
            if (wheel != null && partIndex in vessel.wheelCompression.indices) {
                val taken = minOf(penetration, suspensionTravel ?: 0.0)
                if (taken > vessel.wheelCompression[partIndex]) vessel.wheelCompression[partIndex] = taken
            }

            if (suspensionTravel != null && penetration < suspensionTravel) {
                val spring = springRate!! * penetration - damping!! * normalSpeed
                if (spring <= 0.0) continue
                val normalImpulse = spring * dt
                impulse.setTo(normal).mulInPlace(normalImpulse)
                body.applyImpulseAtOffset(impulse, offset)

                if (wheel != null) {
                    driveWheel(vessel, partIndex, attractor, wheel, normalImpulse, dt)
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
            if (wheel == null) hullNormalImpulse += normalImpulse

            if (wheel != null) {
                driveWheel(vessel, partIndex, attractor, wheel, normalImpulse, dt)
            } else {
                applyFriction(body, attractor, normalImpulse)
            }
            }
        }

        resistRolling(vessel, attractor)
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
        val budget = report.friction * scratch.length * dt
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
        steeringPart: Int,
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
        // Steered by the angle the world posed this wheel at this tick - front
        // wheels into the corner, rear wheels away from it, which is what
        // produces a yaw moment at all (steering every wheel the same way just
        // crabs the craft sideways with its own grip fighting it). The same
        // angle every player sees the wheel turned to.
        val steer = vessel.wheelSteer.getOrElse(steeringPart) { 0.0 }
        if (steer != 0.0) {
            // Turn about the contact normal, which is the local vertical.
            rotateAbout(rollAxis, normal, steer)
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
        // What the ground costs to roll over: the wheel's own resistance,
        // scaled by the material, plus the drag of being sunk into it. Never
        // more than the ground itself will give, and brakes on ice skid.
        val material = ground.material
        val free = wheel.rollingResistance * material.rollingDrag + material.bog * sink
        val rolling = (if (control.brakes) maxOf(wheel.brakeFriction, free) else free)
            .coerceAtMost(groundFriction)
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
                .coerceAtMost(groundFriction * normalImpulse / dt)
            driveForce.setTo(rollAxis).mulInPlace(tractive * dt)
            body.applyImpulseAtOffset(driveForce, offset)
        }
    }

    private fun prefetchGround(vessel: Vessel, attractor: CelestialBody) {
        val field = attractor.terrain ?: return
        val body = vessel.body
        val reach = vessel.contactRadius + PREFETCH_MARGIN_METRES
        attractor.toBodyFixed(body.position, bodyRotation, bodyFixedDirection)
        field.tiles.prefetch(bodyFixedDirection, reach, groundLookup)

        attractor.surfaceVelocityAt(body.position, surfaceVelocity)
        scratch.setTo(body.linearVelocity).subInPlace(surfaceVelocity)
        if (scratch.lengthSq < 1.0) return
        scratch.mulInPlace(PREFETCH_LOOKAHEAD_SECONDS).addInPlace(body.position)
        attractor.toBodyFixed(scratch, bodyRotation, bodyFixedDirection)
        field.tiles.prefetch(bodyFixedDirection, reach, groundLookup)
    }

    /**
     * How far a contact sinks into [material], metres.
     *
     * Proportional to the load on it against a reference load, and reduced by
     * speed: a wheel moving fast spends less time loading any one patch of
     * soft ground, which is why a vehicle that keeps its momentum gets through
     * where one that stops, sinks.
     */
    private fun sinkDepth(material: SurfaceMaterial, load: Double, speed: Double): Double {
        if (material.softness <= 0.0) return 0.0
        val depth = material.softness * (load / REFERENCE_LOAD_NEWTONS) / (1.0 + speed / SKIM_SPEED)
        return depth.coerceAtMost(MAX_SINK_METRES)
    }

    /**
     * Resists a craft rolling on its hull.
     *
     * Sliding friction does nothing to a body that rolls: a tank on its side,
     * a toppled lander, a capsule down on the grass all roll without their
     * contact points sliding, and without this they rolled for ever - a tug
     * pushed over on the pad was a kilometre away by the time anyone looked.
     * Real ground deforms and takes a little energy from every turn; this is
     * that, as an angular impulse opposing the spin relative to the ground,
     * scaled by how hard the hull is pressed down, and never more than stops it.
     */
    private fun resistRolling(vessel: Vessel, attractor: CelestialBody) {
        if (hullNormalImpulse <= 0.0) return
        val body = vessel.body
        attractor.angularVelocity(scratch)
        tangent.setTo(body.angularVelocity).subInPlace(scratch)
        val spin = tangent.length
        if (spin < 1e-6) return
        tangent.mulInPlace(1.0 / spin)
        val available = HULL_ROLLING_RESISTANCE * hullNormalImpulse * vessel.contactRadius
        val needed = spin / body.inverseInertiaAbout(tangent).coerceAtLeast(1e-12)
        impulse.setTo(tangent).mulInPlace(-minOf(available, needed))
        body.applyAngularImpulse(impulse)
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
        rollingCoefficient: Double = groundFriction,
    ) {
        relativeVelocityAt(body, attractor, partPosition, pointVelocity)
        val normalComponent = pointVelocity dot normal
        tangent.setTo(pointVelocity).addScaledInPlace(normal, -normalComponent)

        if (roll != null) {
            val along = tangent dot roll
            // Across the rolling axis first, at full grip.
            tangent.addScaledInPlace(roll, -along)
            opposeAlong(body, tangent, tangent.length, groundFriction * normalImpulse)
            // Then along it, at whatever a free wheel costs.
            tangent.setTo(roll).mulInPlace(if (along < 0.0) -1.0 else 1.0)
            opposeAlong(
                body, tangent, kotlin.math.abs(along), rollingCoefficient * normalImpulse,
            )
            return
        }

        opposeAlong(body, tangent, tangent.length, groundFriction * normalImpulse)
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

        /** Grip before any ground has been touched this tick. Grass, as all ground used to be. */
        const val DEFAULT_FRICTION = 0.6

        /**
         * The load, newtons, at which a contact sinks by exactly its
         * material's softness - about a quarter of a small rover's weight on
         * each wheel.
         */
        const val REFERENCE_LOAD_NEWTONS = 3_000.0

        /**
         * How much soft ground reduces the impact speed a part is judged by,
         * per unit of softness: mud takes about two-thirds off, sand half.
         */
        const val CUSHIONING = 20.0

        /** Speed, m/s, at which sinkage has halved. */
        const val SKIM_SPEED = 6.0

        /** Deepest anything sinks, metres. Mud up to the axles, not the roof. */
        const val MAX_SINK_METRES = 0.35

        /**
         * Rolling resistance of a hull on the ground, as a fraction of the
         * normal force at the craft's reach. Enough to stop a toppled tank
         * within a few metres of flat ground; small beside sliding friction.
         */
        const val HULL_ROLLING_RESISTANCE = 0.15

        /** How far ahead, in seconds of travel, ground is prepared before arriving on it. */
        const val PREFETCH_LOOKAHEAD_SECONDS = 2.0

        /** Around the craft's reach, metres, so a turn does not outrun the prefetch. */
        const val PREFETCH_MARGIN_METRES = 40.0

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
