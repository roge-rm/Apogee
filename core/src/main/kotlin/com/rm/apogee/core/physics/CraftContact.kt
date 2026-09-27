package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import kotlin.math.abs
import kotlin.math.sqrt

/** One craft hitting another, for the caller to turn into consequences. */
class CraftImpactReport {
    val vessels = LongArray(MAX_IMPACTS)
    val parts = IntArray(MAX_IMPACTS)
    val speeds = DoubleArray(MAX_IMPACTS)
    /** The contact normal, pointing into the part that was hit, three per impact. */
    val normals = DoubleArray(MAX_IMPACTS * 3)
    var count: Int = 0
        private set

    fun reset() {
        count = 0
    }

    fun record(vesselId: Long, partIndex: Int, speed: Double, nx: Double = 0.0, ny: Double = 0.0, nz: Double = 0.0) {
        for (i in 0 until count) {
            if (vessels[i] == vesselId && parts[i] == partIndex) {
                if (speed > speeds[i]) {
                    speeds[i] = speed
                    normals[i * 3] = nx; normals[i * 3 + 1] = ny; normals[i * 3 + 2] = nz
                }
                return
            }
        }
        if (count >= MAX_IMPACTS) return
        vessels[count] = vesselId
        parts[count] = partIndex
        speeds[count] = speed
        normals[count * 3] = nx; normals[count * 3 + 1] = ny; normals[count * 3 + 2] = nz
        count++
    }

    private companion object {
        const val MAX_IMPACTS = 32
    }
}

/**
 * Craft against craft.
 *
 * Until this existed, two vessels in the same world passed straight through each other. That's fine
 * while everything is a rocket flying its own path, and not fine at all once anything is meant to
 * be *solid*, like a base you can land on, dock with or fly into.
 *
 * It's shaped like [GroundContact] on purpose. That resolver tests each part's hull points against
 * the terrain, and this one tests each part's hull points against the *other craft's part volumes*,
 * and answers with the same sequential impulses. Parts are already primitives (cylinder, cone, box,
 * sphere), so a point-in-volume test is a handful of arithmetic per point and doesn't need any of
 * the machinery a general convex solver would.
 *
 * Both directions are tested, A's points against B's volumes and B's against A's, because a
 * point-based test is one-sided. A small part can sit entirely inside a large one without any of
 * the large one's corners being inside the small one.
 */
class CraftContact {

    val report = CraftImpactReport()

    /**
     * Craft involved in any contact this tick, whether it did damage or not.
     *
     * This is separate from [report], which only lists what broke. Dormancy needs the wider set.
     * Something resting against a sleeping base has to wake it whether it did any harm or not, or
     * the base acts like scenery.
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
     * The contact point in world coordinates, and the same point in the part's own frame. They're
     * two vectors instead of one reused in place. The first version used a single scratch vector
     * for both, so the world point got overwritten by its own local form before the contact was
     * solved. The offsets that came out were the size of a planet, and the impulses matched: a
     * six-metre-a-second nudge got reported as six hundred and ninety-five.
     */
    private val contactWorld = Vec3()
    private val localPoint = Vec3()
    private val partLocal = Vec3()
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
     * Resolves every overlapping pair in [vessels].
     *
     * Pairs are found by comparing bounding spheres, which grows with the square of the number of
     * vessels. That's deliberate for now. The cost is a squared distance and a comparison, tens of
     * vessels make it too small to measure, and whatever replaces it (a spatial hash, or just
     * skipping dormant craft) depends on decisions that haven't been made yet.
     * `:core:tickBenchmark` measures it. When that column starts to matter, this is the line to
     * change and nothing else needs to.
     */
    /**
     * Pairs to leave alone this tick: two halves of a craft that has just staged, still overlapping
     * as they separate. Asked with the two ids in either order.
     */
    var ignorePair: (Long, Long) -> Boolean = { _, _ -> false }

    /**
     * Pairs that touch gently this tick: two halves of a craft that has just staged. They're still
     * solid to each other, so a stage let go with its engine still burning pushes the one above
     * instead of flying through it. But the overlap they start with gets eased apart slowly and
     * nothing gets hurt by it.
     */
    var gentlePair: (Long, Long) -> Boolean = { _, _ -> false }
    private var gentle = false

    /**
     * Told about each gentle pair still touching this tick, so its grace period lasts as long as
     * the push does.
     */
    var gentleTouching: (Long, Long) -> Unit = { _, _ -> }
    private var gentleTouched = false

    fun resolve(vessels: List<Vessel>, dt: Double): CraftImpactReport {
        report.reset()
        touchedCount = 0
        for (i in vessels.indices) {
            val a = vessels[i]
            for (j in i + 1 until vessels.size) {
                val b = vessels[j]
                // Two things that can't move, like two founded bases, can't push each other
                // anywhere. One is just a wall to the other.
                if (a.body.inverseMass <= 0.0 && b.body.inverseMass <= 0.0) continue
                // Positions are relative to each craft's own attractor, so comparing them across
                // different bodies would be nonsense.
                if (a.referenceBodyId != b.referenceBodyId) continue
                if (ignorePair(a.id.raw, b.id.raw)) continue
                gentle = gentlePair(a.id.raw, b.id.raw)

                scratch.setTo(a.body.position).subInPlace(b.body.position)
                val reach = a.contactRadius + b.contactRadius
                if (scratch.lengthSq > reach * reach) continue

                gentleTouched = false
                resolvePair(a, b, dt)
                if (gentleTouched) gentleTouching(a.id.raw, b.id.raw)
            }
        }
        return report
    }

