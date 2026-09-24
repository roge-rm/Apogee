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

    private class Lobe(
        val centre: Vec3, val up: Quat, val scale: Vec3, val colour: FloatArray, val variant: Int, val flat: Boolean,
        val distance: Double,
        /** Whether this device can afford the finest mesh up close. */
        val fine: Boolean,
    ) {
        /**
         * Close puffs are the finest mesh, small facets that read as billows
         * rather than slabs; near ones a quarter of that; far ones a quarter
         * again.
         */
        val detail: Int get() = when {
            fine && distance < CLOSE_DETAIL -> 3
            distance < NEAR_DETAIL -> 2
            else -> 1
        }
    }

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
        QualityTier.LOW -> 140
        QualityTier.MEDIUM -> 450
        QualityTier.HIGH -> 1_000
    }

    /** The wind near the ground under the camera, body-fixed: for trees to lean in. */
    val surfaceWind = Vec3()
    private var windAt = Double.NEGATIVE_INFINITY

    /** Brings the air and, when due, the cloud list up to [time] for a camera at [camera] (body-fixed). */
    fun update(camera: Vec3, time: Double) {
        weather.sample(camera, time, air)
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
        val first = lobes.isEmpty()
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                // A fresh sky shows the nearest clouds first, then the rest:
                // waiting for all of them left an empty sky for seconds.
                if (first) lobes = list(from, time, reach * 0.3)
                lobes = list(from, time, reach)
            } finally {
                listing = false
            }
        }
    }

    private fun list(camera: Vec3, time: Double, reach: Double): List<Lobe> {
        shapes.clear()
        listingWeather.clouds(camera.copy().normalizeInPlace(), reach, time, shapes)
        val list = ArrayList<Lobe>(shapes.size * 4)
        for (shape in shapes) {
            for (lobe in shape.lobes) {
                val distance = lobe.centre.distanceTo(camera) - lobe.horizontal
                if (distance > reach) continue
                list.add(lobeFor(shape.type, shape.amount, lobe, distance))
            }
        }
        // Nearest first, as many as the device can draw.
        list.sortBy { it.distance }
        return if (list.size > maxLobes) list.subList(0, maxLobes).toList() else list
    }

    /** A drawable lobe: turned its own way, shaped by where it is, coloured and faded. */
    private fun lobeFor(type: CloudType, amount: Double, lobe: com.rm.apogee.core.weather.CloudLobe, distance: Double, flatForced: Boolean = false): Lobe {
        val up = Vec3().setTo(lobe.centre).normalizeInPlace()
        // Each puff turned its own way about the vertical, and its shape
        // picked from where it is: turned alike and handed out in order,
        // neighbours were copies of each other.
        val hash = com.rm.apogee.core.terrain.Noise.hashInt(
            0xC10D, (lobe.centre.x * 0.01).toInt(), (lobe.centre.y * 0.01).toInt(), (lobe.centre.z * 0.01).toInt(),
        )
        val yaw = ((hash ushr 8) and 0xFFFF) / 65_536.0 * 2.0 * Math.PI
        val orient = quatFromTo(Vec3.unitY(), up) * Quat.fromAxisAngle(Vec3.unitY(), yaw)
        val colour = colourOf(type, lobe.shade)
        // How solid, by kind and by how much of it there is - a thin deck or
        // a young cumulus lets the sky through - and fading out toward the
        // edge of the draw distance rather than appearing there.
        colour[3] = (OPACITY[type.ordinal] * (0.55 + 0.45 * amount.coerceIn(0.0, 1.0)) *
            (1.0 - smooth(0.65 * reach, reach, distance))).toFloat()
        return Lobe(
            lobe.centre, orient,
            Vec3(lobe.horizontal, lobe.vertical, lobe.horizontal),
            colour,
            variant = (hash and 0xFF).mod(CloudShapes.VARIANTS),
            flat = flatForced || type == CloudType.STRATUS || type == CloudType.ALTOSTRATUS || type == CloudType.CIRRUS,
            distance = distance,
            fine = tier != QualityTier.LOW,
        )
    }

    private val turned = Vec3()

    /** Appends the clouds, turned to where the planet is at [bodyRotation], to [out]. */
    fun append(bodyRotation: Quat, out: MutableList<RenderItem>) {
        for (lobe in lobes) {
            bodyRotation.rotate(lobe.centre, turned)
            out.add(
                RenderItem(
                    shape = CloudPuff(lobe.variant, lobe.flat, lobe.detail),
                    position = turned.copy(),
                    rotation = bodyRotation * lobe.up,
                    color = lobe.colour,
                    scale = lobe.scale,
                    ambient = 0.42f,
                ),
            )
        }
    }

    // --- the map's cloud -----------------------------------------------------

    private val mapWeather = Weather(body, config)
    @Volatile private var mapLobes: List<Lobe> = emptyList()
    private var mapListedAt = Double.NEGATIVE_INFINITY
    @Volatile private var mapListing = false

    /**
     * The whole planet's cloud for the map, turned to [bodyRotation], into
     * [out]: coarse sheets and storm anvils, refreshed every half minute of
     * game time on a worker, since the whole globe is a few thousand cells.
     */
    fun mapItems(time: Double, bodyRotation: Quat, out: MutableList<RenderItem>) {
        if (!mapListing && time - mapListedAt > MAP_RELIST_SECONDS) {
            mapListedAt = time
            mapListing = true
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    val shapes = ArrayList<CloudShape>()
                    mapWeather.globalCover(MAP_SPACING, time, shapes)
                    mapLobes = shapes.flatMap { shape ->
                        shape.lobes.map { lobe -> lobeFor(shape.type, shape.amount, lobe, 0.0, flatForced = true) }
                    }
                } finally {
                    mapListing = false
                }
            }
        }
        for (lobe in mapLobes) {
            bodyRotation.rotate(lobe.centre, turned)
            out.add(
                RenderItem(
                    shape = CloudPuff(lobe.variant, lobe.flat, detail = 1),
                    position = turned.copy(),
                    rotation = bodyRotation * lobe.up,
                    color = lobe.colour,
                    scale = lobe.scale,
                    ambient = 0.55f,
                ),
            )
        }
    }

    // --- the windsock ---------------------------------------------------------

    private val sockWind = Vec3()
    private var sockWindAt = Double.NEGATIVE_INFINITY
    private val sockDirection = Vec3()
    private var sockGround = Double.NaN

    /**
     * A windsock beside the pads at [site], if the camera at [camera]
     * (body-fixed) is near enough to see it: a striped sock on a pole,
     * hanging limp in still air and standing out straight downwind in a
     * gale - the wind at the Cape, read at a glance.
     */
    fun windsock(site: com.rm.apogee.core.world.LaunchSite, camera: Vec3, time: Double, bodyRotation: Quat, out: MutableList<RenderItem>) {
        if (site.bodyId != body.id) return
        if (sockGround.isNaN()) {
            // Beside the row of pads, which runs east-west: a little north.
            val lat = site.latitude + SOCK_OFFSET / body.radius
            sockDirection.setTo(
                kotlin.math.cos(lat) * kotlin.math.cos(site.longitude),
                kotlin.math.sin(lat),
                kotlin.math.cos(lat) * kotlin.math.sin(site.longitude),
            ).normalizeInPlace()
            sockGround = body.terrain?.elevation(sockDirection)?.coerceAtLeast(0.0) ?: 0.0
        }
        val base = Vec3().setTo(sockDirection).mulInPlace(body.radius + sockGround)
        if (base.distanceTo(camera) > SOCK_VISIBLE) return
        if (time - sockWindAt > 0.2) {
            sockWindAt = time
            weather.sample(Vec3().setTo(sockDirection).mulInPlace(body.radius + sockGround + SOCK_HEIGHT), time, sampleScratch)
            sockWind.setTo(sampleScratch.wind)
        }
        val up = sockDirection
        val upright = quatFromTo(Vec3.unitY(), up)
        // The pole.
        out.add(
            RenderItem(
                shape = SOCK_POLE,
                position = bodyRotation.rotate(Vec3().setTo(base).addScaledInPlace(up, SOCK_HEIGHT / 2), Vec3()),
                rotation = bodyRotation * upright,
                color = floatArrayOf(0.75f, 0.75f, 0.78f, 1f),
            ),
        )
        // The sock: downwind, lifting from hanging to level as the wind rises.
        val horizontal = Vec3().setTo(sockWind).addScaledInPlace(up, -(sockWind dot up))
        val speed = horizontal.length
        val downwind = if (speed > 0.05) horizontal.mulInPlace(1.0 / speed) else Vec3(up.z, 0.0, -up.x).normalizeInPlace()
        val lift = (speed / SOCK_FULL_WIND).coerceIn(0.0, 1.0)
        val droop = Math.toRadians(80.0) * (1.0 - lift)
        val along = Vec3().setTo(downwind).mulInPlace(kotlin.math.cos(droop)).addScaledInPlace(up, -kotlin.math.sin(droop))
        val flutter = 0.06 * lift * kotlin.math.sin(time * 9.0)
        along.addScaledInPlace(Vec3().setTo(up).crossInPlace(downwind), flutter).normalizeInPlace()
        val mouth = Vec3().setTo(base).addScaledInPlace(up, SOCK_HEIGHT)
        for (k in 0 until 3) {
            val t0 = k / 3.0
            val centre = Vec3().setTo(mouth).addScaledInPlace(along, SOCK_LENGTH * (t0 + 1.0 / 6.0))
            out.add(
                RenderItem(
                    shape = SOCK_BANDS[k],
                    position = bodyRotation.rotate(centre, Vec3()),
                    rotation = bodyRotation * quatFromTo(Vec3.unitY(), along.copy().negateInPlace()),
                    color = if (k % 2 == 0) floatArrayOf(1f, 0.45f, 0.12f, 1f) else floatArrayOf(0.95f, 0.95f, 0.95f, 1f),
                ),
            )
        }
    }

    private val sampleScratch = AirSample()

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
        val OPACITY = doubleArrayOf(0.85, 0.55, 0.45, 0.25, 0.95)

        /** Metres out to which puffs get the finest mesh, and the fine one. */
        const val CLOSE_DETAIL = 2_500.0
        const val NEAR_DETAIL = 6_000.0

        const val MAP_SPACING = 70_000.0
        const val MAP_RELIST_SECONDS = 30.0

        const val SOCK_OFFSET = 35.0
        const val SOCK_HEIGHT = 7.0
        const val SOCK_LENGTH = 3.0
        const val SOCK_VISIBLE = 4_000.0
        const val SOCK_FULL_WIND = 12.0

        val SOCK_POLE = com.rm.apogee.core.part.MeshSpec.Cylinder(0.08, SOCK_HEIGHT)

        /** Three bands of a tapering sock, each a third of its length: wide at the mouth. */
        val SOCK_BANDS = (0 until 3).map { k ->
            val r0 = 0.45 - 0.08 * k
            val r1 = 0.45 - 0.08 * (k + 1)
            com.rm.apogee.core.part.MeshSpec.Cone(bottomRadius = r1, topRadius = r0, height = SOCK_LENGTH / 3.0)
        }

        const val RELIST_SECONDS = 1.0
        const val RELIST_DISTANCE = 1_000.0
    }
}
