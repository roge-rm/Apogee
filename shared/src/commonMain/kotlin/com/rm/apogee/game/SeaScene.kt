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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.rm.apogee.platform.System
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile
import com.rm.apogee.platform.synchronized

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
    val reach: Double = when (tier) {
        QualityTier.LOW -> 6_000.0
        QualityTier.MEDIUM -> 12_000.0
        QualityTier.HIGH -> 20_000.0
    }

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
        val at = centre.copy()
        // No more than [MAX_AHEAD] ahead. A slow first build would otherwise send the next one so
        // far on that every prepared point would be stale, making it slow in turn.
        val rate = warp.coerceAtLeast(1.0)
        val gap = if (behind) BEHIND_BUILD_NANOS else MIN_BUILD_NANOS
        val ahead = time + kotlin.math.min((lastBuildMillis / 1_000.0 + gap / 2e9) * rate, MAX_AHEAD * rate)
        scope.launch(BUILD) {
            val started = System.nanoTime()
            try {
                latest = kotlinx.coroutines.coroutineScope { build(this, at, ahead) }
                built = true
                lastBuildMillis = (System.nanoTime() - started) / 1e6
                // The sea state round the craft for the next while, worked out here rather than in
                // the flight step when it gets there.
                builders[0].sea.prefetch(at, ahead)
            } finally {
                building = false
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
        val direction = Vec3()

        /**
         * Lattice point ([gi], [gj]) of grid [level] into vertex [index] of [out]. Past [deadline]
         * (ns), a point that isn't [near] isn't prepared any more: it keeps its last preparation,
         * or is drawn as flat water at [tide].
         */
        fun vertex(
            out: FloatArray, index: Int, origin: Vec3, level: Int, gi: Int, gj: Int, time: Double,
            near: Boolean = true, deadline: Long = 0L, tide: Double = 0.0,
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
                    return
                }
            }
            sea.surface(direction, time, p, sample, spacing)
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
            out[o + 3] = direction.x.toFloat(); out[o + 4] = direction.y.toFloat(); out[o + 5] = direction.z.toFloat()
            colour(sample, direction, out, o + 6, spacing)
            out[o + 10] = sample.rise.toFloat()
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
        out[o + 3] = direction.x.toFloat(); out[o + 4] = direction.y.toFloat(); out[o + 5] = direction.z.toFloat()
        out[o + 6] = DEEP_R.toFloat(); out[o + 7] = DEEP_G.toFloat(); out[o + 8] = DEEP_B.toFloat(); out[o + 9] = DEEP_ALPHA
        out[o + 10] = 0f
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

        init {
            val up = centre.copy().normalizeInPlace()
            chooseFace(up)
            val along = up dot faceAxis
            val u = CubeSphere.unwarp((up dot faceU) / along)
            val v = CubeSphere.unwarp((up dot faceV) / along)
            // From well above, the finest grids are finer than you can see and cost the most with a
            // fast camera, so they're left out.
            val height = kotlin.math.max(0.0, centre.length - body.radius)
            var k = 0
            while (k < levels - 1 && spacing(k) < height * FINEST_SHARE) k++
            first = k
            for (level in 0 until levels) {
                val h = step(level)
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
            val hi = holeI(level); val hj = holeJ(level)
            return !(i > hi && i < hi + cells / 2 && j > hj && j < hj + cells / 2)
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

        /** Which grids are drawn and where their holes are: when it changes, the triangles do. */
        val shape: List<Int> get() = buildList {
            add(first)
            for (level in first + 1 until levels) { add(holeI(level)); add(holeJ(level)) }
        }
    }

    /** The triangles for the last shape of grids, and its number for [SeaSurface.layout]. */
    private var lastShape: List<Int>? = null
    private var lastIndices = IntArray(0)
    private var lastLayout = 0

    /** [layout]'s triangles and their layout number, made again only when the shape changes. */
    private fun triangles(layout: Layout): Pair<IntArray, Int> {
        return synchronized(this) {
            val shape = layout.shape
            if (shape != lastShape) {
                lastShape = shape
                lastIndices = layout.indices()
                lastLayout++
            }
            return lastIndices to lastLayout
        }
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

    /** An array of [size] floats, all zero: a spare one if there's one that fits, or a new one. */
    private fun vertexArray(size: Int): FloatArray {
        while (true) {
            val kept = spare.poll() ?: return FloatArray(size)
            if (kept.size == size) return kept.also { it.fill(0f) }
        }
    }

    private suspend fun build(scope: kotlinx.coroutines.CoroutineScope, centre: Vec3, time: Double): SeaSurface {
        val layout = Layout(centre)
        val origin = layout.origin
        val vertices = vertexArray(layout.count * SeaSurface.STRIDE)
        val cameraUp = centre.copy().normalizeInPlace()
        builders[0].let { b ->
            b.sea.prepare(cameraUp, time, spacing(layout.first), Sea.Prepared()).let { p -> b.sea.surface(cameraUp, time, p, b.sample, spacing(layout.first)) }
        }
        val tide = builders[0].sample.tide
        // Somewhere new (the first build, or flying fast over fresh sea) the outer sea state takes
        // seconds while terrain builds too. Near the camera it's always worked out; further off
        // only within a budget, with later builds filling in and flat water meanwhile.
        val deadline = System.nanoTime() + OUTER_BUDGET_NANOS
        // Rows are dealt out in turn, so each worker gets near and far alike.
        val rows = ArrayList<Pair<Int, Int>>()
        for (level in layout.first until levels) for (j in 0..cells) rows.add(level to j)
        val jobs = builders.mapIndexed { w, builder ->
            scope.async(BUILD) {
                var r = w
                while (r < rows.size) {
                    val (level, j) = rows[r]
                    val si = layout.startI(level); val sj = layout.startJ(level)
                    val h = spacing(level)
                    for (i in 0..cells) {
                        if (!layout.used(level, i, j) || layout.edge(level, i, j)) continue
                        // Roughly how far from the camera, for what has to be worked out now.
                        val di = (si + i - layout.middleI[layout.first]) * h
                        val dj = (sj + j - layout.middleJ[layout.first]) * h
                        val near = di * di + dj * dj <= NEAR_REACH * NEAR_REACH
                        builder.vertex(vertices, layout.index(level, i, j), origin, level, si + i, sj + j, time, near, deadline, tide)
                    }
                    r += builders.size
                }
            }
        }
        jobs.forEach { it.await() }
        // Each grid's edge from the grid outside: on its points exactly, and halfway between where
        // the finer grid has an extra point, so they meet with no crack.
        for (level in layout.first until levels - 1) {
            val si = layout.startI(level); val sj = layout.startJ(level)
            val ci = layout.startI(level + 1); val cj = layout.startJ(level + 1)
            for (j in 0..cells) for (i in 0..cells) {
                if (!layout.edge(level, i, j)) continue
                val gi = si + i; val gj = sj + j
                val i0 = Math.floorDiv(gi, 2) - ci; val j0 = Math.floorDiv(gj, 2) - cj
                val i1 = if (gi % 2 != 0) i0 + 1 else i0
                val j1 = if (gj % 2 != 0) j0 + 1 else j0
                blend(vertices, layout.index(level, i, j), layout.index(level + 1, i0, j0), layout.index(level + 1, i1, j1))
            }
        }
        val (indices, number) = triangles(layout)
        return SeaSurface(origin, vertices, layout.count, indices, number, time, giveBack)
    }

    /** Vertex [into] as the average of vertices [a] and [b] (which can be the same). */
    private fun blend(vertices: FloatArray, into: Int, a: Int, b: Int) {
        val o = into * SeaSurface.STRIDE; val pa = a * SeaSurface.STRIDE; val pb = b * SeaSurface.STRIDE
        for (n in 0 until SeaSurface.STRIDE) vertices[o + n] = 0.5f * (vertices[pa + n] + vertices[pb + n])
        // Up stays unit length.
        val x = vertices[o + 3]; val y = vertices[o + 4]; val z = vertices[o + 5]
        val l = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
        vertices[o + 3] = x / l; vertices[o + 4] = y / l; vertices[o + 5] = z / l
    }

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
        val (indices, number) = triangles(layout)
        return SeaSurface(layout.origin, vertices, layout.count, indices, number, time)
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
        // Foam's ragged edge: each facet foams or not by its own hash against the foam amount. The
        // grain is never finer than the grid, so it isn't noise far off.
        val r0 = body.radius / kotlin.math.max(1.0 / FOAM_GRAIN, spacing)
        val speckle = com.rm.apogee.core.terrain.Noise.hash(
            0xF0A, Math.floor(at.x * r0).toInt(), Math.floor(at.y * r0).toInt(), Math.floor(at.z * r0).toInt(),
        )
        // Foam only where the grid is fine enough to draw it, fading as facets coarsen. Far out
        // each white facet would be a big grey diamond, so there it's only a paler tint.
        val fine = 1.0 - smooth(SURF_SPACING, SURF_SPACING * 2.5, spacing)
        val surf = if (depth in 0.0..1.2 && s.significantHeight > 0.2) 1.0 - depth / 1.2 else 0.0
        val breaking = kotlin.math.max(s.breaking, surf)
        val foam = SurfFoam.amount(s.breaking, (s.height - s.tide) / kotlin.math.max(s.significantHeight, 0.05), s.rise, surf) * fine
        val white = if (foam > 0.05 && speckle < foam * FOAM_COVER) 1.0 else 0.0
        val tint = breaking * (1.0 - fine) * FAR_SURF_TINT
        val lift = kotlin.math.max(white, tint)
        r += (FOAM - r) * lift; g += (FOAM - g) * lift; b += (FOAM_B - b) * lift
        out[o] = r.toFloat(); out[o + 1] = g.toFloat(); out[o + 2] = b.toFloat()
        // See-through over the shallows, and solid where it's deep or foaming.
        val alpha = (0.35 + 0.57 * smooth(0.5, 20.0, depth)).coerceAtLeast(white)
        out[o + 3] = alpha.toFloat()
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private companion object {

        /** Spare vertex arrays kept for building into. */

        const val SPARES = 3

        /** Workers sharing a build. */
        const val WORKERS = 2

        /** The furthest ahead a build is made for, in seconds. */
        const val MAX_AHEAD = 0.5

        /** Out to here, in metres, the sea is worked out in full on every build. */
        const val NEAR_REACH = 2_000.0

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
