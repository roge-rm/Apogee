package com.rm.apogee.core.part

/** A solid's burn worked out in small steps along its thrust curve, to check the game against. */
object CurveSteps {
    private const val G0 = 9.80665
    private const val DT = 1e-4

    class Burn(val seconds: Double, val impulseVacuum: Double, val burntKg: Double)

    /** [engine] burning in vacuum from [from] of its [fullKg] load, for [limit] seconds or till empty. */
    fun burn(engine: Engine, fullKg: Double, from: Double = 1.0, limit: Double = Double.POSITIVE_INFINITY): Burn {
        val rate = engine.thrustVacuum / (engine.ispVacuum * G0)
        var left = from * fullKg
        var t = 0.0
        var impulse = 0.0
        while (left > 0.0 && t < limit) {
            val step = minOf(DT, limit - t)
            // Midpoint, so the steps don't lag the curve.
            val half = left - rate * engine.thrustFactor(left / fullKg) * step / 2
            val factor = engine.thrustFactor(maxOf(half, 0.0) / fullKg)
            val want = rate * factor * step
            val used = minOf(want, left)
            impulse += engine.thrustVacuum * factor * step * used / want
            left -= used
            t += step * used / want
        }
        return Burn(t, impulse, from * fullKg - left)
    }
}
