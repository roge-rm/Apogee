package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.orbit.Trajectory

/** The kinds of mark on the map's path, the first ones kept when two would crowd each other. */
enum class MarkKind(val tag: String) {
    AP("AP"), PE("PE"), BURN("BURN"), ENTER("IN"), LEAVE("OUT"), NEAR("NEAR"), LAND("LAND"), AIR("AIR"), AN("AN"), DN("DN"),
}

/**
 * A point on the path: what it is, when (universe time), where (around the body the map is drawn
 * round), and the body it's about. [height] is above that body's datum, NaN for none. [extra] is the
 * pass distance for [MarkKind.NEAR], the speed for [MarkKind.LAND], the plane angle in radians for
 * the nodes. [ghost] is where the target is at a near pass.
 */
class PathMark(
    val kind: MarkKind,
    val time: Double,
    val at: Vec3,
    val bodyId: String,
    val height: Double = Double.NaN,
    val extra: Double = Double.NaN,
    val ghost: Vec3? = null,
)

/** The marks along a path the [PathPlanner] worked out, or along an orbit with no plan. */
class PathMarks(private val system: SolarSystem) {

    /** The marks on [plan], drawn round [aboutId]. */
    fun of(plan: PathPlanner.Plan, aboutId: String): List<PathMark> {
        val out = ArrayList<PathMark>()
        val burn = plan.burn
        val path = plan.planned ?: plan.current
        plan.burnPoint?.let { if (burn != null) out += PathMark(MarkKind.BURN, burn.time, it.copy(), plan.bodyId) }
        var aired = false
        for (segment in path.segments) {
            val body = system.body(segment.bodyId)
            val o = segment.orbit
            if (o.isBound && o.apoapsis < body.sphereOfInfluence) {
                val t = segment.start + o.timeToApoapsis
                if (t <= segment.end) out += mark(MarkKind.AP, segment, t, aboutId, o.apoapsis - body.radius)
            }
            if (o.timeToPeriapsis.isFinite()) {
                val t = segment.start + o.timeToPeriapsis
                if (t <= segment.end) out += mark(MarkKind.PE, segment, t, aboutId, o.periapsis - body.radius)
            }
            when (segment.ending) {
                Trajectory.Ending.ESCAPE -> out += mark(MarkKind.LEAVE, segment, segment.end, aboutId, bodyId = segment.bodyId)
                Trajectory.Ending.ENCOUNTER -> out += mark(MarkKind.ENTER, segment, segment.end, aboutId, bodyId = segment.nextBodyId ?: segment.bodyId)
                // Down at the datum. With no burn the planner's landing, with drag and hills, is better.
                Trajectory.Ending.IMPACT -> if (burn != null || plan.impact == null) out += mark(MarkKind.LAND, segment, segment.end, aboutId)
                Trajectory.Ending.NONE -> Unit
            }
            if (!aired) airOf(o, body, segment.start)?.takeIf { it <= segment.end }?.let {
                aired = true
                out += mark(MarkKind.AIR, segment, it, aboutId, body.atmosphereHeight)
            }
        }
        if (burn == null && plan.bodyId == aboutId) plan.impact?.let { out += landingMark(it, plan.bodyId) }
        plan.approach?.let { a ->
            val segment = path.segmentAt(a.time) ?: return@let
            // Meeting the target world, its low point there says it already.
            if (segment.bodyId == plan.targetBody) return@let
            val ghost = when {
                plan.targetBody.isNotEmpty() -> system.positionOf(plan.targetBody, a.time).subInPlace(system.positionOf(segment.bodyId, a.time))
                segment.bodyId == plan.bodyId -> plan.targetOrbit?.stateAt(a.time)?.position
                else -> null
            }?.let { drawnAbout(segment, it, aboutId) }
            out += mark(MarkKind.NEAR, segment, a.time, aboutId, extra = a.distance).let { PathMark(it.kind, it.time, it.at, it.bodyId, extra = it.extra, ghost = ghost) }
        }
        nodes(path, plan, aboutId, out)
        return out
    }

