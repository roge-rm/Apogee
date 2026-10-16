package com.rm.apogee.core.weather

import com.rm.apogee.core.concurrentMapOf
import com.rm.apogee.core.guarded
import com.rm.apogee.core.math.StrictMath
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.sea.DetMath
import com.rm.apogee.core.sea.Lattice
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.terrain.CubeSphere
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.terrain.Terrain
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One tsunami as it is at one moment, for drawing, the map and warnings. */
class LiveTsunami(
    /** Stable for its whole life. */
    val id: Long,
    /** Unit, body-fixed: where the quake was, on the sea floor. */
    val origin: Vec3,
    /** When the quake was, in world seconds. */
    val start: Double,
    /** How big it is, 0..1, with the setting's size in. At 1 it reaches [Tsunamis.COAST_CAP] on a coast. */
    val strength: Double,
    /** Seconds between crests. */
    val period: Double,
    /** Seconds since the quake. */
    val age: Double,
    /** How far its leading edge has got from the origin across the sea, in metres, at its furthest. */
    val frontRadius: Double,
    /** When it reaches the place asked about, in world seconds, or NaN if it never does. */
    val arrivesHere: Double,
)

/**
 * Tsunamis, on a world with a sea, when the setting has rare events.
 *
 * At most one quake per world per [CYCLE], with a chance scaled by [WeatherIntensity.rare], on deep
 * sea floor (steep floor preferred). The wave front spreads at sqrt(g * depth): arrival times are
 * worked out once per quake on a coarse cube-sphere [Grid] (Dijkstra, land blocked) shared by every
 * instance, and read between its nodes.
 *
 * At a place it reached, the sea first draws back, then a few crests come in, [period][LiveTsunami.period]
 * apart, dying away over about an hour. Small in deep water and spreading out from the origin, it
 * grows on the shelf by Green's law (depth^-1/4) up to [COAST_CAP]. Low land within reach of the sea
 * floods up to a height that falls off inland, and drains as the crest passes. Its flow carries
 * floating craft in and back out ([height] then [flow]).
 *
 * Not thread-safe: one per [Weather]. The grid and arrival times are shared and made once.
 */
