package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.Rotor
import kotlin.math.sqrt
import kotlin.math.tan
import com.rm.apogee.core.math.Math

/**
 * Rotors: lift from each one along its axis.
 *
 * The throttle is the collective. Several rotors (a drone, a platform) are mixed: each one's
 * collective is nudged by how much it helps the turn asked for, from where it sits and which way
 * it spins. A rotor with cyclic (a helicopter's main rotor) tilts its lift instead, and a tail
 * rotor cancels the main rotor's twist plus whatever yaw is asked for.
 *
 * Lift goes as the square root of air density (the engine's power is limited), gains a little in
 * forward flight and near the ground, and loses some climbing through its own downwash.
 *
 * Each rotor's speed ([Vessel.spool]) eases toward what's asked, and lift goes with its square. A
 * tail rotor is geared to the main rotor's speed.
 */
class Rotors {
    private val command = Vec3()
    private val offset = Vec3()
    private val lift = Vec3()
    private val lever = Vec3()
    private val tilt = Vec3()
    private val force = Vec3()
    private val world = Vec3()
    private val twist = Vec3()
    private val up = Vec3()
    private val airVelocity = Vec3()
    private val scratch = Vec3()
    private val middle = Vec3()

    /** The mixed rotors by part, and each one's steering effect per newton. */
    private var indices = IntArray(0)
    private var levers = Array(0) { Vec3() }

    /**
     * Turns [vessel]'s rotors for a tick of [dt], with the planet at [bodyRotation] and the air
     * already sampled into [Vessel.air].
     */
    fun apply(vessel: Vessel, attractor: CelestialBody, bodyRotation: Quat, time: Double, dt: Double) {
        vessel.fitPose()
        val defs = vessel.defs
        var count = 0
        for (i in defs.indices) if (defs[i].module<Rotor>() != null) count++
        if (count == 0) return
        for (i in defs.indices) if (defs[i].module<Rotor>() != null) vessel.engineOutput[i] = 0.0
        val throttle = vessel.control.throttle
        val air = attractor.atmosphere
        val altitude = attractor.altitudeOf(vessel.body.position)
        val density = air?.densityAt(altitude) ?: 0.0
        // Nothing asked of them, or no air to bite: they wind down.
        if (throttle <= 0.0 || density <= 0.0) {
            spinDown(vessel, dt)
            return
        }
        val thickness = sqrt(density / SEA_LEVEL_DENSITY).coerceAtMost(THICK_AIR)

        val control = vessel.control
        val spins = vessel.rotorSpin
        // The turn asked for, in the craft's own axes: x pitch, y roll, z yaw.
        command.setTo(control.commandPitch, control.commandRoll, control.commandYaw)
        vessel.centerOfMass(middle)
        vessel.body.orientation.inverseRotate(scratch.setTo(vessel.body.position).normalizeInPlace(), up)

        // Its motion through the air, for forward flight and climbing, and how high it is.
        attractor.surfaceVelocityAt(vessel.body.position, airVelocity).negateInPlace().addInPlace(vessel.body.linearVelocity)
        airVelocity.subInPlace(bodyRotation.rotate(vessel.air.wind, scratch))
        val height = heightOver(vessel, attractor, bodyRotation)

        if (indices.size < count) { indices = IntArray(count); levers = Array(count) { Vec3() } }

        // First the lifting rotors that mix: how much each would help the turn, per newton.
        var n = 0
        var most = 0.0
        for (i in defs.indices) {
            val rotor = defs[i].module<Rotor>() ?: continue
            if (rotor.tail || rotor.cyclic > 0.0) continue
            if (!working(vessel, i)) { windDown(vessel, i, rotor, dt); continue }
            steering(vessel, i, rotor, spins[i], levers[n])
            most = maxOf(most, levers[n].length)
            indices[n++] = i
        }
        // What the lifting rotors twist the craft by, in its own axes, for the tail to cancel.
        twist.setZero()
        for (k in 0 until n) {
            val i = indices[k]
            val rotor = defs[i].module<Rotor>()!!
            val share = if (most > 1e-9) (levers[k] dot command) / most else 0.0
            val output = (throttle + MIX * share).coerceIn(0.0, 1.0)
            spinOff(vessel, i, rotor, spins[i], output, thickness, height, dt)
        }
        // Main rotors with cyclic: collective as it is, lift tilted by the stick.
        for (i in defs.indices) {
            val rotor = defs[i].module<Rotor>() ?: continue
            if (rotor.tail || rotor.cyclic <= 0.0) continue
            if (!working(vessel, i)) { windDown(vessel, i, rotor, dt); continue }
            spinOff(vessel, i, rotor, spins[i], throttle, thickness, height, dt, cyclic = true)
        }
        // Tail rotors: against the twist about the craft's up, plus the turn asked for about it.
        val designUp = vessel.design.orientation.up
        var tails = 0
        for (i in defs.indices) {
            val rotor = defs[i].module<Rotor>() ?: continue
            if (!rotor.tail) continue
            if (working(vessel, i)) tails++ else windDown(vessel, i, rotor, dt)
        }
        if (tails == 0) return
        val geared = mainSpeed(vessel)
        val against = -(twist dot designUp) / tails
        for (i in defs.indices) {
            val rotor = defs[i].module<Rotor>() ?: continue
            if (!rotor.tail || !working(vessel, i)) continue
            steering(vessel, i, rotor, 0, lever)
            val perNewton = lever dot designUp
            if (kotlin.math.abs(perNewton) < 1e-6) continue
            // As much as it can push at the speed it's turning, which is the main rotor's.
            val speed = geared ?: vessel.spool[i]
            val most = rotor.lift * thickness * (speed * speed).coerceAtLeast(MIN_TAIL_GRIP)
            val turn = TAIL_TURN * most * kotlin.math.abs(perNewton) * (command dot designUp)
            val output = ((against + turn) / perNewton / most).coerceIn(-1.0, 1.0)
            spinOff(vessel, i, rotor, 0, output, thickness, height, dt, geared = geared)
        }
    }

