package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.Ladder
import com.rm.apogee.core.part.Walker
import com.rm.apogee.core.math.Math

/**
 * Someone out of their craft, on their feet or on a ladder.
 *
 * On the ground the stick walks them, forward and back along the way they're facing and turning on
 * the reaction wheels, as fast as their feet can grip. That's what the ground's friction gives them
 * this tick: brisk on rock, a slow lope on Luna, and a shuffle on ice. They're held upright. On a
 * ladder they go where it goes, and the stick climbs. Off the ground and off a ladder none of this
 * does anything, because the jetpack is the thrusters.
 *
 * In the sea they swim. They're held upright with their head out, the stick swims them along and
 * DIVE and RISE take them down and up, and let go of, they tread water where they are. A suit is a
 * little heavier than the water it displaces, so down on the bottom they walk on it, slowly.
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

    /**
     * Which way they walk. Pushing the stick up is forward. Turning is their roll, around their own
     * height.
     */
    fun input(vessel: Vessel): Double = vessel.control.pitch.coerceIn(-1.0, 1.0)

    /**
     * Before the forces: whether they're on their feet this tick, and the torque that keeps them
     * upright, either against the local vertical or along the ladder they're holding.
     */
    fun stand(vessel: Vessel, walker: Walker, attractor: CelestialBody, ladder: LadderHold?, water: Water? = null) {
        val body = vessel.body
        // Down on the sea floor they're on their feet, just about touching it or not, because a suit
        // in the water weighs next to nothing. Until they swim up off it.
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
        // In the water nothing underfoot stops them turning, so it's their arms and legs. Left
        // free, someone treading water spun round and round.
        if (vessel.swimming) scratch.addScaledInPlace(up, -SWIM_TURN_DAMPING * (body.angularVelocity dot up))
        body.applyTorque(scratch)
    }

    /**
     * After the contacts, the walk uses up the grip the feet found, or the ladder carries them.
     * Returns whether they're still on a ladder.
     */
    fun move(vessel: Vessel, walker: Walker, attractor: CelestialBody, ladder: LadderHold?, dt: Double, water: Water? = null) {
        val grip = vessel.walkGrip
        vessel.walkGrip = 0.0
        val body = vessel.body
        if (ladder != null) {
            climb(vessel, ladder, dt)
            return
        }
        // On the sea floor there's hardly any weight on their feet to grip with, so they push
        // along with their arms and legs as a swimmer does, slowly.
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
     * metres of their feet over the bottom, [deepest] they'll swim down to before their suit gets
     * near what it can take, the sea's [density], and [flow], the water's own motion around them
     * with the waves, in world axes and over the ground's. One is kept and filled in each tick.
     */
    class Water {
        var depth = 0.0
        var aboveFloor = 0.0
        var deepest = 0.0
        var density = 0.0
        val flow = Vec3()
    }

    /**
     * Before the forces: someone in the water swims. The stick along the way they face, DIVE and
     * RISE down and up, and otherwise treading water at the depth they're at, or at the surface with
     * their head out. It's a push of their own, no more than [SWIM_FORCE], so a current or a big sea
     * still carries them where it likes.
     */
    private fun swim(vessel: Vessel, attractor: CelestialBody, water: Water) {
        val body = vessel.body
        up.setTo(body.position).normalizeInPlace()
        // Through the water, which the waves carry up and down and to and fro. Held against the
        // ground instead, they were left behind by every crest and ended up metres under it.
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
        // As much again as the water doesn't hold up, so treading water holds them where they are
        // instead of sinking a little first.
        val g = attractor.gravityAt(body.position, scratch).length
        val wet = ((water.depth + HALF_HEIGHT) / (2.0 * HALF_HEIGHT)).coerceIn(0.0, 1.0)
        val lift = water.density * vessel.defs[0].displacedVolume * wet
        scratch.setTo(want).subInPlace(relative).mulInPlace(body.mass / SWIM_RESPONSE)
        scratch.addScaledInPlace(up, (body.mass - lift) * g)
        // Up and down, and along, each have their own limit, the way someone treads water with
        // their arms and kicks along with their legs. Sharing one, holding their head up at the
        // surface took most of it and they swam at two thirds the pace.
        val vertical = (scratch dot up).coerceIn(-SWIM_FORCE, SWIM_FORCE)
        scratch.addScaledInPlace(up, -(scratch dot up))
        if (scratch.length > SWIM_FORCE) scratch.mulInPlace(SWIM_FORCE / scratch.length)
        scratch.addScaledInPlace(up, vertical)
        body.applyCentralForce(scratch)
    }

    /**
     * How fast what they're standing on is moving under them: the ground, or the deck of the craft
     * they're on, which walking is relative to.
     */
    private fun underfoot(vessel: Vessel, attractor: CelestialBody, out: Vec3): Vec3 {
        val deck = vessel.standingOn ?: return attractor.surfaceVelocityAt(vessel.body.position, out)
        return deck.body.velocityAtOffset(scratch.setTo(vessel.body.position).subInPlace(deck.body.position), out)
    }

    /**
     * Held to [ladder]'s line, moving with its craft, with the stick climbing. It's after they've
     * moved on this tick, so the ladder's [LadderHold.lead] on them is a tick less.
     */
    private fun climb(vessel: Vessel, ladder: LadderHold, dt: Double) {
        val body = vessel.body
        val half = ladder.length / 2
        val lead = ladder.lead - dt
        // How far up it they are is kept, and moved only by climbing. Worked out afresh from where
        // they were each tick, gravity took a little of it every tick, and stopped halfway up a
        // ship's ladder they slid back down into the sea.
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
     * Their legs and arms, swinging as they go: [Vessel.surfaceDeflection], -1..1, depending on how
     * far along their stride they are and how fast they're going, walking or climbing a ladder.
     * When they're still it settles back to nothing. This is only for drawing, and the walk itself
     * is [move].
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

    /**
     * A ladder being held: its craft, its middle, its axis and its outward face, all in the
     * reference body's frame.
     */
    class LadderHold(val craft: Vessel, val centre: Vec3, val axis: Vec3, val out: Vec3, val length: Double, val lead: Double = 0.0)

    /**
     * Ladder [part] of [craft] as held, or null if it isn't a working ladder. [lead] is how many
     * seconds ahead of the one holding it the craft has been moved on this tick.
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
        /**
         * Climbing speed in m/s, how far out from the rungs they hang, and how hard they're pulled
         * to them per second.
         */
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

        /** The most a swimmer can push with, up or down and along, in newtons, and how quickly they get to the pace they want, in seconds. */
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
