package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sqrt

/** Position and velocity at an instant, relative to the attractor's centre. */
class StateVector(val position: Vec3, val velocity: Vec3) {
    override fun toString(): String = "StateVector(r=$position, v=$velocity)"
}

/**
 * A two-body trajectory about a single attractor.
 *
 * Represented by a state vector rather than classical elements, and propagated
 * with the universal-variable formulation. That choice is the load-bearing one
 * here.
 *
 * Classical elements are singular exactly where gameplay lives: argument of
 * periapsis is undefined for a circular orbit, longitude of the ascending node
 * is undefined for an equatorial one, and *both* describe a craft in a standard
 * 100 km parking orbit. Universal variables have no such special cases - one
 * solver covers circles, ellipses, parabolas and hyperbolas continuously, so a
 * craft burning from orbit to escape never crosses a branch.
 *
 * Classical elements are still computed, but only for *display*
 * (see [apoapsis], [inclination]); nothing propagates from them.
 *
 * Convention: +Y is the reference ("north") axis, matching the world.
 */
class Orbit(
    val position: Vec3,
    val velocity: Vec3,
    /** Attractor's GM, m³/s². */
    val mu: Double,
    /** Universe time this state was sampled at, seconds. */
    val epoch: Double = 0.0,
) {
    private val r: Double = position.length
    private val v: Double = velocity.length

    /** Specific orbital energy, J/kg. Negative means bound. */
    val specificEnergy: Double = v * v / 2.0 - mu / r

    /** Specific angular momentum vector. Constant along the trajectory. */
    val angularMomentum: Vec3 = position.cross(velocity)

    /**
     * Semi-major axis, metres. Negative for hyperbolic trajectories, and
     * infinite for the parabolic knife-edge.
     */
    val semiMajorAxis: Double =
        if (abs(specificEnergy) < 1e-12) Double.POSITIVE_INFINITY else -mu / (2.0 * specificEnergy)

    /** Eccentricity vector, pointing at periapsis. */
    val eccentricityVector: Vec3 = run {
        val scaleR = (v * v - mu / r) / mu
        val scaleV = (position dot velocity) / mu
        Vec3(
            position.x * scaleR - velocity.x * scaleV,
            position.y * scaleR - velocity.y * scaleV,
            position.z * scaleR - velocity.z * scaleV,
        )
    }

    val eccentricity: Double = eccentricityVector.length

    val isBound: Boolean get() = specificEnergy < 0.0 && eccentricity < 1.0

    /** Periapsis distance from the attractor's centre, metres. */
    val periapsis: Double = run {
        val h = angularMomentum.length
        // p = h^2/mu; rp = p / (1 + e). Works for every conic, including the
        // parabolic case where semiMajorAxis has blown up to infinity.
        val p = h * h / mu
        p / (1.0 + eccentricity)
    }

    /**
     * Apoapsis distance from the attractor's centre, metres.
     * Infinite on an escape trajectory.
     */
    val apoapsis: Double =
        if (!isBound) Double.POSITIVE_INFINITY else semiMajorAxis * (1.0 + eccentricity)

    /** Orbital period, seconds. Infinite on an escape trajectory. */
    val period: Double =
        if (!isBound) Double.POSITIVE_INFINITY
        else 2.0 * PI * sqrt(semiMajorAxis * semiMajorAxis * semiMajorAxis / mu)

    /** Inclination against the reference (XZ) plane, radians. */
    val inclination: Double = run {
        val h = angularMomentum.length
        if (h < 1e-9) 0.0 else acos((angularMomentum.y / h).coerceIn(-1.0, 1.0))
    }

    /**
     * Angle from periapsis to the craft's current position, radians.
     *
     * Undefined on a perfectly circular orbit - there is no periapsis to
     * measure from - so that case returns 0 rather than a NaN that would
     * propagate into every time estimate downstream.
     */
    val trueAnomaly: Double = run {
        if (eccentricity < 1e-9) {
            0.0
        } else {
            val cosNu = (eccentricityVector dot position) / (eccentricity * r)
            val nu = acos(cosNu.coerceIn(-1.0, 1.0))
            // Past periapsis and climbing, or falling back toward it?
            if ((position dot velocity) < 0.0) 2.0 * PI - nu else nu
        }
    }

    /** Eccentric anomaly, radians. Elliptical orbits only. */
    private val eccentricAnomaly: Double = run {
        if (!isBound) 0.0
        else {
            val factor = sqrt((1.0 - eccentricity) / (1.0 + eccentricity))
            2.0 * kotlin.math.atan(factor * kotlin.math.tan(trueAnomaly / 2.0))
        }
    }

    /** Mean anomaly, radians, wrapped to [0, 2pi). */
    val meanAnomaly: Double = run {
        if (!isBound) 0.0
        else {
            val m = eccentricAnomaly - eccentricity * kotlin.math.sin(eccentricAnomaly)
            val wrapped = m % (2.0 * PI)
            if (wrapped < 0.0) wrapped + 2.0 * PI else wrapped
        }
    }

    /**
     * Seconds until the craft reaches apoapsis.
     *
     * Infinite on an escape trajectory, which never gets there. This is what an
     * ascent autopilot waits on before its circularisation burn, and what the
     * map view puts next to the apoapsis marker.
     */
    val timeToApoapsis: Double = run {
        if (!isBound) Double.POSITIVE_INFINITY
        else {
            val meanMotion = 2.0 * PI / period
            // Apoapsis is at mean anomaly pi.
            var delta = (PI - meanAnomaly) / meanMotion
            if (delta < 0.0) delta += period
            delta
        }
    }

    /** Seconds until the craft reaches periapsis. */
    val timeToPeriapsis: Double = run {
        if (!isBound) Double.POSITIVE_INFINITY
        else {
            val meanMotion = 2.0 * PI / period
            var delta = (2.0 * PI - meanAnomaly) / meanMotion
            if (delta >= period) delta -= period
            delta
        }
    }

    /**
     * Advances the trajectory by [dt] seconds.
     *
     * Newton iteration on the universal anomaly. Converges in a handful of
     * iterations for anything a craft will actually be on; the iteration cap
     * exists so a pathological input cannot hang the simulation thread.
     */
    fun propagate(dt: Double): StateVector {
        if (dt == 0.0 || !dt.isFinite()) return StateVector(position.copy(), velocity.copy())

        val sqrtMu = sqrt(mu)
        // alpha = 1/a. Positive elliptic, zero parabolic, negative hyperbolic.
        val alpha = 2.0 / r - v * v / mu
        val rDotV = position dot velocity

        var chi = initialGuess(dt, alpha, sqrtMu, rDotV)

        var psi = 0.0
        var c2 = 0.5
        var c3 = 1.0 / 6.0
        var rNew = r

        var iterations = 0
        while (iterations < MAX_ITERATIONS) {
            psi = chi * chi * alpha
            c2 = Stumpff.c2(psi)
            c3 = Stumpff.c3(psi)

            rNew = chi * chi * c2 +
                (rDotV / sqrtMu) * chi * (1.0 - psi * c3) +
                r * (1.0 - psi * c2)

            val f = (rDotV / sqrtMu) * chi * chi * c2 +
                (1.0 - alpha * r) * chi * chi * chi * c3 +
                r * chi -
                sqrtMu * dt

            // dF/dchi is exactly rNew, which is what makes this cheap.
            if (abs(rNew) < 1e-12) break
            val delta = f / rNew
            chi -= delta
            if (abs(delta) < CONVERGENCE_TOLERANCE) break
            iterations++
        }

        // Lagrange coefficients turn the solved anomaly back into r and v.
        val fCoefficient = 1.0 - (chi * chi / r) * c2
        val gCoefficient = dt - (chi * chi * chi / sqrtMu) * c3
        val fDot = (sqrtMu / (rNew * r)) * chi * (psi * c3 - 1.0)
        val gDot = 1.0 - (chi * chi / rNew) * c2

        val newPosition = Vec3(
            position.x * fCoefficient + velocity.x * gCoefficient,
            position.y * fCoefficient + velocity.y * gCoefficient,
            position.z * fCoefficient + velocity.z * gCoefficient,
        )
        val newVelocity = Vec3(
            position.x * fDot + velocity.x * gDot,
            position.y * fDot + velocity.y * gDot,
            position.z * fDot + velocity.z * gDot,
        )
        return StateVector(newPosition, newVelocity)
    }

    /** State at absolute universe time [time]. */
    fun stateAt(time: Double): StateVector = propagate(time - epoch)

    /**
     * Samples [count] points around the trajectory, for drawing the conic in
     * map view.
     *
     * A bound orbit is sampled over exactly one period so the path closes; an
     * escape trajectory is sampled over a window either side of now, since
     * there is no period to close.
     */
    fun sample(count: Int = 128): List<Vec3> {
        require(count >= 2) { "need at least two samples" }
        val span = if (isBound) period else ESCAPE_SAMPLE_WINDOW_SECONDS
        val start = if (isBound) 0.0 else -span / 2.0
        return (0 until count).map { i ->
            val t = start + span * (i.toDouble() / (count - 1).toDouble())
            propagate(t).position
        }
    }

    private fun initialGuess(dt: Double, alpha: Double, sqrtMu: Double, rDotV: Double): Double =
        when {
            // Elliptic: the linear estimate is close enough to converge fast.
            alpha > 1e-9 -> sqrtMu * dt * alpha

            // Hyperbolic: the analytic inversion, which the linear guess is far
            // too poor for once the trajectory is steeply hyperbolic.
            alpha < -1e-9 -> {
                val a = 1.0 / alpha
                val numerator = -2.0 * mu * alpha * dt
                val denominator = rDotV + sign(dt) * sqrt(-mu * a) * (1.0 - r * alpha)
                sign(dt) * sqrt(-a) * ln(numerator / denominator)
            }

            // Parabolic: Barker's equation.
            else -> {
                val h = angularMomentum.length
                val p = h * h / mu
                val s = 0.5 * atanCot(3.0 * sqrt(mu / (p * p * p)) * dt)
                val w = kotlin.math.atan(kotlin.math.cbrt(kotlin.math.tan(s)))
                sqrt(p) * 2.0 / kotlin.math.tan(2.0 * w)
            }
        }

    private fun atanCot(x: Double): Double = kotlin.math.atan(1.0 / x)

    override fun toString(): String = buildString {
        append("Orbit(")
        append("pe=${"%.0f".format(periapsis)}m, ")
        append(if (isBound) "ap=${"%.0f".format(apoapsis)}m, " else "escape, ")
        append("e=${"%.4f".format(eccentricity)}, ")
        append("i=${"%.2f".format(Math.toDegrees(inclination))}°")
        append(")")
    }

    companion object {
        private const val MAX_ITERATIONS = 64
        private const val CONVERGENCE_TOLERANCE = 1e-10
        private const val ESCAPE_SAMPLE_WINDOW_SECONDS = 6.0 * 3600.0

        /**
         * A circular orbit at [radiusFromCentre], in the XZ plane.
         * Convenience for spawning and for tests.
         */
        fun circular(radiusFromCentre: Double, mu: Double, epoch: Double = 0.0): Orbit {
            val speed = sqrt(mu / radiusFromCentre)
            return Orbit(
                position = Vec3(radiusFromCentre, 0.0, 0.0),
                velocity = Vec3(0.0, 0.0, -speed),
                mu = mu,
                epoch = epoch,
            )
        }
    }
}
