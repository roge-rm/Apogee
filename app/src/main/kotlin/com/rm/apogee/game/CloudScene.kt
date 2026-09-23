package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.CloudShape
import com.rm.apogee.core.weather.CloudType
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.render.CloudPuff
import com.rm.apogee.render.CloudShapes
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.RenderItem
import kotlinx.coroutines.launch

/**
 * The sky's weather, for drawing: the clouds around the camera, and the air
 * the camera is in.
 *
 * Its own [Weather] - the same function the server and the prediction
 * replica compute, from the same config, so the cloud a player sees is the
 * cloud their craft flies into. The cloud list is refreshed about once a
 * second (they change slowly) and turned with the planet every frame.
 */
class CloudScene(
    val body: CelestialBody,
    val config: WeatherConfig,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {

    /** For the camera's air, every frame, on the frame thread. */
    val weather = Weather(body, config)

    /**
     * For listing clouds, off the frame thread - a first listing over new
     * ground warms caches for tens of milliseconds, a visible hitch if it
     * ran in the frame. Its own instance: a weather's caches are not shared
     * between threads.
     */
    private val listingWeather = Weather(body, config)
    @Volatile private var listing = false

    /** The air at the camera this frame. */
    val air = AirSample()

    private class Lobe(val centre: Vec3, val up: Quat, val scale: Vec3, val colour: FloatArray, val variant: Int, val flat: Boolean, val distance: Double)

    @Volatile private var lobes: List<Lobe> = emptyList()
    private var listedAt = Double.NEGATIVE_INFINITY
    private val listedFrom = Vec3()
    private val shapes = ArrayList<CloudShape>()
    private val direction = Vec3()

    private val reach: Double get() = when (tier) {
        QualityTier.LOW -> 25_000.0
        QualityTier.MEDIUM -> 45_000.0
        QualityTier.HIGH -> 80_000.0
    }

    private val maxLobes: Int get() = when (tier) {
        QualityTier.LOW -> 220
        QualityTier.MEDIUM -> 600
        QualityTier.HIGH -> 1_200
    }

    /** The wind near the ground under the camera, body-fixed: for trees to lean in. */
    val surfaceWind = Vec3()
    private var windAt = Double.NEGATIVE_INFINITY
    private var loggedAt = Double.NEGATIVE_INFINITY

    /** Brings the air and, when due, the cloud list up to [time] for a camera at [camera] (body-fixed). */
    fun update(camera: Vec3, time: Double) {
        weather.sample(camera, time, air)
        if (time - loggedAt > 5.0) {
            loggedAt = time
            android.util.Log.i(
                "ApogeeWeather",
                "t=%.0f wind=%.1f m/s lift=%.1f turb=%.2f cloud=%.2f %s rain=%.2f storm=%.2f vis=%.0f lobes=%d".format(
                    time, air.wind.length, air.lift, air.turbulence, air.cloudDensity, air.cloudType,
                    air.precipitation, air.storm, air.visibility, lobes.size,
                ),
            )
        }
        if (time - windAt > 0.25) {
            windAt = time
            weather.surfaceWind(direction.setTo(camera).normalizeInPlace(), time, surfaceWind)
        }
        if (listing) return
        if (time - listedAt < RELIST_SECONDS && camera.distanceTo(listedFrom) < RELIST_DISTANCE) return
        listedAt = time
        listedFrom.setTo(camera)
        val from = camera.copy()
        listing = true
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                lobes = list(from, time)
            } finally {
                listing = false
            }
        }
    }

    private fun list(camera: Vec3, time: Double): List<Lobe> {
        shapes.clear()
        listingWeather.clouds(camera.copy().normalizeInPlace(), reach, time, shapes)
        val list = ArrayList<Lobe>(shapes.size * 4)
        var index = 0
        for (shape in shapes) {
            for (lobe in shape.lobes) {
                val distance = lobe.centre.distanceTo(camera) - lobe.horizontal
                if (distance > reach) continue
                val up = Vec3().setTo(lobe.centre).normalizeInPlace()
                val orient = quatFromTo(Vec3.unitY(), up)
                val colour = colourOf(shape.type, lobe.shade)
                // How solid, by kind and by how much of it there is - a thin
                // deck or a young cumulus lets the sky through - and fading
                // out toward the edge of the draw distance rather than
                // appearing there.
                colour[3] = (OPACITY[shape.type.ordinal] * (0.55 + 0.45 * shape.amount.coerceIn(0.0, 1.0)) *
                    (1.0 - smooth(0.65 * reach, reach, distance))).toFloat()
                list.add(
                    Lobe(
                        lobe.centre, orient,
                        Vec3(lobe.horizontal, lobe.vertical, lobe.horizontal),
                        colour,
                        variant = (index++ * 7 + (lobe.centre.x * 0.001).toInt()).mod(CloudShapes.VARIANTS),
                        flat = shape.type == CloudType.STRATUS || shape.type == CloudType.ALTOSTRATUS || shape.type == CloudType.CIRRUS,
                        distance = distance,
                    ),
                )
            }
        }
        // Nearest first, as many as the device can draw.
        list.sortBy { it.distance }
        return if (list.size > maxLobes) list.subList(0, maxLobes).toList() else list
    }

    private val turned = Vec3()

    /** Appends the clouds, turned to where the planet is at [bodyRotation], to [out]. */
    fun append(bodyRotation: Quat, out: MutableList<RenderItem>) {
        for (lobe in lobes) {
            bodyRotation.rotate(lobe.centre, turned)
            out.add(
                RenderItem(
                    shape = CloudPuff(lobe.variant, lobe.flat),
                    position = turned.copy(),
                    rotation = bodyRotation * lobe.up,
                    color = lobe.colour,
                    scale = lobe.scale,
                    ambient = 0.42f,
                ),
            )
        }
    }

    /** How far the camera can see through the weather, metres. */
    val fogDistance: Double
        get() {
            // Visibility is where things are all but gone; the fog curve
            // reaches that at about three of its distances.
            val v = air.visibility
            return if (v >= AirSample.CLEAR_VISIBILITY) com.rm.apogee.render.WorldView.CLEAR_FOG else v / 3.0
        }

    /** What the fog is made of: white in cumulus, grey in stratus and rain, dark in a storm. */
    val fogColor: FloatArray
        get() {
            val light = lightScale
            val base = when (air.cloudType) {
                CloudType.CUMULONIMBUS -> floatArrayOf(0.42f, 0.44f, 0.48f)
                CloudType.CUMULUS -> floatArrayOf(0.86f, 0.88f, 0.91f)
                CloudType.STRATUS, CloudType.ALTOSTRATUS -> floatArrayOf(0.70f, 0.72f, 0.75f)
                CloudType.CIRRUS -> floatArrayOf(0.80f, 0.84f, 0.90f)
                null -> floatArrayOf(0.55f, 0.58f, 0.62f)
            }
            return floatArrayOf(base[0] * (0.5f + 0.5f * light), base[1] * (0.5f + 0.5f * light), base[2] * (0.5f + 0.5f * light))
        }

    /** 1 deep in cloud, where the sky is gone. */
    val skyFog: Float get() = smooth(0.05, 0.45, air.cloudDensity).toFloat()

    /** Sunlight left under whatever storm is near. */
    val lightScale: Float get() = (1.0 - 0.6 * air.storm - 0.25 * air.precipitation).coerceIn(0.25, 1.0).toFloat()

    private fun colourOf(type: CloudType, shade: Double): FloatArray {
        val s = shade.toFloat()
        return when (type) {
            CloudType.CUMULUS -> floatArrayOf(0.97f * s, 0.98f * s, 1.0f * s, 1f)
            CloudType.CUMULONIMBUS -> floatArrayOf(0.80f * s, 0.82f * s, 0.88f * s, 1f)
            CloudType.STRATUS -> floatArrayOf(0.80f * s, 0.82f * s, 0.86f * s, 1f)
            CloudType.ALTOSTRATUS -> floatArrayOf(0.86f * s, 0.88f * s, 0.92f * s, 1f)
            CloudType.CIRRUS -> floatArrayOf(0.95f, 0.97f, 1.0f, 1f)
        }
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private companion object {
        /** Opacity of each kind at its thickest, by ordinal. */
        val OPACITY = doubleArrayOf(0.92, 0.72, 0.55, 0.3, 0.96)

        const val RELIST_SECONDS = 1.0
        const val RELIST_DISTANCE = 1_000.0
    }
}
