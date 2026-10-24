package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.DustKind
import com.rm.apogee.core.weather.LiveBlizzard
import com.rm.apogee.core.weather.LiveDust
import com.rm.apogee.core.weather.LiveEruption
import com.rm.apogee.core.weather.LiveHail
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.weather.LiveHurricane
import com.rm.apogee.core.weather.LiveQuake
import com.rm.apogee.core.weather.LiveTornado
import com.rm.apogee.core.weather.LiveTsunami
import com.rm.apogee.core.weather.Quakes
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * The one hurricane, tornado, quake, tsunami, dust storm, blizzard, hail shaft or eruption most worth a warning at a place, worked out from the
 * weather model, so a replica gets the same as the server with nothing sent.
 *
 * Listed off the frame thread about once a second, or sooner when warped.
 */
class WeatherWarnings(
    private val body: CelestialBody,
    private val config: WeatherConfig,
    private val scope: kotlinx.coroutines.CoroutineScope?,
) {
    enum class Kind { TORNADO, WATERSPOUT, TSUNAMI, HURRICANE, QUAKE, AFTERSHOCKS, DUST, DUST_STORM, BLIZZARD, HAIL, ASH, ERUPTION }

    /**
     * One warning. [distance] is metres to a hurricane's eye, a tornado's foot, a quake or a
     * haboob's front, a blizzard's edge, a hail shaft's edge or an ash cloud's edge (0 inside it), or an erupting vent; for a
     * tsunami or a quake, [seconds] until it gets here, 0 while it's passing or shaking. [point] is where to look on the map, unit,
     * body-fixed, and [span] how much round it to show, in metres.
     */
    class Warning(
        val kind: Kind,
        val id: Long,
        val distance: Double,
        val seconds: Double,
        val danger: Boolean,
        val point: Vec3,
        val span: Double,
    ) {
        /** One line for the chip: "TORNADO 3.2 km", "TSUNAMI IN 4 min", "QUAKE IN 20 s", "HURRICANE 120 km", "DUST 12 km". */
        val text: String = when (kind) {
            Kind.TSUNAMI -> if (seconds <= 0.0) "TSUNAMI" else "TSUNAMI IN ${minutes(seconds)}"
            Kind.QUAKE -> if (seconds <= 0.0) "QUAKE" else "QUAKE IN ${minutes(seconds)}"
            Kind.AFTERSHOCKS -> "AFTERSHOCKS"
            Kind.DUST -> if (distance <= 0.0) "DUST" else "DUST ${distanceText(distance)}"
            Kind.DUST_STORM -> "DUST STORM"
            Kind.BLIZZARD -> if (distance <= 0.0) "BLIZZARD" else "BLIZZARD ${distanceText(distance)}"
            Kind.HAIL -> if (distance <= 0.0) "HAIL" else "HAIL ${distanceText(distance)}"
            Kind.ASH -> if (distance <= 0.0) "ASH" else "ASH ${distanceText(distance)}"
            else -> "${kind.name} ${distanceText(distance)}"
        }

        /** Which event this is, for sounding it once: a quake and its aftershocks are one. */
        val event: Long get() = if (kind == Kind.QUAKE || kind == Kind.AFTERSHOCKS) id xor QUAKE_EVENT else id

        override fun equals(other: Any?): Boolean =
            other === this || (other is Warning && other.kind == kind && other.id == id && other.text == text && other.danger == danger)

        override fun hashCode(): Int = (kind.hashCode() * 31 + id.hashCode()) * 31 + text.hashCode()
    }

    /** No air: quakes only. */
    private val airless = body.atmosphere == null

    /** Off where the setting has no rare events, or there's neither air nor ground. */
    val active = config.intensity.rare > 0.0 && (!airless || body.terrain != null)

    /** Its own, for listing on a worker; a weather's caches aren't thread safe. */
    private val weather by lazy { Weather(body, config) }
    private val quakeModel by lazy { if (airless) Quakes.of(body, config) else weather.quakeModel }

    /** A listing's result and which place it was for (see [place]). */
    private class Found(val warning: Warning?, val place: Int)

    @Volatile private var found: Found? = null

    /**
     * Counts jumps: a different craft, or the same one moved more than [JUMP] in one update. A
     * result listed before the latest jump is for somewhere else, so it's dropped.
     */
    @Volatile private var place = 0

    /** The warning as last worked out for where the craft is now, or null for none. */
    val current: Warning? get() = found?.takeIf { it.place == place }?.warning

    /** How long the last listing took, in ms, for the perf log. */
    @Volatile var lastMillis = 0.0
        private set

    @Volatile private var listing = false
    private var listedNanos = Long.MIN_VALUE
    private var listedTime = Double.NEGATIVE_INFINITY
    private val lastAt = Vec3()
    private var lastFor = Long.MIN_VALUE
    private var jumped = false

    /** Works it out again when due, for craft [craft] at body-fixed [at] at world [time]. */
    fun update(at: Vec3, time: Double, craft: Long = 0L) {
        if (!active) { found = null; return }
        if (craft != lastFor || at.distanceTo(lastAt) > JUMP) { place++; jumped = true }
        lastFor = craft; lastAt.setTo(at)
        if (listing) return
        val now = com.rm.apogee.platform.System.nanoTime()
        if (!jumped && now - listedNanos < RELIST_NANOS && kotlin.math.abs(time - listedTime) < RELIST_WORLD_SECONDS) return
        jumped = false
        listedNanos = now; listedTime = time
        val from = at.copy()
        val forPlace = place
        val run = scope
        if (run == null) { found = Found(find(from, time), forPlace); return }
        listing = true
        run.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val started = com.rm.apogee.platform.System.nanoTime()
                val w = find(from, time)
                lastMillis = (com.rm.apogee.platform.System.nanoTime() - started) / 1e6
                if (forPlace == place) found = Found(w, forPlace)
            } finally {
                listing = false
            }
        }
    }

    private val tornadoes = ArrayList<LiveTornado>()
    private val tsunamis = ArrayList<LiveTsunami>()
    private val hurricanes = ArrayList<LiveHurricane>()
    private val quakes = ArrayList<LiveQuake>()
    private val shaken = Vec3()

    /** The most urgent warning at body-fixed [at] at [time]: any danger first, then tornado, quake, tsunami, hurricane, dust, blizzard, hail, ash, eruption, aftershocks. */
    fun find(at: Vec3, time: Double): Warning? = all(at, time).minWithOrNull(compareBy({ !it.danger }, { it.kind.rank }))

    /** The nearest or soonest of each kind worth a warning at body-fixed [at] at [time]. */
    fun all(at: Vec3, time: Double): List<Warning> {
        if (!active) return emptyList()
        val altitude = at.length - body.radius
        if (altitude > TOP) {
            // Ash clouds reach higher than the rest; warned of up to their top.
            if (altitude > ASH_CEILING || airless) return emptyList()
            return listOfNotNull(ash(at, at.copy().normalizeInPlace(), time, altitude))
        }
        val up = at.copy().normalizeInPlace()
        val found = ArrayList<Warning>(4)
        if (airless) return listOfNotNull(quake(up, altitude, time))

        tornadoes.clear()
        weather.tornadoes(up, time, TORNADO_WARN, tornadoes)
        tornadoes.filter { it.strength > 0.02 }.minByOrNull { arc(up, it.centre) }?.let { t ->
            val d = arc(up, t.centre)
            found += Warning(if (t.overWater) Kind.WATERSPOUT else Kind.TORNADO, t.id, d, 0.0, d < t.reach + TORNADO_DANGER,
                t.centre.copy(), maxOf(d, TORNADO_SPAN))
        }

        if (altitude < TSUNAMI_TOP && body.ocean != null) {
            tsunamis.clear()
            weather.tsunamis(up, time, Double.MAX_VALUE, tsunamis)
            var best: Warning? = null
            for (t in tsunamis) {
                if (t.arrivesHere.isNaN()) continue
                val wait = t.arrivesHere - time
                if (wait > TSUNAMI_LEAD || wait < -TSUNAMI_PASSING * t.period) continue
                val w = Warning(Kind.TSUNAMI, t.id, arc(up, t.origin), maxOf(wait, 0.0), wait < TSUNAMI_DANGER,
                    t.origin.copy(), arc(up, t.origin) * 1.2)
                if (best == null || w.seconds < best.seconds) best = w
            }
            best?.let { found += it }
        }

        quake(up, altitude, time)?.let { found += it }
        dust(at, up, altitude, time)?.let { found += it }
        blizzard(at, up, altitude, time)?.let { found += it }
        hail(at, up, altitude, time)?.let { found += it }
        ash(at, up, time, altitude)?.let { found += it }

        hurricanes.clear()
        weather.hurricanes(up, time, HURRICANE_WARN, hurricanes)
        hurricanes.filter { it.wet > 0.0 }.minByOrNull { arc(up, it.centre) }?.let { h ->
            val d = arc(up, h.centre)
            if (d < h.outerRadius + HURRICANE_WARN) {
                found += Warning(Kind.HURRICANE, h.id, d, 0.0, d < HURRICANE_DANGER * h.maxWindRadius,
                    h.centre.copy(), maxOf(d, h.outerRadius))
            }
        }
        return found
    }

    /**
     * A quake shaking unit [up] now (a danger), its waves on their way here, or aftershocks of one
     * still to come; nothing for craft over [QUAKE_TOP] above the ground or afloat.
     */
    private fun quake(up: Vec3, altitude: Double, time: Double): Warning? {
        // Live covers waves still on their way anywhere.
        val model = quakeModel
        if (!model.live(time) && model.aftershockDue(up, time, QUAKE_WARN) == null) return null
        val terrain = body.terrain ?: return null
        val ground = terrain.elevation(up)
        val above = altitude - ground
        if (above > QUAKE_TOP) return null
        if (terrain.hasOcean && ground < 0.0 && above > QUAKE_FLOOR) return null
        quakes.clear()
        model.near(up, time, 0.0, quakes)
        fun warning(q: LiveQuake, kind: Kind, seconds: Double, danger: Boolean): Warning {
            val d = arc(up, q.epicentre)
            return Warning(kind, q.mainId, d, seconds, danger, q.epicentre.copy(), maxOf(d * 1.3, QUAKE_SPAN))
        }
        model.shake(up, time, shaken)
        if (model.level() >= QUAKE_WARN) {
            // The one whose waves got here last.
            quakes.filter { !it.arrivesHere.isNaN() && it.arrivesHere <= time && it.peakHere >= QUAKE_WARN }.maxByOrNull { it.arrivesHere }
                ?.let { return warning(it, Kind.QUAKE, 0.0, true) }
        }
        quakes.filter { !it.arrivesHere.isNaN() && it.arrivesHere > time && it.peakHere >= QUAKE_WARN }.minByOrNull { it.arrivesHere }
            ?.let { return warning(it, Kind.QUAKE, it.arrivesHere - time, false) }
        return model.aftershockDue(up, time, QUAKE_WARN)?.let { warning(it, Kind.AFTERSHOCKS, 0.0, false) }
    }

    private val dusts = ArrayList<LiveDust>()
    private val air = AirSample()
    private val dustRight = Vec3()
    private val rel = Vec3()

    /**
     * A haboob whose front is heading this way within [DUST_WARN] (a danger once inside its
     * dust), or a world-wide dust storm over the craft; only under [DUST_TOP] over the ground.
     */
    private fun dust(at: Vec3, up: Vec3, altitude: Double, time: Double): Warning? {
        if (!weather.dustPossible) return null
        val terrain = body.terrain ?: return null
        val ground = kotlin.math.max(terrain.elevation(up), if (terrain.hasOcean) 0.0 else -1e9)
        if (altitude - ground > DUST_TOP) return null
        dusts.clear()
        weather.dustStorms(up, time, DUST_WARN, dusts)
        if (dusts.isEmpty()) return null
        weather.sample(at, time, air)
        var best: Warning? = null
        for (d in dusts) {
            if (d.strength < 0.05) continue
            if (d.kind == DustKind.WORLD) {
                if (air.dust >= DUST_THICK && best == null) best = Warning(Kind.DUST_STORM, d.id, 0.0, 0.0, false, d.centre.copy(), d.reach)
                continue
            }
            // Where the craft is from the middle of its front: ahead of it, or behind it in its dust.
            dustRight.setTo(d.heading).crossInPlace(d.centre).normalizeInPlace()
            rel.setTo(up).subInPlace(d.centre).mulInPlace(body.radius)
            val across = rel dot dustRight
            if (kotlin.math.abs(across) > d.halfWidth + DUST_SIDE) continue
            val q = (across / d.halfWidth).coerceIn(-1.0, 1.0)
            val ahead = (rel dot d.heading) + LiveDust.BOW * d.halfWidth * q * q
            val travelled = d.centre.distanceTo(d.origin) * body.radius
            if (ahead > DUST_WARN || ahead < -(travelled + 2_000.0)) continue
            val inside = ahead <= 0.0 && kotlin.math.abs(across) < d.halfWidth
            val danger = inside && air.dust >= DUST_THICK
            val w = Warning(Kind.DUST, d.id, if (inside) 0.0 else kotlin.math.max(ahead, 100.0), 0.0, danger, d.centre.copy(),
                kotlin.math.max(kotlin.math.abs(ahead) * 1.3, 2.5 * d.halfWidth))
            val b = best
            if (b == null || b.kind == Kind.DUST_STORM || (w.danger && !b.danger) || (w.danger == b.danger && w.distance < b.distance)) best = w
        }
        return best
    }

    private val blizzards = ArrayList<LiveBlizzard>()

    /**
     * A blizzard whose edge is within [BLIZZARD_WARN] and not going away (a danger once inside it in
     * thick snow), or thick snow lifted by the wind with none listed; only under [BLIZZARD_TOP] over
     * the ground.
     */
    private fun blizzard(at: Vec3, up: Vec3, altitude: Double, time: Double): Warning? {
        if (!weather.snowPossible) return null
        val terrain = body.terrain ?: return null
        val ground = kotlin.math.max(terrain.elevation(up), if (terrain.hasOcean) 0.0 else -1e9)
        if (altitude - ground > BLIZZARD_TOP) return null
        blizzards.clear()
        if (weather.blizzardsPossible) weather.blizzards(up, time, BLIZZARD_WARN, blizzards)
        weather.sample(at, time, air)
        var best: Warning? = null
        for (b in blizzards) {
            if (b.strength < 0.05) continue
            val q = b.inside(up, body.radius)
            val d = arc(up, b.centre)
            // To its edge along the way from its middle.
            val edge = if (q <= 1.0) 0.0 else d * (1.0 - 1.0 / q)
            if (edge > BLIZZARD_WARN) continue
            // One going away from the craft once past it isn't worth a warning.
            rel.setTo(up).subInPlace(b.centre)
            if (edge > 0.0 && (rel dot b.heading) * body.radius < -b.halfLength) continue
            val danger = edge <= 0.0 && air.snow >= SNOW_THICK
            val w = Warning(Kind.BLIZZARD, b.id, if (edge <= 0.0) 0.0 else kotlin.math.max(edge, 100.0), 0.0, danger, b.centre.copy(),
                kotlin.math.max(edge * 1.3, 2.5 * b.halfLength))
            val o = best
            if (o == null || (w.danger && !o.danger) || (w.danger == o.danger && w.distance < o.distance)) best = w
        }
        if (best == null && air.snow >= SNOW_THICK) {
            // Snow lifted off the ground by the wind, with no storm: one event per half hour.
            best = Warning(Kind.BLIZZARD, GROUND_BLIZZARD xor kotlin.math.floor(time / 1_800.0).toLong(), 0.0, 0.0, true, up.copy(), BLIZZARD_GROUND_SPAN)
        }
        return best
    }

    private val hails = ArrayList<LiveHail>()

    /**
     * The nearest hail shaft whose edge is within [HAIL_WARN] and not going away (a danger in
     * heavy hail at the craft); only under [HAIL_TOP] over the ground.
     */
    private fun hail(at: Vec3, up: Vec3, altitude: Double, time: Double): Warning? {
        if (!weather.hailPossible) return null
        val terrain = body.terrain ?: return null
        val ground = kotlin.math.max(terrain.elevation(up), if (terrain.hasOcean) 0.0 else -1e9)
        if (altitude - ground > HAIL_TOP) return null
        hails.clear()
        weather.hailShafts(up, time, HAIL_WARN + HAIL_REACH * MAX_SHAFT, hails)
        if (hails.isEmpty()) return null
        val here = weather.hailAt(up, altitude, time)
        var best: Warning? = null
        for (h in hails) {
            if (h.hail < HAIL_FROM) continue
            val d = arc(up, h.centre)
            // Where it hails at all, past which it's only rain.
            val reach = h.width * kotlin.math.sqrt(kotlin.math.ln(h.hail / HAIL_FROM))
            val edge = d - reach
            if (edge > HAIL_WARN) continue
            rel.setTo(up).subInPlace(h.centre)
            if (edge > 0.0 && (rel dot h.heading) * body.radius < -reach) continue
            val danger = edge <= 0.0 && here >= HAIL_HEAVY
            val w = Warning(Kind.HAIL, h.id, if (edge <= 0.0) 0.0 else kotlin.math.max(edge, 100.0), 0.0, danger, h.centre.copy(),
                kotlin.math.max(d * 1.3, HAIL_SPAN))
            val o = best
            if (o == null || (w.danger && !o.danger) || (w.danger == o.danger && w.distance < o.distance)) best = w
        }
        return best
    }

    private val eruptions = ArrayList<LiveEruption>()
    private val ashPlace = DoubleArray(2)

    /**
     * An ash cloud within [ASH_WARN] of the craft (a danger in ash [ASH_THICK] thick at the craft),
     * or with none that near, an erupting vent within [ERUPTION_WARN]; none for one whose top is
     * under [altitude].
     */
    private fun ash(at: Vec3, up: Vec3, time: Double, altitude: Double): Warning? {
        if (!weather.ashPossible || !weather.ashLive(time)) return null
        eruptions.clear()
        weather.eruptions(up, time, ERUPTION_WARN, eruptions)
        if (eruptions.isEmpty()) return null
        weather.sample(at, time, air)
        var best: Warning? = null
        for (e in eruptions) {
            if (altitude > kotlin.math.max(e.top, e.columnTop)) continue
            val toVent = arc(up, e.vent)
            val edge = ashEdge(e, up)
            val w = if (edge <= ASH_WARN) {
                val danger = edge <= 0.0 && air.ash >= ASH_THICK
                Warning(Kind.ASH, e.id, if (edge <= 0.0) 0.0 else kotlin.math.max(edge, 100.0), 0.0, danger, e.vent.copy(),
                    kotlin.math.max(toVent * 1.3, kotlin.math.max(e.length * 0.7, ASH_SPAN)))
            } else if (e.strength > 0.02 && toVent <= ERUPTION_WARN) {
                Warning(Kind.ERUPTION, e.id, kotlin.math.max(toVent, 100.0), 0.0, false, e.vent.copy(), kotlin.math.max(toVent * 1.3, ASH_SPAN))
            } else continue
            val o = best
            if (o == null || (w.danger && !o.danger) || (w.danger == o.danger && w.kind.rank < o.kind.rank) ||
                (w.danger == o.danger && w.kind == o.kind && w.distance < o.distance)) best = w
        }
        return best
    }

    /** How far unit [up] is from the edge of [e]'s ash cloud over the ground, metres; 0 inside. */
    private fun ashEdge(e: LiveEruption, up: Vec3): Double {
        if (e.length < 1.0) return arc(up, e.vent) - AshScene.START_WIDTH
        e.track.project(up, ashPlace)
        val along = ashPlace[0]
        val across = ashPlace[1]
        val start = AshScene.START_WIDTH
        val end = kotlin.math.max(e.halfWidth, start)
        // Once it stops, the cloud starts [LiveEruption.near] downwind, moving away from the vent.
        val near = e.near.coerceIn(0.0, e.length)
        fun width(s: Double) = start + (end - start) * s / e.length
        return when {
            near <= 0.0 && along < 0.0 -> kotlin.math.max(0.0, kotlin.math.sqrt(along * along + across * across) - start)
            along < near -> kotlin.math.sqrt((near - along).let { it * it } + kotlin.math.max(0.0, across - width(near)).let { it * it })
            along > e.length -> kotlin.math.sqrt((along - e.length).let { it * it } + kotlin.math.max(0.0, across - end).let { it * it })
            else -> kotlin.math.max(0.0, across - width(along))
        }
    }

    private val cross = Vec3()

    /** The distance over the ground between unit [a] and unit [b], in metres. */
    private fun arc(a: Vec3, b: Vec3): Double =
        kotlin.math.atan2(cross.setTo(a).crossInPlace(b).length, a dot b) * body.radius

    companion object {
        private val Kind.rank: Int get() = when (this) {
            Kind.TORNADO, Kind.WATERSPOUT -> 0
            Kind.QUAKE -> 1
            Kind.TSUNAMI -> 2
            Kind.HURRICANE -> 3
            Kind.DUST -> 4
            Kind.BLIZZARD -> 5
            Kind.HAIL -> 6
            Kind.ASH -> 7
            Kind.ERUPTION -> 8
            Kind.AFTERSHOCKS -> 9
            Kind.DUST_STORM -> 10
        }

        /** Mixed into a quake's id for [Warning.event], apart from the other kinds' ids. */
        private const val QUAKE_EVENT = 0x51A4E5L shl 40

        /** A quake's warned of where it shakes the ground over this, m/s^2 (craft parked there wake). */
        const val QUAKE_WARN = 0.3
        /** Only this close over the ground, in metres, and on the sea only this near the floor. */
        const val QUAKE_TOP = 1_000.0
        const val QUAKE_FLOOR = 30.0
        private const val QUAKE_SPAN = 20_000.0

        /** A haboob's front is warned of this far off, in metres, and up to this far past its side. */
        const val DUST_WARN = 40_000.0
        const val DUST_SIDE = 5_000.0
        /** Only this close over the ground, in metres, and dust this thick at the craft is a danger in a haboob. */
        const val DUST_TOP = 3_000.0
        const val DUST_THICK = 0.3

        /** A blizzard's edge is warned of this far off, in metres, only this close over the ground, and snow this thick at the craft is a danger. */
        const val BLIZZARD_WARN = 30_000.0
        const val BLIZZARD_TOP = 3_000.0
        const val SNOW_THICK = 0.3
        private const val BLIZZARD_GROUND_SPAN = 20_000.0
        /** Mixed into the half hour for a ground blizzard's id, apart from storms' ids. */
        private const val GROUND_BLIZZARD = 0x5A0E5L shl 40

        /**
         * A hail shaft's edge, where it hails [HAIL_FROM], is warned of this far off, in metres, only
         * this close over the ground, and hail this hard at the craft is a danger.
         */
        const val HAIL_WARN = 10_000.0
        const val HAIL_TOP = 3_000.0
        const val HAIL_FROM = 0.1
        const val HAIL_HEAVY = 0.4
        private const val HAIL_SPAN = 12_000.0
        /** The widest a shaft's hail reaches past its middle, in shaft widths, and the widest shaft, in metres. */
        private const val HAIL_REACH = 1.6
        private const val MAX_SHAFT = 6_000.0

        /** An ash cloud's edge is warned of this far off, in metres, an erupting vent this far, and ash this thick at the craft is a danger. */
        const val ASH_WARN = 50_000.0
        const val ERUPTION_WARN = 100_000.0
        const val ASH_THICK = 0.1
        private const val ASH_SPAN = 30_000.0

        /** Nothing above this, in metres over the datum. */
        const val TOP = 15_000.0
        /** And nothing for ash above this (higher than any ash top can be). */
        const val ASH_CEILING = 30_000.0

        /** A tornado's warned of within this of its wind's reach, and is a danger within [TORNADO_DANGER]. */
        const val TORNADO_WARN = 15_000.0
        const val TORNADO_DANGER = 1_000.0
        private const val TORNADO_SPAN = 8_000.0

        /** A tsunami's warned of this many seconds ahead, and is a danger within [TSUNAMI_DANGER]. */
        const val TSUNAMI_LEAD = 1_800.0
        const val TSUNAMI_DANGER = 300.0
        /** How many periods after it arrives it still shows, while it's passing. */
        const val TSUNAMI_PASSING = 3.0
        /** Only near the sea, in metres over the datum. */
        const val TSUNAMI_TOP = 1_000.0

        /** A hurricane's warned of within this of its outer edge, and is a danger inside this many eyewall radii. */
        const val HURRICANE_WARN = 150_000.0
        const val HURRICANE_DANGER = 2.0

        /** Moved further than this in one update, in metres, the craft has jumped: relisted at once. */
        const val JUMP = 3_000.0

        private const val RELIST_NANOS = 1_000_000_000L
        private const val RELIST_WORLD_SECONDS = 30.0

        /** "800 m", "3.2 km", "120 km". */
        fun distanceText(metres: Double): String = when {
            metres < 1_000.0 -> "${(metres / 100.0).roundToInt().coerceAtLeast(1) * 100} m"
            metres < 9_950.0 -> {
                val tenths = (metres / 100.0).roundToInt()
                "${tenths / 10}.${tenths % 10} km"
            }
            else -> "${(metres / 1_000.0).roundToInt()} km"
        }

        /** "40 s", "4 min". */
        fun minutes(seconds: Double): String =
            if (seconds < 60.0) "${(seconds / 10.0).roundToInt().coerceAtLeast(1) * 10} s"
            else "${kotlin.math.ceil(seconds / 60.0).toInt()} min"
    }
}
