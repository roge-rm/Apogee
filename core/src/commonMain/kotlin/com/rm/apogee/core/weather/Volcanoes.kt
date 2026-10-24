package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.sea.DetMath
import com.rm.apogee.core.terrain.Noise
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One eruption as it is at one moment, for drawing, the map and warnings. */
class LiveEruption(
    /** Stable for its whole life. */
    val id: Long,
    /** Unit, body-fixed: the vent. */
    val vent: Vec3,
    /** The vent's height over datum, metres. */
    val ventHeight: Double,
    /** When it began, world seconds, and seconds since. */
    val start: Double,
    val age: Double,
    /** Seconds from its start until its last ash has fallen out. */
    val life: Double,
    /** Seconds from its start until it stops giving out ash. */
    val emitting: Double,
    /** How hard it's erupting now, 0..1. */
    val strength: Double,
    /** How high its column reaches now, and its cloud at most, metres over datum. */
    val columnTop: Double,
    val top: Double,
    /** Half the column's width, metres. */
    val columnRadius: Double,
    /** Body-fixed, tangent at the vent, m/s: what carries its cloud. */
    val drift: Vec3,
    /** How far downwind its cloud reaches now, and half its width there, metres. */
    val length: Double,
    val halfWidth: Double,
    /** How far downwind its cloud starts, metres: 0 while it erupts, then moving away. */
    val near: Double = 0.0,
    /** The way its cloud goes over the ground, bending with the wind; [length] and [near] are along it. */
    val track: AshTrack,
)

/**
 * Volcanic ash: eruptions at a world's volcanoes, each a column over its vent and a cloud carried
 * downwind.
 *
 * Up to [SLOTS] eruptions per world per [CYCLE], each at [CHANCE] x [WeatherIntensity.rare] x how
 * hot the world is (Caligo twice Terra, Rubra a quarter). On Terra at real volcanoes (a small
 * table), on Caligo and Rubra at their great shields. Each builds over [BUILD_SHORTEST] to
 * [BUILD_LONGEST], gives out ash for a few hours, dies away over an hour or two, and its ash
 * lingers for up to [MAX_AGE] after it was given out. Fewer and weaker near launch sites.
 *
 * The cloud drifts with the mean of the wind at its height over the eruption's life, worked out
 * once per eruption along an [AshTrack] that bends with that wind on its way: ash given out at
 * time te is [drift] x (t - te) along the track from the vent, widening
 * at [SPREAD], thinning as it widens, settling at [SETTLE] and falling out over [FALLOUT]; some
 * falls under the cloud to the ground. All hashed from the seed and the time, so nothing is stored.
 *
 * Only where there's air and ground. Not thread-safe: one per [Weather].
 */
