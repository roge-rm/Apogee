package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Vec3

/** One craft hitting another, for the caller to turn into consequences. */
class CraftImpactReport {
    val vessels = LongArray(MAX_IMPACTS)
    val parts = IntArray(MAX_IMPACTS)
    val speeds = DoubleArray(MAX_IMPACTS)
    /** The contact normal, into the part that was hit. Three per impact. */
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
 * Craft against craft. Shaped like [GroundContact]: each part's hull points are tested against the
 * other craft's part volumes, and answered with the same sequential impulses.
 *
 * Both directions are tested, because a point test is one-sided. A small part can sit inside a
 * big one without any of the big one's corners being inside it.
 */
class CraftContact {

    val report = CraftImpactReport()

    /**
     * Craft in any contact this tick, harmful or not. Separate from [report], which only lists
     * what broke. Dormancy needs this, so something resting on a sleeping base wakes it.
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
     * The contact point in world coordinates. Keep it separate from the part-frame scratch, or the
     * world point gets overwritten before the contact is solved.
     */
    private val contactWorld = Vec3()
    private val relativeVelocity = Vec3()
    private val velocityA = Vec3()
    private val velocityB = Vec3()
    private val impulse = Vec3()
    private val tangent = Vec3()
    private val scratch = Vec3()
    private val inverseInertiaA = Mat3()
    private val inverseInertiaB = Mat3()

    /**
     * Resolves every overlapping pair in [vessels]. Pairs are found by bounding spheres, which is
     * O(n²) but cheap at tens of vessels. `:core:tickBenchmark` measures it.
     */
    /**
     * Pairs to leave alone this tick: two halves of a craft that just staged, still overlapping.
     * Asked with the ids in either order.
     */
    var ignorePair: (Long, Long) -> Boolean = { _, _ -> false }

    /**
     * Pairs that touch gently this tick: two halves of a craft that just staged. Still solid, so a
     * burning stage pushes the one above, but the starting overlap eases apart slowly and does no
     * damage.
     */
    var gentlePair: (Long, Long) -> Boolean = { _, _ -> false }
    private var gentle = false

    /** Told about each gentle pair still touching, so its grace period lasts as long as the push. */
    var gentleTouching: (Long, Long) -> Unit = { _, _ -> }
    private var gentleTouched = false

    fun resolve(vessels: List<Vessel>, dt: Double): CraftImpactReport {
        report.reset()
        touchedCount = 0
        for (i in vessels.indices) {
            val a = vessels[i]
            for (j in i + 1 until vessels.size) {
                val b = vessels[j]
                // Two immovable things, like two founded bases, can't push each other.
                if (a.body.inverseMass <= 0.0 && b.body.inverseMass <= 0.0) continue
                // Positions are relative to each craft's attractor, so different bodies don't mix.
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
            // Nowhere near the other craft, so skip its parts. Most of a big base hits this.
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

                // Wheels, legs and feet meet craft the way they meet ground, in GroundContact.
                // Here they're only something for the other craft's points to hit.
                if (!isFoot(defA)) for (point in defA.contactPoints.indices) {
                    a.contactPointWorld(partA, point, contactWorld)
                    if (penetrationOf(contactWorld, b, partB)) {
                        applyContact(a, b, contactWorld, partA, partB, dt)
                    }
                }
                if (!isFoot(defB)) for (point in defB.contactPoints.indices) {
                    b.contactPointWorld(partB, point, contactWorld)
                    if (penetrationOf(contactWorld, a, partA)) {
                        // The normal points away from A; the contact routine wants it away from B.
                        normal.mulInPlace(-1.0)
                        applyContact(a, b, contactWorld, partA, partB, dt)
                    }
                }
            }
        }
    }

    /** The depth of the contact [penetrationOf] last found. */
    private var penetration: Double = 0.0
    private val volume = PartVolume()

    /**
     * Whether [worldPoint] is inside part [partIndex] of [vessel]. It leaves the outward normal in
     * [normal] and the depth in [penetration].
     */
    private fun penetrationOf(worldPoint: Vec3, vessel: Vessel, partIndex: Int): Boolean {
        if (!volume.inside(worldPoint, vessel, partIndex)) return false
        normal.setTo(volume.normal)
        penetration = volume.penetration
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
            // Halves of one stack push apart through the centres, along the stack's thrust. At the
            // touching points the ring round a decoupler never balances and the stage tumbles.
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

        // Split the separation by inverse mass, so a probe bounces off a station.
        val totalInverseMass = bodyA.inverseMass + bodyB.inverseMass
        if (totalInverseMass <= 0.0) return
        // Only gentle while the overlap is still or opening. Driven in, like a stage that's still
        // burning, it's fully solid, or one flies through the other.
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
     * `j = -(1+e) v_n / (1/mA + 1/mB + angular terms)`. The two-body form of [GroundContact]'s
     * solver.
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
        /** More craft in one pile-up than anything should make. */
        const val MAX_TOUCHED = 64

        const val POSITION_CORRECTION = 0.35

        /** Share of a gentle pair's overlap closed each tick, so halves part over about a second. */
        const val GENTLE_CORRECTION = 0.03
        const val RESTITUTION = 0.05
        const val FRICTION = 0.5
    }
}
