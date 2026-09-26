package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The things lying and growing on the ground: rocks, boulders, trees, shrubs.
 *
 * Every one is solid, and the ones that grow can be knocked down.
 *
 * @param radius collider radius, metres, at size 1: a boulder's sphere, or a
 *   trunk's thickness.
 * @param height metres at size 1 that the collider reaches up to; zero for a
 *   boulder, whose sphere is all there is.
 * @param breakImpulse newton-seconds that fell it; infinite for rock.
 */
enum class ScatterKind(
    val radius: Double,
    val height: Double,
    val breakImpulse: Double,
) {
    /**
     * Rubble a wheel rolls over as a bump. Sized under a wheel's radius on
     * purpose: at sixty centimetres, rocks sat at axle height, a wheel at
     * speed rode up their rounded flank like a ramp, and a rover driven
     * across open country for a minute rolled over one run in four.
     */
    BOULDER_SMALL(radius = 0.3, height = 0.0, breakImpulse = Double.POSITIVE_INFINITY),

    /** The ones to steer round. */
    BOULDER_LARGE(radius = 1.8, height = 0.0, breakImpulse = Double.POSITIVE_INFINITY),
    CONIFER(radius = 0.3, height = 9.0, breakImpulse = 5_000.0),
    BROADLEAF(radius = 0.35, height = 7.0, breakImpulse = 6_000.0),
    DEAD_TREE(radius = 0.25, height = 6.0, breakImpulse = 2_500.0),
    SHRUB(radius = 0.6, height = 1.1, breakImpulse = 600.0),
    CACTUS(radius = 0.3, height = 3.0, breakImpulse = 900.0);

    val breakable: Boolean get() = breakImpulse.isFinite()
    val isBoulder: Boolean get() = height == 0.0

    companion object {
        private val all = entries.toTypedArray()
        fun of(ordinal: Int): ScatterKind = all[ordinal]
    }
}

/**
 * One block's worth of scatter, in the body-fixed frame: parallel arrays, not
 * an object each, because a forest block holds a couple of hundred and the
 * renderer and collider both walk them every frame.
 */
class ScatterBlock(
    val face: Int,
    val i: Int,
    val j: Int,
    /** Stable, unique across the body: what a felled tree is remembered by. */
    val ids: LongArray,
    val kinds: ByteArray,
    /** Base position on the ground, body-fixed, metres from the centre. */
    val x: DoubleArray,
    val y: DoubleArray,
    val z: DoubleArray,
    /** Scale on the kind's nominal size, about 0.7..1.4. */
    val sizes: FloatArray,
    /** Rotation about the local vertical, radians - for drawing only. */
    val yaws: FloatArray,
) {
    val count: Int get() = ids.size
    @Volatile var lastUsed: Long = 0L
}

/**
 * Where scatter goes, block by block, on the same grid as the collider's tiles.
 *
 * Placement is deterministic from position alone - a jittered grid of cells a
 * few metres across, each rolling once against the odds its ground gives - so
 * every machine grows the same forest without it ever being sent anywhere.
 * It samples the height field only at the few points it needs, not a whole
 * tile's grid, so the renderer can place scatter out to its draw distance
 * cheaply while the collider only ever looks close to a craft.
 */
class ScatterField(private val terrain: Terrain) {

    val tilesPerFace: Int = TerrainTile.tilesPerFace(terrain.bodyRadius)
    private val blocks = ConcurrentHashMap<Long, ScatterBlock>()
    private val clock = AtomicLong()

    fun block(face: Int, i: Int, j: Int): ScatterBlock {
        val key = (face.toLong() shl 48) or (i.toLong() shl 24) or j.toLong()
        val block = blocks[key] ?: place(face, i, j).also {
            blocks[key] = it
            if (blocks.size > CAPACITY) evict()
        }
        block.lastUsed = clock.incrementAndGet()
        return block
    }

