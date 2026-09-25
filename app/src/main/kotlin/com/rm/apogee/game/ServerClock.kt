package com.rm.apogee.game

/**
 * The server's world time, as best it can be told from here, running
 * smoothly with the local clock.
 *
 * Each snapshot says what time it was on the server when it was sent; it
 * arrives some milliseconds later, and how many varies from one to the next.
 * Taking each one at its word - "it is now its time plus how long ago it
 * arrived" - moves the present back and forth by that jitter on every
 * snapshot, and at orbital speed ten milliseconds is five metres: the craft
 * being flown, drawn at that jittering present, jumped about against a stage
 * it had just let go of (Dan's video).
 *
 * So the gap between server time and local time is followed rather than
 * read: up at once to any snapshot that arrived quicker than thought - the
 * least delayed are the truest - and down only slowly, so a late one moves
 * it hardly at all while a server genuinely falling behind is still
 * followed within seconds.
 */
class ServerClock {
    /** Server time minus local time, s, as followed; NaN before the first sample. */
    private var gap = Double.NaN

    /**
     * The gap actually used, slewed toward [gap] a little at a time. The
     * estimate still steps by milliseconds as snapshots arrive, and each
     * step moved the present - and everything drawn at it - by metres at
     * orbital speed: a stage just let go of jumped about against the craft
     * on every snapshot (Dan's second video). Slewed, the present runs
     * smoothly and a correction is spread over a second.
     */
    private var shown = Double.NaN
    private var shownAt = Double.NaN

    /** A snapshot of server [serverTime] arrived at local [localSeconds]. */
    fun sample(serverTime: Double, localSeconds: Double) {
        val seen = serverTime - localSeconds
        gap = when {
            gap.isNaN() || kotlin.math.abs(seen - gap) > JUMP -> seen
            seen > gap -> gap + (seen - gap) * RISE
            else -> gap + (seen - gap) * FALL
        }
    }

    /** The server's time now, at local [localSeconds]; null before any sample. */
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

    /** Forgets: after a pause or warp, when the server's clock ran at another rate. */
    fun reset() {
        gap = Double.NaN
        shown = Double.NaN
    }

    companion object {
        /** Share of an earlier-than-thought arrival taken at once. */
        const val RISE = 0.5

        /** Share of a later-than-thought one: a few seconds to follow a real drift. */
        const val FALL = 0.05

        /** Further out than this, s, is not jitter but a jump: start again from it. */
        const val JUMP = 0.25

        /**
         * How fast the present may be moved to follow the estimate, s per s:
         * two percent - a ten-millisecond correction spread over half a second.
         */
        const val SLEW = 0.02
    }
}
