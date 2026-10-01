package com.rm.apogee.ui.theme

import androidx.compose.ui.unit.dp

/** Layout constants. Dependent ones are worked out from the others, so they change together. */
object Dimens {
    // --- screen scaffolding -------------------------------------------------
    val ScreenPaddingH = 32.dp
    val ScreenPaddingV = 24.dp

    /** Content is capped, not stretched across a wide landscape phone. */
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
    /** Position and size changes. Short so it doesn't feel laggy. */
    const val MOTION_FAST_MS = 180

    /** Fades for readouts and flashes. */
    const val MOTION_FADE_MS = 400
}
