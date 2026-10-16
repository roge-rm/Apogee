package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.weather.LiveHurricane
import com.rm.apogee.core.weather.LiveTornado
import com.rm.apogee.core.weather.LiveTsunami
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * The one hurricane, tornado or tsunami most worth a warning at a place, worked out from the
 * weather model, so a replica gets the same as the server with nothing sent.
 *
 * Listed off the frame thread about once a second, or sooner when warped.
 */
class WeatherWarnings(
    private val body: CelestialBody,
    private val config: WeatherConfig,
    private val scope: kotlinx.coroutines.CoroutineScope?,
) {
    enum class Kind { TORNADO, WATERSPOUT, TSUNAMI, HURRICANE }

    /**
     * One warning. [distance] is metres to a hurricane's eye or a tornado's foot; for a tsunami,
     * [seconds] until it gets here, 0 while it's passing. [point] is where to look on the map, unit,
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
        /** One line for the chip: "TORNADO 3.2 km", "TSUNAMI IN 4 min", "HURRICANE 120 km". */
        val text: String = when (kind) {
            Kind.TSUNAMI -> if (seconds <= 0.0) "TSUNAMI" else "TSUNAMI IN ${minutes(seconds)}"
            else -> "${kind.name} ${distanceText(distance)}"
        }

        override fun equals(other: Any?): Boolean =
            other === this || (other is Warning && other.kind == kind && other.id == id && other.text == text && other.danger == danger)

        override fun hashCode(): Int = (kind.hashCode() * 31 + id.hashCode()) * 31 + text.hashCode()
    }

    /** Off where the setting has no rare events or there's no air. */
    val active = config.intensity.rare > 0.0 && body.atmosphere != null

    /** Its own, for listing on a worker; a weather's caches aren't thread safe. */
    private val weather by lazy { Weather(body, config) }

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

    /** The most urgent warning at body-fixed [at] at [time]: any danger first, then tornado, tsunami, hurricane. */
    fun find(at: Vec3, time: Double): Warning? = all(at, time).minWithOrNull(compareBy({ !it.danger }, { it.kind.rank }))

    /** The nearest or soonest of each kind worth a warning at body-fixed [at] at [time]. */
    fun all(at: Vec3, time: Double): List<Warning> {
        if (!active) return emptyList()
        val altitude = at.length - body.radius
        if (altitude > TOP) return emptyList()
        val up = at.copy().normalizeInPlace()
        val found = ArrayList<Warning>(4)

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

    private val cross = Vec3()

    /** The distance over the ground between unit [a] and unit [b], in metres. */
    private fun arc(a: Vec3, b: Vec3): Double =
        kotlin.math.atan2(cross.setTo(a).crossInPlace(b).length, a dot b) * body.radius

    companion object {
        private val Kind.rank: Int get() = when (this) {
            Kind.TORNADO, Kind.WATERSPOUT -> 0
            Kind.TSUNAMI -> 1
            Kind.HURRICANE -> 2
        }

        /** Nothing above this, in metres over the datum. */
        const val TOP = 15_000.0

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
