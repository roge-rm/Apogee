package com.rm.apogee.core.sea

import com.rm.apogee.core.math.Vec3

/**
 * A field over a planet's surface, worked out at the corners of a 3D grid
 * and blended between them - so the physics and the renderer, blending the
 * same corners, get the same numbers everywhere.
 *
 * A 3D grid rather than one on the cube-sphere's faces: it has no seams. A
 * point on the surface lies in one cube of the grid, and each of its eight
 * corners stands for the surface straight out from it, so neighbouring
 * points always blend neighbouring corners.
 *
 * Over time too, when [epoch] is set: a corner's value is fixed for each
 * epoch and blended between the two either side. Each corner is worked out
 * once and kept, up to [capacity] of them.
 */
internal class Lattice(
    private val radius: Double,
    private val spacing: Double,
    /** Seconds each value stands for, or 0 for a field that does not change. */
    private val epoch: Double,
    /** Numbers per corner. */
    val size: Int,
    private val capacity: Int,
    /**
     * Which field this is, among every lattice in the process: lattices with
     * the same name share their corners - worked out once, by whichever
     * sea asks first, since a corner is the same wherever it is computed.
     */
    name: String,
    /** A corner's values at unit [direction] and [time], into its array. */
    private val compute: (direction: Vec3, time: Double, out: DoubleArray) -> Unit,
) {
    private val cache: java.util.concurrent.ConcurrentHashMap<Long, DoubleArray> =
        shared.getOrPut(name) { java.util.concurrent.ConcurrentHashMap() }
    private val direction = Vec3()

    /** The field at unit [direction] and [time], into [out] (at least [size] long). */
    fun sample(direction: Vec3, time: Double, out: DoubleArray) {
        for (n in 0 until size) out[n] = 0.0
        val x = direction.x * radius / spacing
        val y = direction.y * radius / spacing
        val z = direction.z * radius / spacing
        val ix = Math.floor(x); val iy = Math.floor(y); val iz = Math.floor(z)
        val fx = x - ix; val fy = y - iy; val fz = z - iz
        val e: Long
        val fe: Double
        if (epoch > 0.0) {
            val u = time / epoch
            val ef = Math.floor(u)
            e = ef.toLong(); fe = u - ef
        } else {
            e = 0L; fe = 0.0
        }
        val epochs = if (epoch > 0.0) 2 else 1
        // The same cube as last time - neighbouring samples nearly always
        // are - uses the same corners, found once.
        val i0 = ix.toInt(); val j0 = iy.toInt(); val k0 = iz.toInt()
        if (i0 != lastI || j0 != lastJ || k0 != lastK || e != lastE) {
            for (c in 0 until 8) {
                val dx = c and 1; val dy = (c shr 1) and 1; val dz = (c shr 2) and 1
                for (k in 0 until epochs) corners[c * 2 + k] = node(i0 + dx, j0 + dy, k0 + dz, e + k)
            }
            lastI = i0; lastJ = j0; lastK = k0; lastE = e
        }
        for (c in 0 until 8) {
            val dx = c and 1; val dy = (c shr 1) and 1; val dz = (c shr 2) and 1
            val w = (if (dx == 1) fx else 1.0 - fx) * (if (dy == 1) fy else 1.0 - fy) * (if (dz == 1) fz else 1.0 - fz)
            if (w <= 0.0) continue
            for (k in 0 until epochs) {
                val wt = if (epochs == 1) w else w * (if (k == 1) fe else 1.0 - fe)
                if (wt <= 0.0) continue
                val node = corners[c * 2 + k]!!
                for (n in 0 until size) out[n] += wt * node[n]
            }
        }
    }

    private val corners = arrayOfNulls<DoubleArray>(16)
    private var lastI = Int.MIN_VALUE
    private var lastJ = 0
    private var lastK = 0
    private var lastE = Long.MIN_VALUE

    private fun node(i: Int, j: Int, k: Int, e: Long): DoubleArray {
        val key = ((i + 32_768).toLong() shl 48) or ((j + 32_768).toLong() shl 32) or
            ((k + 32_768).toLong() shl 16) or (e and 0xFFFF)
        val kept = cache[key]
        // The low bits of the epoch are in the key; the whole of it is kept
        // with the values, so a corner from long ago is never taken for now.
        if (kept != null && kept[size] == e.toDouble()) return kept
        direction.setTo(i * spacing, j * spacing, k * spacing).normalizeInPlace()
        val values = DoubleArray(size + 1)
        compute(direction, e * epoch, values)
        values[size] = e.toDouble()
        if (cache.size > capacity) trim(e)
        cache[key] = values
        return values
    }

    /** Makes room: epochs well past first, then anything if still too full. */
    private fun trim(now: Long) {
        if (epoch > 0.0) cache.values.removeIf { it[size] < now - 2 }
        if (cache.size > capacity) cache.clear()
    }

    /** Warms the corners round unit [direction] for [time], without blending anything. */
    fun prefetch(direction: Vec3, time: Double) {
        val x = Math.floor(direction.x * radius / spacing).toInt()
        val y = Math.floor(direction.y * radius / spacing).toInt()
        val z = Math.floor(direction.z * radius / spacing).toInt()
        val e = if (epoch > 0.0) Math.floor(time / epoch).toLong() else 0L
        for (c in 0 until 8) node(x + (c and 1), y + ((c shr 1) and 1), z + ((c shr 2) and 1), e)
    }

    companion object {
        private val shared = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<Long, DoubleArray>>()
    }
}
