package com.rm.apogee.core.physics

import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3

/**
 * A single body with six degrees of freedom.
 *
 * One of these stands for a whole vessel. Every part of a craft is welded into one rigid body
 * instead of joined by springs, and that's a considered trade. A model with a spring per joint
 * makes long stacks flex and wobble, costs a solver pass per joint per tick, and on a phone just
 * isn't affordable. Decoupling a stage splits one body into two instead of releasing a joint, which
 * gives the same gameplay without the instability.
 *
 * All the state is double precision and changed in place, because this gets stepped 60 times a
 * second for every vessel in range and mustn't allocate.
 */
class RigidBody {

    /** The position of the centre of mass, in the current frame's coordinates. */
    val position = Vec3()

    /** The orientation of the body's local axes. */
    val orientation = Quat.identity()

    val linearVelocity = Vec3()

    /** Radians per second, in the world frame. */
    val angularVelocity = Vec3()

    var mass: Double = 1.0
        set(value) {
            field = value
            inverseMass = if (value > 0.0 && !fixed) 1.0 / value else 0.0
        }

    var inverseMass: Double = 1.0
        private set

    /**
     * Can't be moved, like an anchored base. Its mass and inertia are kept, but it answers every
     * push as if it were infinitely heavy. No impulse moves it and no contact shares a correction
     * with it, so everything that meets it works as normal, against something that doesn't give.
     * Whoever sets this poses the body themselves.
     */
    var fixed: Boolean = false
        set(value) {
            field = value
            inverseMass = if (mass > 0.0 && !value) 1.0 / mass else 0.0
            if (value) inverseInertiaLocal.setZero() else inverseInertiaLocal.setTo(inertiaLocal.inverted())
        }

    /** The inertia tensor around the centre of mass, in body-local axes. */
    val inertiaLocal: Mat3 = Mat3.identity()

    /** The inverse of [inertiaLocal]. It's worked out again by [setInertia]. */
    val inverseInertiaLocal: Mat3 = Mat3.identity()

    /** Scratch: [inverseInertiaLocal] in world axes. */
    private val inverseInertiaWorld: Mat3 = Mat3.identity()

    /** Added up over a tick, and cleared by [clearAccumulators]. */
    val force = Vec3()
    val torque = Vec3()

    // Scratch space made up front for the integration step.
    private val scratchA = Vec3()
    private val scratchB = Vec3()

    fun setInertia(tensor: Mat3) {
        inertiaLocal.setTo(tensor)
        if (fixed) inverseInertiaLocal.setZero() else inverseInertiaLocal.setTo(tensor.inverted())
    }

    fun clearAccumulators() {
        force.setZero()
        torque.setZero()
    }

    /** Adds a force through the centre of mass, with no torque. */
    fun applyCentralForce(worldForce: Vec3) {
        force.addInPlace(worldForce)
    }

    /**
     * Adds a force at [worldOffset] from the centre of mass, which makes both linear acceleration
     * and torque.
     *
     * This is how an off-axis engine turns a craft, and how a gimbal steers one.
     */
    fun applyForceAtOffset(worldForce: Vec3, worldOffset: Vec3) {
        force.addInPlace(worldForce)
        scratchA.setTo(worldOffset).crossInPlace(worldForce)
        torque.addInPlace(scratchA)
    }

    fun applyTorque(worldTorque: Vec3) {
        torque.addInPlace(worldTorque)
    }

    /** Applies an instant change in momentum through the centre of mass. */
    fun applyImpulse(worldImpulse: Vec3) {
        linearVelocity.addScaledInPlace(worldImpulse, inverseMass)
    }

    fun applyImpulseAtOffset(worldImpulse: Vec3, worldOffset: Vec3) {
        linearVelocity.addScaledInPlace(worldImpulse, inverseMass)
        scratchA.setTo(worldOffset).crossInPlace(worldImpulse)
        angularVelocityChangeFromAngularImpulse(scratchA)
    }

    /** Applies an instant change in angular momentum, in world axes. */
    fun applyAngularImpulse(worldAngularImpulse: Vec3) {
        scratchA.setTo(worldAngularImpulse)
        angularVelocityChangeFromAngularImpulse(scratchA)
    }

    /** How easily the body turns around the unit [axis]: axis . (I^-1 axis), in world axes. */
    fun inverseInertiaAbout(axis: Vec3): Double {
        inverseInertiaWorld.setRotated(inverseInertiaLocal, orientation)
        inverseInertiaWorld.transform(axis, scratchB)
        return scratchB dot axis
    }

    private fun angularVelocityChangeFromAngularImpulse(angularImpulse: Vec3) {
        inverseInertiaWorld.setRotated(inverseInertiaLocal, orientation)
        inverseInertiaWorld.transform(angularImpulse, scratchB)
        angularVelocity.addInPlace(scratchB)
    }

    /**
     * Moves forward one fixed step, with semi-implicit Euler.
     *
     * Velocity is updated before position (that's the "semi-implicit" part), which costs nothing
     * and is far more stable than the explicit order for things that oscillate, like a craft on its
     * landing legs.
     *
     * The gyroscopic term (`omega x (I omega)`) is left out on purpose. It matters for a body
     * tumbling freely around its middle axis, and leaving it out does lose a real effect. But
     * including it with an explicit integrator at 60 Hz adds energy and makes long stacks blow up,
     * which is a much worse failure. Worth looking at again alongside a proper implicit solver.
     */
    fun integrate(dt: Double) {
        if (inverseMass > 0.0) {
            linearVelocity.addScaledInPlace(force, inverseMass * dt)
            position.addScaledInPlace(linearVelocity, dt)
        }

        inverseInertiaWorld.setRotated(inverseInertiaLocal, orientation)
        inverseInertiaWorld.transform(torque, scratchA)
        angularVelocity.addScaledInPlace(scratchA, dt)

        orientation.integrateAngularVelocity(angularVelocity, dt)
    }

    /** The velocity of the point at [worldOffset] from the centre of mass. */
    fun velocityAtOffset(worldOffset: Vec3, out: Vec3 = Vec3()): Vec3 {
        out.setTo(angularVelocity).crossInPlace(worldOffset)
        return out.addInPlace(linearVelocity)
    }

    /** Kinetic energy, in J. Tests use it to catch an integrator adding energy. */
    val kineticEnergy: Double
        get() {
            val linear = 0.5 * mass * linearVelocity.lengthSq
            val iw = Mat3().setRotated(inertiaLocal, orientation)
            val angular = 0.5 * (angularVelocity dot iw.transform(angularVelocity))
            return linear + angular
        }

    override fun toString(): String =
        "RigidBody(pos=$position, vel=$linearVelocity, mass=${"%.1f".format(mass)})"
}
