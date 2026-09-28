package com.rm.apogee.game

/**
 * The server's world time, as well as it can be told from here, running smoothly with the local
 * clock.
 *
 * Each snapshot says what time it was on the server when it was sent. It arrives some milliseconds
 * later, and how many changes from one to the next. Taking each one at its word ("it's now its time
 * plus how long ago it arrived") moves the present back and forth by that jitter on every snapshot,
 * and at orbital speed ten milliseconds is five metres. The craft being flown, drawn at that
 * jittering present, jumped about against a stage it had just let go of. I caught it on video.
 *
 * So the gap between server time and local time is followed instead of read. It goes up at once to
 * any snapshot that arrived quicker than expected, because the least delayed ones are the truest,
 * and comes down only slowly. That way a late one hardly moves it at all, while a server that's
 * really falling behind still gets followed within seconds.
 */
class ServerClock {
    /** Server time minus local time, in seconds, as followed. NaN before the first sample. */
    private var gap = Double.NaN

    /**
     * The gap that's actually used, slewed toward [gap] a little at a time. The estimate still
     * steps by milliseconds as snapshots arrive, and each step moved the present (and everything
     * drawn at it) by metres at orbital speed. A stage just let go of jumped about against the
     * craft on every snapshot, which I caught in a second video. Slewed, the present runs smoothly
     * and a correction gets spread over a second.
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
     * Forgets everything. For after a pause or warp, when the server's clock ran at a different
     * rate.
     */
    fun reset() {
        gap = Double.NaN
        shown = Double.NaN
    }

    companion object {
        /** The share of an arrival earlier than expected that gets taken at once. */
        const val RISE = 0.5

        /** The share of a later one. It takes a few seconds to follow a real drift. */
        const val FALL = 0.05

        /** Further out than this, in seconds, isn't jitter but a jump, so start again from it. */
        const val JUMP = 0.25

        /**
         * How fast the present can be moved to follow the estimate, in seconds per second. Two
         * percent spreads a ten-millisecond correction over half a second.
         */
        const val SLEW = 0.02
    }
}
