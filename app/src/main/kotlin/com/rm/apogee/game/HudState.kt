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

    /**
     * Which status chip's detail is open - [STATUS_PARTS], [STATUS_CREW],
     * [STATUS_DOCK], [STATUS_BASE] or [STATUS_SHARED] - or null for none:
     * one at a time.
     */
    var statusOpen: String? by mutableStateOf(null)

    /**
     * A feat just earned, or a launch the career refused: shown a few
     * seconds in the prompt slot. [good] for a feat, false for a refusal.
     */
    data class Banner(val title: String, val detail: String, val good: Boolean, val id: Long)
    var banner: Banner? by mutableStateOf(null)

    /**
     * This player's career in the world being flown in, as its server keeps
     * it - the host's, when joined - and that world's firsts; null in a
     * sandbox. And whether the program is open over the flight.
     */
    var career: com.rm.apogee.core.career.CareerState? by mutableStateOf(null)
    var worldFirsts: List<com.rm.apogee.core.career.WorldFirst> by mutableStateOf(emptyList())
    var programOpen: Boolean by mutableStateOf(false)

    /** Whether the flight strip is opened out into the whole panel. */
    var stripOpen: Boolean by mutableStateOf(false)

    /** The idle clock the controls fade by: see [HudFade]. */
    val fade = HudFade()

    /** A touch anywhere, on the view or a control: the controls come back. */
    fun touched() = fade.wake(System.nanoTime())
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
    var mapMode: Boolean by mutableStateOf(false)

    /** The next planned burn, and coming down; see [com.rm.apogee.ui.components.BurnPanel]. */
    var burn: com.rm.apogee.game.GameSession.BurnReadout? by mutableStateOf(null)
    var landing: com.rm.apogee.game.GameSession.LandingReadout? by mutableStateOf(null)
    var window: com.rm.apogee.game.GameSession.WindowReadout? by mutableStateOf(null)
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

    /** Whether the body flown about is surveyed, and what the map shows of it: "ORE", "H2O" or "OFF". */
    var surveyedHere: Boolean by mutableStateOf(false)
    var mapResource: String by mutableStateOf("ORE")

    /** The player's crew lost with the craft that was just lost, by name. */
    var crewLost: List<String> by mutableStateOf(emptyList())

    /** Whether the craft flown is someone out on EVA. */
    var isSuit: Boolean by mutableStateOf(false)

    /** Who is aboard the craft flown, and how many it seats. */
    var crew: List<CrewSeat> by mutableStateOf(emptyList())
    var crewSeats: Int by mutableIntStateOf(0)

    /** Someone aboard: their [name], where they sit, whether they are the player's, and whether there is another free seat for them. */
    data class CrewSeat(val id: Long, val name: String, val where: String, val mine: Boolean, val canMove: Boolean)

    /** Whether it has drills, and converters, so their controls can hide themselves. */
    var hasDrill: Boolean by mutableStateOf(false)
    var hasConverter: Boolean by mutableStateOf(false)

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
        /** Drills and converters switched on, and what the drills are doing. */
        val drilling: Boolean = false,
        val refining: Boolean = false,
        val drillState: com.rm.apogee.core.world.DrillState = com.rm.apogee.core.world.DrillState.OFF,
        /** Survey of the body it orbits, 0..1, or below 0 with no scanner. */
        val survey: Float = -1f,
        /** The ground below, by its scanner: 0..1, or below 0 for no reading. */
        val ore: Float = -1f,
        val water: Float = -1f,
        /** What it holds of ore and of water, and has room for: four numbers, or null for none known. */
        val held: FloatArray? = null,
        /** Why it cannot be flown, or blank. */
        val blocked: String = "",
        /** On EVA: a craft with a free seat in reach, a ladder in reach, holding one. */
        val boardable: String = "",
        val canGrab: Boolean = false,
        val onLadder: Boolean = false,
        /** Aboard someone else's craft. */
        val passenger: Boolean = false,
        /** Its ballast, 0..1 full, or below 0 with no tanks; flooding 1, blowing -1, or 0; the depth held, m, or below 0. */
        val ballast: Float = -1f,
        val ballastMode: Int = 0,
        val holdingDepth: Float = -1f,
        /** How near the sea is to crushing it: 1 is its limit. */
        val crush: Float = 0f,
        /** By its sonar: the floor below, m, and the nearest thing not yet found, bearing and range - below 0 for none. */
        val seabed: Float = -1f,
        val findBearing: Float = 0f,
        val findRange: Float = -1f,
    ) {
        /** Near enough its depth limit to warn, and past it. */
        val deepCaution: Boolean get() = crush > DEEP_CAUTION
        val deepDanger: Boolean get() = crush > 1f
        /** Charge as a share of what it holds, 0..1; 1 with no battery. */
        val share: Float get() = if (capacity > 0f) charge / capacity else 1f
        /** Low enough to warn about. */
        val low: Boolean get() = capacity > 0f && share < com.rm.apogee.core.world.Power.LOW.toFloat()
        /** Out of touch - a probe with no link - or out of charge, so the controls do nothing. */
        val outOfTouch: String? get() = when {
            passenger -> "PASSENGER"
            controllable -> null
            blocked.isNotEmpty() -> blocked
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
        hasDrill = false
        hasConverter = false
        isSuit = false
        crewLost = emptyList()
        crew = emptyList()
        crewSeats = 0
        statusOpen = null
        stripOpen = false
        banner = null
        career = null
        worldFirsts = emptyList()
        programOpen = false
        rcsArmed = false
        rcsSlide = false
        rcsLeft = null
        dock = null
        joints = emptyList()
        sharedWith = null
        sharedPilot = "both"
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

    companion object {
        const val STATUS_PARTS = "parts"
        const val STATUS_CREW = "crew"
        const val STATUS_DOCK = "dock"
        const val STATUS_BASE = "base"
        const val STATUS_SHARED = "shared"

        /** The share of its depth limit where a diving craft is warned. */
        const val DEEP_CAUTION = 0.8f
    }
}

/**
 * When the flight controls fade back to let the view through: [IDLE_NANOS]
 * after the last thing that wanted them - a touch anywhere, a new warning
 * or prompt, the engines running - and back at once on the next.
 */
class HudFade {
    @Volatile private var lastWake = System.nanoTime()

    fun wake(now: Long) {
        lastWake = now
    }

    /** Whether, at [now], it has been idle long enough to fade. */
    fun idle(now: Long): Boolean = now - lastWake > IDLE_NANOS

    companion object {
        const val IDLE_NANOS = 4_000_000_000L

        /** How much of the control opacity is left once faded. */
        const val FADED = 0.35f
    }
}
