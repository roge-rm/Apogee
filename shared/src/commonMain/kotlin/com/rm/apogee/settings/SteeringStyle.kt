package com.rm.apogee.settings

import com.rm.apogee.core.craft.CraftKind
import com.rm.apogee.core.craft.CraftOrientation

/**
 * Which craft read the stick by the screen, and which by their nose. By the screen, the stick
 * points where you want to go as you see it; by the nose, it works the craft's own controls, as a
 * plane, rover or boat wants. The chip under the stick swaps them for one flight.
 */
enum class SteeringStyle(val label: String) {
    ROTORCRAFT_AND_ROCKETS("Rotorcraft and rockets"),
    ALL("Everything"),
    NONE("Nothing");

    /** Whether a craft of [kind], built [orientation], reads the stick by the screen. */
    fun byScreen(kind: CraftKind?, orientation: CraftOrientation?): Boolean = when (this) {
        ROTORCRAFT_AND_ROCKETS -> kind == CraftKind.ROTORCRAFT || orientation == CraftOrientation.VERTICAL
        ALL -> true
        NONE -> false
    }
}
