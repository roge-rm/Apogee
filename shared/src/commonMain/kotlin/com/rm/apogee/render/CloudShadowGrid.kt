package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.weather.CloudShape
import com.rm.apogee.core.weather.CloudType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The clouds' shadows on the ground around the camera. Each cloud is cast along the light onto a
 * flat sheet at the ground's height, as a grid of how shaded it is (red) and how high the lowest
 * cloud over it is (green), so a craft flying above a cloud doesn't get darkened by it.
 *
 * It's in the planet's own turning frame, since it's made once a second and read every frame in
 * between, and [matrix] turns it to the world as it is. It's built off the GL thread, and the
 * renderer uploads it when [revision] changes.
 */
class CloudShadowGrid(
    val size: Int,
    /** How far it reaches from its middle each way, in metres. */
    val extent: Double,
    /** Its middle, on the ground under the camera, body-fixed. */
    val origin: Vec3,
    val east: Vec3,
    val north: Vec3,
    val up: Vec3,
    /** Unit, body-fixed, towards the light the clouds were cast by. */
    val light: Vec3,
    /** How dark a full cloud's shadow is, 0..1. Less by moonlight. */
    val strength: Float,
    val revision: Int,
) {
    /**
     * Red is how shaded, 0..255. Green is the lowest cloud base over it, in [HEIGHT_SCALE] units.
     */
    val data = ByteArray(size * size * 2)

    /**
     * Camera-relative world to the grid: x and y [0,1] across it, and z the height over its sheet
     * in [HEIGHT_SCALE] units, for a point cast back along the light onto the sheet the way the
     * cloud's shadow was. [turn] is the planet's rotation now, and [camera] is absolute.
     */
    fun matrix(turn: Quat, camera: Vec3, out: FloatArray) {
        val lUp = light dot up
        val cameraFixed = turn.inverseRotate(camera, Vec3()).subInPlace(origin)
        fun cast(axis: Vec3): Vec3 = Vec3().setTo(axis).addScaledInPlace(up, -(light dot axis) / lUp)
        val ku = cast(east).mulInPlace(1.0 / (2 * extent))
        val kv = cast(north).mulInPlace(1.0 / (2 * extent))
        val kz = Vec3().setTo(up).mulInPlace(1.0 / HEIGHT_SCALE)
        val wu = turn.rotate(ku, Vec3()); val wv = turn.rotate(kv, Vec3()); val wz = turn.rotate(kz, Vec3())
        out.fill(0f)
        out[0] = wu.x.toFloat(); out[4] = wu.y.toFloat(); out[8] = wu.z.toFloat(); out[12] = ((cameraFixed dot ku) + 0.5).toFloat()
        out[1] = wv.x.toFloat(); out[5] = wv.y.toFloat(); out[9] = wv.z.toFloat(); out[13] = ((cameraFixed dot kv) + 0.5).toFloat()
        out[2] = wz.x.toFloat(); out[6] = wz.y.toFloat(); out[10] = wz.z.toFloat(); out[14] = (cameraFixed dot kz).toFloat()
        out[15] = 1f
    }

    /** How shaded grid cell ([i], [j]) is, 0..1. For tests. */
    fun shade(i: Int, j: Int): Double = (data[(j * size + i) * 2].toInt() and 0xFF) / 255.0

    /** The lowest cloud base over cell ([i], [j]), in metres over the sheet. For tests. */
    fun base(i: Int, j: Int): Double = (data[(j * size + i) * 2 + 1].toInt() and 0xFF) / 255.0 * HEIGHT_SCALE

    companion object {
        /** Heights are kept as a share of this, in metres. */
        const val HEIGHT_SCALE = 12_000.0

        /**
         * Casts [shapes] (body-fixed) onto a grid of [size] cells reaching [extent] each way from
         * the ground under unit [upAt], [ground] m above the datum of a body of [radius], along
         * [light] (unit, body-fixed, towards the light). Null if the light is too low to cast
         * anything sensible.
         */
        fun build(
            shapes: List<CloudShape>, upAt: Vec3, radius: Double, ground: Double,
            light: Vec3, strength: Float, size: Int, extent: Double, revision: Int,
        ): CloudShadowGrid? {
            val up = upAt.copy().normalizeInPlace()
            if ((light dot up) < 0.08) return null
            val reference = if (kotlin.math.abs(up.y) < 0.95) Vec3.unitY() else Vec3.unitX()
            val east = reference.copy().crossInPlace(up).normalizeInPlace()
            val north = up.copy().crossInPlace(east).normalizeInPlace()
            val origin = up.copy().mulInPlace(radius + ground)
            val grid = CloudShadowGrid(size, extent, origin, east, north, up, light.copy(), strength, revision)
            val cover = FloatArray(size * size) // how much light gets through, multiplied
            cover.fill(1f)
            val base = FloatArray(size * size)
            base.fill(Float.MAX_VALUE)
            val cell = 2 * extent / size
            val lUp = light dot up
            val d = Vec3()
            for (shape in shapes) {
                val opacity = OPACITY[shape.type.ordinal] * shape.amount.coerceIn(0.3, 1.0)
                for (lobe in shape.lobes) {
                    d.setTo(lobe.centre).subInPlace(origin)
                    val height = d dot up
                    if (height <= 0.0) continue
                    // Cast along the light onto the sheet.
                    d.addScaledInPlace(light, -height / lUp)
                    val gx = d dot east
                    val gy = d dot north
                    val r = lobe.horizontal
                    if (kotlin.math.abs(gx) > extent + r || kotlin.math.abs(gy) > extent + r) continue
                    val bottom = (height - 0.4 * lobe.vertical).toFloat()
                    val i0 = max(0, ((gx - r + extent) / cell).toInt()); val i1 = min(size - 1, ((gx + r + extent) / cell).toInt())
                    val j0 = max(0, ((gy - r + extent) / cell).toInt()); val j1 = min(size - 1, ((gy + r + extent) / cell).toInt())
                    for (j in j0..j1) for (i in i0..i1) {
                        val x = -extent + (i + 0.5) * cell - gx
                        val y = -extent + (j + 0.5) * cell - gy
                        val q = sqrt(x * x + y * y) / r
                        if (q >= 1.0) continue
                        val fall = if (q < 0.6) 1.0 else (1.0 - q) / 0.4
                        val a = (opacity * fall).toFloat()
                        val k = j * size + i
                        cover[k] *= 1f - a
                        if (a > 0.15f && bottom < base[k]) base[k] = bottom
                    }
                }
            }
            // A light blur, because a cloud's shadow has a soft edge, and the cells are coarse.
            val soft = FloatArray(size * size)
            for (j in 0 until size) for (i in 0 until size) {
                var sum = 0f; var n = 0
                for (dj in -1..1) for (di in -1..1) {
                    val ii = i + di; val jj = j + dj
                    if (ii < 0 || jj < 0 || ii >= size || jj >= size) continue
                    sum += cover[jj * size + ii]; n++
                }
                soft[j * size + i] = 1f - sum / n
            }
            for (k in 0 until size * size) {
                grid.data[k * 2] = (soft[k].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                val b = if (base[k] == Float.MAX_VALUE) HEIGHT_SCALE.toFloat() else base[k].coerceIn(0f, HEIGHT_SCALE.toFloat())
                grid.data[k * 2 + 1] = (b / HEIGHT_SCALE.toFloat() * 255f).toInt().toByte()
            }
            return grid
        }

        /** How much light a full lobe of each kind stops. */
        private val OPACITY = DoubleArray(CloudType.entries.size).also {
            it[CloudType.CUMULUS.ordinal] = 0.6
            it[CloudType.CUMULONIMBUS.ordinal] = 0.85
            it[CloudType.STRATUS.ordinal] = 0.7
            it[CloudType.ALTOSTRATUS.ordinal] = 0.55
            it[CloudType.CIRRUS.ordinal] = 0.15
            it[CloudType.DUST.ordinal] = 0.8
            it[CloudType.DECK.ordinal] = 0.5
        }
    }
}
