package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.Ladder
import com.rm.apogee.core.part.Walker
import com.rm.apogee.core.math.Math

/**
 * Someone out of their craft, on their feet, on a ladder or in the sea.
 *
 * On the ground the stick walks them forward and back, as fast as the ground's friction lets their
 * feet grip, and they turn on the reaction wheels. They're held upright. On a ladder the stick
 * climbs. In the air none of this applies; the jetpack is the thrusters.
 *
 * In the sea they swim, head out, with DIVE and RISE for down and up, and tread water when let go.
 * A suit is a little heavier than water, so on the bottom they walk, slowly.
 */
class Walking {

    private val up = Vec3()
    private val axis = Vec3()
    private val surface = Vec3()
    private val relative = Vec3()
    private val forward = Vec3()
    private val want = Vec3()
    private val scratch = Vec3()
    private val target = Vec3()
    private val out = Vec3()

    /** The walking part of [vessel], if it's someone on foot. */
    fun walkerOf(vessel: Vessel): Walker? {
        if (vessel.defs.size != 1) return null
        return vessel.defs[0].module<Walker>()
    }

    /** Which way they walk: stick up is forward. Turning is their roll, round their own height. */
    fun input(vessel: Vessel): Double = vessel.control.pitch.coerceIn(-1.0, 1.0)

    /**
     * Before the forces: whether they're on their feet this tick, and the torque that keeps them
     * upright, to the local vertical or along the ladder.
     */
    fun stand(vessel: Vessel, walker: Walker, attractor: CelestialBody, ladder: LadderHold?, water: Water? = null) {
        val body = vessel.body
        // Near the sea floor counts as on their feet, since a suit in water weighs next to nothing,
        // until they swim up off it.
        val onFloor = water != null && water.aboveFloor < FLOOR_REACH && vessel.control.ballast >= 0
        vessel.onFeet = ladder != null || ((vessel.touchingGround || onFloor) && !(water != null && vessel.control.ballast < 0))
        vessel.swimming = water != null && !vessel.onFeet
        vessel.walking = kotlin.math.abs(input(vessel)) > 0.05
        if (vessel.swimming) swim(vessel, attractor, water!!)
        if (!vessel.onFeet && !vessel.swimming) return
        if (ladder != null) up.setTo(ladder.axis) else up.setTo(body.position).normalizeInPlace()
        body.orientation.rotate(Vec3.unitY(), axis)
        // Tipped by sin(angle) around axis x up, so it's brought back upright, damped.
        scratch.setTo(axis).crossInPlace(up).mulInPlace(walker.stand)
        relative.setTo(body.angularVelocity).addScaledInPlace(up, -(body.angularVelocity dot up))
        scratch.addScaledInPlace(relative, -STAND_DAMPING)
        // In the water nothing underfoot stops them turning, so damp it or they spin.
        if (vessel.swimming) scratch.addScaledInPlace(up, -SWIM_TURN_DAMPING * (body.angularVelocity dot up))
        body.applyTorque(scratch)
    }

    /** After the contacts: the walk uses up the grip the feet found, or the ladder carries them. */
    fun move(vessel: Vessel, walker: Walker, attractor: CelestialBody, ladder: LadderHold?, dt: Double, water: Water? = null) {
        val grip = vessel.walkGrip
        vessel.walkGrip = 0.0
        val body = vessel.body
        if (ladder != null) {
            climb(vessel, ladder, dt)
            return
        }
        // On the sea floor there's hardly any weight to grip with, so they push along like a
        // swimmer, slowly.
        val floor = water != null && vessel.onFeet
        val reach = if (floor) walker.speed.coerceAtMost(SEABED_SPEED) else walker.speed
        val holding = if (floor) maxOf(grip, SWIM_FORCE * dt) else grip
        if (!(vessel.touchingGround || floor) || holding <= 0.0) return
        up.setTo(body.position).normalizeInPlace()
        underfoot(vessel, attractor, surface)
        relative.setTo(body.linearVelocity).subInPlace(surface)
        relative.addScaledInPlace(up, -(relative dot up))
        body.orientation.rotate(FACING, forward)
        forward.addScaledInPlace(up, -(forward dot up))
        if (forward.length < 1e-6) return
        forward.normalizeInPlace()
        want.setTo(forward).mulInPlace(input(vessel) * reach)
        want.subInPlace(relative)
        val needed = want.length * body.mass
        if (needed <= 1e-9) return
        body.linearVelocity.addScaledInPlace(want, minOf(1.0, holding / needed))
    }

    /**
     * Where someone in the sea is: [depth] metres of their middle under the surface, [aboveFloor]
     * metres of their feet over the bottom, [deepest] the suit allows, the sea's [density], and
     * [flow], the water's own motion with the waves, in world axes over the ground's. One is kept
     * and refilled each tick.
     */
    class Water {
        var depth = 0.0
        var aboveFloor = 0.0
        var deepest = 0.0
        var density = 0.0
        val flow = Vec3()
    }

