package com.rm.apogee.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * Everything the in-world overlay displays, as one observable holder.
 *
 * Deliberately a plain class rather than state hoisted up through composables:
 * the writers are the simulation loop, the frame-clock loop and touch handlers,
 * all of which are ordinary non-composable Kotlin that needs a plain reference
 * to write into. Compose observes; the game does not have to become Compose to
 * talk to it.
 */
class HudState {
    var frameTimeMillis: Float by mutableFloatStateOf(0f)
    var simStepMillis: Float by mutableFloatStateOf(0f)
    var simTick: Long by mutableLongStateOf(0L)
    var drawnItems: Int by mutableIntStateOf(0)

    /** Clears transient state when leaving the world, so a new flight starts clean. */
    fun reset() {
        frameTimeMillis = 0f
        simStepMillis = 0f
        simTick = 0L
        drawnItems = 0
    }
}
