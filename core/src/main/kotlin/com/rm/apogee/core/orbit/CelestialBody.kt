package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * A planet, moon or star.
 *
 * Bodies are identified by [id] and linked by [parentId] into a tree; the root
 * is the star. A body's position at a given time comes from propagating its own
 * orbit about its parent, so the whole system is derived from time rather than
 * simulated - stars and planets are on rails permanently, and only craft are
 * ever integrated.
 */
class CelestialBody(
    val id: String,
    val displayName: String,
    /** Standard gravitational parameter GM, m³/s². */
    val gravitationalParameter: Double,
    /** Radius of the datum ("sea level") surface, metres. */
    val radius: Double,
    /** Sidereal rotation period, seconds. Zero means it does not rotate. */
    val rotationPeriod: Double = 0.0,
    val atmosphere: Atmosphere? = null,
    /**
     * The shape of the surface, or null for a perfectly smooth body.
     *
     * The renderer builds its mesh by sampling this, and the collider resolves
     * against it, so there is one definition of where the ground is.
     */
    val terrain: com.rm.apogee.core.terrain.Terrain? = null,
    /** The sea over whatever of [terrain] lies below the datum, or null for a dry body. */
    val ocean: com.rm.apogee.core.terrain.Ocean? = null,
    val parentId: String? = null,
    /** This body's orbit about its parent. Null for the root. */
    val orbit: Orbit? = null,
    /**
     * Sphere-of-influence radius, metres. Inside it, this body is treated as
     * the only source of gravity.
     *
     * A patched-conic device rather than physics: real gravity has no edge, but
     * committing to one dominant attractor at a time is what makes orbits
     * predictable enough to plan against, and what lets distant craft be
     * propagated analytically instead of integrated.
     */
    val sphereOfInfluence: Double = Double.POSITIVE_INFINITY,
) {
    /** Surface gravity at the datum, m/s². */
    val surfaceGravity: Double get() = gravitationalParameter / (radius * radius)

    /** Speed of a circular orbit at [radiusFromCentre], m/s. */
    fun circularVelocityAt(radiusFromCentre: Double): Double =
        sqrt(gravitationalParameter / radiusFromCentre)

    /** Escape speed at [radiusFromCentre], m/s. */
    fun escapeVelocityAt(radiusFromCentre: Double): Double =
        sqrt(2.0 * gravitationalParameter / radiusFromCentre)

    val hasAtmosphere: Boolean get() = atmosphere != null

    /** Altitude where the atmosphere ends, or 0 if there is none. */
    val atmosphereHeight: Double get() = atmosphere?.height ?: 0.0

    /**
     * Height above the datum - "sea level" - in metres.
     *
     * This is the altitude orbital mechanics cares about, and the one the
     * atmosphere model is defined against. It is *not* the height above the
     * ground beneath you; see [heightAboveTerrain].
     */
    fun altitudeOf(positionRelativeToCentre: Vec3): Double =
        positionRelativeToCentre.length - radius

    /**
     * Height above the ground directly below, in metres.
     *
     * The number a pilot wants when landing, and quite different from
     * [altitudeOf] over a mountain range.
     *
     * @param bodyFixedDirection the position, rotated into the body's own
     *   turning frame. See [surfaceRadiusInBodyFrame] for why that matters.
     */
    fun heightAboveTerrain(positionRelativeToCentre: Vec3, bodyFixedDirection: Vec3): Double {
        val field = terrain ?: return altitudeOf(positionRelativeToCentre)
        return positionRelativeToCentre.length - field.surfaceRadius(bodyFixedDirection)
    }

    /**
     * Distance from the centre to the ground below a **body-fixed** direction.
     *
     * Body-fixed, not inertial, and the distinction is not pedantry. Terrain
     * is carved into a planet that turns: at the equator the surface moves at
     * 175 m/s, so a height field sampled in the inertial frame scrolls past a
     * parked craft at that speed. The first version did exactly that and the
     * stock rocket climbed steadily off its pad, riding a hillside that was
     * sliding underneath it.
     *
     * Callers convert with [toBodyFixed], usually once per tick rather than
     * once per contact point.
     */
    fun surfaceRadiusInBodyFrame(bodyFixedDirection: Vec3): Double =
        terrain?.surfaceRadius(bodyFixedDirection) ?: radius

    /**
     * Distance from the centre to the solid ground below a body-fixed
     * direction - the sea floor, at sea. What contacts resolve against.
     */
    fun solidRadiusInBodyFrame(bodyFixedDirection: Vec3): Double =
        terrain?.solidRadius(bodyFixedDirection) ?: radius

    /**
     * The ground along a body-fixed direction, as the collider needs it:
     * where it is, which way it faces, what it is made of.
     *
     * Read from the terrain's tiles rather than the height function, so it
     * is exactly the drawn surface - flat facets and their real normals - and
     * costs a lookup rather than a full evaluation of the field.
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
     * Takes the rotation as an argument rather than computing it, so a caller
     * touching many points in one tick computes it once.
     */
    fun toBodyFixed(direction: Vec3, rotation: Quat, out: Vec3 = Vec3()): Vec3 =
        if (rotationPeriod == 0.0) out.setTo(direction) else rotation.inverseRotate(direction, out)

    /**
     * Gravitational acceleration at [positionRelativeToCentre], written into
     * [out]. Allocation-free; called for every vessel every tick.
     */
    fun gravityAt(positionRelativeToCentre: Vec3, out: Vec3 = Vec3()): Vec3 {
        val distanceSq = positionRelativeToCentre.lengthSq
        if (distanceSq < 1.0) return out.setZero()
        val distance = kotlin.math.sqrt(distanceSq)
        // -mu / r^2 along the unit vector, folded into one scale factor.
        val scale = -gravitationalParameter / (distanceSq * distance)
        return out.setTo(positionRelativeToCentre).mulInPlace(scale)
    }

    /** Rotation of the body's surface frame at [time], about its +Y axis. */
    fun rotationAt(time: Double, out: Quat = Quat()): Quat {
        if (rotationPeriod == 0.0) return out.setIdentity()
        val angle = 2.0 * PI * (time / rotationPeriod)
        return Quat.fromAxisAngle(Vec3.unitY(), angle, out)
    }

    /**
     * The body's own rotation rate as a vector, rad/s, written into [out].
     *
     * What a craft resting on the surface is turning at: a base on a pad is
     * not stationary, it is going round once a day with the ground.
     */
    fun angularVelocity(out: Vec3 = Vec3()): Vec3 {
        if (rotationPeriod == 0.0) return out.setZero()
        return out.setTo(0.0, 2.0 * PI / rotationPeriod, 0.0)
    }

    /** Surface velocity due to rotation at [positionRelativeToCentre], m/s. */
    fun surfaceVelocityAt(positionRelativeToCentre: Vec3, out: Vec3 = Vec3()): Vec3 {
        if (rotationPeriod == 0.0) return out.setZero()
        val omega = 2.0 * PI / rotationPeriod
        // v = omega_vector x r, with omega along +Y.
        return out.setTo(
            omega * positionRelativeToCentre.z,
            0.0,
            -omega * positionRelativeToCentre.x,
        )
    }

    override fun toString(): String = "CelestialBody($id)"
}
