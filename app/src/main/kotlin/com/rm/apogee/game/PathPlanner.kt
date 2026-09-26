package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Descent
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.orbit.Trajectory
import com.rm.apogee.core.world.Burns
import com.rm.apogee.core.world.PlannedBurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Where the flown craft is going, worked out off the frame thread a few
 * times a second: the path it coasts on, the path after its next planned
 * burn, where it comes down if it is coming down, and how near it passes
 * its target. The map draws it; the HUD reads it.
 */
class PathPlanner(private val system: SolarSystem) {

    /** What was worked out, for a craft about [bodyId] at universe [time]. */
    class Plan(
        val bodyId: String,
        val time: Double,
        /** Coasting from now, as it is. */
        val current: Trajectory,
        /** The next planned burn, when it falls on the first leg of [current]. */
        val burn: PlannedBurn?,
        /** Where the craft is at the burn, relative to [bodyId]. */
        val burnPoint: Vec3?,
        /** After the burn. */
        val planned: Trajectory?,
        /** Where it comes down, coasting, if it does - drag and hills included. */
        val impact: Descent.Impact?,
        /** How near it passes its target, coasting - or after the burn, with one. */
        val approach: Trajectory.Approach?,
    )

    /** What is known of the craft now: see [ask]. */
    class Ask(
        val bodyId: String,
        val position: Vec3,
        val velocity: Vec3,
        val time: Double,
        val burns: List<PlannedBurn>,
        /** Its mass, kg, and drag area, m², for where it comes down; 0 mass for no guess. */
        val mass: Double,
        val dragArea: Double,
        /** A body to pass near, by id, or blank. */
        val targetBody: String,
        /** A craft to pass near: its position and velocity about [bodyId], or null. */
        val targetPosition: Vec3?,
        val targetVelocity: Vec3?,
    )

    @Volatile var plan: Plan? = null
        private set

    private var job: Job? = null
    private var askedNanos = 0L

    /** Works the plan out afresh from [ask], at most every [REFRESH_NANOS], in [scope]. */
    fun ask(scope: CoroutineScope, ask: Ask, now: Long = System.nanoTime()) {
        if (job?.isActive == true || now - askedNanos < REFRESH_NANOS) return
        askedNanos = now
        job = scope.launch(Dispatchers.Default) {
            plan = runCatching { work(ask) }.getOrNull() ?: plan
        }
    }

    /** Forgets it: another craft, or out of flight. */
    fun clear() {
        plan = null
        askedNanos = 0L
    }

    fun work(ask: Ask): Plan {
        val body = system.body(ask.bodyId)
        val current = Trajectory.predict(system, ask.bodyId, ask.position, ask.velocity, ask.time)
        val first = current.segments.first()
        val burn = ask.burns.firstOrNull()?.takeIf { it.time >= ask.time - 60.0 && it.time <= first.end }
        var burnPoint: Vec3? = null
        var planned: Trajectory? = null
        if (burn != null) {
            val after = Burns.after(burn, first.orbit)
            burnPoint = after.position.copy()
            planned = Trajectory.predict(system, ask.bodyId, after.position, after.velocity, burn.time)
        }
        val impact = if (ask.mass > 0.0) Descent.predict(body, ask.position, ask.velocity, ask.time, ask.mass, ask.dragArea) else null
        val approach = approachOf(planned ?: current, ask)
        return Plan(ask.bodyId, ask.time, current, burn, burnPoint, planned, impact, approach)
    }

    /** How near [path] comes to the craft's target, in the target's own body's frame. */
    private fun approachOf(path: Trajectory, ask: Ask): Trajectory.Approach? {
        if (ask.targetBody.isNotEmpty() && ask.targetBody in system.bodies) {
            // Meeting it: as near as its low point there.
            path.about(ask.targetBody)?.let { there ->
                val o = there.orbit
                val speed = kotlin.math.sqrt((o.mu * (2.0 / o.periapsis - 1.0 / o.semiMajorAxis)).coerceAtLeast(0.0))
                return Trajectory.Approach(there.start + o.timeToPeriapsis.coerceAtMost(there.end - there.start), o.periapsis, speed)
            }
            // Else as near as it comes along the leg about the target's parent.
            val parent = system.body(ask.targetBody).parentId ?: return null
            val leg = path.segments.firstOrNull { it.bodyId == parent } ?: return null
            return Trajectory.closestApproach(leg) { t, out ->
                out.setTo(system.positionOf(ask.targetBody, t)).subInPlace(system.positionOf(parent, t))
            }
        }
        val position = ask.targetPosition ?: return null
        val velocity = ask.targetVelocity ?: return null
        val leg = path.segments.firstOrNull { it.bodyId == ask.bodyId } ?: return null
        val target = Orbit(position.copy(), velocity.copy(), system.body(ask.bodyId).gravitationalParameter, ask.time)
        return Trajectory.closestApproach(leg) { t, out -> out.setTo(target.stateAt(t).position) }
    }

    /**
     * Where a point [point] on [segment] (relative to the segment's body)
     * is drawn about body [aboutId] - a moon's leg drawn round the moon as
     * it will be when the craft arrives, into [out].
     */
    fun drawnAbout(segment: Trajectory.Segment, point: Vec3, aboutId: String, out: Vec3): Vec3 {
        out.setTo(point)
        if (segment.bodyId == aboutId) return out
        return out.addInPlace(system.positionOf(segment.bodyId, segment.start)).subInPlace(system.positionOf(aboutId, segment.start))
    }

    private companion object {
        const val REFRESH_NANOS = 250_000_000L
    }
}