    /**
     * Before the forces: swimming. The stick along the way they face, DIVE and RISE down and up,
     * otherwise treading water where they are. The push is capped at [SWIM_FORCE], so currents and
     * a big sea still carry them.
     */
    private fun swim(vessel: Vessel, attractor: CelestialBody, water: Water) {
        val body = vessel.body
        up.setTo(body.position).normalizeInPlace()
        // Relative to the water as the waves move it, or every crest leaves them under it.
        attractor.surfaceVelocityAt(body.position, surface).addInPlace(water.flow)
        relative.setTo(body.linearVelocity).subInPlace(surface)
        body.orientation.rotate(FACING, forward)
        forward.addScaledInPlace(up, -(forward dot up))
        if (forward.length > 1e-6) forward.normalizeInPlace()
        want.setTo(forward).mulInPlace(input(vessel) * SWIM_SPEED)
        val control = vessel.control
        val hold = maxOf(control.holdDepthAt, SURFACE_DEPTH)
        val rise = when {
            control.ballast > 0 && water.depth < water.deepest -> -SWIM_VERTICAL
            control.ballast > 0 -> (water.depth - water.deepest) * HOLD_GAIN
            control.ballast < 0 && water.depth > SURFACE_DEPTH -> SWIM_VERTICAL
            else -> (water.depth - hold) * HOLD_GAIN
        }.coerceIn(-SWIM_VERTICAL, SWIM_VERTICAL)
        want.addScaledInPlace(up, rise)
        // Plus whatever weight the water doesn't hold up, so treading water doesn't sink first.
        val g = attractor.gravityAt(body.position, scratch).length
        val wet = ((water.depth + HALF_HEIGHT) / (2.0 * HALF_HEIGHT)).coerceIn(0.0, 1.0)
        val lift = water.density * vessel.defs[0].displacedVolume * wet
        scratch.setTo(want).subInPlace(relative).mulInPlace(body.mass / SWIM_RESPONSE)
        scratch.addScaledInPlace(up, (body.mass - lift) * g)
        // Vertical and horizontal each get their own limit, or holding their head up eats the
        // push for swimming along.
        val vertical = (scratch dot up).coerceIn(-SWIM_FORCE, SWIM_FORCE)
        scratch.addScaledInPlace(up, -(scratch dot up))
        if (scratch.length > SWIM_FORCE) scratch.mulInPlace(SWIM_FORCE / scratch.length)
        scratch.addScaledInPlace(up, vertical)
        body.applyCentralForce(scratch)
    }

    /** How fast the ground or deck under them is moving. Walking is relative to it. */
    private fun underfoot(vessel: Vessel, attractor: CelestialBody, out: Vec3): Vec3 {
        val deck = vessel.standingOn ?: return attractor.surfaceVelocityAt(vessel.body.position, out)
        return deck.body.velocityAtOffset(scratch.setTo(vessel.body.position).subInPlace(deck.body.position), out)
    }

    /**
     * Held to [ladder]'s line, moving with its craft, with the stick climbing. It runs after they've
     * moved this tick, so the ladder's [LadderHold.lead] on them is a tick less.
     */
    private fun climb(vessel: Vessel, ladder: LadderHold, dt: Double) {
        val body = vessel.body
        val half = ladder.length / 2
        val lead = ladder.lead - dt
        // How far up they are is kept and moved only by climbing. Recomputed each tick, gravity
        // slides them down.
        if (vessel.ladderAlong.isNaN()) {
            ladder.craft.body.velocityAtOffset(scratch.setTo(ladder.centre).subInPlace(ladder.craft.body.position), out)
            vessel.ladderAlong = scratch.setTo(body.position).addScaledInPlace(out, lead).subInPlace(ladder.centre) dot ladder.axis
        }
        val rate = if ((vessel.ladderAlong >= half + END_REACH && input(vessel) > 0.0) || (vessel.ladderAlong <= -half - END_REACH && input(vessel) < 0.0)) 0.0 else input(vessel) * CLIMB_SPEED
        vessel.ladderAlong = (vessel.ladderAlong + rate * dt).coerceIn(-half - END_REACH, half + END_REACH)
        target.setTo(ladder.centre).addScaledInPlace(ladder.axis, vessel.ladderAlong).addScaledInPlace(ladder.out, HOLD_OFF)
        ladder.craft.body.velocityAtOffset(scratch.setTo(target).subInPlace(ladder.craft.body.position), out)
        // Where they'd be by the ladder's time, if it's a tick ahead of them.
        scratch.setTo(body.position).addScaledInPlace(out, lead)
        out.addScaledInPlace(ladder.axis, rate)
        out.addScaledInPlace(target.subInPlace(scratch), PULL)
        body.linearVelocity.setTo(out)
    }

