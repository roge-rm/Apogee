package com.rm.apogee.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Layout constants, with the dependent ones *worked out* from the others instead of written out
 * again.
 *
 * This pattern is worth keeping strictly. When a control's size changes, every measurement that
 * should follow it changes with it, instead of drifting apart until someone notices the HUD doesn't
 * line up any more.
 */
object Dimens {
    // --- screen scaffolding -------------------------------------------------
    val ScreenPaddingH = 32.dp
    val ScreenPaddingV = 24.dp

    /**
     * Content is capped instead of stretched. A landscape phone is about 2340 px wide, and a
     * full-width button at that size is just a bar of colour.
     */
    val MenuContentMaxWidth = 340.dp
    val PanelContentMaxWidth = 560.dp

    // --- HUD control strip --------------------------------------------------
    val HudIconSize = 44.dp
    val HudIconSidePadding = 2.dp
    val HudGroupGap = 10.dp
    const val HUD_ICONS_PER_ROW = 5

    /** Worked out: the main action bar spans exactly the icon row under it. */
    val HudActionBarWidth =
        (HudIconSize + HudIconSidePadding * 2) * HUD_ICONS_PER_ROW + HudGroupGap * 2

    // --- corner treatment ---------------------------------------------------
    val CornerPanel = 12.dp
    val CornerActionBar = 24.dp
    val CornerSmall = 8.dp
    val CornerTight = 6.dp

    // --- motion -------------------------------------------------------------
    /** Position and size changes. Short enough not to feel laggy under a thumb. */
    const val MOTION_FAST_MS = 180

    /** Fades for readouts and flashes. */
    const val MOTION_FADE_MS = 400
}
