package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import kotlin.math.max

/**
 * A body's solid surface: one function, worked out wherever anyone asks. An interface so tests can
 * point the collider and renderer at a cliff exactly where they want one.
 */
interface Terrain {
    /** Radius of the datum surface, in metres. */
    val bodyRadius: Double

    /** Metres from the datum to the highest the surface can reach. */
    val maxElevation: Double

    /**
     * The generator version. It goes up whenever a seed's ground changes, so saves and servers know
     * to set craft left on the old surface down on the new one.
     */
    val generation: Int

    /** Height above the datum at [direction], in metres. Negative under the sea. */
    fun elevation(direction: Vec3): Double

    /**
     * What the ground is made of, given what the tile builder knows: height and slope (0 flat, 1 a
     * wall).
     */
    fun material(direction: Vec3, elevation: Double, slope: Double): SurfaceMaterial

    /**
     * The land's own material under anything laid over it and drawn separately, like paving. Same
     * as [material] where nothing's on top.
     */
    fun groundMaterial(direction: Vec3, elevation: Double, slope: Double): SurfaceMaterial = material(direction, elevation, slope)

    /** The sampled, cached form of this surface, which the collider reads. */
    val tiles: TerrainTileCache

    /** What lies and grows on it. Null for a surface with nothing on it. */
    val scatter: ScatterField? get() = null

    /** Lifeless: only rocks are scattered on it, never Terra's trees and scrub. */
    val barren: Boolean get() = false

    /** Which world's ground this is, which picks its colours. */
    val world: String get() = "terra"

    /**
     * Ground kept clear for the launch complex (pad, runway and the blends round them). Nothing
     * grows or lies there.
     */
    fun isLaunchComplex(direction: Vec3): Boolean = false

    /** How much vent field there is on the sea floor at [direction], 0..1. See [Seabed.ventField]. */
    fun ventField(direction: Vec3): Double = 0.0

    /**
     * Whether ground below the datum is under a sea. False for an airless body, whose low places,
     * like Luna's maria, are just low.
     */
    val hasOcean: Boolean get() = true

    /** The top of whatever is there: ground, or the calm sea over it. */
    fun surfaceRadius(direction: Vec3): Double {
        val e = elevation(direction)
        return bodyRadius + if (hasOcean) max(e, 0.0) else e
    }

    /** The ground itself, including the sea floor. */
    fun solidRadius(direction: Vec3): Double = bodyRadius + elevation(direction)

    fun isOcean(direction: Vec3): Boolean = hasOcean && elevation(direction) < 0.0
}
