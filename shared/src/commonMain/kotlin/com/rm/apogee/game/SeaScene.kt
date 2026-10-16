package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.sea.SeaSample
import com.rm.apogee.core.terrain.CubeSphere
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.SeaSurface
import com.rm.apogee.render.GridMorph
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.rm.apogee.platform.System
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile
import com.rm.apogee.platform.synchronized

/** How far around the camera the waves are drawn at [tier], in metres. */
internal fun seaReachFor(tier: QualityTier): Double = when (tier) {
    QualityTier.LOW -> 6_000.0
    QualityTier.MEDIUM -> 12_000.0
    QualityTier.HIGH -> 20_000.0
}

/**
 * The sea to draw round the camera, sampled on a worker from the same [Sea] the physics floats
 * boats on.
 *
 * It's laid out on the ground: square grids nested inside each other, each twice as coarse as the
 * one inside, finest at the camera and coarsest out at [reach]. Every vertex is a fixed point on
 * the planet (a lattice in the terrain's cube-face coordinates) and each grid moves only by whole
 * cells of the one outside it, so facets stay put on the water as the camera flies over, with no
 * pattern following the craft.
 *
 * Where two grids meet, the finer one's edge takes its points from the coarser, so there's no
 * crack. Waves too short for a grid's spacing are left out there, since they'd only alias. From
 * high up, the finest grids aren't drawn.
 */
