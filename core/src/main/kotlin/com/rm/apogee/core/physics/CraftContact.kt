package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import kotlin.math.abs
import kotlin.math.sqrt

/** One craft striking another, for the caller to turn into consequences. */
class CraftImpactReport {
    val vessels = LongArray(MAX_IMPACTS)
    val parts = IntArray(MAX_IMPACTS)
    val speeds = DoubleArray(MAX_IMPACTS)
    var count: Int = 0
        private set

    fun reset() {
        count = 0
    }

    fun record(vesselId: Long, partIndex: Int, speed: Double) {
        for (i in 0 until count) {
            if (vessels[i] == vesselId && parts[i] == partIndex) {
                if (speed > speeds[i]) speeds[i] = speed
                return
            }
        }
        if (count >= MAX_IMPACTS) return
        vessels[count] = vesselId
        parts[count] = partIndex
        speeds[count] = speed
        count++
    }

    private companion object {
        const val MAX_IMPACTS = 32
    }
}

/**
 * Craft against craft.
 *
 * Until this existed two vessels in the same world passed straight through
 * each other, which is survivable while everything is a rocket flying its own
 * trajectory and not survivable at all once anything is meant to be *solid* -
 * a base that can be landed on, docked with, or flown into.
 *
 * The shape of it follows [GroundContact] deliberately. That resolver tests
 * each part's hull points against the terrain; this one tests each part's hull
 * points against the *other craft's part volumes*, and answers with the same
 * sequential impulses. Parts are already primitives - cylinder, cone, box,
 * sphere - so a point-in-volume test is a handful of arithmetic per point and
 * needs none of the machinery a general convex solver would.
 *
 * Both directions are tested, A's points against B's volumes and B's against
 * A's, because a point-based test is asymmetric: a small part can sit entirely
 * inside a large one with none of the large one's corners inside the small.
 */
class CraftContact {

    val report = CraftImpactReport()

    /**
     * Craft involved in any contact this tick, damaging or not.
     *
     * Separate from [report], which only lists what broke. Dormancy needs the
     * wider set: something resting against a sleeping base has to wake it
     * whether or not it did any harm, or the base behaves like scenery.
     */
    val touched = LongArray(MAX_TOUCHED)
    var touchedCount: Int = 0
        private set

    private fun noteTouched(id: Long) {
        for (i in 0 until touchedCount) if (touched[i] == id) return
        if (touchedCount >= MAX_TOUCHED) return
        touched[touchedCount++] = id
    }

    private val positionA = Vec3()
    private val positionB = Vec3()
    private val offsetA = Vec3()
    private val offsetB = Vec3()
    private val normal = Vec3()

    /**
     * The contact, in world coordinates, and the same point in the part's own
     * frame. Two vectors rather than one reused in place: the first version
     * passed a single scratch through both roles, so the world point was
     * overwritten by its own local form before the contact was solved. The
     * offsets that came out were the size of a planet and the impulses to
     * match - a six-metre-a-second nudge reported as six hundred and
     * ninety-five.
     */
    private val contactWorld = Vec3()
    private val localPoint = Vec3()
    private val localNormal = Vec3()
    private val relativeVelocity = Vec3()
    private val velocityA = Vec3()
    private val velocityB = Vec3()
    private val impulse = Vec3()
    private val tangent = Vec3()
    private val scratch = Vec3()
    private val inverseInertiaA = Mat3()
    private val inverseInertiaB = Mat3()

    /**
     * Resolves every overlapping pair among [vessels].
     *
     * Pairs are found by comparing bounding spheres, which is quadratic in the
     * number of vessels. That is deliberate for now: the cost is a squared
     * distance and a comparison, tens of vessels make it unmeasurable, and the
     * structure that replaces it - a spatial hash, or simply not considering
     * dormant craft - depends on decisions not yet made. `:core:tickBenchmark`
     * measures it; when that column starts to matter, this is the line to
     * change and nothing else needs to.
     */
    /**
     * Pairs to leave alone this tick: two halves of a craft that has just
     * staged, still overlapping as they part. Asked with the two ids in
     * either order.
     */
    var ignorePair: (Long, Long) -> Boolean = { _, _ -> false }

