package com.rm.apogee.ui.screens

/**
 * Every screen in the app.
 *
 * An enum plus a `when` plus a [androidx.activity.compose.BackHandler], rather
 * than a navigation library. A game has a shallow, hand-authored screen graph
 * and needs the back button to do something specific in flight (nothing); a
 * route-and-back-stack abstraction is weight without payoff here.
 */
enum class AppScreen {
    MENU,
    PLAY,
    HOST_GAME,
    JOIN_GAME,
    SETTINGS,
    ABOUT,

    /** The builder (VAB). M2. */
    BUILDER,

    /** The player's craft in the solo world: fly one, reset it, or remove it. */
    RESUME_FLIGHT,

    /** The player's crew in the solo world, and those lost. */
    CREW,

    /** A career's program: the tech tree, the feats, and the worlds. */
    PROGRAM,

    /** The 3D world view. */
    FLIGHT;

    /** Where the back button goes. `null` means "this is the root". */
    val parent: AppScreen?
        get() = when (this) {
            MENU -> null
            PLAY, SETTINGS, ABOUT -> MENU
            HOST_GAME, JOIN_GAME -> PLAY
            BUILDER, RESUME_FLIGHT, CREW, PROGRAM -> PLAY
            // Flight handles its own exit through a confirmation, so that a
            // stray back gesture cannot discard a flight in progress.
            FLIGHT -> null
        }

    /** Whether the 3D surface should be running while this screen is up. */
    val needsWorldSurface: Boolean
        get() = this == FLIGHT || this == BUILDER
}
