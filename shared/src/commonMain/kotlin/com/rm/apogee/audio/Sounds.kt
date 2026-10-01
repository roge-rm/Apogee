package com.rm.apogee.audio

/** The interface's small sounds: short, quiet, and off with the interface sound setting. */
object Sounds {
    private val params = FloatArray(SharedParams.COUNT)
    private var seed = 0

    /** A press. */
    fun click(soft: Boolean = false) {
        params[0] = if (soft) 1f else 0f
        AudioEngine.event(Recipes.CLICK, 0, ++seed, 0f, params)
    }

    /** A warning coming on. */
    fun caution() {
        params[0] = 0f
        AudioEngine.event(Recipes.CAUTION, 0, ++seed, 0f, params)
    }
}