internal class Tsunamis(
    weather: Weather,
    private val bodyRadius: Double,
    seed: Int,
    intensity: WeatherIntensity,
) {
    private val terrain: Terrain? = weather.body.terrain
    private val bodyId = weather.body.id
    private val g = weather.body.gravitationalParameter / (bodyRadius * bodyRadius)
    private val seed = seed + (bodyRadius / 1_000.0).toInt() * 7

    /** Whether this world has tsunamis at all, at this setting. */
    val active = intensity.rare > 0.0 && terrain?.hasOcean == true

    private val chance = (CHANCE * intensity.rare).coerceAtMost(0.95)

    /** How big they get here: [SIZE_WILD] on Wild up to 1 on Extreme. */
    private val size = SIZE_WILD + (1.0 - SIZE_WILD) * ((intensity.rare - 0.1) / 0.9).coerceIn(0.0, 1.0)

    private val phase = Noise.hash(this.seed + 401, 0, 0, 0) * CYCLE

    private class Event {
        var cycle = Long.MIN_VALUE
        var exists = false
        var start = 0.0
        val origin = Vec3()
        /** 0..1, with the setting's size in. */
        var strength = 0.0
        var period = 0.0
        /** How much weaker it is on a launch site, 0..[SITE_SHY]. */
        var shy = 0.0
        /** Arrival per grid node, seconds after the quake, or [NEVER]; made the first time it's needed. */
        var solution: Grid.Solution? = null
        val id: Long get() = cycle
    }

    private val cache = Array(WAYS) { Event() }
    init {
        if (active) Grid.warm(terrain!!, bodyRadius, null, g)
    }
    private val live = arrayOfNulls<Event>(BACK + 1)

    /** A quake placed by a test in place of the hashed ones. */
    private var forced: Event? = null

    private val e = Vec3()
    private val n = Vec3()
    private val probe = Vec3()
    private val cross = Vec3()
    private val sc = DetMath.SinCos()
    private val located = Vec3()

    /** The launch sites on this world, unit. */
    private val sites: List<Vec3> by lazy {
        val out = ArrayList<Vec3>()
        for (site in com.rm.apogee.core.world.World.launchSites) {
            if (site.bodyId != weather.body.id) continue
            val c = DetMath.cos(site.latitude)
            out.add(Vec3(c * DetMath.cos(site.longitude), DetMath.sin(site.latitude), c * DetMath.sin(site.longitude)))
        }
        out
    }

    private fun nearSite(p: Vec3): Double {
        var most = 0.0
        for (d in sites) most = max(most, 1.0 - smooth(SITE_NEAR, SITE_FAR, d.distanceTo(p) * bodyRadius))
        return most
    }

    /**
     * How far it is to the sea from land, for run-up: metres to the nearest sea within
     * [INLAND_MAX], the highest ground on the way there, and the unit direction toward it. Fixed,
     * like the bed, and only asked for on low land while a crest is in.
     */
    private val coast: Lattice? = terrain?.let { t ->
        val name = "${weather.body.id}:${t::class.simpleName}"
        val bed = Lattice(bodyRadius, Sea.COARSE_DEPTH_SPACING, 0.0, 1, 15_000, "coarse-bed:$name") { d, _, out ->
            out[0] = t.elevation(d)
        }
        val bedOut = DoubleArray(1)
        val ce = Vec3(); val cn = Vec3(); val cp = Vec3()
        Lattice(bodyRadius, COAST_SPACING, 0.0, 5, 20_000, "tsunami-coast:$name") { d, _, out ->
            bed.sample(d, 0.0, bedOut)
            val here = bedOut[0]
            if (here <= 0.0) {
                out[0] = 0.0; out[1] = 0.0; out[2] = 0.0; out[3] = 0.0; out[4] = 0.0
                return@Lattice
            }
            frame(d, ce, cn)
            var bestSill = Double.MAX_VALUE
            var bestDistance = FAR
            var bx = 0.0; var by = 0.0; var bz = 0.0
            for (ray in 0 until 8) {
                val de = RAY_E[ray]; val dn = RAY_N[ray]
                var highest = here
                for (step in 1..COAST_STEPS) {
                    val m = step * INLAND_MAX / COAST_STEPS
                    cp.setTo(d).addScaledInPlace(ce, de * m / bodyRadius).addScaledInPlace(cn, dn * m / bodyRadius).normalizeInPlace()
                    bed.sample(cp, 0.0, bedOut)
                    if (bedOut[0] < 0.0) {
                        if (highest < bestSill || highest == bestSill && m < bestDistance) {
                            bestSill = highest; bestDistance = m
                            bx = ce.x * de + cn.x * dn; by = ce.y * de + cn.y * dn; bz = ce.z * de + cn.z * dn
                        }
                        break
                    }
                    highest = max(highest, bedOut[0])
                }
            }
            out[0] = bestDistance
            out[1] = if (bestSill == Double.MAX_VALUE) FAR else bestSill
            out[2] = bx; out[3] = by; out[4] = bz
        }
    }
    private val coastOut = DoubleArray(5)

    /** The quake of [cycle], made the first time it's asked for. */
    private fun event(cycle: Long): Event {
        // One slot per cycle, more slots than cycles in use at once, so a quake in use is never
        // overwritten by another.
        val ev = cache[cycle.mod(WAYS.toLong()).toInt()]
        if (ev.cycle != cycle) build(ev, cycle)
        return ev
    }

    private fun build(ev: Event, cycle: Long) {
        ev.cycle = cycle; ev.exists = false; ev.solution = null
        ev.start = 0.0; ev.origin.setZero(); ev.strength = 0.0; ev.period = 0.0; ev.shy = 0.0
        val t = terrain ?: return
        if (!active) return
        val c = cycle.toInt()
        fun hh(k: Int) = Noise.hash(seed + k, c, 0, 0)
        if (hh(402) >= chance) return
        // Where: the deepest and steepest of the first few tries that land on open sea floor.
        var best = -1.0
        var picks = 0
        for (k in 0 until TRIES) {
            val z = 2.0 * hh(410 + k) - 1.0
            DetMath.sinCos(2.0 * kotlin.math.PI * hh(440 + k), sc)
            val r = sqrt(max(0.0, 1.0 - z * z))
            probe.setTo(r * sc.cos, z, r * sc.sin).normalizeInPlace()
            val bed = t.elevation(probe)
            if (bed > -ORIGIN_DEPTH) continue
            frame(probe, e, n)
            var steep = 0.0
            for (q in 0 until 4) {
                val de = RAY_E[q]; val dn = RAY_N[q]
                cross.setTo(probe).addScaledInPlace(e, de * STEEP_PROBE / bodyRadius).addScaledInPlace(n, dn * STEEP_PROBE / bodyRadius).normalizeInPlace()
                steep = max(steep, kotlin.math.abs(t.elevation(cross) - bed) / STEEP_PROBE)
            }
            val score = -bed * (steep + 0.002) * (0.5 + hh(470 + k))
            if (score > best) { best = score; ev.origin.setTo(probe) }
            if (++picks >= ORIGIN_PICKS) break
        }
        if (best < 0.0) return
        ev.exists = true
        ev.start = cycle * CYCLE - phase + hh(403) * CYCLE
        ev.strength = size * (STRENGTH_LEAST + (1.0 - STRENGTH_LEAST) * hh(404))
        ev.period = PERIOD_SHORTEST + (PERIOD_LONGEST - PERIOD_SHORTEST) * hh(405)
        ev.shy = SITE_SHY * hh(406)
        // Its arrival times, ahead of time on a worker where there is one, so nothing waits on them.
        Grid.warm(t, bodyRadius, ev.origin.copy(), g)
    }

    /** The quakes that may still be moving the sea at [time], into [live]; returns how many. */
    private fun liveAt(time: Double): Int {
        val f = forced
        if (f != null) {
            live[0] = f
            return 1
        }
        if (!active) return 0
        val newest = floor((time + phase) / CYCLE).toLong()
        // The next one's made a cycle early, so its arrival times are ready when it comes.
        event(newest + 1)
        var count = 0
        for (c in newest downTo newest - BACK) {
            val ev = event(c)
            if (!ev.exists) continue
            val age = time - ev.start
            if (age <= 0.0 || age >= LIFE) continue
            live[count++] = ev
        }
        return count
    }

    private fun solution(ev: Event): Grid.Solution {
        ev.solution?.let { return it }
        val grid = Grid.of(terrain!!, bodyRadius)
        return grid.solve(ev.origin, g).also { ev.solution = it }
    }

    // What [height] found, for [flow] and [slope].
    private val flowOut = Vec3()
    private val slopeOut = Vec3()

    /**
     * How far the tsunamis move the sea at unit [up] at [time] where the bed is [bed] metres
     * (negative under the sea), in metres: down in a trough, up in a crest, and over low land
     * near the sea the height a crest floods it to (0 where it doesn't). Leaves the flow for [flow]
     * and the lean for [slope]. The bore's foam, for [foam], is widened by [blur] metres each way.
     */
    fun height(up: Vec3, time: Double, bed: Double, blur: Double = 0.0): Double {
        flowOut.setZero(); slopeOut.setZero(); foamOut = 0.0
        crestOut = NO_CREST; whiteOut = 0.0; periodOut = 0.0
        if (bed > COAST_CAP + 1.0) return 0.0
        val count = liveAt(time)
        if (count == 0) return 0.0
        var sea = 0.0
        var land = 0.0
        var coastRead = false
        for (q in 0 until count) {
            val ev = live[q]!!
            val age = time - ev.start
            // Not there yet even at the fastest any could go.
            val dot = up dot ev.origin
            if (dot < 1.0 && (1.0 - dot) * 2.0 * bodyRadius * bodyRadius > (age * FASTEST) * (age * FASTEST)) continue
            val solution = solution(ev)
            // Not there yet, or long gone, at anything as far from the origin.
            val bin = Grid.binOf(dot)
            if (age <= solution.earliest[bin] || age >= solution.latest[bin] + TRAIN) continue
            val arrival = solution.arrival
            val grid = Grid.of(terrain!!, bodyRadius)
            val at = grid.at(arrival, up, located)
            if (at >= NEVER) continue
            var tau = age - at
            if (tau <= 0.0 || tau >= TRAIN) continue
            // Out from the origin it spreads and fades; on the shelf it grows.
            cross.setTo(ev.origin).crossInPlace(up)
            val sine = cross.length
            val distance = StrictMath.atan2(sine, dot) * bodyRadius
            val spread = sqrt(SPREAD_FROM / max(SPREAD_FROM, sine * bodyRadius))
            var deep = DEEP * ev.strength * spread * DetMath.exp(-distance / FADE)
            if (sites.isNotEmpty()) deep *= 1.0 - ev.shy * nearSite(up)
            val coastSize = min(COAST_CAP * ev.strength, deep * green(SHALLOWEST))
            if (bed <= 0.0) {
                // Slowing over the shallows, it reaches them later, so its front runs in toward
                // the shore along the depth lines.
                // Its depth and the delay from the smoothed shelf, close in to a coast.
                val shelf = -bed < SHOAL_LOOK
                var depth = -bed
                if (shelf) {
                    (if (blur < SHOAL_COARSE_FROM) shoalFine else shoalCoarse)!!.sample(up, 0.0, shoalOut)
                    tau -= shoalOut[0]
                    depth = shoalOut[4]
                }
                val a = min(coastSize, deep * green(-bed))
                // High for the depth, its front steepens into a bore.
                val steep = smooth(BORE_FROM, BORE_FULL, a / max(depth, SHALLOWEST))
                drawn(tau, ev.period, steep, a, 1.0, a)
                if (tau <= 0.0) continue
                wave(tau, ev.period, steep, blur / sqrt(g * max(depth, SHALLOWEST)))
                if (waveF == 0.0 && waveDf == 0.0) continue
                val eta = a * waveF
                sea += eta
                foamOut = max(foamOut, waveFoam * smooth(BORE_LEAST, BORE_LEAST * 3.0, a * waveEnvelope))
                // Flow with the wave, forward under a crest and back under a trough.
                if (kotlin.math.abs(a) > 0.005 && heading(grid, arrival, up, at) && (!shelf || shoalHeading())) {
                    flowOut.addScaledInPlace(probe, eta * sqrt(g / max(-bed, SHALLOWEST)))
                    // The surface leans with the wave: down the arrival times it's where the wave
                    // was a moment ago.
                    slopeOut.addScaledInPlace(probe, -a * waveDf * headingRate)
                }
            } else {
                // Low land: flooded by a crest up to a height that falls off inland, only where the
                // way to the sea is under it. The flood runs in from the coast as a bore.
                if (!coastRead) {
                    coast!!.sample(up, 0.0, coastOut)
                    coastRead = true
                }
                tau -= shoalDelay(0.0) + coastOut[0] / BORE_LAND_SPEED
                if (tau >= TRAIN) continue
                // Drawn as white as deep as its crest would flood it.
                val crestTop = coastSize * (1.0 - coastOut[0] / min(INLAND_MAX, INLAND_PER_METRE * coastSize))
                drawn(tau, ev.period, 1.0, 1e6, if (crestTop > coastOut[1]) smooth(0.1, 0.8, crestTop - bed) else 0.0, coastSize)
                if (tau <= 0.0) continue
                wave(tau, ev.period, 1.0, blur / BORE_LAND_SPEED)
                val top = coastSize * max(waveF, 0.0)
                if (top <= bed) continue
                val reach = min(INLAND_MAX, INLAND_PER_METRE * coastSize)
                val level = top * (1.0 - coastOut[0] / reach)
                if (level <= bed || level <= coastOut[1]) continue
                foamOut = max(foamOut, waveFoam * smooth(0.1, 0.8, level - bed))
                if (level > land) {
                    land = level
                    // In while it rises, back out while it drains, easing through nothing at the
                    // crest.
                    val rising = (waveDf * ev.period / (2.0 * kotlin.math.PI) / max(waveF, RISE_FLOOR)).coerceIn(-1.0, 1.0)
                    val speed = -(FLOW_LAND * sqrt(g * (level - bed))).coerceAtMost(FLOW_MOST) * rising
                    probe.setTo(coastOut[2], coastOut[3], coastOut[4])
                    probe.addScaledInPlace(up, -(probe dot up))
                    flowOut.setZero()
                    if (probe.lengthSq > 1e-6) flowOut.setTo(probe.normalizeInPlace()).mulInPlace(speed)
                }
            }
        }
        val l = flowOut.length
        if (l > FLOW_MOST) flowOut.mulInPlace(FLOW_MOST / l)
        return if (bed <= 0.0) sea else land
    }

    /** How white the bore is where [height] last looked, 0..1: a band along its front and fading lines behind. */
    fun foam(): Double = foamOut
    private var foamOut = 0.0

    /**
     * For drawing the foam per pixel, where [height] last looked, from the tsunami whitest there:
     * seconds since its first crest passed (or the bore's face, which comes with it), counting on
     * through the later ones [period] apart; and how white it is, over 0 for a bore and its lines
     * behind ([BORE_BAND], [BORE_LINES]), under 0 for a gentle crest's pale line ([CREST_LINE]).
     * [whiteness] is 0 where it's none, and [crest] [NO_CREST] where none has arrived.
     */
    fun crest(): Double = crestOut
    fun whiteness(): Double = whiteOut
    fun period(): Double = periodOut
    private var crestOut = NO_CREST
    private var whiteOut = 0.0
    private var periodOut = 0.0
    private var drawnSize = 0.0

    /**
     * Keeps [crest], [whiteness] and [period] for a wave [tau] seconds past its arrival, [steep]
     * toward a bore, [a] metres high, as white as [share], if it's bigger here ([size] metres
     * high) than the one kept. Picked by size, not whiteness, so neighbours pick the same one.
     */
    private fun drawn(tau: Double, period: Double, steep: Double, a: Double, share: Double, size: Double) {
        if (tau >= TRAIN) return
        val envelope = DetMath.exp(-max(tau, 0.0) / DECAY) * (1.0 - smooth(0.8 * TRAIN, TRAIN, tau))
        if (periodOut != 0.0 && size * envelope <= drawnSize) return
        drawnSize = size * envelope
        val strength = smooth(BORE_LEAST, BORE_LEAST * 3.0, a * envelope) * share
        val bore = smooth(0.6, 0.9, steep)
        val gentle = if (steep < 0.15) CREST_WHITE * (1.0 - smooth(0.0, 0.15, steep)) else 0.0
        val white = (bore - gentle) * strength
        // Where wave() puts the crest: the rise squeezed toward [BORE_RISE] seconds as it steepens.
        val rise = 0.5 + (min(BORE_RISE / period, 0.5) - 0.5) * steep
        crestOut = tau - (0.5 + 0.5 * rise) * period
        whiteOut = white
        periodOut = period
    }

    /**
     * For the far sea's foam: tsunami [id]'s seconds since it arrived at unit [up] at [time] by
     * the coarse arrival times (no shelf), into out[0], and how high it is in deep water there,
     * metres, into out[1]. False where it never arrives.
     */
    fun phase(up: Vec3, time: Double, id: Long, out: DoubleArray): Boolean {
        val count = liveAt(time)
        for (q in 0 until count) {
            val ev = live[q]!!
            if (ev.id != id) continue
            val at = Grid.of(terrain!!, bodyRadius).at(solution(ev).arrival, up, located)
            if (at >= NEVER) return false
            out[0] = time - ev.start - at
            val dot = up dot ev.origin
            cross.setTo(ev.origin).crossInPlace(up)
            val sine = cross.length
            val distance = StrictMath.atan2(sine, dot) * bodyRadius
            var deep = DEEP * ev.strength * sqrt(SPREAD_FROM / max(SPREAD_FROM, sine * bodyRadius)) * DetMath.exp(-distance / FADE)
            if (sites.isNotEmpty()) deep *= 1.0 - ev.shy * nearSite(up)
            out[1] = deep
            return true
        }
        return false
    }

    /**
     * Whether any tsunami may be moving the sea at unit [up] at [time]. Quick, and never false
     * where [height] isn't 0, so work can be skipped where none is.
     */
    fun passing(up: Vec3, time: Double): Boolean {
        val count = liveAt(time)
        for (q in 0 until count) {
            val ev = live[q]!!
            val age = time - ev.start
            val dot = up dot ev.origin
            if (dot < 1.0 && (1.0 - dot) * 2.0 * bodyRadius * bodyRadius > (age * FASTEST) * (age * FASTEST)) continue
            val solution = solution(ev)
            val bin = Grid.binOf(dot)
            if (age <= solution.earliest[bin] || age >= solution.latest[bin] + TRAIN) continue
            return true
        }
        return false
    }

    /** The flow [height] last found, along the ground, body-fixed, in m/s, into [out]. */
    fun flow(out: Vec3): Vec3 = out.setTo(flowOut)

    /** How the surface leans where [height] last looked, as its rise per metre along the ground, into [out]. */
    fun slope(out: Vec3): Vec3 = out.setTo(slopeOut)

    /** Seconds per metre the arrival times change by where [heading] last looked. */
    private var headingRate = 0.0

    /**
     * The way the wave runs at unit [up], into [probe]: down the arrival times, from [at] there.
     * False where they're flat.
     */
    private fun heading(grid: Grid, arrival: DoubleArray, up: Vec3, at: Double): Boolean {
        frame(up, e, n)
        val step = HEADING_STEP / bodyRadius
        cross.setTo(up).addScaledInPlace(e, step).normalizeInPlace()
        val te = grid.at(arrival, cross, located)
        cross.setTo(up).addScaledInPlace(n, step).normalizeInPlace()
        val tn = grid.at(arrival, cross, located)
        if (te >= NEVER || tn >= NEVER) return false
        val ge = te - at; val gn = tn - at
        val l = sqrt(ge * ge + gn * gn)
        if (l < 1e-9) return false
        headingRate = l / HEADING_STEP
        probe.setTo(e).mulInPlace(ge / l).addScaledInPlace(n, gn / l)
        return true
    }

    /**
     * How much later than the grid has it the wave reaches water [depth] metres deep, in seconds:
     * crossing a shelf sloping [SHOAL_SLOPE] from [SHOAL_DEPTH] metres at sqrt(g * depth).
     */
    private fun shoalDelay(depth: Double): Double =
        2.0 * (sqrt(SHOAL_DEPTH) - sqrt(depth.coerceIn(0.0, SHOAL_DEPTH))) / (SHOAL_SLOPE * sqrt(g))

    /**
     * The shelf near a coast, smoothed so small bumps in the bed don't scatter the front: per
     * corner the water depth averaged round it, the [shoalDelay] for that, and that delay's
     * gradient as a vector in seconds per metre. Fixed, like the bed. A fine one for floating and
     * for drawing all but from high up, and a coarse one for samples far apart.
     */
    private fun shoalLattice(spacing: Double, capacity: Int): Lattice? = terrain?.let { t ->
        val name = "$bodyId:${t::class.simpleName}:$spacing"
        val se = Vec3(); val sn = Vec3(); val sp = Vec3()
        fun bedAt(d: Vec3, de: Double, dn: Double): Double {
            sp.setTo(d).addScaledInPlace(se, de / bodyRadius).addScaledInPlace(sn, dn / bodyRadius).normalizeInPlace()
            return max(-t.elevation(sp), 0.0)
        }
        Lattice(bodyRadius, spacing, 0.0, 5, capacity, "tsunami-shoal:$name") { d, _, out ->
            frame(d, se, sn)
            val h = 0.5 * spacing
            val depth = (bedAt(d, 0.0, 0.0) + bedAt(d, h, 0.0) + bedAt(d, -h, 0.0) + bedAt(d, 0.0, h) + bedAt(d, 0.0, -h)) / 5.0
            out[0] = shoalDelay(depth)
            // Rough is fine here: it only turns the flow.
            val rate = if (depth < SHOAL_DEPTH) -1.0 / (SHOAL_SLOPE * sqrt(g * max(depth, 0.25))) else 0.0
            val ge = rate * (bedAt(d, spacing, 0.0) - bedAt(d, -spacing, 0.0)) / (2.0 * spacing)
            val gn = rate * (bedAt(d, 0.0, spacing) - bedAt(d, 0.0, -spacing)) / (2.0 * spacing)
            out[1] = se.x * ge + sn.x * gn; out[2] = se.y * ge + sn.y * gn; out[3] = se.z * ge + sn.z * gn
            out[4] = depth
        }
    }
    private val shoalFine = shoalLattice(SHOAL_SPACING, 40_000)
    private val shoalCoarse = shoalLattice(SHOAL_COARSE_SPACING, 20_000)
    private val shoalOut = DoubleArray(5)

    /**
     * Turns [probe] and [headingRate] from [heading] to take in the shelf's delay read into
     * [shoalOut]: down the bed toward the shore. False where they cancel.
     */
    private fun shoalHeading(): Boolean {
        // e and n are still [heading]'s frame at up.
        val ge = (probe dot e) * headingRate + (e.x * shoalOut[1] + e.y * shoalOut[2] + e.z * shoalOut[3])
        val gn = (probe dot n) * headingRate + (n.x * shoalOut[1] + n.y * shoalOut[2] + n.z * shoalOut[3])
        val l = sqrt(ge * ge + gn * gn)
        if (l < 1e-9) return false
        headingRate = l
        probe.setTo(e).mulInPlace(ge / l).addScaledInPlace(n, gn / l)
        return true
    }

    /** Green's law: how much bigger a long wave is in [depth] metres than in [DEEP_DEPTH]. */
    private fun green(depth: Double): Double = sqrt(sqrt(DEEP_DEPTH / max(depth, SHALLOWEST)))

    private var waveF = 0.0
    private var waveDf = 0.0
    private var waveEnvelope = 0.0
    private var waveFoam = 0.0

    /**
     * The wave's shape [tau] seconds after it arrives, into [waveF] (a trough first, then crests,
     * dying away) and its rate of change [waveDf]. With [steep] toward 1 each rise is squeezed into
     * [BORE_RISE] seconds, as a bore, the draw back and the fall taking the rest; the time is
     * warped piecewise with the slope matching at every join, so the shape stays smooth. [waveFoam] is the bore's foam there, its
     * lines widened to [blur] seconds.
     */
    private fun wave(tau: Double, period: Double, steep: Double, blur: Double) {
        val w = 2.0 * kotlin.math.PI / period
        // The share of a cycle the rise takes, from half (no bore) down.
        val rise = 0.5 + (min(BORE_RISE / period, 0.5) - 0.5) * steep
        val cycles = kotlin.math.floor(tau / period)
        val u = tau / period - cycles
        // Trough to crest maps to [0.25, 0.75] of the plain sine, the fall and the draw back
        // share the rest evenly, so the slope matches where they meet.
        val riseStart = 0.5 * (1.0 - rise)
        val crestAt = riseStart + rise
        val warped: Double
        val m: Double
        if (u < riseStart) { m = 0.25 / riseStart; warped = u * m }
        else if (u < crestAt) { m = 0.5 / rise; warped = 0.25 + (u - riseStart) * m }
        else { m = 0.25 / riseStart; warped = 0.75 + (u - crestAt) * m }
        DetMath.sinCos(2.0 * kotlin.math.PI * warped, sc)
        val envelope = DetMath.exp(-tau / DECAY) * (1.0 - smooth(0.8 * TRAIN, TRAIN, tau))
        val raw = -sc.sin
        val k = if (raw < 0.0) TROUGH else 1.0
        waveF = raw * k * envelope
        waveDf = (-sc.cos * w * m - raw / DECAY) * k * envelope
        waveEnvelope = envelope
        // Seconds since this cycle's rise began: white over the top of the face and just behind,
        // then lines parallel to the front, fading.
        waveFoam = 0.0
        val since = (u - riseStart) * period
        val face = rise * period
        if (steep > 0.0) {
            if (since > face - BORE_RISE - blur && since < face + BORE_BAND + blur) waveFoam = 1.0
            else if (since - face - BORE_BAND > 0.0 && since - face - BORE_BAND < LINES_SPAN + blur) {
                // Only behind the face and its band, as the shader draws them (Shaders.TSUNAMI_FOAM).
                val behind = since - face - BORE_BAND
                val line = 0.5 + 0.5 * kotlin.math.cos(2.0 * kotlin.math.PI * behind / BORE_LINES)
                // Too fine to draw, the lines go; further behind, they thin out rather than fade,
                // so a facet is white or not. Under 1, which only the front band reaches.
                val sharp = 1.0 - smooth(BORE_LINES * 0.12, BORE_LINES * 0.25, blur)
                val fade = smooth(0.0, LINES_SPAN, behind)
                val edge = 0.45 + 0.55 * fade
                waveFoam = sharp * smooth(edge, edge + 0.1, line) * 0.9
            }
            // Only a full bore is white; a gentler front rises too slowly to break all along.
            waveFoam *= smooth(0.6, 0.9, steep)
        }
        // Out where it hasn't started to steepen, its crest still breaks a little: a pale line
        // from afar. (Part steepened, the crest comes at a time that varies with the depth, so
        // there it would only be spots.)
        if (steep < 0.15 && since > face - CREST_LINE - blur && since < face + blur) waveFoam = max(waveFoam, CREST_WHITE * (1.0 - smooth(0.0, 0.15, steep)))
    }

    /**
     * The tsunamis alive at [time] whose wave is within [reach] metres of unit [up], or passing
     * over it, into [out]. Makes new objects, so not for every sample.
     */
    fun near(up: Vec3, time: Double, reach: Double, out: MutableList<LiveTsunami>) {
        val count = liveAt(time)
        for (q in 0 until count) {
            val ev = live[q]!!
            val age = time - ev.start
            val arrival = solution(ev).arrival
            val grid = Grid.of(terrain!!, bodyRadius)
            val front = grid.front(arrival, ev.origin, age)
            val here = grid.at(arrival, up, located)
            val distance = StrictMath.atan2(cross.setTo(ev.origin).crossInPlace(up).length, up dot ev.origin) * bodyRadius
            val passing = here < NEVER && age >= here && age < here + TRAIN
            if (!passing && kotlin.math.abs(distance - front) > reach) continue
            out.add(LiveTsunami(ev.id, ev.origin.copy(), ev.start, ev.strength, ev.period, age, front,
                if (here < NEVER) ev.start + here else Double.NaN))
        }
    }

    /** For tests: one quake at unit [origin] at [start], in place of all the hashed ones. */
    internal fun force(origin: Vec3, start: Double, strength: Double = 1.0, period: Double = 900.0) {
        forced = Event().also {
            it.cycle = -1L; it.exists = true; it.start = start; it.origin.setTo(origin).normalizeInPlace()
            it.strength = strength; it.period = period
        }
    }

    /** The arrival times for a quake at unit [origin], per grid node. For tests. */
    internal fun arrivalsFor(origin: Vec3): DoubleArray = Grid.of(terrain!!, bodyRadius).solve(origin.copy().normalizeInPlace(), g).arrival

    /** The arrival time at unit [up] of a quake at unit [origin], seconds, or [NEVER]. For tests. */
    internal fun arrivalAt(origin: Vec3, up: Vec3): Double = Grid.of(terrain!!, bodyRadius).at(arrivalsFor(origin), up, located)

    /** The grid the arrival times are on. For tests. */
    internal val grid: Grid get() = Grid.of(terrain!!, bodyRadius)

    /**
     * The sea floor on a cube-sphere grid about [GRID_SPACING] apart, with each node's neighbours
     * (the eight round it and eight a knight's move away, across face edges too), and the arrival
     * times for each quake asked for. Made once per terrain and shared.
     */
    internal class Grid private constructor(private val terrain: Terrain, private val radius: Double) {
        val perFace = ceil(kotlin.math.PI / 2.0 * radius / GRID_SPACING).toInt().coerceIn(8, GRID_MOST)
        val count = 6 * perFace * perFace
        private val x = DoubleArray(count)
        private val y = DoubleArray(count)
        private val z = DoubleArray(count)
        private val bed = DoubleArray(count)
        private val next = IntArray(count * NEIGHBOURS)
        private val apart = DoubleArray(count * NEIGHBOURS)
        private val solved = concurrentMapOf<String, Solution>()

        init {
            val d = Vec3()
            for (face in 0 until 6) for (i in 0 until perFace) for (j in 0 until perFace) {
                val k = (face * perFace + i) * perFace + j
                CubeSphere.direction(face, (i + 0.5) / perFace * 2.0 - 1.0, (j + 0.5) / perFace * 2.0 - 1.0, d)
                x[k] = d.x; y[k] = d.y; z[k] = d.z
                bed[k] = terrain.elevation(d)
            }
            val located = Vec3()
            for (face in 0 until 6) for (i in 0 until perFace) for (j in 0 until perFace) {
                val k = (face * perFace + i) * perFace + j
                for (m in 0 until NEIGHBOURS) {
                    val ii = i + STEP_I[m]; val jj = j + STEP_J[m]
                    val other = if (ii in 0 until perFace && jj in 0 until perFace) {
                        (face * perFace + ii) * perFace + jj
                    } else {
                        CubeSphere.direction(face, (ii + 0.5) / perFace * 2.0 - 1.0, (jj + 0.5) / perFace * 2.0 - 1.0, d)
                        nodeOf(d, located)
                    }
                    next[k * NEIGHBOURS + m] = if (other == k) -1 else other
                    if (other != k) {
                        val dx = x[other] - x[k]; val dy = y[other] - y[k]; val dz = z[other] - z[k]
                        apart[k * NEIGHBOURS + m] = sqrt(dx * dx + dy * dy + dz * dz) * radius
                    }
                }
            }
        }

        private fun nodeOf(d: Vec3, located: Vec3): Int {
            val face = CubeSphere.locate(d, located)
            val i = floor((located.x + 1.0) * 0.5 * perFace).toInt().coerceIn(0, perFace - 1)
            val j = floor((located.y + 1.0) * 0.5 * perFace).toInt().coerceIn(0, perFace - 1)
            return (face * perFace + i) * perFace + j
        }

        /** How long the last [solve] that did any work took, in milliseconds. For tests. */
        var lastMillis = 0.0
            private set

        /**
         * Seconds from a quake at unit [origin] until its front reaches each node, or [NEVER].
         * Land nodes behind a coast carry it on at [BAY_DEPTH] for up to [LAND_REACH], so bays and
         * harbours narrower than the grid still get it. Worked out once and kept.
         */
        fun solve(origin: Vec3, g: Double): Solution {
            val key = "${origin.x}:${origin.y}:${origin.z}:$g"
            solved[key]?.let { return it }
            val began = com.rm.apogee.core.nanoTime()
            val t = DoubleArray(count) { NEVER }
            val slow = DoubleArray(count) { if (bed[it] < 0.0) 1.0 / sqrt(g * max(-bed[it], SHALLOWEST)) else 0.0 }
            val heap = IntArray(count)
            val where = IntArray(count) { -1 }
            var size = 0

            fun less(a: Int, b: Int) = t[a] < t[b] || t[a] == t[b] && a < b
            fun up(start: Int) {
                var c = start
                val node = heap[c]
                while (c > 0) {
                    val p = (c - 1) / 2
                    if (!less(node, heap[p])) break
                    heap[c] = heap[p]; where[heap[c]] = c; c = p
                }
                heap[c] = node; where[node] = c
            }
            fun down(start: Int) {
                var c = start
                val node = heap[c]
                while (true) {
                    val l = 2 * c + 1
                    if (l >= size) break
                    var m = l
                    if (l + 1 < size && less(heap[l + 1], heap[l])) m = l + 1
                    if (!less(heap[m], node)) break
                    heap[c] = heap[m]; where[heap[c]] = c; c = m
                }
                heap[c] = node; where[node] = c
            }
            fun offer(k: Int, time: Double) {
                if (time >= t[k]) return
                t[k] = time
                if (where[k] < 0) { heap[size] = k; size++; up(size - 1) } else up(where[k])
            }

            // From the origin to the nodes round it.
            val located = Vec3()
            val home = nodeOf(origin, located)
            fun seedFrom(k: Int) {
                if (bed[k] >= 0.0) return
                val dx = x[k] - origin.x; val dy = y[k] - origin.y; val dz = z[k] - origin.z
                offer(k, sqrt(dx * dx + dy * dy + dz * dz) * radius * slow[k])
            }
            seedFrom(home)
            for (m in 0 until NEIGHBOURS) next[home * NEIGHBOURS + m].let { if (it >= 0) seedFrom(it) }

            while (size > 0) {
                val k = heap[0]
                size--
                where[k] = -2
                if (size > 0) { heap[0] = heap[size]; where[heap[0]] = 0; down(0) }
                val tk = t[k]
                if (tk > TRAVEL_MOST) { t[k] = NEVER; continue }
                for (m in 0 until NEIGHBOURS) {
                    val o = next[k * NEIGHBOURS + m]
                    if (o < 0 || bed[o] >= 0.0 || where[o] == -2) continue
                    offer(o, tk + apart[k * NEIGHBOURS + m] * 0.5 * (slow[k] + slow[o]))
                }
            }
            // Anything left in the heap past the cut-off never arrives.
            for (k in 0 until count) if (t[k] > TRAVEL_MOST) t[k] = NEVER
            // Land behind a coast: from its nearest sea neighbour, then on over land as if through
            // shallow water, a little way only.
            val run = DoubleArray(count)
            for (k in 0 until count) {
                if (bed[k] < 0.0) continue
                var best = NEVER
                for (m in 0 until 8) {
                    val o = next[k * NEIGHBOURS + m]
                    if (o < 0 || bed[o] >= 0.0 || t[o] >= NEVER) continue
                    val hop = t[o] + apart[k * NEIGHBOURS + m] * slow[o]
                    if (hop < best) { best = hop; run[k] = apart[k * NEIGHBOURS + m] }
                }
                if (best < NEVER) offer(k, best)
            }
            val baySlow = 1.0 / sqrt(g * BAY_DEPTH)
            while (size > 0) {
                val k = heap[0]
                size--
                where[k] = -2
                if (size > 0) { heap[0] = heap[size]; where[heap[0]] = 0; down(0) }
                for (m in 0 until 8) {
                    val o = next[k * NEIGHBOURS + m]
                    if (o < 0 || bed[o] < 0.0 || where[o] == -2) continue
                    val further = run[k] + apart[k * NEIGHBOURS + m]
                    if (further > LAND_REACH) continue
                    val time = t[k] + apart[k * NEIGHBOURS + m] * baySlow
                    if (time < t[o]) { run[o] = further; offer(o, time) }
                }
            }
            // The earliest and latest arrival at each distance from the origin, with a margin for
            // reading between nodes, so a place can be passed over without the grid.
            val earliest = DoubleArray(BINS) { NEVER }
            val latest = DoubleArray(BINS) { -NEVER }
            for (k in 0 until count) {
                if (t[k] >= NEVER) continue
                val b = binOf(origin.x * x[k] + origin.y * y[k] + origin.z * z[k])
                for (q in max(0, b - BIN_MARGIN)..min(BINS - 1, b + BIN_MARGIN)) {
                    earliest[q] = min(earliest[q], t[k]); latest[q] = max(latest[q], t[k])
                }
            }
            val solution = Solution(t, earliest, latest)
            lastMillis = (com.rm.apogee.core.nanoTime() - began) / 1e6
            guarded(solved) {
                if (solved.size >= KEPT) solved.clear()
                solved[key] = solution
            }
            return solution
        }

        /**
         * One quake's arrival times per node, and the earliest and latest by [binOf] distance from
         * its origin ([NEVER] and -[NEVER] where none arrive).
         */
        class Solution(val arrival: DoubleArray, val earliest: DoubleArray, val latest: DoubleArray)

        /** [arrival] at unit [up], read between the four nearest nodes on its face. [located] is scratch. */
        fun at(arrival: DoubleArray, up: Vec3, located: Vec3): Double {
            val face = CubeSphere.locate(up, located)
            val gi = ((located.x + 1.0) * 0.5 * perFace - 0.5).coerceIn(0.0, perFace - 1.0)
            val gj = ((located.y + 1.0) * 0.5 * perFace - 0.5).coerceIn(0.0, perFace - 1.0)
            val i0 = min(floor(gi).toInt(), perFace - 2)
            val j0 = min(floor(gj).toInt(), perFace - 2)
            val fi = gi - i0; val fj = gj - j0
            var sum = 0.0; var weight = 0.0
            for (c in 0 until 4) {
                val di = c and 1; val dj = c shr 1
                val w = (if (di == 1) fi else 1.0 - fi) * (if (dj == 1) fj else 1.0 - fj)
                if (w <= 0.0) continue
                val v = arrival[(face * perFace + i0 + di) * perFace + j0 + dj]
                if (v >= NEVER) continue
                sum += w * v; weight += w
            }
            return if (weight > 1e-9) sum / weight else NEVER
        }

        /** How far from unit [origin] the furthest sea node [arrival] has reached by [age] is, in metres. */
        fun front(arrival: DoubleArray, origin: Vec3, age: Double): Double {
            var most = 0.0
            for (k in 0 until count) {
                if (bed[k] >= 0.0 || arrival[k] > age) continue
                val cx = origin.y * z[k] - origin.z * y[k]
                val cy = origin.z * x[k] - origin.x * z[k]
                val cz = origin.x * y[k] - origin.y * x[k]
                val d = StrictMath.atan2(sqrt(cx * cx + cy * cy + cz * cz), origin.x * x[k] + origin.y * y[k] + origin.z * z[k])
                most = max(most, d * radius)
            }
            return most
        }

        companion object {
            private val grids = concurrentMapOf<Terrain, Grid>()
            private val lock = Any()
            private val worker by lazy { com.rm.apogee.core.Background("tsunami-grid") }

            /**
             * Makes [terrain]'s grid, and the arrival times for [origin] if it's given, on a worker,
             * so they're ready before anything needs them. Whoever needs them first makes them if
             * they aren't.
             */
            fun warm(terrain: Terrain, radius: Double, origin: Vec3?, g: Double) {
                if (origin == null && grids[terrain] != null) return
                worker.execute {
                    val grid = of(terrain, radius)
                    if (origin != null) grid.solve(origin, g)
                }
            }

            /** Which distance band a place is in from an origin, by the dot product of their unit directions. */
            fun binOf(dot: Double): Int = (sqrt(max(0.0, 0.5 * (1.0 - dot))) * BINS).toInt().coerceIn(0, BINS - 1)
            private const val BINS = 128
            private const val BIN_MARGIN = 4

            /** A grid for [terrain] that isn't kept, for timing. For tests. */
            internal fun unkept(terrain: Terrain, radius: Double): Grid = Grid(terrain, radius)

            fun of(terrain: Terrain, radius: Double): Grid {
                grids[terrain]?.let { return it }
                return guarded(lock) { grids[terrain] ?: Grid(terrain, radius).also { grids[terrain] = it } }
            }

            /** The offsets to each node's neighbours: the eight round it, then knight's moves. */
            private val STEP_I = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 2, 2, -2, -2)
            private val STEP_J = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1, 2, -2, 2, -2, 1, -1, 1, -1)
            private const val NEIGHBOURS = 16

            /** How many quakes' arrival times are kept per terrain. */
            private const val KEPT = 12
        }
    }

    companion object {
        /** One chance of a quake per world per this many seconds, at this chance at rare 1. */
        const val CYCLE = 3.0 * 3_600.0
        const val CHANCE = 0.8

        /** How long after its quake a tsunami can still be moving the sea, in seconds, and how many cycles back that is. */
        const val TRAVEL_MOST = 5.0 * 3_600.0
        const val TRAIN = Weather.TSUNAMI_TRAIN
        const val LIFE = TRAVEL_MOST + TRAIN
        private const val BACK = 3
        private const val WAYS = 8

        /** No arrival. */
        const val NEVER = 1e30

        /** How deep the sea floor must be for a quake, in metres, how many places are tried and how many of those on open floor are weighed. */
        const val ORIGIN_DEPTH = 400.0
        private const val TRIES = 32
        private const val ORIGIN_PICKS = 6
        /** How far off the floor's steepness is measured, in metres. */
        private const val STEEP_PROBE = 15_000.0

        /** Faster than any front could go, in m/s, for the quick test on whether one could be here yet. */
        const val FASTEST = 400.0

        /** Strength from this up to 1 before the setting's size, and Wild's size next to Extreme's. */
        const val STRENGTH_LEAST = 0.4
        const val SIZE_WILD = 0.3

        /** The crests' spacing, in seconds. */
        const val PERIOD_SHORTEST = 600.0
        const val PERIOD_LONGEST = 1_200.0

        /** How fast the train dies away, in seconds, and how deep the first trough is next to the crests. */
        const val DECAY = 2_400.0
        const val TROUGH = 0.7

        /**
         * Its height in deep water at strength 1, in metres, [SPREAD_FROM] metres out, falling as the
         * square root of the distance across the sphere, and fading over [FADE] metres.
         */
        const val DEEP = 2.5
        const val SPREAD_FROM = 50_000.0
        const val FADE = 2_500_000.0

        /** The depth Green's law grows it from, the shallowest it counts, and the most it reaches at a coast, in metres. */
        const val DEEP_DEPTH = 3_000.0
        const val SHALLOWEST = 2.0
        const val COAST_CAP = Weather.TSUNAMI_COAST_CAP

        /** How far inland a crest can run, in metres per metre of its height at the coast, and at most. */
        const val INLAND_PER_METRE = 250.0
        const val INLAND_MAX = 4_000.0

        /** The coast lattice's spacing and the steps it looks for the sea in, in metres. */
        private const val COAST_SPACING = 500.0
        private const val COAST_STEPS = 8
        private const val FAR = 1e6

        /** The flow over flooded land as a share of sqrt(g * depth), and the most anywhere, in m/s. */
        const val FLOW_LAND = 0.6

        /**
         * A bore: steepening from [BORE_FROM] to [BORE_FULL] times the depth, rising in [BORE_RISE]
         * seconds, white over [BORE_BAND] seconds past its face then in lines [BORE_LINES] seconds
         * apart for [BORE_TRAIL] seconds, none under [BORE_LEAST] metres high. Over land it runs
         * in from the coast at [BORE_LAND_SPEED] m/s.
         */
        const val BORE_FROM = 0.1
        const val BORE_FULL = 0.35
        const val BORE_RISE = 3.0
        const val BORE_BAND = 15.0
        const val BORE_LINES = 7.0
        const val BORE_TRAIL = 70.0
        const val BORE_LEAST = 0.3
        const val BORE_LAND_SPEED = 6.0

        /** How long the lines behind a full bore's band go on for, in seconds. */
        const val LINES_SPAN = BORE_TRAIL - BORE_RISE - BORE_BAND

        /** Seconds of a gentle crest's front that are white, and how white, 0..1. */
        const val CREST_LINE = 8.0
        const val CREST_WHITE = 0.8

        /** [crest] where no tsunami has arrived: long before any crest. */
        const val NO_CREST = Weather.TSUNAMI_NO_CREST

        /** The shelf the wave slows over near a coast: from this depth, in metres, at this slope. */
        const val SHOAL_DEPTH = 6.0
        const val SHOAL_SLOPE = Weather.TSUNAMI_SHOAL_SLOPE
        /** The shelf lattices' spacings, and how deep the water is where they're first asked, in metres. */
        private const val SHOAL_SPACING = 40.0
        private const val SHOAL_COARSE_SPACING = 320.0
        /** Samples this far apart, in metres, read the coarse one: only from high up, where the two can't be told apart. */
        private const val SHOAL_COARSE_FROM = 300.0
        private const val SHOAL_LOOK = 30.0

        /** The least share of its crest the wave's height counts as when working out how fast it rises over land. */
        private const val RISE_FLOOR = 0.1
        const val FLOW_MOST = 10.0

        /** How far apart arrival times are compared to find which way the wave runs, in metres. */
        private const val HEADING_STEP = 2_000.0

        /** How deep the water in a bay too narrow for the grid is taken to be, and how far over land the grid carries it, in metres. */
        const val BAY_DEPTH = 10.0
        const val LAND_REACH = 45_000.0

        /** The grid's spacing in metres, and the most nodes along a face. */
        const val GRID_SPACING = 20_000.0
        const val GRID_MOST = 64

        /** How much weaker one is on a launch site at most, fading out from [SITE_NEAR] to [SITE_FAR] metres. */
        const val SITE_SHY = 0.4
        const val SITE_NEAR = 20_000.0
        const val SITE_FAR = 40_000.0

        /** East and north of the eight rays the coast lattice looks along. */
        private val RAY_E = doubleArrayOf(1.0, -1.0, 0.0, 0.0, 0.7071067811865476, 0.7071067811865476, -0.7071067811865476, -0.7071067811865476)
        private val RAY_N = doubleArrayOf(0.0, 0.0, 1.0, -1.0, 0.7071067811865476, -0.7071067811865476, 0.7071067811865476, -0.7071067811865476)
    }
}
