package com.rm.apogee.render

/**
 * How much of the screen's resolution the 3D view is drawn at. The display stretches it to fill the
 * screen for nothing, and the HUD over it is drawn at full resolution whatever this is.
 *
 * A mid-range phone judged high tier drew the Flat Top at sea at 26 frames a second at full
 * resolution, 35 at 80%, 41 at 67% and 51 at half: the time went on filling pixels, spread across
 * every pass, not on any one thing drawn.
 */
enum class Resolution(val label: String, val scale: Double) {
    AUTO("Automatic", 1.0),
    FULL("Full", 1.0),
    EIGHTY("80%", 0.8),
    TWO_THIRDS("67%", 0.67),
    HALF("50%", 0.5),
}

/**
 * The resolution chosen for itself: full to start with, a step less whenever the frame rate falls
 * under [DOWN_FPS], and a step more when there's room to spare, which it only tries again a while
 * after a step up didn't hold.
 */
class AutoResolution {
    private var step = 0
    private var windowStart = 0L
    private var windowFrames = 0L
    private var settleUntil = 0L
    private var upAgainAt = 0L
    private var steppedUpAt = 0L

    /** The scale to draw at now. */
    val scale: Double get() = STEPS[step]

    /** Starts over at full resolution, as for a new flight. */
    fun reset(now: Long) {
        step = 0; windowStart = 0L; settleUntil = now + SETTLE_NANOS; upAgainAt = 0L; steppedUpAt = 0L
    }

    /**
     * Takes the frames drawn by [now], in nanoseconds, and moves a step if the last few seconds
     * call for it. Returns whether the scale changed.
     */
    fun update(now: Long, framesDrawn: Long): Boolean {
        if (now < settleUntil) { windowStart = 0L; return false }
        if (windowStart == 0L) { windowStart = now; windowFrames = framesDrawn; return false }
        if (now - windowStart < WINDOW_NANOS) return false
        val fps = (framesDrawn - windowFrames) * 1e9 / (now - windowStart)
        windowStart = now; windowFrames = framesDrawn
        val was = step
        if (fps < DOWN_FPS && step < STEPS.lastIndex) {
            // A step up that didn't hold is left a good while before it's tried again.
            if (steppedUpAt != 0L && now - steppedUpAt < HELD_NANOS) upAgainAt = now + RETRY_NANOS
            step++
            steppedUpAt = 0L
        } else if (fps > UP_FPS && step > 0 && now >= upAgainAt) {
            step--
            steppedUpAt = now
        }
        if (step == was) return false
        settleUntil = now + SETTLE_NANOS
        return true
    }

    companion object {
        val STEPS = doubleArrayOf(1.0, 0.85, 0.75, 0.67, 0.58, 0.5)
        const val DOWN_FPS = 40.0
        const val UP_FPS = 56.0
        const val WINDOW_NANOS = 3_000_000_000L
        const val SETTLE_NANOS = 2_000_000_000L
        const val HELD_NANOS = 10_000_000_000L
        const val RETRY_NANOS = 60_000_000_000L
    }
}
