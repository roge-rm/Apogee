package com.rm.apogee.input

/**
 * A controller's buttons, by their Android and standard-gamepad names. A is the bottom face
 * button, B right, X left, Y top, whatever's printed on them. L2 and R2 are analog triggers.
 */
enum class PadButton(val id: String, val label: String) {
    A("a", "A"),
    B("b", "B"),
    X("x", "X"),
    Y("y", "Y"),
    L1("l1", "L1"),
    R1("r1", "R1"),
    L2("l2", "L2"),
    R2("r2", "R2"),
    L3("l3", "L3 (left stick in)"),
    R3("r3", "R3 (right stick in)"),
    UP("up", "D-pad up"),
    DOWN("down", "D-pad down"),
    LEFT("left", "D-pad left"),
    RIGHT("right", "D-pad right"),
    START("start", "Start"),
    SELECT("select", "Select"),
    ;

    /** Whether it reads how far it's pressed, not just whether it is. */
    val analog: Boolean get() = this == L2 || this == R2
}

/** The two sticks. */
enum class PadStick(val id: String, val label: String) {
    LEFT("ls", "Left stick"),
    RIGHT("rs", "Right stick"),
}

/** What a stick does: steers the craft (or walks), looks around, or nothing. */
enum class StickUse(val id: String, val label: String) {
    STEER("steer", "Steer"),
    LOOK("look", "Look around"),
    NOTHING("none", "Nothing"),
}

/** Flying a craft, or out of it on foot. Each has its own buttons. */
enum class PadLayer(val id: String, val label: String) {
    FLYING("f", "Flying"),
    ON_FOOT("e", "On foot"),
    MAP("m", "On the map"),
}

/**
 * What a button can do. A [held] one works while it's down (a trigger by how hard); the rest fire
 * once on press.
 */
enum class PadGroup(val label: String) {
    FLYING("Flying"),
    CRAFT("The craft"),
    VIEW("Camera, map and time"),
    ON_FOOT("On foot"),
    NOTHING("Nothing"),
}

enum class PadAction(val id: String, val label: String, val group: PadGroup, val held: Boolean = false) {
    NONE("none", "Nothing", PadGroup.NOTHING),

    STAGE("stage", "Stage", PadGroup.FLYING),
    THROTTLE_UP("throttle-up", "Throttle up", PadGroup.FLYING, held = true),
    THROTTLE_DOWN("throttle-down", "Throttle down", PadGroup.FLYING, held = true),
    THROTTLE_FULL("throttle-full", "Full throttle", PadGroup.FLYING),
    THROTTLE_CUT("throttle-cut", "Cut the throttle", PadGroup.FLYING),
    ROLL_LEFT("roll-left", "Roll left", PadGroup.FLYING, held = true),
    ROLL_RIGHT("roll-right", "Roll right", PadGroup.FLYING, held = true),
    SAS("sas", "SAS on or off", PadGroup.FLYING),
    RCS("rcs", "Thrusters on or off", PadGroup.FLYING),
    STICK_MODE("stick-mode", "Stick turns or slides", PadGroup.FLYING),
    STEERING("steering", "Steer by nose or screen", PadGroup.FLYING),
    CRUISE("cruise", "Cruise on or off", PadGroup.FLYING),
    KEEPER("keeper", "Keeper core on or off", PadGroup.FLYING),
    AUTO_LAND("auto-land", "Auto land on or off", PadGroup.FLYING),

    BRAKES("brakes", "Brakes", PadGroup.CRAFT),
    /** Held for half a second, the lights instead. See [PadInput]. */
    DEPLOY("deploy", "Gear and legs, held for lights", PadGroup.CRAFT),
    LIGHTS("lights", "Lights on or off", PadGroup.CRAFT),
    FLAPS("flaps", "Flaps", PadGroup.CRAFT),
    REVERSE("reverse", "Reverse", PadGroup.CRAFT),
    GROUP_1("group-1", "Group 1", PadGroup.CRAFT),
    GROUP_2("group-2", "Group 2", PadGroup.CRAFT),
    GROUP_3("group-3", "Group 3", PadGroup.CRAFT),
    HOOK("hook", "Hook on or let go", PadGroup.CRAFT),
    WINCH("winch", "Winch", PadGroup.CRAFT),
    DOCK("dock", "Join", PadGroup.CRAFT),
    RIGHT("right", "Roll upright", PadGroup.CRAFT),

