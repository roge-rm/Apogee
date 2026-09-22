package com.rm.apogee.settings

import com.rm.apogee.core.craft.CraftOrientation

/**
 * Which way the stick pitches.
 *
 * The stick's own convention is push-up for +pitch. On a rocket that is as
 * good as any - it has no belly, so "nose up" means nothing in particular - but
 * on an aeroplane +pitch is nose up, and every flight simulator, and every
 * aircraft, climbs with the stick pulled *back*. The first attempt to fly the
 * stock plane off the runway pushed forward to climb and flew it into a hill.
 */
enum class PitchStyle(val label: String, val description: String) {
    AIRCRAFT(
        "Aircraft",
        "Pull back to climb in anything built horizontal; rockets keep push-up for +pitch",
    ),
    ALL(
        "Everything",
        "Pull back for nose-up in every craft",
    ),
    NONE(
        "Never",
        "Push up for +pitch in every craft",
    );

    /** Whether the stick's pitch is reversed for a craft built [orientation]. */
    fun reverses(orientation: CraftOrientation?): Boolean = when (this) {
        AIRCRAFT -> orientation == CraftOrientation.HORIZONTAL
        ALL -> true
        NONE -> false
    }
}
