package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Vec3

/**
 * Where a coasting craft goes from here: the conic around the body it's in, and then, if that
 * carries it into a moon's pull or out of its planet's, the conic around the next one, and so on.
 * This is the same patched-conic picture the world itself flies by (see `World.crossInfluence`), so
 * what the map shows is what will actually happen, apart from the craft's own engines.
 */
class Trajectory(val segments: List<Segment>) {

    /** How a segment ends. */
    enum class Ending {
        /** It doesn't, within the horizon. It's an orbit, round and round. */
        NONE,
        /** Into a moon's pull ([Segment.nextBodyId]). */
        ENCOUNTER,
        /** Out of this body's pull, into its parent's. */
        ESCAPE,
        /** Down onto the body, at its datum. */
        IMPACT,
    }

    /**
     * One conic: [orbit] around body [bodyId] (its epoch is the segment's [start]), flown from
     * [start] to [end] in universe time.
     */
    class Segment(
        val bodyId: String,
        val orbit: Orbit,
        val start: Double,
        val end: Double,
        val ending: Ending,
        val nextBodyId: String?,
    ) {
        /** [count] points along it, relative to its body's centre. */
        fun sample(count: Int): List<Vec3> {
            val points = ArrayList<Vec3>(count)
            val span = end - start
            for (k in 0 until count) points.add(orbit.propagate(span * k / (count - 1)).position)
            return points
        }

        /** Where it is at [time], relative to its body. */
        fun stateAt(time: Double): StateVector = orbit.propagate(time - start)
    }

    /** The segment being flown at [time], or null past the last one. */
    fun segmentAt(time: Double): Segment? = segments.firstOrNull { time >= it.start && time <= it.end }

    /** The first segment around [bodyId], if the trajectory reaches it. */
    fun about(bodyId: String): Segment? = segments.firstOrNull { it.bodyId == bodyId }

    /** Closest approach: when, how close (m), and how fast they pass (m/s). */
    class Approach(val time: Double, val distance: Double, val relativeSpeed: Double)