    /**
     * The marks on [orbit] round [bodyId], with no plan: its high and low points, the air, and where
     * it comes down, at [hit] by the orbit or by [landing] (with drag) when there is one.
     */
    fun ofOrbit(orbit: Orbit, bodyId: String, now: Double, hit: Vec3?, hitTime: Double, landing: com.rm.apogee.core.orbit.Descent.Impact? = null): List<PathMark> {
        val body = system.body(bodyId)
        val out = ArrayList<PathMark>()
        if (hit != null) out += landing?.let { landingMark(it, bodyId) } ?: PathMark(MarkKind.LAND, hitTime, hit.copy(), bodyId)
        else if (orbit.isBound) {
            out += PathMark(MarkKind.AP, now + orbit.timeToApoapsis, orbit.propagate(orbit.timeToApoapsis).position, bodyId, orbit.apoapsis - body.radius)
            out += PathMark(MarkKind.PE, now + orbit.timeToPeriapsis, orbit.propagate(orbit.timeToPeriapsis).position, bodyId, orbit.periapsis - body.radius)
        }
        airOf(orbit, body, now)?.takeIf { hit == null || it <= hitTime }?.let {
            out += PathMark(MarkKind.AIR, it, orbit.propagate(it - now).position, bodyId, body.atmosphereHeight)
        }
        return out
    }

    /** Where [impact] comes down on [bodyId], turned to when it does. */
    private fun landingMark(impact: com.rm.apogee.core.orbit.Descent.Impact, bodyId: String): PathMark {
        val body = system.body(bodyId)
        val elevation = body.terrain?.elevation(impact.direction) ?: 0.0
        val ground = body.radius + if (body.ocean != null) maxOf(elevation, 0.0) else elevation
        val at = body.rotationAt(impact.time).rotate(Vec3().setTo(impact.direction).mulInPlace(ground), Vec3())
        return PathMark(MarkKind.LAND, impact.time, at, bodyId, extra = impact.speed)
    }

    /** When [orbit] (at [start]) next comes down into [body]'s air from above it, or null. */
    private fun airOf(orbit: Orbit, body: com.rm.apogee.core.orbit.CelestialBody, start: Double): Double? {
        if (body.atmosphereHeight <= 0.0) return null
        val top = body.radius + body.atmosphereHeight
        if (orbit.position.length <= top) return null
        return orbit.timeToRadiusDown(top).takeIf { it.isFinite() }?.let { start + it }
    }

    /**
     * Where the path crosses its target's plane, up and down: a world's around the one it goes
     * round, a craft's around the one this path starts at. On the first leg round that one only.
     */
    private fun nodes(path: Trajectory, plan: PathPlanner.Plan, aboutId: String, out: MutableList<PathMark>) {
        val (normal, around) = when {
            plan.targetBody.isNotEmpty() -> {
                val target = system.body(plan.targetBody)
                (target.orbit?.angularMomentum ?: return) to (target.parentId ?: return)
            }
            plan.targetOrbit != null -> plan.targetOrbit.angularMomentum to plan.bodyId
            else -> return
        }
        val segment = path.about(around) ?: return
        val (up, down) = segment.orbit.nodesWith(normal) ?: return
        val angle = segment.orbit.relativeInclination(normal)
        for ((kind, dt) in listOf(MarkKind.AN to up, MarkKind.DN to down)) {
            if (!dt.isFinite() || segment.start + dt > segment.end) continue
            out += mark(kind, segment, segment.start + dt, aboutId, extra = angle)
        }
    }

    private fun mark(
        kind: MarkKind, segment: Trajectory.Segment, time: Double, aboutId: String,
        height: Double = Double.NaN, extra: Double = Double.NaN, bodyId: String = segment.bodyId,
    ) = PathMark(kind, time, drawnAbout(segment, segment.stateAt(time).position, aboutId), bodyId, height, extra)

    /** [point] on [segment] (round its body), round [aboutId] instead: a moon's leg where the moon is when it starts. */
    private fun drawnAbout(segment: Trajectory.Segment, point: Vec3, aboutId: String): Vec3 {
        val out = point.copy()
        if (segment.bodyId == aboutId) return out
        return out.addInPlace(system.positionOf(segment.bodyId, segment.start)).subInPlace(system.positionOf(aboutId, segment.start))
    }
}