    MAP("map", "Map view", PadGroup.VIEW),
    MAP_FOCUS("map-focus", "Map: look at the next", PadGroup.VIEW),
    MAP_PICK("map-pick", "Map: pick what's in the middle", PadGroup.VIEW),
    CAMERA_MODE("camera", "Camera mode", PadGroup.VIEW),
    ZOOM_IN("zoom-in", "Zoom in", PadGroup.VIEW, held = true),
    ZOOM_OUT("zoom-out", "Zoom out", PadGroup.VIEW, held = true),
    WARP_FASTER("warp-faster", "Time faster", PadGroup.VIEW),
    WARP_SLOWER("warp-slower", "Time slower", PadGroup.VIEW),
    FLIGHT_MENU("menu", "Flight menu", PadGroup.VIEW),

    JUMP("jump", "Jump", PadGroup.ON_FOOT),
    GRAB("grab", "Grab or let go of a ladder", PadGroup.ON_FOOT),
    BOARD("board", "Board", PadGroup.ON_FOOT),
    FLAG("flag", "Plant a flag", PadGroup.ON_FOOT),
    SWIM_DOWN("swim-down", "Swim down", PadGroup.ON_FOOT),
    SWIM_UP("swim-up", "Swim up", PadGroup.ON_FOOT),
    ;

