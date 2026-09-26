package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Vec3
import kotlin.math.PI

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

    /** The bodies orbiting [id] directly: its moons, or a star's planets. */
    fun childrenOf(id: String): List<CelestialBody> = children.getOrPut(id) { bodies.values.filter { it.parentId == id } }

    private val children = java.util.concurrent.ConcurrentHashMap<String, List<CelestialBody>>()

    /**
     * Carries [position] and [velocity] - relative to body [from]'s centre -
     * over to being relative to body [to]'s at [time], in place. The same
     * point and motion, measured from somewhere else: nothing moves.
     */
    fun rebase(position: Vec3, velocity: Vec3, from: String, to: String, time: Double) {
        if (from == to) return
        position.addInPlace(positionOf(from, time)).subInPlace(positionOf(to, time))
        velocity.addInPlace(velocityOf(from, time)).subInPlace(velocityOf(to, time))
    }

    /**
     * The body whose pull governs a craft at [position] (relative to
     * [current]'s centre) at [time]: [current] itself, most of the time.
     * Cheap when nothing can have changed - well inside [current]'s sphere
     * of influence and nowhere near a moon's - and [dominantBody] otherwise.
     */
    fun governing(current: CelestialBody, position: Vec3, time: Double): CelestialBody {
        val r = position.length
        var near = r > current.sphereOfInfluence
        if (!near) {
            for (child in childrenOf(current.id)) {
                val orbit = child.orbit ?: continue
                // Nearer the parent than the moon's orbit ever comes, less its reach.
                if (r >= orbit.periapsis - child.sphereOfInfluence) { near = true; break }
            }
        }
        if (!near) return current
        return dominantBody(positionOf(current.id, time).addInPlace(position), time)
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
         * The terrains, made once and shared by every world in the process.
         *
         * A solo game runs a server world, the client's prediction replica
         * and the renderer's mesh builder side by side. Built per world, each
         * would keep its own cache of sampled tiles of the same ground - three
         * copies of identical data, each paid for in full. The terrain is
         * immutable and its cache thread-safe, so one will do.
         */
        private val terraTerrain by lazy {
            com.rm.apogee.core.terrain.TerrainField(
                bodyRadius = 600_000.0,
                maxElevation = 6_000.0,
                oceanDepth = 3_000.0,
                // The continent is raised about latitude 0, longitude 0 -
                // where the launch complex first stood - so its surface
                // normal, +X, keeps every coastline where it was.
                homeDirection = Vec3(1.0, 0.0, 0.0),
                padDirection = surfaceDirection(PAD_LATITUDE, PAD_LONGITUDE),
                harbourDirection = surfaceDirection(HARBOUR_LATITUDE, HARBOUR_LONGITUDE),
            )
        }

        private val lunaTerrain by lazy {
            com.rm.apogee.core.terrain.TerrainField(
                bodyRadius = 200_000.0,
                seed = 0x11115,
                maxElevation = 4_000.0,
                oceanDepth = 0.0,
                profile = com.rm.apogee.core.terrain.TerrainField.Profile.LUNA,
            )
        }

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
                terrain = terraTerrain,
                ocean = com.rm.apogee.core.terrain.Ocean(),
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
                terrain = lunaTerrain,
                parentId = "terra",
                // Tilted to the Cape's latitude, rising through Terra's
                // equator at +X: once a Terra day the Cape is carried to the
                // top of Luna's plane, and a rocket launched due east then
                // flies straight into it - a launch window, as real ones are.
                orbit = Orbit.circular(lunaOrbitRadius, terra.gravitationalParameter, inclination = LUNA_INCLINATION),
                sphereOfInfluence = 2_429_559.0,
            )

            return SolarSystem(listOf(sol, terra, luna), rootId = "sol")
        }

        /** The body new craft launch from. */
        const val HOMEWORLD_ID = "terra"

        /**
         * The launch complex, radians: on the north-west coast of the home
         * continent, a kilometre and a half in from the sea, its runway
         * running east to end just short of the harbour's bay, so a plane
         * climbs out over the water.
         */
        const val PAD_LATITUDE = 0.09723497956796738
        const val PAD_LONGITUDE = 0.09754727774390243

        /**
         * The harbour, radians: in a broad bay five kilometres east of the
         * pad - east as the planet turns, so the lower longitude - calm
         * inside, reached from the sea by a winding inlet.
         */
        const val HARBOUR_LATITUDE = 0.097227372495131
        const val HARBOUR_LONGITUDE = 0.0888403538376949

        /**
         * The ground stations craft talk to: the Cape's, and two round
         * Terra's equator a third of the way either side of it, so a craft
         * in orbit is seldom out of hearing for long.
         */
        val groundStations: List<GroundStation> = listOf(
            GroundStation("Cape Station", HOMEWORLD_ID, PAD_LATITUDE, PAD_LONGITUDE),
            GroundStation("Eastern Station", HOMEWORLD_ID, 0.0, PAD_LONGITUDE - 2 * PI / 3),
            GroundStation("Western Station", HOMEWORLD_ID, 0.0, PAD_LONGITUDE + 2 * PI / 3),
        )

        /** Luna's orbit's tilt to Terra's equator, radians: the Cape's latitude. See [PAD_LATITUDE]. */
        const val LUNA_INCLINATION = PAD_LATITUDE

        /**
         * The place [east] and [north] metres from the pad, body-fixed and
         * unit: how everything at the Cape is laid out. See `TerrainField`'s
         * works and `StockStructures`.
         */
        fun capeDirection(east: Double, north: Double, radius: Double = 600_000.0): Vec3 {
            val pad = surfaceDirection(PAD_LATITUDE, PAD_LONGITUDE)
            val eastward = Vec3(0.0, 1.0, 0.0).crossInPlace(pad).normalizeInPlace()
            val northward = pad.copy().crossInPlace(eastward).normalizeInPlace()
            return pad.mulInPlace(radius).addScaledInPlace(eastward, east).addScaledInPlace(northward, north).normalizeInPlace()
        }

        /** Latitude of body-fixed unit [direction], radians. */
        fun latitudeOf(direction: Vec3): Double = kotlin.math.asin(direction.y.coerceIn(-1.0, 1.0))

        /** Longitude of body-fixed unit [direction], radians. */
        fun longitudeOf(direction: Vec3): Double = kotlin.math.atan2(direction.z, direction.x)

        /** The surface normal at a latitude and longitude, radians. */
        fun surfaceDirection(latitude: Double, longitude: Double): Vec3 = Vec3(
            kotlin.math.cos(latitude) * kotlin.math.cos(longitude),
            kotlin.math.sin(latitude),
            kotlin.math.cos(latitude) * kotlin.math.sin(longitude),
        )
    }
}

/**
 * A dish on the ground that craft talk home to: body-fixed on [bodyId] at
 * [latitude] and [longitude], radians, hearing an antenna within the
 * shorter of the two ranges, [range] or the antenna's own.
 */
data class GroundStation(
    val name: String,
    val bodyId: String,
    val latitude: Double,
    val longitude: Double,
    val range: Double = STATION_RANGE,
) {
    companion object {
        /** A ground station's reach, m: across all of Terra's pull. */
        const val STATION_RANGE = 200_000_000.0
    }
}
