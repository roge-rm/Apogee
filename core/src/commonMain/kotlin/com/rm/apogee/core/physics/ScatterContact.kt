package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.ScatterKind
import kotlin.math.sqrt

/**
 * Craft against the things on the ground: boulders, trunks and shrubs.
 *
 * Boulders are spheres sitting partly buried, and everything that grows is an upright capsule along
 * its trunk. A craft's hull contact points (the same ones it rests on the ground with) get pushed
 * out of them along the surface they touch, with friction, and an arrival faster than a part can
 * take breaks the part, the same as the ground does.
 *
 * Things that grow can give way. A blow harder than a tree can take knocks it down. The tree is
 * gone, the craft keeps the part of the impulse it took to break it, and [onFelled] is told so the
 * world can remember. A felled tree stays down, for everyone, across restarts.
 */
class ScatterContact {

    private val bodyRotation = Quat.identity()
    private val bodyFixed = Vec3()
    private val point = Vec3()
    private val pointFixed = Vec3()
    private val centre = Vec3()
    private val normalFixed = Vec3()
    private val normal = Vec3()
    private val offset = Vec3()
    private val velocity = Vec3()
    private val surface = Vec3()
    private val impulse = Vec3()
    private val tangent = Vec3()
    private val scratch = Vec3()
    private val inverseInertia = Mat3()

    /**
     * @param removed ids of scatter that's no longer there.
     * @param onFelled called with the id of anything this pass knocks down.
     */
    fun resolve(
        vessel: Vessel,
        attractor: CelestialBody,
        time: Double,
        report: ContactReport,
        removed: Set<Long>,
        onFelled: (Long) -> Unit,
    ) {
        val field = attractor.terrain?.scatter ?: return
        val body = vessel.body
        if (body.inverseMass <= 0.0) return

        // Only near the ground, since nothing grows taller than a tall tree.
        attractor.rotationAt(time, bodyRotation)
        attractor.toBodyFixed(body.position, bodyRotation, bodyFixed)
        val ground = attractor.solidRadiusInBodyFrame(bodyFixed)
        if (body.position.length - vessel.contactRadius > ground + REACH_ABOVE_GROUND) return

        val reach = vessel.contactRadius + MAX_OBJECT_REACH
        field.forEachBlockNear(bodyFixed, reach, scratch) { block ->
            for (k in 0 until block.count) {
                val id = block.ids[k]
                if (id in removed) continue
                val dx = block.x[k] - bodyFixed.x
                val dy = block.y[k] - bodyFixed.y
                val dz = block.z[k] - bodyFixed.z
                if (dx * dx + dy * dy + dz * dz > reach * reach) continue
                val kind = ScatterKind.of(block.kinds[k].toInt())
                val felled = collide(vessel, attractor, report, block.x[k], block.y[k], block.z[k], kind, block.sizes[k].toDouble())
                if (felled) onFelled(id)
            }
        }
    }

