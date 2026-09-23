package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Built tiles, kept while they are in use.
 *
 * One per [Terrain], shared by everything that touches that ground: every
 * craft in a server's world, the client's prediction replica, the mesh
 * builder's workers. Thread-safe because those run on different threads;
 * tiles are immutable once built, so the worst a race does is build one twice.
 *
 * Bounded, least-recently-used out. A tile is about twenty kilobytes, and a
 * craft touching the ground uses a handful; the cap is far above any real
 * working set and far below what a phone would notice.
 */
class TerrainTileCache(
    private val terrain: Terrain,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    val tilesPerFace: Int = TerrainTile.tilesPerFace(terrain.bodyRadius)

    private val tiles = ConcurrentHashMap<Long, TerrainTile>()
    private val clock = AtomicLong()

    /** How many tiles have been built, for tests and the survey. */
    val builds = AtomicLong()

    fun tile(face: Int, i: Int, j: Int): TerrainTile {
        val key = key(face, i, j)
        val tile = tiles[key] ?: TerrainTile.build(terrain, face, i, j, tilesPerFace).also {
            builds.incrementAndGet()
            tiles[key] = it
            if (tiles.size > capacity) evict()
        }
        tile.lastUsed = clock.incrementAndGet()
        return tile
    }

    /**
     * The ground along a body-fixed [direction] (need not be unit length).
     *
     * @param scratch the caller's own, so concurrent callers never share one.
     */
    fun ground(direction: Vec3, out: GroundPoint, scratch: Lookup) {
        val unit = scratch.unit.setTo(direction).normalizeInPlace()
        val face = CubeSphere.locate(unit, scratch.faceCoords)
        val gx = (scratch.faceCoords.x + 1.0) * 0.5 * tilesPerFace
        val gy = (scratch.faceCoords.y + 1.0) * 0.5 * tilesPerFace
        val i = kotlin.math.floor(gx).toInt().coerceIn(0, tilesPerFace - 1)
        val j = kotlin.math.floor(gy).toInt().coerceIn(0, tilesPerFace - 1)
        tile(face, i, j).ground(
            unit,
            (gx - i) * TerrainTile.CELLS,
            (gy - j) * TerrainTile.CELLS,
            terrain.bodyRadius,
            out,
            scratch.tile,
        )
    }

    val size: Int get() = tiles.size

    private val queued: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * One background thread per terrain, building tiles before anything
     * stands on them.
     *
     * Without it a craft rolling onto unsampled ground pays for the whole tile
     * in the tick it arrives - several milliseconds on a desktop, tens on a
     * phone, and on the client that is a visible hitch in the predicted
     * craft. Building ahead changes when the work is done and nothing about
     * its result: a tile is the same whichever thread samples it, so the
     * simulation stays deterministic.
     */
    private val builder by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "terrain-tiles").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    /**
     * Queues every tile within [radiusMetres] of body-fixed [direction] that
     * is not already built or queued. Cheap when there is nothing to do, so
     * it can be asked every tick.
     *
     * Stays on the one cube face; the rare craft straddling a face edge
     * builds the other face's tile itself, as it always could.
     */
    fun prefetch(direction: Vec3, radiusMetres: Double, scratch: Lookup) {
        val unit = scratch.unit.setTo(direction).normalizeInPlace()
        val face = CubeSphere.locate(unit, scratch.faceCoords)
        val gx = (scratch.faceCoords.x + 1.0) * 0.5 * tilesPerFace
        val gy = (scratch.faceCoords.y + 1.0) * 0.5 * tilesPerFace
        val tileMetres = terrain.bodyRadius * Math.PI / 2.0 / tilesPerFace
        val reach = radiusMetres / tileMetres
        val i0 = kotlin.math.floor(gx - reach).toInt().coerceAtLeast(0)
        val i1 = kotlin.math.floor(gx + reach).toInt().coerceAtMost(tilesPerFace - 1)
        val j0 = kotlin.math.floor(gy - reach).toInt().coerceAtLeast(0)
        val j1 = kotlin.math.floor(gy + reach).toInt().coerceAtMost(tilesPerFace - 1)
        for (i in i0..i1) for (j in j0..j1) {
            val key = key(face, i, j)
            if (tiles.containsKey(key) || !queued.add(key)) continue
            builder.execute {
                try {
                    tile(face, i, j)
                } finally {
                    queued.remove(key)
                }
            }
        }
    }

    private fun evict() {
        // Drop the oldest quarter in one go, rather than one tile per insert.
        synchronized(this) {
            if (tiles.size <= capacity) return
            val ordered = tiles.entries.sortedBy { it.value.lastUsed }
            for (k in 0 until ordered.size / 4) tiles.remove(ordered[k].key)
        }
    }

    /** Per-caller scratch for [ground]. */
    class Lookup {
        val unit = Vec3()
        val faceCoords = Vec3()
        val tile = TerrainTile.Scratch()
    }

    private fun key(face: Int, i: Int, j: Int): Long =
        (face.toLong() shl 48) or (i.toLong() shl 24) or j.toLong()

    private companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
