package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.concurrentMapOf
import com.rm.apogee.core.Counter
import com.rm.apogee.core.guarded
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile

/**
 * Things on the ground: rocks, boulders, trees and shrubs. All solid; growing ones can be felled.
 *
 * @param radius collider radius in metres at size 1: a boulder's sphere, or a trunk's thickness.
 * @param height how high the collider reaches in metres at size 1. Zero for a boulder.
 * @param breakImpulse newton-seconds to knock it down. Infinite for rock.
 */
enum class ScatterKind(
    val radius: Double,
    val height: Double,
    val breakImpulse: Double,
) {
    /**
     * Rubble a wheel rolls over as a bump. Kept under a wheel's radius, or a wheel at speed rides up
     * it like a ramp and rolls the rover.
     */
    BOULDER_SMALL(radius = 0.3, height = 0.0, breakImpulse = Double.POSITIVE_INFINITY),

    /** The ones to steer around. */
    BOULDER_LARGE(radius = 1.8, height = 0.0, breakImpulse = Double.POSITIVE_INFINITY),
    CONIFER(radius = 0.3, height = 9.0, breakImpulse = 5_000.0),
    BROADLEAF(radius = 0.35, height = 7.0, breakImpulse = 6_000.0),
    DEAD_TREE(radius = 0.25, height = 6.0, breakImpulse = 2_500.0),
    SHRUB(radius = 0.6, height = 1.1, breakImpulse = 600.0),
    CACTUS(radius = 0.3, height = 3.0, breakImpulse = 900.0),

    /** A spire of rock on the sea floor, house-high or more, on canyon walls and seamounts. */
    PINNACLE(radius = 2.2, height = 18.0, breakImpulse = Double.POSITIVE_INFINITY),

    /** A vent's chimney: mineral stacked round rising hot water, a few metres tall and brittle. */
    VENT(radius = 0.9, height = 6.0, breakImpulse = 40_000.0),

    /** A lump of metal lying on the ooze, fist-sized and bigger. */
    NODULE(radius = 0.2, height = 0.0, breakImpulse = Double.POSITIVE_INFINITY),

    /** A blade of ice, taller than a person, left where the sun wore the ice away round it. */
    ICE_SPIRE(radius = 0.5, height = 4.0, breakImpulse = 8_000.0),

    /** A block of ice broken off the crust. */
    ICE_BLOCK(radius = 1.4, height = 0.0, breakImpulse = Double.POSITIVE_INFINITY),

    /** A geyser's low cone, round the hole it sprays from. */
    GEYSER(radius = 1.2, height = 1.5, breakImpulse = Double.POSITIVE_INFINITY),

    /** A crusted cone of sulfur round a hot vent. */
    FUMAROLE(radius = 0.8, height = 2.0, breakImpulse = 20_000.0);

    /** Whether it grows, so only on a world with life. */
    val lives: Boolean get() = this == CONIFER || this == BROADLEAF || this == DEAD_TREE || this == SHRUB || this == CACTUS

    val breakable: Boolean get() = breakImpulse.isFinite()
    val isBoulder: Boolean get() = height == 0.0

    companion object {
        private val all = entries.toTypedArray()
        fun of(ordinal: Int): ScatterKind = all[ordinal]
    }
}

/**
 * One block's scatter, body-fixed. Parallel arrays, since a forest block holds a couple of hundred
 * and the renderer and collider walk them every frame.
 */
class ScatterBlock(
    val face: Int,
    val i: Int,
    val j: Int,
    /** Stable and unique across the body. A felled tree is remembered by it. */
    val ids: LongArray,
    val kinds: ByteArray,
    /** Base position on the ground, body-fixed, in metres from the centre. */
    val x: DoubleArray,
    val y: DoubleArray,
    val z: DoubleArray,
    /** Scale on the kind's usual size, about 0.7..1.4. */
    val sizes: FloatArray,
    /** Rotation around the local vertical, in radians. For drawing only. */
    val yaws: FloatArray,
) {
    val count: Int get() = ids.size
    @Volatile var lastUsed: Long = 0L
}

/**
 * Where scatter goes, block by block, on the collider's tile grid. Placement comes only from
 * position (a jittered grid of cells, each rolling once against its ground's odds), so every
 * machine grows the same forest. It samples only the points it needs, so placing out to draw
 * distance is cheap.
 */
class ScatterField(private val terrain: Terrain) {

    /** Whose ground it is. */
    val world: String get() = terrain.world