    /** Resolves every contact point against one object. @return true if it got knocked down. */
    private fun collide(
        vessel: Vessel,
        attractor: CelestialBody,
        report: ContactReport,
        bx: Double, by: Double, bz: Double,
        kind: ScatterKind,
        size: Double,
    ): Boolean {
        val body = vessel.body
        val radius = kind.radius * size
        val height = kind.height * size
        val baseLength = sqrt(bx * bx + by * by + bz * bz)
        val ux = bx / baseLength; val uy = by / baseLength; val uz = bz / baseLength
        var felled = false

        for (partIndex in vessel.defs.indices) {
            val def = vessel.defs[partIndex]
            for (pointIndex in def.contactPoints.indices) {
                vessel.contactPointWorld(partIndex, pointIndex, point)
                attractor.toBodyFixed(point, bodyRotation, pointFixed)

                // The nearest point of the object's collider to this contact.
                if (kind.isBoulder) {
                    // It sits a little buried, with its centre just above the ground.
                    centre.setTo(bx + ux * radius * 0.55, by + uy * radius * 0.55, bz + uz * radius * 0.55)
                } else {
                    // A trunk: the nearest point on its axis, from base to crown.
                    val along = ((pointFixed.x - bx) * ux + (pointFixed.y - by) * uy + (pointFixed.z - bz) * uz)
                        .coerceIn(0.0, height)
                    centre.setTo(bx + ux * along, by + uy * along, bz + uz * along)
                }
                normalFixed.setTo(pointFixed).subInPlace(centre)
                val distance = normalFixed.length
                val penetration = radius - distance
                if (penetration <= 0.0 || distance < 1e-6) continue
                normalFixed.mulInPlace(1.0 / distance)
                if (!kind.isBoulder) {
                    // Plants only push sideways. The rounded top of a shrub's collider faces up,
                    // and a wheel meeting it got launched like it was going off a ramp. No shrub
                    // ever threw a vehicle into the air. It gets flattened under one.
                    val upward = normalFixed.x * ux + normalFixed.y * uy + normalFixed.z * uz
                    normalFixed.x -= ux * upward; normalFixed.y -= uy * upward; normalFixed.z -= uz * upward
                    val sideways = normalFixed.length
                    if (sideways < 1e-3) continue
                    normalFixed.mulInPlace(1.0 / sideways)
                }
                bodyRotation.rotate(normalFixed, normal)

                vessel.contactOffsetWorld(partIndex, pointIndex, offset)
                body.velocityAtOffset(offset, velocity)
                attractor.surfaceVelocityAt(point, surface)
                velocity.subInPlace(surface)
                val approach = velocity dot normal
                report.contactCount++

                // Push the point out, a fraction each tick, the same as the ground does.
                body.position.addScaledInPlace(normal, penetration * POSITION_CORRECTION)
                if (approach >= 0.0) continue

                var j = solveImpulse(body, normal, approach)
                // What the craft actually feels. Against rock, it's the whole arrival. Against
                // something that gives way, it's only the impulse it took to break it. Judging a
                // rover by its full closing speed when a tree snapped in front of it wrote off
                // craft that should have come away with a dent.
                var impact = -approach
                if (kind.breakable && j > kind.breakImpulse * size) {
                    val full = j
                    j = kind.breakImpulse * size
                    impact *= j / full
                    felled = true
                }
                if (impact > report.worstImpactSpeed) {
                    report.worstImpactSpeed = impact
                    report.worstPartIndex = partIndex
                }
                if (impact > def.crashTolerance) report.recordImpact(partIndex, impact, normal)

                impulse.setTo(normal).mulInPlace(j)
                body.applyImpulseAtOffset(impulse, offset)

                // Friction along the surface.
                tangent.setTo(velocity).addScaledInPlace(normal, -approach)
                val slide = tangent.length
                if (slide > 1e-6) {
                    tangent.mulInPlace(-1.0 / slide)
                    val stop = solveImpulse(body, tangent, -slide)
                    impulse.setTo(tangent).mulInPlace(minOf(stop, FRICTION * j))
                    body.applyImpulseAtOffset(impulse, offset)
                }
                if (felled) return true
            }
        }
        return felled
    }

    /**
     * The impulse along unit [direction] that cancels [speed] along it at [offset], for a rigid
     * body.
     */
    private fun solveImpulse(body: RigidBody, direction: Vec3, speed: Double): Double {
        inverseInertia.setRotated(body.inverseInertiaLocal, body.orientation)
        scratch.setTo(offset).crossInPlace(direction)
        inverseInertia.transform(scratch, scratch)
        scratch.crossInPlace(offset)
        val effective = body.inverseMass + (scratch dot direction)
        return -(1.0 + RESTITUTION) * speed / effective
    }

    private companion object {
        const val POSITION_CORRECTION = 0.35
        const val RESTITUTION = 0.1
        const val FRICTION = 0.5
        /** How many metres above the ground a craft can still reach anything: the tallest tree. */
        const val REACH_ABOVE_GROUND = 14.0
        /** How far past a craft's own reach an object can still touch it. */
        const val MAX_OBJECT_REACH = 6.0
    }
}
