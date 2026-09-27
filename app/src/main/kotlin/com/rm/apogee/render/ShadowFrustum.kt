package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3

/**
 * Where a shadow map looks from: a box along the light, [radius] each way across it and [depth]
 * each way along it, centred on a point.
 *
 * It's built camera-relative, like everything drawn (a position handed to the shader is metres from
 * the camera), but its centre is snapped to whole texels in the light's own axes, measured from the
 * world's origin. So a moving camera or craft slides the map a texel at a time, and every shadow
 * edge stays put on the ground instead of crawling.
 *
 * It's pure, with no GL here, so it gets tested on its own.
 */
class ShadowFrustum {
    /** Camera-relative world to clip space, for drawing the map. Column-major. */
    val viewProjection = FloatArray(16)

    /** Camera-relative world to the map's texture, [0,1] each way. Column-major. */
    val texture = FloatArray(16)

    /** How big one texel is on the ground, in metres. */
    var texelSize = 1.0
        private set

    private val right = Vec3(); private val up = Vec3(); private val along = Vec3()
    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0
    private var radius = 1.0; private var depth = 1.0

    /**
     * Aims it along [toLight] (unit, towards the light) at [centre], for a camera at [camera], both
     * absolute, with [texels] a side.
     */
    fun update(toLight: Vec3, centre: Vec3, camera: Vec3, radius: Double, depth: Double, texels: Int) {
        aim(toLight, centre, radius, depth, texels)
        place(camera, null)
    }

    /**
     * Sets where it looks: along [toLight] at [centre], in whatever frame those are given in.
     * That's either the world's, or a planet's own turning one for a map that gets drawn now and
     * read for a while after as the planet turns.
     */
    fun aim(toLight: Vec3, centre: Vec3, radius: Double, depth: Double, texels: Int) {
        along.setTo(toLight).mulInPlace(-1.0).normalizeInPlace() // the way the light travels
        // Any steady axis across it: the frame's +Y, unless the light is along it.
        val reference = if (kotlin.math.abs(along.y) < 0.95) Vec3.unitY() else Vec3.unitX()
        right.setTo(reference).crossInPlace(along).normalizeInPlace()
        up.setTo(along).crossInPlace(right).normalizeInPlace()
        texelSize = 2.0 * radius / texels
        // Snapped in absolute terms, so the grid is the frame's, not the camera's.
        sx = kotlin.math.floor((centre dot right) / texelSize) * texelSize
        sy = kotlin.math.floor((centre dot up) / texelSize) * texelSize
        sz = centre dot along
        this.radius = radius
        this.depth = depth
    }

    private val wr = Vec3(); private val wu = Vec3(); private val wa = Vec3()

    /**
     * Builds the matrices for a camera at [camera], given in the frame it was aimed in. [turn]
     * takes that frame to the world's (the planet's rotation now), or is null if it's already the
     * world's.
     */
    fun place(camera: Vec3, turn: com.rm.apogee.core.math.Quat?) {
        // Rows: x = (p + camera)·right - sx over radius, and so on. The camera part and the snapped
        // centre cancel to a few metres in double here, before anything is narrowed.
        val tx = ((camera dot right) - sx) / radius
        val ty = ((camera dot up) - sy) / radius
        val tz = ((camera dot along) - sz) / depth
        if (turn == null) {
            wr.setTo(right); wu.setTo(up); wa.setTo(along)
        } else {
            turn.rotate(right, wr); turn.rotate(up, wu); turn.rotate(along, wa)
        }
        fill(viewProjection, wr, wu, wa, 1.0 / radius, 1.0 / depth, tx, ty, tz, 1.0, 0.0)
        fill(texture, wr, wu, wa, 0.5 / radius, 0.5 / depth, 0.5 * tx, 0.5 * ty, 0.5 * tz, 1.0, 0.5)
    }

    private fun fill(
        m: FloatArray, r: Vec3, u: Vec3, f: Vec3, xy: Double, z: Double,
        tx: Double, ty: Double, tz: Double, w: Double, bias: Double,
    ) {
        m.fill(0f)
        m[0] = (r.x * xy).toFloat(); m[4] = (r.y * xy).toFloat(); m[8] = (r.z * xy).toFloat(); m[12] = (tx + bias).toFloat()
        m[1] = (u.x * xy).toFloat(); m[5] = (u.y * xy).toFloat(); m[9] = (u.z * xy).toFloat(); m[13] = (ty + bias).toFloat()
        m[2] = (f.x * z).toFloat(); m[6] = (f.y * z).toFloat(); m[10] = (f.z * z).toFloat(); m[14] = (tz + bias).toFloat()
        m[15] = w.toFloat()
    }

    /** Where camera-relative [p] falls in the map's texture: x, y and depth, each [0,1] inside. For tests. */
    fun project(p: Vec3, out: DoubleArray = DoubleArray(3)): DoubleArray {
        for (row in 0..2) {
            out[row] = texture[row] * p.x + texture[4 + row] * p.y + texture[8 + row] * p.z + texture[12 + row]
        }
        return out
    }
}
