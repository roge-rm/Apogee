package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.CubeSphere
import com.rm.apogee.core.terrain.Terrain
import com.rm.apogee.render.ChunkData
import com.rm.apogee.render.DrawEntry
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
import com.rm.apogee.platform.System
import com.rm.apogee.platform.synchronized
import com.rm.apogee.platform.format
import com.rm.apogee.platform.Log
import kotlin.concurrent.Volatile

/**
 * Decides which terrain to draw and builds it, off the render thread.
 *
 * The ground near the craft is a quadtree of [TerrainChunk]s on the cube sphere. A chunk gets split
 * into four finer ones when the craft is within a couple of its own widths of it, down to facets
 * the size of the collider's samples under the craft and out to kilometre-wide ones at the horizon.
 * The old way (one square patch of fixed resolution, rebuilt whole as the craft moved) had to
 * choose between detail under the wheels and reach to the horizon, and got neither.
 *
 * Chunks are built on background workers, nearest first, and kept, so driving back over ground
 * costs nothing. Until a chunk is built its parent stands in for it, so the ground gets coarser for
 * a moment instead of vanishing.
 */
class TerrainBuilder(
    private val source: TerrainSource,
    private val quality: QualityTier,
) {
    private var globeBody: CelestialBody? = null
    private var workers: List<Job> = emptyList()

    /**
     * Whether the distant surface (the globe) should be drawn too. True whenever the chunks stop
     * short of the horizon.
     */
    var farSurfaceNeeded: Boolean = true

    /** How far the chunks reach in this selection, in metres. 0 when none are drawn. */
    var chunkRange: Double = 0.0
        private set

    /**
     * Whether the ground under the craft has been built at full detail at least once. The flight
     * view waits on it instead of showing a craft hovering over a globe too coarse to have the
     * ground under it.
     */
    @Volatile
    var patchReady: Boolean = false
        private set

    private val globeRings: Int
        get() = when (quality) {
            // Twice what they were. At 64 rings a facet was 29 km across, and from just above the
            // chunks' ceiling the ground below looked like a quilt of single-colour blocks.
            QualityTier.LOW -> 128
            QualityTier.MEDIUM -> 192
            QualityTier.HIGH -> 256
        }

    /** The collider's own resolution, as a chunk level: the finest there is. */
    private fun fullLevel(field: Terrain): Int = TerrainChunk.finestLevel(field.tiles.tilesPerFace)

    /**
     * How many levels short of the collider's resolution the finest chunk stops at, beyond
     * [FULL_DETAIL_METRES].
     */
    private val detailOffset: Int
        get() = when (quality) {
            QualityTier.LOW -> 2
            QualityTier.MEDIUM -> 1
            QualityTier.HIGH -> 0
        }

    /**
     * How close, in chunk widths, a chunk has to be before it gets split. Larger means finer ground
     * further out, at the cost of triangles.
     *
     * Each level of detail is a ring of about 4 * pi * (k + 0.75)^2 chunks, so this is the lever
     * that sets the triangle count. At 2.4 the ground came to 550 chunks, over a quarter of a
     * million triangles, and more than the GPU budget could hold. These keep each tier's working
     * set well inside [QualityTier.terrainChunkBudget], and the renderer culls the half of it
     * behind the camera.
     */
    private val splitDistance: Double
        get() = when (quality) {
            QualityTier.LOW -> 1.0
            QualityTier.MEDIUM -> 1.25
            QualityTier.HIGH -> 1.5
        }

    // --- state shared with the workers ---------------------------------------

    private val lock = Any()
    /** Chunks wanted and not built yet, nearest first. Replaced each selection. */
    private var wanted: List<ChunkKey> = emptyList()
    private val inFlight = HashSet<ChunkKey>()
    private var terrain: Terrain? = null

    /** Built chunks' centres and bounds, which drawing needs. Game thread only. */
    private val built = HashMap<ChunkKey, ChunkData>()

    /** The rough centre of each chunk ever considered. Game thread only. */
    private val centres = HashMap<ChunkKey, Vec3>()

    private var selections = 0L

    /**
     * Chunks seen stuck, and the selection they were first seen at: asked for but skipped because
     * the source says it has them, or built and never uploaded. Neither should last. If one does (a
     * chunk lost somewhere between builder, source and GPU), the loading screen waits on it
     * forever. Past [STUCK_SELECTIONS] it's dropped everywhere and built afresh. Game thread only.
     */
    private val stuckSince = HashMap<ChunkKey, Long>()
    private val stuckSeen = HashSet<ChunkKey>()

    /** Set by [stop]. A worker still finishing a chunk throws it away instead of publishing it. */
    @Volatile private var stopped = false
    private var lostThisSelection = 0

    /** Every square the current selection reached: the tree it wants, drawn or not. */
    private val wantedTree = HashSet<ChunkKey>()

    /** The selection each chunk was last drawn in, for choosing what to let go. */
    private val lastUsed = HashMap<ChunkKey, Long>()

    /** How many chunks the GPU can hold. See [QualityTier.terrainChunkBudget]. */
    private val gpuBudget = quality.terrainChunkBudget

    /** Chunks split in the last selection, and this one, for hysteresis. */
    private var splitLast = HashSet<ChunkKey>()
    private var splitNow = HashSet<ChunkKey>()

    /** Every ancestor of a chunk in the last draw list, meaning where finer ground was. */
    private var drawnBelow = HashSet<ChunkKey>()
    private var drawnBelowNext = HashSet<ChunkKey>()

    /** The distance to the nearest chunk built but not uploaded yet, in this selection. */
    private var nearestWaiting = Double.MAX_VALUE
    private val camera = Vec3()
    private val scratch = Vec3()

    /** What the last choice of chunks was made from, to tell when it needn't be made again. */
    private var lastComplete = false
    private var lastField: com.rm.apogee.core.terrain.Terrain? = null
    private var lastChanges = -1
    private var lastRange = 0.0
    private val requests = ArrayList<Pair<Double, ChunkKey>>()

    /**
     * Builds the whole body, once per body. It's safe to call every frame.
     *
     * Revisions are numbered across the whole process. The renderer outlives a flight, and a new
     * flight's first globe mustn't share a number with the last one's, or a launch to Luna would
     * keep drawing Terra.
     */
    fun requestGlobe(body: CelestialBody, scope: CoroutineScope) {
        if (globeBody === body) return
        globeBody = body
        val revision = nextGlobeRevision.incrementAndGet()
        scope.launch(Dispatchers.Default) {
            source.publishGlobe(revision, PlanetMesh.buildGlobe(body.terrain, body.radius, globeRings, body.id))
        }
    }

    /**
     * Picks the chunks to draw again for a craft at [bodyFixedPosition], the position in the body's
     * own turning frame, since terrain turns with it.
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
        chunkRange = if (altitude > CHUNK_CEILING_METRES) 0.0 else range
        // Always, behind the chunks. Mountains stand above the horizon of flat ground (a
        // three-kilometre peak is in view sixty kilometres past it), so chunks reaching only that
        // horizon left whole ranges popping in and out as the craft climbed. Reaching every visible
        // peak with chunks doubled LOW's triangles. The globe, one mesh already built, shows them
        // instead, coarser, and chunks take over as they come in range.
        farSurfaceNeeded = true
        if (altitude > CHUNK_CEILING_METRES) {
            source.publishDrawList(emptyList())
            synchronized(lock) { wanted = emptyList() }
            patchReady = true
            return
        }

        // Nothing to choose again: hardly moved, nothing built, uploaded or let go since, and the
        // last choice had everything it wanted. Chosen afresh anyway, every frame, the whole tree
        // was walked from the top on a phone's frame thread to come to the same answer.
        val changes = source.changes.get()
        if (lastComplete && field === lastField && changes == lastChanges &&
            camera.distanceTo(bodyFixedPosition) < RESELECT_METRES && kotlin.math.abs(range - lastRange) < range * RESELECT_RANGE
        ) return
        lastField = field
        lastChanges = changes
        lastRange = range

        camera.setTo(bodyFixedPosition)
        val maxLevel = TerrainChunk.finestLevel(field.tiles.tilesPerFace) - detailOffset

        requests.clear()
        splitNow.clear()
        wantedTree.clear()
        lostThisSelection = 0
        nearestWaiting = Double.MAX_VALUE
        val draw = ArrayList<DrawEntry>(256)
        var complete = true
        for (face in 0 until 6) {
            val resolved = resolve(ChunkKey(face, 0, 0, 0, field.hashCode()), field, maxLevel, range, draw)
            if (!resolved) complete = false
        }
        requests.sortBy { it.first }
        synchronized(lock) { wanted = requests.map { it.second } }
        source.publishDrawList(draw)
        drawnBelowNext.clear()
        for (chunk in draw) {
            var up = chunk.key.parent
            while (up != null && drawnBelowNext.add(up)) up = up.parent
        }
        drawnBelow = drawnBelowNext.also { drawnBelowNext = drawnBelow }
        splitLast = splitNow.also { splitNow = splitLast }
        // Ready once nothing near the craft is still coarse. Not just once something can be drawn,
        // because the first thing that can be drawn is a hundred-kilometre chunk from the top of
        // the tree, and lifting the loading screen onto that shows a craft on ground that looks
        // broken.
        //
        // The globe keeps out of the chunks' ground only while there's chunk ground everywhere.
        // With any square missing (after the GPU lost its chunks, say), it fills in underneath
        // instead of leaving sky or sea showing through the hole.
        //
        // Anything that isn't stuck any more is forgotten.
        if (stuckSince.isNotEmpty()) stuckSince.keys.retainAll(stuckSeen)
        stuckSeen.clear()
        lastComplete = complete && requests.isEmpty()
        if (!complete) chunkRange = 0.0
        if (complete && nearestWaiting > READY_RADIUS_METRES &&
            (requests.isEmpty() || requests.first().first > READY_RADIUS_METRES)
        ) {
            patchReady = true
        }
        if (++selections % 600 == 0L) {
            val levels = draw.groupingBy { it.key.level }.eachCount().toList().sortedBy { it.first }.toMap()
            val triangles = draw.size * TerrainChunk.CELLS * TerrainChunk.CELLS * 2
            Log.i(
                "ApogeeTerrain",
                "draw ${draw.size} (~$triangles tris) wanted ${requests.size} (lost $lostThisSelection) built ${built.size} " +
                    "complete=$complete levels=$levels",
            )
        }

        // Keep the GPU within its budget by letting go of whatever has gone longest without being
        // drawn. That happens here, where the next draw list is decided, so a chunk is never freed
        // in the same frame something lists it again. Travelling leaves a trail of built ground
        // behind the craft, and anything let go just gets rebuilt if it's needed again.
        for (chunk in draw) lastUsed[chunk.key] = selections
        if (built.size > gpuBudget) {
            val drawing = draw.mapTo(HashSet()) { it.key }
            val idle = built.keys
                // Nothing this selection wants, drawn or not. A chunk uploaded and waiting on its
                // three siblings isn't drawn yet, and letting it go restarted the wait, round and
                // round, with four top-level chunks standing in for the whole planet. And not one
                // still on its way to the GPU either. Let go here, it would be uploaded with
                // nothing left to list it by.
                .filter { it !in drawing && it !in wantedTree && !source.isWaitingForUpload(it) }
                .sortedBy { lastUsed[it] ?: 0L }
            for (key in idle.take(built.size - gpuBudget * 9 / 10)) {
                built.remove(key)
                lastUsed.remove(key)
                source.release(key)
            }
        }
    }

    /**
     * Adds to [draw] what should be drawn for [key]'s square, if all of it is ready. Otherwise it
     * asks for what's missing.
     *
     * @return false if nothing for this square could be drawn yet.
     */
    private fun resolve(
        key: ChunkKey,
        field: Terrain,
        maxLevel: Int,
        range: Double,
        draw: MutableList<DrawEntry>,
    ): Boolean {
        val size = TerrainChunk.size(field.bodyRadius, key.level)
        val distance = (camera.distanceTo(centre(key, field)) - size * 0.75).coerceAtLeast(0.0)
        if (distance > range) return true // Out of reach, so nothing to draw and nothing missing.
        wantedTree.add(key)

        // Hysteresis: split at the split distance, but once split, stay split until a fifth further
        // out. With one threshold, a camera wobbling by a metre across it flipped the chunk between
        // parent and children from frame to frame, so a coarser patch of ground blinked in and out.
        val splitAt = size * splitDistance * (if (key in splitLast) MERGE_HYSTERESIS else 1.0)
        // Right around the craft, the ground is drawn as finely as the collider holds it, whatever
        // the tier. A coarser mesh over rough ground sits metres off the surface the craft really
        // rests on, and a pod landed in the mountains was drawn buried to its nose.
        val deepest = if (distance < FULL_DETAIL_METRES) fullLevel(field) else maxLevel
        if (key.level < deepest && distance < splitAt) {
            splitNow.add(key)
            val start = draw.size
            var missing = 0
            for (dj in 0..1) for (di in 0..1) {
                if (!resolve(key.child(di, dj), field, maxLevel, range, draw)) missing = missing or (1 shl (dj * 2 + di))
            }
            if (missing == 0) return true
            // Not all four are ready, so this chunk stands in for the ones that aren't, but only
            // those quarters of it. When it stood in whole, it covered its ready children too, and
            // as the craft climbed and new ground came into range at the far edge, the chunk the
            // craft was over (kilometres across) replaced all the ground near it until one child
            // twenty kilometres away was built.
            val parent = built[key]?.takeIf { source.isUploaded(key) }
            if (parent != null) {
                draw.add(DrawEntry(parent, missing))
                return true
            }
            while (draw.size > start) draw.removeAt(draw.size - 1)
        }

        val ready = built[key]?.takeIf { source.isUploaded(key) }
        if (ready != null) {
            draw.add(DrawEntry(ready))
            return true
        }
        if (built[key] != null && source.isWaitingForUpload(key)) {
            // Built and on its way to the GPU. There's nothing to ask for, but it can't be drawn
            // yet either, so whatever is coarser stands in.
            if (distance < nearestWaiting) nearestWaiting = distance
            stuck(key)
            return false
        }
        if (built.remove(key) != null) lostThisSelection++
        requests.add(distance to key)
        // Asked for, but the source says it has it, so no worker will touch it. That's normal for
        // the frame or two before a finished build is collected, and stuck if it lasts.
        if (source.isAvailable(key) && synchronized(lock) { key !in inFlight }) stuck(key)
        // Merging back from finer ground whose parent the GPU has let go of since. Keep the finer
        // ground until the parent is back. Without this the square fell back to the nearest
        // ancestor still uploaded (sometimes a whole face of the planet), and for a frame or two
        // the ground was one flat slab, or gone with the sea showing through.
        if (key.level < fullLevel(field) && key in drawnBelow && drawUploadedBelow(key, fullLevel(field), draw)) return true
        return false
    }

    /**
     * Covers [key]'s square with uploaded descendants, if it can be covered completely. Otherwise
     * it adds nothing and returns false.
     */
    private fun drawUploadedBelow(key: ChunkKey, maxLevel: Int, draw: MutableList<DrawEntry>): Boolean {
        val start = draw.size
        for (dj in 0..1) for (di in 0..1) {
            val child = key.child(di, dj)
            val ready = built[child]?.takeIf { source.isUploaded(child) }
            val covered = when {
                ready != null -> { draw.add(DrawEntry(ready)); true }
                // Only down branches that led to something drawn last time. Anywhere else there's
                // nothing finer to find, and walking a face's empty subtree down to the finest
                // level costs millions.
                child.level < maxLevel && child in drawnBelow -> drawUploadedBelow(child, maxLevel, draw)
                else -> false
            }
            if (!covered) {
                while (draw.size > start) draw.removeAt(draw.size - 1)
                return false
            }
        }
        return true
    }

    /**
     * Notes [key] as stuck in this selection. Once it has been stuck for too long, it's dropped
     * from here and the source so it gets built from scratch, and it says so. True if it was
     * dropped.
     */
    private fun stuck(key: ChunkKey): Boolean {
        stuckSeen.add(key)
        val since = stuckSince.getOrPut(key) { selections }
        if (selections - since < STUCK_SELECTIONS) return false
        Log.w(
            "ApogeeTerrain",
            "chunk $key stuck for ${selections - since} selections (available=${source.isAvailable(key)} " +
                "uploaded=${source.isUploaded(key)} built=${built[key] != null}): rebuilding it",
        )
        stuckSince.remove(key)
        built.remove(key)
        source.release(key)
        return true
    }

    /** Where a chunk's middle is, near enough to decide its level by. Cached. */
    private fun centre(key: ChunkKey, field: Terrain): Vec3 =
        centres.getOrPut(key) {
            val n = 1 shl key.level
            val s = -1.0 + 2.0 * (key.i + 0.5) / n
            val t = -1.0 + 2.0 * (key.j + 0.5) / n
            val d = CubeSphere.direction(key.face, s, t, Vec3())
            // The real ground height, once. A craft on a mountain three kilometres up isn't three
            // kilometres from the chunk under it.
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
                        // Stopped mid-build, replaced by a builder of another quality. Published
                        // now, the source would have it and the new builder never would, and would
                        // wait on it.
                        if (stopped) continue
                        source.publishChunk(data)
                        onBuilt(data)
                    } finally {
                        synchronized(lock) { inFlight -= key }
                    }
                    // A chunk at a time, giving way between them, for a browser's one thread.
                    kotlinx.coroutines.yield()
                }
            }
        }
    }

    private val buildCount = com.rm.apogee.platform.AtomicLong()
    private val buildNanos = com.rm.apogee.platform.AtomicLong()

    /** Chunk build cost, logged every so often. It's the number the LOW tier lives or dies by. */
    private fun recordBuild(nanos: Long) {
        val count = buildCount.incrementAndGet()
        val total = buildNanos.addAndGet(nanos)
        if (count % 200 == 0L) {
            Log.i("ApogeeTerrain", "chunks built %d, mean %.1f ms".format(count, total / 1e6 / count))
        }
    }

    private val justBuilt = com.rm.apogee.core.ConcurrentQueue<ChunkData>()

    private fun onBuilt(data: ChunkData) {
        justBuilt.add(data)
    }

    /** Folds finished builds into [built]. Game thread. */
    fun collect() {
        while (true) {
            val data = justBuilt.poll() ?: break
            built[data.key] = data
        }
    }

    fun stop() {
        stopped = true
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
        val nextGlobeRevision = com.rm.apogee.platform.AtomicInteger()

        /** A little past the horizon, so the edge of the chunks is never on screen. */
        /** Moved less than this, in metres, with nothing new built, the chunks chosen stand. */
        const val RESELECT_METRES = 1.0
        /** Or the range changed by less than this share of itself. */
        const val RESELECT_RANGE = 0.01

        const val HORIZON_MARGIN = 1.3

        const val MIN_RANGE_METRES = 4_000.0

        /** Matched to the near pass's far plane. Past it nothing is drawn. */
        const val MAX_RANGE_METRES = 250_000.0

        /**
         * Above this the globe alone draws the ground. Chunks get coarser with distance on their
         * own, so they're kept as long as the near pass can hold any of them. By 200 km the ground
         * under the craft is level-two chunks, with cells as coarse as the globe's facets, so the
         * handover shows nothing. At 60 km, as it was, chunks with 2 km cells gave way all at once
         * to 29 km globe facets, well within sight.
         */
        const val CHUNK_CEILING_METRES = 200_000.0

        const val WORKERS = 2

        /** How many selections a chunk can stay stuck before it's rebuilt: a few seconds. */
        const val STUCK_SELECTIONS = 180L
        const val IDLE_POLL_MILLIS = 8L
        const val MAX_CENTRES = 20_000

        /**
         * Chunks nearer than this have to be at full detail before the view is shown. It's three
         * kilometres instead of one, because at one the first frame still had coarse slabs a couple
         * of kilometres out, refined over the next second in plain view.
         */
        const val READY_RADIUS_METRES = 3_000.0

        /** How much further out a split chunk stays split than where it split. */
        const val MERGE_HYSTERESIS = 1.2

        /**
         * How far around the craft, in metres, the ground is drawn at the collider's own resolution
         * on every tier. That's a few dozen extra chunks.
         */
        const val FULL_DETAIL_METRES = 80.0
    }
}
