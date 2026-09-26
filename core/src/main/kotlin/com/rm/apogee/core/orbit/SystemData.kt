package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The worlds beyond Terra and Luna, as numbers.
 *
 * Built to one scale throughout: radii at the real ratio × 0.094 - Terra's -
 * surface gravity as it really is, days a quarter of the real ones, and the
 * planets' orbits at their real shape and spacing, 13.6 Gm to the
 * astronomical unit. A moon sits at half its real distance in its planet's
 * radii, no nearer than three. Where the rule breaks something - the two
 * tiny moons of Rubra would be smaller than their own reach - the exception
 * is written beside it.
 *
 * Terra spins about the world's +Y, as it always has, and Luna goes round
 * it as it always has: every base, runway and saved craft stays where it
 * is on the ground. It is the planets' plane - the ecliptic - that is
 * tipped [OBLIQUITY] about Terra instead, set so that the sun at time zero
 * stands where the old fixed sun did, give or take a few degrees: midsummer
 * at the Cape.
 */
object SystemData {

    /** Terra's tilt, radians: how far the ecliptic is tipped from its equator. */
    val OBLIQUITY = Math.toRadians(23.4)

    /** One astronomical unit at this scale, m: Terra's distance from Sol. */
    const val AU = 13_599_840_256.0

    /** Where the old fixed sun stood: the sun's direction at time zero, give or take. */
    private val OLD_SUN = Vec3(0.62, 0.45, 0.64).normalizeInPlace()

    /** Ecliptic north, inertial: tipped from +Y away from the old sun, so the sun stands at midsummer. */
    val ECLIPTIC_NORTH: Vec3 = run {
        val h = Vec3(OLD_SUN.x, 0.0, OLD_SUN.z).normalizeInPlace()
        Vec3.unitY().mulInPlace(cos(OBLIQUITY)).addScaledInPlace(h, -sin(OBLIQUITY)).normalizeInPlace()
    }

    /** Carries the ecliptic's own frame - north +Y - into the world's. */
    val ECLIPTIC: Quat = quatFromTo(Vec3.unitY(), ECLIPTIC_NORTH)

    /** A spin axis tipped [tilt] radians from [north], toward [azimuth] radians round it. */
    fun axis(north: Vec3, tilt: Double, azimuth: Double): Vec3 {
        val a = (if (kotlin.math.abs(north.y) < 0.9) Vec3.unitY() else Vec3.unitX()).cross(north).normalizeInPlace()
        val b = north.cross(a).normalizeInPlace()
        val side = a.mulInPlace(cos(azimuth)).addScaledInPlace(b, sin(azimuth))
        return north.copy().mulInPlace(cos(tilt)).addScaledInPlace(side, sin(tilt)).normalizeInPlace()
    }

    /** The frame whose +Y is [axis]: a body's equator, for its moons and rings. */
    fun equator(axis: Vec3): Quat = quatFromTo(Vec3.unitY(), axis)

    /** Laplace's sphere of influence, m, for a body of [mu] a distance [a] from a parent of [parentMu]. */
    fun sphereOfInfluence(a: Double, mu: Double, parentMu: Double): Double = a * (mu / parentMu).pow(0.4)

    /** μ from surface gravity [g] at radius [r]. */
    fun mu(g: Double, r: Double): Double = g * r * r

    /** The planet Terra, in the ecliptic: its orbit, set so that the sun at time zero stands as close as it can to where the old fixed one did. */
    fun terraOrbit(solMu: Double): Orbit {
        // Terra sits opposite the sun: -OLD_SUN, as nearly as the ecliptic allows.
        val local = ECLIPTIC.inverseRotate(OLD_SUN.copy().mulInPlace(-1.0), Vec3())
        // In the ecliptic's own frame, prograde from +X toward -Z, as Orbit.circular goes.
        val anomaly = kotlin.math.atan2(-local.z, local.x)
        return Orbit.fromElements(AU, 0.0, 0.0, 0.0, 0.0, anomaly, solMu, frame = ECLIPTIC)
    }

    /** A planet's heliocentric orbit, in the ecliptic, from real elements. */
    fun planetOrbit(au: Double, e: Double, iDegrees: Double, nodeDegrees: Double, periDegrees: Double, anomalyDegrees: Double, solMu: Double): Orbit =
        Orbit.fromElements(
            au * AU, e, Math.toRadians(iDegrees), Math.toRadians(nodeDegrees), Math.toRadians(periDegrees - nodeDegrees),
            Math.toRadians(anomalyDegrees), solMu, frame = ECLIPTIC,
        )

    /** A moon's orbit in its planet's equatorial plane: [radii] of its planet's radius out, tipped [iDegrees] from the equator. */
    fun moonOrbit(planet: CelestialBody, radii: Double, iDegrees: Double, anomalyDegrees: Double, e: Double = 0.0, nodeDegrees: Double = 0.0): Orbit =
        Orbit.fromElements(
            radii * planet.radius, e, Math.toRadians(iDegrees), Math.toRadians(nodeDegrees), 0.0,
            Math.toRadians(anomalyDegrees), planet.gravitationalParameter, frame = equator(planet.spinAxis),
        )

    /** One orbit's time, s. */
    fun period(orbit: Orbit): Double = 2 * PI * sqrt(orbit.semiMajorAxis.pow(3) / orbit.mu)
}
