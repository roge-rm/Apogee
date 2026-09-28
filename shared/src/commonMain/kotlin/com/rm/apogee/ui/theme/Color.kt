package com.rm.apogee.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The one place Apogee's palette is defined.
 *
 * It's defined once, on purpose. The app this borrows its look from copies its three colour
 * constants into four separate files, and they've already started to drift apart. Everything here
 * is referenced, never copied.
 */
object ApogeeColors {
    /** The backdrop gradient, top to bottom: deep navy into plum. */
    val BackdropTop = Color(0xFF101830)
    val BackdropBottom = Color(0xFF241735)

    /** The one accent. Lavender, used for emphasis, selection and focus. */
    val Accent = Color(0xFFB39DFF)
    val AccentDim = Color(0xFF8A78D0)

    /** Solid panel fill for anything that has to stay readable over the 3D scene. */
    val Surface = Color(0xFF17182E)
    val SurfaceRaised = Color(0xFF1F2140)

    // --- flight signal colours ---------------------------------------------
    //
    // A HUD needs meanings, not decoration. These are the only colours allowed to carry
    // information, and none of them is ever the only thing carrying it. An icon or a label changes
    // too (see how FirePill does it).

    /** Prograde / nominal / go. */
    val Prograde = Color(0xFF7CFFB2)

    /** Retrograde / inverted reference. */
    val Retrograde = Color(0xFFFF9E80)

    /** Caution: low fuel, high dynamic pressure, getting near limits. */
    val Caution = Color(0xFFFFD37F)

    /** Danger: overheating, structural failure, a collision about to happen. */
    val Danger = Color(0xFFFF6B6B)

    /** Number readouts and telemetry. */
    val Data = Color(0xFF7FD8FF)
}

/**
 * The white-alpha ladder, which is really what holds this whole look together. Almost nothing in
 * the UI is a separate colour. It's white at a chosen transparency over the gradient, and keeping
 * those steps consistent is what makes unrelated screens look like one system.
 *
 * Use these names instead of literal alphas, so a change carries through everywhere.
 */
object ApogeeAlpha {
    /** Main text and active icons. */
    const val PRIMARY = 1.0f

    /** Body text. */
    const val BODY = 0.85f

    /** Secondary text, inactive icons. */
    const val SECONDARY = 0.75f

    /** Subtitles, unselected tabs. */
    const val SUBTITLE = 0.60f

    /** Focused field borders. */
    const val BORDER = 0.35f

    /** Unselected selection outlines. */
    const val BORDER_FAINT = 0.30f

    /** Filled control backgrounds: nudge buttons and slider tracks. */
    const val CONTROL_FILL = 0.18f

    /** Dividers. */
    const val DIVIDER = 0.12f

    /** Chip backgrounds and disabled fills. */
    const val FILL_FAINT = 0.06f

    // --- scrims, for panels sitting over the rendered world ----------------

    /** Light scrim: passing readouts that mustn't hide the scene. */
    const val SCRIM_LIGHT = 0.45f

    /** Standard scrim: HUD panels. */
    const val SCRIM = 0.65f

    /** Heavy scrim: modal content over the scene. */
    const val SCRIM_HEAVY = 0.82f
}

fun Color.alpha(value: Float): Color = copy(alpha = value)
