package com.rm.apogee.game.tutorial

/**
 * Where you've got to in [tutorial]. Each look at the craft checks only the step you're on. When it
 * holds (for its [Step.holdFor]) the step gets a tick, and [TICK_SECONDS] later the next one comes.
 */
class TutorialRun(val tutorial: Tutorial) {
    /** The step you're on, or the step count once it's all done. */
    var index = 0
        private set

    /** The first look in flight, and the look when this step began, for checks against them. */
    var first: TutorialView? = null
        private set
    var stepStart: TutorialView? = null
        private set

    private var heldSince = Double.NaN
    private var tickedAt = Double.NaN

    val finished: Boolean get() = index >= tutorial.steps.size

    /** What to show now. */
    val line: TutorialLine
        get() {
            val count = tutorial.steps.size
            if (finished) return TutorialLine(tutorial.title, count, count, ticked = true, finished = true)
            return TutorialLine(tutorial.steps[index].text, index + 1, count, ticked = !tickedAt.isNaN(), finished = false)
        }

    /** Takes a look at [view] at [now] seconds. Returns whether [line] changed. */
    fun tick(view: TutorialView, now: Double): Boolean {
        if (finished) return false
        if (view.builder == null && first == null) first = view
        // A new step, or out of the Vehicle Assembly into flight, starts its own measure.
        val began = stepStart
        if (began == null || (began.builder == null) != (view.builder == null)) stepStart = view
        if (!tickedAt.isNaN()) {
            if (now - tickedAt < TICK_SECONDS) return false
            index++
            tickedAt = Double.NaN
            heldSince = Double.NaN
            stepStart = view
            return true
        }
        val step = tutorial.steps[index]
        if (!step.check(view, this)) {
            heldSince = Double.NaN
            return false
        }
        if (heldSince.isNaN()) heldSince = now
        if (now - heldSince < step.holdFor) return false
        tickedAt = now
        return true
    }

    companion object {
        /** How long a tick shows before the next step. */
        const val TICK_SECONDS = 1.2
    }
}

/** One line of a tutorial on screen: the step's [text], which of how many, and whether it's done. */
data class TutorialLine(val text: String, val number: Int, val count: Int, val ticked: Boolean, val finished: Boolean)
