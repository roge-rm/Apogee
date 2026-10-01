package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.concurrentMapOf
import com.rm.apogee.core.concurrentSetOf
import com.rm.apogee.core.Counter
import com.rm.apogee.core.guarded
import com.rm.apogee.core.math.Math

/**
 * Built tiles, kept while they're in use.
 *
 * One per [Terrain], shared by every craft in a server's world, the client's prediction replica and
 * the mesh builder's workers, so it's thread-safe. Tiles never change once built, so a race can at
 * worst build one twice. Least recently used tiles go first past the limit. A tile is about 20 kB
 * and a grounded craft uses a handful.
 */
class TerrainTileCache(
    private val terrain: Terrain,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    val tilesPerFace: Int = TerrainTile.tilesPerFace(terrain.bodyRadius)

    private val tiles = concurrentMapOf<Long, TerrainTile>()
    private val clock = Counter()

    /** How many tiles have been built, for tests and the survey. */
    val builds = Counter()

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
     * The ground along a body-fixed [direction] (it doesn't need to be unit length).
     *
     * @param scratch the caller's own, so callers running at the same time never share one.
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

    private val queued: MutableSet<Long> = concurrentSetOf()

    /**
     * One background thread per terrain, building tiles before anything stands on them, so a craft
     * rolling onto new ground doesn't hitch. A tile is the same whichever thread builds it, so the
     * result doesn't change.
     */
    private val builder by lazy { com.rm.apogee.core.Background("terrain-tiles") }

    /**
     * Queues every tile within [radiusMetres] of body-fixed [direction] that isn't built or queued.
     * Cheap when there's nothing to do, so call it every tick. It stays on one cube face; a craft
     * across a face edge builds the other face's tile itself.
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
        // Drop the oldest quarter in one go.
        guarded(this) {
            if (tiles.size <= capacity) return
            val ordered = tiles.entries.sortedBy { it.value.lastUsed }
            for (k in 0 until ordered.size / 4) tiles.remove(ordered[k].key)
        }
    }

    /** Scratch space for each caller of [ground]. */
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