    /**
     * Calls [action] with every block within [radiusMetres] of body-fixed
     * [direction]. Stays on one cube face, like the tile prefetch.
     */
    inline fun forEachBlockNear(direction: Vec3, radiusMetres: Double, scratch: Vec3, action: (ScatterBlock) -> Unit) {
        val unit = scratch.setTo(direction).normalizeInPlace()
        val coords = Vec3()
        val face = CubeSphere.locate(unit, coords)
        val gx = (coords.x + 1.0) * 0.5 * tilesPerFace
        val gy = (coords.y + 1.0) * 0.5 * tilesPerFace
        val reach = radiusMetres / blockMetres
        val i0 = kotlin.math.floor(gx - reach).toInt().coerceAtLeast(0)
        val i1 = kotlin.math.floor(gx + reach).toInt().coerceAtMost(tilesPerFace - 1)
        val j0 = kotlin.math.floor(gy - reach).toInt().coerceAtLeast(0)
        val j1 = kotlin.math.floor(gy + reach).toInt().coerceAtMost(tilesPerFace - 1)
        for (i in i0..i1) for (j in j0..j1) action(block(face, i, j))
    }

    val blockMetres: Double = terrain.bodyRadius * Math.PI / 2.0 / tilesPerFace

    private fun place(face: Int, i: Int, j: Int): ScatterBlock {
        val ids = LongArray(CELLS * CELLS)
        val kinds = ByteArray(CELLS * CELLS)
        val xs = DoubleArray(CELLS * CELLS); val ys = DoubleArray(CELLS * CELLS); val zs = DoubleArray(CELLS * CELLS)
        val sizes = FloatArray(CELLS * CELLS); val yaws = FloatArray(CELLS * CELLS)
        var n = 0
        val radius = terrain.bodyRadius
        val d = Vec3(); val e = Vec3(); val f = Vec3()
        // A metre, in face coordinates, for measuring the slope.
        val step = 2.0 / tilesPerFace / (blockMetres)
        val base = ((face.toLong() * tilesPerFace + i) * tilesPerFace + j) * 256L

        for (cj in 0 until CELLS) for (ci in 0 until CELLS) {
            val cell = cj * CELLS + ci
            val roll = Noise.hash(SEED, face * 7919 + ci, i * 104729 + cj, j)
            // Cheap early out: nothing anywhere is denser than this.
            if (roll > MAX_DENSITY) continue
            val u = Noise.hash(SEED + 1, face * 7919 + ci, i * 104729 + cj, j)
            val v = Noise.hash(SEED + 2, face * 7919 + ci, i * 104729 + cj, j)
            val s = -1.0 + 2.0 * (i + (ci + u) / CELLS) / tilesPerFace
            val t = -1.0 + 2.0 * (j + (cj + v) / CELLS) / tilesPerFace
            CubeSphere.direction(face, s, t, d)
            if (terrain.isLaunchComplex(d)) continue
            val h = terrain.elevation(d)
            // Nothing on the sea floor - on a world with a sea.
            if (terrain.hasOcean && h < 1.0) continue
            // Slope from two more samples a metre away along the face axes.
            CubeSphere.direction(face, s + step, t, e)
            CubeSphere.direction(face, s, t + step, f)
            val dhx = terrain.elevation(e) - h
            val dhy = terrain.elevation(f) - h
            val gradient = kotlin.math.sqrt(dhx * dhx + dhy * dhy)
            val slope = 1.0 - 1.0 / kotlin.math.sqrt(1.0 + gradient * gradient)
            val kind = kindFor(terrain.material(d, h, slope), h, slope, roll)
                // Nothing grows off Terra: only its rocks.
                ?.takeIf { !terrain.barren || it.isBoulder } ?: continue

            ids[n] = base + cell
            kinds[n] = kind.ordinal.toByte()
            val r = radius + h
            xs[n] = d.x * r; ys[n] = d.y * r; zs[n] = d.z * r
            sizes[n] = (0.7 + 0.7 * Noise.hash(SEED + 3, face * 7919 + ci, i * 104729 + cj, j)).toFloat()
            yaws[n] = (Noise.hash(SEED + 4, face * 7919 + ci, i * 104729 + cj, j) * Math.PI * 2.0).toFloat()
            n++
        }
        return ScatterBlock(
            face, i, j, ids.copyOf(n), kinds.copyOf(n),
            xs.copyOf(n), ys.copyOf(n), zs.copyOf(n), sizes.copyOf(n), yaws.copyOf(n),
        )
    }

