package com.rm.apogee.desktop

import com.rm.apogee.ApogeeApp
import com.rm.apogee.input.MenuPad
import com.rm.apogee.input.PadButton
import com.rm.apogee.input.PadMode
import com.rm.apogee.input.PadState
import com.rm.apogee.ui.goBack
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWGamepadState
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * The first controller GLFW knows as a gamepad, in its Xbox-style layout (a Steam Deck's own
 * controls come through Steam Input as one). Read on the world thread, where GLFW was started, and
 * handed to the window's thread. In flight the app flies with it; elsewhere it works the screens.
 */
class Gamepads(private val app: ApogeeApp) {
    private val state = GLFWGamepadState.create()
    private var connected: Int = -1
    private var name: String? = null

    private val menus = MenuPad(::screensKey)
    private val started = System.nanoTime()

    /** On the world thread, after GLFW's events. */
    fun poll() {
        val jid = (GLFW_JOYSTICK_1..GLFW_JOYSTICK_LAST).firstOrNull { glfwJoystickIsGamepad(it) }
        if (jid == null) {
            if (connected >= 0) {
                connected = -1
                SwingUtilities.invokeLater {
                    app.pad.clear()
                    app.controllerName = null
                }
            }
            return
        }
        if (!glfwGetGamepadState(jid, state)) return
        if (jid != connected) {
            connected = jid
            name = glfwGetGamepadName(jid) ?: glfwGetJoystickName(jid)
        }
        val pad = PadState()
        for ((glfw, button) in BUTTONS) pad[button] = if (state.buttons(glfw).toInt() == GLFW_PRESS) 1f else 0f
        // Triggers go -1 to 1 resting at -1.
        pad[PadButton.L2] = (state.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) + 1f) / 2f
        pad[PadButton.R2] = (state.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) + 1f) / 2f
        pad.leftX = state.axes(GLFW_GAMEPAD_AXIS_LEFT_X)
        pad.leftY = state.axes(GLFW_GAMEPAD_AXIS_LEFT_Y)
        pad.rightX = state.axes(GLFW_GAMEPAD_AXIS_RIGHT_X)
        pad.rightY = state.axes(GLFW_GAMEPAD_AXIS_RIGHT_Y)
        val padName = name
        val time = (System.nanoTime() - started) / 1e6
        SwingUtilities.invokeLater {
            app.pad.update(pad)
            if (app.controllerName != padName) app.controllerName = padName
            if (app.padMode() != PadMode.FLIGHT) menus.update(pad, time) else menus.idle(pad)
        }
    }

    /** Types [key] into whatever has the keyboard. Escape goes back. True if something used it. */
    private fun screensKey(key: String, shift: Boolean): Boolean {
        if (key == "Escape") return goBack()
        val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        val code = when (key) {
            "Tab" -> KeyEvent.VK_TAB
            "Enter" -> KeyEvent.VK_ENTER
            "ArrowLeft" -> KeyEvent.VK_LEFT
            "ArrowRight" -> KeyEvent.VK_RIGHT
            else -> return false
        }
        val modifiers = if (shift) KeyEvent.SHIFT_DOWN_MASK else 0
        val char = if (code == KeyEvent.VK_ENTER) '\n' else if (code == KeyEvent.VK_TAB) '\t' else KeyEvent.CHAR_UNDEFINED
        val now = System.currentTimeMillis()
        val down = KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifiers, code, char)
        target.dispatchEvent(down)
        target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, now, modifiers, code, char))
        return down.isConsumed
    }

    private companion object {
        val BUTTONS = listOf(
            GLFW_GAMEPAD_BUTTON_A to PadButton.A, GLFW_GAMEPAD_BUTTON_B to PadButton.B,
            GLFW_GAMEPAD_BUTTON_X to PadButton.X, GLFW_GAMEPAD_BUTTON_Y to PadButton.Y,
            GLFW_GAMEPAD_BUTTON_LEFT_BUMPER to PadButton.L1, GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER to PadButton.R1,
            GLFW_GAMEPAD_BUTTON_BACK to PadButton.SELECT, GLFW_GAMEPAD_BUTTON_START to PadButton.START,
            GLFW_GAMEPAD_BUTTON_LEFT_THUMB to PadButton.L3, GLFW_GAMEPAD_BUTTON_RIGHT_THUMB to PadButton.R3,
            GLFW_GAMEPAD_BUTTON_DPAD_UP to PadButton.UP, GLFW_GAMEPAD_BUTTON_DPAD_DOWN to PadButton.DOWN,
            GLFW_GAMEPAD_BUTTON_DPAD_LEFT to PadButton.LEFT, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT to PadButton.RIGHT,
        )
    }
}
