package com.rm.apogee.core.physics

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.DockingPort
import kotlin.math.acos
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math

/** One docking part on one craft: which one, where its face is and which way it faces, this tick. */
class PortRef(val vessel: Vessel, val index: Int, val port: DockingPort) {
    /** The centre of the face, in world space. */
    val face = Vec3()
    /** The way the face faces, in world space, unit length. */
    val axis = Vec3()
    /** The face's offset from the craft's centre of mass, in world space. */
    val offset = Vec3()

    fun update(): PortRef {
        vessel.partPointOffsetWorld(index, local.setTo(0.0, port.faceOffset, 0.0), offset)
        face.setTo(offset).addInPlace(vessel.body.position)
        vessel.design.parts[index].rotation.rotate(Vec3.unitY(), axis)
        vessel.body.orientation.rotate(axis, axis).normalizeInPlace()
        return this
    }

    private val local = Vec3()
}

/**
 * Two docking parts drawing each other in: captured, and on the way to latching or letting go.
 *
 * Soft capture: brought within [DockingPort.captureRange], facing each other within
 * [DockingPort.captureAngle] and coming together slower than [DockingPort.captureSpeed], a pair
 * takes hold. From then on a spring pulls the faces together and a torque lines them up. It's
 * critically damped on the two craft's own masses and inertias, so a light probe and a heavy
 * station come together without bouncing, and it's capped at the part's [DockingPort.pull] and
 * [DockingPort.turn], so the magnets can steady a drift but can't drag a craft under power. Once
 * the faces have sat together long enough it asks to latch. If they're pulled apart, it lets go.
 *
 * It's pure. It pushes the two bodies and reports back, and joining the craft is the world's job.
 */
class Docking {

    class Capture(val a: PortRef, val b: PortRef) {
        /** Seconds the faces have been together. */
        var together = 0.0
        val key: Long get() = pairKey(a.vessel.id.raw, a.index, b.vessel.id.raw, b.index)
    }

    /** The pairs captured right now, by [Capture.key]. */
    val captures = LinkedHashMap<Long, Capture>()

    /** Pairs ready to latch this tick, for the world to join. */
    val latching = ArrayList<Capture>()

    private val ports = ArrayList<PortRef>()
    private val pool = ArrayList<PortRef>()
    private val separation = Vec3()
    private val relative = Vec3()
    private val va = Vec3()
    private val vb = Vec3()
    private val force = Vec3()
    private val torque = Vec3()
    private val scratch = Vec3()

