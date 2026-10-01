package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sqrt
import com.rm.apogee.core.fixed
import com.rm.apogee.core.math.Math

/** Position and velocity at one moment, relative to the attractor's centre. */
class StateVector(val position: Vec3, val velocity: Vec3) {
    override fun toString(): String = "StateVector(r=$position, v=$velocity)"
}

/**
 * A two-body trajectory about one attractor, stored as a state vector and propagated with universal
 * variables.
 *
 * Classical elements are undefined for circular or equatorial orbits, which is exactly a standard
 * parking orbit. Universal variables cover circles, ellipses, parabolas and hyperbolas with one
 * solver, so a burn from orbit to escape never switches branch. Classical elements are computed for
 * display only (see [apoapsis], [inclination]); nothing is propagated from them.
 *
 * +Y is the reference ("north") axis, as in the world.
 */
class Orbit(
    val position: Vec3,
    val velocity: Vec3,
    /** The attractor's GM, in m³/s². */
    val mu: Double,
    /** Universe time of this state, in seconds. */
    val epoch: Double = 0.0,
) {
    private val r: Double = position.length
    private val v: Double = velocity.length

    /** Specific orbital energy, in J/kg. Negative means bound. */
    val specificEnergy: Double = v * v / 2.0 - mu / r

    /** Specific angular momentum vector, constant along the trajectory. */
    val angularMomentum: Vec3 = position.cross(velocity)

    /** Semi-major axis in metres. Negative for hyperbolas, infinite on the parabolic edge. */
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

    /** Periapsis distance from the attractor's centre, in metres. */
    val periapsis: Double = run {
        val h = angularMomentum.length
        // p = h^2/mu; rp = p / (1 + e). Works for every conic, including parabolic where
        // semiMajorAxis is infinite.
        val p = h * h / mu
        p / (1.0 + eccentricity)
    }

    /** Apoapsis distance from the attractor's centre in metres. Infinite when escaping. */
    val apoapsis: Double =
        if (!isBound) Double.POSITIVE_INFINITY else semiMajorAxis * (1.0 + eccentricity)

    /** Orbital period in seconds. Infinite when escaping. */
    val period: Double =
        if (!isBound) Double.POSITIVE_INFINITY
        else 2.0 * PI * sqrt(semiMajorAxis * semiMajorAxis * semiMajorAxis / mu)

    /** Inclination against the reference (XZ) plane, in radians. */
    val inclination: Double = run {
        val h = angularMomentum.length
        if (h < 1e-9) 0.0 else acos((angularMomentum.y / h).coerceIn(-1.0, 1.0))
    }

    /**
     * Angle from periapsis to the current position, in radians. A circular orbit has no periapsis,
     * so it returns 0 instead of NaN.
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

    /** Eccentric anomaly, in radians. Elliptical orbits only. */
    private val eccentricAnomaly: Double = run {
        if (!isBound) 0.0
        else {
            val factor = sqrt((1.0 - eccentricity) / (1.0 + eccentricity))
            2.0 * kotlin.math.atan(factor * kotlin.math.tan(trueAnomaly / 2.0))
        }
    }

    /** Mean anomaly, in radians, wrapped to [0, 2pi). */
    val meanAnomaly: Double = run {
        if (!isBound) 0.0
        else {
            val m = eccentricAnomaly - eccentricity * kotlin.math.sin(eccentricAnomaly)
            val wrapped = m % (2.0 * PI)
            if (wrapped < 0.0) wrapped + 2.0 * PI else wrapped
        }
    }

    /**
     * Seconds to apoapsis, infinite when escaping. Used for circularisation timing and the map's
     * apoapsis marker.
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

    /**
     * Seconds to periapsis. When escaping, the one pass (the low point to brake at on arrival), or
     * infinite once it's behind.
     */
    val timeToPeriapsis: Double = run {
        if (!isBound) {
            if (eccentricity <= 1.0 + 1e-9 || (position dot velocity) >= 0.0) Double.POSITIVE_INFINITY
            else {
                // Hyperbolic anomaly from true anomaly, then the hyperbolic Kepler equation for the
                // time to zero.
                val cosNu = ((eccentricityVector dot position) / (eccentricity * r)).coerceIn(-1.0, 1.0)
                val coshF = (eccentricity + cosNu) / (1.0 + eccentricity * cosNu)
                val f = ln(coshF + sqrt((coshF * coshF - 1.0).coerceAtLeast(0.0)))
                val m = eccentricity * kotlin.math.sinh(f) - f
                m / sqrt(mu / (-semiMajorAxis * -semiMajorAxis * -semiMajorAxis))
            }
        } else {
            val meanMotion = 2.0 * PI / period
            var delta = (2.0 * PI - meanAnomaly) / meanMotion
            if (delta >= period) delta -= period
            delta
        }
    }

    /**
     * Moves the trajectory forward by [dt] seconds by Newton iteration on the universal anomaly.
     * Converges in a few steps for real trajectories; the cap stops bad input hanging the sim
     * thread.
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
        var converged = false
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

            // dF/dchi is exactly rNew, which makes this cheap.
            if (abs(rNew) < 1e-12) { converged = true; break }
            val delta = f / rNew
            chi -= delta
            if (abs(delta) < CONVERGENCE_TOLERANCE) { converged = true; break }
            iterations++
        }
        if (converged && chi.isFinite()) {
            // A huge wrong guess can also "converge", since its steps shrink as r grows, so check
            // the residual of the time equation itself.
            val p = chi * chi * alpha
            val residual = (rDotV / sqrtMu) * chi * chi * Stumpff.c2(p) +
                (1.0 - alpha * r) * chi * chi * chi * Stumpff.c3(p) + r * chi - sqrtMu * dt
            if (!(abs(residual) <= 1e-6 * sqrtMu * abs(dt) + 1e-3)) converged = false
        }
        if (!converged || !chi.isFinite()) {
            // Newton got lost from a poor start (on a fast hyperbola from far out the analytic
            // guess's terms nearly cancel), so solve again inside a bracket.
            chi = bracketed(dt, alpha, sqrtMu, rDotV)
            psi = chi * chi * alpha
            c2 = Stumpff.c2(psi)
            c3 = Stumpff.c3(psi)
            rNew = chi * chi * c2 + (rDotV / sqrtMu) * chi * (1.0 - psi * c3) + r * (1.0 - psi * c2)
        }

        // Lagrange coefficients turn the anomaly back into r and v.
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

    /** The state at absolute universe time [time]. */
    fun stateAt(time: Double): StateVector = propagate(time - epoch)

    /**
     * Samples [count] points along the trajectory for the map. A bound orbit covers one period so
     * it closes; an escape covers a window either side of now.
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

    /**
     * The universal anomaly for [dt] by bracketed Newton. The time equation rises with the anomaly,
     * so the root stays between a low and high point, and any step outside them bisects instead.
     */
    private fun bracketed(dt: Double, alpha: Double, sqrtMu: Double, rDotV: Double): Double {
        fun f(chi: Double, out: DoubleArray) {
            val psi = chi * chi * alpha
            val c2 = Stumpff.c2(psi)
            val c3 = Stumpff.c3(psi)
            out[0] = (rDotV / sqrtMu) * chi * chi * c2 + (1.0 - alpha * r) * chi * chi * chi * c3 + r * chi - sqrtMu * dt
            out[1] = chi * chi * c2 + (rDotV / sqrtMu) * chi * (1.0 - psi * c3) + r * (1.0 - psi * c2)
        }
        val s = sign(dt)
        val v = DoubleArray(2)
        var inside = 0.0
        var beyond = s * kotlin.math.max(1e-3, sqrtMu * abs(dt) / r)
        for (k in 0 until 400) {
            f(beyond, v)
            if (s * v[0] > 0.0) break
            inside = beyond
            beyond *= 2.0
        }
        var chi = 0.5 * (inside + beyond)
        for (k in 0 until 400) {
            f(chi, v)
            if (!v[0].isFinite()) { beyond = chi; chi = 0.5 * (inside + beyond); continue }
            if (s * v[0] > 0.0) beyond = chi else inside = chi
            var next = if (abs(v[1]) > 1e-12) chi - v[0] / v[1] else Double.NaN
            val lo = kotlin.math.min(inside, beyond)
            val hi = kotlin.math.max(inside, beyond)
            if (!(next > lo && next < hi)) next = 0.5 * (inside + beyond)
            if (abs(next - chi) < CONVERGENCE_TOLERANCE) return next
            chi = next
        }
        return chi
    }

    private fun initialGuess(dt: Double, alpha: Double, sqrtMu: Double, rDotV: Double): Double =
        when {
            // Elliptic: the linear estimate converges quickly.
            alpha > 1e-9 -> sqrtMu * dt * alpha

            // Hyperbolic: the analytic inversion, since the linear guess is poor on steep
            // hyperbolas.
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
        append("pe=${fixed(periapsis, 0)}m, ")
        append(if (isBound) "ap=${fixed(apoapsis, 0)}m, " else "escape, ")
        append("e=${fixed(eccentricity, 4)}, ")
        append("i=${fixed(Math.toDegrees(inclination), 2)}°")
        append(")")
    }

    companion object {
        private const val MAX_ITERATIONS = 64
        private const val CONVERGENCE_TOLERANCE = 1e-10
        private const val ESCAPE_SAMPLE_WINDOW_SECONDS = 6.0 * 3600.0

        /**
         * An orbit from elements: semi-major axis [a], eccentricity [e], inclination [i], ascending
         * node longitude [node], argument of periapsis [argument] and true anomaly [anomaly] at
         * [epoch]. Angles in radians, in a reference plane whose north is [frame] applied to +Y.
         * Prograde is anticlockwise seen from that north, as [circular]'s is from +Y.
         */
        fun fromElements(
            a: Double, e: Double, i: Double, node: Double, argument: Double, anomaly: Double,
            mu: Double, epoch: Double = 0.0, frame: com.rm.apogee.core.math.Quat = com.rm.apogee.core.math.Quat.identity(),
        ): Orbit {
            val p = a * (1 - e * e)
            val r = p / (1 + e * kotlin.math.cos(anomaly))
            // Perifocal: periapsis along x, motion toward y.
            val px = r * kotlin.math.cos(anomaly)
            val py = r * kotlin.math.sin(anomaly)
            val k = sqrt(mu / p)
            val vx = -k * kotlin.math.sin(anomaly)
            val vy = k * (e + kotlin.math.cos(anomaly))
            fun toFrame(x: Double, y: Double): Vec3 {
                // Rz(node) Rx(i) Rz(argument), in the usual z-up convention...
                val cO = kotlin.math.cos(node); val sO = kotlin.math.sin(node)
                val ci = kotlin.math.cos(i); val si = kotlin.math.sin(i)
                val cw = kotlin.math.cos(argument); val sw = kotlin.math.sin(argument)
                val x1 = cw * x - sw * y; val y1 = sw * x + cw * y
                val x2 = x1; val y2 = ci * y1; val z2 = si * y1
                val x3 = cO * x2 - sO * y2; val y3 = sO * x2 + cO * y2; val z3 = z2
                // ...then into world axes, north +Y: z goes to Y, y to -Z.
                return frame.rotate(Vec3(x3, z3, -y3), Vec3())
            }
            return Orbit(position = toFrame(px, py), velocity = toFrame(vx, vy), mu = mu, epoch = epoch)
        }

        /**
         * A circular orbit starting at +X, prograde about +Y, tilted by [inclination] radians about
         * X so it rises through the equator there. For spawning and tests.
         */
        fun circular(radiusFromCentre: Double, mu: Double, epoch: Double = 0.0, inclination: Double = 0.0): Orbit {
            val speed = sqrt(mu / radiusFromCentre)
            return Orbit(
                position = Vec3(radiusFromCentre, 0.0, 0.0),
                velocity = Vec3(0.0, speed * kotlin.math.sin(inclination), -speed * kotlin.math.cos(inclination)),
                mu = mu,
                epoch = epoch,
            )
        }
    }
}
