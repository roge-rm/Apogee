package com.rm.apogee.audio

/**
 * The sounds the native engine can make, by number - the same list as
 * `cpp/synth/recipes.h`, which a test holds this to.
 */
object Recipes {
    // Held, for as long as the scene asks for them.
    const val ROCKET = 1
    const val JET = 2
    const val PROP = 3
    const val AIRFLOW = 4
    const val REENTRY = 5
    const val WIND = 6
    const val RAIN = 7
    const val CABIN = 8
    const val STRESS = 9
    const val FIRE = 10
    const val ROVER = 11
    const val OUTBOARD = 12
    const val SURF = 13
    const val RCS = 14

    // One-shots.
    const val IMPACT = 50
    const val CRUNCH = 51
    const val TEAR = 52
    const val EXPLOSION = 53
    const val SPLASH = 54
    const val STAGE = 55
    const val THUNDER = 56
    const val CLICK = 57
    const val CAUTION = 58
    const val IGNITION = 59
    const val CHUTE = 60
    const val CLUNK = 61
}

/** What an impact is on, for how it rings: `material` in recipes.h. */
object Materials {
    const val METAL = 0
    const val ROCK = 1
    const val EARTH = 2
    const val SAND = 3
    const val SNOW = 4
    const val WOOD = 5
}

/** Mix buses: `bus` in recipes.h. */
object Buses {
    const val VEHICLE = 0
    const val ENVIRONMENT = 1
    const val IMPACTS = 2
    const val UI = 3
    const val MUSIC = 4
    const val COUNT = 5
}

/** Voice flags: `flag` in recipes.h. */
object VoiceFlags {
    /** Heard through the craft's own structure. */
    const val HULL = 1
}

/** Parameters every voice shares after its recipe's own: `P_*` in synth.h. */
object SharedParams {
    const val COUNT = 9
    const val GAIN = 5
    const val PAN = 6
    const val LOWPASS = 7
    /** Doppler: frequency factor, 0 for none. Engines and wheels. */
    const val PITCH = 8
}