    val tilesPerFace: Int = TerrainTile.tilesPerFace(terrain.bodyRadius)
    private val blocks = concurrentMapOf<Long, ScatterBlock>()
    private val clock = Counter()

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
     * Calls [action] with every block within [radiusMetres] of body-fixed [direction]. Stays on one
     * cube face, like the tile prefetch.
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
            // Cheap early exit: nothing anywhere is denser than this.
            if (roll > MAX_DENSITY) continue
            val u = Noise.hash(SEED + 1, face * 7919 + ci, i * 104729 + cj, j)
            val v = Noise.hash(SEED + 2, face * 7919 + ci, i * 104729 + cj, j)
            val s = -1.0 + 2.0 * (i + (ci + u) / CELLS) / tilesPerFace
            val t = -1.0 + 2.0 * (j + (cj + v) / CELLS) / tilesPerFace
            CubeSphere.direction(face, s, t, d)
            if (terrain.isKeptClear(d)) continue
            val h = terrain.elevation(d)
            // On the sea floor, only what lies there, and nothing in the swash.
            val underwater = terrain.hasOcean && h < 1.0
            if (underwater && h > -SWASH) continue
            // The slope, from two more samples a metre away along the face axes.
            CubeSphere.direction(face, s + step, t, e)
            CubeSphere.direction(face, s, t + step, f)
            val dhx = terrain.elevation(e) - h
            val dhy = terrain.elevation(f) - h
            val gradient = kotlin.math.sqrt(dhx * dhx + dhy * dhy)
            val slope = 1.0 - 1.0 / kotlin.math.sqrt(1.0 + gradient * gradient)
            val material = terrain.material(d, h, slope)
            val kind = (if (underwater) seaKindFor(material, roll) else worldKindFor(terrain.world, material, slope, roll) ?: kindFor(material, h, slope, roll))
                // Nothing grows off Terra.
                ?.takeIf { !terrain.barren || !it.lives } ?: continue

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
     * What, if anything, grows or lies in a cell of this ground. [roll] is the cell's 0..1 draw,
     * and the ground's odds are stacked bands of it.
     */
    /**
     * What lies on sea floor of [material]: chimneys at vents, nodules on their fields, spires on
     * rock.
     */
    private fun seaKindFor(material: SurfaceMaterial, roll: Double): ScatterKind? = when (material) {
        SurfaceMaterial.VENT_CRUST -> if (roll < 0.07) ScatterKind.VENT else if (roll < 0.1) ScatterKind.BOULDER_SMALL else null
        SurfaceMaterial.NODULES -> if (roll < 0.14) ScatterKind.NODULE else null
        SurfaceMaterial.BASALT -> if (roll < 0.004) ScatterKind.PINNACLE else if (roll < 0.02) ScatterKind.BOULDER_LARGE else null
        SurfaceMaterial.OOZE -> if (roll < 0.0015) ScatterKind.PINNACLE else if (roll < 0.004) ScatterKind.BOULDER_SMALL else null
        SurfaceMaterial.SAND -> if (roll < 0.004) ScatterKind.BOULDER_SMALL else null
        else -> null
    }

    /**
     * A world's own scatter on [material], before the usual: stacked bands of [roll], each the top
     * of a kind's share. Null where the world has nothing of its own there.
     */
    private fun worldKindFor(world: String, material: SurfaceMaterial, slope: Double, roll: Double): ScatterKind? {
        if (slope > 0.45) return null
        val bands = WORLD_SCATTER[world]?.get(material) ?: return null
        for ((top, kind) in bands) if (roll < top) return kind
        return null
    }

    private fun kindFor(material: SurfaceMaterial, elevation: Double, slope: Double, roll: Double): ScatterKind? {
        if (slope > 0.45) return null
        return when (material) {
            // Nothing grows on paving, or on sea floor lifted dry.
            SurfaceMaterial.CONCRETE, SurfaceMaterial.ASPHALT, SurfaceMaterial.OOZE, SurfaceMaterial.NODULES, SurfaceMaterial.VENT_CRUST -> null
            SurfaceMaterial.FOREST -> when {
                // One cell in five, so it's a forest and not a wall, and few enough to draw.
                roll < 0.20 -> if (elevation > 1_300.0 || roll < 0.07) ScatterKind.CONIFER else ScatterKind.BROADLEAF
                roll < 0.25 -> ScatterKind.SHRUB
                roll < 0.26 -> ScatterKind.BOULDER_SMALL
                else -> null
            }
            // Open country: a lone tree every 150 m or so. Sparse, since the grassland round the
            // Cape is where people learn to drive.
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
            // Mare floors are where landings aim, so just the odd rock.
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
            SurfaceMaterial.FROST -> if (roll < 0.01) ScatterKind.BOULDER_SMALL else null
            // Fresh rubble: rocks everywhere.
            SurfaceMaterial.EJECTA -> when {
                roll < 0.04 -> ScatterKind.BOULDER_SMALL
                roll < 0.048 -> ScatterKind.BOULDER_LARGE
                else -> null
            }
            SurfaceMaterial.DARK_SAND -> if (roll < 0.004) ScatterKind.BOULDER_SMALL else null
            SurfaceMaterial.LAYERED_ROCK -> when {
                roll < 0.04 -> ScatterKind.BOULDER_SMALL
                roll < 0.06 -> ScatterKind.BOULDER_LARGE
                else -> null
            }
            SurfaceMaterial.FLOW_ROCK -> when {
                roll < 0.10 -> ScatterKind.BOULDER_SMALL
                roll < 0.14 -> ScatterKind.BOULDER_LARGE
                else -> null
            }
            SurfaceMaterial.RED_SULFUR -> if (roll < 0.02) ScatterKind.BOULDER_SMALL else null
            SurfaceMaterial.VENT_ICE, SurfaceMaterial.SALT -> null
        }
    }