internal class Volcanoes(
    private val weather: Weather,
    private val bodyRadius: Double,
    seed: Int,
    intensity: WeatherIntensity,
) {
    private val climate = weather.climate
    private val terrain = weather.body.terrain
    private val bodyId = weather.body.id
    private val seed = seed + 0xA54

    private val vents: List<Vent> = if (terrain == null || !climate.ground) emptyList() else ventsOf(bodyId)
    private val totalWeight = vents.sumOf { it.weight }

    /** Eruptions at all, at this setting. */
    val active = intensity.rare > 0.0 && vents.isNotEmpty() && climate.circulation != Climate.Circulation.STILL

    private val chance = (CHANCE * intensity.rare * heatOf(bodyId)).coerceAtMost(0.95)

    /** Where each cycle starts, in seconds before a whole number of [CYCLE]s. */
    private val phase = Noise.hash(this.seed + 401, 0, 0, 0) * CYCLE

    private class Vent(val up: Vec3, val weight: Double) {
        var height = 0.0
        /** 1 with a launch site close by, fading to 0. */
        var nearSite = 0.0
        var ready = false
    }

    private class Eruption {
        var exists = false
        var id = 0L
        var ventIndex = -1
        val vent = Vec3()
        var ventHeight = 0.0
        var start = 0.0
        var build = 0.0
        /** Full until here, then dying away until [stop], seconds after [start]. */
        var full = 0.0
        var stop = 0.0
        var strength = 0.0
        var top = 0.0
        var columnRadius = 0.0
        var width = 0.0
        /** Unit tangent at the vent along the drift. */
        val along = Vec3()
        var speed = 0.0
        /** The way its cloud goes, from [along] at the vent. */
        var track: AshTrack? = null
        /** World seconds past which none of it is left anywhere. */
        var end = 0.0
        /** Unit-sphere chord past which it can't reach, at its oldest. */
        var reachChord = 0.0
    }

    private class Cycle {
        var cycle = Long.MIN_VALUE
        val eruptions = Array(SLOTS) { Eruption() }
    }

    private val cache = Array(WAYS) { Cycle() }
    private val forced = ArrayList<Eruption>()
    private val wind = Vec3()
    private val sum = Vec3()

    private val sites: List<Vec3> by lazy {
        val out = ArrayList<Vec3>()
        for (site in com.rm.apogee.core.world.World.launchSites) {
            if (site.bodyId != bodyId) continue
            val c = DetMath.cos(site.latitude)
            out.add(Vec3(c * DetMath.cos(site.longitude), DetMath.sin(site.latitude), c * DetMath.sin(site.longitude)))
        }
        out
    }

    private fun ready(v: Vent): Vent {
        if (v.ready) return v
        v.height = terrain?.elevation(v.up) ?: 0.0
        var most = 0.0
        for (d in sites) most = max(most, 1.0 - smooth(SITE_NEAR, SITE_FAR, d.distanceTo(v.up) * bodyRadius))
        v.nearSite = most
        v.ready = true
        return v
    }

    private fun newest(time: Double): Long = floor((time + phase) / CYCLE).toLong()

    private fun cycleOf(c: Long): Cycle {
        val slot = cache[c.mod(WAYS.toLong()).toInt()]
        if (slot.cycle != c) build(slot, c)
        return slot
    }

    private fun build(cy: Cycle, cycle: Long) {
        cy.cycle = cycle
        for (e in cy.eruptions) e.exists = false
        if (!active) return
        val c = cycle.toInt()
        for (slot in 0 until SLOTS) {
            fun h(k: Int) = Noise.hash(seed + k, c, slot, 0)
            if (h(1) >= chance) continue
            // A vent by how active it is; one eruption per vent a cycle.
            val pick = h(2) * totalWeight
            var i = 0
            var acc = vents[0].weight
            while (i < vents.size - 1 && acc < pick) { i++; acc += vents[i].weight }
            var taken = false
            for (k in 0 until slot) if (cy.eruptions[k].exists && cy.eruptions[k].ventIndex == i) taken = true
            if (taken) continue
            val v = ready(vents[i])
            if (h(3) < SITE_SKIP * v.nearSite) continue
            val e = cy.eruptions[slot]
            val u = h(4)
            val strength = (STRENGTH_LEAST + (1.0 - STRENGTH_LEAST) * u * sqrt(u)) * (1.0 - SITE_SHY * v.nearSite * h(5))
            val start = cycle * CYCLE - phase + h(6) * CYCLE
            e.ventIndex = i
            fill(e, cycle * ID_STRIDE + slot, v.up, v.height, start, strength,
                BUILD_SHORTEST + (BUILD_LONGEST - BUILD_SHORTEST) * h(7),
                FULL_SHORTEST + (FULL_LONGEST - FULL_SHORTEST) * h(8),
                DECAY_SHORTEST + (DECAY_LONGEST - DECAY_SHORTEST) * h(9), h(10), null)
        }
    }

    /** Sets [e] up, its drift the mean wind at its cloud's height over its life, or [drift] if given. */
    private fun fill(e: Eruption, id: Long, vent: Vec3, ventHeight: Double, start: Double, strength: Double,
                     build: Double, full: Double, decay: Double, turn: Double, drift: Vec3?) {
        e.exists = true
        e.id = id
        e.vent.setTo(vent).normalizeInPlace()
        e.ventHeight = max(ventHeight, 0.0)
        e.start = start
        e.build = build
        e.full = build + full
        e.stop = build + full + decay
        e.strength = strength
        e.top = min(e.ventHeight + TOP_LEAST + (TOP_MOST - TOP_LEAST) * strength, climate.ceiling - 3_000.0)
        e.columnRadius = COLUMN_LEAST + (COLUMN_MOST - COLUMN_LEAST) * strength
        e.width = WIDTH_LEAST + (WIDTH_MOST - WIDTH_LEAST) * strength
        val height = e.top - 0.25 * (e.top - e.ventHeight)
        if (drift != null) sum.setTo(drift) else {
            sum.setZero()
            for (k in 0 until 3) sum.addInPlace(weather.ashWind(e.vent, start + 0.5 * k * e.stop, height, wind))
            sum.mulInPlace(1.0 / 3.0)
        }
        sum.addScaledInPlace(e.vent, -(sum dot e.vent))
        val speed = sum.length
        if (speed > 1e-3) e.along.setTo(sum).mulInPlace(1.0 / speed) else {
            // Still air: any way, from the hash.
            val east = Vec3(); val north = Vec3()
            frame(e.vent, east, north)
            val sc = DetMath.SinCos()
            DetMath.sinCos(2.0 * kotlin.math.PI * turn, sc)
            e.along.setTo(east).mulInPlace(sc.cos).addScaledInPlace(north, sc.sin)
        }
        e.speed = speed.coerceIn(SLOWEST, FASTEST)
        // Bending with the wind met on the way, as the ash given out over its life meets it.
        val step = e.speed * MAX_AGE / (AshTrack.SEGMENTS - 1)
        e.track = AshTrack.trace(e.vent, e.along, step, bodyRadius) { p, s, out ->
            if (drift != null) null else {
                out.setZero()
                val late = s / e.speed
                for (k in 0 until 3) out.addInPlace(weather.ashWind(p, start + 0.5 * k * e.stop + late, height, wind))
                out
            }
        }
        e.end = start + e.stop + MAX_AGE
        val far = e.speed * (e.stop + MAX_AGE) + e.width + SPREAD * MAX_AGE + e.columnRadius
        e.reachChord = min(2.0, far / bodyRadius * 1.02 + 1e-6)
    }

    /** How much ash it gives out at [s] seconds after its start, 0..1, before its strength. */
    private fun output(e: Eruption, s: Double): Double {
        if (s <= 0.0 || s >= e.stop) return 0.0
        return smooth(0.0, e.build, s) * (1.0 - smooth(e.full, e.stop, s))
    }

    private inline fun each(from: Long, to: Long, body: (Eruption) -> Unit) {
        if (forced.isNotEmpty()) {
            for (e in forced) body(e)
            return
        }
        if (!active) return
        for (c in from..to) for (e in cycleOf(c).eruptions) if (e.exists) body(e)
    }

    // What one eruption gives at a point.
    private var hereAsh = 0.0
    private var hereTop = 0.0
    private var hereColumn = 0.0
    private val placed = DoubleArray(2)

    /**
     * Ash at unit [up], [altitude] metres over datum, at [time], from [e]: into [hereAsh],
     * [hereTop] (the top of the ash over this point) and [hereColumn] (the column's share).
     */
    private fun at(e: Eruption, up: Vec3, altitude: Double, time: Double) {
        hereAsh = 0.0; hereTop = 0.0; hereColumn = 0.0
        val chord = up.distanceTo(e.vent)
        if (chord > e.reachChord) return
        val age = time - e.start
        e.track!!.project(up, placed)
        val along = placed[0]
        val cross = placed[1]

        // The column over the vent, rising as it builds.
        val now = output(e, age)
        if (now > 0.0) {
            val d = chord * bodyRadius
            val columnTop = e.ventHeight + (e.top - e.ventHeight) * smooth(0.0, e.build, age)
            val inside = (1.0 - smooth(0.5 * e.columnRadius, e.columnRadius, d)) *
                (1.0 - smooth(columnTop - COLUMN_CAP, columnTop, altitude))
            if (inside > 0.0) {
                hereColumn = e.strength * now * inside
                hereTop = columnTop
            }
        }

        // The cloud: what was given out a = s / speed seconds ago, now s metres downwind.
        val s = max(along, 0.0)
        val behind = min(along, 0.0)
        val a = s / e.speed
        var cloud = 0.0
        if (a < MAX_AGE) {
            val given = output(e, age - a)
            if (given > 0.0) {
                val width = e.width + SPREAD * a
                val off = sqrt(cross * cross + behind * behind)
                val lateral = 1.0 - smooth(0.5 * width, width, off)
                if (lateral > 0.0) {
                    val left = sqrt(e.width / width) * DetMath.exp(-a / FALLOUT) * (1.0 - smooth(0.7 * MAX_AGE, MAX_AGE, a))
                    // A layer settling as it goes, and ash falling out of it to the ground.
                    val depth = DEPTH * (e.top - e.ventHeight)
                    val top = e.top - SETTLE * a
                    val base = top - depth
                    val edge = EDGE * depth
                    val layer = smooth(base - edge, base + edge, altitude) * (1.0 - smooth(top - edge, top + edge, altitude))
                    val fallen = FALLING * smooth(0.0, FALL_SOFT, a * FALL_SPEED - (base - altitude)) *
                        (1.0 - smooth(base - edge, base + edge, altitude))
                    cloud = e.strength * given * lateral * left * max(layer, fallen)
                    if (cloud > 0.0) hereTop = max(hereTop, top + edge)
                }
            }
        }
        hereAsh = 1.0 - (1.0 - hereColumn) * (1.0 - cloud)
    }

    /**
     * Adds the ash at unit [up], [altitude] metres over datum, at [time] into [out]: its
     * thickness, top, sight range and the column's rough air.
     */
    fun apply(up: Vec3, altitude: Double, time: Double, out: AirSample) {
        if (!active && forced.isEmpty()) return
        var clear = 1.0
        var top = 0.0
        var column = 0.0
        val newest = newest(time)
        each(newest - LOOK_BACK, newest) { e ->
            if (time < e.start || time >= e.end) return@each
            at(e, up, altitude, time)
            if (hereAsh <= 0.0) return@each
            clear *= 1.0 - hereAsh
            top = max(top, hereTop)
            column = max(column, hereColumn)
        }
        val ash = 1.0 - clear
        if (ash <= 0.0) return
        out.ash = max(out.ash, ash)
        out.ashTop = max(out.ashTop, top)
        out.turbulence += COLUMN_ROUGH * column
        out.visibility = min(out.visibility, ASH_SIGHT / ash)
    }

    /**
     * The eruptions alive at [time] whose ash may come within [reach] metres of unit [up], into
     * [out]. Makes new objects, so not for every sample.
     */
    fun near(up: Vec3, time: Double, reach: Double, out: MutableList<LiveEruption>) {
        val newest = newest(time)
        each(newest - LOOK_BACK, newest) { e ->
            if (time < e.start || time >= e.end) return@each
            if ((up.distanceTo(e.vent) - e.reachChord) * bodyRadius > reach) return@each
            val age = time - e.start
            val columnTop = e.ventHeight + (e.top - e.ventHeight) * smooth(0.0, e.build, age)
            // From the oldest ash still about to the newest.
            val oldest = min(age, MAX_AGE)
            val newestAsh = max(0.0, age - e.stop)
            val length = if (newestAsh >= MAX_AGE) 0.0 else e.speed * oldest
            out.add(LiveEruption(e.id, e.vent.copy(), e.ventHeight, e.start, age, e.stop + MAX_AGE, e.stop,
                e.strength * output(e, age), columnTop, e.top, e.columnRadius,
                Vec3().setTo(e.along).mulInPlace(e.speed), length, e.width + SPREAD * oldest, min(length, e.speed * newestAsh), e.track!!))
        }
    }

    /** Whether any eruption is giving out or carrying ash at [time]. Quick. */
    fun live(time: Double): Boolean {
        if (!active && forced.isEmpty()) return false
        val newest = newest(time)
        each(newest - LOOK_BACK, newest) { if (time >= it.start && time < it.end) return true }
        return false
    }

    /**
     * For tests: one eruption at unit [vent] from [start] in place of all the hashed ones, or with
     * [also] beside those already placed; its cloud carried by [drift] (tangent, m/s) if given.
     */
    fun force(vent: Vec3, start: Double, strength: Double, drift: Vec3? = null, also: Boolean = false) {
        if (!also) forced.clear()
        val e = Eruption()
        val up = vent.copy().normalizeInPlace()
        fill(e, -1L - forced.size, up, terrain?.elevation(up) ?: 0.0, start, strength,
            1_800.0, 3 * 3_600.0, 3_600.0, 0.0, drift)
        forced += e
    }

    /** The eruptions of [cycle]: for tests. */
    internal fun eruptionsOf(cycle: Long): List<LiveEruption> {
        val cy = cycleOf(cycle)
        val out = ArrayList<LiveEruption>()
        for (e in cy.eruptions) if (e.exists) out.add(LiveEruption(e.id, e.vent.copy(), e.ventHeight, e.start, 0.0,
            e.stop + MAX_AGE, e.stop, e.strength, e.ventHeight, e.top, e.columnRadius,
            Vec3().setTo(e.along).mulInPlace(e.speed), 0.0, e.width, track = e.track!!))
        return out
    }

    companion object {
        /** Eruptions per world per this many seconds at most, each at [CHANCE] at rare 1 on Terra. */
        const val CYCLE = 6.0 * 3_600.0
        const val SLOTS = 2
        const val CHANCE = 0.5
        private const val WAYS = 4
        private const val ID_STRIDE = 8L
        /** Cycles back an eruption's ash can still be about: [CYCLE] x this past its longest life. */
        private const val LOOK_BACK = 2L

        /** How hard, most near the bottom (u^1.5). */
        const val STRENGTH_LEAST = 0.45

        /** Seconds it builds, then gives out ash in full, then dies away. */
        const val BUILD_SHORTEST = 1_200.0
        const val BUILD_LONGEST = 2_400.0
        const val FULL_SHORTEST = 2.0 * 3_600.0
        const val FULL_LONGEST = 5.0 * 3_600.0
        const val DECAY_SHORTEST = 3_600.0
        const val DECAY_LONGEST = 2.0 * 3_600.0

        /** Its cloud's top over the vent, metres, from the weakest to the strongest. */
        const val TOP_LEAST = 7_000.0
        const val TOP_MOST = 16_000.0
        /** The column's half width, and its top's soft edge, metres. */
        const val COLUMN_LEAST = 1_200.0
        const val COLUMN_MOST = 2_500.0
        private const val COLUMN_CAP = 1_500.0
        /** Rough air in the column at full ash. */
        const val COLUMN_ROUGH = 0.6

        /** The cloud's half width at the vent, metres, and how fast it widens, m/s. */
        const val WIDTH_LEAST = 3_000.0
        const val WIDTH_MOST = 6_000.0
        const val SPREAD = 1.5
        /** How deep the cloud is as a share of the column, and its soft edges as a share of that. */
        const val DEPTH = 0.45
        private const val EDGE = 0.15
        /** How fast the cloud settles, m/s, and the share of its ash falling under it, at [FALL_SPEED]. */
        const val SETTLE = 0.25
        const val FALLING = 0.35
        const val FALL_SPEED = 3.0
        private const val FALL_SOFT = 2_000.0
        /** Ash falls out to 1/e over this many seconds, and is gone by [MAX_AGE]. */
        const val FALLOUT = 2.0 * 3_600.0
        const val MAX_AGE = 3.0 * 3_600.0

        /** The drift's speed is kept within these, m/s. */
        const val SLOWEST = 3.0
        const val FASTEST = 35.0

        /** How far you see in ash at full thickness, metres. */
        const val ASH_SIGHT = 100.0

        /** Near a launch site: up to this share don't happen and the rest up to [SITE_SHY] weaker. */
        const val SITE_SKIP = 0.5
        const val SITE_SHY = 0.4
        const val SITE_NEAR = 30_000.0
        const val SITE_FAR = 90_000.0

        /** A jet's thrust fades from [JET_FROM] ash and it's flamed out by [JET_OUT]. */
        const val JET_FROM = 0.1
        const val JET_OUT = 0.6
        /** A propeller or rotor on fuel loses up to [PROP_LOSS] of its push from [PROP_FROM] to [PROP_FULL] ash. */
        const val PROP_FROM = 0.1
        const val PROP_FULL = 0.8
        const val PROP_LOSS = 0.6

        /** The share of a jet's thrust left in [ash]. */
        fun jetLeft(ash: Double): Double = 1.0 - smooth(JET_FROM, JET_OUT, ash)

        /** The share of a fuel-driven propeller's or rotor's push left in [ash]. */
        fun propLeft(ash: Double): Double = 1.0 - PROP_LOSS * smooth(PROP_FROM, PROP_FULL, ash)

        /** How often a world erupts against Terra. */
        private fun heatOf(bodyId: String): Double = when (bodyId) {
            SolarSystem.HOMEWORLD_ID -> 1.0
            "caligo" -> 2.0
            "rubra" -> 0.25
            else -> 0.0
        }

        private fun unit(lat: Double, lon: Double): Vec3 {
            val la = lat * kotlin.math.PI / 180.0; val lo = lon * kotlin.math.PI / 180.0
            val c = DetMath.cos(la)
            return Vec3(c * DetMath.cos(lo), DetMath.sin(la), c * DetMath.sin(lo))
        }

        private fun vent(unit: DoubleArray, weight: Double) = Vent(Vec3(unit[0], unit[1], unit[2]).normalizeInPlace(), weight)

        private fun ventsOf(bodyId: String): List<Vent> = when (bodyId) {
            SolarSystem.HOMEWORLD_ID -> TERRA.map { Vent(unit(it[0], it[1]), it[2]) }
            // The first has the lava lake.
            "caligo" -> com.rm.apogee.core.terrain.CaligoLand.VOLCANOES.mapIndexed { i, v -> vent(v, if (i == 0) 1.5 else 0.8) }
            "rubra" -> listOf(vent(com.rm.apogee.core.terrain.RubraLand.GREAT_MOUNT, 1.0)) +
                com.rm.apogee.core.terrain.RubraLand.THE_THREE.map { vent(it, 0.6) }
            else -> emptyList()
        }

        /**
         * Terra's volcanoes that throw up ash: latitude, longitude in degrees, and how active.
         * Mount Cameroon is the Cape's own, about 40 km off.
         */
        private val TERRA = listOf(
            doubleArrayOf(46.20, -122.18, 0.6),  // Mount St. Helens
            doubleArrayOf(60.49, -152.74, 0.6),  // Redoubt
            doubleArrayOf(54.76, -163.97, 0.5),  // Shishaldin
            doubleArrayOf(19.02, -98.62, 1.0),   // Popocatepetl
            doubleArrayOf(19.51, -103.62, 0.8),  // Colima
            doubleArrayOf(14.47, -90.88, 1.0),   // Fuego
            doubleArrayOf(4.89, -75.32, 0.6),    // Nevado del Ruiz
            doubleArrayOf(-0.68, -78.44, 0.8),   // Cotopaxi
            doubleArrayOf(-15.79, -71.86, 0.6),  // Sabancaya
            doubleArrayOf(-39.42, -71.93, 0.7),  // Villarrica
            doubleArrayOf(63.63, -19.62, 0.6),   // Eyjafjallajokull
            doubleArrayOf(37.75, 14.99, 1.0),    // Etna
            doubleArrayOf(40.82, 14.43, 0.3),    // Vesuvius
            doubleArrayOf(4.20, 9.17, 0.4),      // Mount Cameroon
            doubleArrayOf(-7.54, 110.45, 1.0),   // Merapi
            doubleArrayOf(-8.11, 112.92, 0.7),   // Semeru
            doubleArrayOf(15.13, 120.35, 0.5),   // Pinatubo
            doubleArrayOf(13.26, 123.69, 0.7),   // Mayon
            doubleArrayOf(31.58, 130.66, 1.0),   // Sakurajima
            doubleArrayOf(36.40, 138.53, 0.5),   // Asama
            doubleArrayOf(56.06, 160.64, 1.0),   // Klyuchevskoy
            doubleArrayOf(-39.28, 175.57, 0.5),  // Ruapehu
            doubleArrayOf(-77.53, 167.15, 0.3),  // Erebus
        )
    }
}
