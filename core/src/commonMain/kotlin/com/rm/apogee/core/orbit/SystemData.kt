package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math

/**
 * The worlds beyond Terra and Luna, as numbers.
 *
 * One scale for all: radii at the real ratio times 0.094 (Terra's), real surface gravity, days a
 * quarter of real, and planet orbits with their real shape and spacing at 13.6 Gm per AU. A moon
 * sits at half its real distance in planet radii, and no closer than three. Exceptions (Rubra's two
 * tiny moons would be smaller than their own sphere of influence) are noted where they are.
 *
 * Terra still spins about world +Y and Luna still orbits it, so every base, runway and saved craft
 * stays put. Instead the ecliptic is tipped [OBLIQUITY] about Terra, set so the sun at time zero
 * stands within a few degrees of where the old fixed sun did: midsummer at the Cape.
 */
object SystemData {

    /** Terra's tilt in radians: the ecliptic's tip from its equator. */
    val OBLIQUITY = Math.toRadians(23.4)

    /** One AU at this scale, in metres: Terra's distance from Sol. */
    const val AU = 13_599_840_256.0

    /** Where the old fixed sun stood, about the sun's direction at time zero. */
    private val OLD_SUN = Vec3(0.62, 0.45, 0.64).normalizeInPlace()

    /**
     * Ecliptic north, inertial, tipped from +Y away from the old sun, so the sun stands at
     * midsummer.
     */
    val ECLIPTIC_NORTH: Vec3 = run {
        val h = Vec3(OLD_SUN.x, 0.0, OLD_SUN.z).normalizeInPlace()
        Vec3.unitY().mulInPlace(cos(OBLIQUITY)).addScaledInPlace(h, -sin(OBLIQUITY)).normalizeInPlace()
    }

    /** Takes the ecliptic's frame (north +Y) into the world's. */
    val ECLIPTIC: Quat = quatFromTo(Vec3.unitY(), ECLIPTIC_NORTH)

    /** A spin axis tipped [tilt] radians from [north], toward [azimuth] radians about it. */
    fun axis(north: Vec3, tilt: Double, azimuth: Double): Vec3 {
        val a = (if (kotlin.math.abs(north.y) < 0.9) Vec3.unitY() else Vec3.unitX()).cross(north).normalizeInPlace()
        val b = north.cross(a).normalizeInPlace()
        val side = a.mulInPlace(cos(azimuth)).addScaledInPlace(b, sin(azimuth))
        return north.copy().mulInPlace(cos(tilt)).addScaledInPlace(side, sin(tilt)).normalizeInPlace()
    }

    /** The frame whose +Y is [axis] (a body's equator), for its moons and rings. */
    fun equator(axis: Vec3): Quat = quatFromTo(Vec3.unitY(), axis)

    /**
     * Laplace sphere of influence in metres, for a body of [mu] at distance [a] from a parent of
     * [parentMu].
     */
    fun sphereOfInfluence(a: Double, mu: Double, parentMu: Double): Double = a * (mu / parentMu).pow(0.4)

    /** μ from surface gravity [g] at radius [r]. */
    fun mu(g: Double, r: Double): Double = g * r * r

    /**
     * Terra's orbit in the ecliptic, set so the sun at time zero stands as near as it can to the
     * old fixed sun.
     */
    fun terraOrbit(solMu: Double): Orbit {
        // Terra sits opposite the sun, at -OLD_SUN, as near as the ecliptic allows.
        val local = ECLIPTIC.inverseRotate(OLD_SUN.copy().mulInPlace(-1.0), Vec3())
        // In the ecliptic frame, prograde from +X toward -Z, like Orbit.circular.
        val anomaly = kotlin.math.atan2(-local.z, local.x)
        return Orbit.fromElements(AU, 0.0, 0.0, 0.0, 0.0, anomaly, solMu, frame = ECLIPTIC)
    }

    /** A planet's heliocentric orbit, in the ecliptic, from real elements. */
    fun planetOrbit(au: Double, e: Double, iDegrees: Double, nodeDegrees: Double, periDegrees: Double, anomalyDegrees: Double, solMu: Double): Orbit =
        Orbit.fromElements(
            au * AU, e, Math.toRadians(iDegrees), Math.toRadians(nodeDegrees), Math.toRadians(periDegrees - nodeDegrees),
            Math.toRadians(anomalyDegrees), solMu, frame = ECLIPTIC,
        )

    /**
     * A moon's orbit in its planet's equatorial plane, [radii] planet radii out, tipped [iDegrees]
     * from the equator.
     */
    fun moonOrbit(planet: CelestialBody, radii: Double, iDegrees: Double, anomalyDegrees: Double, e: Double = 0.0, nodeDegrees: Double = 0.0): Orbit =
        Orbit.fromElements(
            radii * planet.radius, e, Math.toRadians(iDegrees), Math.toRadians(nodeDegrees), 0.0,
            Math.toRadians(anomalyDegrees), planet.gravitationalParameter, frame = equator(planet.spinAxis),
        )

    /** Time for one orbit, in seconds. */
    fun period(orbit: Orbit): Double = 2 * PI * sqrt(orbit.semiMajorAxis.pow(3) / orbit.mu)
}
