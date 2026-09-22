package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Vec3

/**
 * The tree of celestial bodies, and the queries the simulation asks of it.
 *
 * Bodies are never integrated - their positions are a function of time - so
 * this is effectively a closed-form description of the universe that craft move
 * through.
 */
class SolarSystem(
    bodies: List<CelestialBody>,
    val rootId: String,
) {
    val bodies: Map<String, CelestialBody> = bodies.associateBy { it.id }

    init {
        require(this.bodies.containsKey(rootId)) { "Root body '$rootId' is not in the system" }
        for (body in bodies) {
            body.parentId?.let { parent ->
                require(this.bodies.containsKey(parent)) {
                    "Body '${body.id}' names unknown parent '$parent'"
                }
                requireNotNull(body.orbit) {
                    "Body '${body.id}' has a parent but no orbit about it"
                }
            }
        }
    }

    fun body(id: String): CelestialBody =
        bodies[id] ?: throw IllegalArgumentException(
            "Unknown body '$id'. Known: ${bodies.keys.sorted()}"
        )

    /** Absolute position of [id] at [time], relative to the system root. */
    fun positionOf(id: String, time: Double): Vec3 {
        val body = body(id)
        val parentId = body.parentId ?: return Vec3.zero()
        val local = body.orbit!!.stateAt(time).position
        return positionOf(parentId, time).addInPlace(local)
    }

    /** Absolute velocity of [id] at [time], relative to the system root. */
    fun velocityOf(id: String, time: Double): Vec3 {
        val body = body(id)
        val parentId = body.parentId ?: return Vec3.zero()
        val local = body.orbit!!.stateAt(time).velocity
        return velocityOf(parentId, time).addInPlace(local)
    }

    /** Every body between [id] and the root, nearest first. */
    fun ancestorsOf(id: String): List<CelestialBody> {
        val chain = ArrayList<CelestialBody>()
        var current = body(id).parentId
        while (current != null) {
            val parent = body(current)
            chain.add(parent)
            current = parent.parentId
        }
        return chain
    }

    /**
     * Which body's sphere of influence [absolutePosition] falls in at [time].
     *
     * Descends from the root, preferring the deepest body whose SOI contains
     * the point - so a craft near a moon is governed by the moon, not the
     * planet it is orbiting. This is the patched-conic decision, made once per
     * tick per vessel.
     */
    fun dominantBody(absolutePosition: Vec3, time: Double): CelestialBody {
        var best = body(rootId)
        var bestDepth = 0

        for (candidate in bodies.values) {
            if (candidate.sphereOfInfluence.isInfinite()) continue
            val centre = positionOf(candidate.id, time)
            if (absolutePosition.distanceTo(centre) <= candidate.sphereOfInfluence) {
                val depth = ancestorsOf(candidate.id).size
                if (depth >= bestDepth) {
                    best = candidate
                    bestDepth = depth
                }
            }
        }
        return best
    }

    companion object {
        /**
         * The v1 system: a star, a homeworld and its moon.
         *
         * Scaled down roughly tenfold from reality, which is the convention the
         * genre settled on for good reason - a full-scale Earth needs ~9400 m/s
         * to orbit and half an hour of burn time, while this needs ~3400 m/s
         * and a couple of minutes. The physics is identical; only the numbers
         * are chosen to fit a session rather than a career.
         */
        fun defaultSystem(): SolarSystem {
            val sol = CelestialBody(
                id = "sol",
                displayName = "Sol",
                gravitationalParameter = 1.1723328e18,
                radius = 261_600_000.0,
                rotationPeriod = 432_000.0,
            )

            val terraOrbitRadius = 13_599_840_256.0
            val terraRadius = 600_000.0
            val terra = CelestialBody(
                id = "terra",
                displayName = "Terra",
                gravitationalParameter = 3.5316000e12,
                radius = terraRadius,
                rotationPeriod = 21_549.425,
                atmosphere = Atmosphere(
                    seaLevelDensity = 1.225,
                    seaLevelPressure = 101_325.0,
                    scaleHeight = 5_600.0,
                    height = 70_000.0,
                ),
                terrain = com.rm.apogee.core.terrain.TerrainField(
                    bodyRadius = terraRadius,
                    maxElevation = 6_000.0,
                    oceanDepth = 3_000.0,
                    // The launch complex is at latitude 0, longitude 0, so its
                    // surface normal is +X. Terrain is raised there to keep the
                    // pad out of the sea.
                    homeDirection = Vec3(1.0, 0.0, 0.0),
                ),
                parentId = "sol",
                orbit = Orbit.circular(terraOrbitRadius, sol.gravitationalParameter),
                sphereOfInfluence = 84_159_286.0,
            )

            val lunaOrbitRadius = 12_000_000.0
            val lunaRadius = 200_000.0
            val luna = CelestialBody(
                id = "luna",
                displayName = "Luna",
                gravitationalParameter = 6.5138398e10,
                radius = lunaRadius,
                rotationPeriod = 138_984.0,
                atmosphere = null,
                // Airless and battered: no oceans, so the whole surface is
                // relief rather than the top half of it.
                terrain = com.rm.apogee.core.terrain.TerrainField(
                    bodyRadius = lunaRadius,
                    seed = 0x11115,
                    maxElevation = 4_000.0,
                    oceanDepth = 0.0,
                ),
                parentId = "terra",
                orbit = Orbit.circular(lunaOrbitRadius, terra.gravitationalParameter),
                sphereOfInfluence = 2_429_559.0,
            )

            return SolarSystem(listOf(sol, terra, luna), rootId = "sol")
        }

        /** The body new craft launch from. */
        const val HOMEWORLD_ID = "terra"
    }
}
