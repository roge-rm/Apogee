package com.rm.apogee.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Apogee's palette. Reference these, never copy them. */
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
    // The only colours that carry meaning, and never alone: an icon or label changes too. With
    // [colourBlind] they're from the Okabe-Ito set, told apart without red against green.

    /** Signal colours told apart by most colour blindness. A setting; screens follow as it changes. */
    var colourBlind by mutableStateOf(false)

    /** Prograde / nominal / go. */
    val Prograde get() = if (colourBlind) Color(0xFF7CC4FF) else Color(0xFF7CFFB2)

    /** Retrograde / inverted reference. */
    val Retrograde get() = if (colourBlind) Color(0xFFF0A330) else Color(0xFFFF9E80)

    /** Caution: low fuel, high dynamic pressure, getting near limits. */
    val Caution get() = if (colourBlind) Color(0xFFF0E442) else Color(0xFFFFD37F)

    /** Danger: overheating, structural failure, a collision about to happen. */
    val Danger get() = if (colourBlind) Color(0xFFE8642A) else Color(0xFFFF6B6B)

    /** Number readouts and telemetry. */
    val Data = Color(0xFF7FD8FF)
}

/**
 * The white-alpha ladder. Most of the UI is white at one of these alphas over the gradient. Use
 * these names instead of literal alphas.
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
