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
import com.rm.apogee.platform.System
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile

/**
 * The sky's weather for drawing: the clouds round the camera and the air it's in.
 *
 * It has its own [Weather], the same function and config the server and prediction replica use, so
 * the cloud a player sees is the one their craft flies into. The cloud list is refreshed about once
 * a second and turned with the planet every frame.
 */
class CloudScene(
    val body: CelestialBody,
    val config: WeatherConfig,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** Toward the star at a given time, inertial. The clouds' shadows fall away from it. */
    private val sunAt: (Double) -> Vec3 = { Vec3.unitY() },
) {

    /** For the camera's air, every frame, on the frame thread. */
    val weather = Weather(body, config)

    /**
     * For listing clouds off the frame thread, since a first listing over new ground warms caches
     * for tens of milliseconds. Its own instance, because a weather's caches aren't thread safe.
     */
    private val listingWeather = Weather(body, config)
    @Volatile private var listing = false

    /** The air at the camera this frame. */
    val air = AirSample()

    private class Lobe(
        val centre: Vec3, val up: Quat, val scale: Vec3, val colour: FloatArray, val variant: Int, val flat: Boolean,
        val distance: Double,
        private val tier: QualityTier,
        /** A rain curtain, not a puff. */
        val rain: Boolean = false,
        /** Part of a storm, so it's drawn before anything else. */
        val storm: Boolean = false,
        /** How fast it's moving, body-fixed, in m/s, and when it was where [centre] says. */
        val drift: Vec3 = NO_DRIFT,
        val listedAt: Double = 0.0,
        /**
         * How fast it's changing per second until [changingUntil]: moving, growing and thinning
         * with its deck. Null and 0 for none.
         */
        val centreRate: Vec3? = null,
        val scaleRate: Vec3? = null,
        val alphaRate: Float = 0f,
        val changingUntil: Double = Double.NEGATIVE_INFINITY,
    ) {
        /** Faded by its place in the budget, 0..1. See [budgeted]. */
        @Volatile var budgetFade = 1f
        /**
         * Close puffs get the finest mesh, with small facets that read as billows, not slabs; each
         * step further out has a quarter of the facets. The finest is only used where the device
         * can take it.
         */
        val detail: Int get() = when (tier) {
            QualityTier.HIGH -> when {
                distance < 3_000.0 -> 4
                distance < 9_000.0 -> 3
                distance < 25_000.0 -> 2
                else -> 1
            }
            QualityTier.MEDIUM -> when {
                distance < 5_000.0 -> 3
                distance < 14_000.0 -> 2
                else -> 1
            }
            QualityTier.LOW -> if (distance < NEAR_DETAIL) 2 else 1
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

    /**
     * How far away storms are drawn, in metres, well past the rest. A storm tens of kilometres
     * across shows from a hundred away, and it's what a pilot most needs to see coming.
     */
    private val stormReach: Double get() = when (tier) {
        QualityTier.LOW -> 70_000.0
        QualityTier.MEDIUM -> 100_000.0
        QualityTier.HIGH -> 140_000.0
    }

    /** How finely storms are built: fewer, bigger lobes where fewer can be drawn. */
    private val stormDetail: Double get() = when (tier) {
        QualityTier.LOW -> 0.45
        QualityTier.MEDIUM -> 0.75
        QualityTier.HIGH -> 1.0
    }

    private val maxLobes: Int get() = when (tier) {
        // Room on LOW for a storm or two (a big one nearby is 200 lobes at LOW's detail, drawn
        // first) and the cumulus round them.
        QualityTier.LOW -> 320
        QualityTier.MEDIUM -> 900
        QualityTier.HIGH -> 1_800
    }

    /** The wind near the ground under the camera, body-fixed, for trees to lean in. */
    val surfaceWind = Vec3()
    private var windAt = Double.NEGATIVE_INFINITY

    /**
     * Brings the air and, when due, the cloud list up to [time] for a camera at body-fixed
     * [camera].
     */
    fun update(camera: Vec3, time: Double) {
        weather.sample(camera, time, air)
        if (time - windAt > 0.25) {
            windAt = time
            weather.surfaceWind(direction.setTo(camera).normalizeInPlace(), time, surfaceWind)
            overcast = weather.overcastAbove(direction, camera.length - body.radius, time)
        }
        if (listing) return
        // Between listings, the shadow follows its storms.
        val storms = shadowShapes
        if (time - shadowCastAt >= SHADOW_EVERY && storms.any { it.drift.lengthSq > 0.0 } &&
            time - listedAt < RELIST_SECONDS && camera.distanceTo(listedFrom) < RELIST_DISTANCE
        ) {
            shadowCastAt = time
            listing = true
            scope.launch(LIST) {
                try { recastShadow(time) } finally { listing = false }
            }
            return
        }
        val farDue = farLobes == null || time - farListedAt >= FAR_RELIST_SECONDS || camera.distanceTo(farListedFrom) >= FAR_RELIST_DISTANCE
        if (!farDue && time - listedAt < RELIST_SECONDS && camera.distanceTo(listedFrom) < RELIST_DISTANCE) return
        listedAt = time
        listedFrom.setTo(camera)
        if (farDue) { farListedAt = time; farListedFrom.setTo(camera) }
        val from = camera.copy()
        listing = true
        val first = lobes.isEmpty()
        scope.launch(LIST) {
            val started = System.nanoTime()
            try {
                // A fresh sky lists the nearest clouds first, then the rest, so it isn't empty for
                // seconds.
                if (first) lobes = list(from, time, 0.0, nearReach, withShadow = true)
                // The far sky changes slowly and is most of the work, so it's listed now and then;
                // the near sky (and its shadows) every second.
                val far = if (farDue) list(from, time, nearReach * FAR_OVERLAP, reach, withShadow = false).also { farLobes = it } else farLobes!!
                val near = list(from, time, 0.0, nearReach, withShadow = true)
                lobes = merged(near, far, from)
            } finally {
                lastListMillis = (System.nanoTime() - started) / 1e6
                listing = false
            }
        }
    }

    /** How long the last listing of the sky took, in ms, for the debug performance log. */
    @Volatile var lastListMillis = 0.0
        private set

    /** The far sky, as it was last listed. Null before the first. */
    @Volatile private var farLobes: List<Lobe>? = null
    private var farListedAt = Double.NEGATIVE_INFINITY
    private val farListedFrom = Vec3()

    /** Out to here the sky is listed every second, at least as far as the clouds' shadows reach. */
    private val nearReach: Double get() = kotlin.math.max(0.3 * reach, if (tier == QualityTier.HIGH) 30_000.0 else 20_000.0)

    /**
     * The near and far sky together. The far one leaves out what's now inside the near one (it was
     * listed from where the camera was). Storms first, then nearest first, up to the budget, plus
     * rain.
     */
    private fun merged(near: List<Lobe>, far: List<Lobe>, camera: Vec3): List<Lobe> {
        val all = ArrayList<Lobe>(near.size + far.size)
        val curtains = ArrayList<Lobe>()
        for (lobe in near) if (lobe.rain) curtains.add(lobe) else all.add(lobe)
        for (lobe in far) {
            if (lobe.centre.distanceTo(camera) - lobe.scale.x < nearReach) continue
            if (lobe.rain) curtains.add(lobe) else all.add(lobe)
        }
        val kept = budgeted(all)
        kept.addAll(curtains)
        return kept
    }

    /**
     * [lobes] cut to the budget: storms first, then nearest first, skipping any too faint to see.
     * The last few fade by their place in line, so a puff pushed out thins away instead of
     * vanishing.
     */
    private fun budgeted(lobes: MutableList<Lobe>): MutableList<Lobe> {
        lobes.removeAll { it.colour[3] < VISIBLE_ALPHA && it.alphaRate <= 0f }
        lobes.sortWith(compareBy<Lobe>({ !it.storm }, { it.distance }))
        val kept = if (lobes.size > maxLobes) lobes.subList(0, maxLobes).toMutableList() else lobes
        val fadeFrom = (maxLobes * (1.0 - BUDGET_FADE)).toInt()
        for (r in kept.indices) kept[r].budgetFade = if (r < fadeFrom) 1f else ((maxLobes - r).toFloat() / (maxLobes - fadeFrom)).coerceIn(0f, 1f)
        return kept
    }

    private fun list(camera: Vec3, time: Double, inner: Double, reach: Double, withShadow: Boolean): List<Lobe> {
        shapes.clear()
        val stormReach = if (reach < this.reach) reach else stormReach
        listingWeather.clouds(camera.copy().normalizeInPlace(), reach, time, shapes, stormReach, stormDetail)
        val list = ArrayList<Lobe>(shapes.size * 4)
        val curtains = ArrayList<Lobe>()
        for (shape in shapes) {
            val far = if (shape.type == CloudType.CUMULONIMBUS || shape.type == CloudType.DUST) stormReach else reach
            for (lobe in shape.lobes) {
                val distance = lobe.centre.distanceTo(camera) - lobe.horizontal
                if (distance > far || distance < inner) continue
                val weight = deckWeight(shape, lobe, camera)
                if (weight < 0.01) continue
                if (shape.type == CloudType.CIRRUS) cirrusStreaks(shape.amount, lobe, distance, far, weight, list, time, shape.amountRate)
                else list.add(lobeFor(shape.type, shape.amount, lobe, distance, fadeAt = far, weight = weight, drift = shape.drift, listedAt = time, amountRate = shape.amountRate))
            }
            for (lobe in shape.rain) {
                val distance = lobe.centre.distanceTo(camera) - lobe.horizontal
                if (distance > far || distance < inner) continue
                curtains.add(curtainFor(lobe, distance, far, shape.drift, time, forming(shape.type, shape.amount)))
            }
        }
        if (withShadow) {
            buildShadow(shapes, camera, time)
            // Kept so the shadow can be cast again as storms move between listings.
            shadowShapes = ArrayList(shapes)
            shadowFrom.setTo(camera)
            shadowListedAt = time
            shadowCastAt = time
        }
        // Storms first, whatever their distance: a few dozen lobes each, and what a pilot most
        // needs to see. Nearest first alone let a busy day's cumulus fill the budget and hide a
        // storm beyond. Then the rest, nearest first, as many as the device can draw, plus rain.
        val kept = budgeted(list)
        kept.addAll(curtains)
        return kept
    }

    /** The clouds' shadows on the ground round the camera, as last worked out, or null. */
    @Volatile var shadowGrid: com.rm.apogee.render.CloudShadowGrid? = null
        private set
    private var shadowRevision = 0

    /** The near sky as last listed, where it was listed from, and when, to cast the shadow again from. */
    @Volatile private var shadowShapes: List<com.rm.apogee.core.weather.CloudShape> = emptyList()
    private val shadowFrom = Vec3()
    @Volatile private var shadowListedAt = 0.0
    @Volatile private var shadowCastAt = Double.NEGATIVE_INFINITY

    /**
     * The shadow cast again from the last listing with its storms moved on to [time], so a storm's
     * shadow glides rather than stepping each second.
     */
    private fun recastShadow(time: Double) {
        val since = time - shadowListedAt
        val moved = shadowShapes.map { shape ->
            if (shape.drift.lengthSq == 0.0) shape
            else com.rm.apogee.core.weather.CloudShape(shape.type, shape.amount, shape.far).also { copy ->
                for (lobe in shape.lobes) {
                    copy.lobes.add(com.rm.apogee.core.weather.CloudLobe(Vec3().setTo(lobe.centre).addScaledInPlace(shape.drift, since), lobe.horizontal, lobe.vertical, lobe.shade, lobe.flat))
                }
            }
        }
        buildShadow(moved, shadowFrom, time)
    }

    private fun buildShadow(shapes: List<com.rm.apogee.core.weather.CloudShape>, camera: Vec3, time: Double) {
        val up = camera.copy().normalizeInPlace()
        val sun = body.rotationAt(time).inverseRotate(sunAt(time), Vec3())
        val (light, strength) = if ((sun dot up) > 0.08) sun to 0.6f else sun.copy().mulInPlace(-1.0) to 0.35f
        val ground = kotlin.math.max(body.terrain?.elevation(up) ?: 0.0, 0.0)
        val fine = tier == QualityTier.HIGH
        shadowGrid = com.rm.apogee.render.CloudShadowGrid.build(
            shapes, up, body.radius, ground, light, strength,
            size = if (fine) 192 else 128, extent = if (fine) 30_000.0 else 20_000.0, revision = ++shadowRevision,
        )
    }

    /** A storm's rain seen from outside it: a grey curtain from its base to the ground. */
    private fun curtainFor(lobe: com.rm.apogee.core.weather.CloudLobe, distance: Double, reach: Double, drift: Vec3, listedAt: Double, formed: Double = 1.0): Lobe {
        val up = Vec3().setTo(lobe.centre).normalizeInPlace()
        // Dark and nearly solid; paler, it vanished against the sky beside darker storms.
        val alpha = 0.8 * lobe.shade * (1.0 - smooth(0.65 * reach, reach, distance)) * formed
        return Lobe(
            lobe.centre, quatFromTo(Vec3.unitY(), up), Vec3(lobe.horizontal, lobe.vertical, lobe.horizontal),
            floatArrayOf(0.22f, 0.24f, 0.29f, alpha.toFloat()), variant = 0, flat = false, distance = distance, tier = tier,
            rain = true, drift = drift, listedAt = listedAt,
        )
    }

    /**
     * How much of a layer cloud's lobe to draw, 0..1, by its ground distance from [camera]: fine
     * puffs fade out toward [Weather.NEAR_DECK] as rough sheets fade in. Anything else is drawn
     * whole.
     */
    private fun deckWeight(shape: CloudShape, lobe: com.rm.apogee.core.weather.CloudLobe, camera: Vec3): Double {
        if (shape.type != CloudType.STRATUS && shape.type != CloudType.ALTOSTRATUS && shape.type != CloudType.CIRRUS) return 1.0
        val cosine = (lobe.centre dot camera) / (lobe.centre.length * camera.length).coerceAtLeast(1e-9)
        val ground = kotlin.math.acos(cosine.coerceIn(-1.0, 1.0)) * body.radius
        val near = Weather.NEAR_DECK
        return if (shape.far) smooth(DECK_BLEND_FAR * near, near, ground) else 1.0 - smooth(DECK_BLEND_NEAR * near, near, ground)
    }

    /** A lobe that can be drawn: turned its own way, shaped by where it is, coloured and faded. */
    private fun lobeFor(
        type: CloudType,
        amount: Double,
        lobe: com.rm.apogee.core.weather.CloudLobe,
        distance: Double,
        /** Faded out toward this distance, in metres. */
        fadeAt: Double = reach,
        /** How much of it to draw, from [deckWeight]. */
        weight: Double = 1.0,
        drift: Vec3 = NO_DRIFT,
        listedAt: Double = 0.0,
        /** How fast its shape's [CloudShape.amount] is changing, per second. */
        amountRate: Double = 0.0,
    ): Lobe {
        val up = Vec3().setTo(lobe.centre).normalizeInPlace()
        // Each puff is turned its own way about the vertical and its shape picked from where it is,
        // so neighbours aren't copies. "Where" is its ground point before drifting, so a storm's
        // puffs keep their shapes as it moves and a puff changing height keeps its own.
        val home = homeOf(lobe.centre, drift, listedAt)
        val hash = com.rm.apogee.core.terrain.Noise.hashInt(
            0xC10D, (home.x * 0.01).toInt(), (home.y * 0.01).toInt(), (home.z * 0.01).toInt(),
        )
        val yaw = ((hash ushr 8) and 0xFFFF) / 65_536.0 * 2.0 * Math.PI
        val orient = quatFromTo(Vec3.unitY(), up) * Quat.fromAxisAngle(Vec3.unitY(), yaw)
        val colour = colourOf(type, lobe.shade)
        // How solid, by kind and amount (a thin deck or young cumulus lets the sky through), fading
        // toward the edge of draw distance instead of popping in.
        val fade = (1.0 - smooth(0.65 * fadeAt, fadeAt, distance)) * weight * forming(type, amount)
        colour[3] = (OPACITY[type.ordinal] * (0.55 + 0.45 * amount.coerceIn(0.0, 1.0)) * fade).toFloat()
        val changing = lobe.changingUntil > listedAt
        return Lobe(
            lobe.centre, orient,
            Vec3(lobe.horizontal, lobe.vertical, lobe.horizontal),
            colour,
            variant = (hash and 0xFF).mod(CloudShapes.VARIANTS),
            flat = lobe.flat || type == CloudType.STRATUS || type == CloudType.ALTOSTRATUS || type == CloudType.CIRRUS || type == CloudType.DECK,
            distance = distance,
            tier = tier,
            storm = type == CloudType.CUMULONIMBUS || type == CloudType.DUST,
            drift = drift,
            listedAt = listedAt,
            centreRate = if (changing) lobe.centreRate else null,
            scaleRate = if (changing) Vec3(lobe.horizontalRate, lobe.verticalRate, lobe.horizontalRate) else null,
            alphaRate = if (changing && amount in 0.0..1.0) (OPACITY[type.ordinal] * 0.45 * amountRate * fade).toFloat() else 0f,
            changingUntil = lobe.changingUntil,
        )
    }

    /**
     * How far a cumulus or storm is in forming, 0..1, by its amount: it thickens into view as it
     * forms and thins as it dies, rather than popping in whole.
     */
    private fun forming(type: CloudType, amount: Double): Double = when (type) {
        CloudType.CUMULUS -> smooth(0.02, 0.15, amount)
        CloudType.CUMULONIMBUS, CloudType.DUST -> smooth(0.05, 0.2, amount)
        else -> 1.0
    }

    /** Where [centre] was before it drifted, on the ground below it, for its looks to be picked by. */
    private fun homeOf(centre: Vec3, drift: Vec3, listedAt: Double): Vec3 =
        Vec3().setTo(centre).addScaledInPlace(drift, -listedAt).normalizeInPlace().mulInPlace(body.radius)

    /**
     * Cirrus as streaks: three long thin wisps side by side inside the puff's round footprint, so
     * the craft still flies into cloud where it's drawn. Round puffs looked like solid stacked
     * discs from above. High wind combs real cirrus, so neighbouring wisps lie much the same way,
     * turning slowly across the sky.
     */
    private fun cirrusStreaks(
        amount: Double,
        lobe: com.rm.apogee.core.weather.CloudLobe,
        distance: Double,
        fadeAt: Double,
        weight: Double,
        out: MutableList<Lobe>,
        listedAt: Double = 0.0,
        amountRate: Double = 0.0,
    ) {
        val up = Vec3().setTo(lobe.centre).normalizeInPlace()
        val home = homeOf(lobe.centre, NO_DRIFT, 0.0)
        val hash = com.rm.apogee.core.terrain.Noise.hashInt(
            0xC1A5, (home.x * 0.01).toInt(), (home.y * 0.01).toInt(), (home.z * 0.01).toInt(),
        )
        val c = lobe.centre
        val yaw = Math.PI * com.rm.apogee.core.terrain.Noise.simplex(0xC1A6, c.x / CIRRUS_TURN, c.y / CIRRUS_TURN, c.z / CIRRUS_TURN) +
            0.25 * (((hash ushr 16) and 0xFF) / 255.0 - 0.5)
        val orient = quatFromTo(Vec3.unitY(), up) * Quat.fromAxisAngle(Vec3.unitY(), yaw)
        // Across the streaks, in the puff's own frame: its local x, turned by the yaw.
        val across = orient.rotate(Vec3(0.0, 0.0, 1.0), Vec3())
        val fade = (1.0 - smooth(0.65 * fadeAt, fadeAt, distance)) * weight
        val alpha = (CIRRUS_OPACITY * (0.55 + 0.45 * amount.coerceIn(0.0, 1.0)) * fade).toFloat()
        val changing = lobe.changingUntil > listedAt
        // Its streaks grow in step with it.
        val growth = if (lobe.horizontal > 1.0) lobe.horizontalRate / lobe.horizontal else 0.0
        for (k in 0 until 3) {
            // The middle one longest, the outer ones shorter, to stay inside the circle.
            val offset = (k - 1) * 0.45
            val length = lobe.horizontal * (if (k == 1) 1.0 else 0.75) * (0.8 + 0.2 * (((hash ushr (k * 4)) and 0xF) / 15.0))
            val centre = Vec3().setTo(lobe.centre).addScaledInPlace(across, offset * lobe.horizontal)
            val colour = colourOf(CloudType.CIRRUS, lobe.shade)
            colour[3] = alpha
            out.add(
                Lobe(
                    centre, orient,
                    Vec3(length, lobe.vertical * 0.35, lobe.horizontal * CIRRUS_WIDTH),
                    colour,
                    variant = ((hash ushr (8 + k)) and 0xFF).mod(CloudShapes.VARIANTS),
                    flat = true,
                    distance = distance,
                    tier = tier,
                    listedAt = listedAt,
                    centreRate = if (changing) Vec3().setTo(lobe.centreRate ?: NO_DRIFT).addScaledInPlace(across, offset * lobe.horizontalRate) else null,
                    scaleRate = if (changing) Vec3(length * growth, lobe.verticalRate * 0.35, lobe.horizontalRate * CIRRUS_WIDTH) else null,
                    alphaRate = if (changing && amount in 0.0..1.0) (CIRRUS_OPACITY * 0.45 * amountRate * fade).toFloat() else 0f,
                    changingUntil = lobe.changingUntil,
                ),
            )
        }
    }

    private val turned = Vec3()
    private val drifted = Vec3()

    /** Appends the clouds, turned to where the planet is at [bodyRotation], to [out]. */
    fun append(bodyRotation: Quat, time: Double, out: MutableList<RenderItem>) {
        for (lobe in lobes) {
            // Carried on along its drift since listing, so a moving storm moves smoothly and a
            // deck's puff keeps growing or thinning.
            val since = time - lobe.listedAt
            val changed = (kotlin.math.min(time, lobe.changingUntil) - lobe.listedAt).coerceAtLeast(0.0)
            if (lobe.drift === NO_DRIFT && (lobe.centreRate == null || changed == 0.0)) bodyRotation.rotate(lobe.centre, turned)
            else {
                drifted.setTo(lobe.centre).addScaledInPlace(lobe.drift, since)
                lobe.centreRate?.let { drifted.addScaledInPlace(it, changed) }
                bodyRotation.rotate(drifted, turned)
            }
            val scale = lobe.scaleRate?.takeIf { changed > 0.0 }?.let { rate ->
                Vec3(
                    (lobe.scale.x + rate.x * changed).coerceAtLeast(0.0),
                    (lobe.scale.y + rate.y * changed).coerceAtLeast(0.0),
                    (lobe.scale.z + rate.z * changed).coerceAtLeast(0.0),
                )
            } ?: lobe.scale
            val colour = if ((lobe.alphaRate != 0f && changed > 0.0) || lobe.budgetFade < 1f) lobe.colour.copyOf().also {
                it[3] = ((it[3] + lobe.alphaRate * changed.toFloat()) * lobe.budgetFade).coerceIn(0f, 1f)
            } else lobe.colour
            if (lobe.rain) {
                out.add(
                    RenderItem(
                        shape = RAIN_CURTAIN, position = turned.copy(), rotation = bodyRotation * lobe.up,
                        color = colour, caps = 0, scale = scale, ambient = 0.5f, curtain = true,
                    ),
                )
                continue
            }
            out.add(
                RenderItem(
                    shape = CloudPuff(lobe.variant, lobe.flat, lobe.detail),
                    position = turned.copy(),
                    rotation = bodyRotation * lobe.up,
                    color = colour,
                    scale = scale,
                    ambient = 0.42f,
                ),
            )
        }
    }

    // --- the map's cloud -----------------------------------------------------

    private val mapWeather = Weather(body, config)
    @Volatile private var mapShell: com.rm.apogee.render.CloudShell? = null
    private var mapListedAt = Double.NEGATIVE_INFINITY
    @Volatile private var mapListing = false
    private var mapRevision = 0

    /**
     * The whole planet's cloud for the map, as a veil over the globe: deck cover at each vertex
     * with every storm laid over it, greyer. Refreshed every half minute of game time on a worker.
     */
    fun mapShell(time: Double): com.rm.apogee.render.CloudShell? {
        if (!mapListing && time - mapListedAt > MAP_RELIST_SECONDS) {
            mapListedAt = time
            mapListing = true
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    val storms = ArrayList<CloudShape>()
                    mapWeather.globalStorms(time, storms)
                    // Each storm's lobes as directions, reach (a chord of the unit sphere), amount
                    // and darkness.
                    val lobes = storms.flatMap { shape ->
                        shape.lobes.map { lobe -> StormPatch(lobe.centre.normalized(), lobe.horizontal / body.radius, shape.amount, lobe.shade) }
                    }
                    val scratch = DoubleArray(4)
                    fun storm(direction: Vec3): StormPatch? = lobes.minByOrNull { it.centre.distanceTo(direction) / it.reach }
                        ?.takeIf { it.centre.distanceTo(direction) < it.reach }
                    fun stormCover(direction: Vec3): Double {
                        val s = storm(direction) ?: return 0.0
                        val t = 1.0 - s.centre.distanceTo(direction) / s.reach
                        return s.amount * (t * (2.0 - t))
                    }
                    // Decks averaged over a patch round each vertex. Their finer noise sampled once
                    // per vertex, with vertices tens of kilometres apart, came out as blocks.
                    val east = Vec3(); val north = Vec3(); val probe = Vec3()
                    val step = MAP_SHELL_BLUR / body.radius
                    fun decks(d: Vec3): Double {
                        east.setTo(0.0, 1.0, 0.0).crossInPlace(d)
                        if (east.lengthSq < 1e-9) east.setTo(1.0, 0.0, 0.0) else east.normalizeInPlace()
                        north.setTo(d).crossInPlace(east)
                        var sum = 0.0
                        for (i in -1..1) for (j in -1..1) {
                            probe.setTo(d).addScaledInPlace(east, i * step).addScaledInPlace(north, j * step).normalizeInPlace()
                            sum += mapWeather.coverAt(probe, time, scratch)
                        }
                        return sum / 9.0
                    }
                    mapShell = com.rm.apogee.render.CloudShell.build(
                        radius = body.radius,
                        height = MAP_SHELL_HEIGHT,
                        revision = ++mapRevision,
                        cover = { d -> maxOf(decks(d), stormCover(d)) },
                        shade = { d -> storm(d)?.let { s -> 1.0 - (1.0 - s.shade) * stormCover(d) } ?: 1.0 },
                    )
                } finally {
                    mapListing = false
                }
            }
        }
        return mapShell
    }

    /**
     * A storm on the map's veil: where it is, how far across (a chord of the unit sphere), how
     * much, and how dark.
     */
    private class StormPatch(val centre: Vec3, val reach: Double, val amount: Double, val shade: Double)

    // --- the windsock ---------------------------------------------------------

    private val sockWind = Vec3()
    private var sockWindAt = Double.NEGATIVE_INFINITY
    private val sockDirection = Vec3()
    private var sockGround = Double.NaN

    /**
     * A windsock beside the pads at [site], if the camera at body-fixed [camera] is near enough to
     * see it. A striped sock on a pole, limp in still air and straight out downwind in a gale, so
     * you can read the Cape's wind at a glance.
     */
    fun windsock(site: com.rm.apogee.core.world.LaunchSite, camera: Vec3, time: Double, bodyRotation: Quat, out: MutableList<RenderItem>) {
        if (site.bodyId != body.id) return
        if (sockGround.isNaN()) {
            // Beside the row of pads, which runs east-west, a little north.
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

    /** How far the camera can see through the weather, in metres. */
    val fogDistance: Double
        get() {
            // Visibility is where things are almost gone; the fog curve gets there at about three
            // of its distances.
            val v = air.visibility
            // A murky world's clear air (Aurantia's orange haze) closes in at its own distance, and
            // cloud and rain closer still.
            val clear = weather.climate.haze
            return when {
                v >= AirSample.CLEAR_VISIBILITY -> com.rm.apogee.render.WorldView.CLEAR_FOG
                v >= clear -> clear
                else -> v / 3.0
            }
        }

    /** What the fog is made of: white in cumulus, grey in stratus and rain, and dark in a storm. */
    val fogColor: FloatArray
        get() {
            val light = lightScale
            val base = when (air.cloudType) {
                CloudType.CUMULONIMBUS -> floatArrayOf(0.42f, 0.44f, 0.48f)
                CloudType.CUMULUS -> floatArrayOf(0.86f, 0.88f, 0.91f)
                CloudType.STRATUS, CloudType.ALTOSTRATUS -> floatArrayOf(0.70f, 0.72f, 0.75f)
                CloudType.CIRRUS -> floatArrayOf(0.80f, 0.84f, 0.90f)
                CloudType.DUST -> floatArrayOf(0.62f, 0.42f, 0.26f)
                CloudType.DECK -> floatArrayOf(0.80f, 0.70f, 0.46f)
                // Clear air. A murky world's is its haze.
                null -> if (sky === com.rm.apogee.render.SkyColours.TERRA) floatArrayOf(0.55f, 0.58f, 0.62f) else sky.haze
            }
            val t = if (air.cloudType == null) WHITE else sky.cloud
            return floatArrayOf(base[0] * t[0] * (0.5f + 0.5f * light), base[1] * t[1] * (0.5f + 0.5f * light), base[2] * t[2] * (0.5f + 0.5f * light))
        }

    /** 1 deep in cloud, where the sky is gone. */
    val skyFog: Float get() = smooth(0.05, 0.45, air.cloudDensity).toFloat()

    /** How much a deck overhead closes off the sky, 0..1. */
    @Volatile private var overcast = 0.0

    /** Sunlight left. A storm, rain, or an overcast sky over the camera dims it. */
    val lightScale: Float get() =
        (1.0 - 0.6 * air.storm - 0.25 * air.precipitation - 0.4 * overcast).coerceIn(0.25, 1.0).toFloat()

    private fun colourOf(type: CloudType, shade: Double): FloatArray {
        val s = shade.toFloat()
        return when (type) {
            CloudType.CUMULUS -> floatArrayOf(0.97f * s, 0.98f * s, 1.0f * s, 1f)
            // Darker than any other cloud and a cold blue-grey, so a storm looks like trouble from
            // 30 km.
            CloudType.CUMULONIMBUS -> floatArrayOf(0.60f * s, 0.63f * s, 0.72f * s, 1f)
            CloudType.STRATUS -> floatArrayOf(0.80f * s, 0.82f * s, 0.86f * s, 1f)
            CloudType.ALTOSTRATUS -> floatArrayOf(0.86f * s, 0.88f * s, 0.92f * s, 1f)
            CloudType.CIRRUS -> floatArrayOf(0.95f, 0.97f, 1.0f, 1f)
            // A wall of dust, the colour of the ground it was lifted from.
            CloudType.DUST -> floatArrayOf(0.72f * s, 0.48f * s, 0.30f * s, 1f)
            CloudType.DECK -> floatArrayOf(0.90f * s, 0.82f * s, 0.58f * s, 1f)
        }.also { c -> val t = sky.cloud; c[0] *= t[0]; c[1] *= t[1]; c[2] *= t[2] }
    }

    /** The colours of this world's air, which tint its clouds. */
    private val sky = com.rm.apogee.render.SkyColours.of(body.id)

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private companion object {

        /** No drift at all, shared. */

        val NO_DRIFT = Vec3()

        /** The opacity of each kind at its thickest, by ordinal. */
        val OPACITY = doubleArrayOf(0.85, 0.55, 0.45, 0.25, 1.0, 0.95, 0.9)

        /**
         * Each cirrus wisp. Thin, so the ground shows through, and a little less where two cross.
         */
        const val CIRRUS_OPACITY = 0.14

        /** How wide a cirrus wisp is, as a share of its puff's radius. */
        const val CIRRUS_WIDTH = 0.14

        /**
         * Where a deck's fine puffs start to fade and its rough sheets start to come in, as shares
         * of [Weather.NEAR_DECK]. Sheets start further in, since each is kilometres across and one
         * centred at the edge would leave a thin band.
         */
        const val DECK_BLEND_NEAR = 0.5
        const val DECK_BLEND_FAR = 0.25

        /** Over how far, in metres, the wisps turn from lying one way to another. */
        const val CIRRUS_TURN = 120_000.0

        val WHITE = floatArrayOf(1f, 1f, 1f)

        /** Metres out to which puffs get the finest mesh, and the fine one. */
        const val NEAR_DETAIL = 6_000.0

        /**
         * Rain seen from far off: a ragged column, a little wider at the ground, open at both ends.
         */
        val RAIN_CURTAIN = com.rm.apogee.core.part.ModelSpec.Lathe(
            listOf(listOf(1.05, -1.0), listOf(0.98, -0.55), listOf(0.9, 0.0), listOf(0.94, 0.5), listOf(0.85, 1.0)),
            segments = 12,
        )

        /**
         * How high the map's veil floats over the ground, in metres. Never higher than a share of
         * the world.
         */
        const val MAP_SHELL_HEIGHT = 8_000.0

        /** How far around each of its vertices the map's veil averages the cloud, in metres. */
        const val MAP_SHELL_BLUR = 30_000.0
        const val MAP_RELIST_SECONDS = 30.0

        const val SOCK_OFFSET = 35.0
        const val SOCK_HEIGHT = 7.0
        const val SOCK_LENGTH = 3.0
        const val SOCK_VISIBLE = 4_000.0
        const val SOCK_FULL_WIND = 12.0

        val SOCK_POLE = com.rm.apogee.core.part.MeshSpec.Cylinder(0.08, SOCK_HEIGHT)

        /** Three bands of a tapering sock, each a third of its length, wide at the mouth. */
        val SOCK_BANDS = (0 until 3).map { k ->
            val r0 = 0.45 - 0.08 * k
            val r1 = 0.45 - 0.08 * (k + 1)
            com.rm.apogee.core.part.MeshSpec.Cone(bottomRadius = r1, topRadius = r0, height = SOCK_LENGTH / 3.0)
        }

        const val RELIST_SECONDS = 1.0

        /** Fainter than this, a lobe isn't drawn, and takes no place in the budget. */
        const val VISIBLE_ALPHA = 0.01f

        /** The last share of the budget, faded out by place. */
        const val BUDGET_FADE = 0.15

        /** How often a storm's shadow is cast again as it moves, in seconds. */
        const val SHADOW_EVERY = 0.25
        const val RELIST_DISTANCE = 1_000.0

        /**
         * The far sky is listed again this often, in seconds, or when the camera has gone this far,
         * in metres.
         */
        const val FAR_RELIST_SECONDS = 5.0
        const val FAR_RELIST_DISTANCE = 4_000.0

        /** The far listing reaches this share of the near one in, so moving between listings leaves no gap. */
        const val FAR_OVERLAP = 0.7

        /**
         * The sky is listed on its own thread, just below normal. On HIGH a listing is most of a
         * second of work, and on the shared pool it fought the game server for threads.
         */
        val LIST = workerPool("cloud-list", 1)
    }
}
