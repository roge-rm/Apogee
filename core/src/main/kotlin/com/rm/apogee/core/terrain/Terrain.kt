package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import kotlin.math.max

/**
 * A body's solid surface: one function, evaluated wherever anyone asks.
 *
 * An interface so the collider and renderer can be pointed at something other
 * than a whole generated planet - a test needs a cliff exactly where it wants
 * one, not wherever the noise happens to put one.
 */
interface Terrain {
    /** Radius of the datum surface, metres. */
    val bodyRadius: Double

    /** Metres from the datum to the highest the surface can reach. */
    val maxElevation: Double

    /**
     * Which version of the generator this is. Bumped whenever the ground a
     * given seed produces changes, so saves and servers can tell that craft
     * left on the old surface need setting down on the new one.
     */
    val generation: Int

    /** Height above the datum at [direction], metres; negative under the sea. */
    fun elevation(direction: Vec3): Double

    /**
     * What the ground is made of, given what the tile builder already knows
     * about the point: its height and how steep it is (0 flat, 1 a wall).
     */
    fun material(direction: Vec3, elevation: Double, slope: Double): SurfaceMaterial

    /**
     * What the ground is to look at, where something is laid over it and
     * drawn apart: the land's own under any paving. The same as [material]
     * wherever nothing is.
     */
    fun groundMaterial(direction: Vec3, elevation: Double, slope: Double): SurfaceMaterial = material(direction, elevation, slope)

    /** The sampled, cached form of this surface, which the collider reads. */
    val tiles: TerrainTileCache

    /** What lies and grows on it. Null for a surface with nothing on it. */
    val scatter: ScatterField? get() = null

    /**
     * Ground kept clear for the launch complex - pad, runway, and their
     * blends - where nothing may grow or lie.
     */
    fun isLaunchComplex(direction: Vec3): Boolean = false

    /**
     * Whether ground below the datum is under a sea. False for an airless
     * body, whose low places - Luna's maria - are just low.
     */
    val hasOcean: Boolean get() = true

    /** Top of whatever is there - ground, or the calm sea over it. */
    fun surfaceRadius(direction: Vec3): Double {
        val e = elevation(direction)
        return bodyRadius + if (hasOcean) max(e, 0.0) else e
    }

    /** The ground itself, sea floor included. */
    fun solidRadius(direction: Vec3): Double = bodyRadius + elevation(direction)

    fun isOcean(direction: Vec3): Boolean = hasOcean && elevation(direction) < 0.0
}
