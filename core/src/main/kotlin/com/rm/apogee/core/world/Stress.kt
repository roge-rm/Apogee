package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.Parachute
import kotlin.math.sqrt

/**
 * What each joint of a craft is carrying, and what gives.
 *
 * A craft is pushed at its parts - thrust at the engines, drag and lift
 * where the air meets it - but moves as one. Every part has to be dragged
 * along at the craft's acceleration (and swung round with its spin), and
 * whatever force that takes goes through the joints between. So the load on
 * the joint above a part is everything pushing on the parts hanging from it,
 * less what those parts' own mass takes to keep up: an engine at full thrust
 * shoves the whole stack, and the joint above it carries all of that but its
 * own share; a fin at an angle of attack bends the tank it is bolted to.
 *
 * Gravity pulls every part alike and so loads nothing; nor do the ground and
 * other craft, which are [World.impact]'s business. What a joint carries is
 * weighed against its strength - force, plus the bending moment over its
 * width - and past [FATIGUE_START] of it the joint fatigues, faster and
 * faster, until it lets go; past [SNAP] it lets go at once. A wing or fin is
 * judged by its own [AeroSurface.loadLimit] in the drag pass - its share of
 * that is reported here as its joint's load, but not fatigued - and a chute
 * tears by its own rule.
 *
 * Reused scratch, like [Forces]: this runs for every craft every tick.
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

        // Each part's own share: what pushes it, less what it takes to keep up.
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

        // Leaves first: each part hands what it carries to its parent.
        val order = orderOf(vessel.design)
        for (k in 0 until n) {
            val i = order[k]
            val q = parts[i].parentIndex
            if (q < 0) { vessel.jointLoad[i] = 0f; continue }
            // The joint sits between the two: the moment about it.
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
                    // A joint worn through lets go; it does not vanish.
                    if (wear >= vessel.health[i]) snap(i) else vessel.damage(i, wear)
                }
            } else if (child.module<AeroSurface>() != null) {
                // A wing or fin: judged by its own limit in the drag pass,
                // but how near it is still shows and still warns. Read once:
                // a tick with no air leaves it at nothing.
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
        // Breadth first from the roots, filled from the back: parents land
        // after all their descendants.
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
        /** Load over strength where a joint begins to fatigue. */
        const val FATIGUE_START = 0.7

        /** Where it lets go at once. */
        const val SNAP = 1.5

        /**
         * Health a second worn from a joint at its limit: five seconds there
         * breaks it; at 0.9 of it about twenty; at 1.3 under a second.
         */
        const val FATIGUE_RATE = 0.2
    }
}
