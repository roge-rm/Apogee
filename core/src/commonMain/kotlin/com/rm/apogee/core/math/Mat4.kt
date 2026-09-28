package com.rm.apogee.core.math

import kotlin.math.tan
import kotlin.jvm.JvmField

/**
 * A 4x4 matrix in **float**, column-major, ready to pass straight to `glUniformMatrix4fv`.
 *
 * This is the one place floats are allowed, and it's deliberately the last step in the pipeline.
 * [setFromTrs] takes double world positions and a double camera position, does the subtraction in
 * double, and only then narrows to float. That subtraction is the floating origin, and it's what
 * lets a rocket on a launchpad and a 600 km planet share one frame without the rocket jumping in
 * visible steps. Narrowing before the subtraction would throw away exactly the precision the
 * subtraction was meant to keep.
 */
class Mat4 {
    /** Column-major, the way OpenGL wants it: [m] holds column 0 in indices 0..3. */
    @JvmField
    val m = FloatArray(16)

    init {
        setIdentity()
    }

    fun setIdentity(): Mat4 {
        m.fill(0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return this
    }

    /**
     * Builds a model matrix for an object at [worldPos] with orientation [rot], relative to a
     * camera at [cameraPos].
     *
     * The camera ends up at the origin of the rendered scene and every object is placed at
     * `worldPos - cameraPos`. See the class note on why the order of operations matters here.
     */
    fun setFromTrs(
        worldPos: Vec3,
        rot: Quat,
        cameraPos: Vec3,
        scale: Double = 1.0,
    ): Mat4 {
        // Floating origin: subtract in double, THEN narrow.
        val tx = (worldPos.x - cameraPos.x).toFloat()
        val ty = (worldPos.y - cameraPos.y).toFloat()
        val tz = (worldPos.z - cameraPos.z).toFloat()

        val x = rot.x; val y = rot.y; val z = rot.z; val w = rot.w
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        val s = scale

        m[0] = ((1.0 - 2.0 * (yy + zz)) * s).toFloat()
        m[1] = ((2.0 * (xy + wz)) * s).toFloat()
        m[2] = ((2.0 * (xz - wy)) * s).toFloat()
        m[3] = 0f

        m[4] = ((2.0 * (xy - wz)) * s).toFloat()
        m[5] = ((1.0 - 2.0 * (xx + zz)) * s).toFloat()
        m[6] = ((2.0 * (yz + wx)) * s).toFloat()
        m[7] = 0f

        m[8] = ((2.0 * (xz + wy)) * s).toFloat()
        m[9] = ((2.0 * (yz - wx)) * s).toFloat()
        m[10] = ((1.0 - 2.0 * (xx + yy)) * s).toFloat()
        m[11] = 0f

        m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
        return this
    }

    /**
     * [setFromTrs] with a different scale along each of the object's own axes, for things like a
     * cloud lobe, which is an ellipsoid stretched from a unit mesh.
     */
    fun setFromTrs(worldPos: Vec3, rot: Quat, cameraPos: Vec3, sx: Double, sy: Double, sz: Double): Mat4 {
        setFromTrs(worldPos, rot, cameraPos)
        for (k in 0..2) { m[k] = (m[k] * sx).toFloat(); m[4 + k] = (m[4 + k] * sy).toFloat(); m[8 + k] = (m[8 + k] * sz).toFloat() }
        return this
    }

    /**
     * The view matrix for a camera sitting at the scene origin with orientation [rot]. Because the
     * floating origin has already moved the world to meet the camera, the view matrix is only a
     * rotation: the inverse (conjugate) of the camera's orientation, with no translation at all.
     */
    fun setViewFromCameraRotation(rot: Quat): Mat4 {
        val x = -rot.x; val y = -rot.y; val z = -rot.z; val w = rot.w
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z

        m[0] = (1.0 - 2.0 * (yy + zz)).toFloat()
        m[1] = (2.0 * (xy + wz)).toFloat()
        m[2] = (2.0 * (xz - wy)).toFloat()
        m[3] = 0f
        m[4] = (2.0 * (xy - wz)).toFloat()
        m[5] = (1.0 - 2.0 * (xx + zz)).toFloat()
        m[6] = (2.0 * (yz + wx)).toFloat()
        m[7] = 0f
        m[8] = (2.0 * (xz + wy)).toFloat()
        m[9] = (2.0 * (yz - wx)).toFloat()
        m[10] = (1.0 - 2.0 * (xx + yy)).toFloat()
        m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
        return this
    }

    /**
     * Standard OpenGL perspective projection.
     *
     * The depth range this game needs runs from about 0.1 m (a part in the builder) to 10^7 m (a
     * planet from orbit), which no single near/far pair can handle. The renderer deals with that
     * using a logarithmic depth term in the vertex shader instead of here, because GLES has no
     * reliable `glClipControl`, so reversed-Z isn't an option.
     */
    fun setPerspective(fovYRadians: Double, aspect: Double, near: Double, far: Double): Mat4 {
        val f = 1.0 / tan(fovYRadians * 0.5)
        m.fill(0f)
        m[0] = (f / aspect).toFloat()
        m[5] = f.toFloat()
        m[10] = ((far + near) / (near - far)).toFloat()
        m[11] = -1f
        m[14] = ((2.0 * far * near) / (near - far)).toFloat()
        return this
    }

    /** `this = a * b`. Neither argument can be the same object as `this`. */
    fun setMultiplied(a: Mat4, b: Mat4): Mat4 {
        val am = a.m
        val bm = b.m
        for (col in 0..3) {
            val c = col * 4
            val b0 = bm[c]; val b1 = bm[c + 1]; val b2 = bm[c + 2]; val b3 = bm[c + 3]
            m[c] = am[0] * b0 + am[4] * b1 + am[8] * b2 + am[12] * b3
            m[c + 1] = am[1] * b0 + am[5] * b1 + am[9] * b2 + am[13] * b3
            m[c + 2] = am[2] * b0 + am[6] * b1 + am[10] * b2 + am[14] * b3
            m[c + 3] = am[3] * b0 + am[7] * b1 + am[11] * b2 + am[15] * b3
        }
        return this
    }

    fun setTo(other: Mat4): Mat4 {
        other.m.copyInto(m)
        return this
    }
}