    private fun resolvePair(a: Vessel, b: Vessel, dt: Double) {
        inverseInertiaA.setRotated(a.body.inverseInertiaLocal, a.body.orientation)
        inverseInertiaB.setRotated(b.body.inverseInertiaLocal, b.body.orientation)

        for (partA in a.defs.indices) {
            val defA = a.defs[partA]
            if (!defA.solid) continue
            a.partPositionWorld(partA, positionA)
            val radiusA = defA.boundsHalfExtents.length
            // Nowhere near the other craft at all, so none of its parts need testing. A spaceport
            // with hundreds of parts next to a rover is mostly this.
            scratch.setTo(positionA).subInPlace(b.body.position)
            val reachB = radiusA + b.contactRadius
            if (scratch.lengthSq > reachB * reachB) continue

            for (partB in b.defs.indices) {
                val defB = b.defs[partB]
                if (!defB.solid) continue
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
                        // The normal came out pointing away from A, and the contact routine wants
                        // it pointing away from B.
                        normal.mulInPlace(-1.0)
                        applyContact(a, b, contactWorld, partA, partB, dt)
                    }
                }
            }
        }
    }

    /**
     * Whether [worldPoint] is inside part [partIndex] of [vessel]. It leaves the outward normal in
     * [normal] and returns the depth through [penetration].
     */
    private var penetration: Double = 0.0

    private fun penetrationOf(worldPoint: Vec3, vessel: Vessel, partIndex: Int): Boolean {
        val def = vessel.defs[partIndex]
        if (!def.solid) return false
        vessel.worldToPartLocal(partIndex, worldPoint, localPoint)
        if (def.hull.isEmpty()) {
            if (!insidePrimitive(def.mesh)) return false
        } else {
            // Inside any one of its volumes, each tested in its own place.
            partLocal.setTo(localPoint)
            var inside = false
            for (volume in def.hull) {
                localPoint.setTo(partLocal).subInPlace(volume.offset)
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

    /** One contact: separate the pair, cancel the approach, and record the hit. */
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

        if (gentle) {
            // Two halves of one stack pushing apart, through the centres, the same way the stack's
            // own thrust went. Taken at the touching points, the ring of points around a decoupler
            // never balances, and a stage still burning below spun the one above into a tumble.
            gentleTouched = true
            offsetA.setZero()
            offsetB.setZero()
        } else {
            offsetA.setTo(point).subInPlace(bodyA.position)
            offsetB.setTo(point).subInPlace(bodyB.position)
        }

        bodyA.velocityAtOffset(offsetA, velocityA)
        bodyB.velocityAtOffset(offsetB, velocityB)
        relativeVelocity.setTo(velocityA).subInPlace(velocityB)
        val approach = relativeVelocity dot normal

        // Split the separation by inverse mass, so a probe bounces off a station instead of shoving
        // it.
        val totalInverseMass = bodyA.inverseMass + bodyB.inverseMass
        if (totalInverseMass <= 0.0) return
        // Only gentle while the overlap is standing still or opening, which is what's left over
        // from the split. When it's being driven in, like a stage below that's still burning, it's
        // as solid as anything else. Otherwise one flew right through the other.
        val correction = penetration * (if (gentle && approach >= 0.0) GENTLE_CORRECTION else POSITION_CORRECTION) / totalInverseMass
        scratch.setTo(normal).mulInPlace(correction * bodyA.inverseMass)
        bodyA.position.addInPlace(scratch)
        scratch.setTo(normal).mulInPlace(-correction * bodyB.inverseMass)
        bodyB.position.addInPlace(scratch)

        if (approach >= 0.0) return

        val impactSpeed = -approach
        // The normal points from B to A. A is hit along it and B against it.
        if (!gentle && impactSpeed > a.defs[partA].crashTolerance) {
            report.record(a.id.raw, partA, impactSpeed, normal.x, normal.y, normal.z)
        }
        if (!gentle && impactSpeed > b.defs[partB].crashTolerance) {
            report.record(b.id.raw, partB, impactSpeed, -normal.x, -normal.y, -normal.z)
        }

        val magnitude = solveImpulse(bodyA, bodyB, normal, approach, RESTITUTION)
        impulse.setTo(normal).mulInPlace(magnitude)
        bodyA.applyImpulseAtOffset(impulse, offsetA)
        impulse.mulInPlace(-1.0)
        bodyB.applyImpulseAtOffset(impulse, offsetB)

        if (!gentle) applyFriction(bodyA, bodyB, magnitude)
    }

    /**
     * `j = -(1+e) v_n / (1/mA + 1/mB + angular terms)`.
     *
     * This is the two-body form of [GroundContact]'s solver. The ground can't move and adds
     * nothing, while another craft adds its own inverse mass and its own rotational response.
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

        /**
         * The share of a gentle pair's overlap closed each tick, so parting halves ease apart over
         * a second or so.
         */
        const val GENTLE_CORRECTION = 0.03
        const val RESTITUTION = 0.05
        const val FRICTION = 0.5
    }
}
