package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.Parachute
import kotlin.math.sqrt

/**
 * What each joint of a craft is carrying, and what gives.
 *
 * The load on the joint above a part is everything pushing on the parts hanging from it, minus what
 * their own mass takes to keep up with the craft's acceleration and spin. Gravity loads nothing,
 * and ground and craft contact is [World.impact]'s job. Load is force plus bending moment over the
 * joint's width, against its strength. Past [FATIGUE_START] the joint wears, faster and faster,
 * and past [SNAP] it lets go at once. A wing or fin is judged by its own [AeroSurface.loadLimit] in
 * the drag pass and only reported here. A chute tears by its own rule.
 *
 * It reuses scratch space, like [Forces], since it runs for every craft every tick.
 */
class Stress {

    /** Parts whose joint to their parent let go this update, [snappedCount] of them. */
    var snapped = IntArray(16)
        private set
    var snappedCount = 0
        private set

    private var capacity = 0
    private var forces = DoubleArray(0)
    private var moments = DoubleArray(0)
    private var positions = DoubleArray(0)
    private var orderFor: CraftDesign? = null
    private var order = IntArray(0)

    fun update(vessel: Vessel, dt: Double) {
        snappedCount = 0
        val parts = vessel.design.parts
        val n = parts.size
        vessel.stress = 0.0
        vessel.worstJoint = -1
        if (n < 2) {
            if (n == 1) vessel.jointLoad[0] = 0f
            return
        }
        ensure(n)
        val body = vessel.body
        val recorded = vessel.partForce

        // The craft's acceleration from what was recorded, and its spin.
        var fx = 0.0; var fy = 0.0; var fz = 0.0
        for (i in 0 until n) {
            fx += recorded[i * 3]; fy += recorded[i * 3 + 1]; fz += recorded[i * 3 + 2]
        }
        val inverseMass = if (body.mass > 0.0) 1.0 / body.mass else 0.0
        val ax = fx * inverseMass; val ay = fy * inverseMass; val az = fz * inverseMass
        val w = body.angularVelocity

        // Each part's own share: what pushes it, minus what it takes to keep up.
        val offset = scratch
        for (i in 0 until n) {
            vessel.partOffsetWorld(i, offset)
            positions[i * 3] = offset.x; positions[i * 3 + 1] = offset.y; positions[i * 3 + 2] = offset.z
            // Centripetal: w x (w x r).
            val cx = w.y * offset.z - w.z * offset.y
            val cy = w.z * offset.x - w.x * offset.z
            val cz = w.x * offset.y - w.y * offset.x
            val px = w.y * cz - w.z * cy
            val py = w.z * cx - w.x * cz
            val pz = w.x * cy - w.y * cx
            val m = vessel.partMass(i)
            forces[i * 3] = recorded[i * 3] - m * (ax + px)
            forces[i * 3 + 1] = recorded[i * 3 + 1] - m * (ay + py)
            forces[i * 3 + 2] = recorded[i * 3 + 2] - m * (az + pz)
            moments[i * 3] = 0.0; moments[i * 3 + 1] = 0.0; moments[i * 3 + 2] = 0.0
        }

        // Leaves first, so each part hands what it carries on to its parent.
        val order = orderOf(vessel.design)
        for (k in 0 until n) {
            val i = order[k]
            val q = parts[i].parentIndex
            if (q < 0) { vessel.jointLoad[i] = 0f; continue }
            // The joint sits between the two, so this is the moment around it.
            val jx = 0.5 * (positions[i * 3] - positions[q * 3])
            val jy = 0.5 * (positions[i * 3 + 1] - positions[q * 3 + 1])
            val jz = 0.5 * (positions[i * 3 + 2] - positions[q * 3 + 2])
            val sfx = forces[i * 3]; val sfy = forces[i * 3 + 1]; val sfz = forces[i * 3 + 2]
            val mx = moments[i * 3] + (jy * sfz - jz * sfy)
            val my = moments[i * 3 + 1] + (jz * sfx - jx * sfz)
            val mz = moments[i * 3 + 2] + (jx * sfy - jy * sfx)

            val child = vessel.defs[i]
            val parent = vessel.defs[q]
            val judged = child.module<AeroSurface>() == null && child.module<Parachute>() == null
            if (judged) {
                val strength = minOf(child.jointStrength, parent.jointStrength)
                val width = 2.0 * minOf(child.jointRadius, parent.jointRadius)
                val load = sqrt(sfx * sfx + sfy * sfy + sfz * sfz) + sqrt(mx * mx + my * my + mz * mz) / width
                val ratio = load / strength
                vessel.jointLoad[i] = ratio.toFloat()
                if (ratio > vessel.stress) { vessel.stress = ratio; vessel.worstJoint = i }
                if (ratio >= SNAP) {
                    snap(i)
                } else if (ratio > FATIGUE_START) {
                    val over = (ratio - FATIGUE_START) / (1.0 - FATIGUE_START)
                    val wear = FATIGUE_RATE * over * over * over * dt
                    // A joint worn through lets go. It doesn't just vanish.
                    if (wear >= vessel.health[i]) snap(i) else vessel.damage(i, wear)
                }
            } else if (child.module<AeroSurface>() != null) {
                // Judged in the drag pass, but shown and warned on here. Cleared on read, so a
                // tick with no air leaves it at nothing.
                val ratio = vessel.surfaceLoad[i]
                vessel.surfaceLoad[i] = 0f
                vessel.jointLoad[i] = ratio
                if (ratio > vessel.stress) { vessel.stress = ratio.toDouble(); vessel.worstJoint = i }
            } else {
                vessel.jointLoad[i] = 0f
            }

            // On to the parent, moved to its centre.
            val dx = positions[i * 3] - positions[q * 3]
            val dy = positions[i * 3 + 1] - positions[q * 3 + 1]
            val dz = positions[i * 3 + 2] - positions[q * 3 + 2]
            forces[q * 3] += sfx; forces[q * 3 + 1] += sfy; forces[q * 3 + 2] += sfz
            moments[q * 3] += moments[i * 3] + (dy * sfz - dz * sfy)
            moments[q * 3 + 1] += moments[i * 3 + 1] + (dz * sfx - dx * sfz)
            moments[q * 3 + 2] += moments[i * 3 + 2] + (dx * sfy - dy * sfx)
        }
    }

