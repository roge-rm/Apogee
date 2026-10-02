package com.rm.apogee.ui.screens

/**
 * Every screen in the app. An enum, a `when` and a [androidx.activity.compose.BackHandler] do the
 * navigation; the graph is shallow and flight needs back to do nothing.
 */
enum class AppScreen {
    MENU,
    PLAY,
    HOST_GAME,
    JOIN_GAME,
    SETTINGS,
    ABOUT,

    /** The builder (VAB). */
    BUILDER,

    /** Pick a craft and a site, and launch it in place of the last one. */
    QUICK_LAUNCH,

    /** The player's craft in the solo world: fly one, reset it, or remove it. */
    RESUME_FLIGHT,

    /** The player's crew in the solo world, and the ones who were lost. */
    CREW,

    /** A career's program: the tech tree, the feats, and the worlds. */
    PROGRAM,

    /** The guided flights. */
    TUTORIALS,

    /** Which controller button does what. */
    CONTROLLER,

    /** The 3D world view. */
    FLIGHT;

    /** Where the back button goes. `null` means "this is the root". */
    val parent: AppScreen?
        get() = when (this) {
            MENU -> null
            PLAY, SETTINGS, ABOUT -> MENU
            HOST_GAME, JOIN_GAME -> PLAY
            BUILDER, QUICK_LAUNCH, RESUME_FLIGHT, CREW, PROGRAM, TUTORIALS -> PLAY
            CONTROLLER -> SETTINGS
            // Flight asks before leaving, so a stray back can't end a flight.
            FLIGHT -> null
        }

    /** Whether the 3D surface should be running while this screen is up. */
    val needsWorldSurface: Boolean
        get() = this == FLIGHT || this == BUILDER
}
