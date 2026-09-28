package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.CubeSphere
import com.rm.apogee.core.terrain.ScatterBlock
import com.rm.apogee.core.terrain.ScatterField
import com.rm.apogee.core.terrain.ScatterKind
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.ScatterDraw
import com.rm.apogee.render.ScatterSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.rm.apogee.platform.synchronized
import kotlin.concurrent.Volatile

/**
 * Chooses which blocks of scatter to draw and builds them, off the render thread.
 *
 * Draw distance is up to the quality tier and nothing else. Every object exists and collides on
 * every device, because they're solid, and a phone that just didn't have the tree a server's craft
 * hit would disagree with it about the world. A low tier only draws fewer of them.
 */
class ScatterStreamer(
    private val source: ScatterSource,
    quality: QualityTier,
) {
    private val drawMetres = when (quality) {
        QualityTier.LOW -> 260.0
        QualityTier.MEDIUM -> 450.0
        QualityTier.HIGH -> 700.0
    }

    private val lock = Any()
    private var wanted: List<Triple<Int, Int, Int>> = emptyList()
    private val building = HashSet<Long>()
    private val built = com.rm.apogee.core.concurrentMapOf<Long, ScatterDraw>()
    private var field: ScatterField? = null
    private var felled: Set<Long> = emptySet()
    @Volatile private var felledRevision = 0
    private var worker: Job? = null
    private val scratch = Vec3()

    /**
     * Picks blocks again around [bodyFixedPosition]. It's cheap, because it only works out which
     * blocks are wanted. The building happens on the worker.
     */
    fun follow(body: CelestialBody, bodyFixedPosition: Vec3, altitude: Double, felledIds: Set<Long>, revision: Int, scope: CoroutineScope) {
        val scatter = body.terrain?.scatter
        if (scatter == null || altitude > drawMetres) {
            source.publish(emptyList())
            return
        }
        synchronized(lock) {
            if (field !== scatter) { field = scatter; built.clear() }
            if (revision != felledRevision) {
                felled = felledIds.toSet()
                felledRevision = revision
                // Something got knocked down, so rebuild what's on screen and it goes.
                built.clear()
            }
        }
        if (worker == null) worker = scope.launch(Dispatchers.Default) { work() }

        val unit = scratch.setTo(bodyFixedPosition).normalizeInPlace()
        val coords = Vec3()
        val face = CubeSphere.locate(unit, coords)
        val n = scatter.tilesPerFace
        val gx = (coords.x + 1.0) * 0.5 * n
        val gy = (coords.y + 1.0) * 0.5 * n
        val reach = drawMetres / scatter.blockMetres
        val keys = ArrayList<Triple<Int, Int, Int>>()
        val draw = ArrayList<ScatterDraw>()
        for (i in kotlin.math.floor(gx - reach).toInt()..kotlin.math.floor(gx + reach).toInt()) {
            for (j in kotlin.math.floor(gy - reach).toInt()..kotlin.math.floor(gy + reach).toInt()) {
                if (i !in 0 until n || j !in 0 until n) continue
                val di = (i + 0.5 - gx); val dj = (j + 0.5 - gy)
                if (di * di + dj * dj > (reach + 0.75) * (reach + 0.75)) continue
                val key = key(face, i, j)
                val ready = built[key]
                if (ready != null && ready.revision == felledRevision) draw += ready else keys += Triple(face, i, j)
            }
        }
        // Nearest first.
        keys.sortBy { (_, i, j) -> (i + 0.5 - gx) * (i + 0.5 - gx) + (j + 0.5 - gy) * (j + 0.5 - gy) }
        synchronized(lock) { wanted = keys }
        source.publish(draw)
    }

    private suspend fun work() {
        while (true) {
            val next = synchronized(lock) {
                wanted.firstOrNull { key(it.first, it.second, it.third) !in building }
                    ?.also { building += key(it.first, it.second, it.third) }
            }
            val scatter = field
            if (next == null || scatter == null) {
                delay(IDLE_MILLIS)
                continue
            }
            val (face, i, j) = next
            try {
                val revision = felledRevision
                val draw = build(scatter.block(face, i, j), felled, revision)
                built[key(face, i, j)] = draw
                if (built.size > MAX_BUILT) built.clear()
            } finally {
                synchronized(lock) { building -= key(face, i, j) }
            }
            // A block at a time, giving way between them, for a browser's one thread.
            kotlinx.coroutines.yield()
        }
    }

    /** A block's instances, grouped by kind, leaving out anything felled. */
    private fun build(block: ScatterBlock, felled: Set<Long>, revision: Int): ScatterDraw {
        // The centre is the average base position, so instance coordinates stay small.
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        val count = block.count.coerceAtLeast(1)
        for (k in 0 until block.count) { cx += block.x[k]; cy += block.y[k]; cz += block.z[k] }
        val centre = if (block.count == 0) Vec3(1.0, 0.0, 0.0) else Vec3(cx / count, cy / count, cz / count)
        val up = centre.normalized()
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).let { if (it.lengthSq < 1e-9) Vec3(1.0, 0.0, 0.0) else it.normalizeInPlace() }
        val north = up.cross(east).normalizeInPlace()

        val kinds = ScatterDraw.KINDS
        val counts = IntArray(kinds)
        for (k in 0 until block.count) if (block.ids[k] !in felled) counts[block.kinds[k].toInt()]++
        val offsets = IntArray(kinds)
        for (k in 1 until kinds) offsets[k] = offsets[k - 1] + counts[k - 1]
        val total = offsets[kinds - 1] + counts[kinds - 1]
        val instances = FloatArray(total * ScatterDraw.INSTANCE_FLOATS)
        val cursor = offsets.copyOf()
        var bound = 0.0
        for (k in 0 until block.count) {
            if (block.ids[k] in felled) continue
            val kind = block.kinds[k].toInt()
            val at = cursor[kind]++ * ScatterDraw.INSTANCE_FLOATS
            val x = block.x[k] - centre.x; val y = block.y[k] - centre.y; val z = block.z[k] - centre.z
            instances[at] = x.toFloat(); instances[at + 1] = y.toFloat(); instances[at + 2] = z.toFloat()
            instances[at + 3] = block.sizes[k]
            instances[at + 4] = block.yaws[k]
            bound = maxOf(bound, kotlin.math.sqrt(x * x + y * y + z * z))
        }
        val basis = floatArrayOf(
            east.x.toFloat(), east.y.toFloat(), east.z.toFloat(),
            up.x.toFloat(), up.y.toFloat(), up.z.toFloat(),
            north.x.toFloat(), north.y.toFloat(), north.z.toFloat(),
        )
        return ScatterDraw(
            key(block.face, block.i, block.j), revision, centre, basis,
            bound + ScatterKind.CONIFER.height * 1.5, instances, offsets, counts,
        )
    }

    fun stop() {
        worker?.cancel()
        worker = null
        built.clear()
        source.clear()
    }

    private fun key(face: Int, i: Int, j: Int): Long = (face.toLong() shl 48) or (i.toLong() shl 24) or j.toLong()

    private companion object {
        const val IDLE_MILLIS = 12L
        const val MAX_BUILT = 1_500
    }
}
