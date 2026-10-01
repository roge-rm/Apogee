package com.rm.apogee.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where controller input goes: the craft, the Vehicle Assembly's camera, or nowhere (a menu or
 * panel has it).
 */
enum class PadMode { FLIGHT, BUILDER, MENU }

/**
 * A controller right now: how far each button is pressed (0 or 1, between on a trigger) and the
 * sticks, -1 to 1, down and right positive. The host keeps one and fills it from its events.
 */
class PadState {
    val buttons = FloatArray(PadButton.entries.size)
    var leftX = 0f
    var leftY = 0f
    var rightX = 0f
    var rightY = 0f

    operator fun get(button: PadButton): Float = buttons[button.ordinal]
    operator fun set(button: PadButton, value: Float) { buttons[button.ordinal] = value }
    fun press(button: PadButton, down: Boolean) { buttons[button.ordinal] = if (down) 1f else 0f }
}

/** What the controller works, so [PadInput] can be tried without a game. */
interface PadTarget {
    /** The stick: pitch and yaw, -1 to 1, the same as the touch stick. */
    fun steer(pitch: Float, yaw: Float)
    fun roll(roll: Float)
    /** Turns the camera by these angles, in radians. */
    fun look(yaw: Double, pitch: Double)
    /** Over 1 closer, under 1 further. */
    fun zoom(factor: Float)
    val throttle: Float
    fun setThrottle(value: Float)
    fun act(action: PadAction)
    /** How far a held A has got toward staging, 0 to 1. */
    fun stageHold(fraction: Float) {}
    /** The controller was used, so the touch stick can get out of the way. */
    fun used() {}
}

/** How the controller's set up, from the settings. */
data class PadConfig(
    val bindings: PadBindings = PadBindings.RETROID_MINI,
    val deadZone: Float = 0.15f,
    val lookSpeed: Float = 1f,
    val invertLook: Boolean = false,
    val holdToStage: Boolean = true,
)

/**
 * A controller turned into flying.
 *
 * The host passes the state to [update] whenever it changes, and [tick] runs once a frame. A
 * one-shot button fires on press, even if released before the frame. A held one (throttle, roll,
 * zoom) works every frame it's down. Sticks have a dead zone and a gentle curve, and steering is
 * only sent when it changes, like the keys.
 */
class PadInput(private val config: () -> PadConfig) {

    /** The last button pressed, anywhere, so settings can show which is which. */
    var lastPressed: PadButton? by mutableStateOf(null)
        private set

    /** Counts presses, so pressing the same button twice still shows. */
    var presses: Int by mutableStateOf(0)
        private set

    private val state = PadState()
    private val down = BooleanArray(PadButton.entries.size)
    private val pressed = BooleanArray(PadButton.entries.size)
    private var usedSinceTick = false

    private var lastNanos = 0L
    private var sentPitch = 0f
    private var sentYaw = 0f
    private var sentRoll = 0f

    /** The button held to stage, when it went down, and whether it's staged already this hold. */
    private var stageButton: PadButton? = null
    private var stageSince = 0L
    private var staged = false
    private var shownHold = 0f

    private val shaped = FloatArray(2)

    fun update(from: PadState) {
        val dead = config().deadZone
        for (button in PadButton.entries) {
            val i = button.ordinal
            val value = from.buttons[i]
            state.buttons[i] = value
            val now = value > PRESSED
            if (now && !down[i]) {
                pressed[i] = true
                usedSinceTick = true
                lastPressed = button
                presses++
            }
            down[i] = now
        }
        state.leftX = from.leftX; state.leftY = from.leftY
        state.rightX = from.rightX; state.rightY = from.rightY
        if (kotlin.math.hypot(from.leftX, from.leftY) > dead || kotlin.math.hypot(from.rightX, from.rightY) > dead) {
            usedSinceTick = true
        }
    }

    /** Lets go of everything, as when the controller's gone. */
    fun clear() {
        update(PadState())
        pressed.fill(false)
    }

