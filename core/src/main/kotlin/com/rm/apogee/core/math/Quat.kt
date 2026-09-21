package com.rm.apogee.core.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A unit quaternion describing an orientation, in double precision.
 *
 * Convention: **right-handed, Y-up**, matching OpenGL. A craft's local frame is
 * +Y "up" through the nose of a rocket, +Z aft, +X starboard. Composition reads
 * left-to-right in the usual sense - `parent * child` applies `child` first,
 * then `parent`.
 *
 * Orientation is a quaternion rather than Euler angles because the simulation
 * integrates angular velocity every tick and Euler angles gimbal-lock exactly
 * where a rocket spends its time (straight up). [integrateAngularVelocity] is
 * the reason this type exists.
 *
 * Mutable with in-place operations, for the same no-allocation-in-step()
 * reason as [Vec3].
 */
class Quat(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
    @JvmField var z: Double = 0.0,
    @JvmField var w: Double = 1.0,
) {
    constructor(other: Quat) : this(other.x, other.y, other.z, other.w)

    fun setTo(x: Double, y: Double, z: Double, w: Double): Quat {
        this.x = x; this.y = y; this.z = z; this.w = w
        return this
    }

    fun setTo(other: Quat): Quat = setTo(other.x, other.y, other.z, other.w)

    fun setIdentity(): Quat = setTo(0.0, 0.0, 0.0, 1.0)

    fun copy() = Quat(x, y, z, w)

    // ---- composition -------------------------------------------------------

    /** Hamilton product: the rotation `this`, applied after [other]. */
    operator fun times(other: Quat) = Quat(
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
        w * other.w - x * other.x - y * other.y - z * other.z,
    )

    /** In-place `this = this * other`, allocation-free. */
    fun mulInPlace(other: Quat): Quat = setTo(
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
        w * other.w - x * other.x - y * other.y - z * other.z,
    )

    // ---- applying to vectors ----------------------------------------------

    /**
     * Rotates [v] from local space into the space this quaternion describes,
     * writing the result into [out] (which may alias [v]).
     *
     * Uses the standard `v + 2w(q x v) + 2(q x (q x v))` form rather than
     * building a matrix - cheaper for a handful of vectors, and this runs once
     * per part per tick for thrust directions.
     */
    fun rotate(v: Vec3, out: Vec3 = Vec3()): Vec3 {
        // t = 2 * (q_vec x v)
        val tx = 2.0 * (y * v.z - z * v.y)
        val ty = 2.0 * (z * v.x - x * v.z)
        val tz = 2.0 * (x * v.y - y * v.x)
        return out.setTo(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx),
        )
    }

    /** Rotates [v] from world space back into local space. */
    fun inverseRotate(v: Vec3, out: Vec3 = Vec3()): Vec3 {
        val tx = 2.0 * (z * v.y - y * v.z)
        val ty = 2.0 * (x * v.z - z * v.x)
        val tz = 2.0 * (y * v.x - x * v.y)
        return out.setTo(
            v.x + w * tx + (z * ty - y * tz),
            v.y + w * ty + (x * tz - z * tx),
            v.z + w * tz + (y * tx - x * ty),
        )
    }

    // ---- integration -------------------------------------------------------

    /**
     * Advances this orientation by angular velocity [omega] (rad/s, world
     * frame) over [dt] seconds, then renormalises.
     *
     * `dq/dt = 0.5 * omega_pure * q`, integrated with a single explicit Euler
     * step. That is first-order accurate, which is why renormalising every tick
     * is mandatory rather than an optimisation - without it the quaternion
     * drifts off the unit sphere and the craft visibly skews.
     */
    fun integrateAngularVelocity(omega: Vec3, dt: Double): Quat {
        val half = 0.5 * dt
        val dx = half * (omega.x * w + omega.y * z - omega.z * y)
        val dy = half * (omega.y * w + omega.z * x - omega.x * z)
        val dz = half * (omega.z * w + omega.x * y - omega.y * x)
        val dw = half * (-omega.x * x - omega.y * y - omega.z * z)
        return setTo(x + dx, y + dy, z + dz, w + dw).normalizeInPlace()
    }

    // ---- housekeeping ------------------------------------------------------

    fun normalizeInPlace(): Quat {
        val len = sqrt(x * x + y * y + z * z + w * w)
        if (len <= Vec3.EPSILON) return setIdentity()
        val inv = 1.0 / len
        return setTo(x * inv, y * inv, z * inv, w * inv)
    }

    /** For a unit quaternion this is also the inverse. */
    fun conjugate() = Quat(-x, -y, -z, w)

    fun conjugateInPlace(): Quat = setTo(-x, -y, -z, w)

    val length: Double get() = sqrt(x * x + y * y + z * z + w * w)

    infix fun dot(other: Quat): Double = x * other.x + y * other.y + z * other.z + w * other.w

    val isFinite: Boolean
        get() = x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()

    /**
     * True if both describe the same orientation. `q` and `-q` are the same
     * rotation, so the sign is normalised away before comparing.
     */
    fun approxEqualsRotation(other: Quat, tolerance: Double = 1e-9): Boolean =
        abs(abs(this dot other) - 1.0) <= tolerance

    override fun toString(): String = "($x, $y, $z, w=$w)"

    companion object {
        fun identity() = Quat(0.0, 0.0, 0.0, 1.0)

        fun fromAxisAngle(axis: Vec3, radians: Double, out: Quat = Quat()): Quat {
            val len = axis.length
            if (len <= Vec3.EPSILON) return out.setIdentity()
            val half = radians * 0.5
            val s = sin(half) / len
            return out.setTo(axis.x * s, axis.y * s, axis.z * s, cos(half))
        }

        /**
         * Shortest-arc interpolation, used to smooth remote vessels between the
         * 20 Hz server snapshots that drive them.
         */
        fun slerp(a: Quat, b: Quat, t: Double, out: Quat = Quat()): Quat {
            var cosom = a dot b
            // Take the short way round: q and -q are the same orientation, but
            // lerping toward the wrong one spins the craft the long way.
            var bx = b.x; var by = b.y; var bz = b.z; var bw = b.w
            if (cosom < 0.0) {
                cosom = -cosom; bx = -bx; by = -by; bz = -bz; bw = -bw
            }
            // Near-parallel: slerp's sin(omega) denominator vanishes, so fall
            // back to nlerp, which is indistinguishable at this angle.
            if (cosom > 0.9995) {
                return out.setTo(
                    a.x + (bx - a.x) * t,
                    a.y + (by - a.y) * t,
                    a.z + (bz - a.z) * t,
                    a.w + (bw - a.w) * t,
                ).normalizeInPlace()
            }
            val omega = acos(cosom)
            val sinom = sin(omega)
            val sa = sin((1.0 - t) * omega) / sinom
            val sb = sin(t * omega) / sinom
            return out.setTo(
                a.x * sa + bx * sb,
                a.y * sa + by * sb,
                a.z * sa + bz * sb,
                a.w * sa + bw * sb,
            )
        }
    }
}