    /**
     * Steps every capture and looks for new ones among [vessels] over [dt]. [ignore] names pairs of
     * craft that mustn't capture now, because they just undocked or are already joined.
     */
    fun step(vessels: Collection<Vessel>, dt: Double, ignore: (Long, Long) -> Boolean, occupied: (Vessel, Int) -> Boolean = { _, _ -> false }) {
        latching.clear()
        collect(vessels, occupied)

        // Captures first: drawn in, lined up, then latched or let go.
        val iterator = captures.values.iterator()
        while (iterator.hasNext()) {
            val c = iterator.next()
            val a = find(c.a.vessel, c.a.index)
            val b = find(c.b.vessel, c.b.index)
            if (a == null || b == null || ignore(a.vessel.id.raw, b.vessel.id.raw)) { iterator.remove(); continue }
            val m = measure(a, b)
            // Yanked apart, or twisted right off, so let go.
            if (m.distance > a.port.captureRange * RELEASE_RANGE || (a.port.rigid && m.angle > a.port.captureAngle * RELEASE_ANGLE)) {
                iterator.remove(); continue
            }
            pull(a, b, m, dt)
            val settled = m.distance < minOf(a.port.latchRange, b.port.latchRange) &&
                (!a.port.rigid || m.angle < minOf(a.port.latchAngle, b.port.latchAngle)) && m.speed < LATCH_SPEED
            c.together = if (settled) c.together + dt else 0.0
            if (c.together >= maxOf(a.port.latchSeconds, b.port.latchSeconds)) latching.add(c)
        }

        // New captures.
        for (i in ports.indices) for (j in i + 1 until ports.size) {
            val a = ports[i]; val b = ports[j]
            if (a.vessel === b.vessel) continue
            if (a.vessel.referenceBodyId != b.vessel.referenceBodyId) continue
            if (!a.port.matesWith(b.port)) continue
            if (captures.containsKey(pairKey(a.vessel.id.raw, a.index, b.vessel.id.raw, b.index))) continue
            if (busy(a) || busy(b)) continue
            if (ignore(a.vessel.id.raw, b.vessel.id.raw)) continue
            if (a.face.distanceTo(b.face) > maxOf(a.port.captureRange, b.port.captureRange)) continue
            val m = measure(a, b)
            val range = minOf(a.port.captureRange, b.port.captureRange)
            // Close in, the guides allow more, up to twice the angle with the faces touching. That
            // way two rings that met a little crooked and came to rest against each other still
            // draw in instead of just sitting there.
            val angle = minOf(a.port.captureAngle, b.port.captureAngle) * (2.0 - minOf(1.0, m.distance / range))
            val speed = minOf(a.port.captureSpeed, b.port.captureSpeed)
            if (m.distance > range || m.angle > angle || m.speed > speed) continue
            // In front of each other, not back to back.
            if (m.distance > 0.05 && (separation.setTo(b.face).subInPlace(a.face) dot a.axis) < -0.1) continue
            a.vessel.wake(); b.vessel.wake()
            val c = Capture(a, b)
            captures[c.key] = c
        }
    }

    /** Forgets any capture involving [vessel], because it has been joined, split or removed. */
    fun forget(vessel: Long) {
        captures.values.removeAll { it.a.vessel.id.raw == vessel || it.b.vessel.id.raw == vessel }
    }

    /** Whether [vessel] is drawing, or being drawn to, anything right now. */
    fun capturing(vessel: Long): Boolean = captures.values.any { it.a.vessel.id.raw == vessel || it.b.vessel.id.raw == vessel }

    /** Whether craft [a] and [b] are drawing each other in right now. */
    fun capturing(a: Long, b: Long): Boolean = captures.values.any {
        (it.a.vessel.id.raw == a && it.b.vessel.id.raw == b) || (it.a.vessel.id.raw == b && it.b.vessel.id.raw == a)
    }

    private fun busy(p: PortRef) = captures.values.any { (it.a.vessel === p.vessel && it.a.index == p.index) || (it.b.vessel === p.vessel && it.b.index == p.index) }

    private fun find(v: Vessel, index: Int): PortRef? = ports.firstOrNull { it.vessel === v && it.index == index }

    private fun collect(vessels: Collection<Vessel>, occupied: (Vessel, Int) -> Boolean) {
        ports.clear()
        var used = 0
        for (v in vessels) {
            for (i in v.defs.indices) {
                val port = v.defs[i].module<DockingPort>() ?: continue
                if (v.isBroken(i) || v.design.parts[i].dockedTo >= 0 || occupied(v, i)) continue
                val ref = if (used < pool.size && pool[used].vessel === v && pool[used].index == i) pool[used]
                    else PortRef(v, i, port).also { if (used < pool.size) pool[used] = it else pool.add(it) }
                used++
                ports.add(ref.update())
            }
        }
    }

    class Measure {
        var distance = 0.0
        /** Degrees off facing each other, with 0 meaning face to face. */
        var angle = 0.0
        /** How fast the faces are moving apart or together, in m/s. */
        var speed = 0.0
    }

    private val measured = Measure()

    private fun measure(a: PortRef, b: PortRef): Measure {
        separation.setTo(b.face).subInPlace(a.face)
        measured.distance = separation.length
        val facing = -(a.axis dot b.axis)
        measured.angle = Math.toDegrees(acos(facing.coerceIn(-1.0, 1.0)))
        a.vessel.body.velocityAtOffset(a.offset, va)
        b.vessel.body.velocityAtOffset(b.offset, vb)
        measured.speed = relative.setTo(vb).subInPlace(va).length
        return measured
    }

