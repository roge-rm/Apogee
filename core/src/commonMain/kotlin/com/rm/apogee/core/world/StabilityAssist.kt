package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math

/**
 * SAS as attitude hold: let go of the stick and the craft stays pointing where it was. A stable
 * aircraft weathervanes to zero angle of attack and drops its nose, so something has to hold it.
 * It steers through the same surfaces, gimbals and wheels as the stick, via
 * [com.rm.apogee.core.craft.ControlState.assistPitch] and its siblings.
 *
 * Only off the ground, or a rover would fight every bump. The hold is in inertial axes.
 *
 * It's PID, unlike the [AttitudeController]'s PD, because holding a steady angle of attack needs a
 * steady elevator, and without an integral it settles short. Commands move no faster than [SLEW],
 * like a real actuator; slammed end to end in a tick, strong surfaces chatter at the tick rate and
 * tear the craft apart.
 *
 * Afloat it's a helmsman: it holds the heading, brings the hull level after a turn, and leaves the
 * pitch to the sea. A helicopter it levels too, holding the heading.
 *
 * Its gains shrink on an axis where the craft turns so readily that the rate term would take out
 * more than [RATE_SHARE] of the spin in one tick, as control surfaces do at speed; past that it
 * overshoots every tick and the surfaces chatter. While you steer a winged craft in the air it
 * holds the turn rate under what a few g allows ([turnLimit]), so full stick at speed can't pull
 * the wings off.
 */
