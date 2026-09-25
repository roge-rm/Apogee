package com.rm.apogee.audio

import kotlin.math.abs
import kotlin.math.exp

/**
 * How hard a crewed hull is still working against a change in the pressure
 * outside it, 0..1: what makes it tick and creak. It climbs quickly while
 * the air thins on the way up (or thickens on the way down), then dies away
 * over a minute or so once the pressure has levelled out - so in space,
 * once the craft has settled, it is almost silent.
 *
 * Driven by world time, so time warp does not turn a climb into a storm of ticks.
 */
class HullSettling {
    var level = 0.0
        private set
    private var lastPressure = Double.NaN
    private var lastTime = Double.NaN

    /** Feeds this frame's outside [pressure] (share of sea level) at world [time], s. */
    fun update(pressure: Double, time: Double): Double {
        val dt = time - lastTime
        if (lastPressure.isNaN() || dt.isNaN() || dt <= 0.0 || dt > MAX_STEP) {
            // First look, a pause, or a jump: nothing to judge a change by.
            if (dt.isNaN() || dt > MAX_STEP) { lastPressure = pressure; lastTime = time }
            return level
        }
        val target = (abs(pressure - lastPressure) / dt / FULL_RATE).coerceIn(0.0, 1.0)
        val tau = if (target > level) RISE else FALL
        level += (target - level) * (1.0 - exp(-dt / tau))
        lastPressure = pressure
        lastTime = time
        return level
    }

    /** A different craft, or a new flight: start settled. */
    fun reset() {
        level = 0.0
        lastPressure = Double.NaN
        lastTime = Double.NaN
    }

    companion object {
        /** Change of pressure, share of sea level per second, that has the hull working flat out. */
        const val FULL_RATE = 0.008
        /** How fast the working builds, and how slowly it dies away, s. */
        const val RISE = 2.0
        const val FALL = 45.0
        /** A frame gap longer than this, s, is a jump, not a change. */
        const val MAX_STEP = 5.0
    }
}
