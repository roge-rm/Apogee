package com.rm.apogee

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.rm.apogee.input.PadButton
import com.rm.apogee.input.PadState

/**
 * Turns Android gamepad events into a [PadState]: buttons from key events; sticks, triggers and a
 * hat D-pad from motion events.
 *
 * Most controllers put the right stick on Z/RZ and the triggers on LTRIGGER/RTRIGGER (often
 * mirrored on BRAKE/GAS). Some use RX/RY for the right stick, so that's used when there are no
 * trigger axes. A trigger that only sends L2/R2 keys counts as fully in while down.
 */
class GamepadReader {
    val state = PadState()

    // Key and axis input kept apart, so releasing one doesn't cancel the other.
    private val keyDown = BooleanArray(PadButton.entries.size)
    private var hatX = 0f
    private var hatY = 0f
    private var leftTrigger = 0f
    private var rightTrigger = 0f

    /** The button [event] is, if it's from a controller. */
    fun button(event: KeyEvent): PadButton? {
        // A controller-only button counts from anywhere (adb's fake gamepad has no device). A D-pad
        // key only counts from a controller, so keyboard arrows don't.
        val controller = fromController(event.device) || event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            KeyEvent.isGamepadButton(event.keyCode)
        if (!controller) return null
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> PadButton.A
            KeyEvent.KEYCODE_BUTTON_B -> PadButton.B
            KeyEvent.KEYCODE_BUTTON_X -> PadButton.X
            KeyEvent.KEYCODE_BUTTON_Y -> PadButton.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.R2
            KeyEvent.KEYCODE_BUTTON_THUMBL -> PadButton.L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> PadButton.R3
            KeyEvent.KEYCODE_BUTTON_START -> PadButton.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.SELECT
            KeyEvent.KEYCODE_DPAD_UP -> PadButton.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PadButton.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> PadButton.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PadButton.RIGHT
            else -> null
        }
    }

    /** A controller key going down or up. True if it was one. */
    fun key(event: KeyEvent): Boolean {
        val button = button(event) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        keyDown[button.ordinal] = event.action == KeyEvent.ACTION_DOWN
        refresh()
        return true
    }

    /** A controller's sticks, triggers and hat moving. True if it was one. */
    fun motion(event: MotionEvent): Boolean {
        val device = event.device
        if (!fromController(device) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        fun axis(which: Int): Float {
            val range = device.getMotionRange(which, event.source) ?: return 0f
            val value = event.getAxisValue(which)
            return if (kotlin.math.abs(value) > range.flat) value else 0f
        }
        fun has(which: Int) = device.getMotionRange(which, event.source) != null
        state.leftX = axis(MotionEvent.AXIS_X)
        state.leftY = axis(MotionEvent.AXIS_Y)
        val triggerAxes = has(MotionEvent.AXIS_LTRIGGER) || has(MotionEvent.AXIS_BRAKE)
        if (!triggerAxes && has(MotionEvent.AXIS_RX) && has(MotionEvent.AXIS_RY)) {
            state.rightX = axis(MotionEvent.AXIS_RX)
            state.rightY = axis(MotionEvent.AXIS_RY)
        } else {
            state.rightX = axis(MotionEvent.AXIS_Z)
            state.rightY = axis(MotionEvent.AXIS_RZ)
        }
        leftTrigger = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))
        rightTrigger = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))
        hatX = axis(MotionEvent.AXIS_HAT_X)
        hatY = axis(MotionEvent.AXIS_HAT_Y)
        refresh()
        return true
    }

    /** Everything let go, as when the controller's gone. */
    fun clear() {
        keyDown.fill(false)
        hatX = 0f; hatY = 0f; leftTrigger = 0f; rightTrigger = 0f
        state.leftX = 0f; state.leftY = 0f; state.rightX = 0f; state.rightY = 0f
        refresh()
    }

    private fun refresh() {
        for (button in PadButton.entries) state.press(button, keyDown[button.ordinal])
        if (hatY < -0.5f) state.press(PadButton.UP, true)
        if (hatY > 0.5f) state.press(PadButton.DOWN, true)
        if (hatX < -0.5f) state.press(PadButton.LEFT, true)
        if (hatX > 0.5f) state.press(PadButton.RIGHT, true)
        state[PadButton.L2] = maxOf(leftTrigger, if (keyDown[PadButton.L2.ordinal]) 1f else 0f)
        state[PadButton.R2] = maxOf(rightTrigger, if (keyDown[PadButton.R2.ordinal]) 1f else 0f)
    }

    companion object {
        /**
         * Whether [device] is a controller. Checked by device, since a handheld's D-pad can send
         * plain D-pad keys.
         */
        fun fromController(device: InputDevice?): Boolean {
            val sources = device?.sources ?: return false
            return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        }

        /** The first controller connected, by name, or null. */
        fun connectedName(): String? = InputDevice.getDeviceIds().toList()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .firstOrNull { !it.isVirtual && fromController(it) }
            ?.name
    }
}