    fun resolve(vessels: List<Vessel>, dt: Double): CraftImpactReport {
        report.reset()
        touchedCount = 0
        for (i in vessels.indices) {
            val a = vessels[i]
            if (a.body.inverseMass <= 0.0) continue
            for (j in i + 1 until vessels.size) {
                val b = vessels[j]
                if (b.body.inverseMass <= 0.0) continue
                // Positions are relative to each craft's own attractor, so
                // comparing them across bodies would be nonsense.
                if (a.referenceBodyId != b.referenceBodyId) continue
                if (ignorePair(a.id.raw, b.id.raw)) continue

                scratch.setTo(a.body.position).subInPlace(b.body.position)
                val reach = a.contactRadius + b.contactRadius
                if (scratch.lengthSq > reach * reach) continue

                resolvePair(a, b, dt)
            }
        }
        return report
    }

    private fun resolvePair(a: Vessel, b: Vessel, dt: Double) {
        inverseInertiaA.setRotated(a.body.inverseInertiaLocal, a.body.orientation)
        inverseInertiaB.setRotated(b.body.inverseInertiaLocal, b.body.orientation)

        for (partA in a.defs.indices) {
            val defA = a.defs[partA]
            a.partPositionWorld(partA, positionA)
            val radiusA = defA.boundsHalfExtents.length

            for (partB in b.defs.indices) {
                val defB = b.defs[partB]
                b.partPositionWorld(partB, positionB)
                val radiusB = defB.boundsHalfExtents.length

                scratch.setTo(positionA).subInPlace(positionB)
                val reach = radiusA + radiusB
                if (scratch.lengthSq > reach * reach) continue

                for (point in defA.contactPoints.indices) {
                    a.contactPointWorld(partA, point, contactWorld)
                    if (penetrationOf(contactWorld, b, partB)) {
                        applyContact(a, b, contactWorld, partA, partB, dt)
                    }
                }
                for (point in defB.contactPoints.indices) {
                    b.contactPointWorld(partB, point, contactWorld)
                    if (penetrationOf(contactWorld, a, partA)) {
                        // The normal came out pointing away from A; the
                        // contact routine wants it pointing away from B.
                        normal.mulInPlace(-1.0)
                        applyContact(a, b, contactWorld, partA, partB, dt)
                    }
                }
            }
        }
    }

    /**
     * Whether [worldPoint] is inside part [partIndex] of [vessel], leaving the
     * outward normal in [normal] and returning the depth through [penetration].
     */
    private var penetration: Double = 0.0

    private fun penetrationOf(worldPoint: Vec3, vessel: Vessel, partIndex: Int): Boolean {
        vessel.worldToPartLocal(partIndex, worldPoint, localPoint)
        if (!insidePrimitive(vessel.defs[partIndex].mesh)) return false

        // Part local -> world, for the normal.
        vessel.design.parts[partIndex].rotation.rotate(localNormal, normal)
        vessel.body.orientation.rotate(normal, normal)
        return true
    }

