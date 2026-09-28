package com.rm.apogee.render

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.world.VesselCondition

/**
 * What a joint working near its limit looks like. The part beyond it, and everything hanging from
 * that, shudders about the seam, a degree or two at most, so a long stack visibly flexes at its
 * tip. Past [SPARKS_FROM], sparks and flecks of metal come off the seam itself (drawn by the
 * effects, from [seam] and [sparkRate]).
 *
 * Only the picture moves. The physics shape stays rigid. Loads come from the condition block, so
 * everyone watching sees the same craft straining.
 */
class StrainLook {

    /** Each part's flex as an affine map in design space: p -> turn * p + shift. */
    var turn = Array(0) { Quat.identity() }; private set
    var shift = Array(0) { Vec3() }; private set

    /** Where each part's joint to its parent is, in design space. Meaningless for a root. */
    var seams = Array(0) { Vec3() }; private set

    private var done = BooleanArray(0)
    private val axis = Vec3()
    private val across = Vec3()
    private val other = Vec3()
    private val local = Quat()

    /**
     * Works out every part's flex for [loads] (each part's joint, as a share of its strength) at
     * [time], in seconds. False, with nothing to apply, if no joint is working hard enough to show.
     */
    fun compute(design: CraftDesign, defs: List<PartDef?>, loads: FloatArray, time: Double, seed: Int): Boolean {
        val n = design.parts.size
        if (loads.size != n || loads.none { it > VesselCondition.LOAD_VISIBLE }) return false
        if (turn.size != n) {
            turn = Array(n) { Quat.identity() }
            shift = Array(n) { Vec3() }
            seams = Array(n) { Vec3() }
            done = BooleanArray(n)
        }
        done.fill(false)
        for (i in 0 until n) settle(i, design, defs, loads, time, seed)
        return true
    }

    /** Where [p] (design space) is drawn for part [i]. */
    fun apply(i: Int, p: Vec3, out: Vec3): Vec3 = turn[i].rotate(p, out).addInPlace(shift[i])

    private fun settle(i: Int, design: CraftDesign, defs: List<PartDef?>, loads: FloatArray, time: Double, seed: Int) {
        if (done[i]) return
        done[i] = true
        val placed = design.parts[i]
        val q = placed.parentIndex
        if (q !in design.parts.indices || q == i) {
            turn[i].setTo(0.0, 0.0, 0.0, 1.0); shift[i].setZero()
            return
        }
        settle(q, design, defs, loads, time, seed)
        val child = placed.position; val parent = design.parts[q].position
        val seam = seams[i]
        seamOf(child, design.parts[q].position, design.parts[q].rotation, defs[q], seam)

        val angle = flexAngle(loads[i].toDouble())
        if (angle <= 0.0) {
            turn[i].setTo(turn[q]); shift[i].setTo(shift[q])
            return
        }
        // A shudder about the two axes across the joint. It wanders, it doesn't tick.
        axis.setTo(child).subInPlace(parent)
        if (axis.lengthSq < 1e-9) axis.setTo(0.0, 1.0, 0.0) else axis.normalizeInPlace()
        other.setTo(if (kotlin.math.abs(axis.x) < 0.9) 1.0 else 0.0, if (kotlin.math.abs(axis.x) < 0.9) 0.0 else 1.0, 0.0)
        across.setTo(axis).crossInPlace(other).normalizeInPlace()
        other.setTo(axis).crossInPlace(across).normalizeInPlace()
        val key = seed * 97 + i * 13
        val a = angle * Noise.simplex(key, time * SHUDDER_HZ, 0.0, 0.0)
        val b = angle * Noise.simplex(key + 7, time * SHUDDER_HZ * 1.3, 0.0, 0.0)
        across.mulInPlace(a).addScaledInPlace(other, b)
        val swing = across.length
        if (swing < 1e-9) local.setTo(0.0, 0.0, 0.0, 1.0) else Quat.fromAxisAngle(across.mulInPlace(1.0 / swing), swing, local)

        // This part's map: its parent's, after turning about the seam.
        //     T(p) = Tq(R (p - s) + s) = Rq R p + Rq (s - R s) + tq
        turn[i].setTo(turn[q] * local).normalizeInPlace()
        local.rotate(seam, other)
        other.mulInPlace(-1.0).addInPlace(seam)
        turn[q].rotate(other, shift[i]).addInPlace(shift[q])
    }

    companion object {
        /** The load where sparks start coming off the seam. */
        const val SPARKS_FROM = 0.75

        /** The most a joint flexes, in radians. A touch, not a hinge. */
        const val MOST_FLEX = 0.035

        /** How quickly the shudder wanders, roughly in Hz. */
        const val SHUDDER_HZ = 7.0

        fun flexAngle(load: Double): Double {
            val t = ((load - VesselCondition.LOAD_VISIBLE) / (1.2 - VesselCondition.LOAD_VISIBLE)).coerceIn(0.0, 1.0)
            return MOST_FLEX * t * t * (3 - 2 * t)
        }

        /**
         * Sparks a second off a seam at [load]. None below [SPARKS_FROM], and a stream at the
         * limit.
         */
        fun sparkRate(load: Double): Double =
            if (load < SPARKS_FROM) 0.0 else 6.0 + 34.0 * ((load - SPARKS_FROM) / (1.0 - SPARKS_FROM)).coerceIn(0.0, 2.0)

        /**
         * Where a part centred at [child] meets its parent (at [parent], turned by [turn]), into
         * [out]. Seen from the parent, a part beyond its end is stacked on it and meets it on that
         * end face. One beside it is mounted on its side and meets its skin level with itself.
         */
        fun seamOf(child: Vec3, parent: Vec3, turn: Quat, def: PartDef?, out: Vec3): Vec3 {
            val local = turn.inverseRotate(out.setTo(child).subInPlace(parent), Vec3())
            val half: Double
            val radius: Double
            when (val mesh = def?.mesh) {
                is MeshSpec.Cylinder -> { half = mesh.height * 0.5; radius = mesh.radius }
                is MeshSpec.Cone -> { half = mesh.height * 0.5; radius = maxOf(mesh.bottomRadius, mesh.topRadius) }
                is MeshSpec.Box -> { half = mesh.height * 0.5; radius = mesh.width * 0.5 }
                is MeshSpec.Sphere -> { half = mesh.radius; radius = mesh.radius }
                null -> { half = 0.5; radius = 0.5 }
            }
            val across = kotlin.math.sqrt(local.x * local.x + local.z * local.z)
            if (kotlin.math.abs(local.y) >= half * 0.9 || across < 1e-6) {
                // Stacked, so on the end face, as far out as the child sits.
                val k = if (across > radius) radius / across else 1.0
                local.setTo(local.x * k, if (local.y >= 0) half else -half, local.z * k)
            } else {
                // Side-mounted, so on the skin, level with it.
                local.setTo(local.x * radius / across, local.y, local.z * radius / across)
            }
            return turn.rotate(local, out).addInPlace(parent)
        }
    }
}
