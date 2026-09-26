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
 * The top of the screen, in the middle: only what asks for something now.
 * In flight, the next burn counting down and the landing coming up, lining
 * up to dock, and the one-tap chances - JOIN two modules, FOUND BASE where
 * the craft stands, BOARD a craft, GRAB or LET GO a ladder. On the map,
 * planning: the transfer window, the burn's editor, and which survey shows.
 * Nothing at all while there is nothing to do.
 */
@Composable
fun PromptSlot(
    hud: HudState,
    burnActions: BurnActions,
    actions: PromptActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BurnPanel(
            hud.burn, hud.landing, hud.mapMode, hud.autopilotNote, burnActions,
            window = hud.window, align = Alignment.CenterHorizontally,
        )
        if (hud.mapMode) {
            // A surveyed world's ore or water on the map: tap round ORE, H2O, off.
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

/** One chance to take: solid, so it reads against a bright sky as well as the ground. */
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

/** What is showing in the slot, as a key: when it changes, the controls wake. */
fun promptKey(hud: HudState): String = buildString {
    hud.burn?.let { append("burn").append(if (it.startsIn <= 0.0) "now" else "") }
    hud.landing?.let { append("land") }
    if (hud.dock != null) append("dock")
    if (hud.canJoin) append("join")
    if (hud.baseService?.canFound == true) append("found")
    hud.power?.let { if (it.boardable.isNotEmpty()) append("board"); if (it.canGrab) append("grab") }
    if (hud.autopilotNote.isNotEmpty()) append(hud.autopilotNote)
}