    /** Every rotor winding down together: the throttle's shut, or there's no air to turn in. */
    private fun spinDown(vessel: Vessel, dt: Double) {
        val defs = vessel.defs
        for (i in defs.indices) {
            val rotor = defs[i].module<Rotor>() ?: continue
            if (!rotor.tail) windDown(vessel, i, rotor, dt)
        }
        val geared = mainSpeed(vessel)
        for (i in defs.indices) {
            val rotor = defs[i].module<Rotor>() ?: continue
            if (!rotor.tail) continue
            if (geared != null && working(vessel, i)) {
                vessel.spool[i] = geared
                vessel.engineOutput[i] = geared
            } else {
                windDown(vessel, i, rotor, dt)
            }
        }
    }

    /** Rotor [i] slowing on its own, with nothing driving it. */
    private fun windDown(vessel: Vessel, i: Int, rotor: Rotor, dt: Double) {
        vessel.spool[i] = ease(vessel.spool[i], 0.0, rotor, dt)
        if (vessel.spool[i] < 1e-3) vessel.spool[i] = 0.0
        vessel.engineOutput[i] = vessel.spool[i]
    }

    /** The fastest main rotor's speed, which tail rotors are geared to. Null if it has none. */
    private fun mainSpeed(vessel: Vessel): Double? {
        val defs = vessel.defs
        var fastest: Double? = null
        for (i in defs.indices) {
            val rotor = defs[i].module<Rotor>() ?: continue
            if (rotor.tail || rotor.torque <= 0.0 || !working(vessel, i)) continue
            fastest = maxOf(fastest ?: 0.0, vessel.spool[i])
        }
        return fastest
    }

    /** [from] a tick of [dt] closer to [to], at rotor's own pace. */
    private fun ease(from: Double, to: Double, rotor: Rotor, dt: Double): Double =
        from + (to - from) * (1.0 - kotlin.math.exp(-dt / spoolTime(rotor)))

    /** Whether rotor [i] can turn: whole, and not in a group switched off. */
    private fun working(vessel: Vessel, i: Int): Boolean = !vessel.isBroken(i) && vessel.groupState(i) >= 0

    /**
     * What a newton of rotor [i]'s lift does to the craft's turning, in craft axes, into [out]: its
     * lever about the centre of mass, plus its counter-twist.
     */
    private fun steering(vessel: Vessel, i: Int, rotor: Rotor, spin: Int, out: Vec3): Vec3 {
        val placed = vessel.design.parts[i]
        placed.rotation.rotate(rotor.liftDirection, lift).normalizeInPlace()
        offset.setTo(placed.position).subInPlace(middle)
        out.setTo(offset).crossInPlace(lift)
        out.addScaledInPlace(lift, -spin * rotor.torque)
        return out
    }

