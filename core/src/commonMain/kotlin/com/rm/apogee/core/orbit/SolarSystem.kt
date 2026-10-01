package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import com.rm.apogee.core.concurrentMapOf
import com.rm.apogee.core.math.Math

/** The tree of celestial bodies. Bodies aren't integrated: their positions are a function of time. */
class SolarSystem(
    bodies: List<CelestialBody>,
    val rootId: String,
) {
    val bodies: Map<String, CelestialBody> = bodies.associateBy { it.id }

    /**
     * A hash of every world's size, pull, spin, tilt, orbit, air, weather, rings and ground. Builds
     * that differ can't share a game.
     */
    val contentHash: String by lazy {
        val text = StringBuilder()
        fun d(x: Double) { text.append(x.toRawBits().toULong().toString(16)).append(',') }
        fun v(x: Vec3) { d(x.x); d(x.y); d(x.z) }
        text.append(rootId).append(';')
        for (b in this.bodies.values.sortedBy { it.id }) {
            text.append(b.id).append(':').append(b.parentId).append(':')
            d(b.gravitationalParameter); d(b.radius); d(b.rotationPeriod); v(b.spinAxis); d(b.sphereOfInfluence)
            b.orbit?.let { v(it.position); v(it.velocity); d(it.mu); d(it.epoch) }
            // Doubles by their bits, since platforms can print them differently.
            b.atmosphere?.let {
                d(it.seaLevelDensity); d(it.seaLevelPressure); d(it.scaleHeight); d(it.height)
                d(it.surfaceTemperature); d(it.lapseRate); d(it.tropopause); text.append(it.deep)
            }
            b.rings?.let { d(it.inner); d(it.outer) }
            b.ocean?.let { d(it.density) }
            b.terrain?.let { text.append(it.world).append('/').append(it.generation).append('/').append(it.hasOcean); d(it.maxElevation) }
            com.rm.apogee.core.weather.Climate.of(b.id)?.let { text.append(it.fingerprint()) }
            text.append(';')
        }
        com.rm.apogee.core.hex(com.rm.apogee.core.Sha256.digest(text.toString().encodeToByteArray())).take(16)
    }

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

    /** The absolute position of [id] at [time], relative to the root of the system. */
    fun positionOf(id: String, time: Double): Vec3 {
        val body = body(id)
        val parentId = body.parentId ?: return Vec3.zero()
        lastPosition[id]?.let { if (it.time == time) return Vec3(it.x, it.y, it.z) }
        val local = body.orbit!!.stateAt(time).position
        return positionOf(parentId, time).addInPlace(local).also { lastPosition[id] = Fix(time, it.x, it.y, it.z) }
    }

    /** The absolute velocity of [id] at [time], relative to the root of the system. */
    fun velocityOf(id: String, time: Double): Vec3 {
        val body = body(id)
        val parentId = body.parentId ?: return Vec3.zero()
        lastVelocity[id]?.let { if (it.time == time) return Vec3(it.x, it.y, it.z) }
        val local = body.orbit!!.stateAt(time).velocity
        return velocityOf(parentId, time).addInPlace(local).also { lastVelocity[id] = Fix(time, it.x, it.y, it.z) }
    }

    /**
     * Each body's last position and velocity, and when. A tick asks for the same bodies at the same
     * time over and over, and each is a Kepler solve up to the star.
     */
    private class Fix(val time: Double, val x: Double, val y: Double, val z: Double)

    private val lastPosition = concurrentMapOf<String, Fix>()
    private val lastVelocity = concurrentMapOf<String, Fix>()

    /**
     * The unit direction to the star from [position] (relative to [bodyId]'s centre) at [time],
     * inertial, written into [out].
     */
    fun sunDirection(bodyId: String, position: Vec3, time: Double, out: Vec3 = Vec3()): Vec3 {
        // A system with no star at its root, like a test's lone planet, has fixed light.
        if (rootId != STAR_ID) return out.setTo(FIXED_SUN)
        if (bodyId == rootId) return out.setTo(position).mulInPlace(-1.0).normalizeInPlace()
        return out.setTo(positionOf(bodyId, time)).addInPlace(position).mulInPlace(-1.0).normalizeInPlace()
    }

    /** Sunlight at [position] (relative to [bodyId]) as a share of Terra's, by inverse square. */
    fun sunStrength(bodyId: String, position: Vec3, time: Double): Double {
        if (rootId != STAR_ID) return 1.0
        val d = if (bodyId == rootId) position.length else positionOf(bodyId, time).addInPlace(position).length
        if (d <= 0.0) return 1.0
        val r = SystemData.AU / d
        return r * r
    }

    /** The bodies orbiting [id] directly: its moons, or a star's planets. */
    fun childrenOf(id: String): List<CelestialBody> = children.getOrPut(id) { bodies.values.filter { it.parentId == id } }

    private val children = concurrentMapOf<String, List<CelestialBody>>()

    /** Makes [position] and [velocity] relative to [to]'s centre instead of [from]'s, in place. */
    fun rebase(position: Vec3, velocity: Vec3, from: String, to: String, time: Double) {
        if (from == to) return
        position.addInPlace(positionOf(from, time)).subInPlace(positionOf(to, time))
        velocity.addInPlace(velocityOf(from, time)).subInPlace(velocityOf(to, time))
    }

    /**
     * The body governing a craft at [position] (relative to [current]'s centre) at [time]. Cheap when
     * it's well inside [current]'s SOI and clear of its moons; otherwise uses [dominantBody].
     */
    fun governing(current: CelestialBody, position: Vec3, time: Double): CelestialBody {
        val r = position.length
        var near = r > current.sphereOfInfluence
        if (!near) {
            for (child in childrenOf(current.id)) {
                val orbit = child.orbit ?: continue
                // Inside the moon's periapsis less its SOI is safe.
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
     * The deepest body whose sphere of influence holds [absolutePosition] at [time], so a moon beats
     * its planet. The patched-conic choice.
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
        /** The [defaultSystem]'s [contentHash], which this build offers a server. */
        val DEFAULT_HASH: String by lazy { defaultSystem().contentHash }

        /**
         * Terrains made once and shared by every world in the process (server, client replica,
         * renderer), so there's one tile cache. It never changes and its cache is thread-safe.
         */
        private val terraTerrain by lazy {
            com.rm.apogee.core.terrain.TerrainField(
                bodyRadius = 600_000.0,
                maxElevation = 6_000.0,
                oceanDepth = 3_000.0,
                // The continent is centred on lat 0, lon 0 (+X). Moving it would move every coast.
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
         * The system: a star, Terra, Luna and the other worlds. Scaled down about ten times, so orbit
         * takes ~3400 m/s and a couple of minutes.
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
                // In the ecliptic, phased so the sun at time zero is near where the old fixed one was.
                orbit = SystemData.terraOrbit(sol.gravitationalParameter),
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
                // No oceans, so the whole range is relief.
                terrain = lunaTerrain,
                parentId = "terra",
                // Tilted to the Cape's latitude, ascending node at +X, so once a Terra day a rocket
                // launched due east flies straight into Luna's plane.
                orbit = Orbit.circular(lunaOrbitRadius, terra.gravitationalParameter, inclination = LUNA_INCLINATION),
                sphereOfInfluence = 2_429_559.0,
            )

            return SolarSystem(listOf(sol, terra, luna) + otherWorlds(sol), rootId = "sol")
        }

        /** Every world except Terra and Luna, at [SystemData]'s scale. */
        private fun otherWorlds(sol: CelestialBody): List<CelestialBody> {
            val solMu = sol.gravitationalParameter
            val north = SystemData.ECLIPTIC_NORTH
            val out = ArrayList<CelestialBody>()
            fun planet(
                id: String, name: String, radiusKm: Double, g: Double, dayHours: Double, tilt: Double, azimuth: Double,
                orbit: Orbit, atmosphere: Atmosphere? = null, rings: Rings? = null,
            ): CelestialBody {
                val r = radiusKm * 1_000.0
                val mu = SystemData.mu(g, r)
                val body = CelestialBody(
                    id = id, displayName = name, gravitationalParameter = mu, radius = r,
                    // A quarter of the real day, like Terra's.
                    rotationPeriod = dayHours * 3_600.0 / 4.0,
                    atmosphere = atmosphere, terrain = worldTerrain(id, r), ocean = worldOcean(id),
                    parentId = "sol", orbit = orbit,
                    sphereOfInfluence = SystemData.sphereOfInfluence(orbit.semiMajorAxis, mu, solMu),
                    spinAxis = SystemData.axis(north, Math.toRadians(tilt), Math.toRadians(azimuth)),
                    rings = rings?.let { Rings(it.inner * r, it.outer * r) },
                )
                out.add(body)
                return body
            }
            fun moon(
                id: String, name: String, parent: CelestialBody, radiusKm: Double, g: Double, radii: Double, iDegrees: Double,
                anomaly: Double, atmosphere: Atmosphere? = null, e: Double = 0.0,
            ): CelestialBody {
                val r = radiusKm * 1_000.0
                val mu = SystemData.mu(g, r)
                val orbit = SystemData.moonOrbit(parent, radii, iDegrees, anomaly, e)
                // Tidally locked: its day is its month, around its orbit's pole.
                val pole = orbit.angularMomentum.normalized()
                val body = CelestialBody(
                    id = id, displayName = name, gravitationalParameter = mu, radius = r,
                    rotationPeriod = SystemData.period(orbit),
                    atmosphere = atmosphere, terrain = worldTerrain(id, r), ocean = worldOcean(id),
                    parentId = parent.id, orbit = orbit,
                    sphereOfInfluence = SystemData.sphereOfInfluence(orbit.semiMajorAxis, mu, parent.gravitationalParameter),
                    spinAxis = pole,
                )
                out.add(body)
                return body
            }
            fun air(density: Double, pressure: Double, scaleKm: Double, surfaceK: Double, lapse: Double, tropopause: Double, deep: Boolean = false) =
                Atmosphere(
                    seaLevelDensity = density, seaLevelPressure = pressure, scaleHeight = scaleKm * 1_000.0,
                    // Where it thins to Terra's edge density.
                    height = scaleKm * 1_000.0 * kotlin.math.ln(density / AIR_EDGE_DENSITY),
                    surfaceTemperature = surfaceK, lapseRate = lapse, tropopause = tropopause, deep = deep,
                )

            // The inner worlds.
            planet("celer", "Celer", 230.0, 3.70, 1_407.5, 0.03, 0.0,
                SystemData.planetOrbit(0.387, 0.2056, 7.00, 48.3, 77.5, 170.0, solMu))
            planet("caligo", "Caligo", 570.0, 8.87, 5_832.5, 177.4, 40.0,
                SystemData.planetOrbit(0.723, 0.0068, 3.39, 76.7, 131.6, 60.0, solMu),
                atmosphere = air(65.0, 9.2e6, 10.5, 735.0, 0.0078, 170.0))
            val rubra = planet("rubra", "Rubra", 319.0, 3.72, 24.62, 25.2, 110.0,
                SystemData.planetOrbit(1.524, 0.0934, 1.85, 49.6, 336.0, 250.0, solMu),
                atmosphere = air(0.020, 600.0, 7.3, 210.0, 0.0025, 150.0))
            // Heavier and further out than real, or their SOI would be too small to orbit in.
            moon("timor", "Timor", rubra, 3.0, 0.15, 4.0, 1.1, 30.0)
            moon("pavor", "Pavor", rubra, 2.0, 0.10, 6.9, 1.8, 200.0)

            // The giants and their moons.
            val magna = planet("magna", "Magna", 6_585.0, 24.79, 9.925, 3.1, 200.0,
                SystemData.planetOrbit(5.203, 0.0484, 1.30, 100.5, 14.8, 20.0, solMu),
                atmosphere = air(0.16, 1e5, 18.0, 165.0, 0.002, 110.0, deep = true), rings = Rings(1.4, 1.8))
            moon("fornax", "Fornax", magna, 172.0, 1.796, 3.0, 0.05, 10.0)
            moon("crusta", "Crusta", magna, 147.0, 1.315, 4.7, 0.47, 100.0)
            moon("maxima", "Maxima", magna, 248.0, 1.428, 7.5, 0.20, 190.0)
            moon("cicatrix", "Cicatrix", magna, 227.0, 1.235, 13.2, 0.28, 280.0)
            val aurea = planet("aurea", "Aurea", 5_485.0, 10.44, 10.656, 26.7, 300.0,
                SystemData.planetOrbit(9.537, 0.0539, 2.49, 113.7, 92.4, 300.0, solMu),
                atmosphere = air(0.19, 1e5, 39.0, 134.0, 0.001, 82.0, deep = true), rings = Rings(1.24, 2.27))
            moon("aurantia", "Aurantia", aurea, 243.0, 1.352, 10.2, 0.35, 60.0,
                atmosphere = air(5.3, 146_700.0, 14.0, 94.0, 0.001, 70.0))
            // Out at four radii; half its real distance leaves no room to orbit inside the rings.
            moon("fons", "Fons", aurea, 24.0, 0.113, 4.0, 0.02, 150.0)
            planet("obliqua", "Obliqua", 2_389.0, 8.69, 17.24, 97.8, 20.0,
                SystemData.planetOrbit(19.19, 0.0473, 0.77, 74.0, 170.9, 120.0, solMu),
                atmosphere = air(0.42, 1e5, 18.0, 76.0, 0.0009, 53.0, deep = true), rings = Rings(1.64, 2.0))
            val caerula = planet("caerula", "Caerula", 2_319.0, 11.15, 16.11, 28.3, 250.0,
                SystemData.planetOrbit(30.07, 0.0086, 1.77, 131.8, 44.9, 210.0, solMu),
                atmosphere = air(0.45, 1e5, 13.0, 72.0, 0.0012, 55.0, deep = true))
            // Goes backwards around its planet.
            moon("aversa", "Aversa", caerula, 127.0, 0.779, 7.2, 157.0, 330.0,
                atmosphere = air(1.2e-4, 1.4, 8.0, 38.0, 0.0, 38.0))

            // The last of them, and its companion.
            val ultima = planet("ultima", "Ultima", 112.0, 0.62, 153.3, 119.6, 70.0,
                SystemData.planetOrbit(39.48, 0.2488, 17.14, 110.3, 224.1, 330.0, solMu),
                atmosphere = air(8.4e-5, 1.0, 13.0, 44.0, 0.0, 40.0))
            moon("portitor", "Portitor", ultima, 57.0, 0.288, 8.3, 0.0, 90.0)
            return out
        }

        /** Density where an atmosphere ends, in kg/m³: Terra's at its edge. */
        private const val AIR_EDGE_DENSITY = 3e-6

        /** World [id]'s ground, or null for a gas giant. See [com.rm.apogee.core.terrain.Worlds]. */
        private fun worldTerrain(id: String, radius: Double): com.rm.apogee.core.terrain.Terrain? =
            com.rm.apogee.core.terrain.Worlds.terrain(id, radius)

        /** The sea of world [id], if it has one. */
        private fun worldOcean(id: String): com.rm.apogee.core.terrain.Ocean? = com.rm.apogee.core.terrain.Worlds.ocean(id)

        /** The star at the centre of it all. */
        const val STAR_ID = "sol"

        /** The light for a world with no star at its centre. */
        private val FIXED_SUN = Vec3(0.62, 0.45, 0.64).normalizeInPlace()

        /** The body new craft launch from. */
        const val HOMEWORLD_ID = "terra"

        /**
         * The launch complex, in radians: on the home continent's north-west coast, with the runway
         * running east to end short of the harbour's bay.
         */
        const val PAD_LATITUDE = 0.09723497956796738
        const val PAD_LONGITUDE = 0.09754727774390243

        /**
         * The harbour, in radians: a bay five kilometres east of the pad (east as the planet turns,
         * so lower longitude), reached by a winding inlet.
         */
        const val HARBOUR_LATITUDE = 0.097227372495131
        const val HARBOUR_LONGITUDE = 0.0888403538376949

        /** The Cape's station, and two on Terra's equator a third of the way round each side. */
        val groundStations: List<GroundStation> = listOf(
            GroundStation("Cape Station", HOMEWORLD_ID, PAD_LATITUDE, PAD_LONGITUDE),
            GroundStation("Eastern Station", HOMEWORLD_ID, 0.0, PAD_LONGITUDE - 2 * PI / 3),
            GroundStation("Western Station", HOMEWORLD_ID, 0.0, PAD_LONGITUDE + 2 * PI / 3),
        )

        /** Luna's orbit tilt to Terra's equator, in radians: the Cape's latitude ([PAD_LATITUDE]). */
        const val LUNA_INCLINATION = PAD_LATITUDE

        /**
         * The body-fixed unit direction [east] and [north] metres from the pad. The Cape is laid out
         * with this. See `TerrainField`'s works and `StockStructures`.
         */
        fun capeDirection(east: Double, north: Double, radius: Double = 600_000.0): Vec3 {
            val pad = surfaceDirection(PAD_LATITUDE, PAD_LONGITUDE)
            val eastward = Vec3(0.0, 1.0, 0.0).crossInPlace(pad).normalizeInPlace()
            val northward = pad.copy().crossInPlace(eastward).normalizeInPlace()
            return pad.mulInPlace(radius).addScaledInPlace(eastward, east).addScaledInPlace(northward, north).normalizeInPlace()
        }

        /** Latitude of body-fixed unit [direction], in radians. */
        fun latitudeOf(direction: Vec3): Double = kotlin.math.asin(direction.y.coerceIn(-1.0, 1.0))

        /** Longitude of body-fixed unit [direction], in radians. */
        fun longitudeOf(direction: Vec3): Double = kotlin.math.atan2(direction.z, direction.x)

        /** The surface normal at a latitude and longitude, in radians. */
        fun surfaceDirection(latitude: Double, longitude: Double): Vec3 = Vec3(
            kotlin.math.cos(latitude) * kotlin.math.cos(longitude),
            kotlin.math.sin(latitude),
            kotlin.math.cos(latitude) * kotlin.math.sin(longitude),
        )
    }
}

/**
 * A dish craft talk home to, body-fixed on [bodyId] at [latitude] and [longitude] in radians. It
 * hears an antenna within the shorter of [range] and the antenna's range.
 */
data class GroundStation(
    val name: String,
    val bodyId: String,
    val latitude: Double,
    val longitude: Double,
    val range: Double = STATION_RANGE,
) {
    companion object {
        /** A ground station's reach, in metres: out to Ultima, for a dish that can answer. */
        const val STATION_RANGE = 5e12
    }
}
