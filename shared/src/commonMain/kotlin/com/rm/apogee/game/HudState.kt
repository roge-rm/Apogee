package com.rm.apogee.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.apogee.platform.System
import kotlin.concurrent.Volatile

/**
 * Everything the in-world overlay shows, as one observable holder.
 *
 * It's a plain class on purpose, instead of state hoisted up through composables. The writers are
 * the frame-clock loop, the game session and the touch handlers, and all of them are ordinary
 * non-composable Kotlin that needs a plain reference to write into. Compose watches it, and the
 * game doesn't have to become Compose to talk to it.
 */
class HudState {
    // --- diagnostics --------------------------------------------------------
    var frameTimeMillis: Float by mutableFloatStateOf(0f)
    var frameBuildMillis: Float by mutableFloatStateOf(0f)
    var simTick: Long by mutableLongStateOf(0L)
    var drawnItems: Int by mutableIntStateOf(0)

    // --- flight -------------------------------------------------------------
    var telemetry: FlightTelemetry by mutableStateOf(FlightTelemetry.EMPTY)

    /** How the craft being flown gets about, for the words about it: "Keep sailing". */
    var going: Going by mutableStateOf(Going.FLY)

    /**
     * Which status chip's detail is open ([STATUS_PARTS], [STATUS_CREW], [STATUS_DOCK],
     * [STATUS_BASE] or [STATUS_SHARED]), or null for none. Only one at a time.
     */
    var statusOpen: String? by mutableStateOf(null)

    /**
     * A feat just earned, or a launch the career refused, shown for a few seconds in the prompt
     * slot. [good] is true for a feat and false for a refusal.
     */
    data class Banner(val title: String, val detail: String, val good: Boolean, val id: Long)
    var banner: Banner? by mutableStateOf(null)

    /**
     * This player's career in the world being flown in, as its server keeps it (the host's, when
     * joined), and that world's firsts. Null in a sandbox. Also whether the program is open over
     * the flight.
     */
    var career: com.rm.apogee.core.career.CareerState? by mutableStateOf(null)
    var worldFirsts: List<com.rm.apogee.core.career.WorldFirst> by mutableStateOf(emptyList())
    var programOpen: Boolean by mutableStateOf(false)

    /** Whether the flight strip is opened out into the whole panel. */
    var stripOpen: Boolean by mutableStateOf(false)

    /** The idle clock the controls fade by. See [HudFade]. */
    val fade = HudFade()

    /** A touch anywhere, on the view or a control, brings the controls back. */
    fun touched() = fade.wake(System.nanoTime())
    var connecting: Boolean by mutableStateOf(true)
    var connectionError: String? by mutableStateOf(null)

    /**
     * Whether there's ground under the craft yet. It's separate from [connecting] because the two
     * wait on different things and tell the player different things. One is the server, and the
     * other is this device.
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
     * Thrusters: whether the craft has any (otherwise the RCS control hides itself), whether
     * they're armed, whether the stick slides the craft instead of turning it, and the
     * monopropellant left (0..1, or null).
     */
    var hasRcs: Boolean by mutableStateOf(false)
    var rcsArmed: Boolean by mutableStateOf(false)
    var rcsSlide: Boolean by mutableStateOf(false)
    var rcsLeft: Float? by mutableStateOf(null)

    /** Whether the stick is read by the screen, or by the craft's nose. See [com.rm.apogee.settings.SteeringStyle]. */
    var steerByScreen: Boolean by mutableStateOf(false)

    /** How the camera follows the craft. */
    var cameraMode: CameraMode by mutableStateOf(CameraMode.FREE)

    /** Docking: lining up, what the flown craft is joined by, and who it's shared with. */
    var dock: com.rm.apogee.game.GameSession.DockReadout? by mutableStateOf(null)
    var joints: List<com.rm.apogee.game.GameSession.Joint> by mutableStateOf(emptyList())
    /**
     * Shared with [sharedWith] (their name): who flies it ("me", "them" or "both"), or null when it
     * isn't shared.
     */
    var sharedWith: String? by mutableStateOf(null)
    var sharedPilot: String by mutableStateOf("both")
    var mapMode: Boolean by mutableStateOf(false)

