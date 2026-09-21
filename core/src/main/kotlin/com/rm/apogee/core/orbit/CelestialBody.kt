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

    fun altitudeOf(positionRelativeToCentre: Vec3): Double =
        positionRelativeToCentre.length - radius

    /**
     * Gravitational acceleration at [positionRelativeToCentre], written into
     * [out]. Allocation-free; called for every vessel every tick.
     */
    fun gravityAt(positionRelativeToCentre: Vec3, out: Vec3 = Vec3()): Vec3 {
        val distanceSq = positionRelativeToCentre.lengthSq
        if (distanceSq < 1.0) return out.setZero()
        val distance = sqrt(distanceSq)
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
