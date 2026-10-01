package com.rm.apogee.core.physics

import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.fixed

/**
 * A single six-degree-of-freedom body standing for a whole vessel. Parts are welded into one rigid
 * body, not sprung joints, which is stable and cheap on a phone; staging splits it in two. Double
 * precision, changed in place, and stepped 60 times a second, so it mustn't allocate.
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
     * Can't be moved, like an anchored base. Mass and inertia are kept, but it answers every push as
     * if infinitely heavy, so whatever meets it works normally against something that doesn't give.
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

    /** The inverse of [inertiaLocal], worked out by [setInertia]. */
    val inverseInertiaLocal: Mat3 = Mat3.identity()

    /** Scratch: [inverseInertiaLocal] in world axes. */
    private val inverseInertiaWorld: Mat3 = Mat3.identity()

    /** Summed over a tick and cleared by [clearAccumulators]. */
    val force = Vec3()
    val torque = Vec3()

    // Scratch for the integration step.
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
     * Adds a force at [worldOffset] from the centre of mass, giving both acceleration and torque.
     * How an off-axis engine or a gimbal turns a craft.
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
     * One step of semi-implicit Euler (velocity before position), which is stable for things that
     * oscillate, like a craft on its legs. The gyroscopic term `omega x (I omega)` is left out: with
     * an explicit integrator at 60 Hz it adds energy and blows up long stacks.
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
        "RigidBody(pos=$position, vel=$linearVelocity, mass=${fixed(mass, 1)})"
}
