package com.rm.apogee.settings

import com.rm.apogee.core.craft.CraftKind
import com.rm.apogee.core.craft.CraftOrientation

/**
 * Which craft read the stick by the screen, and which by their nose.
 *
 * By the screen, the stick points where you want to go as you see it: a helicopter or a drone tips
 * that way and flies there whichever way it's facing, and a rocket's nose goes that way. By the
 * nose, it works the craft's own controls, which is what a plane, a rover or a boat wants. The
 * chip under the stick swaps them for one flight.
 */
enum class SteeringStyle(val label: String, val description: String) {
    ROTORCRAFT_AND_ROCKETS(
        "Rotorcraft and rockets",
        "Helicopters, drones and rockets by the screen. Planes, rovers and boats by their nose",
    ),
    ALL(
        "Everything",
        "Every craft by the screen",
    ),
    NONE(
        "Nothing",
        "Every craft by its nose",
    );

    /** Whether a craft of [kind], built [orientation], reads the stick by the screen. */
    fun byScreen(kind: CraftKind?, orientation: CraftOrientation?): Boolean = when (this) {
        ROTORCRAFT_AND_ROCKETS -> kind == CraftKind.ROTORCRAFT || orientation == CraftOrientation.VERTICAL
        ALL -> true
        NONE -> false
    }
}
