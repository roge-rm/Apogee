package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * A planet, moon or star.
 *
 * Bodies are identified by [id] and linked by [parentId] into a tree, with the star at the root. A
 * body's position at a given time comes from following its own orbit around its parent, so the
 * whole system is worked out from time instead of simulated. Stars and planets are always on rails,
 * and only craft ever get integrated.
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
     * The shape of the surface, or null for a perfectly smooth body.
     *
     * The renderer builds its mesh by sampling this and the collider works against it, so there's
     * only one definition of where the ground is.
     */
    val terrain: com.rm.apogee.core.terrain.Terrain? = null,
    /** The sea over any part of [terrain] below the datum, or null for a dry body. */
    val ocean: com.rm.apogee.core.terrain.Ocean? = null,
    val parentId: String? = null,
    /** This body's orbit around its parent. Null for the root. */
    val orbit: Orbit? = null,
    /**
     * Sphere of influence radius, in metres. Inside it, this body is treated as the only source of
     * gravity.
     *
     * This is a patched-conic shortcut, not physics. Real gravity has no edge, but committing to
     * one main attractor at a time is what makes orbits predictable enough to plan with, and it's
     * what lets distant craft be propagated with formulas instead of integrated.
     */
    val sphereOfInfluence: Double = Double.POSITIVE_INFINITY,
    /**
     * The axis the body spins around, inertial and unit length: its north pole. It's world +Y for
     * Terra and Luna, because everything was built in their ground frames, and tipped over for the
     * rest. Obliqua lies on its side and Caligo spins backwards.
     */
    spinAxis: Vec3 = Vec3.unitY(),
    /** Its rings, if it has any: inner and outer radius in metres, in its equatorial plane. */
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
     * Height above the datum ("sea level"), in metres.
     *
     * This is the height orbital mechanics cares about, and the one the atmosphere model is defined
     * against. It's *not* your height above the ground below you. See [heightAboveTerrain].
     */
    fun altitudeOf(positionRelativeToCentre: Vec3): Double =
        positionRelativeToCentre.length - radius

    /**
     * Height above the ground directly below, in metres.
     *
     * This is the number a pilot wants when landing, and it's very different from [altitudeOf] over
     * a mountain range.
     *
     * @param bodyFixedDirection the position, rotated into the body's own turning frame. See
     *     [surfaceRadiusInBodyFrame] for why that matters.
     */
    fun heightAboveTerrain(positionRelativeToCentre: Vec3, bodyFixedDirection: Vec3): Double {
        val field = terrain ?: return altitudeOf(positionRelativeToCentre)
        return positionRelativeToCentre.length - field.surfaceRadius(bodyFixedDirection)
    }

    /**
     * The distance from the centre to the ground below a **body-fixed** direction.
     *
     * Body-fixed, not inertial, and the difference isn't nitpicking. Terrain is carved into a
     * planet that turns. At the equator the surface moves at 175 m/s, so a height field sampled in
     * the inertial frame scrolls past a parked craft at that speed. The first version did exactly
     * that, and the stock rocket climbed steadily off its pad, riding a hillside that was sliding
     * along underneath it.
     *
     * Callers convert with [toBodyFixed], usually once per tick rather than once per contact point.
     */
    fun surfaceRadiusInBodyFrame(bodyFixedDirection: Vec3): Double =
        terrain?.surfaceRadius(bodyFixedDirection) ?: radius

    /**
     * The distance from the centre to the solid ground below a body-fixed direction, which is the
     * sea floor at sea. This is what contacts get resolved against.
     */
    fun solidRadiusInBodyFrame(bodyFixedDirection: Vec3): Double =
        terrain?.solidRadius(bodyFixedDirection) ?: radius

    /**
     * The ground along a body-fixed direction, the way the collider needs it: where it is, which
     * way it faces, and what it's made of.
     *
     * It's read from the terrain's tiles instead of the height function, so it's exactly the drawn
     * surface, with flat facets and their real normals, and it costs a lookup instead of a full
     * evaluation of the field.
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
     * Rotates an inertial direction into the body's turning frame at [time].
     *
     * It takes the rotation as an argument instead of working it out, so a caller touching lots of
     * points in one tick only works it out once.
     */
    fun toBodyFixed(direction: Vec3, rotation: Quat, out: Vec3 = Vec3()): Vec3 =
        if (rotationPeriod == 0.0 && upright) out.setTo(direction) else rotation.inverseRotate(direction, out)

    /**
     * Gravitational acceleration at [positionRelativeToCentre], written into [out]. It doesn't
     * allocate, and it's called for every vessel every tick.
     */
    fun gravityAt(positionRelativeToCentre: Vec3, out: Vec3 = Vec3()): Vec3 {
        val distanceSq = positionRelativeToCentre.lengthSq
        if (distanceSq < 1.0) return out.setZero()
        val distance = kotlin.math.sqrt(distanceSq)
        // -mu / r^2 along the unit vector, folded into one scale factor.
        val scale = -gravitationalParameter / (distanceSq * distance)
        return out.setTo(positionRelativeToCentre).mulInPlace(scale)
    }

    /**
     * Rotation of the body's surface frame at [time]: turned around its own +Y by the time of day,
     * then tipped onto [spinAxis].
     */
    fun rotationAt(time: Double, out: Quat = Quat()): Quat {
        if (rotationPeriod == 0.0) return out.setTo(tilt)
        val angle = 2.0 * PI * (time / rotationPeriod)
        Quat.fromAxisAngle(Vec3.unitY(), angle, out)
        if (upright) return out
        return out.setTo(tilt * out)
    }

    /**
     * The body's own rotation rate as a vector, in rad/s, written into [out].
     *
     * This is what a craft resting on the surface is turning at. A base on a pad isn't stationary.
     * It's going round once a day with the ground.
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
