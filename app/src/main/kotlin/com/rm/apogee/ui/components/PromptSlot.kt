package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.HudState
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha

/** What the prompts do. */
class PromptActions(
    val onJoin: () -> Unit = {},
    val onFound: (Boolean) -> Unit = {},
    val onBoard: () -> Unit = {},
    /** Take hold of the nearest ladder (true), or let go. */
    val onGrab: (Boolean) -> Unit = {},
)

/**
 * The top middle of the screen, only for what's asking for something right now. In flight that's
 * the next burn counting down, the landing coming up, lining up to dock, and the one-tap chances:
 * JOIN two modules, FOUND BASE where the craft stands, BOARD a craft, and GRAB or LET GO of a
 * ladder. On the map it's planning: the transfer window, the burn's editor, and which survey shows.
 * There's nothing at all while there's nothing to do.
 */
@Composable
fun PromptSlot(
    hud: HudState,
    burnActions: BurnActions,
    actions: PromptActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        hud.banner?.let { banner ->
            // A few seconds, then gone.
            androidx.compose.runtime.LaunchedEffect(banner.id) {
                kotlinx.coroutines.delay(BANNER_MS)
                if (hud.banner?.id == banner.id) hud.banner = null
            }
            Banner(banner)
        }
        BurnPanel(
            hud.burn, hud.landing, hud.mapMode, hud.autopilotNote, burnActions,
            window = hud.window, align = Alignment.CenterHorizontally,
        )
        if (hud.mapMode) {
            // A surveyed world's ore or water on the map. Tap round ORE, H2O and off.
            if (hud.surveyedHere) SurveyToggle(hud)
            return@Column
        }
        hud.dock?.let { DockingPanel(it, emptyList(), {}) }
        val power = hud.power
        val service = hud.baseService
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hud.canJoin && !hud.isSuit) Prompt(Icons.Filled.Link, "JOIN", ApogeeColors.Prograde, actions.onJoin)
            if (service?.canFound == true) Prompt(Icons.Filled.Home, "FOUND BASE", ApogeeColors.Accent) { actions.onFound(true) }
            if (hud.isSuit && power != null) {
                if (power.boardable.isNotEmpty()) Prompt(Icons.AutoMirrored.Filled.Login, "BOARD ${power.boardable.uppercase().take(12)}", ApogeeColors.Prograde, actions.onBoard)
                if (power.onLadder) Prompt(Icons.Filled.PanTool, "LET GO", ApogeeColors.Prograde) { actions.onGrab(false) }
                else if (power.canGrab) Prompt(Icons.Filled.PanTool, "GRAB LADDER", ApogeeColors.Accent) { actions.onGrab(true) }
            }
        }
    }
}

/** A feat earned (or a launch refused) across the top, briefly. */
@Composable
private fun Banner(banner: HudState.Banner) {
    val colour = if (banner.good) ApogeeColors.Prograde else ApogeeColors.Danger
    Column(
        Modifier
            .clip(RoundedCornerShape(Dimens.CornerPanel))
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(banner.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colour, maxLines = 1)
        if (banner.detail.isNotEmpty()) {
            Text(banner.detail, style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.BODY), maxLines = 2)
        }
    }
}

private const val BANNER_MS = 4_000L

/** One chance to take, solid so it reads against a bright sky as well as the ground. */
@Composable
private fun Prompt(icon: ImageVector, text: String, colour: Color, onTap: () -> Unit) {
    val ink = Color(0xFF0C1824)
    Row(
        Modifier
            .clip(RoundedCornerShape(Dimens.CornerActionBar))
            .background(colour.alpha(0.88f))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
    }
}

@Composable
private fun SurveyToggle(hud: HudState) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
            .clickable { hud.mapResource = when (hud.mapResource) { "ORE" -> "H2O"; "H2O" -> "OFF"; else -> "ORE" } }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("SURVEY", style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
        Spacer(Modifier.width(6.dp))
        Text(
            hud.mapResource,
            style = TelemetryTextStyle,
            color = when (hud.mapResource) {
                "ORE" -> Color(0xFFFF9E40)
                "H2O" -> Color(0xFF59CCFF)
                else -> Color.White.alpha(ApogeeAlpha.SECONDARY)
            },
        )
    }
}

/** What's showing in the slot, as a key. When it changes, the controls wake up. */
fun promptKey(hud: HudState): String = buildString {
    hud.burn?.let { append("burn").append(if (it.startsIn <= 0.0) "now" else "") }
    hud.landing?.let { append("land") }
    if (hud.dock != null) append("dock")
    if (hud.canJoin) append("join")
    if (hud.baseService?.canFound == true) append("found")
    hud.power?.let { if (it.boardable.isNotEmpty()) append("board"); if (it.canGrab) append("grab") }
    if (hud.autopilotNote.isNotEmpty()) append(hud.autopilotNote)
    hud.banner?.let { append(it.id) }
}