    /**
     * The magnets: a critically damped spring drawing B's face to A's, and for a rigid pair a
     * second one turning B to face A squarely.
     */
    private fun pull(a: PortRef, b: PortRef, m: Measure, dt: Double) {
        val bodyA = a.vessel.body; val bodyB = b.vessel.body
        // From the inverse masses, so a founded base, which can't move and is infinitely heavy,
        // leaves the other craft's own mass to be drawn in.
        val inverseMass = bodyA.inverseMass + bodyB.inverseMass
        if (inverseMass <= 0.0) return
        val reduced = 1.0 / inverseMass
        // Stiff enough to reach full pull by half the capture range (when it was soft, a cart's
        // rolling resistance held a hitch a quarter of a metre short), and critically damped on the
        // two craft's masses.
        val most = minOf(a.port.pull, b.port.pull)
        val k = maxOf(reduced * PULL_RATE * PULL_RATE, most / (0.5 * minOf(a.port.captureRange, b.port.captureRange)))
        val c = 2.0 * sqrt(k * reduced)
        // The force on A, toward B. B gets the opposite.
        separation.setTo(b.face).subInPlace(a.face)
        force.setTo(separation).mulInPlace(k).addScaledInPlace(relative.setTo(vb).subInPlace(va), c)
        if (force.length > most) force.mulInPlace(most / force.length)
        bodyA.applyImpulseAtOffset(scratch.setTo(force).mulInPlace(dt), a.offset)
        bodyB.applyImpulseAtOffset(scratch.setTo(force).mulInPlace(-dt), b.offset)

        if (!a.port.rigid || !b.port.rigid) return
        // Turn B so its axis points back along A's, around B x (-A).
        torque.setTo(b.axis).crossInPlace(scratch.setTo(a.axis).mulInPlace(-1.0))
        val sin = torque.length
        if (sin < 1e-6 && m.angle < 90.0) return
        val about = if (sin < 1e-6) Vec3.unitX() else scratch.setTo(torque).mulInPlace(1.0 / sin)
        val inverse = bodyA.inverseInertiaAbout(about) + bodyB.inverseInertiaAbout(about)
        if (inverse <= 0.0) return
        val inertia = 1.0 / inverse
        val angle = Math.toRadians(m.angle)
        val spin = (bodyB.angularVelocity dot about) - (bodyA.angularVelocity dot about)
        var t = inertia * (TURN_RATE * TURN_RATE * angle - 2.0 * TURN_RATE * spin)
        val limit = minOf(a.port.turn, b.port.turn)
        t = t.coerceIn(-limit, limit)
        bodyB.applyAngularImpulse(torque.setTo(about).mulInPlace(t * dt))
        bodyA.applyAngularImpulse(torque.setTo(about).mulInPlace(-t * dt))
    }

    companion object {
        /**
         * Faces within this many metres, this many degrees and this slow in m/s are ready to latch.
         */
        /** The default for [DockingPort.latchRange]. */
        const val LATCH_DISTANCE = 0.06
        /** The default for [DockingPort.latchAngle]. */
        const val LATCH_ANGLE = 2.5
        const val LATCH_SPEED = 0.25

        /** How far past capture range, and capture angle, before a capture lets go. */
        const val RELEASE_RANGE = 1.6
        const val RELEASE_ANGLE = 2.5

        /**
         * The magnets' natural rates, in rad/s. That's about three seconds to draw in and line up.
         */
        const val PULL_RATE = 1.5
        const val TURN_RATE = 1.5

        fun pairKey(va: Long, ia: Int, vb: Long, ib: Int): Long {
            val a = va * 4_096 + ia
            val b = vb * 4_096 + ib
            return if (a < b) a * 1_000_003L + b else b * 1_000_003L + a
        }
    }
}
