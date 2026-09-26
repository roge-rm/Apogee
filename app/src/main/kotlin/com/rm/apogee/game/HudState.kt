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

    /** Whether the part-condition list under the caution chips is open. */
    var damageExpanded: Boolean by mutableStateOf(false)
    var connecting: Boolean by mutableStateOf(true)
    var connectionError: String? by mutableStateOf(null)

    /**
     * Whether there is ground under the craft yet. Separate from [connecting]
     * because the two wait on different things and say different things to
     * the player: one is the server, the other is this device.
     */
    var surfaceReady: Boolean by mutableStateOf(false)

    /** Mirrors the control the player is holding, so the UI can show it. */
    var throttle: Float by mutableFloatStateOf(0f)
    var sasEnabled: Boolean by mutableStateOf(false)
    var brakes: Boolean by mutableStateOf(false)
    var reverse: Boolean by mutableStateOf(false)

    /** Whether the craft has wheels, so the brake control can hide itself. */
    var hasWheels: Boolean by mutableStateOf(false)

    /**
     * Thrusters: whether the craft has any (the RCS control hides itself
     * otherwise), whether they are armed, whether the stick slides the craft
     * rather than turning it, and the monopropellant left (0..1, or null).
     */
    var hasRcs: Boolean by mutableStateOf(false)
    var rcsArmed: Boolean by mutableStateOf(false)
    var rcsSlide: Boolean by mutableStateOf(false)
    var rcsLeft: Float? by mutableStateOf(null)

    /** Docking: lining up, what the flown craft is joined by, and who it is shared with. */
    var dock: com.rm.apogee.game.GameSession.DockReadout? by mutableStateOf(null)
    var joints: List<com.rm.apogee.game.GameSession.Joint> by mutableStateOf(emptyList())
    /** Shared with [sharedWith] (their name): who flies - "me", "them" or "both" - or null when not shared. */
    var sharedWith: String? by mutableStateOf(null)
    var sharedPilot: String by mutableStateOf("both")
    /** Whether the who-flies card is open. */
    var sharedOpen: Boolean by mutableStateOf(false)
    var mapMode: Boolean by mutableStateOf(false)

    /** The next planned burn, and coming down; see [com.rm.apogee.ui.components.BurnPanel]. */
    var burn: com.rm.apogee.game.GameSession.BurnReadout? by mutableStateOf(null)
    var landing: com.rm.apogee.game.GameSession.LandingReadout? by mutableStateOf(null)
    var autopilotNote: String by mutableStateOf("")

    /**
     * Whether another craft is close enough and still enough to weld to.
     *
     * Decided on the client from the craft it already knows about, so the
     * button appears exactly when pressing it would do something. The server
     * checks the same conditions again before acting - this is for the UI, not
     * for authority.
     */
    var canJoin: Boolean by mutableStateOf(false)

    /** What the flown craft can do with a base: found, let go, refuel. */
    var baseService: com.rm.apogee.core.world.ServerMessage.Service? by mutableStateOf(null)

    /** The founded base nearby, or the one flown, for its card. */
    var nearBase: com.rm.apogee.core.world.ServerMessage.BaseStatus? by mutableStateOf(null)

    /** The flown craft's power and link home, or null before the server has said. */
    var power: PowerReadout? by mutableStateOf(null)

    /** Whether the flown craft has sun wings or dishes to fold out, so the DEPLOY control can hide itself. */
    var hasFoldouts: Boolean by mutableStateOf(false)

    /**
     * A craft's power and link home, for the HUD: charge and what it holds,
     * the net rate a second, whether it has power, whether it needs a signal
     * (a probe) and which it has, through how many relays, whether it can be
     * flown now, and whether its fold-outs are told out.
     */
    data class PowerReadout(
        val charge: Float,
        val capacity: Float,
        val net: Float,
        val powered: Boolean,
        val needsSignal: Boolean,
        val signal: com.rm.apogee.core.world.Signal,
        val relays: Int,
        val controllable: Boolean,
        val deployed: Boolean,
    ) {
        /** Charge as a share of what it holds, 0..1; 1 with no battery. */
        val share: Float get() = if (capacity > 0f) charge / capacity else 1f
        /** Low enough to warn about. */
        val low: Boolean get() = capacity > 0f && share < com.rm.apogee.core.world.Power.LOW.toFloat()
        /** Out of touch - a probe with no link - or out of charge, so the controls do nothing. */
        val outOfTouch: String? get() = when {
            controllable -> null
            !powered -> "NO POWER"
            else -> "NO SIGNAL"
        }
    }

    /** The flown craft's parachute: "ARMED", "OPEN", or null for none staged. */
    var chute: String? by mutableStateOf(null)

    /** How many craft the player owns, so the switch control can hide itself. */
    var ownedCraft: Int by mutableIntStateOf(0)

    /** The stage burning now and those still to fire, for the stage stack. */
    var stages: List<StageCard> by mutableStateOf(emptyList())

    /** Whether the stage stack is open to full detail. */
    var stagesExpanded: Boolean by mutableStateOf(false)

    /** Whether the SAS mode and target picker is open. */
    var sasPickerOpen: Boolean by mutableStateOf(false)

    /** Time: how fast it runs (0 paused), what was asked for, and whether this player may change it. */
    var warp: Double by mutableStateOf(1.0)
    var warpRequested: Double by mutableStateOf(1.0)
    var warpAllowed: Boolean by mutableStateOf(false)

    /** Sounds playing now, for diagnostics. */
    var voices: Int by mutableIntStateOf(0)

    /** Whether the warp rates are showing. */
    var warpPickerOpen: Boolean by mutableStateOf(false)

    /** Clears transient state when leaving the world, so a new flight starts clean. */
    fun reset() {
        frameTimeMillis = 0f
        frameBuildMillis = 0f
        simTick = 0L
        drawnItems = 0
        telemetry = FlightTelemetry.EMPTY
        connecting = true
        connectionError = null
        surfaceReady = false
        throttle = 0f
        sasEnabled = false
        brakes = false
        reverse = false
        hasWheels = false
        hasRcs = false
        power = null
        hasFoldouts = false
        rcsArmed = false
        rcsSlide = false
        rcsLeft = null
        dock = null
        joints = emptyList()
        sharedWith = null
        sharedPilot = "both"
        sharedOpen = false
        chute = null
        mapMode = false
        canJoin = false
        ownedCraft = 0
        stages = emptyList()
        stagesExpanded = false
        sasPickerOpen = false
        warp = 1.0
        warpRequested = 1.0
        warpAllowed = false
        warpPickerOpen = false
    }
}