    /**
     * Point-in-primitive, with the shallowest way out as the normal.
     *
     * Shallowest rather than nearest-surface because that is the direction the
     * contact should push: a corner barely inside a tank's end cap should be
     * ejected through the cap, not sideways through two metres of tank.
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
                val dx = hx - abs(p.x)
                val dy = hy - abs(p.y)
                val dz = hz - abs(p.z)
                if (dx <= 0.0 || dy <= 0.0 || dz <= 0.0) return false
                penetration = minOf(dx, dy, dz)
                when (penetration) {
                    dx -> localNormal.setTo(if (p.x < 0) -1.0 else 1.0, 0.0, 0.0)
                    dy -> localNormal.setTo(0.0, if (p.y < 0) -1.0 else 1.0, 0.0)
                    else -> localNormal.setTo(0.0, 0.0, if (p.z < 0) -1.0 else 1.0)
                }
                return true
            }

            is MeshSpec.Cylinder -> return insideTube(mesh.radius, mesh.radius, mesh.height)

            // A cone is treated as a tube whose radius varies with height. The
            // parts that use it are engine bells and nose cones, where the
            // taper is gentle and the difference is millimetres.
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

    /** One contact: separate the pair, cancel the approach, record the hit. */
    private fun applyContact(
        a: Vessel,
        b: Vessel,
        point: Vec3,
        partA: Int,
        partB: Int,
        dt: Double,
    ) {
        val bodyA = a.body
        val bodyB = b.body

        noteTouched(a.id.raw)
        noteTouched(b.id.raw)

        offsetA.setTo(point).subInPlace(bodyA.position)
        offsetB.setTo(point).subInPlace(bodyB.position)

        bodyA.velocityAtOffset(offsetA, velocityA)
        bodyB.velocityAtOffset(offsetB, velocityB)
        relativeVelocity.setTo(velocityA).subInPlace(velocityB)
        val approach = relativeVelocity dot normal

        // Split the separation by inverse mass, so a probe bounces off a
        // station rather than shoving it.
        val totalInverseMass = bodyA.inverseMass + bodyB.inverseMass
        if (totalInverseMass <= 0.0) return
        val correction = penetration * POSITION_CORRECTION / totalInverseMass
        scratch.setTo(normal).mulInPlace(correction * bodyA.inverseMass)
        bodyA.position.addInPlace(scratch)
        scratch.setTo(normal).mulInPlace(-correction * bodyB.inverseMass)
        bodyB.position.addInPlace(scratch)

        if (approach >= 0.0) return

        val impactSpeed = -approach
        if (impactSpeed > a.defs[partA].crashTolerance) {
            report.record(a.id.raw, partA, impactSpeed)
        }
        if (impactSpeed > b.defs[partB].crashTolerance) {
            report.record(b.id.raw, partB, impactSpeed)
        }

        val magnitude = solveImpulse(bodyA, bodyB, normal, approach, RESTITUTION)
        impulse.setTo(normal).mulInPlace(magnitude)
        bodyA.applyImpulseAtOffset(impulse, offsetA)
        impulse.mulInPlace(-1.0)
        bodyB.applyImpulseAtOffset(impulse, offsetB)

        applyFriction(bodyA, bodyB, magnitude)
    }

    /**
     * `j = -(1+e) v_n / (1/mA + 1/mB + angular terms)`.
     *
     * The two-body form of [GroundContact]'s solver: the ground is immovable
     * and contributes nothing, another craft contributes its own inverse mass
     * and its own rotational response.
     */
    private fun solveImpulse(
        bodyA: RigidBody,
        bodyB: RigidBody,
        contactNormal: Vec3,
        approach: Double,
        restitution: Double,
    ): Double {
        var denominator = bodyA.inverseMass + bodyB.inverseMass

        scratch.setTo(offsetA).crossInPlace(contactNormal)
        inverseInertiaA.transform(scratch, scratch)
        scratch.crossInPlace(offsetA)
        denominator += scratch dot contactNormal

        scratch.setTo(offsetB).crossInPlace(contactNormal)
        inverseInertiaB.transform(scratch, scratch)
        scratch.crossInPlace(offsetB)
        denominator += scratch dot contactNormal

        if (denominator <= 1e-12) return 0.0
        return -(1.0 + restitution) * approach / denominator
    }

    private fun applyFriction(bodyA: RigidBody, bodyB: RigidBody, normalImpulse: Double) {
        bodyA.velocityAtOffset(offsetA, velocityA)
        bodyB.velocityAtOffset(offsetB, velocityB)
        relativeVelocity.setTo(velocityA).subInPlace(velocityB)

        val alongNormal = relativeVelocity dot normal
        tangent.setTo(relativeVelocity).addScaledInPlace(normal, -alongNormal)
        val speed = tangent.length
        if (speed < 1e-6) return
        tangent.mulInPlace(1.0 / speed)

        val stopping = solveImpulse(bodyA, bodyB, tangent, speed, 0.0)
        val limit = FRICTION * normalImpulse
        val magnitude = stopping.coerceIn(-limit, limit)

        impulse.setTo(tangent).mulInPlace(magnitude)
        bodyA.applyImpulseAtOffset(impulse, offsetA)
        impulse.mulInPlace(-1.0)
        bodyB.applyImpulseAtOffset(impulse, offsetB)
    }

    private companion object {
        /** More craft in one pile-up than anything should produce. */
        const val MAX_TOUCHED = 64

        const val POSITION_CORRECTION = 0.35
        const val RESTITUTION = 0.05
        const val FRICTION = 0.5
    }
}
