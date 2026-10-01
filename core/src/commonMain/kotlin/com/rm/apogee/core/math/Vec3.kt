package com.rm.apogee.core.math

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.jvm.JvmField

/**
 * A double 3-vector.
 *
 * The simulation is all `Double`. Craft sit millions of metres from the barycentre, and float32's 7
 * digits can't resolve centimetres a few hundred km out, so a landed rocket would jitter. Floats
 * appear only at the end, after the floating-origin subtraction in [Mat4.setFromTrs].
 *
 * Mutable, with chainable in-place ops returning `this`. `World.step()` runs 60 times a second over
 * every vessel, and allocating per force term causes GC jitter. Hot paths use
 * [addInPlace]/[mulInPlace]/[setTo] on scratch vectors; the allocating `operator` forms are for
 * setup and tests.
 */
class Vec3(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
    @JvmField var z: Double = 0.0,
) {
    constructor(other: Vec3) : this(other.x, other.y, other.z)

    // ---- in-place: use these inside the simulation loop --------------------

    fun setTo(x: Double, y: Double, z: Double): Vec3 {
        this.x = x; this.y = y; this.z = z
        return this
    }

    fun setTo(other: Vec3): Vec3 = setTo(other.x, other.y, other.z)

    fun setZero(): Vec3 = setTo(0.0, 0.0, 0.0)

    fun addInPlace(other: Vec3): Vec3 = setTo(x + other.x, y + other.y, z + other.z)

    /** `this += other * scale`, the usual step when summing forces. */
    fun addScaledInPlace(other: Vec3, scale: Double): Vec3 =
        setTo(x + other.x * scale, y + other.y * scale, z + other.z * scale)

    fun subInPlace(other: Vec3): Vec3 = setTo(x - other.x, y - other.y, z - other.z)

    fun mulInPlace(scalar: Double): Vec3 = setTo(x * scalar, y * scalar, z * scalar)

    fun negateInPlace(): Vec3 = setTo(-x, -y, -z)

    fun crossInPlace(other: Vec3): Vec3 = setTo(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    /** Scales to unit length, or leaves a zero vector alone. */
    fun normalizeInPlace(): Vec3 {
        val len = length
        return if (len > EPSILON) mulInPlace(1.0 / len) else this
    }

    // ---- allocating: setup, tests, cold paths ------------------------------

    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Double) = Vec3(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Double) = Vec3(x / scalar, y / scalar, z / scalar)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun normalized(): Vec3 {
        val len = length
        return if (len > EPSILON) Vec3(x / len, y / len, z / len) else Vec3()
    }

    fun copy() = Vec3(x, y, z)

    // ---- queries -----------------------------------------------------------

    infix fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    val lengthSq: Double get() = x * x + y * y + z * z
    val length: Double get() = sqrt(lengthSq)

    fun distanceTo(other: Vec3): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    val isFinite: Boolean
        get() = x.isFinite() && y.isFinite() && z.isFinite()

    fun approxEquals(other: Vec3, tolerance: Double = EPSILON): Boolean =
        abs(x - other.x) <= tolerance &&
            abs(y - other.y) <= tolerance &&
            abs(z - other.z) <= tolerance

    override fun toString(): String = "($x, $y, $z)"

    /** Exact value equality, for tests and map keys. Use [approxEquals] after integration. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Vec3 && x == other.x && y == other.y && z == other.z)

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        return result
    }

    companion object {
        const val EPSILON = 1e-12

        fun zero() = Vec3(0.0, 0.0, 0.0)
        fun unitX() = Vec3(1.0, 0.0, 0.0)
        fun unitY() = Vec3(0.0, 1.0, 0.0)
        fun unitZ() = Vec3(0.0, 0.0, 1.0)
    }
}

operator fun Double.times(v: Vec3) = Vec3(this * v.x, this * v.y, this * v.z)