    /**
     * Their legs and arms swinging, for drawing only: [Vessel.surfaceDeflection], -1..1, by stride
     * and pace. It settles back to nothing when they stop. The walk itself is [move].
     */
    fun swing(vessel: Vessel, walker: Walker, attractor: CelestialBody, ladder: LadderHold?, dt: Double) {
        val body = vessel.body
        val pace = when {
            ladder != null -> kotlin.math.abs(input(vessel)) * CLIMB_SPEED
            vessel.touchingGround || vessel.onFeet || vessel.swimming -> {
                up.setTo(body.position).normalizeInPlace()
                underfoot(vessel, attractor, surface)
                relative.setTo(body.linearVelocity).subInPlace(surface)
                relative.addScaledInPlace(up, -(relative dot up)).length
            }
            else -> 0.0
        }
        val amount = (pace / walker.speed).coerceIn(0.0, 1.0)
        vessel.fitPose()
        if (amount < 0.05) {
            // Feet back together, not frozen in the middle of a stride.
            vessel.surfaceDeflection[0] *= (1.0 - (dt * SETTLE).coerceAtMost(1.0))
            return
        }
        vessel.walkPhase = (vessel.walkPhase + pace / walker.stride * Math.PI * dt) % (2 * Math.PI)
        vessel.surfaceDeflection[0] = kotlin.math.sin(vessel.walkPhase) * amount
    }

    /** A ladder being held: its craft, middle, axis and outward face, in the reference body's frame. */
    class LadderHold(val craft: Vessel, val centre: Vec3, val axis: Vec3, val out: Vec3, val length: Double, val lead: Double = 0.0)

    /**
     * Ladder [part] of [craft] as held, or null if it isn't a working ladder. [lead] is how many
     * seconds ahead of the climber the craft has been moved this tick.
     */
    fun ladderOf(craft: Vessel, part: Int, lead: Double = 0.0): LadderHold? {
        if (part !in craft.defs.indices || craft.isBroken(part)) return null
        val ladder = craft.defs[part].module<Ladder>() ?: return null
        val rotation = craft.design.parts[part].rotation
        val axis = craft.body.orientation.rotate(rotation.rotate(Vec3.unitY(), Vec3()), Vec3())
        val out = craft.body.orientation.rotate(rotation.rotate(Vec3.unitX(), Vec3()), Vec3())
        return LadderHold(craft, craft.partPositionWorld(part, Vec3()), axis, out, ladder.length, lead)
    }

    /** How far [position] is from [hold]'s rungs, in metres. */
    fun distanceTo(hold: LadderHold, position: Vec3): Double {
        val half = hold.length / 2
        val along = (scratch.setTo(position).subInPlace(hold.centre) dot hold.axis).coerceIn(-half, half)
        target.setTo(hold.centre).addScaledInPlace(hold.axis, along)
        return target.distanceTo(position)
    }

    companion object {
        /** A suit's face, which is the way it walks. */
        val FACING: Vec3 = Vec3(0.0, 0.0, 1.0)
        /** Rotation damping holding them upright, in N·m per rad/s. */
        const val STAND_DAMPING = 300.0
        /** Climbing speed in m/s, how far out from the rungs they hang, and the pull to them per second. */
        const val CLIMB_SPEED = 0.8
        const val HOLD_OFF = 0.45
        const val PULL = 6.0
        /** How far past either end of a ladder they can hang on, in metres. */
        const val END_REACH = 0.4
        /** How close to a ladder, in metres, they need to be to grab it. */
        const val GRAB_REACH = 1.0
        /** How fast legs come back together once they stop, per second. */
        const val SETTLE = 6.0

        /** Swimming along, and down or up, in m/s through the water. */
        const val SWIM_SPEED = 1.0
        const val SWIM_VERTICAL = 0.7

        /** The most a swimmer pushes with, vertically and along, in newtons, and time to reach pace, in seconds. */
        const val SWIM_FORCE = 350.0
        const val SWIM_RESPONSE = 0.5

        /** How hard treading water pulls them back to the depth they're holding, in m/s per metre. */
        const val HOLD_GAIN = 0.8

        /** How deep their middle is, in metres, treading water at the surface with their head out. */
        const val SURFACE_DEPTH = 0.75

        /** How much someone in the water resists turning, in N·m per rad/s. */
        const val SWIM_TURN_DAMPING = 150.0

        /** Walking on the sea floor, in m/s. */
        const val SEABED_SPEED = 0.6

        /** How near the sea floor their feet have to be, in metres, to be standing on it. */
        const val FLOOR_REACH = 0.25

        /** Half a person's height, in metres, the same as World's. */
        const val HALF_HEIGHT = 0.9
    }
}
