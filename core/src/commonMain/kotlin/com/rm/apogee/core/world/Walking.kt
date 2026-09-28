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
    fun stand(vessel: Vessel, walker: Walker, attractor: CelestialBody, ladder: LadderHold?) {
        val body = vessel.body
        vessel.onFeet = vessel.touchingGround || ladder != null
        vessel.walking = kotlin.math.abs(input(vessel)) > 0.05
        if (!vessel.onFeet) return
        if (ladder != null) up.setTo(ladder.axis) else up.setTo(body.position).normalizeInPlace()
        body.orientation.rotate(Vec3.unitY(), axis)
        // Tipped by sin(angle) around axis x up, so it's brought back upright, damped.
        scratch.setTo(axis).crossInPlace(up).mulInPlace(walker.stand)
        relative.setTo(body.angularVelocity).addScaledInPlace(up, -(body.angularVelocity dot up))
        scratch.addScaledInPlace(relative, -STAND_DAMPING)
        body.applyTorque(scratch)
    }

    /**
     * After the contacts, the walk uses up the grip the feet found, or the ladder carries them.
     * Returns whether they're still on a ladder.
     */
    fun move(vessel: Vessel, walker: Walker, attractor: CelestialBody, ladder: LadderHold?, dt: Double) {
        val grip = vessel.walkGrip
        vessel.walkGrip = 0.0
        val body = vessel.body
        if (ladder != null) {
            climb(vessel, ladder)
            return
        }
        if (!vessel.touchingGround || grip <= 0.0) return
        up.setTo(body.position).normalizeInPlace()
        attractor.surfaceVelocityAt(body.position, surface)
        relative.setTo(body.linearVelocity).subInPlace(surface)
        relative.addScaledInPlace(up, -(relative dot up))
        body.orientation.rotate(FACING, forward)
        forward.addScaledInPlace(up, -(forward dot up))
        if (forward.length < 1e-6) return
        forward.normalizeInPlace()
        want.setTo(forward).mulInPlace(input(vessel) * walker.speed)
        want.subInPlace(relative)
        val needed = want.length * body.mass
        if (needed <= 1e-9) return
        body.linearVelocity.addScaledInPlace(want, minOf(1.0, grip / needed))
    }

    /** Held to [ladder]'s line, moving with its craft, with the stick climbing. */
    private fun climb(vessel: Vessel, ladder: LadderHold) {
        val body = vessel.body
        val half = ladder.length / 2
        val along = (scratch.setTo(body.position).subInPlace(ladder.centre) dot ladder.axis).coerceIn(-half - END_REACH, half + END_REACH)
        target.setTo(ladder.centre).addScaledInPlace(ladder.axis, along).addScaledInPlace(ladder.out, HOLD_OFF)
        ladder.craft.body.velocityAtOffset(scratch.setTo(target).subInPlace(ladder.craft.body.position), out)
        val rate = if ((along >= half + END_REACH && input(vessel) > 0.0) || (along <= -half - END_REACH && input(vessel) < 0.0)) 0.0 else input(vessel) * CLIMB_SPEED
        out.addScaledInPlace(ladder.axis, rate)
        out.addScaledInPlace(target.subInPlace(body.position), PULL)
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
            vessel.touchingGround -> {
                up.setTo(body.position).normalizeInPlace()
                attractor.surfaceVelocityAt(body.position, surface)
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
    class LadderHold(val craft: Vessel, val centre: Vec3, val axis: Vec3, val out: Vec3, val length: Double)

    /** Ladder [part] of [craft] as held, or null if it isn't a working ladder. */
    fun ladderOf(craft: Vessel, part: Int): LadderHold? {
        if (part !in craft.defs.indices || craft.isBroken(part)) return null
        val ladder = craft.defs[part].module<Ladder>() ?: return null
        val rotation = craft.design.parts[part].rotation
        val axis = craft.body.orientation.rotate(rotation.rotate(Vec3.unitY(), Vec3()), Vec3())
        val out = craft.body.orientation.rotate(rotation.rotate(Vec3.unitX(), Vec3()), Vec3())
        return LadderHold(craft, craft.partPositionWorld(part, Vec3()), axis, out, ladder.length)
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
    }
}