    /**
     * What, if anything, grows or lies in a cell of this ground. [roll] is
     * the cell's own 0..1 draw; the ground's odds are cumulative bands of it.
     */
    private fun kindFor(material: SurfaceMaterial, elevation: Double, slope: Double, roll: Double): ScatterKind? {
        if (slope > 0.45) return null
        return when (material) {
            // Nothing grows on the launch complex's paving.
            SurfaceMaterial.CONCRETE, SurfaceMaterial.ASPHALT -> null
            SurfaceMaterial.FOREST -> when {
                // One cell in five: a forest, not a hedge. At one in three it was
                // over four thousand trees a square kilometre, which is thick
                // enough to be a wall and too many to draw.
                roll < 0.20 -> if (elevation > 1_300.0 || roll < 0.07) ScatterKind.CONIFER else ScatterKind.BROADLEAF
                roll < 0.25 -> ScatterKind.SHRUB
                roll < 0.26 -> ScatterKind.BOULDER_SMALL
                else -> null
            }
            // Open country: a lone tree every hundred and fifty metres or so.
            // They are solid, and a wheel that catches one at speed spins the
            // craft round it - which is fair, but the grassland round the Cape
            // is where people learn to drive.
            SurfaceMaterial.GRASS -> when {
                roll < 0.006 -> ScatterKind.BROADLEAF
                roll < 0.024 -> ScatterKind.SHRUB
                roll < 0.03 -> ScatterKind.BOULDER_SMALL
                else -> null
            }
            SurfaceMaterial.DIRT -> when {
                roll < 0.03 -> ScatterKind.SHRUB
                roll < 0.045 -> ScatterKind.BOULDER_SMALL
                roll < 0.05 -> ScatterKind.DEAD_TREE
                else -> null
            }
            SurfaceMaterial.SAND -> when {
                roll < 0.006 -> ScatterKind.CACTUS
                roll < 0.012 -> ScatterKind.BOULDER_SMALL
                else -> null
            }
            SurfaceMaterial.CLAY -> when {
                roll < 0.03 -> ScatterKind.BOULDER_SMALL
                roll < 0.04 -> ScatterKind.BOULDER_LARGE
                roll < 0.045 -> ScatterKind.SHRUB
                else -> null
            }
            // Mare floors are the smooth ground landings aim for: the odd
            // rock, not a boulder field.
            SurfaceMaterial.BASALT -> if (roll < 0.008) ScatterKind.BOULDER_SMALL else null
            SurfaceMaterial.ROCK -> when {
                roll < 0.05 -> ScatterKind.BOULDER_SMALL
                roll < 0.08 -> ScatterKind.BOULDER_LARGE
                else -> null
            }
            SurfaceMaterial.SCREE -> when {
                roll < 0.14 -> ScatterKind.BOULDER_SMALL
                roll < 0.17 -> ScatterKind.BOULDER_LARGE
                else -> null
            }
            SurfaceMaterial.SNOW -> when {
                roll < 0.01 -> ScatterKind.CONIFER
                roll < 0.02 -> ScatterKind.BOULDER_SMALL
                else -> null
            }
            SurfaceMaterial.MUD -> when {
                roll < 0.02 -> ScatterKind.DEAD_TREE
                roll < 0.04 -> ScatterKind.SHRUB
                else -> null
            }
            SurfaceMaterial.REGOLITH -> if (roll < 0.015) ScatterKind.BOULDER_SMALL else null
            SurfaceMaterial.ICE -> null
            // The other worlds' ground: rocks, more or fewer.
            SurfaceMaterial.TESSERA, SurfaceMaterial.SULFUR -> when {
                roll < 0.04 -> ScatterKind.BOULDER_SMALL
                roll < 0.05 -> ScatterKind.BOULDER_LARGE
                else -> null
            }
            SurfaceMaterial.RED_DUST, SurfaceMaterial.THOLIN -> when {
                roll < 0.03 -> ScatterKind.BOULDER_SMALL
                roll < 0.035 -> ScatterKind.BOULDER_LARGE
                else -> null
            }
            SurfaceMaterial.ORGANIC_SAND, SurfaceMaterial.NITROGEN_ICE, SurfaceMaterial.LAVA -> null
        }
    }

    private fun evict() {
        synchronized(this) {
            if (blocks.size <= CAPACITY) return
            val ordered = blocks.entries.sortedBy { it.value.lastUsed }
            for (k in 0 until ordered.size / 4) blocks.remove(ordered[k].key)
        }
    }

    companion object {
        /** Cells along a block's side: about eight metres each on Terra. */
        const val CELLS = 14
        const val MAX_DENSITY = 0.26
        const val CAPACITY = 2_048
        private const val SEED = 0x5CA77E
    }
}
