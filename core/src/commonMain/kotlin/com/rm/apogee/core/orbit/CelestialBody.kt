package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * A planet, moon or star. Bodies form a tree by [parentId], star at the root. Positions come from
 * each body's orbit about its parent as a function of time; bodies are always on rails and only
 * craft are integrated.
 */
class CelestialBody(
    val id: String,
    val displayName: String,
    /** Standard gravitational parameter GM, in m³/s². */
    val gravitationalParameter: Double,
    /** Radius of the datum ("sea level") surface, in metres. */
    val radius: Double,
    /** Sidereal rotation period, in seconds. Zero means it doesn't rotate. */
    val rotationPeriod: Double = 0.0,
    val atmosphere: Atmosphere? = null,
    /**
     * The surface shape, or null for a smooth body. The renderer and collider both use it, so
     * there's one definition of the ground.
     */
    val terrain: com.rm.apogee.core.terrain.Terrain? = null,
    /** The sea over any part of [terrain] below the datum, or null for a dry body. */
    val ocean: com.rm.apogee.core.terrain.Ocean? = null,
    val parentId: String? = null,
    /** This body's orbit about its parent. Null for the root. */
    val orbit: Orbit? = null,
    /**
     * Sphere of influence radius in metres. Inside it, this body is the only source of gravity. A
     * patched-conic shortcut that keeps orbits plannable and lets distant craft be propagated by
     * formula.
     */
    val sphereOfInfluence: Double = Double.POSITIVE_INFINITY,
    /**
     * The inertial unit spin axis (north pole). World +Y for Terra and Luna, since everything was
     * built in their ground frames; tipped for the rest. Obliqua lies on its side and Caligo spins
     * backwards.
     */
    spinAxis: Vec3 = Vec3.unitY(),
    /** Its rings, if any: inner and outer radius in metres, in its equatorial plane. */
    val rings: Rings? = null,
) {
    /** Its north pole, inertial, unit length. */
    val spinAxis: Vec3 = spinAxis.normalized()

    /** Tips body-fixed +Y onto [spinAxis]. Identity for an upright body. */
    private val tilt: Quat = com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), this.spinAxis)
    private val upright: Boolean = this.spinAxis.y > 1.0 - 1e-12

    /** Surface gravity at the datum, in m/s². */
    val surfaceGravity: Double get() = gravitationalParameter / (radius * radius)

    /** The speed of a circular orbit at [radiusFromCentre], in m/s. */
    fun circularVelocityAt(radiusFromCentre: Double): Double =
        sqrt(gravitationalParameter / radiusFromCentre)

    /** Escape speed at [radiusFromCentre], in m/s. */
    fun escapeVelocityAt(radiusFromCentre: Double): Double =
        sqrt(2.0 * gravitationalParameter / radiusFromCentre)

    val hasAtmosphere: Boolean get() = atmosphere != null

    /** The height where the atmosphere ends, or 0 if there isn't one. */
    val atmosphereHeight: Double get() = atmosphere?.height ?: 0.0

    /**
     * Height above the datum ("sea level") in metres, as orbits and the atmosphere use. Not height
     * above the ground; see [heightAboveTerrain].
     */
    fun altitudeOf(positionRelativeToCentre: Vec3): Double =
        positionRelativeToCentre.length - radius

    /**
     * Height above the ground directly below, in metres: what a pilot wants when landing.
     *
     * @param bodyFixedDirection the position in the body's turning frame. See
     * [surfaceRadiusInBodyFrame].
     */
    fun heightAboveTerrain(positionRelativeToCentre: Vec3, bodyFixedDirection: Vec3): Double {
        val field = terrain ?: return altitudeOf(positionRelativeToCentre)
        return positionRelativeToCentre.length - field.surfaceRadius(bodyFixedDirection)
    }

    /**
     * Distance from the centre to the ground below a body-fixed direction. Must be body-fixed: the
     * surface moves at 175 m/s at the equator, so sampling inertially would slide the terrain under
     * a parked craft. Callers convert with [toBodyFixed], usually once per tick.
     */
    fun surfaceRadiusInBodyFrame(bodyFixedDirection: Vec3): Double =
        terrain?.surfaceRadius(bodyFixedDirection) ?: radius

    /**
     * Distance from the centre to solid ground below a body-fixed direction (the sea floor at sea).
     * Contacts resolve against this.
     */
    fun solidRadiusInBodyFrame(bodyFixedDirection: Vec3): Double =
        terrain?.solidRadius(bodyFixedDirection) ?: radius

    /**
     * The ground along a body-fixed direction for the collider: position, normal and material. Read
     * from the terrain tiles, so it matches the drawn facets exactly and costs a lookup rather than
     * a field evaluation.
     */
    fun groundInBodyFrame(
        bodyFixedDirection: Vec3,
        out: com.rm.apogee.core.terrain.GroundPoint,
        lookup: com.rm.apogee.core.terrain.TerrainTileCache.Lookup,
    ) {
        val field = terrain
        if (field == null) {
            out.radius = radius
            out.normal.setTo(bodyFixedDirection).normalizeInPlace()
            out.material = com.rm.apogee.core.terrain.SurfaceMaterial.GRASS
            return
        }
        field.tiles.ground(bodyFixedDirection, out, lookup)
    }

    /**
     * Rotates an inertial direction into the body's turning frame. Takes [rotation] so a caller can
     * compute it once per tick.
     */
    fun toBodyFixed(direction: Vec3, rotation: Quat, out: Vec3 = Vec3()): Vec3 =
        if (rotationPeriod == 0.0 && upright) out.setTo(direction) else rotation.inverseRotate(direction, out)

    /**
     * Gravitational acceleration at [positionRelativeToCentre], into [out]. Called for every vessel
     * every tick, so it doesn't allocate.
     */
    fun gravityAt(positionRelativeToCentre: Vec3, out: Vec3 = Vec3()): Vec3 {
        val distanceSq = positionRelativeToCentre.lengthSq
        if (distanceSq < 1.0) return out.setZero()
        val distance = kotlin.math.sqrt(distanceSq)
        // -mu / r^2 along the unit vector, as one scale factor.
        val scale = -gravitationalParameter / (distanceSq * distance)
        return out.setTo(positionRelativeToCentre).mulInPlace(scale)
    }

    /**
     * The surface frame's rotation at [time]: turned about its +Y by the time of day, then tipped
     * onto [spinAxis].
     */
    fun rotationAt(time: Double, out: Quat = Quat()): Quat {
        if (rotationPeriod == 0.0) return out.setTo(tilt)
        val angle = 2.0 * PI * (time / rotationPeriod)
        Quat.fromAxisAngle(Vec3.unitY(), angle, out)
        if (upright) return out
        return out.setTo(tilt * out)
    }

    /**
     * The body's rotation rate vector in rad/s, into [out]. A craft resting on the surface turns at
     * this.
     */
    fun angularVelocity(out: Vec3 = Vec3()): Vec3 {
        if (rotationPeriod == 0.0) return out.setZero()
        return out.setTo(spinAxis).mulInPlace(2.0 * PI / rotationPeriod)
    }

    /** Surface velocity due to rotation at [positionRelativeToCentre], in m/s. */
    fun surfaceVelocityAt(positionRelativeToCentre: Vec3, out: Vec3 = Vec3()): Vec3 {
        if (rotationPeriod == 0.0) return out.setZero()
        val omega = 2.0 * PI / rotationPeriod
        // v = omega_vector x r.
        val a = spinAxis
        val p = positionRelativeToCentre
        return out.setTo(
            omega * (a.y * p.z - a.z * p.y),
            omega * (a.z * p.x - a.x * p.z),
            omega * (a.x * p.y - a.y * p.x),
        )
    }

    override fun toString(): String = "CelestialBody($id)"
}

/** A body's rings: from [inner] to [outer], in metres from its centre, in its equatorial plane. */
data class Rings(val inner: Double, val outer: Double)
