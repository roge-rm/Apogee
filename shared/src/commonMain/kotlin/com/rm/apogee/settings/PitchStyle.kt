package com.rm.apogee.settings

import com.rm.apogee.core.craft.CraftOrientation

/**
 * Which way the stick pitches. Push up is +pitch, which suits a rocket, but aircraft climb with the
 * stick pulled back.
 */
enum class PitchStyle(val label: String, val description: String) {
    AIRCRAFT(
        "Aircraft",
        "Anything built lying down",
    ),
    ALL(
        "Everything",
        "",
    ),
    NONE(
        "Never",
        "",
    );

    /** Whether the stick's pitch is reversed for a craft built [orientation]. */
    fun reverses(orientation: CraftOrientation?): Boolean = when (this) {
        AIRCRAFT -> orientation == CraftOrientation.HORIZONTAL
        ALL -> true
        NONE -> false
    }
}