    private fun snap(part: Int) {
        if (snappedCount == snapped.size) snapped = snapped.copyOf(snapped.size * 2)
        snapped[snappedCount++] = part
    }

    private val scratch = com.rm.apogee.core.math.Vec3()

    private fun ensure(n: Int) {
        if (n <= capacity) return
        capacity = n
        forces = DoubleArray(n * 3)
        moments = DoubleArray(n * 3)
        positions = DoubleArray(n * 3)
    }

    /** The parts in an order that puts every part before its parent. */
    private fun orderOf(design: CraftDesign): IntArray {
        if (orderFor === design) return order
        val parts = design.parts
        val n = parts.size
        val children = Array(n) { ArrayList<Int>(2) }
        val roots = ArrayList<Int>()
        for (i in 0 until n) {
            val p = parts[i].parentIndex
            if (p in 0 until n) children[p].add(i) else roots.add(i)
        }
        val out = IntArray(n)
        var k = n
        // Breadth first from the roots, filled from the back, so parents end up after their
        // descendants.
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            out[--k] = p
            queue.addAll(children[p])
        }
        order = out
        orderFor = design
        return out
    }

    companion object {
        /** Load over strength where a joint starts to fatigue. */
        const val FATIGUE_START = 0.7

        /** Where it lets go straight away. */
        const val SNAP = 1.5

        /** Health worn from a joint per second at its limit, so five seconds there breaks it. */
        const val FATIGUE_RATE = 0.2
    }
}