    companion object {
        const val MAX_SEGMENTS = 3

        /** The furthest it looks ahead, in seconds. That's about one of Luna's months. */
        const val HORIZON = 400_000.0

        /**
         * Once a path is out among the planets, around the star, it gets followed at least this
         * much further, in seconds. That's long enough to reach Ultima the slowest way there.
         */
        const val STAR_HORIZON = 5.0e8

        /**
         * The trajectory of a craft at [position] and [velocity] around body [bodyId] at [time], at
         * most [maxSegments] conics long and [horizon] seconds.
         */
        fun predict(
            system: SolarSystem,
            bodyId: String,
            position: Vec3,
            velocity: Vec3,
            time: Double,
            maxSegments: Int = MAX_SEGMENTS,
            horizon: Double = HORIZON,
        ): Trajectory {
            val segments = ArrayList<Segment>(maxSegments)
            var body = system.body(bodyId)
            val p = position.copy()
            val v = velocity.copy()
            var t = time
            var limit = time + horizon
            while (segments.size < maxSegments && t < limit) {
                // Out among the planets a crossing takes months, or years.
                if (body.parentId == null && system.childrenOf(body.id).isNotEmpty()) limit = maxOf(limit, t + STAR_HORIZON)
                val orbit = Orbit(p.copy(), v.copy(), body.gravitationalParameter, t)
                val segment = follow(system, body, orbit, t, limit)
                segments.add(segment)
                val next = segment.nextBodyId ?: break
                val at = orbit.propagate(segment.end - t)
                p.setTo(at.position)
                v.setTo(at.velocity)
                system.rebase(p, v, body.id, next, segment.end)
                body = system.body(next)
                t = segment.end
            }
            return Trajectory(segments)
        }

        /** The conic [orbit] around [body] from [t0], up to its first ending or [limit]. */
        private fun follow(system: SolarSystem, body: CelestialBody, orbit: Orbit, t0: Double, limit: Double): Segment {
            val children = system.childrenOf(body.id).filter { it.orbit != null }
            val parent = body.parentId
            val end = if (orbit.isBound && orbit.apoapsis < body.sphereOfInfluence) minOf(limit, t0 + orbit.period) else limit
            val impacts = orbit.periapsis < body.radius
            val escapes = !orbit.isBound || orbit.apoapsis >= body.sphereOfInfluence
            // Steps short enough that neither the ground nor a moon's reach can slip between two
            // looks. That's a fiftieth of the craft's own distance over speed, and shorter near a
            // moon (see below).
            var t = t0
            var before = t0
            val scratch = Vec3()
            fun event(time: Double): Pair<Ending, String?>? {
                val at = orbit.propagate(time - t0)
                val r = at.position.length
                if (impacts && r <= body.radius) return Ending.IMPACT to null
                if (escapes && parent != null && r >= body.sphereOfInfluence) return Ending.ESCAPE to parent
                for (child in children) {
                    val c = child.orbit!!.stateAt(time).position
                    if (scratch.setTo(at.position).subInPlace(c).length <= child.sphereOfInfluence) return Ending.ENCOUNTER to child.id
                }
                return null
            }
            while (t < end) {
                val state = orbit.propagate(t - t0)
                val r = state.position.length
                val speed = state.velocity.length.coerceAtLeast(1.0)
                var step = 0.02 * r / speed
                // Short enough that no moon's reach slips between two looks, judged by how far away
                // each one is now. That means long strides far out between the planets, where
                // months have to be crossed.
                for (child in children) {
                    val gap = scratch.setTo(state.position).subInPlace(child.orbit!!.stateAt(t).position).length - child.sphereOfInfluence
                    step = minOf(step, maxOf(0.25 * child.sphereOfInfluence, 0.5 * gap) / (speed + child.orbit.velocity.length))
                }
                if (impacts) step = minOf(step, maxOf(1.0, 0.25 * (r - body.radius) / speed))
                step = step.coerceIn(0.5, (end - t0) / 64.0 + 0.5)
                before = t
                t = minOf(end, t + step)
                val found = event(t) ?: continue
                // Halved down to a hundredth of a second.
                var lo = before
                var hi = t
                while (hi - lo > 0.01) {
                    val mid = 0.5 * (lo + hi)
                    if (event(mid) != null) hi = mid else lo = mid
                }
                val (ending, next) = event(hi) ?: found
                return Segment(body.id, orbit, t0, hi, ending, next)
            }
            return Segment(body.id, orbit, t0, end, Ending.NONE, null)
        }

        /**
         * The closest approach along [segment] to something whose position relative to the
         * segment's body is [target] at a given time. It searches [steps] even steps, then closes
         * in around the nearest.
         */
        fun closestApproach(segment: Segment, steps: Int = 256, target: (Double, Vec3) -> Vec3): Approach {
            val span = segment.end - segment.start
            val there = Vec3()
            fun distance(t: Double): Double =
                segment.stateAt(t).position.distanceTo(target(t, there))
            var best = segment.start
            var bestDistance = Double.MAX_VALUE
            for (k in 0..steps) {
                val t = segment.start + span * k / steps
                val d = distance(t)
                if (d < bestDistance) { bestDistance = d; best = t }
            }
            var lo = maxOf(segment.start, best - span / steps)
            var hi = minOf(segment.end, best + span / steps)
            repeat(60) {
                val a = lo + (hi - lo) * 0.382
                val b = lo + (hi - lo) * 0.618
                if (distance(a) < distance(b)) hi = b else lo = a
            }
            val t = 0.5 * (lo + hi)
            val here = segment.stateAt(t)
            val d = here.position.distanceTo(target(t, there))
            // Its speed past it, from how much the target's position changes over a second.
            val later = target(t + 0.5, Vec3())
            val earlier = target(t - 0.5, Vec3())
            val targetVelocity = later.subInPlace(earlier)
            return Approach(t, d, here.velocity.copy().subInPlace(targetVelocity).length)
        }
    }
}