    private fun evict() {
        guarded(this) {
            if (blocks.size <= CAPACITY) return
            val ordered = blocks.entries.sortedBy { it.value.lastUsed }
            for (k in 0 until ordered.size / 4) blocks.remove(ordered[k].key)
        }
    }

    companion object {
        /** Cells along a block's side, about eight metres each on Terra. */
        const val CELLS = 14
        const val MAX_DENSITY = 0.26

        /** How many metres below the datum the sea floor proper begins. Above it is the swash. */
        const val SWASH = 4.0
        const val CAPACITY = 2_048
        private const val SEED = 0x5CA77E

        /**
         * Each world's own scatter, by ground: the top of each kind's band of the cell's roll.
         * Ground left out takes the usual rocks.
         */
        private val WORLD_SCATTER: Map<String, Map<SurfaceMaterial, List<Pair<Double, ScatterKind>>>> = mapOf(
            // Hoodoos standing off the layered walls.
            "rubra" to mapOf(SurfaceMaterial.LAYERED_ROCK to listOf(0.006 to ScatterKind.PINNACLE, 0.05 to ScatterKind.BOULDER_SMALL, 0.07 to ScatterKind.BOULDER_LARGE)),
            "portitor" to mapOf(SurfaceMaterial.LAYERED_ROCK to listOf(0.004 to ScatterKind.PINNACLE, 0.05 to ScatterKind.BOULDER_SMALL)),
            // Fumaroles in the red sulfur.
            "fornax" to mapOf(SurfaceMaterial.RED_SULFUR to listOf(0.0015 to ScatterKind.FUMAROLE, 0.02 to ScatterKind.BOULDER_SMALL)),
            // A field of ice blades in the Spires, ice blocks in the chaos.
            "crusta" to mapOf(
                SurfaceMaterial.SNOW to listOf(0.14 to ScatterKind.ICE_SPIRE),
                SurfaceMaterial.THOLIN to listOf(0.04 to ScatterKind.ICE_BLOCK),
                SurfaceMaterial.ICE to listOf(0.002 to ScatterKind.ICE_SPIRE),
            ),
            "cicatrix" to mapOf(SurfaceMaterial.FROST to listOf(0.01 to ScatterKind.ICE_SPIRE, 0.04 to ScatterKind.BOULDER_LARGE)),
            // Geysers along the vents, few, and ice thrown out round them.
            "fons" to mapOf(
                SurfaceMaterial.VENT_ICE to listOf(0.0004 to ScatterKind.GEYSER, 0.03 to ScatterKind.ICE_BLOCK),
                SurfaceMaterial.SNOW to listOf(0.003 to ScatterKind.ICE_BLOCK),
            ),
            "aversa" to mapOf(SurfaceMaterial.VENT_ICE to listOf(0.0006 to ScatterKind.GEYSER, 0.02 to ScatterKind.ICE_BLOCK)),
            "ultima" to mapOf(
                SurfaceMaterial.NITROGEN_ICE to listOf(0.006 to ScatterKind.ICE_BLOCK),
                SurfaceMaterial.ICE to listOf(0.03 to ScatterKind.ICE_SPIRE),
            ),
            // Pebbles of ice on the flats between the dunes.
            "aurantia" to mapOf(
                SurfaceMaterial.SALT to listOf(0.012 to ScatterKind.BOULDER_SMALL),
                SurfaceMaterial.ICE to listOf(0.01 to ScatterKind.ICE_BLOCK),
            ),
        )
    }
}
