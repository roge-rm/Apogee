package com.rm.apogee.core.physics

import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3

/**
 * A single six-degree-of-freedom body.
 *
 * One of these backs an entire vessel: every part of a craft is welded into one
 * rigid body rather than joined by springs. That is a considered trade. A
 * per-joint model is the direct cause of its wobbling-rocket problem and most
 * of its CPU cost, and on a phone it is simply not affordable. Decoupling a
 * stage splits one body into two rather than releasing a joint, which gives the
 * same gameplay without the instability.
 *
 * All state is double precision and mutable in place - this is stepped 60 times
 * a second for every vessel in range, and must not allocate.
 */
class RigidBody {

    /** Position of the centre of mass, in the current frame's coordinates. */
    val position = Vec3()

    /** Orientation of the body's local axes. */
    val orientation = Quat.identity()

    val linearVelocity = Vec3()

    /** Radians per second, world frame. */
    val angularVelocity = Vec3()

    var mass: Double = 1.0
        set(value) {
            field = value
            inverseMass = if (value > 0.0) 1.0 / value else 0.0
        }

    var inverseMass: Double = 1.0
        private set

    /** Inertia tensor about the centre of mass, in body-local axes. */
    val inertiaLocal: Mat3 = Mat3.identity()

    /** Inverse of [inertiaLocal]. Recomputed by [setInertia]. */
    val inverseInertiaLocal: Mat3 = Mat3.identity()

    /** Scratch: [inverseInertiaLocal] expressed in world axes. */
    private val inverseInertiaWorld: Mat3 = Mat3.identity()

    /** Accumulated over a tick, cleared by [clearAccumulators]. */
    val force = Vec3()
    val torque = Vec3()

    // Preallocated scratch for the integration step.
    private val scratchA = Vec3()
    private val scratchB = Vec3()

    fun setInertia(tensor: Mat3) {
        inertiaLocal.setTo(tensor)
        inverseInertiaLocal.setTo(tensor.inverted())
    }

    fun clearAccumulators() {
        force.setZero()
        torque.setZero()
    }

    /** Adds a force through the centre of mass - no torque. */
    fun applyCentralForce(worldForce: Vec3) {
        force.addInPlace(worldForce)
    }

    /**
     * Adds a force at [worldOffset] from the centre of mass, producing both
     * linear acceleration and torque.
     *
     * This is how an off-axis engine turns a craft, and how a gimbal steers
     * one.
     */
    fun applyForceAtOffset(worldForce: Vec3, worldOffset: Vec3) {
        force.addInPlace(worldForce)
        scratchA.setTo(worldOffset).crossInPlace(worldForce)
        torque.addInPlace(scratchA)
    }

    fun applyTorque(worldTorque: Vec3) {
        torque.addInPlace(worldTorque)
    }

    /** Applies an instantaneous change in momentum through the centre of mass. */
    fun applyImpulse(worldImpulse: Vec3) {
        linearVelocity.addScaledInPlace(worldImpulse, inverseMass)
    }

    fun applyImpulseAtOffset(worldImpulse: Vec3, worldOffset: Vec3) {
        linearVelocity.addScaledInPlace(worldImpulse, inverseMass)
        scratchA.setTo(worldOffset).crossInPlace(worldImpulse)
        angularVelocityChangeFromAngularImpulse(scratchA)
    }

    private fun angularVelocityChangeFromAngularImpulse(angularImpulse: Vec3) {
        inverseInertiaWorld.setRotated(inverseInertiaLocal, orientation)
        inverseInertiaWorld.transform(angularImpulse, scratchB)
        angularVelocity.addInPlace(scratchB)
    }

    /**
     * Advances one fixed step, semi-implicit Euler.
     *
     * Velocity is updated before position - the "semi-implicit" part - which
     * costs nothing and is dramatically more stable than the explicit ordering
     * for oscillatory systems like a craft on its landing legs.
     *
     * The gyroscopic term (`omega x (I omega)`) is deliberately omitted. It
     * matters for a body tumbling freely about an intermediate axis, and
     * omitting it costs a real effect - but including it with an explicit
     * integrator at 60 Hz injects energy and makes long stacks diverge, which
     * is a far worse failure. Revisit alongside a proper implicit solver.
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

    /** Velocity of the point at [worldOffset] from the centre of mass. */
    fun velocityAtOffset(worldOffset: Vec3, out: Vec3 = Vec3()): Vec3 {
        out.setTo(angularVelocity).crossInPlace(worldOffset)
        return out.addInPlace(linearVelocity)
    }

    /** Kinetic energy, J. Used by tests to detect an integrator injecting energy. */
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
