package com.rm.apogee.input

/**
 * A controller working the screens like a keyboard, where nothing turns it into key presses
 * itself (a browser, a desktop): the D-pad or left stick moves between things, A presses the one
 * it's on and B goes back. [send] types a key by its browser name ("Tab", "ArrowLeft", "Enter",
 * "Escape"), with Shift or not, and says whether anything used it.
 */
class MenuPad(private val send: (key: String, shift: Boolean) -> Boolean) {
    // The way the D-pad or stick is held, and when it next moves again on its own.
    private var heading: PadButton? = null
    private var nextStep = 0.0
    private var wasA = false
    private var wasB = false

    /** Works the screens from [pad], at [timeMillis] on any steady clock. */
    fun update(pad: PadState, timeMillis: Double) {
        val way = when {
            pad[PadButton.UP] > 0.5f || pad.leftY < -STICK_STEP -> PadButton.UP
            pad[PadButton.DOWN] > 0.5f || pad.leftY > STICK_STEP -> PadButton.DOWN
            pad[PadButton.LEFT] > 0.5f || pad.leftX < -STICK_STEP -> PadButton.LEFT
            pad[PadButton.RIGHT] > 0.5f || pad.leftX > STICK_STEP -> PadButton.RIGHT
            else -> null
        }
        if (way == null) {
            heading = null
        } else if (way != heading) {
            heading = way
            nextStep = timeMillis + FIRST_REPEAT_MS
            step(way)
        } else if (timeMillis >= nextStep) {
            nextStep = timeMillis + REPEAT_MS
            step(way)
        }
        val a = pad[PadButton.A] > 0.5f
        val b = pad[PadButton.B] > 0.5f
        if (a && !wasA) send("Enter", false)
        if (b && !wasB) send("Escape", false)
        wasA = a
        wasB = b
    }

    /** In flight, where the controller flies instead: only keeps track of A and B. */
    fun idle(pad: PadState) {
        heading = null
        wasA = pad[PadButton.A] > 0.5f
        wasB = pad[PadButton.B] > 0.5f
    }

    /**
     * One step [way]. Up and down are Shift-Tab and Tab, since arrows would scroll the page. Left
     * and right go as arrows first (for a slider), then Tab or Shift-Tab if unused.
     */
    private fun step(way: PadButton) {
        when (way) {
            PadButton.UP -> send("Tab", true)
            PadButton.DOWN -> send("Tab", false)
            PadButton.LEFT -> if (!send("ArrowLeft", false)) send("Tab", true)
            else -> if (!send("ArrowRight", false)) send("Tab", false)
        }
    }

    private companion object {
        /** How far the stick goes to move, and how soon a held one moves again, in ms. */
        const val STICK_STEP = 0.6f
        const val FIRST_REPEAT_MS = 400.0
        const val REPEAT_MS = 150.0
    }
}