    /**
     * Rotor [i] at [output] collective (a tail rotor's can go negative): spool, lift, fuel, twist,
     * and tilt for [cyclic]. A tail rotor turns at [geared] if given, and [output] sets only its pitch.
     */
    private fun spinOff(
        vessel: Vessel, i: Int, rotor: Rotor, spin: Int, output: Double, thickness: Double, height: Double, dt: Double,
        cyclic: Boolean = false, geared: Double? = null,
    ) {
        val placed = vessel.design.parts[i]
        placed.rotation.rotate(rotor.liftDirection, lift).normalizeInPlace()
        offset.setTo(placed.position).subInPlace(middle)
        // Forward flight and the ground help it, and climbing through its own wash costs it. Not a
        // tail rotor, whose pitch is set for exactly the push it needs.
        vessel.body.orientation.rotate(lift, world)
        val through = airVelocity.length
        val climbing = (airVelocity dot world).coerceAtLeast(0.0)
        val boost = if (rotor.tail) 1.0 else (1.0 + TRANSLATIONAL * (through / TRANSLATIONAL_SPEED).coerceAtMost(1.0)) *
            (1.0 + GROUND_EFFECT * (1.0 - height / rotor.diameter.coerceAtLeast(0.1)).coerceIn(0.0, 1.0)) *
            (1.0 - climbing / INFLOW).coerceIn(MOST_LOST, 1.0)
        // Lift from its speed now. Spooled up, speed squared equals what was asked.
        val speed = if (rotor.tail && geared != null) geared else vessel.spool[i]
        val pull = if (rotor.tail) output * speed * speed else speed * speed
        var thrust = rotor.lift * thickness * boost * pull
        // Fuel for what it lifts.
        val needed = kotlin.math.abs(thrust) * dt / rotor.efficiency
        val feed = if (rotor.propellant == ResourceType.ELECTRIC_CHARGE) {
            if (vessel.drawCharge(needed)) 1.0 else 0.0
        } else {
            val units = needed / rotor.propellant.densityPerUnit
            if (units <= 0.0) 1.0 else vessel.drainFromGroupOf(i, rotor.propellant, units) / units
        }
        // Then its speed, toward what's asked while it's fed, and down while it isn't.
        if (rotor.tail && geared != null) {
            vessel.spool[i] = geared
        } else {
            val wanted = if (feed > 0.0) sqrt(kotlin.math.abs(output).coerceAtMost(1.0)) else 0.0
            vessel.spool[i] = ease(vessel.spool[i], wanted, rotor, dt)
        }
        vessel.engineOutput[i] = vessel.spool[i]
        if (feed <= 0.0 || thrust == 0.0) { recordTilt(vessel, i); return }
        thrust *= feed

        val hangs = cyclic && hang(vessel, rotor, thrust)
        if (cyclic) {
            // Tilt toward (command across the axis) x axis, reversed for a rotor below the middle.
            tilt.setTo(command).addScaledInPlace(lift, -(command dot lift))
            val ask = tilt.length.coerceAtMost(1.0)
            if (ask > 1e-6) {
                tilt.crossInPlace(lift).normalizeInPlace()
                if ((offset dot lift) < 0.0) tilt.negateInPlace()
                lift.addScaledInPlace(tilt, tan(Math.toRadians(rotor.cyclic)) * ask).normalizeInPlace()
                // The rotor head's push on the mast, most of what turns a helicopter.
                tilt.setTo(command).addScaledInPlace(lift, -(command dot lift))
                if (tilt.length > 1.0) tilt.normalizeInPlace()
                vessel.body.orientation.rotate(tilt, scratch)
                vessel.body.applyTorque(scratch.mulInPlace(HUB_MOMENT * rotor.diameter * kotlin.math.abs(thrust)))
            }
        }
        recordTilt(vessel, i)
        vessel.body.orientation.rotate(lift, world)
        force.setTo(world).mulInPlace(thrust)
        // A hanging craft's lift goes through its middle, or the head's offset would tip it over. A
        // tail rotor only turns the craft: the main rotor is rigged to lean against its sideways push.
        vessel.body.orientation.rotate(offset, scratch)
        when {
            hangs -> vessel.body.applyCentralForce(force)
            rotor.tail && geared != null -> vessel.body.applyTorque(scratch.crossInPlace(force))
            else -> vessel.body.applyForceAtOffset(force, scratch)
        }
        vessel.recordForce(i, force)
        // Turning the blades one way turns the craft the other.
        if (spin != 0 && rotor.torque > 0.0) {
            twist.addScaledInPlace(lift, -spin * rotor.torque * thrust)
            vessel.body.applyTorque(scratch.setTo(world).mulInPlace(-spin * rotor.torque * thrust))
        }
    }

    private val mast = Vec3()
    private val spin = Vec3()
    private val levelling = Vec3()

