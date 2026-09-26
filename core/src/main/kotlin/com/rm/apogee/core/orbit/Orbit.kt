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

    /**
     * Seconds until the craft reaches periapsis. On an escape trajectory,
     * the one pass - arriving at a moon, the low point to brake at - or
     * infinite once it is behind it.
     */
    val timeToPeriapsis: Double = run {
        if (!isBound) {
            if (eccentricity <= 1.0 + 1e-9 || (position dot velocity) >= 0.0) Double.POSITIVE_INFINITY
            else {
                // Hyperbolic anomaly from the true anomaly, then Kepler's
                // equation for the hyperbola: how long until it is zero.
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

            // dF/dchi is exactly rNew, which is what makes this cheap.
            if (abs(rNew) < 1e-12) { converged = true; break }
            val delta = f / rNew
            chi -= delta
            if (abs(delta) < CONVERGENCE_TOLERANCE) { converged = true; break }
            iterations++
        }
        if (converged && chi.isFinite()) {
            // A huge wrong start "converges" too: its steps shrink as r grows
            // with it. Checked by the time equation itself, off by no more
            // than a hair of the time asked for.
            val p = chi * chi * alpha
            val residual = (rDotV / sqrtMu) * chi * chi * Stumpff.c2(p) +
                (1.0 - alpha * r) * chi * chi * chi * Stumpff.c3(p) + r * chi - sqrtMu * dt
            if (!(abs(residual) <= 1e-6 * sqrtMu * abs(dt) + 1e-3)) converged = false
        }
        if (!converged || !chi.isFinite()) {
            // Newton lost from a poor start: coming in from far out on a
            // fast hyperbola the analytic guess's two terms all but cancel.
            // Solved again, slower, kept inside a bracket round the root.
            chi = bracketed(dt, alpha, sqrtMu, rDotV)
            psi = chi * chi * alpha
            c2 = Stumpff.c2(psi)
            c3 = Stumpff.c3(psi)
            rNew = chi * chi * c2 + (rDotV / sqrtMu) * chi * (1.0 - psi * c3) + r * (1.0 - psi * c2)
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

    /**
     * The universal anomaly for [dt] by Newton kept inside a bracket: the
     * time equation rises with the anomaly, so the root is always between
     * a point below it and one above, and a step that leaves them halves
     * them instead.
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
        /**
         * A circular orbit starting at +X, prograde about +Y, tilted by
         * [inclination] radians about the X axis - so it rises through the
         * equator there.
         */
        /**
         * An orbit from its elements: semi-major axis [a], eccentricity [e],
         * inclination [i], longitude of the ascending node [node], argument
         * of periapsis [argument] and true anomaly [anomaly] at [epoch] - the
         * angles in radians, measured in a reference plane whose north is
         * [frame] applied to +Y. Prograde is anticlockwise seen from that
         * north, as [circular]'s is from +Y.
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
                // ...then into this world's axes, where north is +Y: z up to Y, y to -Z.
                return frame.rotate(Vec3(x3, z3, -y3), Vec3())
            }
            return Orbit(position = toFrame(px, py), velocity = toFrame(vx, vy), mu = mu, epoch = epoch)
        }

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