    /** The next planned burn, and coming down. See [com.rm.apogee.ui.components.BurnPanel]. */
    var burn: com.rm.apogee.game.GameSession.BurnReadout? by mutableStateOf(null)
    var landing: com.rm.apogee.game.GameSession.LandingReadout? by mutableStateOf(null)

    /** On the map, whether the path shown is one to plan burns on, or a course over the ground. */
    var mapPlannable: Boolean by mutableStateOf(true)
    var window: com.rm.apogee.game.GameSession.WindowReadout? by mutableStateOf(null)
    var autopilotNote: String by mutableStateOf("")

    /**
     * Whether another craft is close enough and still enough to weld to.
     *
     * The client decides this from the craft it already knows about, so the button shows up exactly
     * when pressing it would do something. The server checks the same things again before acting.
     * This is for the UI, not for authority.
     */
    var canJoin: Boolean by mutableStateOf(false)

    /** What the flown craft can do with a base: found, let go, or refuel. */
    var baseService: com.rm.apogee.core.world.ServerMessage.Service? by mutableStateOf(null)

    /** The founded base nearby, or the one being flown, for its card. */
    var nearBase: com.rm.apogee.core.world.ServerMessage.BaseStatus? by mutableStateOf(null)

    /** The flown craft's power and link home, or null before the server has said. */
    var power: PowerReadout? by mutableStateOf(null)

    /** Whether the flown craft has sun wings or dishes to fold out, so the DEPLOY control can hide itself. */
    var hasFoldouts: Boolean by mutableStateOf(false)

    /**
     * Whether the body being flown around is surveyed, and what the map shows of it: "ORE", "H2O"
     * or "OFF".
     */
    var surveyedHere: Boolean by mutableStateOf(false)
    var mapResource: String by mutableStateOf("ORE")

    /** Whether the world here has sea currents, for the map's CURRENTS layer. */
    var currentsHere: Boolean by mutableStateOf(false)

    /** The player's crew who were lost with the craft that was just lost, by name. */
    var crewLost: List<String> by mutableStateOf(emptyList())

    /** Whether the craft being flown is someone out on EVA. */
    var isSuit: Boolean by mutableStateOf(false)

    /** Who's aboard the craft being flown, and how many it seats. */
    var crew: List<CrewSeat> by mutableStateOf(emptyList())
    var crewSeats: Int by mutableIntStateOf(0)

    /** Someone aboard: their [name], where they sit, whether they're the player's, and whether there's another free seat for them. */
    data class CrewSeat(val id: Long, val name: String, val where: String, val mine: Boolean, val canMove: Boolean)

    /** Whether it has drills and converters, so their controls can hide themselves. */
    var hasDrill: Boolean by mutableStateOf(false)
    var hasConverter: Boolean by mutableStateOf(false)

    /** Whether it has wings with flaps, and whether they're down. */
    var hasFlaps: Boolean by mutableStateOf(false)
    var flaps: Boolean by mutableStateOf(false)

    /** Whether it has a sail, so the strip always shows the wind. */
    var hasSails: Boolean by mutableStateOf(false)

    /** Whether it's riding on the water, so the readouts are a boat's. */
    var afloat: Boolean by mutableStateOf(false)

    /** Whether it's a rover, so on the ground the readouts are a car's. */
    var driving: Boolean by mutableStateOf(false)

    /** Whether the list of the player's craft is open. */
    var craftListOpen: Boolean by mutableStateOf(false)

    /** Whether it's a plane in the air, which can hold its height and heading. */
    var canCruise: Boolean by mutableStateOf(false)

    /** Whether the auto-land can take a plane, rotorcraft or airship down here, and whether it is. */
    var canLand: Boolean by mutableStateOf(false)
    var autoLanding: Boolean by mutableStateOf(false)

    /** The action groups its parts are in, 1 to 3, so the rail shows a switch for each. */
    var groupsUsed: List<Int> by mutableStateOf(emptyList())

