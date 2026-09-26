package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.ResourceType

/**
 * How much ore or water the ground holds, 0..1: what a drill there brings up
 * for each unit of its rate.
 *
 * Read off the ground itself - the same material the renderer colours and
 * the wheels grip - with a slow patchiness over it, so two stretches of the
 * same rock are not equally good and a survey is worth having. Deterministic
 * from the terrain, so client and server agree without saying anything.
 * Deposits never run out: a spot is only as good as its ground.
 */
object Deposits {

    /** How rich the ground at body-fixed [direction] of [terrain] is in [resource], 0..1. */
    fun richness(terrain: Terrain, direction: Vec3, resource: ResourceType): Double {
        if (resource != ResourceType.ORE && resource != ResourceType.WATER) return 0.0
        val d = scratchD.get().setTo(direction).normalizeInPlace()
        val h = terrain.elevation(d)
        if (terrain.hasOcean && h < 0.0) return 0.0
        val material = terrain.material(d, h, slopeAt(terrain, d, h))
        return richnessOf(material, resource, patch(terrain, d, resource))
    }

    /** [richness] for ground of [material] at patchiness [patch], 0..1: the table itself. */
    fun richnessOf(material: SurfaceMaterial, resource: ResourceType, patch: Double): Double {
        val range = when (resource) {
            ResourceType.ORE -> ORE[material.ordinal]
            ResourceType.WATER -> WATER[material.ordinal]
            else -> return 0.0
        }
        return range.first + (range.second - range.first) * patch
    }

    /** The slow patchiness laid over the ground's own richness, 0..1. */
    private fun patch(terrain: Terrain, d: Vec3, resource: ResourceType): Double {
        val r = terrain.bodyRadius * PATCH_FREQUENCY
        val seed = if (resource == ResourceType.ORE) ORE_SEED else WATER_SEED
        val n = Noise.simplex(seed, d.x * r, d.y * r, d.z * r) * 0.7 +
            Noise.simplex(seed + 1, d.x * r * 3.1, d.y * r * 3.1, d.z * r * 3.1) * 0.3
        return Noise.smoothstep((0.5 + 0.5 * n).coerceIn(0.0, 1.0))
    }

    /** Slope as the terrain's materials take it, from two samples a few metres off. */
    private fun slopeAt(terrain: Terrain, d: Vec3, h: Double): Double {
        val step = SLOPE_STEP / terrain.bodyRadius
        val axis = if (kotlin.math.abs(d.y) < 0.9) Vec3.unitY() else Vec3.unitX()
        val e = axis.cross(d).normalizeInPlace()
        val n = d.cross(e).normalizeInPlace()
        val he = terrain.elevation(e.mulInPlace(step).addInPlace(d)) - h
        val hn = terrain.elevation(n.mulInPlace(step).addInPlace(d)) - h
        val gradient = kotlin.math.sqrt(he * he + hn * hn) / SLOPE_STEP
        return 1.0 - 1.0 / kotlin.math.sqrt(1.0 + gradient * gradient)
    }

    private val scratchD = ThreadLocal.withInitial { Vec3() }

    private const val PATCH_FREQUENCY = 1.0 / 40_000.0
    private const val SLOPE_STEP = 4.0
    private const val ORE_SEED = 0x0DE5
    private const val WATER_SEED = 0x1CE5

    /** Ore, low to high, by ground. */
    private val ORE: Array<Pair<Double, Double>> = Array(SurfaceMaterial.entries.size) { i ->
        when (SurfaceMaterial.entries[i]) {
            SurfaceMaterial.SCREE -> 0.55 to 0.95
            SurfaceMaterial.CLAY -> 0.4 to 0.8
            SurfaceMaterial.ROCK -> 0.35 to 0.75
            SurfaceMaterial.BASALT -> 0.4 to 0.7
            SurfaceMaterial.REGOLITH -> 0.15 to 0.3
            SurfaceMaterial.DIRT -> 0.1 to 0.3
            SurfaceMaterial.SAND -> 0.1 to 0.25
            SurfaceMaterial.GRASS, SurfaceMaterial.FOREST, SurfaceMaterial.MUD -> 0.03 to 0.07
            SurfaceMaterial.SNOW -> 0.02 to 0.06
            SurfaceMaterial.ICE, SurfaceMaterial.CONCRETE, SurfaceMaterial.ASPHALT -> 0.0 to 0.0
            SurfaceMaterial.SULFUR -> 0.6 to 0.95
            SurfaceMaterial.RED_DUST -> 0.25 to 0.5
            SurfaceMaterial.TESSERA -> 0.45 to 0.8
            SurfaceMaterial.THOLIN -> 0.2 to 0.4
            SurfaceMaterial.ORGANIC_SAND -> 0.1 to 0.2
            SurfaceMaterial.NITROGEN_ICE, SurfaceMaterial.LAVA -> 0.0 to 0.0
        }
    }

    /** Water, low to high, by ground: only where there is ice. */
    private val WATER: Array<Pair<Double, Double>> = Array(SurfaceMaterial.entries.size) { i ->
        when (SurfaceMaterial.entries[i]) {
            SurfaceMaterial.ICE -> 0.8 to 1.0
            SurfaceMaterial.SNOW -> 0.3 to 0.5
            SurfaceMaterial.NITROGEN_ICE -> 0.5 to 0.7
            SurfaceMaterial.THOLIN -> 0.05 to 0.15
            else -> 0.0 to 0.0
        }
    }
}
