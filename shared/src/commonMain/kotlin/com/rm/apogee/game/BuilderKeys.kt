package com.rm.apogee.game

/** What a key does in the assembly building. */
enum class BuilderKey { UNDO, REDO, DELETE, DUPLICATE, SAVE, TURN, SYMMETRY, GROUP, CANCEL, RECENTRE, PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN }

/** Maps keys, by browser code, to what they do in the assembly building. */
object BuilderKeys {
    /** The action for [code] with Ctrl and Shift as given, or null if the building doesn't use it. */
    fun map(code: String, ctrl: Boolean, shift: Boolean): BuilderKey? = if (ctrl) when (code) {
        "KeyZ" -> if (shift) BuilderKey.REDO else BuilderKey.UNDO
        "KeyY" -> BuilderKey.REDO
        "KeyD" -> BuilderKey.DUPLICATE
        "KeyS" -> BuilderKey.SAVE
        else -> null
    } else when (code) {
        "Delete", "Backspace" -> BuilderKey.DELETE
        "KeyR" -> BuilderKey.TURN
        "KeyX" -> BuilderKey.SYMMETRY
        "KeyG" -> BuilderKey.GROUP
        "KeyF" -> BuilderKey.RECENTRE
        "Escape" -> BuilderKey.CANCEL
        "ArrowLeft" -> BuilderKey.PAN_LEFT
        "ArrowRight" -> BuilderKey.PAN_RIGHT
        "ArrowUp" -> BuilderKey.PAN_UP
        "ArrowDown" -> BuilderKey.PAN_DOWN
        else -> null
    }
}
