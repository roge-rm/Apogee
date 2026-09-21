package com.rm.apogee.core.math

/**
 * A 3x3 matrix in double precision, row-major.
 *
 * Exists almost entirely for inertia tensors. Unlike [Mat4] - which is a float
 * buffer shaped for OpenGL - this one stays in the simulation's precision and
 * is never handed to the GPU.
 *
 * Indexing is `m[row * 3 + col]`.
 */
class Mat3(
    @JvmField val m: DoubleArray = DoubleArray(9),
) {
    init {
        require(m.size == 9) { "Mat3 needs exactly 9 elements, got ${m.size}" }
    }

    operator fun get(row: Int, col: Int): Double = m[row * 3 + col]
    operator fun set(row: Int, col: Int, value: Double) {
        m[row * 3 + col] = value
    }

    fun setIdentity(): Mat3 {
        m.fill(0.0)
        m[0] = 1.0; m[4] = 1.0; m[8] = 1.0
        return this
    }

    fun setZero(): Mat3 {
        m.fill(0.0)
        return this
    }

    fun setTo(other: Mat3): Mat3 {
        other.m.copyInto(m)
        return this
    }

    fun copy() = Mat3(m.copyOf())

    /** `out = this * v`. [out] may alias [v]. */
    fun transform(v: Vec3, out: Vec3 = Vec3()): Vec3 {
        val x = m[0] * v.x + m[1] * v.y + m[2] * v.z
        val y = m[3] * v.x + m[4] * v.y + m[5] * v.z
        val z = m[6] * v.x + m[7] * v.y + m[8] * v.z
        return out.setTo(x, y, z)
    }

    fun addInPlace(other: Mat3): Mat3 {
        for (i in 0..8) m[i] += other.m[i]
        return this
    }

    fun mulInPlace(scalar: Double): Mat3 {
        for (i in 0..8) m[i] *= scalar
        return this
    }

    /** `this = a * b`. Neither argument may alias `this`. */
    fun setMultiplied(a: Mat3, b: Mat3): Mat3 {
        for (row in 0..2) {
            for (col in 0..2) {
                var sum = 0.0
                for (k in 0..2) sum += a[row, k] * b[k, col]
                this[row, col] = sum
            }
        }
        return this
    }

    fun transposed(): Mat3 {
        val t = Mat3()
        for (row in 0..2) for (col in 0..2) t[col, row] = this[row, col]
        return t
    }

    /**
     * General 3x3 inverse by cofactors.
     *
     * An inertia tensor is symmetric positive-definite so it is always
     * invertible in principle, but a craft that is a single point mass (or a
     * degenerate one-part stack with no extent on an axis) can produce a
     * singular tensor. Rather than emit infinities that propagate silently
     * through the whole simulation, that case returns a zero matrix - which
     * reads downstream as "infinite inertia about that axis", i.e. it simply
     * will not rotate. Wrong, but inert and visible, instead of NaN.
     */
    fun inverted(): Mat3 {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]

        val cofactor0 = e * i - f * h
        val cofactor1 = f * g - d * i
        val cofactor2 = d * h - e * g
        val det = a * cofactor0 + b * cofactor1 + c * cofactor2

        if (det == 0.0 || !det.isFinite()) return Mat3()

        val invDet = 1.0 / det
        return Mat3(
            doubleArrayOf(
                cofactor0 * invDet, (c * h - b * i) * invDet, (b * f - c * e) * invDet,
                cofactor1 * invDet, (a * i - c * g) * invDet, (c * d - a * f) * invDet,
                cofactor2 * invDet, (b * g - a * h) * invDet, (a * e - b * d) * invDet,
            )
        )
    }

    /**
     * Rebuilds this as `R * I * R^T` - the local-frame inertia tensor [local]
     * expressed in world space for a body oriented by [rotation].
     *
     * Needed every tick: angular acceleration is `I⁻¹ * torque` with both in
     * the same frame, and torque accumulates in world space.
     */
    fun setRotated(local: Mat3, rotation: Quat): Mat3 {
        val r = fromQuat(rotation)
        val temp = Mat3().setMultiplied(r, local)
        return setMultiplied(temp, r.transposed())
    }

    override fun toString(): String = buildString {
        for (row in 0..2) {
            append("[")
            for (col in 0..2) {
                append(this@Mat3[row, col])
                if (col < 2) append(", ")
            }
            append("]")
            if (row < 2) append("\n")
        }
    }

    companion object {
        fun identity() = Mat3().setIdentity()

        fun diagonal(xx: Double, yy: Double, zz: Double) = Mat3(
            doubleArrayOf(xx, 0.0, 0.0, 0.0, yy, 0.0, 0.0, 0.0, zz)
        )

        fun fromQuat(q: Quat): Mat3 {
            val x = q.x; val y = q.y; val z = q.z; val w = q.w
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z
            val wx = w * x; val wy = w * y; val wz = w * z
            return Mat3(
                doubleArrayOf(
                    1.0 - 2.0 * (yy + zz), 2.0 * (xy - wz), 2.0 * (xz + wy),
                    2.0 * (xy + wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz - wx),
                    2.0 * (xz - wy), 2.0 * (yz + wx), 1.0 - 2.0 * (xx + yy),
                )
            )
        }

        /**
         * The parallel-axis (Huygens-Steiner) term for shifting a body of mass
         * [mass] whose tensor is about its own centre of mass to an axis
         * [offset] away: `m * ((r·r)E - r⊗r)`.
         *
         * This is what lets a craft's inertia be assembled by summing its
         * parts, which is the entire reason the builder can show a real
         * handling characteristic before anything is launched.
         */
        fun parallelAxisTerm(mass: Double, offset: Vec3): Mat3 {
            val rr = offset.lengthSq
            val x = offset.x; val y = offset.y; val z = offset.z
            return Mat3(
                doubleArrayOf(
                    mass * (rr - x * x), mass * (-x * y), mass * (-x * z),
                    mass * (-y * x), mass * (rr - y * y), mass * (-y * z),
                    mass * (-z * x), mass * (-z * y), mass * (rr - z * z),
                )
            )
        }
    }
}