    companion object {
        fun byId(id: String): PadAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which button does what, and what each stick does, flying and on foot.
 *
 * Saved as a short line ("f.a=stage;f.ls=steer;e.a=jump…"). Anything missing or unknown comes from
 * [RETROID_MINI], so an older layout still loads with new actions where they belong.
 */
class PadBindings private constructor(
    private val buttons: Map<PadLayer, Map<PadButton, PadAction>>,
    private val sticks: Map<PadLayer, Map<PadStick, StickUse>>,
) {
    fun action(layer: PadLayer, button: PadButton): PadAction = buttons[layer]?.get(button) ?: PadAction.NONE

    fun stick(layer: PadLayer, stick: PadStick): StickUse = sticks[layer]?.get(stick) ?: StickUse.NOTHING

    /** The stick that does [use], if one does. */
    fun stickFor(layer: PadLayer, use: StickUse): PadStick? = PadStick.entries.firstOrNull { stick(layer, it) == use }

    fun with(layer: PadLayer, button: PadButton, action: PadAction): PadBindings =
        PadBindings(buttons + (layer to (buttons[layer].orEmpty() + (button to action))), sticks)

    fun with(layer: PadLayer, stick: PadStick, use: StickUse): PadBindings =
        PadBindings(buttons, sticks + (layer to (sticks[layer].orEmpty() + (stick to use))))

    fun format(): String = buildString {
        for (layer in PadLayer.entries) {
            for (stick in PadStick.entries) item(layer.id + "." + stick.id, stick(layer, stick).id)
            for (button in PadButton.entries) item(layer.id + "." + button.id, action(layer, button).id)
        }
    }

    private fun StringBuilder.item(key: String, value: String) {
        if (isNotEmpty()) append(';')
        append(key).append('=').append(value)
    }

    override fun equals(other: Any?): Boolean = other is PadBindings && other.format() == format()
    override fun hashCode(): Int = format().hashCode()

    companion object {
        /**
         * The Retroid Pocket Mini V2 layout, and the default: left stick steers, right looks,
         * triggers throttle, shoulders roll, A stages, B brakes, X is SAS, Y the map, D-pad zooms
         * and warps, stick clicks are thrusters and camera mode, Select gear and legs (held,
         * lights), Start the flight menu. On foot, A jumps, B grabs a ladder, X boards and Select plants a flag.
         */
        val RETROID_MINI: PadBindings = run {
            val flying = mapOf(
                PadButton.A to PadAction.STAGE,
                PadButton.B to PadAction.BRAKES,
                PadButton.X to PadAction.SAS,
                PadButton.Y to PadAction.MAP,
                PadButton.L1 to PadAction.ROLL_LEFT,
                PadButton.R1 to PadAction.ROLL_RIGHT,
                PadButton.L2 to PadAction.THROTTLE_DOWN,
                PadButton.R2 to PadAction.THROTTLE_UP,
                PadButton.L3 to PadAction.RCS,
                PadButton.R3 to PadAction.CAMERA_MODE,
                PadButton.UP to PadAction.ZOOM_IN,
                PadButton.DOWN to PadAction.ZOOM_OUT,
                PadButton.LEFT to PadAction.WARP_SLOWER,
                PadButton.RIGHT to PadAction.WARP_FASTER,
                PadButton.START to PadAction.FLIGHT_MENU,
                PadButton.SELECT to PadAction.DEPLOY,
            )
            val onFoot = flying + mapOf(
                PadButton.A to PadAction.JUMP,
                PadButton.B to PadAction.GRAB,
                PadButton.X to PadAction.BOARD,
                PadButton.SELECT to PadAction.FLAG,
                // The triggers swim on foot, since there's no throttle.
                PadButton.L2 to PadAction.SWIM_DOWN,
                PadButton.R2 to PadAction.SWIM_UP,
            )
            // On the map both sticks turn the view, the triggers zoom, A picks what's in the
            // middle, X looks at the next thing, and B or Y go back to flying. The rest fly.
            val onMap = flying + mapOf(
                PadButton.A to PadAction.MAP_PICK,
                PadButton.B to PadAction.MAP,
                PadButton.X to PadAction.MAP_FOCUS,
                PadButton.Y to PadAction.MAP,
                PadButton.L2 to PadAction.ZOOM_OUT,
                PadButton.R2 to PadAction.ZOOM_IN,
                PadButton.UP to PadAction.ZOOM_IN,
                PadButton.DOWN to PadAction.ZOOM_OUT,
                PadButton.LEFT to PadAction.WARP_SLOWER,
                PadButton.RIGHT to PadAction.WARP_FASTER,
                PadButton.START to PadAction.FLIGHT_MENU,
            )
            val sticks = mapOf(PadStick.LEFT to StickUse.STEER, PadStick.RIGHT to StickUse.LOOK)
            val mapSticks = mapOf(PadStick.LEFT to StickUse.LOOK, PadStick.RIGHT to StickUse.LOOK)
            PadBindings(
                mapOf(PadLayer.FLYING to flying, PadLayer.ON_FOOT to onFoot, PadLayer.MAP to onMap),
                mapOf(PadLayer.FLYING to sticks, PadLayer.ON_FOOT to sticks, PadLayer.MAP to mapSticks),
            )
        }

        /** A saved layout, with anything it doesn't say (or says wrong) from [RETROID_MINI]. */
        fun parse(text: String): PadBindings {
            var bindings = RETROID_MINI
            for (item in text.split(';')) {
                val equals = item.indexOf('=')
                if (equals < 0) continue
                val key = item.substring(0, equals)
                val value = item.substring(equals + 1)
                val dot = key.indexOf('.')
                if (dot < 0) continue
                val layer = PadLayer.entries.firstOrNull { it.id == key.substring(0, dot) } ?: continue
                val name = key.substring(dot + 1)
                val stick = PadStick.entries.firstOrNull { it.id == name }
                if (stick != null) {
                    val use = StickUse.entries.firstOrNull { it.id == value } ?: continue
                    bindings = bindings.with(layer, stick, use)
                    continue
                }
                val button = PadButton.entries.firstOrNull { it.id == name } ?: continue
                val action = PadAction.byId(value) ?: continue
                bindings = bindings.with(layer, button, action)
            }
            return bindings
        }
    }
}
