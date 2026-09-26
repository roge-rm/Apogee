package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.Noise
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The air over one body: a pure function of the [config], the body's
 * terrain, the time and the place.
 *
 * Nothing here is stepped or stored beyond caches of pure results, so the
 * server, each client's prediction replica and the renderer all compute the
 * same wind for the same place and moment - every craft in a close
 * formation is pushed by the same gust, and a saved world resumes under the
 * same sky. Each user holds its own instance: the caches are not shared
 * between threads.
 *
 * Layers, from the top down:
 * - global circulation: trade winds, westerlies and polar easterlies by
 *   latitude, bent round drifting highs and lows, a jet stream near 10 km,
 *   and nothing above 30 km;
 * - the boundary layer, where the ground slows the wind by how rough it is,
 *   speeds it over crests, starves it in the lee and in hollows, lifts it up
 *   windward slopes and turns it down valleys ([TerrainWind]);
 * - thermals and their cumulus ([Convection]);
 * - storms: towers, gust fronts, rain and lightning ([Storms]);
 * - layer cloud: stratus, altostratus, cirrus;
 * - turbulence, sampled part by part ([turbulence]).
 */
class Weather(
    val body: CelestialBody,
    val config: WeatherConfig,
    /** What kind of weather this world has: Terra's unless it has its own. */
    val climate: Climate = Climate.of(body.id) ?: Climate.TERRA,
) {

    private val radius = body.radius
    private val seed = config.seed * 31 + 0x5EA7
    private val intensity = config.intensity
    private val terrain = body.terrain
    private val terrainWind = terrain?.let { TerrainWind(it, radius) }
    private val convection = Convection(this, terrainWind, radius, seed, intensity)
    private val storms = Storms(this, radius, seed, intensity)
    private val stormShapes = StormShapes(storms, seed, radius, climate)

    private val up = Vec3()
    private val east = Vec3()
    private val north = Vec3()
    private val free = Vec3()
    private val dir = Vec3()
    private val jetScratch = Vec3()
    private val probe = Vec3()
    private val rotated = Vec3()
    private val descriptor = DoubleArray(TerrainWind.SIZE)
    private val gradient = Vec3()
    private val axis = Vec3()

    // --- the whole sample ---------------------------------------------------------

    /**
     * The air at [position] (body-fixed, metres from the centre) at [time],
     * into [out].
     */
    fun sample(position: Vec3, time: Double, out: AirSample): AirSample {
        out.clear()
        val r = position.length
        if (r < 1.0) return out
        up.setTo(position).mulInPlace(1.0 / r)
        val altitude = r - radius
        if (altitude > climate.ceiling) return out
        frame(up, east, north)
        val still = climate.circulation == Climate.Circulation.STILL

        // The ground: what it is, and how high the surface of it stands.
        val described = terrainWind?.let { it.describe(up, descriptor); true } ?: false
        val ocean = described && descriptor[TerrainWind.OCEAN] > 0.5
        val ground = terrain?.elevation(up) ?: 0.0
        val groundTop = if (terrain?.hasOcean == true) max(ground, 0.0) else ground
        // A giant has no ground: the whole depth of its air is free air.
        val agl = if (climate.ground) max(altitude - groundTop, 0.5) else BOUNDARY_LAYER + 9_000.0
        val z0 = if (described) descriptor[TerrainWind.Z0] else 0.05
        val relief = if (described) descriptor[TerrainWind.RELIEF] else 0.0

        // The free wind above the boundary layer, from the circulation.
        val pressure = circulation(up, east, north, time, free, surfaceInflow = 1.0 - smooth(0.0, 1_500.0, agl))
        val freeSpeed = free.length
        if (freeSpeed > 1e-6) dir.setTo(free).mulInPlace(1.0 / freeSpeed) else dir.setTo(east)

        // How far up the ground's influence reaches: more over rough ground.
        val terrainFade = 1.0 - smooth(0.0, 500.0 + 2.0 * relief, agl)
        var speedFactor = 1.0
        var vertical = 0.0
        var lee = 0.0
        if (described && terrainFade > 0.0) {
            val exposure = descriptor[TerrainWind.H0] - descriptor[TerrainWind.MEAN]
            // Crests speed the wind up, hollows starve it.
            speedFactor = 1.0 + (exposure / 350.0).coerceIn(-0.6, 0.7)
            gradient.setTo(descriptor[TerrainWind.GRAD], descriptor[TerrainWind.GRAD + 1], descriptor[TerrainWind.GRAD + 2])
            val alongSlope = gradient dot dir
            // Air meeting rising ground goes up it; ground falling away
            // downwind is a lee slope, sheltered, sinking and rough.
            lee = (-alongSlope * 4.0).coerceIn(0.0, 1.0)
            speedFactor *= 1.0 - 0.55 * lee
            // Valleys: turn the wind along them, and starve what crosses them.
            val channel = descriptor[TerrainWind.CHANNEL]
            if (channel > 0.0) {
                axis.setTo(descriptor[TerrainWind.AXIS], descriptor[TerrainWind.AXIS + 1], descriptor[TerrainWind.AXIS + 2])
                if ((axis dot dir) < 0.0) axis.mulInPlace(-1.0)
                val along = axis dot dir
                speedFactor *= (1.0 - 0.7 * channel * (1.0 - along)) * (1.0 + 0.35 * channel * along)
                val c = channel * terrainFade
                dir.mulInPlace(1.0 - c).addScaledInPlace(axis, c).normalizeInPlace()
            }
            speedFactor = 1.0 + (speedFactor - 1.0) * terrainFade
            vertical = alongSlope * 0.9 * terrainFade - 0.25 * lee * terrainFade
        }

        // Height: a log profile through the boundary layer, stronger aloft.
        val profile = if (agl < BOUNDARY_LAYER) {
            ln((agl + z0) / z0) / ln((BOUNDARY_LAYER + z0) / z0)
        } else {
            min(1.0 + (agl - BOUNDARY_LAYER) / 9_000.0, 2.2)
        }
        val fade = 1.0 - smooth(climate.fadeFrom, climate.ceiling, altitude)
        val speed = freeSpeed * profile * speedFactor * fade * intensity.wind
        out.wind.setTo(dir).mulInPlace(speed)
        out.wind.addScaledInPlace(up, vertical * speed)
        out.lift = vertical * speed

        // The jet stream: westerly, mid-latitudes, near ten kilometres.
        val latitude = asin(up.y.coerceIn(-1.0, 1.0))
        val jet = climate.jet * exp(-((altitude - climate.jetHeight) / 3_200.0).let { it * it }) *
            exp(-((abs(latitude) - 0.7) / 0.25).let { it * it }) * fade * intensity.wind
        out.wind.addScaledInPlace(east, jet)
        // The upper air racing round the world, faster the higher.
        if (climate.superRotation != 0.0) {
            out.wind.addScaledInPlace(east, climate.superRotation * smooth(0.0, climate.superRotationHeight, altitude) * fade * intensity.wind)
        }

        // Rough air: the ground, the lee, shear round the jet.
        val mechanical = (speed / 15.0).coerceIn(0.0, 1.0) * (0.2 + 0.5 * (z0 / 1.0).coerceIn(0.0, 1.0)) *
            (1.0 - smooth(0.0, 400.0 + relief, agl))
        out.turbulence += mechanical + 0.7 * lee * terrainFade + (if (climate.jet > 0.0) 0.15 * (jet / climate.jet) else 0.0)
        if (still) out.turbulence = 0.0

        convection.apply(up, east, north, position, altitude, time, out)
        storms.apply(up, east, north, position, altitude, groundTop, time, out)
        if (climate.layers) layers(up, altitude, groundTop, time, out)
        if (climate.hasDeck) deckAt(altitude, out)

        // Inside cloud there is more turbulence, by kind.
        out.turbulence += out.cloudDensity * when (out.cloudType) {
            CloudType.CUMULONIMBUS -> 0.8
            CloudType.CUMULUS -> 0.35
            CloudType.STRATUS, CloudType.ALTOSTRATUS -> 0.1
            CloudType.DUST -> 0.5
            else -> 0.02
        }
        out.turbulence = out.turbulence.coerceIn(0.0, 1.0)

        // How far can be seen: rain, then cloud.
        var visibility = climate.haze * (1.0 - 0.97 * out.precipitation)
        out.cloudType?.let { type ->
            if (out.cloudDensity > 0.0) visibility = min(visibility, type.visibility / max(out.cloudDensity, 0.02))
        }
        out.visibility = visibility
        return out
    }

    /** The wind ten metres above the ground or sea at [direction]: what drives waves. */
    fun surfaceWind(direction: Vec3, time: Double, out: Vec3): Vec3 {
        val ground = terrain?.elevation(direction) ?: 0.0
        val top = if (terrain?.hasOcean == true) max(ground, 0.0) else ground
        probe.setTo(direction).normalizeInPlace().mulInPlace(radius + top + 10.0)
        val sample = scratchSample
        sample(probe, time, sample)
        return out.setTo(sample.wind)
    }

    private val scratchSample = AirSample()

    /**
     * A gust: the turbulent part of the wind at [position], for a craft whose
     * mean wind is [meanWind] and whose air is [turbulence] rough.
     *
     * Frozen eddies carried along by the mean wind, in three sizes - the
     * biggest the strongest - with a slow drift of their own. Sampled per
     * part, so a gust rolls a wing and yaws a tail rather than moving the
     * whole craft as one.
     */
    fun turbulence(position: Vec3, time: Double, meanWind: Vec3, turbulence: Double, out: Vec3): Vec3 {
        out.setZero()
        if (turbulence <= 0.0) return out
        val sigma = turbulence * (1.5 + 0.35 * meanWind.length) * intensity.gusts
        for (octave in 0 until 3) {
            val scale = TURBULENCE_SCALES[octave]
            val weight = TURBULENCE_WEIGHTS[octave]
            val x = (position.x - meanWind.x * time) / scale + time * 0.05
            val y = (position.y - meanWind.y * time) / scale
            val z = (position.z - meanWind.z * time) / scale - time * 0.03
            out.x += weight * Noise.simplex(seed + 101 + octave, x, y, z)
            out.y += weight * Noise.simplex(seed + 111 + octave, x, y, z)
            out.z += weight * Noise.simplex(seed + 121 + octave, x, y, z)
        }
        return out.mulInPlace(sigma)
    }

    // --- circulation --------------------------------------------------------------

    /**
     * The pressure pattern at unit [direction], about -1 (a deep low) to 1
     * (a strong high). Drifts eastward as a whole and slowly reshapes itself.
     */
    fun pressure(direction: Vec3, time: Double): Double {
        val angle = -PATTERN_DRIFT / radius * time
        val c = cos(angle); val s = sin(angle)
        rotated.setTo(direction.x * c + direction.z * s, direction.y, -direction.x * s + direction.z * c)
        val k1 = radius / 350_000.0
        val k2 = radius / 150_000.0
        val t = time / 10_800.0
        return 0.65 * Noise.simplex(seed + 1, rotated.x * k1 + t * 0.3, rotated.y * k1 + t * 0.5, rotated.z * k1 + t * 0.8) +
            0.35 * Noise.simplex(seed + 2, rotated.x * k2 - t * 0.6, rotated.y * k2 + t * 0.2, rotated.z * k2 - t * 0.4)
    }

    /**
     * The free wind at unit [up] - bands by latitude, plus flow round the
     * highs and lows - into [out], tangent to the surface. Returns the
     * pressure there. [surfaceInflow], 0..1, tilts it in toward the lows as
     * friction does near the ground.
     */
    private fun circulation(up: Vec3, east: Vec3, north: Vec3, time: Double, out: Vec3, surfaceInflow: Double): Double {
        val latitude = asin(up.y.coerceIn(-1.0, 1.0))
        if (climate.circulation == Climate.Circulation.STILL) {
            out.setZero()
            return 0.0
        }
        val a = abs(latitude)
        val zonal: Double
        val meridional: Double
        if (climate.circulation == Climate.Circulation.BANDED) {
            // A giant's jets, alternating from the equator to the poles,
            // weakening toward them.
            zonal = climate.bandSpeed * cos(climate.bands * a) * (0.35 + 0.65 * cos(a))
            meridional = 0.0
        } else {
            // Easterly trades, westerlies, polar easterlies.
            val band = if (a <= Math.PI / 3.0) -cos(3.0 * a) else cos(6.0 * (a - Math.PI / 3.0))
            zonal = band * when {
                a <= Math.PI / 6.0 -> 6.0
                a <= Math.PI / 3.0 -> 9.0
                else -> if (band < 0.0) 4.0 else 9.0
            } * climate.windScale
            // Trades lean toward the equator.
            meridional = (if (a < Math.PI / 6.0) -2.5 * sin(6.0 * latitude) else 0.0) * climate.windScale
        }

        val p0 = pressure(up, time)
        val delta = GRADIENT_STEP / radius
        val pe = pressure(probe.setTo(up).addScaledInPlace(east, delta).normalizeInPlace(), time) -
            pressure(probe.setTo(up).addScaledInPlace(east, -delta).normalizeInPlace(), time)
        val pn = pressure(probe.setTo(up).addScaledInPlace(north, delta).normalizeInPlace(), time) -
            pressure(probe.setTo(up).addScaledInPlace(north, -delta).normalizeInPlace(), time)
        val scale = 350_000.0 / (2.0 * GRADIENT_STEP)
        val ge = pe * scale; val gn = pn * scale
        // Round the highs and lows, the way the planet's turning bends it:
        // highs on the right in the north, on the left in the south. As
        // sin(lat) / (sin^2 + e^2): 1/sin(lat) away from the equator, and
        // fading smoothly to nothing across it, where the turning that
        // bends the wind is gone. Clamping the divisor to +/-0.35 instead
        // flipped the whole flow round every time a craft crossed the line.
        val s = sin(latitude)
        val turning = s / (s * s + EQUATORIAL * EQUATORIAL)
        val geostrophic = GEOSTROPHIC * climate.windScale
        val geoE = -geostrophic * gn * turning
        val geoN = geostrophic * ge * turning
        // Friction near the ground: in toward the lows.
        val inflowE = -geostrophic * 0.35 * ge * surfaceInflow
        val inflowN = -geostrophic * 0.35 * gn * surfaceInflow

        out.setTo(east).mulInPlace(zonal + geoE + inflowE).addScaledInPlace(north, meridional + geoN + inflowN)
        return p0
    }

    /** The low-level wind at unit [direction]: what carries a thermal along. */
    internal fun boundaryWind(direction: Vec3, time: Double, out: Vec3): Vec3 {
        val e = Vec3(); val n = Vec3()
        frame(direction, e, n)
        circulation(direction, e, n, time, out, surfaceInflow = 0.5)
        return out.mulInPlace(0.8 * intensity.wind)
    }

    /** The wind at a storm's steering level, about five kilometres up. */
    internal fun steeringWind(direction: Vec3, time: Double, out: Vec3): Vec3 {
        val e = Vec3(); val n = Vec3()
        frame(direction, e, n)
        circulation(direction, e, n, time, out, surfaceInflow = 0.0)
        return out.mulInPlace(1.4 * intensity.wind)
    }

    // --- the permanent deck -------------------------------------------------------

    /** Inside the world-wide deck: thickest in its middle, thinning to its edges. */
    private fun deckAt(altitude: Double, out: AirSample) {
        val base = climate.deckBase; val top = climate.deckTop
        if (altitude < base || altitude > top) return
        val edge = min(altitude - base, top - altitude) / (0.15 * (top - base))
        addCloud(out, climate.deckDensity * smooth(0.0, 1.0, edge), CloudType.DECK)
    }

    // --- layer cloud --------------------------------------------------------------

    /**
     * Stratus, altostratus and cirrus at [altitude] over unit [up]: inside
     * one of the deck's drawn puffs, cloud; between them, clear air. As a
     * continuous sheet it put fog round a craft climbing through gaps where
     * nothing was drawn.
     */
    private fun layers(up: Vec3, altitude: Double, groundTop: Double, time: Double, out: AirSample) {
        if (altitude > 10_000.0) return
        for (type in LAYER_TYPES) {
            // Only the deck whose band this height is in.
            val height = if (type == CloudType.STRATUS) altitude - groundTop else altitude
            val band = LAYER_BANDS[type.ordinal]
            if (height < band.first || height > band.second) continue
            val cells = layerCells[type.ordinal]
            val spacing = LAYER_SPACING[type.ordinal]
            val count = cells.around(up, east, north, spacing / radius, layerKeys, reach = 1)
            for (k in 0 until count) {
                val shape = deck(type, layerKeys[k], time) ?: continue
                for (lobe in shape.lobes) {
                    val density = inside(lobe, up, altitude)
                    if (density > 0.0) addCloud(out, (density * 2.0).coerceAtMost(1.0) * LAYER_DENSITY[type.ordinal] * shape.amount, type)
                }
            }
        }
    }

    private val layerKeys = LongArray(32)
    private val lobeRel = Vec3()

    /**
     * How far inside [lobe] the point at unit [up] and [altitude] is: 1 at
     * its heart, 0 at its edge or outside. The drawn puff's shape - an
     * ellipsoid a little inside its lumps, cut flat underneath.
     */
    private fun inside(lobe: CloudLobe, up: Vec3, altitude: Double): Double {
        val c = lobe.centre
        val cr = c.length
        val vertical = (altitude + radius) - cr
        if (vertical < -0.4 * lobe.vertical) return 0.0
        lobeRel.setTo(up).mulInPlace(radius + altitude).addScaledInPlace(c, -1.0)
        val along = lobeRel dot c / cr
        lobeRel.addScaledInPlace(c, -along / cr)
        val h = lobeRel.length / (0.9 * lobe.horizontal)
        val v = vertical / (0.9 * lobe.vertical)
        return 1.0 - (h * h + v * v)
    }

    private val layerScratch = DoubleArray(4)

    private fun humidity(up: Vec3, pressure: Double, ocean: Boolean, time: Double): Double {
        val t = time / 14_400.0
        val k = radius / 600_000.0
        return 0.5 + 0.3 * Noise.simplex(seed + 3, up.x * k + t, up.y * k, up.z * k - t) -
            0.35 * pressure + (if (ocean) 0.12 else 0.0) + config.clouds.humidity
    }

    /**
     * How thick the layer cloud is here, 0..1: low over most of the map, so
     * the ground shows through a deck, rising to solid in the occasional
     * pocket some tens of kilometres across.
     */
    private fun thickness(up: Vec3, time: Double): Double {
        val pocket = 0.5 + 0.5 * noise(10, up, 45_000.0, time / 5_400.0)
        val dense = smooth(0.62, 0.9, pocket) * config.clouds.pockets
        return (0.3 + 0.7 * dense).coerceIn(0.0, 1.0)
    }

    /**
     * A layer of [type] over unit [up], above ground or sea standing at
     * [groundTop]: its cover (0..1), base and top (metres above datum) into
     * [out]. False where there is none.
     */
    private fun layer(type: CloudType, up: Vec3, groundTop: Double, humidity: Double, time: Double, out: DoubleArray): Boolean {
        when (type) {
            CloudType.STRATUS -> {
                val overcast = blanket(up, time, 31)
                out[3] = overcast
                val cover = max(smooth(0.7, 0.9, humidity + 0.12 * noise(4, up, 15_000.0, time / 3_600.0)), overcast)
                if (cover <= 0.0) return false
                // A few hundred metres over whatever is under it. Pinned to
                // sea level it buried the Cape - a kilometre up - in fog.
                val base = groundTop + 450.0 + 250.0 * noise(5, up, 40_000.0, 0.0)
                out[0] = cover; out[1] = base
                out[2] = base + 250.0 + 250.0 * (0.5 + 0.5 * noise(6, up, 2_500.0, time / 1_800.0))
            }
            CloudType.ALTOSTRATUS -> {
                val overcast = 0.85 * blanket(up, time, 33)
                out[3] = overcast
                val cover = max(smooth(0.78, 0.95, humidity + 0.15 * noise(7, up, 30_000.0, time / 3_600.0)), overcast)
                if (cover <= 0.0) return false
                out[0] = cover; out[1] = 4_200.0
                out[2] = 4_200.0 + 350.0 + 250.0 * (0.5 + 0.5 * noise(8, up, 4_000.0, time / 2_400.0))
            }
            CloudType.CIRRUS -> {
                out[3] = 0.0
                val cover = smooth(0.68, 0.9, 0.5 + 0.5 * noise(9, up, 80_000.0, time / 7_200.0))
                if (cover <= 0.0) return false
                out[0] = cover; out[1] = 8_700.0; out[2] = 9_300.0
            }
            else -> return false
        }
        return true
    }

    /**
     * Overcast over unit [up], 0..1: a broad pattern, a hundred and more
     * kilometres across and drifting over hours, closing a deck into a
     * blanket over its high ground - how much of the map, by the cloud
     * setting. A second, smaller pattern on it breaks the edges up and opens
     * gaps inside.
     */
    private fun blanket(up: Vec3, time: Double, salt: Int): Double {
        val from = config.clouds.blanketFrom
        if (from >= 1.0) return 0.0
        val p = 0.5 + 0.5 * (noise(salt, up, 150_000.0, time / 21_600.0) + 0.35 * noise(salt + 1, up, 40_000.0, time / 7_200.0)) / 1.35
        return smooth(from, from + 0.16, p)
    }

    private fun noise(salt: Int, up: Vec3, wavelength: Double, t: Double): Double {
        val k = radius / wavelength
        return Noise.simplex(seed + salt, up.x * k + t, up.y * k, up.z * k - t)
    }

    // --- features, for drawing and for lightning ------------------------------

    /** Thermals around unit [direction] whose cumulus is showing. */
    internal fun cumulus(direction: Vec3, radiusCells: Int, time: Double, out: MutableList<Convection.Thermal>) {
        val e = Vec3(); val n = Vec3()
        frame(direction, e, n)
        convection.clouds(direction, e, n, radiusCells, time, out)
    }

    /**
     * The clouds within [reach] metres of unit [direction] at [time], for
     * drawing: each as lobes in the body's frame. The same shapes the air
     * samples are made of, so a cloud looks as big as it is to fly into.
     */
    fun clouds(
        direction: Vec3,
        reach: Double,
        time: Double,
        out: MutableList<CloudShape>,
        /** How far off storms are drawn, m: further than the rest. */
        stormReach: Double = reach,
        /** How much detail in them, 0..1: fewer, bigger lobes for a device that can draw few. */
        stormDetail: Double = 1.0,
    ) {
        val e = Vec3(); val n = Vec3()
        frame(direction, e, n)
        // Cumulus, on their thermals.
        val thermals = ArrayList<Convection.Thermal>()
        convection.clouds(direction, e, n, kotlin.math.ceil(reach / Convection.CELL).toInt(), time, thermals)
        val column = Vec3(); val te = Vec3(); val tn = Vec3()
        for (th in thermals) {
            val amount = convection.cloudAmount(th, time)
            if (amount <= 0.02) continue
            val middle = th.base + th.depth * amount * 0.5
            convection.columnAt(th, middle, column)
            frame(column, te, tn)
            val shape = CloudShape(CloudType.CUMULUS, th.consistency * amount)
            for (l in 0 until th.lobeCount) {
                val o = l * 5
                val centre = Vec3().setTo(column).mulInPlace(radius + th.ground + middle)
                    .addScaledInPlace(te, th.lobes[o]).addScaledInPlace(tn, th.lobes[o + 1])
                    .addScaledInPlace(column, th.lobes[o + 2] * amount)
                shape.lobes.add(
                    CloudLobe(
                        centre,
                        th.lobes[o + 3] * (0.4 + 0.6 * amount),
                        max(th.lobes[o + 4] * amount, 40.0),
                        shade = 0.9 + 0.1 * th.consistency,
                    ),
                )
            }
            out.add(shape)
        }
        // Storms: out to [stormReach], further than the rest - they are what
        // a pilot most needs to see coming - in less detail the further off.
        val found = ArrayList<Storms.Storm>()
        storms.around(direction, e, n, kotlin.math.ceil(stormReach / Storms.CELL).toInt() + 1, time, found)
        val here = Vec3().setTo(direction).mulInPlace(radius)
        val stormCentre = Vec3()
        for (s in found) {
            if (storms.envelope(s, time) <= 0.05) continue
            storms.centreAt(s, time, stormCentre)
            val away = stormCentre.mulInPlace(radius).distanceTo(here) - kotlin.math.hypot(s.halfAlong, s.halfAcross)
            if (away > stormReach) continue
            val near = when {
                away < 15_000.0 -> 1.0
                away < 45_000.0 -> 0.6
                else -> 0.35
            }
            out.add(stormShapes.build(s, time, stormDetail * near) { terrain?.elevation(it) ?: 0.0 })
        }
        // Layer cloud: the deck's puffs, cell by cell - the same puffs the
        // air is sampled from, so what is drawn is what a craft flies into.
        // Near, the puffs themselves; far off, where a puff is a speck and
        // an overcast would need tens of thousands of them, coarse sheets
        // over the same cover.
        for (type in LAYER_TYPES) {
            if (!climate.layers) break
            val cells = layerCells[type.ordinal]
            val spacing = LAYER_SPACING[type.ordinal]
            val nearReach = min(reach, NEAR_DECK)
            val keys = LongArray(((2 * (nearReach / spacing).toInt() + 3) * (2 * (nearReach / spacing).toInt() + 3)) * 2 + 16)
            val count = cells.around(direction, e, n, spacing / radius, keys, reach = kotlin.math.ceil(nearReach / spacing).toInt())
            for (k in 0 until count) {
                deck(type, keys[k], time)?.let { out.add(it) }
            }
            if (reach <= NEAR_DECK) continue
            val farSpacing = spacing * FAR_DECK_FACTOR
            val farKeys = LongArray(((2 * (reach / farSpacing).toInt() + 3) * (2 * (reach / farSpacing).toInt() + 3)) * 2 + 16)
            val farCount = farCells[type.ordinal].around(direction, e, n, farSpacing / radius, farKeys, reach = kotlin.math.ceil(reach / farSpacing).toInt())
            val centre = Vec3()
            for (k in 0 until farCount) {
                farCells[type.ordinal].centre(farKeys[k], centre)
                // Leave the near ground to the fine puffs.
                if (kotlin.math.acos((centre dot direction).coerceIn(-1.0, 1.0)) * radius < NEAR_DECK - farSpacing * 0.5) continue
                farDeck(type, farKeys[k], time)?.let { out.add(it) }
            }
        }
    }

    private class DeckKey(val type: Int, val cell: Long, val epoch: Long) {
        override fun equals(other: Any?) = other is DeckKey && other.type == type && other.cell == cell && other.epoch == epoch
        override fun hashCode() = ((type * 31 + cell.hashCode()) * 31 + epoch.hashCode())
    }

    /** Deck cells worked out, so neither drawing nor sampling works them out twice. */
    private val decks = object : LinkedHashMap<DeckKey, CloudShape?>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DeckKey, CloudShape?>?) = size > 6_000
    }

    /**
     * The puffs of [type]'s deck in cell [key] at [time], or null for none.
     *
     * A clump of one to three, of mixed sizes, anywhere in the cell - and
     * none at all in some cells where the cover is thin. Worked out once per
     * [DECK_EPOCH] of time: a deck changes over hours, and a craft sampling
     * the air sixty times a second must not rebuild its neighbours' clouds
     * each time.
     */
    /** [deck], coarsely, for far off: cached alike. */
    private fun farDeck(type: CloudType, key: Long, time: Double): CloudShape? {
        val epoch = kotlin.math.floor(time / DECK_EPOCH).toLong()
        val cacheKey = DeckKey(type.ordinal + 100, key, epoch)
        if (decks.containsKey(cacheKey)) return decks[cacheKey]
        val shape = buildDeck(type, key, epoch * DECK_EPOCH, far = true)
        decks[cacheKey] = shape
        return shape
    }

    private fun deck(type: CloudType, key: Long, time: Double): CloudShape? {
        val epoch = kotlin.math.floor(time / DECK_EPOCH).toLong()
        val cacheKey = DeckKey(type.ordinal, key, epoch)
        if (decks.containsKey(cacheKey)) return decks[cacheKey]
        val shape = buildDeck(type, key, epoch * DECK_EPOCH)
        decks[cacheKey] = shape
        return shape
    }

    /**
     * How much of the sky over unit [up] a deck above [altitude] closes
     * off, 0..1: for how grey the day is under it. For drawing - the physics
     * never needs it.
     */
    fun overcastAbove(up: Vec3, altitude: Double, time: Double): Double {
        val elevation = terrain?.elevation(up) ?: 0.0
        val ocean = terrain?.hasOcean == true && elevation < 0.0
        val ground = if (ocean) 0.0 else max(elevation, 0.0)
        val humidity = humidity(up, pressure(up, time), ocean, time)
        val scratch = DoubleArray(4)
        var most = if (climate.hasDeck && altitude < climate.deckTop) climate.deckDensity else 0.0
        for (type in LAYER_TYPES) {
            if (!climate.layers) break
            if (type == CloudType.CIRRUS) continue
            if (!layer(type, up, ground, humidity, time, scratch)) continue
            if (scratch[2] < altitude) continue
            val cover = max(scratch[0] * thickness(up, time), scratch[3])
            most = max(most, smooth(0.3, 0.9, cover) * (if (type == CloudType.STRATUS) 1.0 else 0.75))
        }
        // Under a storm's base the whole sky is dark with it.
        val e = Vec3(); val n = Vec3()
        frame(up, e, n)
        return max(most, storms.overcastAbove(up, e, n, altitude, time))
    }

    private fun buildDeck(type: CloudType, key: Long, time: Double, far: Boolean = false): CloudShape? {
        val cells = if (far) farCells[type.ordinal] else layerCells[type.ordinal]
        val spacing = LAYER_SPACING[type.ordinal] * (if (far) FAR_DECK_FACTOR else 1.0)
        val cellCentre = cells.centre(key, Vec3())
        val cx = cells.hashX(key); val cy = cells.hashY(key)
        // One sample of the ground: all a deck needs to know is how high it
        // is and whether it is sea. (The wind's detailed description of the
        // ground costs nine, and on a fresh flight none of them are cached.)
        val elevation = terrain?.elevation(cellCentre) ?: 0.0
        val ocean = terrain?.hasOcean == true && elevation < 0.0
        val ground = if (ocean) 0.0 else max(elevation, 0.0)
        val humidity = humidity(cellCentre, pressure(cellCentre, time), ocean, time)
        val scratch = DoubleArray(4)
        if (!layer(type, cellCentre, ground, humidity, time, scratch)) return null
        // An overcast is thick wherever it is; elsewhere the pockets decide.
        val cover = max(scratch[0] * thickness(cellCentre, time), scratch[3])
        if (cover < 0.15) return null
        val base = scratch[1]; val top = scratch[2]
        val salt = seed + 90 + type.ordinal * 11
        if (Noise.hash(salt, cx, cy, 0) > 0.35 + 0.65 * cover) return null
        val ce = Vec3(); val cn = Vec3()
        frame(cellCentre, ce, cn)
        val shape = CloudShape(type, cover)
        // Thick cover closes up: more puffs, bigger, overlapping their
        // neighbours' into one lumpy sheet - three small ones a cell never
        // could, however cloudy the sky.
        val dense = smooth(0.55, 0.95, cover)
        val puffs = 1 + (Noise.hash(salt, cx, cy, 1) * 3.0 * cover).toInt().coerceAtMost(2) +
            (dense * (if (far) 1.0 else 4.0)).toInt()
        for (p in 0 until puffs) {
            // No wider than reaches its neighbours' cells: the air is sampled
            // from the cells round a point, and a puff spilling further would
            // be drawn where the air said there was none.
            val size = (spacing * (0.25 + 0.55 * Noise.hash(salt, cx, cy, 10 + p)) * (0.6 + 0.4 * cover) * (1.0 + 0.55 * dense))
                .coerceAtMost(spacing * (if (far) 1.0 else 0.6))
            val at = Vec3().setTo(cellCentre)
                .mulInPlace(radius + (base + top) * 0.5 + (Noise.hash(salt, cx, cy, 20 + p) - 0.5) * (top - base) * 0.4)
                .addScaledInPlace(ce, (Noise.hash(salt, cx, cy, 30 + p) - 0.5) * spacing * 0.9)
                .addScaledInPlace(cn, (Noise.hash(salt, cx, cy, 40 + p) - 0.5) * spacing * 0.9)
            shape.lobes.add(
                CloudLobe(
                    at, size,
                    (top - base) * 0.5 * (0.45 + 0.55 * Noise.hash(salt, cx, cy, 50 + p)) * (0.6 + 0.4 * cover),
                    shade = 0.8 + 0.12 * Noise.hash(salt, cx, cy, 60 + p),
                ),
            )
        }
        return shape
    }

    private val layerCells = Array(CloudType.entries.size) { SphereCells(radius, LAYER_SPACING[it]) }

    /** The same decks, coarsely, for drawing far off: a few big sheets instead of thousands of puffs. */
    private val farCells = Array(CloudType.entries.size) { SphereCells(radius, LAYER_SPACING[it] * FAR_DECK_FACTOR) }

    /**
     * The whole planet's cloud, coarsely, for the map: a sheet every
     * [spacing] metres where the decks are, and every storm's anvil. The
     * same fields the air is made of, at a scale where a single puff is
     * tens of kilometres across.
     */
    fun globalCover(spacing: Double, time: Double, out: MutableList<CloudShape>) {
        val cells = SphereCells(radius, spacing)
        val n = cells.perFace
        val centre = Vec3()
        val ce = Vec3(); val cn = Vec3()
        val scratch = DoubleArray(4)
        for (face in 0 until 6) for (i in 0 until n) for (j in 0 until n) {
            val key = (face.toLong() * n + i) * n + j
            cells.centre(key, centre)
            val elevation = terrain?.elevation(centre) ?: 0.0
            val ocean = terrain?.hasOcean == true && elevation < 0.0
            val ground = if (ocean) 0.0 else max(elevation, 0.0)
            val humidity = humidity(centre, pressure(centre, time), ocean, time)
            for (type in LAYER_TYPES) {
                if (!climate.layers) break
                if (!layer(type, centre, ground, humidity, time, scratch)) continue
                val cover = max(scratch[0] * thickness(centre, time), scratch[3])
                if (cover < 0.25) continue
                frame(centre, ce, cn)
                val cx = cells.hashX(key); val cy = cells.hashY(key)
                val at = Vec3().setTo(centre).mulInPlace(radius + (scratch[1] + scratch[2]) * 0.5)
                    .addScaledInPlace(ce, (Noise.hash(seed + 200, cx, cy, type.ordinal) - 0.5) * spacing * 0.6)
                    .addScaledInPlace(cn, (Noise.hash(seed + 201, cx, cy, type.ordinal) - 0.5) * spacing * 0.6)
                val shape = CloudShape(type, cover)
                shape.lobes.add(CloudLobe(at, spacing * (0.35 + 0.35 * cover), 1_500.0, shade = 0.9))
                out.add(shape)
            }
        }
        // Storms, all of them.
        val stormCells = SphereCells(radius, Storms.CELL)
        val sn = stormCells.perFace
        val c = Vec3(); val steer = Vec3(); val side = Vec3()
        for (face in 0 until 6) for (i in 0 until sn) for (j in 0 until sn) {
            val s = storms.storm((face.toLong() * sn + i) * sn + j, time)
            if (!s.exists) continue
            val envelope = storms.envelope(s, time)
            if (envelope <= 0.05) continue
            val shape = CloudShape(climate.stormCloud, envelope)
            // Its anvil, and its base - as wide as the storm spreads.
            storms.frameAt(s, time, c, steer, side)
            val main = s.mainCell
            shape.lobes.add(
                CloudLobe(
                    storms.place(c, steer, side, s.cellAlong[main] + 0.8 * s.core, s.cellAcross[main], radius + s.top - 800.0, Vec3()),
                    max(storms.anvilHalfAlong(s), storms.anvilHalfAcross(s)) * envelope, 2_500.0, shade = 0.95,
                ),
            )
            shape.lobes.add(
                CloudLobe(
                    storms.place(c, steer, side, s.deckAlong, 0.0, radius + (s.base + s.top) * 0.5, Vec3()),
                    max(s.halfAlong, s.halfAcross), (s.top - s.base) * 0.5, shade = 0.6,
                ),
            )
            out.add(shape)
        }
    }

    /** Lightning strikes near unit [direction] between [from] and [to]. */
    fun strikes(direction: Vec3, from: Double, to: Double, out: MutableList<Strike>) {
        val e = Vec3(); val n = Vec3()
        frame(direction, e, n)
        storms.strikes(direction, e, n, from, to, out)
    }

    internal val convectionModel: Convection get() = convection
    internal val stormModel: Storms get() = storms

    companion object {
        /** Above this Terra's air is still: no weather reaches orbit. Each world's is its [Climate.ceiling]. */
        const val CEILING = 30_000.0

        /** Height of the boundary layer, m. */
        const val BOUNDARY_LAYER = 1_000.0

        /** Peak jet-stream speed, m/s. */
        const val JET = 22.0

        /** Wind speed for a typical pressure gradient, m/s. */
        const val GEOSTROPHIC = 4.5

        /** How fast the pressure pattern as a whole drifts east, m/s. */
        const val PATTERN_DRIFT = 3.0

        /** How near the equator, as sin(latitude), the flow round highs and lows gives way. */
        const val EQUATORIAL = 0.35

        /** Finite-difference step for the pressure gradient, m. */
        const val GRADIENT_STEP = 30_000.0

        /** Out to here decks are drawn puff by puff, m; beyond, as coarse sheets. */
        private const val NEAR_DECK = 12_000.0

        /** How much coarser the far sheets' cells are than the puffs'. */
        private const val FAR_DECK_FACTOR = 4.0

        private val LAYER_TYPES = listOf(CloudType.STRATUS, CloudType.ALTOSTRATUS, CloudType.CIRRUS)

        /** Peak density of each layer type, by ordinal. */
        private val LAYER_DENSITY = doubleArrayOf(0.0, 1.0, 0.8, 0.35, 0.0, 0.0, 0.0)

        /** Spacing of the puffs a deck is drawn with, m, by ordinal. */
        private val LAYER_SPACING = doubleArrayOf(3_000.0, 2_500.0, 4_000.0, 8_000.0, 3_000.0, 3_000.0, 3_000.0)

        /** Heights each deck can be at, by ordinal: stratus above its ground, the rest above datum. */
        private val LAYER_BANDS = arrayOf(0.0 to 0.0, 0.0 to 2_200.0, 3_500.0 to 5_600.0, 8_300.0 to 9_700.0, 0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0)

        /** Seconds a deck's puffs are worked out for. */
        private const val DECK_EPOCH = 30.0

        private val TURBULENCE_SCALES = doubleArrayOf(40.0, 150.0, 500.0)
        private val TURBULENCE_WEIGHTS = doubleArrayOf(0.25, 0.45, 0.6)
    }
}
