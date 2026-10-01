package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import kotlin.math.abs
import com.rm.apogee.core.math.Math
import com.rm.apogee.core.math.StrictMath

/**
 * A sphere addressed as the six faces of a cube, the grid terrain is sampled on. Cells stay within
 * about 1.4x of each other in size, and each face is a plain square grid. Face coordinates (-1..1)
 * are warped through tan(s * pi/4) to even out cell size. StrictMath throughout, so every machine
 * gets the same sample positions.
 */
object CubeSphere {

    /** Face normals, and the two axes that span each face. u x v = normal. */
    private val normals = arrayOf(
        Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0),
        Vec3(0.0, 1.0, 0.0), Vec3(0.0, -1.0, 0.0),
        Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0),
    )
    private val uAxes = arrayOf(
        Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0),
        Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0),
        Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0),
    )
    private val vAxes = arrayOf(
        Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 0.0),
        Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0),
        Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0),
    )

    /** tan(s * pi/4): face coordinate to the cube's own tangent-plane coordinate. */
    fun warp(s: Double): Double = StrictMath.tan(s * QUARTER_PI)

    /** The inverse of [warp]. */
    fun unwarp(a: Double): Double = StrictMath.atan(a) / QUARTER_PI

    /**
     * The unit direction on [face] at plane coordinates [a], [b] that have already been through
     * [warp]. Split out so a grid can warp each row and column once.
     */
    fun directionWarped(face: Int, a: Double, b: Double, out: Vec3): Vec3 {
        val n = normals[face]; val u = uAxes[face]; val v = vAxes[face]
        return out.setTo(
            n.x + a * u.x + b * v.x,
            n.y + a * u.y + b * v.y,
            n.z + a * u.z + b * v.z,
        ).normalizeInPlace()
    }

    fun direction(face: Int, s: Double, t: Double, out: Vec3): Vec3 =
        directionWarped(face, warp(s), warp(t), out)

    /**
     * Which face [direction] falls on, and where on it.
     *
     * @param out receives s in x and t in y, both -1..1.
     * @return the face index.
     */
    fun locate(direction: Vec3, out: Vec3): Int {
        val ax = abs(direction.x); val ay = abs(direction.y); val az = abs(direction.z)
        val face = when {
            ax >= ay && ax >= az -> if (direction.x >= 0.0) 0 else 1
            ay >= az -> if (direction.y >= 0.0) 2 else 3
            else -> if (direction.z >= 0.0) 4 else 5
        }
        val n = normals[face]; val u = uAxes[face]; val v = vAxes[face]
        val along = direction dot n
        out.setTo(unwarp((direction dot u) / along), unwarp((direction dot v) / along), 0.0)
        return face
    }

    private const val QUARTER_PI = Math.PI / 4.0
}