    /**
     * Whether this flight can be rewound (a save point taken, loaded, or the flight reverted to its
     * launch): only in your own world, with nobody else on it. And when the save point was taken,
     * in words, or null with none, and whether there's a launch to revert to.
     */
    var canRewind: Boolean by mutableStateOf(false)
    var savePoint: String? by mutableStateOf(null)
    var canRevert: Boolean by mutableStateOf(false)

    /** The runway approach cue, while coming in to land on it, or null. */
    var approach: com.rm.apogee.core.world.Approach.Cue? by mutableStateOf(null)

    /** The current the craft is floating in, over the ground: speed in m/s and where it's going, in compass degrees. */
    var currentSpeed: Float by mutableStateOf(0f)
    var currentBearing: Float by mutableStateOf(0f)

    /**
     * A craft's power and link home, for the HUD: charge and what it holds, the net rate per
     * second, whether it has power, whether it needs a signal (a probe) and which one it has,
     * through how many relays, whether it can be flown right now, and whether its fold-outs have
     * been told to come out.
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
        /** The survey of the body it orbits, 0..1, or below 0 with no scanner. */
        val survey: Float = -1f,
        /** The ground below, from its scanner: 0..1, or below 0 for no reading. */
        val ore: Float = -1f,
        val water: Float = -1f,
        /** What it holds of ore and of water, and has room for: four numbers, or null for none known. */
        val held: FloatArray? = null,
        /** Why it can't be flown, or blank. */
        val blocked: String = "",
        /** On EVA: a craft with a free seat in reach, a ladder in reach, and holding one. */
        val boardable: String = "",
        val canGrab: Boolean = false,
        val onLadder: Boolean = false,
        /** In the water: a craft they could climb out onto, or empty; swimming; and how cold, 0..1. */
        val climbOnto: String = "",
        val swimming: Boolean = false,
        val chill: Float = 0f,
        /** Why nobody can go outside now, or empty. */
        val evaBlocked: String = "",
        /** Aboard someone else's craft. */
        val passenger: Boolean = false,
        /**
         * Its ballast, 0..1 full, or below 0 with no tanks. Flooding is 1, blowing -1, or 0. The
         * depth held in metres, or below 0.
         */
        val ballast: Float = -1f,
        val ballastMode: Int = 0,
        val holdingDepth: Float = -1f,
        /** How close the sea is to crushing it. 1 is its limit. */
        val crush: Float = 0f,
        /**
         * From its sonar: the floor below in metres, and the nearest thing not found yet, with
         * bearing and range. Below 0 for none.
         */
        val seabed: Float = -1f,
        val findBearing: Float = 0f,
        val findRange: Float = -1f,
        /** Holding height and heading: the height, or below 0 when not, and whether it can at all. */
        val cruiseHeight: Float = -1f,
        val mayCruise: Boolean = true,
        /** Its action groups' states, by group number: 0 left alone, 1 on, -1 off. */
        val groups: List<Int> = emptyList(),
        /**
         * Its winch: whether it has one, what it could hook now (blank for nothing), whether it's
         * hooked, which way it's winding (1 in, -1 out, 0 holding), and whether the line is pulling.
         */
        val hasWinch: Boolean = false,
        val canHook: String = "",
        val hooked: Boolean = false,
        val reel: Int = 0,
        val taut: Boolean = false,
        /** Whether it has a keeper core, and whether it's holding station with it. */
        val hasKeeper: Boolean = false,
        val keeping: Boolean = false,
        /** Its gas cells' lift as a share of its weight, or below 0 with none. */
        val lift: Float = -1f,
        /** Gone over, and small enough for its crew to roll it back upright. */
        val canRight: Boolean = false,
    ) {
        /** Whether it's holding height and heading. */
        val cruising: Boolean get() = cruiseHeight >= 0f

        /** Action group [group]'s state: 0 left alone, 1 on, -1 off. */
        fun group(group: Int): Int = groups.getOrElse(group) { 0 }

        /** Close enough to its depth limit to warn, and past it. */
        val deepCaution: Boolean get() = crush > DEEP_CAUTION
        val deepDanger: Boolean get() = crush > 1f
        /** Cold enough in the water to warn, and near the end. */
        val coldCaution: Boolean get() = chill > COLD_CAUTION
        val coldDanger: Boolean get() = chill > COLD_DANGER
        /** Charge as a share of what it holds, 0..1. 1 with no battery. */
        val share: Float get() = if (capacity > 0f) charge / capacity else 1f
        /** Low enough to warn about. */
        val low: Boolean get() = capacity > 0f && share < com.rm.apogee.core.world.Power.LOW.toFloat()
        /** Out of touch (a probe with no link) or out of charge, so the controls do nothing. */
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

    /** The stage burning now and the ones still to fire, for the stage stack. */
    var stages: List<StageCard> by mutableStateOf(emptyList())

    /** Whether the stage stack is open to full detail. */
    var stagesExpanded: Boolean by mutableStateOf(false)

    /** Whether the SAS mode and target picker is open. */
    var sasPickerOpen: Boolean by mutableStateOf(false)

    /**
     * Time: how fast it runs (0 is paused), what was asked for, and whether this player can change
     * it.
     */
    var warp: Double by mutableStateOf(1.0)
    var warpRequested: Double by mutableStateOf(1.0)

    /** How fast the world is really going under physics warp, or NaN when that isn't measured. */
    var warpActual: Double by mutableStateOf(Double.NaN)
    var warpAllowed: Boolean by mutableStateOf(false)

    /** Sounds playing right now, for diagnostics. */
    var voices: Int by mutableIntStateOf(0)

    /** Whether the warp rates are showing. */
    var warpPickerOpen: Boolean by mutableStateOf(false)

    /** Whether the flight menu (save points, and leaving) is up. */
    var exitMenuOpen: Boolean by mutableStateOf(false)

    /**
     * Whether a controller's flying the craft, so the touch stick can step aside, until a touch
     * says otherwise. And how far a held A has got toward staging, for the STAGE button's fill.
     */
    var padActive: Boolean by mutableStateOf(false)
    var stageHold: Float by mutableFloatStateOf(0f)

    /** Whether something is open over the flight that a controller should work instead of the craft. */
    val panelOpen: Boolean
        get() = exitMenuOpen || programOpen || craftListOpen || sasPickerOpen || warpPickerOpen || statusOpen != null

    /** Closes the top thing open over the flight, if there is one. True if something closed. */
    fun closePanel(): Boolean {
        when {
            exitMenuOpen -> exitMenuOpen = false
            programOpen -> programOpen = false
            craftListOpen -> craftListOpen = false
            sasPickerOpen -> sasPickerOpen = false
            warpPickerOpen -> warpPickerOpen = false
            statusOpen != null -> statusOpen = null
            else -> return false
        }
        return true
    }

    /** Clears passing state when leaving the world, so a new flight starts clean. */
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
        hasFlaps = false
        flaps = false
        groupsUsed = emptyList()
        canCruise = false
        canLand = false
        autoLanding = false
        hasSails = false
        afloat = false
        driving = false
        approach = null
        currentSpeed = 0f
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
        exitMenuOpen = false
        stageHold = 0f
    }

    companion object {
        const val STATUS_PARTS = "parts"
        const val STATUS_CREW = "crew"
        const val STATUS_DOCK = "dock"
        const val STATUS_BASE = "base"
        const val STATUS_SHARED = "shared"

        /** The share of its depth limit where a diving craft gets warned. */
        const val DEEP_CAUTION = 0.8f

        /** How cold, as a share of what kills, to warn about someone in the water, and to warn hard. */
        const val COLD_CAUTION = 0.5f
        const val COLD_DANGER = 0.85f
    }
}

/**
 * When the flight controls fade back to let the view through: [IDLE_NANOS] after the last thing
 * that wanted them (a touch anywhere, a new warning or prompt, or the engines running), and back
 * straight away on the next one.
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
