package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.Skid
import com.rm.apogee.core.part.Wheel
import com.rm.apogee.core.terrain.SurfaceMaterial

/** What a contact resolution pass found. */
class ContactReport {
    var contactCount: Int = 0
    /** The fastest impact along the normal this tick, in m/s. */
    var worstImpactSpeed: Double = 0.0
    /** Index of the part that took [worstImpactSpeed], or -1. */
    var worstPartIndex: Int = -1

    /** A part that touched molten ground this tick, or -1. See [SurfaceMaterial.LAVA]. */
    var lavaPart: Int = -1

    /**
     * Parts that hit harder than they can take this tick: which, how hard (m/s into the surface,
     * softened by soft ground), and the surface normal, for the damage and the dent's direction.
     * Fixed arrays because `World.step` runs this for every vessel every tick and mustn't allocate.
     */
    val impactParts = IntArray(MAX_FAILURES)
    val impactSpeeds = DoubleArray(MAX_FAILURES)
    val impactNormals = DoubleArray(MAX_FAILURES * 3)
    var impactCount: Int = 0
        private set

    fun reset() {
        contactCount = 0
        worstImpactSpeed = 0.0
        worstPartIndex = -1
        lavaPart = -1
        impactCount = 0
        anchored = false
        friction = 0.0
    }

    /**
     * Records [partIndex] hitting at [speed] against a surface facing [normal] (world axes). A part
     * hit twice keeps the harder hit.
     */
    fun recordImpact(partIndex: Int, speed: Double, normal: Vec3) {
        for (i in 0 until impactCount) {
            if (impactParts[i] != partIndex) continue
            if (speed > impactSpeeds[i]) {
                impactSpeeds[i] = speed
                impactNormals[i * 3] = normal.x; impactNormals[i * 3 + 1] = normal.y; impactNormals[i * 3 + 2] = normal.z
            }
            return
        }
        if (impactCount >= MAX_FAILURES) return
        impactParts[impactCount] = partIndex
        impactSpeeds[impactCount] = speed
        impactNormals[impactCount * 3] = normal.x; impactNormals[impactCount * 3 + 1] = normal.y; impactNormals[impactCount * 3 + 2] = normal.z
        impactCount++
    }

    val hadContact: Boolean get() = contactCount > 0

    /**
     * Held in place by friction, not just touching: the craft's motion relative to the ground was
     * small enough for friction to cancel in one tick.
     */
    var anchored: Boolean = false

    /**
     * The best grip among this tick's contacts, which decides whether friction can hold the craft.
     * A rover with one wheel on rock and three on ice is held by the rock.
     */
    var friction: Double = 0.0

    private companion object {
        const val MAX_FAILURES = 16
    }
}

