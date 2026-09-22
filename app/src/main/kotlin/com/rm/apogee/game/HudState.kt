package com.rm.apogee.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Everything the in-world overlay displays, as one observable holder.
 *
 * Deliberately a plain class rather than state hoisted up through composables:
 * the writers are the frame-clock loop, the game session and touch handlers,
 * all of which are ordinary non-composable Kotlin that needs a plain reference
 * to write into. Compose observes; the game does not have to become Compose to
 * talk to it.
 */
class HudState {
    // --- diagnostics --------------------------------------------------------
    var frameTimeMillis: Float by mutableFloatStateOf(0f)
    var frameBuildMillis: Float by mutableFloatStateOf(0f)
    var simTick: Long by mutableLongStateOf(0L)
    var drawnItems: Int by mutableIntStateOf(0)

    // --- flight -------------------------------------------------------------
    var telemetry: FlightTelemetry by mutableStateOf(FlightTelemetry.EMPTY)
    var connecting: Boolean by mutableStateOf(true)
    var connectionError: String? by mutableStateOf(null)

    /** Mirrors the control the player is holding, so the UI can show it. */
    var throttle: Float by mutableFloatStateOf(0f)
    var sasEnabled: Boolean by mutableStateOf(false)
    var mapMode: Boolean by mutableStateOf(false)

    /**
     * Whether another craft is close enough and still enough to weld to.
     *
     * Decided on the client from the craft it already knows about, so the
     * button appears exactly when pressing it would do something. The server
     * checks the same conditions again before acting - this is for the UI, not
     * for authority.
     */
    var canJoin: Boolean by mutableStateOf(false)

    /** How many craft the player owns, so the switch control can hide itself. */
    var ownedCraft: Int by mutableIntStateOf(0)

    /** Clears transient state when leaving the world, so a new flight starts clean. */
    fun reset() {
        frameTimeMillis = 0f
        frameBuildMillis = 0f
        simTick = 0L
        drawnItems = 0
        telemetry = FlightTelemetry.EMPTY
        connecting = true
        connectionError = null
        throttle = 0f
        sasEnabled = false
        mapMode = false
        canJoin = false
        ownedCraft = 0
    }
}
