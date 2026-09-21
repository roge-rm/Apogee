package com.rm.apogee.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The single definition of Apogee's palette.
 *
 * Defined once, on purpose. The app this borrows its look from copy-pastes its
 * three colour constants into four separate files, and they have already begun
 * to drift; everything here is referenced, never duplicated.
 */
object ApogeeColors {
    /** Backdrop gradient, top to bottom: deep navy into plum. */
    val BackdropTop = Color(0xFF101830)
    val BackdropBottom = Color(0xFF241735)

    /** The one accent. Lavender, used for emphasis, selection and focus. */
    val Accent = Color(0xFFB39DFF)
    val AccentDim = Color(0xFF8A78D0)

    /** Opaque panel fill for anything that must stay readable over the 3D scene. */
    val Surface = Color(0xFF17182E)
    val SurfaceRaised = Color(0xFF1F2140)

    // --- flight signal colours ---------------------------------------------
    // A HUD needs meanings, not decoration. These are the only colours allowed
    // to carry information, and none of them is ever the sole carrier of it -
    // an icon or a label changes too (see FirePill's precedent).

    /** Prograde / nominal / go. */
    val Prograde = Color(0xFF7CFFB2)

    /** Retrograde / inverted reference. */
    val Retrograde = Color(0xFFFF9E80)

    /** Caution: low fuel, high dynamic pressure, approaching limits. */
    val Caution = Color(0xFFFFD37F)

    /** Danger: overheating, structural failure, collision imminent. */
    val Danger = Color(0xFFFF6B6B)

    /** Numeric readouts and telemetry. */
    val Data = Color(0xFF7FD8FF)
}

/**
 * The white-alpha ladder, which is the actual load-bearing part of this design
 * language. Almost nothing in the UI is a distinct colour - it is white at a
 * chosen transparency over the gradient, and keeping those steps consistent is
 * what makes unrelated screens read as one system.
 *
 * Use these names rather than literal alphas so a change propagates.
 */
object ApogeeAlpha {
    /** Primary text and active icons. */
    const val PRIMARY = 1.0f

    /** Body copy. */
    const val BODY = 0.85f

    /** Secondary text, inactive icons. */
    const val SECONDARY = 0.75f

    /** Subtitles, unselected tabs. */
    const val SUBTITLE = 0.60f

    /** Focused field borders. */
    const val BORDER = 0.35f

    /** Unselected selection outlines. */
    const val BORDER_FAINT = 0.30f

    /** Filled control backgrounds: nudge buttons, slider tracks. */
    const val CONTROL_FILL = 0.18f

    /** Dividers. */
    const val DIVIDER = 0.12f

    /** Chip backgrounds, disabled fills. */
    const val FILL_FAINT = 0.06f

    // --- scrims, for panels sitting over the rendered world ----------------

    /** Light scrim: transient readouts that must not hide the scene. */
    const val SCRIM_LIGHT = 0.45f

    /** Standard scrim: HUD panels. */
    const val SCRIM = 0.65f

    /** Heavy scrim: modal content over the scene. */
    const val SCRIM_HEAVY = 0.82f
}

fun Color.alpha(value: Float): Color = copy(alpha = value)