class SeaScene(
    val body: CelestialBody,
    moon: CelestialBody?,
    val config: WeatherConfig?,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /**
     * For building off the frame thread: one per worker, since a sea's caches aren't thread safe.
     */
    private val builders = Array(WORKERS) { Builder(Sea(body, moon, config?.let { Weather(body, it) }, config?.seed ?: 0)) }

    /** For the frame thread: the tide under the camera, and whether it's under the water. */
    private val frameSea = Sea(body, moon, config?.let { Weather(body, it) }, config?.seed ?: 0)
    private val frameSample = SeaSample()

    /** A sea of something lighter than water, like Aurantia's methane. */
    private val methane = (body.ocean?.density ?: 1_025.0) < 800.0

    /** How far around the camera the waves are drawn, in metres. */
    val reach: Double = seaReachFor(tier)

    /**
     * Cells along each side of a grid. A multiple of four, so each grid sits on the next's lines.
     */
    private val cells = when (tier) {
        QualityTier.LOW -> 24
        QualityTier.MEDIUM -> 32
        QualityTier.HIGH -> 40
    }
    private val side = cells + 1
    private val perLevel = side * side

    /** Under the camera, and the finest grid drawn, for this build's wave filter. See [Builder.vertex]. */
    private val filterUp = Vec3()
    @kotlin.concurrent.Volatile private var filterFinest = 1.0

    /**
     * Whether a tsunami may be passing the camera this build: then the tide near it is redone on
     * every build, not every few seconds, so flood water stands where boats float.
     */
    @kotlin.concurrent.Volatile private var retiding = false

    /**
     * Tsunamis' sea round the camera as the far water draws it, or null. Out toward the edge the
     * waves' crests go over to its, so the lines meet where the far water takes over.
     */
    @kotlin.concurrent.Volatile var tsunamiField: com.rm.apogee.render.TsunamiField? = null

    /** [tsunamiField] as this build took it. */
    @kotlin.concurrent.Volatile private var buildField: com.rm.apogee.render.TsunamiField? = null

    /** The finest grid's spacing, in metres. */
    private val finest = if (tier == QualityTier.LOW) 1.0 else 0.5

    /**
     * How many grids, finest to coarsest, it takes to reach [reach] with room to spare: toward a
     * face's corners the lattice is up to a third finer than at its middle.
     */
    private val levels: Int = run {
        var k = 0
        while (cells / 2 * finest * (1 shl k) < reach * REACH_MARGIN) k++
        k + 1
    }

    /** Grid [level]'s spacing, in metres, near the middle of a cube face. */
    private fun spacing(level: Int): Double = finest * (1 shl level)

    /** Grid [level]'s spacing in face coordinates, which run a quarter turn from middle to edge. */
    private fun step(level: Int): Double = spacing(level) / (body.radius * Math.PI / 4.0)

    /** Unit [out] for lattice point ([gi], [gj]) at [step] apart on the face. */
    private fun point(gi: Int, gj: Int, step: Double, out: Vec3): Vec3 =
        out.setTo(faceAxis).addScaledInPlace(faceU, CubeSphere.warp(gi * step)).addScaledInPlace(faceV, CubeSphere.warp(gj * step))
            .normalizeInPlace()

    /**
     * The cube face the grids are laid on and its axes: [faceAxis] out through its middle, [faceU]
     * and [faceV] across it. Changes only when the camera is well over its edge.
     */
    @Volatile private var face = -1
    private val faceAxis = Vec3()
    private val faceU = Vec3()
    private val faceV = Vec3()

    /**
     * The sea state at each lattice point (depth, tide, each wave train's size), kept between
     * builds per grid. It changes over tens of metres and seconds while waves change every frame,
     * so it's only redone once old. Points are fixed on the ground, so they're kept by position in
     * a small wrap-around table per grid. See [Sea.Prepared].
     */
    private val table = side + 4
    private val prepared = Array(levels) { arrayOfNulls<Sea.Prepared>(table * table) }
    private val preparedI = Array(levels) { IntArray(table * table) }
    private val preparedJ = Array(levels) { IntArray(table * table) }
    private val preparedTime = Array(levels) { DoubleArray(table * table) { Double.NaN } }

    @Volatile var latest: SeaSurface? = null
        private set

    /** Whether [latest] is a worked-out sea, not the flat stand-in drawn until the first one is. */
    @Volatile var built = false
        private set
    @Volatile private var building = false
    @Volatile var lastBuildMillis = 0.0
        private set
    private var lastStartedNanos = 0L

    /**
     * Asks for the sea round body-fixed [centre] at [time], unless one's already building. It's
     * built for a moment ahead (the last build's time plus half the wait till the next) so it's
     * drawn nearly on time. [warp] is the world clock's rate: at 4x a tenth-second build is nearly
     * half a second of waves, so it has to be built that far ahead to match the sea the boats ride.
     */
    fun update(centre: Vec3, time: Double, warp: Double = 1.0, behind: Boolean = false) {
        if (building) return
        // Nothing to draw yet: flat water at once while the first real sea is worked out, not bare
        // seabed.
        if (latest == null) latest = placeholder(centre, time)
        // Ten a second is plenty. The renderer carries each forward by how fast its water is
        // rising.
        val now = System.nanoTime()
        if (now - lastStartedNanos < (if (behind) BEHIND_BUILD_NANOS else MIN_BUILD_NANOS)) return
        lastStartedNanos = now
        building = true
        // The world's founded bases, so the sea drawn is calm round them like the one boats ride.
        val bases = body.ocean?.sea?.calmBases ?: emptyList()
        for (b in builders) b.sea.calmBases = bases
        frameSea.calmBases = bases
        val at = centre.copy()
        // No more than [MAX_AHEAD] ahead. A slow first build would otherwise send the next one so
        // far on that every prepared point would be stale, making it slow in turn.
        val rate = warp.coerceAtLeast(1.0)
        val gap = if (behind) BEHIND_BUILD_NANOS else MIN_BUILD_NANOS
        val ahead = time + kotlin.math.min((lastBuildMillis / 1_000.0 + gap / 2e9) * rate, MAX_AHEAD * rate)
        scope.launch(BUILD) {
            val started = System.nanoTime()
            try {
                val next = kotlinx.coroutines.coroutineScope { build(this, at, ahead) }
                val old = latest
                latest = next
                // One the GPU never took won't be drawn now, so its vertices can be built into again.
                if (old?.giveBack != null && old.drop()) old.giveBack.invoke(old.vertices)
                built = true
                lastBuildMillis = (System.nanoTime() - started) / 1e6
                // The sea state round the craft for the next while, worked out here rather than in
                // the flight step when it gets there.
                builders[0].sea.prefetch(at, ahead)
            } finally {
                building = false
                com.rm.apogee.platform.AllocProbe.note("sea-build")
            }
        }
    }

    /** The sea at body-fixed [position] into [out], for the frame thread: spray and wakes. */
    fun sampleInto(position: Vec3, time: Double, out: SeaSample) { frameSea.sample(position, time, out) }

    /** The sea at body-fixed [position] now, for the frame thread. */
    fun sampleAt(position: Vec3, time: Double): SeaSample = frameSea.sample(position, time, frameSample)

    /** Whether body-fixed [position] is under the sea, with [here] being the sea sampled there. */
    fun isUnder(position: Vec3, here: SeaSample): Boolean = here.depth > 0.0 && position.length < body.radius + here.height


    /** One worker's share of a build: its own sea and scratch space. */
    private inner class Builder(val sea: Sea) {
        val sample = SeaSample()
        private val farCrest = DoubleArray(com.rm.apogee.render.TsunamiField.CREST_OUT)
        val direction = Vec3()
        val morphTarget = FloatArray(SeaSurface.STRIDE)

        /**
         * Lattice point ([gi], [gj]) of grid [level] into vertex [index] of [out]. Past [deadline]
         * (ns), a point that isn't [near] isn't prepared any more: it keeps its last preparation,
         * or is drawn as flat water at [tide].
         */
        fun vertex(
            out: FloatArray, index: Int, origin: Vec3, level: Int, gi: Int, gj: Int, time: Double,
            near: Boolean = true, deadline: Long = 0L, tide: Double = 0.0, depths: FloatArray? = null,
        ) {
            val spacing = spacing(level)
            val h = step(level)
            point(gi, gj, h, direction)
            val slot = Math.floorMod(gi, table) * table + Math.floorMod(gj, table)
            val cache = prepared[level]
            val p = cache[slot] ?: Sea.Prepared().also { cache[slot] = it }
            val times = preparedTime[level]
            val mine = preparedI[level][slot] == gi && preparedJ[level][slot] == gj && !times[slot].isNaN()
            val age = time - times[slot]
            // Staggered, so the refreshes don't all fall in one build.
            val stale = REPREPARE_SECONDS * (1.0 + 0.5 * ((slot * 0x9E3779B1L).toInt() ushr 24) / 255.0)
            if (!mine || age !in 0.0..stale) {
                if (near || System.nanoTime() < deadline) {
                    sea.prepare(direction, time, spacing, p)
                    preparedI[level][slot] = gi; preparedJ[level][slot] = gj
                    times[slot] = time
                } else if (!mine) {
                    flat(out, index, origin, tide)
                    depths?.set(index, Float.MAX_VALUE)
                    return
                }
            } else if (retiding && near && !sea.retide(direction, time, p)) {
                sea.prepare(direction, time, spacing, p)
                times[slot] = time
            }
            finish(out, index, origin, spacing, p, time, depths)
        }

        /**
         * A point of a coast cell, [SUB_SHIFT] grids finer than the cell's own, at lattice point
         * ([gi], [gj]) of grid [level]. Its sea state is kept apart from the grids', by position.
         */
        fun refinedVertex(out: FloatArray, index: Int, origin: Vec3, level: Int, gi: Int, gj: Int, time: Double) {
            val spacing = spacing(level)
            point(gi, gj, step(level), direction)
            val key = (level.toLong() shl 58) xor ((gi.toLong() and 0x1FFFFFFFL) shl 29) xor (gj.toLong() and 0x1FFFFFFFL)
            var kept: Stamped? = null
            val retide = retiding && direction.distanceTo(filterUp) * body.radius < NEAR_REACH
            val ready = synchronized(refinedPrepared) {
                kept = refinedPrepared[key]
                val fresh = kept?.let { time - it.time in 0.0..REPREPARE_SECONDS } ?: false
                // Cells sharing a point share its state: retided by the first to reach it, before
                // any of them reads it this build.
                if (fresh && retide && kept!!.retidedAt != time) {
                    val k = kept!!
                    if (!sea.retide(direction, time, k.prepared)) sea.prepare(direction, time, spacing, k.prepared)
                    k.retidedAt = time
                }
                fresh
            }
            val p = if (ready) kept!!.prepared else {
                val stamp = kept ?: Stamped(Sea.Prepared(), time)
                sea.prepare(direction, time, spacing, stamp.prepared)
                synchronized(refinedPrepared) {
                    stamp.time = time
                    stamp.retidedAt = time
                    if (refinedPrepared.size > REFINED_KEEP) refinedPrepared.clear()
                    refinedPrepared[key] = stamp
                }
                stamp.prepared
            }
            finish(out, index, origin, spacing, p, time, null)
        }

        /** Vertex [index] of [out] at [direction], from its sea state [p]. */
        private fun finish(out: FloatArray, index: Int, origin: Vec3, spacing: Double, p: Sea.Prepared, time: Double, depths: FloatArray?) {
            // The waves drawn, and the foam, go by how far out this is, not by its grid, so a point on
            // the edge between two grids looks the same from both. It's never finer than the grid.
            val shown = kotlin.math.max(filterFinest, direction.distanceTo(filterUp) * body.radius * FILTER_SHARE / cells)
            sea.surface(direction, time, p, sample, shown)
            // Over dry land, sunk under it. At tide height the coarse far water and coarse ground
            // cross facet by facet along low coasts and speckle from high up; sunk, the coast is
            // where they cross.
            val height = if (sample.depth <= 0.0) {
                minOf(sample.height, sample.tide - sample.depth - kotlin.math.max(DRY_SINK, DRY_SINK_SHARE * spacing))
            } else sample.height
            val radius = body.radius + height
            val o = index * SeaSurface.STRIDE
            out[o] = (direction.x * radius - origin.x).toFloat()
            out[o + 1] = (direction.y * radius - origin.y).toFloat()
            out[o + 2] = (direction.z * radius - origin.z).toFloat()
            colour(sample, direction, out, o + 3, shown)
            out[o + 7] = sample.rise.toFloat()
            var crest = sample.tsunamiCrest
            var white = sample.tsunamiWhiteness
            var period = sample.tsunamiPeriod
            val field = buildField
            val share = if (field == null) 0.0 else smooth(reach * FAR_CREST_FROM, reach * FAR_CREST_TO, direction.distanceTo(filterUp) * body.radius)
            if (share > 0.0 && field!!.crest(direction, kotlin.math.max(sample.depth - sample.tide, 0.0), time, farCrest)) {
                // Toward the edge, the far water's crest (TsunamiField.crest), mixed only between
                // times of the same tsunami.
                val fc = farCrest[0]; val fw = farCrest[1]; val fp = farCrest[2]
                when {
                    fp == period -> { crest += (fc - crest) * share; white += (fw - white) * share }
                    period == 0.0 || white == 0.0 -> { crest = fc; white = fw * share; period = fp }
                    fp == 0.0 || fw == 0.0 -> white *= 1.0 - share
                    share >= 0.5 -> { crest = fc; white = fw; period = fp }
                }
            }
            out[o + 8] = crest.toFloat()
            out[o + 9] = if (methane) 0f else packTsunami(white, period)
            depths?.set(index, sample.depth.toFloat())
        }

        /** Vertex [index] as open water, flat at [tide], until there's time to work it out. */
        private fun flat(out: FloatArray, index: Int, origin: Vec3, tide: Double) {
            flatVertex(out, index, direction, origin, body.radius + tide)
        }
    }

    /** Vertex [index] at unit [direction] as deep open water, flat at [radius] from the centre. */
    private fun flatVertex(out: FloatArray, index: Int, direction: Vec3, origin: Vec3, radius: Double) {
        val o = index * SeaSurface.STRIDE
        out[o] = (direction.x * radius - origin.x).toFloat()
        out[o + 1] = (direction.y * radius - origin.y).toFloat()
        out[o + 2] = (direction.z * radius - origin.z).toFloat()
        out[o + 3] = DEEP_R.toFloat(); out[o + 4] = DEEP_G.toFloat(); out[o + 5] = DEEP_B.toFloat(); out[o + 6] = DEEP_ALPHA
        out[o + 7] = 0f
        out[o + 8] = com.rm.apogee.core.weather.Weather.TSUNAMI_NO_CREST.toFloat(); out[o + 9] = 0f
    }

    /**
     * The grids for a build: the finest drawn, each one's middle as a lattice index (always even,
     * so it sits on the next one's lines), and the point vertices are measured from.
     */
    private inner class Layout(centre: Vec3) {
        val first: Int
        val middleI = IntArray(levels)
        val middleJ = IntArray(levels)
        val origin: Vec3
        val cameraI = DoubleArray(levels)
        val cameraJ = DoubleArray(levels)
        val desiredSpacing: Double

        init {
            val up = centre.copy().normalizeInPlace()
            chooseFace(up)
            val along = up dot faceAxis
            val u = CubeSphere.unwarp((up dot faceU) / along)
            val v = CubeSphere.unwarp((up dot faceV) / along)
            // From well above, the finest grids are finer than you can see and cost the most with a
            // fast camera, so they're left out.
            val height = kotlin.math.max(0.0, centre.length - body.radius)
            desiredSpacing = height * FINEST_SHARE
            var k = 0
            while (k < levels - 1 && spacing(k) < height * FINEST_SHARE) k++
            first = k
            for (level in 0 until levels) {
                val h = step(level)
                cameraI[level] = u / h
                cameraJ[level] = v / h
                middleI[level] = 2 * Math.round(u / (2.0 * h)).toInt()
                middleJ[level] = 2 * Math.round(v / (2.0 * h)).toInt()
            }
            val top = levels - 1
            val h = step(top)
            origin = point(middleI[top], middleJ[top], h, Vec3()).mulInPlace(body.radius)
        }

        /** Grid [level]'s first lattice index across and up. */
        fun startI(level: Int) = middleI[level] - cells / 2
        fun startJ(level: Int) = middleJ[level] - cells / 2

        /** Grid [level]'s hole (where the grid inside it is), as its first cell across and up; -1 for the finest. */
        fun holeI(level: Int) = if (level <= first) -1 else startI(level - 1) / 2 - startI(level)
        fun holeJ(level: Int) = if (level <= first) -1 else startJ(level - 1) / 2 - startJ(level)

        /** Where vertex (i, j) of grid [level] is in the arrays. */
        fun index(level: Int, i: Int, j: Int) = (level - first) * perLevel + j * side + i

        val count: Int get() = (levels - first) * perLevel

        /** Whether cell (i, j) of grid [level] is drawn: not in the hole the grid inside fills. */
        fun drawn(level: Int, i: Int, j: Int): Boolean {
            if (level <= first) return true
            val hi = holeI(level); val hj = holeJ(level)
            return !(i >= hi && i < hi + cells / 2 && j >= hj && j < hj + cells / 2)
        }

        /** Whether vertex (i, j) of grid [level] is used by a drawn cell. */
        fun used(level: Int, i: Int, j: Int): Boolean {
            if (level <= first) return true
            // The innermost grid needs the whole next grid while it fades out with altitude.
            if (level == first + 1 && altitudeMorph(first) > 0.0) return true
            val hi = holeI(level); val hj = holeJ(level)
            return !(i > hi + MORPH_PADDING && i < hi + cells / 2 - MORPH_PADDING &&
                j > hj + MORPH_PADDING && j < hj + cells / 2 - MORPH_PADDING)
        }

        fun altitudeMorph(level: Int) = GridMorph.smooth(spacing(level) * 0.5, spacing(level), desiredSpacing)

        /** Fully on the next grid before a snapped boundary can pass this point. */
        fun morph(level: Int, i: Double, j: Double): Double {
            val away = maxOf(kotlin.math.abs(startI(level) + i - cameraI[level]), kotlin.math.abs(startJ(level) + j - cameraJ[level]))
            return maxOf(altitudeMorph(level), GridMorph.smooth(cells / 2.0 - 6.0, cells / 2.0 - 3.0, away))
        }

        /** Whether vertex (i, j) of grid [level] is on its edge, taken from the grid outside it. */
        fun edge(level: Int, i: Int, j: Int) = level < levels - 1 && (i == 0 || j == 0 || i == cells || j == cells)

        fun indices(): IntArray {
            val list = ArrayList<Int>()
            for (level in first until levels) {
                for (j in 0 until cells) for (i in 0 until cells) {
                    if (!drawn(level, i, j)) continue
                    val a = index(level, i, j); val b = index(level, i + 1, j)
                    val c = index(level, i, j + 1); val d = index(level, i + 1, j + 1)
                    // Counterclockwise from above: U, then V. Each triangle takes its colour from
                    // its last corner, so the two end on different ones (c and b), as the ground's
                    // do; otherwise the sea shows in squares beside the ground's triangles.
                    list.add(a); list.add(b); list.add(c)
                    list.add(d); list.add(c); list.add(b)
                }
            }
            return list.toIntArray()
        }

        /**
         * Which grids are drawn and where their holes are, into [out]: when it changes, the
         * triangles do. Returns how many numbers it took.
         */
        fun shape(out: IntArray): Int {
            var n = 0
            out[n++] = first
            for (level in first + 1 until levels) { out[n++] = holeI(level); out[n++] = holeJ(level) }
            return n
        }
    }

    /** The last number given to a set of triangles, for [SeaSurface.layout]. */
    private var lastLayout = 0

    /** The grids' triangles for the last shape of grids, and their number. */
    private val gridShape = IntArray(1 + 2 * levels)
    private var gridShapeSize = -1
    private var gridIndices = IntArray(0)
    private var gridLayout = 0

    /**
     * The last coast's triangles, the shape and coast cells they were made for, and their number.
     * Published arrays are never written again: a new coast gets new ones.
     */
    private val coastShape = IntArray(1 + 2 * levels)
    private var coastShapeSize = -1
    private var coastKept = IntArray(0)
    private var coastIndices = IntArray(0)
    private var coastLayout = 0

    /** Scratch for a layout's shape. Only touched under the lock. */
    private val shapeScratch = IntArray(1 + 2 * levels)

    /** A surface on [layout]'s grids with no coast. Their triangles are made again only when the shape changes. */
    private fun gridSurface(layout: Layout, vertices: FloatArray, time: Double, giveBack: ((FloatArray) -> Unit)?): SeaSurface =
        synchronized(this) {
            val n = layout.shape(shapeScratch)
            if (!sameShape(gridShape, gridShapeSize, n)) {
                shapeScratch.copyInto(gridShape, 0, 0, n)
                gridShapeSize = n
                gridIndices = layout.indices()
                gridLayout = ++lastLayout
            }
            SeaSurface(layout.origin, vertices, layout.count, gridIndices, gridLayout, time, giveBack)
        }

    private fun sameShape(kept: IntArray, keptSize: Int, n: Int): Boolean {
        if (keptSize != n) return false
        for (k in 0 until n) if (kept[k] != shapeScratch[k]) return false
        return true
    }

    /**
     * Picks the cube face for the grids under a camera over unit [up]. Keeps the current one until
     * the camera is well past its edge, so the grids don't jump back and forth.
     */
    private fun chooseFace(up: Vec3) {
        if (face >= 0) {
            val along = up dot faceAxis
            if (along > 0.3 && kotlin.math.abs((up dot faceU) / along) < FACE_KEEP && kotlin.math.abs((up dot faceV) / along) < FACE_KEEP) return
        }
        val ax = kotlin.math.abs(up.x); val ay = kotlin.math.abs(up.y); val az = kotlin.math.abs(up.z)
        face = when {
            ax >= ay && ax >= az -> if (up.x > 0) 0 else 1
            ay >= az -> if (up.y > 0) 2 else 3
            else -> if (up.z > 0) 4 else 5
        }
        when (face) {
            0 -> faceAxis.setTo(1.0, 0.0, 0.0); 1 -> faceAxis.setTo(-1.0, 0.0, 0.0)
            2 -> faceAxis.setTo(0.0, 1.0, 0.0); 3 -> faceAxis.setTo(0.0, -1.0, 0.0)
            4 -> faceAxis.setTo(0.0, 0.0, 1.0); else -> faceAxis.setTo(0.0, 0.0, -1.0)
        }
        if (face == 2 || face == 3) faceU.setTo(1.0, 0.0, 0.0).crossInPlace(faceAxis).normalizeInPlace()
        else faceU.setTo(0.0, 1.0, 0.0).crossInPlace(faceAxis).normalizeInPlace()
        faceV.setTo(faceAxis).crossInPlace(faceU)
        // A new face: nothing kept is where it was.
        for (times in preparedTime) times.fill(Double.NaN)
    }

    /**
     * Vertex arrays the GPU is done with, to build into again rather than churn a megabyte ten
     * times a second.
     */
    private val spare = com.rm.apogee.core.ConcurrentQueue<FloatArray>()
    private val giveBack: (FloatArray) -> Unit = { if (spare.size < SPARES) spare.add(it) }

    /**
     * A vertex array with its first [used] floats zero: a spare one if there's one, or a new one.
     * Every build's is the same size, room for the most grids and coast there can be.
     */
    private fun vertexArray(used: Int): FloatArray {
        val size = (levels * perLevel + MAX_REFINED * SUB_SIDE * SUB_SIDE) * SeaSurface.STRIDE
        while (true) {
            val kept = spare.poll() ?: return FloatArray(size)
            if (kept.size == size) return kept.also { it.fill(0f, 0, used) }
        }
    }

    /** Scratch for one build at a time: each grid vertex's depth, and the coast cells. */
    private val depthScratch = FloatArray(levels * perLevel)
    private val coastCells = IntArray(MAX_REFINED)
    private val coastSet = BooleanArray(levels * cells * cells)
    private var triangleScratch = IntArray(0)

    /** How long the outer sea gets to be worked out each build, in ns. Tests lift it to stay repeatable. */
    internal var outerBudgetNanos = OUTER_BUDGET_NANOS

    /** How many coast cells the last build split, for tests. */
    internal var lastCoastCount = 0
        private set

    /** Builds the sea round [centre] at [time] and waits for it, for tests. */
    internal suspend fun buildNow(centre: Vec3, time: Double): SeaSurface =
        kotlinx.coroutines.coroutineScope { build(this, centre, time) }

    private suspend fun build(scope: kotlinx.coroutines.CoroutineScope, centre: Vec3, time: Double): SeaSurface {
        val layout = Layout(centre)
        val origin = layout.origin
        // Room for the coast's finer cells after the grids.
        val vertices = vertexArray((layout.count + MAX_REFINED * SUB_SIDE * SUB_SIDE) * SeaSurface.STRIDE)
        val depths = depthScratch.also { it.fill(0f, 0, layout.count) }
        val cameraUp = centre.copy().normalizeInPlace()
        filterUp.setTo(cameraUp)
        filterFinest = kotlin.math.max(finest, layout.desiredSpacing)
        buildField = tsunamiField
        builders[0].let { b ->
            b.sea.prepare(cameraUp, time, spacing(layout.first), Sea.Prepared()).let { p -> b.sea.surface(cameraUp, time, p, b.sample, spacing(layout.first)) }
        }
        val tide = builders[0].sample.tide
        retiding = builders[0].sea.tsunamiPassing(cameraUp, time)
        // Somewhere new (the first build, or flying fast over fresh sea) the outer sea state takes
        // seconds while terrain builds too. Near the camera it's always worked out; further off
        // only within a budget, with later builds filling in and flat water meanwhile.
        val deadline = System.nanoTime() + outerBudgetNanos
        // Rows are dealt out in turn, so each worker gets near and far alike.
        // Row r is grid first + r / side, row r % side.
        val rows = (levels - layout.first) * side
        val jobs = builders.mapIndexed { w, builder ->
            scope.async(BUILD) {
                var r = w
                while (r < rows) {
                    val level = layout.first + r / side; val j = r % side
                    val si = layout.startI(level); val sj = layout.startJ(level)
                    val h = spacing(level)
                    for (i in 0..cells) {
                        if (!layout.used(level, i, j) || layout.edge(level, i, j)) continue
                        // Roughly how far from the camera, for what has to be worked out now.
                        val di = (si + i - layout.middleI[layout.first]) * h
                        val dj = (sj + j - layout.middleJ[layout.first]) * h
                        val near = di * di + dj * dj <= NEAR_REACH * NEAR_REACH
                        builder.vertex(vertices, layout.index(level, i, j), origin, level, si + i, sj + j, time, near, deadline, tide, depths)
                    }
                    r += builders.size
                }
                com.rm.apogee.platform.AllocProbe.note("sea-build")
            }
        }
        jobs.forEach { it.await() }
        // Work inward: the outer grid is already morphed before the inner one samples it.
        val target = FloatArray(SeaSurface.STRIDE)
        for (level in levels - 2 downTo layout.first) {
            val si = layout.startI(level); val sj = layout.startJ(level)
            val ci = layout.startI(level + 1); val cj = layout.startJ(level + 1)
            for (j in 0..cells) for (i in 0..cells) {
                if (!layout.used(level, i, j)) continue
                val gi = si + i; val gj = sj + j
                val edge = layout.edge(level, i, j)
                val amount = if (edge) 1.0 else layout.morph(level, i.toDouble(), j.toDouble())
                if (amount == 0.0) continue
                val x = gi * 0.5 - ci; val y = gj * 0.5 - cj
                GridMorph.sample(vertices, side, SeaSurface.STRIDE, x, y, target, 0, layout.index(level + 1, 0, 0) * SeaSurface.STRIDE)
                val index = layout.index(level, i, j)
                morphVertex(vertices, index, target, amount.toFloat())
                if (edge) {
                    val i0 = kotlin.math.floor(x).toInt(); val j0 = kotlin.math.floor(y).toInt()
                    val i1 = kotlin.math.ceil(x).toInt(); val j1 = kotlin.math.ceil(y).toInt()
                    depths[index] = 0.5f * (depths[layout.index(level + 1, i0, j0)] + depths[layout.index(level + 1, i1, j1)])
                }
            }
        }
        val coast = coast(scope, layout, vertices, depths, origin, time)
        lastCoastCount = coast
        if (coast == 0) return gridSurface(layout, vertices, time, giveBack)
        return synchronized(this) {
            val indices = coastTriangles(layout, coast)
            SeaSurface(origin, vertices, layout.count + coast * SUB_SIDE * SUB_SIDE, indices, coastLayout, time, giveBack)
        }
    }

    /**
     * The coast, finer: each drawn cell with water and dry land among its corners is split
     * [SUB] by [SUB], on the lattice [SUB_SHIFT] grids finer, so the sea meets the ground in
     * triangles near its own size instead of the grid's. The open sea isn't touched. Leaves those
     * cells in [coastCells], packed as [packCell], and marked in [coastSet], with their points
     * written after the grids', and returns how many.
     */
    private suspend fun coast(
        scope: kotlinx.coroutines.CoroutineScope, layout: Layout, vertices: FloatArray, depths: FloatArray, origin: Vec3, time: Double,
    ): Int {
        val found = coastCells
        var count = 0
        search@ for (level in kotlin.math.max(layout.first, SUB_SHIFT) until levels) {
            for (j in 0 until cells) for (i in 0 until cells) {
                if (!layout.drawn(level, i, j)) continue
                val d0 = depths[layout.index(level, i, j)]; val d1 = depths[layout.index(level, i + 1, j)]
                val d2 = depths[layout.index(level, i, j + 1)]; val d3 = depths[layout.index(level, i + 1, j + 1)]
                val lo = minOf(minOf(d0, d1), minOf(d2, d3)); val hi = maxOf(maxOf(d0, d1), maxOf(d2, d3))
                if (lo <= 0f && hi > 0f && hi < Float.MAX_VALUE) {
                    found[count++] = packCell(level, i, j)
                    if (count >= MAX_REFINED) break@search
                }
            }
        }
        if (count == 0) return 0
        val set = coastSet.also { it.fill(false) }
        for (k in 0 until count) set[found[k]] = true
        val jobs = builders.mapIndexed { w, builder ->
            scope.async(BUILD) {
                var k = w
                while (k < count) {
                    val cell = found[k]
                    val level = cellLevel(cell); val i = cellI(cell); val j = cellJ(cell)
                    val sub = level - SUB_SHIFT
                    val gi0 = (layout.startI(level) + i) * SUB; val gj0 = (layout.startJ(level) + j) * SUB
                    val base = layout.count + k * SUB_SIDE * SUB_SIDE
                    for (b in 0..SUB) for (a in 0..SUB) {
                        val corner = (a == 0 || a == SUB) && (b == 0 || b == SUB)
                        if (corner) continue
                        val into = base + b * SUB_SIDE + a
                        // On a side shared with a cell that isn't split, on the straight line between
                        // its corners, so the two meet with no crack.
                        val acrossSplit = when {
                            a == 0 -> i > 0 && set[packCell(level, i - 1, j)]
                            a == SUB -> i < cells - 1 && set[packCell(level, i + 1, j)]
                            b == 0 -> j > 0 && set[packCell(level, i, j - 1)]
                            b == SUB -> j < cells - 1 && set[packCell(level, i, j + 1)]
                            else -> false
                        }
                        val onSide = a == 0 || a == SUB || b == 0 || b == SUB
                        if (onSide && !acrossSplit) {
                            if (a == 0 || a == SUB) {
                                val ii = if (a == 0) i else i + 1
                                mix(vertices, into, layout.index(level, ii, j), layout.index(level, ii, j + 1), b / SUB.toFloat())
                            } else {
                                val jj = if (b == 0) j else j + 1
                                mix(vertices, into, layout.index(level, i, jj), layout.index(level, i + 1, jj), a / SUB.toFloat())
                            }
                        } else {
                            builder.refinedVertex(vertices, into, origin, sub, gi0 + a, gj0 + b, time)
                            if (level < levels - 1) {
                                val x = i + a / SUB.toDouble(); val y = j + b / SUB.toDouble()
                                val amount = layout.morph(level, x, y)
                                if (amount > 0.0) {
                                    val target = builder.morphTarget
                                    GridMorph.sample(vertices, side, SeaSurface.STRIDE,
                                        (layout.startI(level) + x) * 0.5 - layout.startI(level + 1),
                                        (layout.startJ(level) + y) * 0.5 - layout.startJ(level + 1),
                                        target, 0, layout.index(level + 1, 0, 0) * SeaSurface.STRIDE)
                                    morphVertex(vertices, into, target, amount.toFloat())
                                }
                            }
                        }
                    }
                    k += builders.size
                }
                com.rm.apogee.platform.AllocProbe.note("sea-build")
            }
        }
        jobs.forEach { it.await() }
        return count
    }

    /**
     * The grids' triangles without the [count] coast cells in [coastCells], and the coast's own
     * instead. Made again only when the shape or the cells change; their number is [coastLayout].
     */
    private fun coastTriangles(layout: Layout, count: Int): IntArray = synchronized(this) {
        val n = layout.shape(shapeScratch)
        var same = sameShape(coastShape, coastShapeSize, n) && coastKept.size == count
        var k = 0
        while (same && k < count) { same = coastKept[k] == coastCells[k]; k++ }
        if (!same) {
            shapeScratch.copyInto(coastShape, 0, 0, n)
            coastShapeSize = n
            coastKept = coastCells.copyOf(count)
            coastIndices = coastTrianglesNew(layout, count)
            coastLayout = ++lastLayout
        }
        coastIndices
    }

    private fun coastTrianglesNew(layout: Layout, count: Int): IntArray {
        val split = coastSet
        val need = (levels - layout.first) * cells * cells * 6 + count * SUB * SUB * 6
        if (triangleScratch.size < need) triangleScratch = IntArray(need)
        val list = triangleScratch
        var n = 0
        fun add(a: Int, b: Int, c: Int) { list[n++] = a; list[n++] = b; list[n++] = c }
        for (level in layout.first until levels) for (j in 0 until cells) for (i in 0 until cells) {
            if (!layout.drawn(level, i, j) || split[packCell(level, i, j)]) continue
            val a = layout.index(level, i, j); val b = layout.index(level, i + 1, j)
            val c = layout.index(level, i, j + 1); val d = layout.index(level, i + 1, j + 1)
            add(a, b, c); add(d, c, b)
        }
        for (k in 0 until count) {
            val cell = coastCells[k]
            val level = cellLevel(cell); val i = cellI(cell); val j = cellJ(cell)
            val base = layout.count + k * SUB_SIDE * SUB_SIDE
            fun at(a: Int, b: Int): Int = when {
                a == 0 && b == 0 -> layout.index(level, i, j)
                a == SUB && b == 0 -> layout.index(level, i + 1, j)
                a == 0 && b == SUB -> layout.index(level, i, j + 1)
                a == SUB && b == SUB -> layout.index(level, i + 1, j + 1)
                else -> base + b * SUB_SIDE + a
            }
            for (b in 0 until SUB) for (a in 0 until SUB) {
                val p = at(a, b); val q = at(a + 1, b); val r = at(a, b + 1); val s = at(a + 1, b + 1)
                add(p, q, r); add(s, r, q)
            }
        }
        return list.copyOf(n)
    }

    private fun packCell(level: Int, i: Int, j: Int): Int = (level * cells + j) * cells + i
    private fun cellLevel(cell: Int): Int = cell / (cells * cells)
    private fun cellI(cell: Int): Int = cell % cells
    private fun cellJ(cell: Int): Int = cell / cells % cells

    /** Vertex [into] as [a] blended toward [b] by [t]. */
    private fun mix(vertices: FloatArray, into: Int, a: Int, b: Int, t: Float) {
        val o = into * SeaSurface.STRIDE; val pa = a * SeaSurface.STRIDE; val pb = b * SeaSurface.STRIDE
        val ya = vertices[pa + 9]; val yb = vertices[pb + 9]
        for (n in 0 until SeaSurface.STRIDE) vertices[o + n] = vertices[pa + n] + (vertices[pb + n] - vertices[pa + n]) * t
        vertices[o + 9] = mixTsunami(ya, yb, t)
    }

    private fun morphVertex(vertices: FloatArray, into: Int, target: FloatArray, amount: Float) {
        val o = into * SeaSurface.STRIDE
        val own = vertices[o + 9]
        for (n in 0 until SeaSurface.STRIDE) vertices[o + n] += (target[n] - vertices[o + n]) * amount
        vertices[o + 9] = mixTsunami(own, target[9], amount, keep = true)
    }

    /** A coast point's sea state and when it was worked out. */
    private class Stamped(val prepared: Sea.Prepared, var time: Double) {
        /** The build time it was last retided for, so it's done once a build, under the lock. */
        var retidedAt = Double.NaN
    }

    /** The coast's sea state, by lattice point, apart from the grids' tables. Locked on itself. */
    private val refinedPrepared = com.rm.apogee.render.LongMap<Stamped>(1_024)


    /**
     * The grids laid flat at the datum in deep water's colour round body-fixed [centre]. No sea is
     * worked out, so it's quick enough for the frame thread. Only used until the first build lands.
     */
    private fun placeholder(centre: Vec3, time: Double): SeaSurface {
        val layout = Layout(centre)
        val vertices = FloatArray(layout.count * SeaSurface.STRIDE)
        val d = Vec3()
        for (level in layout.first until levels) {
            val h = step(level)
            for (j in 0..cells) for (i in 0..cells) {
                point(layout.startI(level) + i, layout.startJ(level) + j, h, d)
                flatVertex(vertices, layout.index(level, i, j), d, layout.origin, body.radius)
            }
        }
        return gridSurface(layout, vertices, time, null)
    }

    /**
     * The water's colour and opacity here: deep blue at sea, turquoise and clear over the shallows,
     * lighter on crests, grey-green under a storm, and white where it breaks (whitecaps, storm
     * crests, surf).
     */
    private fun colour(s: SeaSample, at: Vec3, out: FloatArray, o: Int, spacing: Double) {
        val depth = s.depth
        val shallow = 1.0 - smooth(1.0, 14.0, depth)
        val mid = 1.0 - smooth(10.0, 60.0, depth)
        var r = DEEP_R + (MID_R - DEEP_R) * mid + (SHALLOW_R - MID_R) * shallow
        var g = DEEP_G + (MID_G - DEEP_G) * mid + (SHALLOW_G - MID_G) * shallow
        var b = DEEP_B + (MID_B - DEEP_B) * mid + (SHALLOW_B - MID_B) * shallow
        if (methane) {
            // Liquid methane: dark and brown, glassy, and amber over the shallows.
            r = 0.06 + 0.10 * shallow; g = 0.045 + 0.07 * shallow; b = 0.025 + 0.03 * shallow
        }
        // Crests are lighter and greener, where light comes through.
        val crest = if (s.significantHeight > 0.05) smooth(0.1, 0.6, (s.height - s.tide) / s.significantHeight) else 0.0
        if (!methane) { r += 0.03 * crest; g += 0.09 * crest; b += 0.05 * crest }
        // A storm sea: grey and hard.
        val storm = smooth(1.0, 8.0, s.stormHeight)
        r += (STORM_R - r) * storm * 0.7; g += (STORM_G - g) * storm * 0.7; b += (STORM_B - b) * storm * 0.7
        // A tsunami: muddy over the land it floods and silty for a while behind its bore. Its foam
        // is drawn per pixel by the shader, from the vertex's tsunami crest and whiteness.
        var mud = 0.0
        if ((s.tsunami != 0.0 || s.tsunamiWhiteness > 0.0) && !methane) {
            val since = s.tsunamiCrest
            val silt = if (s.tsunamiWhiteness > 0.0 && s.tsunamiPeriod > 0.0) {
                // Seconds since the nearest crest, soft at both ends so its edge doesn't follow the foam's.
                val c = since - s.tsunamiPeriod * kotlin.math.floor(since / s.tsunamiPeriod + 0.5).coerceAtLeast(0.0)
                s.tsunamiWhiteness * smooth(-60.0, 0.0, c) * (1.0 - smooth(60.0, SILT_SECONDS, c))
            } else 0.0
            mud = if (s.tide - s.depth > 0.0) smooth(0.0, 0.6, depth) else silt * BORE_SILT
            r += (MUD_R - r) * mud; g += (MUD_G - g) * mud; b += (MUD_B - b) * mud
        }
        // Foam's ragged edge: each facet foams or not by its own hash against the foam amount. The
        // grain is never finer than the grid, so it isn't noise far off.
        val r0 = body.radius / kotlin.math.max(1.0 / FOAM_GRAIN, spacing)
        val speckle = com.rm.apogee.core.terrain.Noise.hash(
            0xF0A, Math.floor(at.x * r0).toInt(), Math.floor(at.y * r0).toInt(), Math.floor(at.z * r0).toInt(),
        )
        // Foam only where the grid is fine enough to draw it, fading as facets coarsen. Far out
        // each white facet would be a big grey diamond, so there it's only a paler tint.
        val fine = 1.0 - smooth(SURF_SPACING, SURF_SPACING * 2.5, spacing)
        // No surf over flooded land: its own churn shows there instead.
        val surf = if (depth in 0.0..1.2 && s.significantHeight > 0.2) (1.0 - depth / 1.2) * (1.0 - mud) else 0.0
        val breaking = kotlin.math.max(s.breaking, surf)
        val foam = SurfFoam.amount(s.breaking, (s.height - s.tide) / kotlin.math.max(s.significantHeight, 0.05), s.rise, surf) * fine
        val white = if (foam > 0.05 && speckle < foam * FOAM_COVER) 1.0 else 0.0
        val tint = breaking * (1.0 - fine) * FAR_SURF_TINT
        val lift = kotlin.math.max(white, tint)
        r += (FOAM - r) * lift; g += (FOAM - g) * lift; b += (FOAM_B - b) * lift
        out[o] = r.toFloat(); out[o + 1] = g.toFloat(); out[o + 2] = b.toFloat()
        // See-through over the shallows, and solid where it's deep or foaming.
        val alpha = (0.35 + 0.57 * smooth(0.5, 20.0, depth)).coerceAtLeast(white).coerceAtLeast(MUD_ALPHA * mud)
        out[o + 3] = alpha.toFloat()
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private companion object {
        /**
         * A tsunami's whiteness (-1..1) and its period (whole seconds) in one float, for a vertex:
         * 4 x period + whiteness. Shaders.SEA_VERTEX takes them apart, so each tsunami's foam is
         * wrapped by its own period where two overlap.
         */
        fun packTsunami(whiteness: Double, period: Double): Float =
            (4.0 * kotlin.math.round(period) + whiteness.coerceIn(-1.0, 1.0)).toFloat()

        fun tsunamiPeriodOf(packed: Float): Float = kotlin.math.floor((packed + 2f) * 0.25f)

        /**
         * Packed tsunami [a] toward [b] by [t]: the whiteness mixed where both have the same
         * period, otherwise the nearer one's whole, or [a]'s if [keep].
         */
        fun mixTsunami(a: Float, b: Float, t: Float, keep: Boolean = false): Float {
            val pa = tsunamiPeriodOf(a); val pb = tsunamiPeriodOf(b)
            if (pa == pb) return a + (b - a) * t
            return if (keep || t < 0.5f) a else b
        }


        /** Spare vertex arrays kept for building into. */

        const val SPARES = 3

        /** Coarse samples inside each hole used by the finer grid's transition band. */
        const val MORPH_PADDING = 4

        /** A coast cell is split this many ways each way, which is [SUB_SHIFT] grids finer. */
        const val SUB = 4
        const val SUB_SHIFT = 2
        const val SUB_SIDE = SUB + 1

        /** The most coast cells split in one build, and coast points kept between builds. */
        const val MAX_REFINED = 1_500
        const val REFINED_KEEP = 80_000

        /**
         * The drawn waves' spacing, as distance over cells times this. 4 makes it the grid's own at
         * the inner edge of each grid and twice it at the outer edge, the same on both sides of one.
         */
        const val FILTER_SHARE = 4.0

        /** Workers sharing a build. */
        const val WORKERS = 2

        /** The furthest ahead a build is made for, in seconds. */
        const val MAX_AHEAD = 0.5

        /** Out to here, in metres, the sea is worked out in full on every build. */
        const val NEAR_REACH = 2_000.0

        /**
         * Shares of [reach] over which the waves' tsunami crests go over to the far water's: where
         * that water rises under them and where they start to fade into it (Shaders' SEA_RISE and
         * SEA_BLEND).
         */
        const val FAR_CREST_FROM = 0.6
        const val FAR_CREST_TO = 0.8

        /** How long a build can spend working out the sea beyond [NEAR_REACH] afresh, in ns. */
        const val OUTER_BUDGET_NANOS = 200_000_000L

        /** Builds no closer together than this, in ns: ten a second. */
        const val MIN_BUILD_NANOS = 100_000_000L

        /** And while the world is falling behind the warp asked for, five a second. */
        const val BEHIND_BUILD_NANOS = 200_000_000L

        /**
         * The sea's own threads, just below normal priority, not the shared pool. A build is long
         * and never pauses, and with terrain and scatter it could take every shared thread on a
         * four-core phone, including the game server's, and stop the world.
         */
        val BUILD = workerPool("sea-build", WORKERS)

        /**
         * A grid's [Sea.Prepared] is redone once it's this many seconds old (up to half again,
         * staggered).
         */
        const val REPREPARE_SECONDS = 3.0

        /** How far past [reach] the coarsest grid goes, as a share of it. */
        const val REACH_MARGIN = 1.5

        /** The finest grid drawn is no finer than this share of the camera's height above the sea. */
        const val FINEST_SHARE = 0.005

        /**
         * How far past its face's edge, in face coordinates (1 is the edge), the camera goes before
         * the grids move face.
         */
        const val FACE_KEEP = 1.15

        // Deep ocean, the middle depths, and turquoise shallows.
        const val DEEP_R = 0.03; const val DEEP_G = 0.20; const val DEEP_B = 0.42
        const val DEEP_ALPHA = 0.92f
        const val MID_R = 0.05; const val MID_G = 0.40; const val MID_B = 0.60
        const val SHALLOW_R = 0.14; const val SHALLOW_G = 0.72; const val SHALLOW_B = 0.70
        const val STORM_R = 0.20; const val STORM_G = 0.30; const val STORM_B = 0.33
        const val FOAM = 0.93; const val FOAM_B = 0.97

        /**
         * Water over dry land is drawn this far under it, in metres, or this share of grid spacing
         * if more.
         */
        const val DRY_SINK = 2.0
        const val DRY_SINK_SHARE = 0.03

        /**
         * Surf is drawn fully where the grid is finer than this, in metres, fading out by 2.5 times
         * it.
         */
        const val SURF_SPACING = 25.0

        /**
         * How far over the foam amount a facet's speckle can be and still be white. Over 1, so a
         * breaking crest's middle is solid and only its edges are ragged.
         */
        const val FOAM_COVER = 1.15

        /** How much paler surf too far off to draw makes the water. */
        const val FAR_SURF_TINT = 0.18

        /** Flood water over land: brown and hardly see-through. */
        const val MUD_R = 0.36; const val MUD_G = 0.32; const val MUD_B = 0.24
        const val MUD_ALPHA = 0.85


        /** How muddy a breaking bore's water is, 0..1, and for how long behind it, in seconds. */
        const val BORE_SILT = 0.5
        const val SILT_SECONDS = 240.0

        /** Foam patches per metre, as the grain its speckle is hashed on. */
        const val FOAM_GRAIN = 0.35
    }
}

/**
 * How much foam there is where the sea breaks, 0..1. It rides the waves: solid along each breaking
 * crest, thinning to lace behind as the water falls away, none in the trough ahead.
 */
internal object SurfFoam {
    /**
     * [breaking] from the sea; [crest] is the height over the tide in significant heights (about
     * 0.5 on a crest, -0.5 in a trough); [rise] is how fast the water is rising; [swash] at the
     * waterline is white all the way.
     */
    fun amount(breaking: Double, crest: Double, rise: Double, swash: Double): Double {
        if (breaking <= 0.0) return swash
        val onCrest = smooth(0.05, 0.35, crest)
        // Behind a crest the water's falling and its foam is thinning.
        val trail = if (rise < 0.0) 0.5 * smooth(-0.35, 0.05, crest) else 0.0
        return maxOf(swash, breaking * maxOf(onCrest, trail))
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