class StabilityAssist(
    private val proportionalGain: Double = 5.0,
    private val derivativeGain: Double = 4.0,
    private val integralGain: Double = 1.5,
) {
    private val error = Quat()
    private val nose = Vec3()
    private val turn = Quat()
    private val errorBody = Vec3()
    private val rateBody = Vec3()
    private val up = Vec3()
    private val level = Quat()
    private val heading = Vec3()
    private val axis = Vec3()

    /** How much of each gain to use, pitch, roll and yaw, this tick. See [RATE_SHARE]. */
    private val scale = Vec3()

    /**
     * @param direction when holding a navball marker, where the nose should point, inertial. Null
     *     to hold the attitude at release.
     */
    fun update(vessel: Vessel, dt: Double, direction: Vec3? = null) {
        val control = vessel.control
        val body = vessel.body
        // Afloat, it keeps the deck level while you steer and holds the heading once you let go.
        // Submerged it holds pitch too, or a submarine goes down nose first.
        val afloat = control.sasEnabled && vessel.buoyed && !vessel.submerged && !vessel.touchingGround && direction == null
        control.assistLevelling = afloat
        control.assistGuarding = false
        if (control.sasEnabled) scaleGains(vessel, dt)
        if (afloat && control.stickOverrides) {
            vessel.assistHeld.setTo(body.orientation)
            levelled(vessel)
            // Retaken when the wheel is let go, at the heading then.
            vessel.assistHolding = false
            vessel.assistIntegral.setZero()
            errorOf(vessel)
            control.assistPitch = 0.0; control.assistYaw = 0.0
            control.assistRoll = slew(control.assistRoll, scale.y * command(errorBody.y, rateBody.y, 0.0), SLEW * dt)
            return
        }
        val holding = control.sasEnabled && !control.stickOverrides && !vessel.touchingGround
        if (!holding) {
            release(vessel)
            if (control.sasEnabled && control.stickOverrides && !vessel.touchingGround && !afloat) guard(vessel)
            return
        }

        if (!vessel.assistHolding) {
            vessel.assistHeld.setTo(body.orientation)
            vessel.assistIntegral.setZero()
            vessel.assistHolding = true
        }
        if (afloat || (direction == null && hangs(vessel))) levelled(vessel)
        if (direction != null) {
            // Turn the nose onto the marker the shortest way, keeping the craft's roll.
            vessel.forward(nose)
            com.rm.apogee.core.math.quatFromTo(nose, direction, turn)
            vessel.assistHeld.setTo(turn).mulInPlace(body.orientation)
        }

        errorOf(vessel)

        // Clamped, so it doesn't wind up against something the craft can't overcome.
        val integral = vessel.assistIntegral
        integral.addScaledInPlace(errorBody, integralGain * dt)
        integral.setTo(
            integral.x.coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT),
            integral.y.coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT),
            integral.z.coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT),
        )

        // X pitches, Y rolls, Z yaws, the reaction wheels' convention.
        val step = SLEW * dt
        control.assistPitch = if (afloat) 0.0 else slew(control.assistPitch, scale.x * command(errorBody.x, rateBody.x, integral.x), step)
        control.assistRoll = slew(control.assistRoll, scale.y * command(errorBody.y, rateBody.y, integral.y), step)
        control.assistYaw = slew(control.assistYaw, scale.z * command(errorBody.z, rateBody.z, integral.z), step)
        if (afloat) integral.x = 0.0
    }

    /**
     * The turn from where [vessel] points to where it should point, in its own axes, into
     * [errorBody], and its spin into [rateBody].
     */
    private fun errorOf(vessel: Vessel) {
        val body = vessel.body
        // conj(current) * held, the shortest way round.
        error.setTo(body.orientation).conjugateInPlace().mulInPlace(vessel.assistHeld)
        if (error.w < 0.0) error.setTo(-error.x, -error.y, -error.z, -error.w)
        val sine = sqrt(error.x * error.x + error.y * error.y + error.z * error.z)
        if (sine > 1e-12) {
            val angle = 2.0 * atan2(sine, error.w)
            errorBody.setTo(error.x, error.y, error.z).mulInPlace(angle / sine)
        } else {
            errorBody.setZero()
        }
        body.orientation.inverseRotate(body.angularVelocity, rateBody)
    }

    private fun slew(from: Double, to: Double, step: Double): Double = from + (to - from).coerceIn(-step, step)

    /**
     * The attitude a boat's helmsman holds: nose on the held heading, deck level with the local
     * horizon. Redone every tick since the horizon turns as the boat moves.
     */
    private fun levelled(vessel: Vessel) {
        val body = vessel.body
        val orientation = vessel.design.orientation
        up.setTo(body.position).normalizeInPlace()
        vessel.assistHeld.rotate(orientation.forward, heading)
        heading.addScaledInPlace(up, -(heading dot up))
        if (heading.lengthSq < 1e-9) return
        heading.normalizeInPlace()
        com.rm.apogee.core.math.quatFromTo(orientation.up, up, level)
        level.rotate(orientation.forward, nose)
        nose.addScaledInPlace(up, -(nose dot up)).normalizeInPlace()
        val swing = if ((nose dot heading) < -0.999999) Quat.fromAxisAngle(up, Math.PI) else com.rm.apogee.core.math.quatFromTo(nose, heading)
        vessel.assistHeld.setTo(swing).mulInPlace(level)
    }

    /**
     * Into [scale], each axis's share of the gains: all of it unless a full command on the control
     * surfaces would spin the craft up so fast that the rate term takes out more than [RATE_SHARE]
     * of the spin in a tick. Reaction wheels are left out: they don't grow with speed.
     */
    private fun scaleGains(vessel: Vessel, dt: Double) {
        val body = vessel.body
        val authority = vessel.surfaceAuthority
        scale.x = axisScale(body, 1.0, 0.0, 0.0, authority.x, dt)
        scale.y = axisScale(body, 0.0, 1.0, 0.0, authority.y, dt)
        scale.z = axisScale(body, 0.0, 0.0, 1.0, authority.z, dt)
    }

    private fun axisScale(body: com.rm.apogee.core.physics.RigidBody, x: Double, y: Double, z: Double, torque: Double, dt: Double): Double {
        body.orientation.rotate(axis.setTo(x, y, z), axis)
        // rad/s² at full command.
        val spinUp = torque * body.inverseInertiaAbout(axis)
        val perTick = derivativeGain * spinUp * dt
        return if (perTick > RATE_SHARE) RATE_SHARE / perTick else 1.0
    }

    /**
     * The stick steers, through [com.rm.apogee.core.craft.ControlState.assistPitch] and
     * [com.rm.apogee.core.craft.ControlState.assistYaw], but eased off as the pitch or yaw rate
     * nears [turnLimit] and pushed back past it. Roll is left to the stick.
     */
    private fun guard(vessel: Vessel) {
        if (hangs(vessel)) return
        val limit = turnLimit(vessel)

        if (limit <= 0.0) return
        val control = vessel.control
        vessel.body.orientation.inverseRotate(vessel.body.angularVelocity, rateBody)
        control.assistPitch = held(control.pitch, rateBody.x, limit, derivativeGain * scale.x)
        control.assistYaw = held(control.yaw, rateBody.z, limit, derivativeGain * scale.z)
        control.assistGuarding = true
    }

    /** [stick], kept to what keeps [rate] inside [limit], with [gain] of command per rad/s over. */
    private fun held(stick: Double, rate: Double, limit: Double, gain: Double): Double =
        stick.coerceIn(gain * (-limit - rate), gain * (limit - rate)).coerceIn(-1.0, 1.0)

    /**
     * The fastest pitch or yaw rate, rad/s, [vessel]'s wings take at this airspeed: the turn that
     * pulls [LOAD_SHARE] of the load they're built for, at most [MOST_G]. 0 for no limit: a craft
     * that doesn't fly on wings, as a rocket with fins, or one too slow to pull that before its
     * wings stall.
     */
    private fun turnLimit(vessel: Vessel): Double {
        val airspeed = vessel.airspeed
        val weight = vessel.body.mass * STANDARD_GRAVITY
        if (airspeed < 1.0 || weight <= 0.0) return 0.0
        var built = 0.0
        var lift = 0.0
        val enclosed = vessel.enclosed()
        for (i in vessel.defs.indices) {
            if (vessel.isBroken(i) || enclosed.getOrElse(i) { false }) continue
            val surface = vessel.defs[i].module<com.rm.apogee.core.part.AeroSurface>() ?: continue
            built += surface.loadLimit
            lift += surface.area * surface.liftCoefficient
        }
        if (built < WINGED_G * weight) return 0.0
        val g = (LOAD_SHARE * built / weight).coerceAtMost(MOST_G)
        if (vessel.dynamicPressure * lift <= g * weight) return 0.0
        return g * STANDARD_GRAVITY / airspeed
    }

    /** Whether [vessel] flies on a main rotor with cyclic, a helicopter. */
    private fun hangs(vessel: Vessel): Boolean =
        vessel.defs.any { def -> def.module<com.rm.apogee.core.part.Rotor>()?.let { !it.tail && it.cyclic > 0.0 } == true }

    /** Holds nothing and commands nothing, because there's no power to hold with. */
    fun idle(vessel: Vessel) = release(vessel)

    private fun release(vessel: Vessel) {
        vessel.assistHolding = false
        vessel.control.assistLevelling = false
        vessel.control.assistGuarding = false
        vessel.control.assistPitch = 0.0
        vessel.control.assistYaw = 0.0
        vessel.control.assistRoll = 0.0
    }

    private fun command(error: Double, rate: Double, integral: Double): Double =
        (proportionalGain * error - derivativeGain * rate + integral).coerceIn(-1.0, 1.0)

    private companion object {
        const val INTEGRAL_LIMIT = 0.6

        /** How fast a command can move, in full travel per second: end to end in a fifth of a second. */
        const val SLEW = 10.0

        /**
         * The most of the spin the rate term takes out in one tick. Past 1 it overshoots, and near
         * 2 it rings at the tick rate.
         */
        const val RATE_SHARE = 0.5

        /** Of the load the wings and fins are built for, the share a steered turn may pull. */
        const val LOAD_SHARE = 0.35

        /** The most g a steered turn pulls, however strong the wings. */
        const val MOST_G = 6.0

        /** How many g a craft's wings and fins must be built for to count as flying on them. */
        const val WINGED_G = 3.0

        /** One g, m/s², for load factors on any world. */
        const val STANDARD_GRAVITY = 9.81
    }
}