/**
 * Resolves a vessel against a body's surface, sampled in the body-fixed frame from the height field
 * the renderer meshes, so a craft lands on the ground you see. Contacts are per part, so a craft
 * landing on one leg tips. Working [LandingLeg]s touch through a spring; everything else is rigid.
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
    /** Its own vector, because solveImpulse uses [scratch] internally. */
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

    /** The grip of the ground under the current contact, from its material. */
    private var groundFriction = DEFAULT_FRICTION

    /** How far the contact being resolved has sunk into soft ground, in metres. */
    private var sink = 0.0

    /** The normal impulse taken by hull contacts (not wheels) this pass. */
    private var hullNormalImpulse = 0.0

    /**
     * Another craft's body, while a contact against its deck is being worked out, and where on it
     * the contact is. Null on the ground.
     */
    private var deck: RigidBody? = null
    private val deckOffset = Vec3()
    private val leadShift = Vec3()
    private val testPoint = Vec3()
    private val testCentre = Vec3()
    private val deckInverseInertia = Mat3()
    private val pushBack = Vec3()
    private val volume = PartVolume()
    private val craftCentre = Vec3()
    private val localUp = Vec3()
    private var compressionCleared = false

    /** The craft this one's wheels, legs or feet were last on this tick, or null. */
    var deckUnder: Vessel? = null
        private set

    val report = ContactReport()

    /**
     * @param time universe time, needed because terrain turns with the planet.
     * @param accumulate keep what the report already holds, so a caller splitting a tick into
     *     substeps gets the worst of them all.
     */
    fun resolve(
        vessel: Vessel,
        attractor: CelestialBody,
        dt: Double,
        time: Double,
        accumulate: Boolean = false,
        /**
         * The ground under the craft at the start of the tick, if the caller has it, and how far the
         * craft can move during the tick. Saves working it out every substep.
         */
        groundBelowAtTick: Double = Double.NaN,
        tickSlack: Double = 0.0,
    ): ContactReport {
        if (!accumulate) { report.reset(); deckUnder = null }
        compressionCleared = false
        // Once per vessel, not once per contact point.
        attractor.rotationAt(time, bodyRotation)
        val body = vessel.body
        if (body.inverseMass <= 0.0) return report
        // A gas giant has no ground, only more air all the way down.
        if (attractor.atmosphere?.deep == true) return report

        // Nothing is within reach of the ground. Worth testing, since the height field is fifteen
        // octaves of noise and the most expensive thing in the step.
        val ceiling = attractor.radius +
            (attractor.terrain?.maxElevation ?: 0.0) + vessel.contactRadius
        if (body.position.length > ceiling) return report

        // One sample below the craft decides whether the other hundred are worth taking. The
        // ceiling above only rules out craft over the tallest mountain the planet can make.
        // The margin is generous because the ground under an edge point isn't the ground under the
        // centre, and slopes can pass forty-five degrees.
        attractor.toBodyFixed(body.position, bodyRotation, bodyFixedDirection)
        val groundBelow = if (groundBelowAtTick.isNaN()) attractor.solidRadiusInBodyFrame(bodyFixedDirection) else groundBelowAtTick + tickSlack
        val lowestPossible = body.position.length - vessel.contactRadius
        if (lowestPossible > groundBelow + vessel.contactRadius + TERRAIN_PROXIMITY_MARGIN) {
            return report
        }

        // Ask for the ground ahead before arriving: under the craft, and where it'll be a couple of
        // seconds from now.
        prefetchGround(vessel, attractor)

        // The velocity the craft arrived with. Damage is judged against this, because contacts are
        // solved in part order and the first one solved absorbs the arrival. Otherwise later parts
        // (like a lander's legs) see a craft that has already stopped.
        entryLinear.setTo(body.linearVelocity)
        entryAngular.setTo(body.angularVelocity)

        // The load under each contact point: the craft's weight shared between the points that
        // touched last tick. It sets how far soft ground gives.
        attractor.gravityAt(body.position, scratch)
        val loadPerContact = body.mass * scratch.length / vessel.groundContacts.coerceAtLeast(1)
        fitSkids(vessel, scratch.length)
        hullNormalImpulse = 0.0
        var terrainContacts = 0

        vessel.fitPose()
        vessel.wheelCompression.fill(0.0)
        compressionCleared = true
        for (partIndex in vessel.defs.indices) {
            val def = vessel.defs[partIndex]
            // A leg meets the ground where its feet are: folded, swinging down, or out. Only a fully
            // deployed, unbroken leg is sprung. Stowed or moving, it's rigid and judged like any
            // hard contact, so landing with gear up or late is a bad landing. A collapsed leg has
            // no foot.
            val leg = def.module<LandingLeg>()
            if (leg != null && vessel.broken[partIndex]) continue
            val sprung = leg == null || (vessel.isWorking(partIndex) && vessel.legDeploy.getOrElse(partIndex) { 1.0 } >= 1.0)
            // A leg that doesn't fold has no stowed pose to touch with. Stowed, it isn't there.
            if (leg != null && leg.stowedAngle == 0.0 && !vessel.isWorking(partIndex)) continue

            // A wheel is a leg that rolls, so it borrows the leg's suspension.
            val wheel = def.module<Wheel>()
            // Feet grip the way walking drives them, not the way a dragged hull does.
            val walker = def.module<com.rm.apogee.core.part.Walker>() != null
            // Skids flex a little. Rigid, a helicopter rocks corner to corner on uneven ground and
            // walks across it.
            val skid = def.module<Skid>() != null
            val suspensionTravel = if (!sprung) null else leg?.suspensionTravel ?: wheel?.suspensionTravel ?: if (skid) SKID_TRAVEL else null
            val springRate = leg?.springRate ?: wheel?.springRate ?: if (skid) skidSpring else null
            val damping = leg?.damping ?: wheel?.damping ?: if (skid) skidDamping else null
            val pointCount = def.contactPoints.size
            // How far this part has sunk, and how far the ground wants it to sink now, from its
            // deepest point in contact.
            val sunkBefore = vessel.sunk.getOrElse(partIndex) { 0.0 }
            val sinkStep = SINK_RATE * dt
            var sinkTarget = 0.0
            for (pointIndex in 0 until pointCount) {
            vessel.contactPointWorld(partIndex, pointIndex, partPosition)

            val distance = partPosition.length
            if (distance < 1e-6) continue

            attractor.toBodyFixed(partPosition, bodyRotation, bodyFixedDirection)
            attractor.groundInBodyFrame(bodyFixedDirection, ground, groundLookup)
            if (ground.radius - distance <= -MAX_SINK_METRES) continue

            // Soft ground gives: in sand, mud, snow or regolith the contact surface sits lower under
            // more load and less at speed. The load jumps as points touch down, so a part sinks or
            // rises no faster than [SINK_RATE], or the ground throws the craft clear.
            relativeVelocityAt(body, attractor, partPosition, pointVelocity)
            val target = sinkDepth(ground.material, loadPerContact, pointVelocity.length)
            sink = sunkBefore + (target - sunkBefore).coerceIn(-sinkStep, sinkStep)
            val radialDepth = ground.radius - sink - distance
            if (radialDepth <= 0.0) continue
            if (target > sinkTarget) sinkTarget = target

            // Push along the face's own normal, so a craft driven into a cliff stops there instead of
            // climbing it. Depth is measured along that normal too.
            bodyRotation.rotate(ground.normal, normal)
            radialUp.setTo(partPosition).mulInPlace(1.0 / distance)
            val penetration = radialDepth * (normal dot radialUp).coerceAtLeast(0.05)
            if (ground.material == com.rm.apogee.core.terrain.SurfaceMaterial.LAVA) report.lavaPart = partIndex
            terrainContacts++
            touch(vessel, attractor, partIndex, pointIndex, penetration, wheel, walker, suspensionTravel, springRate, damping, dt)
            }
            if (partIndex in vessel.sunk.indices) {
                vessel.sunk[partIndex] = sunkBefore + (sinkTarget - sunkBefore).coerceIn(-sinkStep, sinkStep)
            }
        }

        resistRolling(vessel, attractor)
        // Only held still against the ground. On a deck it moves with the deck.
        if (terrainContacts > 0 && deckUnder == null) anchorIfResting(vessel, attractor, dt)
        return report
    }

    /**
     * [vessel]'s wheels, legs and feet against the craft in [decks], after [resolve] has done the
     * ground. The ground's contact with the deck's motion under each point and an equal push back on
     * the deck. Decks grip like concrete. Only feet go through here; other parts meet craft in
     * [CraftContact].
     */
    fun resolveOnCraft(
        vessel: Vessel,
        attractor: CelestialBody,
        decks: List<Vessel>,
        dt: Double,
        /**
         * The world's tick, its length, and how far into it [vessel] has got, in seconds. A deck
         * already moved this tick ([Vessel.movedTick]) is at its end, one not moved yet at its
         * start, so it can be ahead of [vessel] or behind it.
         */
        tick: Long = Long.MIN_VALUE,
        tickLength: Double = 0.0,
        into: Double = 0.0,
    ): ContactReport {
        val body = vessel.body
        if (body.inverseMass <= 0.0 || decks.isEmpty()) return report
        entryLinear.setTo(body.linearVelocity)
        entryAngular.setTo(body.angularVelocity)
        ground.material = com.rm.apogee.core.terrain.SurfaceMaterial.CONCRETE
        sink = 0.0
        vessel.fitPose()
        if (!compressionCleared) {
            vessel.wheelCompression.fill(0.0)
            compressionCleared = true
        }
        craftCentre.setTo(body.position)
        localUp.setTo(body.position).normalizeInPlace()
        fitSkids(vessel, attractor.gravityAt(body.position, scratch).length)
        for (partIndex in vessel.defs.indices) {
            val def = vessel.defs[partIndex]
            if (!isFoot(def)) continue
            // The same feet as on the ground. See [resolve].
            val leg = def.module<LandingLeg>()
            if (leg != null && vessel.broken[partIndex]) continue
            val sprung = leg == null || (vessel.isWorking(partIndex) && vessel.legDeploy.getOrElse(partIndex) { 1.0 } >= 1.0)
            if (leg != null && leg.stowedAngle == 0.0 && !vessel.isWorking(partIndex)) continue
            val wheel = def.module<Wheel>()
            val walker = def.module<com.rm.apogee.core.part.Walker>() != null
            // Skids flex a little. See [resolve].
            val skid = def.module<Skid>() != null
            val suspensionTravel = if (!sprung) null else leg?.suspensionTravel ?: wheel?.suspensionTravel ?: if (skid) SKID_TRAVEL else null
            val springRate = leg?.springRate ?: wheel?.springRate ?: if (skid) skidSpring else null
            val damping = leg?.damping ?: wheel?.damping ?: if (skid) skidDamping else null
            for (pointIndex in def.contactPoints.indices) {
                vessel.contactPointWorld(partIndex, pointIndex, partPosition)
                // The first deck part the point is in. A foot stands on one thing.
                search@ for (other in decks) {
                    // Where the point is at the deck's own moment in the tick, so both are measured
                    // together. A tick apart, a rider on a fast deck stands metres off where it is.
                    val lead = if (tick == Long.MIN_VALUE) 0.0 else (if (other.movedTick == tick) tickLength else 0.0) - into
                    other.body.velocityAtOffset(scratch.setTo(partPosition).subInPlace(other.body.position), leadShift)
                    leadShift.mulInPlace(lead)
                    testPoint.setTo(partPosition).addInPlace(leadShift)
                    testCentre.setTo(craftCentre).addInPlace(leadShift)
                    scratch.setTo(testPoint).subInPlace(other.body.position)
                    if (scratch.lengthSq > other.contactRadius * other.contactRadius) continue
                    for (deckPart in other.defs.indices) {
                        val deckDef = other.defs[deckPart]
                        if (!deckDef.solid) continue
                        other.partPositionWorld(deckPart, scratch)
                        val reach = deckDef.boundsHalfExtents.length
                        if (scratch.subInPlace(testPoint).lengthSq > reach * reach) continue
                        if (!volume.inside(testPoint, other, deckPart, testCentre, localUp)) continue
                        val deckBody = other.body
                        deck = deckBody
                        deckOffset.setTo(testPoint).subInPlace(deckBody.position)
                        deckInverseInertia.setRotated(deckBody.inverseInertiaLocal, deckBody.orientation)
                        normal.setTo(volume.normal)
                        deckUnder = other
                        touch(vessel, attractor, partIndex, pointIndex, volume.penetration, wheel, walker, suspensionTravel, springRate, damping, dt)
                        deck = null
                        break@search
                    }
                }
            }
        }
        return report
    }

    /**
     * One contact point of part [partIndex], [penetration] metres into the ground, or into another
     * craft when [deck] is set. The surface normal is already in [normal] and the point in
     * [partPosition].
     */
    private fun touch(
        vessel: Vessel,
        attractor: CelestialBody,
        partIndex: Int,
        pointIndex: Int,
        penetration: Double,
        wheel: Wheel?,
        walker: Boolean,
        suspensionTravel: Double?,
        springRate: Double?,
        damping: Double?,
        dt: Double,
    ) {
        val body = vessel.body
        val def = vessel.defs[partIndex]
        groundFriction = ground.material.friction
        if (groundFriction > report.friction) report.friction = groundFriction
        vessel.contactOffsetWorld(partIndex, pointIndex, offset)

        relativeVelocityAt(body, attractor, partPosition, pointVelocity)
        val normalSpeed = pointVelocity dot normal

        report.contactCount++
        // Soft ground cushions an arrival: a landing that wrecks a craft on rock dents it in snow.
        // Without this, sinking made landings harder, since the craft fell further first.
        val impactSpeed = -approachSpeedAt(attractor, partPosition) /
            (1.0 + ground.material.softness * CUSHIONING)
        if (impactSpeed > report.worstImpactSpeed) {
            report.worstImpactSpeed = impactSpeed
            report.worstPartIndex = partIndex
        }
        if (impactSpeed > def.crashTolerance) report.recordImpact(partIndex, impactSpeed, normal)

        inverseInertiaWorld.setRotated(body.inverseInertiaLocal, body.orientation)

        // The springy case: a working leg still within its travel. It's an impulse (force x dt),
        // because contacts are resolved after integration and a force would act a tick late and
        // oscillate.
        //
        // Record how far the wheel's suspension is taken up, so it's drawn sitting on its springs.
        if (wheel != null && partIndex in vessel.wheelCompression.indices) {
            val taken = minOf(penetration, suspensionTravel ?: 0.0)
            if (taken > vessel.wheelCompression[partIndex]) vessel.wheelCompression[partIndex] = taken
        }

        if (suspensionTravel != null && penetration < suspensionTravel) {
            val spring = springRate!! * penetration - damping!! * normalSpeed
            if (spring <= 0.0) return
            val normalImpulse = spring * dt
            impulse.setTo(normal).mulInPlace(normalImpulse)
            push(body, impulse)

            if (wheel != null) {
                driveWheel(vessel, partIndex, attractor, wheel, normalImpulse, dt)
            } else if (walker) {
                vessel.walkGrip += normalImpulse * groundFriction
            } else {
                applyFriction(body, attractor, normalImpulse)
            }
            return
        }

        // Everything else, including a bottomed-out leg, is rigid. Correct a fraction of the
        // penetration per tick, or a resting craft jitters.
        scratch.setTo(normal).mulInPlace(penetration * POSITION_CORRECTION)
        body.position.addInPlace(scratch)

        if (normalSpeed >= 0.0) return

        val normalImpulse = solveImpulse(body, normal, normalSpeed, RESTITUTION)
        impulse.setTo(normal).mulInPlace(normalImpulse)
        push(body, impulse)
        if (wheel == null) hullNormalImpulse += normalImpulse

        if (wheel != null) {
            driveWheel(vessel, partIndex, attractor, wheel, normalImpulse, dt)
        } else if (walker) {
            vessel.walkGrip += normalImpulse * groundFriction
        } else {
            applyFriction(body, attractor, normalImpulse)
        }
    }

    private var skidSpring = 0.0
    private var skidDamping = 0.0

    /**
     * Springing for [vessel]'s skids: stiff enough that its weight, shared between the corners,
     * presses them [SKID_SAG] in, and damped short of bouncing. Worked out per craft, since skids go
     * under anything from a drone to a heavy lifter.
     */
    private fun fitSkids(vessel: Vessel, gravity: Double) {
        var feet = 0
        for (def in vessel.defs) if (def.module<Skid>() != null) feet += def.contactPoints.size / 2
        if (feet == 0) return
        val mass = vessel.body.mass
        skidSpring = mass * gravity / (feet * SKID_SAG)
        skidDamping = SKID_DAMPING * 2.0 * kotlin.math.sqrt(skidSpring * mass / feet)
    }

    /**
     * Holds a resting craft still, as static friction does. Over one tick friction can cancel up to
     * `mu * g * dt` of motion relative to the ground; slower than that, the motion is removed. That
     * gives sticking on a slope while `tan(theta) < mu`.
     *
     * The solver's own jitter has to stay under that budget (about 0.1 m/s), so leg and wheel spring
     * rates can't be set freely. `./gradlew :core:restSurvey` prints what each craft settles to.
     */
    private fun anchorIfResting(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        if (!report.hadContact) return
        val body = vessel.body

        // Under power isn't at rest, however slow, and neither is walking or jumping.
        if (vessel.control.throttle > 0.0 || vessel.walking) return

        attractor.gravityAt(body.position, scratch)
        val budget = report.friction * scratch.length * dt
        if (budget <= 0.0) return

        // First: slow enough for friction to stop it outright? Then stop it. This removes the
        // solver's leftover jitter on a hull contact.
        attractor.surfaceVelocityAt(body.position, surfaceVelocity)
        pointVelocity.setTo(body.linearVelocity).subInPlace(surfaceVelocity)
        attractor.angularVelocity(scratch)
        tangent.setTo(body.angularVelocity).subInPlace(scratch)
        val rimSpeed = tangent.length * vessel.contactRadius

        if (pointVelocity.length <= budget && rimSpeed <= budget) {
            body.linearVelocity.setTo(surfaceVelocity)
            body.angularVelocity.setTo(scratch)
        }

        // Second, which decides rest: has it actually gone anywhere? A craft on sprung legs never
        // passes the speed test, since it ends each tick holding the impulse that cancelled gravity
        // (0.163 m/s against a 0.098 budget) without moving. Asking what it did works for both kinds
        // of contact.
        attractor.toBodyFixed(body.position, bodyRotation, restPosition)
        restOrientation.setTo(bodyRotation).conjugateInPlace().mulInPlace(body.orientation)
        val moved = vessel.groundMovementSince(restPosition, restOrientation)

        // What a craft creeping at the fastest speed friction could cancel covers in a tick.
        report.anchored = moved <= budget * dt
    }

    /**
     * How fast this point was closing on the ground when the contact pass started, along the
     * current normal. Built from the saved starting velocity, since earlier contacts this tick have
     * already pushed the body.
     */
    private fun approachSpeedAt(attractor: CelestialBody, worldPoint: Vec3): Double {
        approachVelocity.setTo(entryAngular).crossInPlace(offset).addInPlace(entryLinear)
        surfaceVelocityAt(attractor, worldPoint, surfaceVelocity)
        approachVelocity.subInPlace(surfaceVelocity)
        return approachVelocity dot normal
    }

    /**
     * The impulse that cancels the approach, with the rotational term:
     * `j = -(1+e) v_n / (1/m + n . (I⁻¹ (r x n)) x r)`. That term makes an off-centre hit spin the
     * craft.
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
        var denominator = body.inverseMass + angularTerm
        // A deck gives a little too, by its own mass and turning.
        val deck = deck
        if (deck != null && deck.inverseMass > 0.0) {
            scratch.setTo(deckOffset).crossInPlace(contactNormal)
            deckInverseInertia.transform(scratch, scratch)
            scratch.crossInPlace(deckOffset)
            denominator += deck.inverseMass + (scratch dot contactNormal)
        }
        if (denominator <= 1e-12) return 0.0

        return -(1.0 + restitution) * normalSpeed / denominator
    }

    /**
     * The velocity of the contact point relative to the ground it's touching, into [out]. Essential:
     * the equator moves at ~175 m/s inertially, and friction in that frame would topple a parked
     * craft.
     */
    private fun relativeVelocityAt(
        body: RigidBody,
        attractor: CelestialBody,
        worldPoint: Vec3,
        out: Vec3,
    ): Vec3 {
        body.velocityAtOffset(offset, out)
        surfaceVelocityAt(attractor, worldPoint, surfaceVelocity)
        return out.subInPlace(surfaceVelocity)
    }

    /** How fast what's under [worldPoint] is moving: the ground, or the deck it's on. */
    private fun surfaceVelocityAt(attractor: CelestialBody, worldPoint: Vec3, out: Vec3): Vec3 {
        val deck = deck ?: return attractor.surfaceVelocityAt(worldPoint, out)
        return deck.velocityAtOffset(deckOffset, out)
    }

    /** [impulse] on [body] at the contact, and the opposite on the deck under it, if it can move. */
    private fun push(body: RigidBody, impulse: Vec3) {
        body.applyImpulseAtOffset(impulse, offset)
        val deck = deck ?: return
        if (deck.inverseMass <= 0.0) return
        pushBack.setTo(impulse).mulInPlace(-1.0)
        deck.applyImpulseAtOffset(pushBack, deckOffset)
    }

    /** Coulomb friction along the contact tangent, capped by the normal impulse. */
    /**
     * A wheel on the ground: it rolls along the craft's steered forward, flattened onto the ground.
     * Full friction across the axis so it doesn't slide out of a turn, only
     * [Wheel.rollingResistance] along it so a small motor can move a tonne.
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

        // The craft's forward, steered, then flattened onto the ground plane. Use the design's
        // forward, not the nose (+Y), which points at the sky on a craft standing on its tail.
        body.orientation.rotate(vessel.design.orientation.forward, rollAxis)
        // Steered by the angle the world posed this wheel at: fronts into the corner, rears away,
        // which gives the yaw moment. It's the angle every player sees.
        val steer = vessel.wheelSteer.getOrElse(steeringPart) { 0.0 }
        if (steer != 0.0) {
            // Turn around the contact normal, the local vertical.
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

        // Braked, a wheel grips along its rolling direction at brake friction. Rolling costs the
        // wheel's resistance times the material, plus drag from sinking. Never more than the ground
        // gives, so brakes on ice skid.
        val material = ground.material
        val free = wheel.rollingResistance * material.rollingDrag + material.bog * sink
        val rolling = (if (control.brakes) maxOf(wheel.brakeFriction, free) else free)
            .coerceAtMost(groundFriction)
        applyFriction(body, attractor, normalImpulse, rollAxis, rolling)

        // Traction, applied where the wheel is, so a rover with drive at one end pitches under
        // power. A wheel in a switched-off action group freewheels, so a land yacht can sail on the
        // throttle without its motors.
        if (wheel.motorForce > 0.0 && control.throttle != 0.0 && !control.brakes && vessel.groupState(steeringPart) >= 0) {
            // How fast this wheel is already rolling over the ground.
            relativeVelocityAt(body, attractor, partPosition, pointVelocity)
            // Reverse is the same motor the other way, at a crawl's top speed.
            val way = if (control.reverse) -1.0 else 1.0
            val rolling = (pointVelocity dot rollAxis) * way
            val top = wheel.topSpeed * if (control.reverse) REVERSE_TOP else 1.0
            val fade = if (wheel.topSpeed <= 0.0) 1.0
                else (1.0 - rolling / top).coerceIn(0.0, 1.0)

            val tractive = (wheel.motorForce * control.throttle * fade * way)
                .coerceIn(-groundFriction * normalImpulse / dt, groundFriction * normalImpulse / dt)
            driveForce.setTo(rollAxis).mulInPlace(tractive * dt)
            push(body, driveForce)
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
     * How far a contact sinks into [material], in metres: proportional to load against a reference
     * load, and less at speed, so a vehicle that keeps moving gets through where one that stops
     * sinks.
     */
    private fun sinkDepth(material: SurfaceMaterial, load: Double, speed: Double): Double {
        if (material.softness <= 0.0) return 0.0
        val depth = material.softness * (load / REFERENCE_LOAD_NEWTONS) / (1.0 + speed / SKIM_SPEED)
        return depth.coerceAtMost(MAX_SINK_METRES)
    }

    /**
     * Resists a craft rolling on its hull. Sliding friction doesn't touch something that rolls, so a
     * toppled tank or capsule would roll forever. This is an angular impulse against the spin
     * relative to the ground, scaled by how hard the hull is pressed down, and never more than
     * enough to stop it.
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

    /** Rotates [v] in place around the unit axis [axis] by [angle] radians. */
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
     * Ground friction at the current contact. With [roll] given, motion along that axis is only
     * resisted at [rollingCoefficient] and the rest at full grip. That split is what makes a wheel.
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
            // Across the rolling direction first, at full grip.
            tangent.addScaledInPlace(roll, -along)
            opposeAlong(body, tangent, tangent.length, groundFriction * normalImpulse)
            // Then along it, at what a free wheel costs.
            tangent.setTo(roll).mulInPlace(if (along < 0.0) -1.0 else 1.0)
            opposeAlong(
                body, tangent, kotlin.math.abs(along), rollingCoefficient * normalImpulse,
            )
            return
        }

        opposeAlong(body, tangent, tangent.length, groundFriction * normalImpulse)
    }

    /** Resists motion at [speed] along [direction], up to [maxImpulse]. */
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
        push(body, impulse)
    }

    private companion object {
        /** The fraction of penetration corrected each tick. */
        const val POSITION_CORRECTION = 0.35

        /** Structures don't bounce much. */
        const val RESTITUTION = 0.05

        /**
         * How far a craft's weight presses its skids in and how far they can give, in metres, and
         * their damping as a share of critical.
         */
        const val SKID_SAG = 0.03
        const val SKID_TRAVEL = 0.12
        const val SKID_DAMPING = 0.7

        /** The grip before any ground has been touched this tick. Grass. */
        const val DEFAULT_FRICTION = 0.6

        /**
         * The load, in newtons, at which a contact sinks by exactly its material's softness. About a
         * quarter of a small rover's weight per wheel.
         */
        const val REFERENCE_LOAD_NEWTONS = 3_000.0

        /**
         * How much soft ground reduces the impact speed a part is judged by, per unit of softness.
         * Mud takes about two thirds off, sand half.
         */
        const val CUSHIONING = 20.0

        /** The speed, in m/s, at which sinking has halved. */
        const val SKIM_SPEED = 6.0

        /** Reverse runs up to this share of a wheel's top speed. */
        const val REVERSE_TOP = 0.35

        /**
         * How fast soft ground gives under a part, or lets it back up, in m/s. A wheel settles into
         * sand over a second or so. See [resolve].
         */
        const val SINK_RATE = 0.1

        /** The deepest anything sinks, in metres. Mud up to the axles. */
        const val MAX_SINK_METRES = 0.35

        /**
         * Rolling resistance of a hull on the ground, as a fraction of the normal force at the
         * craft's reach. Stops a toppled tank within a few metres, and is small next to sliding.
         */
        const val HULL_ROLLING_RESISTANCE = 0.15

        /** How far ahead, in seconds of travel, ground is prepared. */
        const val PREFETCH_LOOKAHEAD_SECONDS = 2.0

        /** Extra room around the craft's reach, in metres, so a turn doesn't outrun the prefetch. */
        const val PREFETCH_MARGIN_METRES = 40.0

        /**
         * Metres of slack on the "is this craft near the ground" test, for the ground under an edge
         * point differing from under the centre. Far more than terrain varies across a craft.
         */
        const val TERRAIN_PROXIMITY_MARGIN = 50.0
    }
}

/** Whether [def] is something a craft stands on: a wheel, a landing leg, or a foot. */
fun isFoot(def: com.rm.apogee.core.part.PartDef): Boolean = def.foot
