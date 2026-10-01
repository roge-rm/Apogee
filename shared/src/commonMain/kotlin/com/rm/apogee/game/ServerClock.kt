package com.rm.apogee.game

/**
 * The server's world time as well as it can be told from here, running smoothly with the local
 * clock.
 *
 * Each snapshot carries its server send time, but arrives after a varying delay. Taking each at its
 * word makes the present jitter, and at orbital speed ten milliseconds is five metres, so a craft
 * jumps against a stage it just dropped. Instead the server-minus-local gap is followed: it rises
 * at once to any snapshot that arrived quicker than expected (the least delayed are truest) and
 * falls only slowly. A late one hardly moves it, but real drift is followed within seconds.
 */
class ServerClock {
    /** Server time minus local time, in seconds, as followed. NaN before the first sample. */
    private var gap = Double.NaN

    /**
     * The gap actually used, slewed toward [gap] a little at a time. The estimate still steps by
     * milliseconds per snapshot, which is metres at orbital speed; slewing spreads a correction
     * over a second so the present runs smoothly.
     */
    private var shown = Double.NaN
    private var shownAt = Double.NaN

    /** A snapshot of server time [serverTime] arrived at local time [localSeconds]. */
    fun sample(serverTime: Double, localSeconds: Double) {
        val seen = serverTime - localSeconds
        gap = when {
            gap.isNaN() || kotlin.math.abs(seen - gap) > JUMP -> seen
            seen > gap -> gap + (seen - gap) * RISE
            else -> gap + (seen - gap) * FALL
        }
    }

    /** The server's time now, at local time [localSeconds]. Null before any sample. */
    fun now(localSeconds: Double): Double? {
        if (gap.isNaN()) return null
        if (shown.isNaN() || kotlin.math.abs(gap - shown) > JUMP) {
            shown = gap
        } else {
            val dt = (localSeconds - shownAt).coerceIn(0.0, 0.25)
            val most = SLEW * dt
            shown += (gap - shown).coerceIn(-most, most)
        }
        shownAt = localSeconds
        return localSeconds + shown
    }

    /**
     * Forgets everything. For after a pause or warp, when the server's clock ran at another rate.
     */
    fun reset() {
        gap = Double.NaN
        shown = Double.NaN
    }

    companion object {
        /** The share of an arrival earlier than expected that gets taken at once. */
        const val RISE = 0.5

        /** The share of a later one. Takes a few seconds to follow a real drift. */
        const val FALL = 0.05

        /** Further out than this, in seconds, is a jump, not jitter, so start again from it. */
        const val JUMP = 0.25

        /**
         * How fast the present can move to follow the estimate, in seconds per second. Two percent
         * spreads a ten millisecond correction over half a second.
         */
        const val SLEW = 0.02
    }
}