    /**
     * A helicopter hangs under its rotor: as the craft tips, the rotor pulls the mast back upright,
     * damped. The stick's push on the head tips it against that, so the body leans the way it's
     * flying. [lift] and [offset] are set for the rotor on entry. False if the rotor isn't over the
     * craft, as one below would tip it over.
     */
    private fun hang(vessel: Vessel, rotor: Rotor, thrust: Double): Boolean {
        val reach = offset.length
        if (reach < HANG_LEAST || (offset dot lift) <= 0.0) return false
        mast.setTo(lift)
        // Levelling: the mast pulled back upright, turning about mast x up.
        levelling.setTo(mast).crossInPlace(up).mulInPlace(HANG_STIFFNESS * kotlin.math.abs(thrust) * reach)
        // Damped by the rotor, against tipping only, not turning about its own axis.
        vessel.body.orientation.inverseRotate(vessel.body.angularVelocity, spin)
        spin.addScaledInPlace(mast, -(spin dot mast))
        levelling.addScaledInPlace(spin, -HANG_DAMPING * kotlin.math.abs(thrust) * rotor.diameter)
        vessel.body.applyTorque(vessel.body.orientation.rotate(levelling, scratch))
        return true
    }

    /** Which way rotor [i]'s lift points now, [lift] in the craft's axes, for drawing its disc. */
    private fun recordTilt(vessel: Vessel, i: Int) {
        val tilts = vessel.rotorTilt
        if (i * 3 + 2 >= tilts.size) return
        tilts[i * 3] = lift.x; tilts[i * 3 + 1] = lift.y; tilts[i * 3 + 2] = lift.z
    }

    private val lookUp = Vec3()

    /** How high [vessel] is over the ground or sea under it, in metres. */
    private fun heightOver(vessel: Vessel, attractor: CelestialBody, bodyRotation: Quat): Double {
        val altitude = attractor.altitudeOf(vessel.body.position)
        val terrain = attractor.terrain ?: return altitude
        attractor.toBodyFixed(vessel.body.position, bodyRotation, lookUp).normalizeInPlace()
        return altitude - maxOf(terrain.elevation(lookUp), 0.0)
    }

    companion object {
        /** Terra's air at sea level, in kg/m³, where a rotor gives its rated lift. */
        const val SEA_LEVEL_DENSITY = 1.225

        /** The most thick air multiplies a rotor's lift by, since its engine's power runs out. */
        const val THICK_AIR = 2.5

        /**
         * Time constant for a rotor's speed, in seconds. Electric motors are quick (mixed rotors
         * hunt if they lag); an engine-driven eight metre rotor takes about a second and a half.
         */
        fun spoolTime(rotor: Rotor): Double =
            if (rotor.propellant == ResourceType.ELECTRIC_CHARGE) MOTOR_BASE + MOTOR_PER_METRE * rotor.diameter
            else SPOOL_BASE + SPOOL_PER_METRE * rotor.diameter
        const val SPOOL_BASE = 0.05
        const val SPOOL_PER_METRE = 0.18
        const val MOTOR_BASE = 0.04
        const val MOTOR_PER_METRE = 0.04

        /** The least tail rotor grip, as a share of full lift, so it never divides by zero. */
        const val MIN_TAIL_GRIP = 1e-3

        /**
         * Hanging under a rotor: the pull back upright (per newton of lift per metre to the head),
         * the damping (per newton per metre of rotor), and the least middle-to-head distance it
         * works at.
         */
        const val HANG_STIFFNESS = 1.6
        const val HANG_DAMPING = 0.09
        const val HANG_LEAST = 0.1

        /** How far, as a share of the collective, the stick can move one mixed rotor. */
        const val MIX = 0.35

        /** A rotor head's push at full cyclic, in newton-metres per newton of lift per metre of rotor. */
        const val HUB_MOMENT = 0.08

        /** How hard a tail rotor turns the craft at full stick, as a share of its most lift. */
        const val TAIL_TURN = 0.5

        /** Extra lift in forward flight, as a share, reached at [TRANSLATIONAL_SPEED] m/s. */
        const val TRANSLATIONAL = 0.15
        const val TRANSLATIONAL_SPEED = 15.0

        /** Extra lift close over the ground, as a share, fading out by one rotor's width up. */
        const val GROUND_EFFECT = 0.1

        /** Climbing this fast along its axis, in m/s, would lose all its lift; and the least share kept. */
        const val INFLOW = 25.0
        const val MOST_LOST = 0.4
    }
}
