package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.CubeSphere
import com.rm.apogee.core.terrain.Terrain
import com.rm.apogee.render.ChunkData
import com.rm.apogee.render.ChunkKey
import com.rm.apogee.render.PlanetMesh
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.TerrainChunk
import com.rm.apogee.render.TerrainSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Decides which terrain to draw and builds it, off the render thread.
 *
 * The ground near the craft is a quadtree of [TerrainChunk]s on the cube
 * sphere: a chunk is split into four finer ones when the craft is within a
 * couple of its own widths of it, down to facets the size of the collider's
 * samples under the craft and out to kilometre-wide ones at the horizon. The
 * old approach - one square patch of fixed resolution, rebuilt wholesale as
 * the craft moved - had to choose between detail under the wheels and reach to
 * the horizon, and got neither.
 *
 * Chunks are built on background workers, nearest first, and kept: driving
 * back over ground costs nothing. Until a chunk is built its parent stands in
 * for it, so the ground coarsens briefly rather than vanishing.
 */
class TerrainBuilder(
    private val source: TerrainSource,
    private val quality: QualityTier,
) {
    private var globeBody: CelestialBody? = null
    private var workers: List<Job> = emptyList()

    /**
     * Whether the distant surface - the globe - should be drawn too. True
     * whenever the chunks stop short of the horizon.
     */
    var farSurfaceNeeded: Boolean = true
        private set

    /**
     * Whether the ground under the craft has been built at full detail at
     * least once. The flight view waits on it rather than show a craft
     * hovering over a globe too coarse to have the ground under it.
     */
    @Volatile
    var patchReady: Boolean = false
        private set

    private val globeRings: Int
        get() = when (quality) {
            QualityTier.LOW -> 64
            QualityTier.MEDIUM -> 96
            QualityTier.HIGH -> 128
        }

    /** Levels short of the collider's resolution the finest chunk stops at. */
    private val detailOffset: Int
        get() = when (quality) {
            QualityTier.LOW -> 2
            QualityTier.MEDIUM -> 1
            QualityTier.HIGH -> 0
        }

    /**
     * How close, in chunk widths, a chunk must be before it is split. Larger
     * is finer ground further out, at the cost of triangles.
     *
     * Each level of detail is a ring of about 4 * pi * (k + 0.75)^2 chunks,
     * so this is the lever that sets the triangle count: at 2.4 the ground
     * came to 550 chunks, over a quarter of a million triangles, and more than
     * the GPU budget could hold. These keep each tier's working set well inside
     * [QualityTier.terrainChunkBudget], and the renderer culls the half of it
     * behind the camera.
     */
    private val splitDistance: Double
        get() = when (quality) {
            QualityTier.LOW -> 1.0
            QualityTier.MEDIUM -> 1.25
            QualityTier.HIGH -> 1.5
        }

    // --- state shared with the workers ---------------------------------------

    private val lock = Object()
    /** Chunks wanted and not yet built, nearest first. Replaced each selection. */
    private var wanted: List<ChunkKey> = emptyList()
    private val inFlight = HashSet<ChunkKey>()
    private var terrain: Terrain? = null

    /** Built chunks' centres and bounds, which drawing needs; game thread only. */
    private val built = HashMap<ChunkKey, ChunkData>()

    /** Approximate centre of each chunk ever considered; game thread only. */
    private val centres = HashMap<ChunkKey, Vec3>()

    private var selections = 0L
    private var lostThisSelection = 0
    private val camera = Vec3()
    private val scratch = Vec3()
    private val requests = ArrayList<Pair<Double, ChunkKey>>()

    /**
     * Builds the whole body, once per body. Safe to call every frame.
     *
     * Revisions are numbered across the whole process: the renderer outlives
     * a flight, and a new flight's first globe must not share a number with
     * the last one's, or a launch to Luna would keep drawing Terra.
     */
    fun requestGlobe(body: CelestialBody, scope: CoroutineScope) {
        if (globeBody === body) return
        globeBody = body
        val revision = nextGlobeRevision.incrementAndGet()
        scope.launch(Dispatchers.Default) {
            source.publishGlobe(revision, PlanetMesh.buildGlobe(body.terrain, body.radius, globeRings))
        }
    }

    /**
     * Re-selects the chunks to draw for a craft at [bodyFixedPosition] -
     * position in the body's own turning frame, since terrain turns with it.
     */
    fun followCraft(
        body: CelestialBody,
        bodyFixedPosition: Vec3,
        altitude: Double,
        scope: CoroutineScope,
    ) {
        val field = body.terrain ?: run {
            patchReady = true
            return
        }
        if (terrain !== field) {
            synchronized(lock) { terrain = field }
            built.clear()
            centres.clear()
        }
        if (workers.isEmpty()) startWorkers(scope)

        // Reach: past the horizon, which on a sphere is sqrt(2Rh) away.
        val horizon = kotlin.math.sqrt(2.0 * body.radius * altitude.coerceAtLeast(1.0))
        val range = (horizon * HORIZON_MARGIN).coerceIn(MIN_RANGE_METRES, MAX_RANGE_METRES)
        farSurfaceNeeded = range < horizon || altitude > CHUNK_CEILING_METRES
        if (altitude > CHUNK_CEILING_METRES) {
            source.publishDrawList(emptyList())
            synchronized(lock) { wanted = emptyList() }
            patchReady = true
            return
        }

        camera.setTo(bodyFixedPosition)
        val maxLevel = TerrainChunk.finestLevel(field.tiles.tilesPerFace) - detailOffset

        requests.clear()
        lostThisSelection = 0
        val draw = ArrayList<ChunkData>(256)
        var complete = true
        for (face in 0 until 6) {
            val resolved = resolve(ChunkKey(face, 0, 0, 0, System.identityHashCode(field)), field, maxLevel, range, draw)
            if (!resolved) complete = false
        }
        requests.sortBy { it.first }
        synchronized(lock) { wanted = requests.map { it.second } }
        source.publishDrawList(draw)
        // Ready once nothing near the craft is still coarse. Not merely once
        // something is drawable - the first thing drawable is a
        // hundred-kilometre chunk from the top of the tree, and lifting the
        // loading screen onto that shows a craft on ground that reads as
        // broken.
        if (complete && (requests.isEmpty() || requests.first().first > READY_RADIUS_METRES)) {
            patchReady = true
        }
        if (++selections % 600 == 0L) {
            val levels = draw.groupingBy { it.key.level }.eachCount().toSortedMap()
            val triangles = draw.size * TerrainChunk.CELLS * TerrainChunk.CELLS * 2
            android.util.Log.i(
                "ApogeeTerrain",
                "draw ${draw.size} (~$triangles tris) wanted ${requests.size} (lost $lostThisSelection) built ${built.size} " +
                    "complete=$complete levels=$levels",
            )
        }

        // Forget chunks nobody is drawing once there are a lot of them. Travel
        // leaves a trail of built ground behind the craft; keeping all of it
        // is a leak, and anything forgotten is simply rebuilt if needed again.
        if (built.size > MAX_BUILT) {
            val drawing = draw.mapTo(HashSet()) { it.key }
            val iterator = built.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in drawing) {
                    iterator.remove()
                    source.discarded(entry.key)
                }
            }
        }
    }

    /**
     * Adds to [draw] what should be drawn for [key]'s square, if all of it is
     * ready; otherwise requests what is missing.
     *
     * @return false if nothing for this square could be drawn yet.
     */
    private fun resolve(
        key: ChunkKey,
        field: Terrain,
        maxLevel: Int,
        range: Double,
        draw: MutableList<ChunkData>,
    ): Boolean {
        val size = TerrainChunk.size(field.bodyRadius, key.level)
        val distance = (camera.distanceTo(centre(key, field)) - size * 0.75).coerceAtLeast(0.0)
        if (distance > range) return true // Out of reach: nothing to draw, nothing missing.

        if (key.level < maxLevel && distance < size * splitDistance) {
            val start = draw.size
            var all = true
            for (dj in 0..1) for (di in 0..1) {
                if (!resolve(key.child(di, dj), field, maxLevel, range, draw)) all = false
            }
            if (all) return true
            // Not all four ready: this chunk stands in for them, if it can.
            while (draw.size > start) draw.removeAt(draw.size - 1)
        }

        val ready = built[key]?.takeIf { source.isAvailable(key) }
        if (ready != null) {
            draw.add(ready)
            return true
        }
        if (built.remove(key) != null) lostThisSelection++
        requests.add(distance to key)
        return false
    }

    /** Where a chunk's middle is, near enough to decide its level by. Cached. */
    private fun centre(key: ChunkKey, field: Terrain): Vec3 =
        centres.getOrPut(key) {
            val n = 1 shl key.level
            val s = -1.0 + 2.0 * (key.i + 0.5) / n
            val t = -1.0 + 2.0 * (key.j + 0.5) / n
            val d = CubeSphere.direction(key.face, s, t, Vec3())
            // The real ground height, once: a craft on a mountain three
            // kilometres up is not three kilometres from the chunk under it.
            d.mulInPlace(field.surfaceRadius(d))
        }.also { if (centres.size > MAX_CENTRES) centres.clear() }

    private fun startWorkers(scope: CoroutineScope) {
        workers = List(WORKERS) {
            scope.launch(Dispatchers.Default) {
                while (isActive) {
                    val next = synchronized(lock) {
                        val field = terrain
                        val key = wanted.firstOrNull { it !in inFlight && !source.isAvailable(it) }
                        if (key != null && field != null) {
                            inFlight += key
                            key to field
                        } else {
                            null
                        }
                    }
                    if (next == null) {
                        delay(IDLE_POLL_MILLIS)
                        continue
                    }
                    val (key, field) = next
                    try {
                        val started = System.nanoTime()
                        val data = TerrainChunk.build(field, key)
                        recordBuild(System.nanoTime() - started)
                        source.publishChunk(data)
                        onBuilt(data)
                    } finally {
                        synchronized(lock) { inFlight -= key }
                    }
                }
            }
        }
    }

    private val buildCount = java.util.concurrent.atomic.AtomicLong()
    private val buildNanos = java.util.concurrent.atomic.AtomicLong()

    /** Chunk build cost, logged every so often: the number the LOW tier lives or dies by. */
    private fun recordBuild(nanos: Long) {
        val count = buildCount.incrementAndGet()
        val total = buildNanos.addAndGet(nanos)
        if (count % 200 == 0L) {
            android.util.Log.i("ApogeeTerrain", "chunks built %d, mean %.1f ms".format(count, total / 1e6 / count))
        }
    }

    private val justBuilt = java.util.concurrent.ConcurrentLinkedQueue<ChunkData>()

    private fun onBuilt(data: ChunkData) {
        justBuilt.add(data)
    }

    /** Folds finished builds into [built]; game thread. */
    fun collect() {
        while (true) {
            val data = justBuilt.poll() ?: break
            built[data.key] = data
        }
    }

    fun stop() {
        workers.forEach { it.cancel() }
        workers = emptyList()
        synchronized(lock) {
            wanted = emptyList()
            inFlight.clear()
        }
        built.clear()
        justBuilt.clear()
        patchReady = false
    }

    private companion object {
        val nextGlobeRevision = java.util.concurrent.atomic.AtomicInteger()

        /** A little past the horizon, so the edge of the chunks is never on screen. */
        const val HORIZON_MARGIN = 1.3

        const val MIN_RANGE_METRES = 4_000.0

        /** Matched to the near pass's far plane; past it nothing is drawn. */
        const val MAX_RANGE_METRES = 250_000.0

        /** Above this the globe alone is as much as the eye can resolve. */
        const val CHUNK_CEILING_METRES = 60_000.0

        const val WORKERS = 2
        const val IDLE_POLL_MILLIS = 8L
        const val MAX_CENTRES = 20_000
        const val MAX_BUILT = 1_200

        /** Chunks nearer than this must be at full detail before the view is shown. */
        const val READY_RADIUS_METRES = 1_000.0
    }
}
