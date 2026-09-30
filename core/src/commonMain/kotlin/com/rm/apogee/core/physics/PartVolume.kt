package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Whether a point is inside one of a craft's parts, how deep, and which way is out. Craft against
 * craft uses it for every point, and a wheel on a deck uses it to find the deck.
 */
class PartVolume {
    /** The way out, in world axes, after [inside] says yes. */
    val normal = Vec3()

    /** How deep, in metres, after [inside] says yes. */
    var penetration: Double = 0.0
        private set

    private val localPoint = Vec3()
    private val partLocal = Vec3()
    private val localNormal = Vec3()
    private val localFrom = Vec3()
    private val fromKeep = Vec3()
    private val localUp = Vec3()
    private var upSet = false
    private var fromSet = false

    /**
     * Whether [worldPoint] is inside part [partIndex] of [vessel].
     *
     * With [from], a box is only ever left through a face on [from]'s side of it. That's for a
     * wheel on a deck, with [from] the craft it belongs to. The shallowest way out of a deck sixty
     * centimetres thick is down through its underside once a wheel has sunk more than half way into
     * it, and a hard landing would have pushed the plane through the deck instead of back up.
     */
    fun inside(worldPoint: Vec3, vessel: Vessel, partIndex: Int, from: Vec3? = null, up: Vec3? = null): Boolean {
        val def = vessel.defs[partIndex]
        if (!def.solid) return false
        vessel.worldToPartLocal(partIndex, worldPoint, localPoint)
        fromSet = from != null
        if (from != null) vessel.worldToPartLocal(partIndex, from, localFrom)
        upSet = up != null
        if (up != null) {
            vessel.body.orientation.inverseRotate(up, localUp)
            vessel.design.parts[partIndex].rotation.inverseRotate(localUp, localUp)
        }
        if (def.hull.isEmpty()) {
            if (!insidePrimitive(def.mesh)) return false
        } else {
            // Inside any one of its volumes, each tested in its own place.
            partLocal.setTo(localPoint)
            fromKeep.setTo(localFrom)
            var inside = false
            for (volume in def.hull) {
                localPoint.setTo(partLocal).subInPlace(volume.offset)
                if (fromSet) localFrom.setTo(fromKeep).subInPlace(volume.offset)
                if (insidePrimitive(volume.mesh)) { inside = true; break }
            }
            if (!inside) return false
        }

        // Part local to world, for the normal.
        vessel.design.parts[partIndex].rotation.rotate(localNormal, normal)
        vessel.body.orientation.rotate(normal, normal)
        return true
    }

    /**
     * Point in primitive, with the shallowest way out as the normal.
     *
     * It's the shallowest way out rather than the nearest surface because that's the direction the
     * contact should push. A corner barely inside a tank's end cap should be pushed out through the
     * cap, not sideways through two metres of tank.
     */
    private fun insidePrimitive(mesh: MeshSpec): Boolean {
        val p = localPoint
        when (mesh) {
            is MeshSpec.Sphere -> {
                val distance = p.length
                if (distance >= mesh.radius) return false
                penetration = mesh.radius - distance
                if (distance > 1e-9) {
                    localNormal.setTo(p).mulInPlace(1.0 / distance)
                } else {
                    localNormal.setTo(0.0, 1.0, 0.0)
                }
                return true
            }

            is MeshSpec.Box -> {
                val hx = mesh.width * 0.5
                val hy = mesh.height * 0.5
                val hz = mesh.depth * 0.5
                if (abs(p.x) >= hx || abs(p.y) >= hy || abs(p.z) >= hz) return false
                // Each axis's way out: through the near face, or with a side to leave by, the face
                // on that side, however deep that is.
                val sx = if (fromSet) (if (localFrom.x < 0) -1.0 else 1.0) else if (p.x < 0) -1.0 else 1.0
                val sy = if (fromSet) (if (localFrom.y < 0) -1.0 else 1.0) else if (p.y < 0) -1.0 else 1.0
                val sz = if (fromSet) (if (localFrom.z < 0) -1.0 else 1.0) else if (p.z < 0) -1.0 else 1.0
                val dx = hx - p.x * sx
                val dy = hy - p.y * sy
                val dz = hz - p.z * sz
                penetration = minOf(dx, dy, dz)
                when (penetration) {
                    dx -> localNormal.setTo(sx, 0.0, 0.0)
                    dy -> localNormal.setTo(0.0, sy, 0.0)
                    else -> localNormal.setTo(0.0, 0.0, sz)
                }
                // With an up, the face on top is the way out whenever it's no more than a step
                // away. A deck laid from several parts has their ends butted together inside it,
                // and a wheel rolling from one onto the next was nearest the next one's end, which
                // pushed it back like a wall. A Sparrow taking off along a deck broke up at the
                // first join past fifty metres a second.
                if (upSet) {
                    val ux = localUp.x * sx; val uy = localUp.y * sy; val uz = localUp.z * sz
                    val best = maxOf(ux, uy, uz)
                    if (best > FACING_UP) {
                        val depth = when (best) { ux -> dx; uy -> dy; else -> dz }
                        if (depth <= STEP) {
                            penetration = depth
                            when (best) {
                                ux -> localNormal.setTo(sx, 0.0, 0.0)
                                uy -> localNormal.setTo(0.0, sy, 0.0)
                                else -> localNormal.setTo(0.0, 0.0, sz)
                            }
                        }
                    }
                }
                return true
            }

            is MeshSpec.Cylinder -> return insideTube(mesh.radius, mesh.radius, mesh.height)

            // A cone is treated as a tube whose radius changes with height. The parts that use it
            // are engine bells and nose cones, where the taper is gentle and the difference is
            // millimetres.
            is MeshSpec.Cone ->
                return insideTube(mesh.bottomRadius, mesh.topRadius, mesh.height)
        }
    }

    private fun insideTube(bottomRadius: Double, topRadius: Double, height: Double): Boolean {
        val p = localPoint
        val half = height * 0.5
        val alongDepth = half - abs(p.y)
        if (alongDepth <= 0.0) return false

        val t = ((p.y + half) / height).coerceIn(0.0, 1.0)
        val radius = bottomRadius + (topRadius - bottomRadius) * t
        val radial = sqrt(p.x * p.x + p.z * p.z)
        val radialDepth = radius - radial
        if (radialDepth <= 0.0) return false

        if (radialDepth < alongDepth) {
            penetration = radialDepth
            if (radial > 1e-9) {
                localNormal.setTo(p.x / radial, 0.0, p.z / radial)
            } else {
                localNormal.setTo(1.0, 0.0, 0.0)
            }
        } else {
            penetration = alongDepth
            localNormal.setTo(0.0, if (p.y < 0) -1.0 else 1.0, 0.0)
        }
        return true
    }

    private companion object {
        /** How near straight up a face has to be to stand on, as the cosine of its tilt. */
        const val FACING_UP = 0.7

        /** The highest step a wheel or a foot goes up instead of meeting it as a wall, in metres. */
        const val STEP = 0.35
    }
}