    fun tick(nanos: Long, mode: PadMode, layer: PadLayer, target: PadTarget) {
        val dt = if (lastNanos == 0L) 0.0 else ((nanos - lastNanos) / 1e9).coerceIn(0.0, MAX_STEP)
        lastNanos = nanos
        val setup = config()
        val bindings = setup.bindings
        when (mode) {
            PadMode.MENU -> {
                letGo(target)
                pressed.fill(false)
                usedSinceTick = false
                return
            }
            PadMode.BUILDER -> {
                letGo(target)
                pressed.fill(false)
                usedSinceTick = false
                // The Vehicle Assembly's camera: the look stick turns it and the triggers zoom. The
                // rest of the controller works its menus.
                look(bindings.stickFor(PadLayer.FLYING, StickUse.LOOK), setup, dt, target)
                val zoom = amount(PadButton.R2) - amount(PadButton.L2)
                if (zoom != 0f && dt > 0.0) target.zoom(kotlin.math.exp(zoom * ZOOM_PER_SECOND * dt).toFloat())
                return
            }
            PadMode.FLIGHT -> Unit
        }
        if (usedSinceTick) { target.used(); usedSinceTick = false }

        // Steering, only when it's changed.
        val steerStick = bindings.stickFor(layer, StickUse.STEER)
        if (steerStick != null) {
            stick(steerStick, setup.deadZone)
            // Stick up is pitch up, like W. (0 - y, not -y, so centred is 0, not -0.)
            val pitch = 0f - shaped[1]
            val yaw = shaped[0]
            if (changed(pitch, sentPitch) || changed(yaw, sentYaw)) {
                sentPitch = pitch; sentYaw = yaw
                target.steer(pitch, yaw)
            }
        } else if (sentPitch != 0f || sentYaw != 0f) {
            sentPitch = 0f; sentYaw = 0f
            target.steer(0f, 0f)
        }
        look(bindings.stickFor(layer, StickUse.LOOK), setup, dt, target)

        // What's held: throttle, roll and zoom, as hard as each is pressed.
        var throttle = 0f; var roll = 0f; var zoom = 0f
        for (button in PadButton.entries) {
            val amount = amount(button)
            if (amount == 0f) continue
            when (bindings.action(layer, button)) {
                PadAction.THROTTLE_UP -> throttle += amount
                PadAction.THROTTLE_DOWN -> throttle -= amount
                PadAction.ROLL_RIGHT -> roll += amount
                PadAction.ROLL_LEFT -> roll -= amount
                PadAction.ZOOM_IN -> zoom += amount
                PadAction.ZOOM_OUT -> zoom -= amount
                else -> Unit
            }
        }
        if (throttle != 0f && dt > 0.0) {
            val next = (target.throttle + throttle * (dt / THROTTLE_SECONDS).toFloat()).coerceIn(0f, 1f)
            if (next != target.throttle) target.setThrottle(next)
        }
        roll = roll.coerceIn(-1f, 1f)
        if (changed(roll, sentRoll)) {
            sentRoll = roll
            target.roll(roll)
        }
        if (zoom != 0f && dt > 0.0) target.zoom(kotlin.math.exp(zoom * ZOOM_PER_SECOND * dt).toFloat())

        // What happens once, as a button goes down.
        for (button in PadButton.entries) {
            if (!pressed[button.ordinal]) continue
            pressed[button.ordinal] = false
            val action = bindings.action(layer, button)
            when {
                action.held || action == PadAction.NONE -> Unit
                action == PadAction.STAGE && setup.holdToStage -> {
                    stageButton = button; stageSince = nanos; staged = false
                }
                else -> target.act(action)
            }
        }

        // Staging, once it's been held long enough.
        val holding = stageButton
        var hold = 0f
        if (holding != null) {
            if (!down[holding.ordinal] || bindings.action(layer, holding) != PadAction.STAGE) {
                stageButton = null
            } else if (!staged) {
                hold = ((nanos - stageSince).toFloat() / STAGE_HOLD_NANOS).coerceIn(0f, 1f)
                if (hold >= 1f) {
                    staged = true
                    hold = 0f
                    target.act(PadAction.STAGE)
                }
            }
        }
        if (hold != shownHold) {
            shownHold = hold
            target.stageHold(hold)
        }
    }

    /** Lets go of the stick and roll, if they were holding anything, and of a stage being held. */
    private fun letGo(target: PadTarget) {
        if (sentPitch != 0f || sentYaw != 0f) {
            sentPitch = 0f; sentYaw = 0f
            target.steer(0f, 0f)
        }
        if (sentRoll != 0f) {
            sentRoll = 0f
            target.roll(0f)
        }
        stageButton = null
        if (shownHold != 0f) {
            shownHold = 0f
            target.stageHold(0f)
        }
    }

    private fun look(stick: PadStick?, setup: PadConfig, dt: Double, target: PadTarget) {
        if (stick == null || dt <= 0.0) return
        stick(stick, setup.deadZone)
        if (shaped[0] == 0f && shaped[1] == 0f) return
        val rate = LOOK_RADIANS_PER_SECOND * setup.lookSpeed * dt
        val up = if (setup.invertLook) -1.0 else 1.0
        target.look(shaped[0] * rate, -shaped[1] * rate * up)
    }

    /** How far [button] is pressed, with a trigger resting a little open read as not at all. */
    private fun amount(button: PadButton): Float {
        val value = state.buttons[button.ordinal]
        return if (value < TRIGGER_FLOOR) 0f else value.coerceAtMost(1f)
    }

    /** A stick through the dead zone and the curve, into [shaped]. */
    private fun stick(stick: PadStick, dead: Float) {
        val x = if (stick == PadStick.LEFT) state.leftX else state.rightX
        val y = if (stick == PadStick.LEFT) state.leftY else state.rightY
        val length = kotlin.math.hypot(x, y)
        if (length <= dead || length == 0f) { shaped[0] = 0f; shaped[1] = 0f; return }
        val out = ((length - dead) / (1f - dead)).coerceIn(0f, 1f)
        val curved = out * (0.5f + 0.5f * out)
        shaped[0] = x / length * curved
        shaped[1] = y / length * curved
    }

    private fun changed(now: Float, sent: Float): Boolean =
        kotlin.math.abs(now - sent) > SEND_STEP || (now == 0f && sent != 0f)

    companion object {
        /** A trigger past half way counts as pressed. */
        const val PRESSED = 0.5f
        const val TRIGGER_FLOOR = 0.05f
        /** A trigger held all the way moves the throttle end to end in this long, like the keys. */
        const val THROTTLE_SECONDS = 1.5
        /** How fast a held zoom goes: three times closer a second. */
        val ZOOM_PER_SECOND = kotlin.math.ln(3.0)
        const val LOOK_RADIANS_PER_SECOND = 2.4
        /** How long A has to be held to stage. */
        const val STAGE_HOLD_NANOS = 300_000_000f
        /** How much the stick has to move before it's sent again. */
        const val SEND_STEP = 0.02f
        const val MAX_STEP = 0.1

        /**
         * The next time warp from [current], faster or slower, through [rates] (in order, from
         * paused up). Null at either end.
         */
        fun warpStep(current: Double, faster: Boolean, rates: List<Double>): Double? =
            if (faster) rates.firstOrNull { it > current + 1e-9 } else rates.lastOrNull { it < current - 1e-9 }
    }
}
