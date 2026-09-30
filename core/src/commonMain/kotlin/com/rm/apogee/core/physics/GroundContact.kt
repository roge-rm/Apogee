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
     * Parts that hit harder than they can take this tick: which ones, how hard (m/s into the
     * surface, softened by soft ground), and the surface's normal, so the damage knows how much and
     * the dent knows which way.
     *
     * These are fixed arrays instead of lists because `World.step` runs this for every vessel every
     * tick and mustn't allocate.
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
     * hit twice this tick keeps the harder hit.
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
     * The craft is being held in place by friction, not just touching.
     *
     * Set when the contact pass finished with the craft's motion relative to the ground small
     * enough for friction to cancel in a single tick.
     */
    var anchored: Boolean = false

    /**
     * The best grip among this tick's contacts. This decides whether friction can hold the craft
     * still. A rover with one wheel on rock and three on ice is held by the rock.
     */
    var friction: Double = 0.0

    private companion object {
        const val MAX_FAILURES = 16
    }
}

/**
 * Resolves a vessel against a celestial body's surface.
 *
 * The surface comes from the body's own height field, sampled in the body-fixed frame. That's the
 * same function the renderer builds its mesh from, so a craft lands on the ground you can see.
 * Sampling in the inertial frame instead is a mistake worth mentioning: the planet turns
 * underneath, so a craft parked on the pad slowly climbs an imaginary hill.
 *
 * Contacts are per *part*, not per vessel, which is what makes a craft tip over when it lands on
 * one leg instead of settling flat like a ball. Parts with a working [LandingLeg] make contact
 * through a spring instead of rigidly, and everything else arrives all at once.
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
     * Its own vector, not [scratch]. solveImpulse uses scratch internally, so handing it scratch as
     * the direction would make it cross a vector with itself and return nonsense.
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
     * The grip of the ground under the contact being resolved, from its own material instead of one
     * number for the whole planet. It's set per contact, and read by friction, brakes and traction.
     */
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

    /** The craft this one's wheels, legs or feet were last on, this tick, or null. */
    var deckUnder: Vessel? = null
        private set

    val report = ContactReport()

    /**
     * @param time universe time, needed because terrain turns with the planet.
     * @param accumulate keep what the report already holds, for a caller that's splitting a tick up
     *     and wants the worst of all its substeps instead of whatever the last one happened to see.
     */
    fun resolve(
        vessel: Vessel,
        attractor: CelestialBody,
        dt: Double,
        time: Double,
        accumulate: Boolean = false,
    ): ContactReport {
        if (!accumulate) { report.reset(); deckUnder = null }
        compressionCleared = false
        // Once per vessel, not once per contact point.
        attractor.rotationAt(time, bodyRotation)
        val body = vessel.body
        if (body.inverseMass <= 0.0) return report
        // A gas giant has no ground, only more air all the way down.
        if (attractor.atmosphere?.deep == true) return report

        // Nothing is within reach of the ground, so there's nothing to sample.
        //
        // This is worth testing explicitly, because otherwise it would evaluate the height field
        // under every contact point of every craft in the system every tick, including the ones in
        // orbit. The height field is fifteen octaves of noise, which makes it easily the most
        // expensive thing in the step.
        val ceiling = attractor.radius +
            (attractor.terrain?.maxElevation ?: 0.0) + vessel.contactRadius
        if (body.position.length > ceiling) return report

        // One sample decides whether the other hundred are worth taking.
        //
        // The ceiling above only rules out craft higher than the tallest mountain the *planet* can
        // make. That still leaves a rocket climbing through five kilometres of empty air evaluating
        // fifteen octaves of noise under every contact point, eight times a tick once substepping
        // kicks in. A single query for the ground directly below the craft rules that out for a
        // hundredth of the cost.
        //
        // The margin is generous on purpose. The ground under a contact point at the edge of the
        // craft isn't the ground under its centre, and terrain here reaches slopes steeper than
        // forty-five degrees.
        attractor.toBodyFixed(body.position, bodyRotation, bodyFixedDirection)
        val groundBelow = attractor.solidRadiusInBodyFrame(bodyFixedDirection)
        val lowestPossible = body.position.length - vessel.contactRadius
        if (lowestPossible > groundBelow + vessel.contactRadius + TERRAIN_PROXIMITY_MARGIN) {
            return report
        }

        // Ask for the ground ahead before arriving on it: under the craft, and where it'll be a
        // couple of seconds from now at its current speed.
        prefetchGround(vessel, attractor)

        // The velocity the craft arrived with, before any contact is solved.
        //
        // Damage is judged against this instead of the running velocity, because contacts are
        // solved one part at a time in part order. The first one solved absorbs the arrival, and
        // every later contact sees a craft that has already stopped. That put the blame on
        // whichever part happened to come first in the design. A lander's legs, which touch the
        // ground first and come last in the index, were recorded as touching down at nothing while
        // the engine above them was written off.
        entryLinear.setTo(body.linearVelocity)
        entryAngular.setTo(body.angularVelocity)

        // The load under each contact point, from the craft's weight shared between however many
        // touched last tick. This decides how far soft ground gives under it.
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
            // A leg meets the ground where its feet are: folded against the hull, swinging down, or
            // out. Only a fully deployed, unbroken leg gives on its springs. Stowed or still
            // moving, it's rigid, and touching down on it is judged like any other hard contact, so
            // leaving the gear up or putting it down too late is a way to land badly. A collapsed
            // leg has no foot at all.
            val leg = def.module<LandingLeg>()
            if (leg != null && vessel.broken[partIndex]) continue
            val sprung = leg == null || (vessel.isWorking(partIndex) && vessel.legDeploy.getOrElse(partIndex) { 1.0 } >= 1.0)
            // A leg that doesn't fold has no stowed pose to meet the ground in. Stowed, as before,
            // it just isn't there.
            if (leg != null && leg.stowedAngle == 0.0 && !vessel.isWorking(partIndex)) continue

            // A wheel is a leg that rolls, so it borrows the leg's whole suspension instead of
            // growing a second, almost identical one.
            val wheel = def.module<Wheel>()
            // Feet grip the way walking drives them, not the way a dragged hull does.
            val walker = def.module<com.rm.apogee.core.part.Walker>() != null
            // Skids give a little too, the way real ones flex. Rigid, a helicopter on uneven ground
            // rocked from corner to corner like a table with a short leg, and walked twenty metres
            // across it in a minute.
            val skid = def.module<Skid>() != null
            val suspensionTravel = if (!sprung) null else leg?.suspensionTravel ?: wheel?.suspensionTravel ?: if (skid) SKID_TRAVEL else null
            val springRate = leg?.springRate ?: wheel?.springRate ?: if (skid) skidSpring else null
            val damping = leg?.damping ?: wheel?.damping ?: if (skid) skidDamping else null
            val pointCount = def.contactPoints.size
            // How far this part has sunk so far, and how far the ground under it wants it to sink
            // now, from the deepest of its points in contact.
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

            // Soft ground gives. The surface a contact meets in sand, mud, snow or regolith sits
            // below the drawn one, lower under more load and less the faster it's moving. A heavy
            // rover bogs down where a light one going fast skims across.
            //
            // Not all at once, though. The load is the craft's weight shared between the points
            // that touched last tick, which jumps whenever one more comes down. Taken all at once,
            // a parked plane rocking from two wheels onto four had the ground under every wheel
            // spring up ten centimetres in a tick, and got thrown clear of it, over and over, every
            // second. So a part sinks toward that depth, and comes back up from it, no faster than
            // [SINK_RATE].
            relativeVelocityAt(body, attractor, partPosition, pointVelocity)
            val target = sinkDepth(ground.material, loadPerContact, pointVelocity.length)
            sink = sunkBefore + (target - sunkBefore).coerceIn(-sinkStep, sinkStep)
            val radialDepth = ground.radius - sink - distance
            if (radialDepth <= 0.0) continue
            if (target > sinkTarget) sinkTarget = target

            // The face's own normal, not the radial direction. Radial treats every surface as a
            // floor, so a craft driven into a cliff got lifted up it instead of stopped. The face
            // normal pushes it back the way the wall actually faces. Depth is measured along that
            // normal too, because on a slope, a point a metre below the surface vertically is less
            // than a metre inside it.
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
        // Only held still against the ground. Something on a deck moves with the deck.
        if (terrainContacts > 0 && deckUnder == null) anchorIfResting(vessel, attractor, dt)
        return report
    }

    /**
     * [vessel]'s wheels, legs and feet against the craft in [decks], after [resolve] has had the
     * ground: a plane rolling out on a carrier's deck, a buggy driving onto a barge, a lander
     * setting down on a platform in a swell, someone walking about on a base.
     *
     * It's the ground's own contact, with the deck's motion under each point instead of the
     * planet's, a push back onto the deck for every push it gives, and the deck's weight in how
     * hard each contact is. Decks grip like concrete and never give underfoot. Only the feet go
     * through here. Every other part meets another craft in [CraftContact], as a hull meets the
     * ground, and that skips the feet so nothing is met twice.
     */
    fun resolveOnCraft(
        vessel: Vessel,
        attractor: CelestialBody,
        decks: List<Vessel>,
        dt: Double,
        /**
         * The world's tick, how long it is, and how far into it [vessel] has got, in seconds. A
         * deck already moved on this tick ([Vessel.movedTick]) is at its end, and one not moved yet
         * is at its start, so it can be ahead of [vessel] or behind it.
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
            // The same feet as on the ground: see [resolve].
            val leg = def.module<LandingLeg>()
            if (leg != null && vessel.broken[partIndex]) continue
            val sprung = leg == null || (vessel.isWorking(partIndex) && vessel.legDeploy.getOrElse(partIndex) { 1.0 } >= 1.0)
            if (leg != null && leg.stowedAngle == 0.0 && !vessel.isWorking(partIndex)) continue
            val wheel = def.module<Wheel>()
            val walker = def.module<com.rm.apogee.core.part.Walker>() != null
            // Skids give a little too, the way real ones flex. Rigid, a helicopter on uneven ground
            // rocked from corner to corner like a table with a short leg, and walked twenty metres
            // across it in a minute.
            val skid = def.module<Skid>() != null
            val suspensionTravel = if (!sprung) null else leg?.suspensionTravel ?: wheel?.suspensionTravel ?: if (skid) SKID_TRAVEL else null
            val springRate = leg?.springRate ?: wheel?.springRate ?: if (skid) skidSpring else null
            val damping = leg?.damping ?: wheel?.damping ?: if (skid) skidDamping else null
            for (pointIndex in def.contactPoints.indices) {
                vessel.contactPointWorld(partIndex, pointIndex, partPosition)
                // The first deck part the point is in. One is plenty: a foot stands on one thing.
                search@ for (other in decks) {
                    // Where the point is as of the deck's own moment, so the two are measured
                    // together. A tick apart, a rider on Terra was three metres out along the deck
                    // from where it really stood, standing on air past one end of a barge and off
                    // the other.
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
     * One contact point of part [partIndex] in contact, [penetration] metres into whatever it's
     * touching, with the surface's normal already in [normal] and its point in [partPosition]: the
     * ground, or with [deck] set, another craft.
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
        // Soft ground takes the sting out of an arrival. The same landing that wrecks a craft
        // on rock leaves it dented in snow. Without this, sinking made landings harder instead
        // of softer, because the craft fell a little further before meeting the lowered
        // surface.
        val impactSpeed = -approachSpeedAt(attractor, partPosition) /
            (1.0 + ground.material.softness * CUSHIONING)
        if (impactSpeed > report.worstImpactSpeed) {
            report.worstImpactSpeed = impactSpeed
            report.worstPartIndex = partIndex
        }
        if (impactSpeed > def.crashTolerance) report.recordImpact(partIndex, impactSpeed, normal)

        inverseInertiaWorld.setRotated(body.inverseInertiaLocal, body.orientation)

        // The springy case: a working leg that's still within its travel.
        //
        // It's applied as an impulse of force x dt instead of a force, because contacts are
        // resolved *after* integration. A force added here wouldn't move anything until the
        // next tick, and a suspension that responds a tick late is a suspension that
        // oscillates.
        //
        // How far the wheel's suspension is taken up, so everyone can see it sit on its
        // springs.
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

        // Everything else, including a leg that has bottomed out, is rigid.
        //
        // Positional correction is a fraction per tick. Correcting the whole penetration at
        // once makes a resting craft jitter, because gravity pushes it back in every step and
        // the full correction throws it back out.
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
     * The springing for [vessel]'s skids, if it has any: stiff enough that its weight, shared
     * between the corners underneath them, presses them [SKID_SAG] in, and damped short of
     * bouncing. Worked out from the craft, since skids go under anything from a drone to a
     * heavy lifter.
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
     * Holds a resting craft still, the way friction really does.
     *
     * Without this a craft parked on a pad never stops moving. The solver corrects a fraction of
     * its penetration each tick and gravity puts it straight back, and with eight contact points
     * resolved one after another the leftovers don't cancel out. A settled lander jitters at up to
     * 0.065 m/s and 0.032 rad/s forever, and everything further down then has to allow for motion
     * that isn't real.
     *
     * The rule is the static friction condition itself. Friction can give at most `mu * N`, and a
     * resting craft's normal force is its weight, so over one tick it can cancel a speed relative
     * to the ground of up to `mu * g * dt`. If the craft is moving slower than that, friction wins
     * and it doesn't move, so this says so exactly by removing the motion. If it's moving faster,
     * friction loses and this does nothing.
     *
     * That gets the slope behaviour for free. Gravity adds `g * sin(theta) * dt` of downhill motion
     * each tick against a budget of `mu * g * cos(theta) * dt`, so a craft sticks while `tan(theta)
     * < mu` and slides once it's steeper. That's the textbook result, reached without anywhere to
     * put a fudge factor.
     *
     * The limit it creates is real and easy to trip over. The contact solver's own leftover jitter
     * has to stay under one tick's friction budget, about 0.1 m/s. Stiffening the landing legs to
     * carry a heavier craft pushed that jitter above it, and craft just stopped settling. Spring
     * rates are part of this balance, not something you can set freely.
     *
     * A known limit, measured rather than guessed: a craft standing on *deployed* landing legs
     * doesn't anchor. A sprung contact resolves after gravity and before the next tick's, so it
     * finishes every tick holding the impulse that cancelled that tick's gravity, 0.163 m/s against
     * a budget of 0.098, even though its height above the ground hasn't changed by a tenth of a
     * millimetre. Adding the acceleration back before the test reads correctly on its own, but then
     * fights the spring, which pushes the craft off the ground once the motion it was balancing is
     * removed. Rigid contacts (gear up, or resting on the hull) anchor correctly. `./gradlew
     * :core:restSurvey` prints what each craft actually settles to.
     */
    private fun anchorIfResting(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        if (!report.hadContact) return
        val body = vessel.body

        // Under power isn't at rest, however slowly it happens to be moving, and neither is someone
        // walking or jumping.
        if (vessel.control.throttle > 0.0 || vessel.walking) return

        attractor.gravityAt(body.position, scratch)
        val budget = report.friction * scratch.length * dt
        if (budget <= 0.0) return

        // Two questions, because they catch different things.
        //
        // First, is it moving slowly enough for friction to stop it outright? If so, stop it.
        // That's what removes the contact solver's leftover jitter, which a craft resting on its
        // hull would otherwise carry forever.
        attractor.surfaceVelocityAt(body.position, surfaceVelocity)
        pointVelocity.setTo(body.linearVelocity).subInPlace(surfaceVelocity)
        attractor.angularVelocity(scratch)
        tangent.setTo(body.angularVelocity).subInPlace(scratch)
        val rimSpeed = tangent.length * vessel.contactRadius

        if (pointVelocity.length <= budget && rimSpeed <= budget) {
            body.linearVelocity.setTo(surfaceVelocity)
            body.angularVelocity.setTo(scratch)
        }

        // Second, and this is what decides whether it's at rest: has it actually gone anywhere? A
        // craft balanced on sprung legs never passes the speed test. Contacts resolve after
        // gravity, so it finishes every tick holding the impulse that cancelled that tick's
        // gravity, 0.163 m/s against a budget of 0.098, while its height above the ground doesn't
        // change to five decimal places. Asking what it did instead of what it's doing gives the
        // same answer for both kinds of contact, and needs no correction for where in the tick it's
        // asked.
        attractor.toBodyFixed(body.position, bodyRotation, restPosition)
        restOrientation.setTo(bodyRotation).conjugateInPlace().mulInPlace(body.orientation)
        val moved = vessel.groundMovementSince(restPosition, restOrientation)

        // A craft creeping at the fastest speed friction could still cancel covers this much in a
        // tick. Anything less isn't going anywhere.
        report.anchored = moved <= budget * dt
    }

    /**
     * How fast this point was closing on the ground when the tick's contact pass started, along the
     * current contact normal.
     *
     * It's rebuilt from the saved starting velocity instead of read from the body, because earlier
     * contacts this tick have already pushed the body around.
     */
    private fun approachSpeedAt(attractor: CelestialBody, worldPoint: Vec3): Double {
        approachVelocity.setTo(entryAngular).crossInPlace(offset).addInPlace(entryLinear)
        surfaceVelocityAt(attractor, worldPoint, surfaceVelocity)
        approachVelocity.subInPlace(surfaceVelocity)
        return approachVelocity dot normal
    }

    /**
     * The impulse size to cancel the approach, including the rotational term.
     *
     * `j = -(1+e) v_n / (1/m + n . (I⁻¹ (r x n)) x r)`. The second term is what makes a hit far
     * from the centre of mass spin the craft instead of just stopping it.
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
        // A deck gives a little too, by its own mass and turning, the way another craft does.
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
     * The velocity of the body's contact point *relative to the ground it's touching*, written into
     * [out].
     *
     * Subtracting the surface velocity isn't a refinement. It's the difference between a game where
     * things can land and one where they can't. A planet's equator moves at ~175 m/s in the
     * inertial frame, so a craft parked on the pad is travelling at 175 m/s inertially, and
     * friction worked out in that frame brakes it at mu*g until it topples. The first unpowered pad
     * test slid the stock rocket over and buried it.
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

    /**
     * [impulse] on [body] at the contact, and the same back the other way on the deck it's pushing
     * against, if it's on one that can be pushed.
     */
    private fun push(body: RigidBody, impulse: Vec3) {
        body.applyImpulseAtOffset(impulse, offset)
        val deck = deck ?: return
        if (deck.inverseMass <= 0.0) return
        pushBack.setTo(impulse).mulInPlace(-1.0)
        deck.applyImpulseAtOffset(pushBack, deckOffset)
    }

    /** Coulomb friction along the contact tangent, capped by the normal impulse. */
    /**
     * A wheel on the ground: it rolls along its axis, grips across it, and drives.
     *
     * The rolling direction is the craft's own forward, turned by the steering input for a
     * steerable wheel and flattened into the ground plane, so a wheel on a slope rolls along the
     * slope, not into it.
     *
     * Friction is split instead of scaled. Across the axis it's the full ground friction, which
     * stops a rover sliding sideways out of a turn. Along it, it's only [Wheel.rollingResistance],
     * which lets a motor measured in hundreds of newtons move a tonne that ordinary friction would
     * pin in place.
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

        // The craft's forward, steered, then projected onto the ground plane.
        //
        // The design says which way forward is. Using the nose (+Y) for a craft that stands on its
        // tail leaves a rolling direction pointing at the sky, with nothing left once it's
        // flattened into the ground. That's how the first version of this failed: the wheels
        // turned, and the rover crept along at a fifth of a metre per second on rounding error.
        body.orientation.rotate(vessel.design.orientation.forward, rollAxis)
        // Steered by the angle the world posed this wheel at this tick: front wheels into the
        // corner and rear wheels away from it, which is what makes a yaw moment at all. Steering
        // every wheel the same way just moves the craft sideways like a crab, with its own grip
        // fighting it. It's the same angle every player sees the wheel turned to.
        val steer = vessel.wheelSteer.getOrElse(steeringPart) { 0.0 }
        if (steer != 0.0) {
            // Turn around the contact normal, which is the local vertical.
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

        // Braked, a wheel grips along its rolling direction at its brake friction, so the craft
        // stops, or stays where it was left.
        //
        // What the ground costs to roll over is the wheel's own resistance, scaled by the material,
        // plus the drag of being sunk into it. It's never more than the ground itself will give,
        // and brakes on ice skid.
        val material = ground.material
        val free = wheel.rollingResistance * material.rollingDrag + material.bog * sink
        val rolling = (if (control.brakes) maxOf(wheel.brakeFriction, free) else free)
            .coerceAtMost(groundFriction)
        applyFriction(body, attractor, normalImpulse, rollAxis, rolling)

        // Traction. Torque comes from where the wheel is, like every other force on a craft, so a
        // rover with all its drive at one end pitches under power exactly like it should. A wheel
        // in an action group that's switched off freewheels, so a land yacht can sail on the
        // throttle without its motors running too.
        if (wheel.motorForce > 0.0 && control.throttle != 0.0 && !control.brakes && vessel.groupState(steeringPart) >= 0) {
            // How fast this wheel is already rolling, relative to the ground.
            relativeVelocityAt(body, attractor, partPosition, pointVelocity)
            // In reverse, it's the same motor running the other way, at a crawl's top speed.
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
     * How far a contact sinks into [material], in metres.
     *
     * It's proportional to the load on it against a reference load, and less the faster it goes. A
     * wheel moving fast spends less time loading any one patch of soft ground, which is why a
     * vehicle that keeps its momentum gets through where one that stops sinks.
     */
    private fun sinkDepth(material: SurfaceMaterial, load: Double, speed: Double): Double {
        if (material.softness <= 0.0) return 0.0
        val depth = material.softness * (load / REFERENCE_LOAD_NEWTONS) / (1.0 + speed / SKIM_SPEED)
        return depth.coerceAtMost(MAX_SINK_METRES)
    }

    /**
     * Resists a craft rolling on its hull.
     *
     * Sliding friction does nothing to something that rolls. A tank on its side, a toppled lander
     * or a capsule down on the grass all roll without their contact points sliding, and without
     * this they rolled forever. A tug pushed over on the pad was a kilometre away by the time
     * anyone looked. Real ground deforms and takes a little energy from every turn, and this is
     * that: an angular impulse against the spin relative to the ground, scaled by how hard the hull
     * is pressed down, and never more than enough to stop it.
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
     * Ground friction at the current contact.
     *
     * With [roll] given, the sideways velocity is split. The part along that axis is only resisted
     * at [rollingCoefficient], and the rest at the full ground friction. That split is all that
     * makes a wheel a wheel.
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
            // Then along it, at whatever a free wheel costs.
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

        /** How far a craft's weight presses its skids in, how far they can give, in metres, and their damping as a share of critical. */
        const val SKID_SAG = 0.03
        const val SKID_TRAVEL = 0.12
        const val SKID_DAMPING = 0.7

        /**
         * The grip before any ground has been touched this tick. Grass, which is what all ground
         * used to be.
         */
        const val DEFAULT_FRICTION = 0.6

        /**
         * The load, in newtons, at which a contact sinks by exactly its material's softness. That's
         * about a quarter of a small rover's weight on each wheel.
         */
        const val REFERENCE_LOAD_NEWTONS = 3_000.0

        /**
         * How much soft ground reduces the impact speed a part is judged by, per unit of softness.
         * Mud takes about two thirds off, and sand half.
         */
        const val CUSHIONING = 20.0

        /** The speed, in m/s, at which sinking has halved. */
        const val SKIM_SPEED = 6.0

        /** Reverse runs up to this share of a wheel's top speed. */
        const val REVERSE_TOP = 0.35

        /**
         * How fast soft ground gives under a part, or lets it back up, in m/s. A wheel settles into
         * sand over a second or so, not in a single tick. See the sinking in [resolve].
         */
        const val SINK_RATE = 0.1

        /** The deepest anything sinks, in metres. Mud up to the axles, not the roof. */
        const val MAX_SINK_METRES = 0.35

        /**
         * The rolling resistance of a hull on the ground, as a fraction of the normal force at the
         * craft's reach. It's enough to stop a toppled tank within a few metres of flat ground, and
         * small next to sliding friction.
         */
        const val HULL_ROLLING_RESISTANCE = 0.15

        /** How far ahead, in seconds of travel, ground gets prepared before arriving on it. */
        const val PREFETCH_LOOKAHEAD_SECONDS = 2.0

        /**
         * Extra room around the craft's reach, in metres, so a turn doesn't outrun the prefetch.
         */
        const val PREFETCH_MARGIN_METRES = 40.0

        /**
         * Metres of slack on the "is this craft near the ground" test.
         *
         * It covers the difference between the ground under the craft's centre and the ground under
         * a contact point at its edge. Too small and a craft skims over a ridge it should have hit,
         * too large and the saving disappears. Fifty metres is far more than the terrain varies
         * across a craft-sized footprint.
         */
        const val TERRAIN_PROXIMITY_MARGIN = 50.0
    }
}

/**
 * Whether [def] is something a craft stands on, rather than rests on: a wheel, a landing leg, or a
 * foot.
 */
fun isFoot(def: com.rm.apogee.core.part.PartDef): Boolean = def.foot
