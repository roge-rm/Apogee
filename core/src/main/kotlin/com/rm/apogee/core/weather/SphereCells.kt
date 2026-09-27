package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.CubeSphere
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The sphere cut into cells about [cellSize] across, for scattering weather features: one thermal,
 * one storm or one cloud per cell and time.
 *
 * It's on the cube-sphere, the same mapping the terrain uses, so cells are nearly square everywhere
 * and the poles aren't a pinch point.
 */
class SphereCells(bodyRadius: Double, cellSize: Double) {

    val perFace: Int = ceil(PI / 2.0 * bodyRadius / cellSize).toInt().coerceAtLeast(1)

    private val located = Vec3()
    private val probe = Vec3()

    /** The cell [direction] falls in. */
    fun key(direction: Vec3): Long {
        val face = CubeSphere.locate(direction, located)
        val i = floor((located.x + 1.0) * 0.5 * perFace).toInt().coerceIn(0, perFace - 1)
        val j = floor((located.y + 1.0) * 0.5 * perFace).toInt().coerceIn(0, perFace - 1)
        return (face.toLong() * perFace + i) * perFace + j
    }

    /** Integer coordinates for hashing, unique for each cell. */
    fun hashX(key: Long): Int = (key / perFace).toInt()
    fun hashY(key: Long): Int = (key % perFace).toInt()

    /** The unit direction at the middle of cell [key]. */
    fun centre(key: Long, out: Vec3): Vec3 {
        val j = (key % perFace).toInt()
        val rest = key / perFace
        val i = (rest % perFace).toInt()
        val face = (rest / perFace).toInt()
        val s = (i + 0.5) / perFace * 2.0 - 1.0
        val t = (j + 0.5) / perFace * 2.0 - 1.0
        return CubeSphere.direction(face, s, t, out)
    }

    /**
     * The cells around [direction]: its own and those up to [reach] cells away, into [out].
     *
     * It goes by index within the face. Stepping a cell's width across the tangent plane skipped
     * cells wherever the face grid runs at an angle to east and north, which away from the equator
     * is nearly everywhere. Only near a face edge, where the neighbours are on another face, does
     * it probe instead, at half-cell steps so none get missed. [east], [north] and [angularStep] (a
     * cell's width in radians) are for that.
     *
     * @return how many different keys were written.
     */
    fun around(direction: Vec3, east: Vec3, north: Vec3, angularStep: Double, out: LongArray, reach: Int = 1): Int {
        val face = CubeSphere.locate(direction, located)
        val i = floor((located.x + 1.0) * 0.5 * perFace).toInt().coerceIn(0, perFace - 1)
        val j = floor((located.y + 1.0) * 0.5 * perFace).toInt().coerceIn(0, perFace - 1)
        var count = 0
        for (di in -reach..reach) for (dj in -reach..reach) {
            val ii = i + di; val jj = j + dj
            if (ii < 0 || jj < 0 || ii >= perFace || jj >= perFace) continue
            count = addUnique(out, count, (face.toLong() * perFace + ii) * perFace + jj)
        }
        if (i < reach || j < reach || i >= perFace - reach || j >= perFace - reach) {
            val steps = 2 * reach + 1
            for (dy in -steps..steps) for (dx in -steps..steps) {
                probe.setTo(direction)
                    .addScaledInPlace(east, dx * 0.5 * angularStep)
                    .addScaledInPlace(north, dy * 0.5 * angularStep)
                    .normalizeInPlace()
                count = addUnique(out, count, key(probe))
            }
        }
        return count
    }

    private fun addUnique(out: LongArray, count: Int, key: Long): Int {
        for (n in 0 until count) if (out[n] == key) return count
        if (count >= out.size) return count
        out[count] = key
        return count + 1
    }
}
