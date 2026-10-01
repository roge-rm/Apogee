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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
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
    /** Hook the winch's line onto what's in front of it, or let it go. */
    val onHook: () -> Unit = {},
    val onRelease: () -> Unit = {},
    /** Roll a capsized craft back upright. */
    val onRight: () -> Unit = {},
    /** Climb out of the water, or off the top of a ladder, onto a deck. */
    val onClimbOut: () -> Unit = {},
)

/**
 * The top middle of the screen, for whatever needs you now: burns, landing, approach, docking and
 * the one-tap prompts in flight; the window, burn editor and survey layer on the map. Empty when
 * there's nothing to do.
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
            window = hud.window, align = Alignment.CenterHorizontally, plannable = hud.mapPlannable,
        )
        if (hud.mapMode) {
            // Ore, water or currents on the map. Taps cycle through them and off.
            if (hud.surveyedHere || hud.currentsHere) SurveyToggle(hud)
            return@Column
        }
        hud.approach?.let { ApproachChip(it) }
        hud.dock?.let { DockingPanel(it, emptyList(), {}) }
        val power = hud.power
        val service = hud.baseService
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hud.canJoin && !hud.isSuit) Prompt(Icons.Filled.Link, "JOIN", ApogeeColors.Prograde, actions.onJoin)
            if (service?.canFound == true) Prompt(Icons.Filled.Home, "FOUND BASE", ApogeeColors.Accent) { actions.onFound(true) }
            if (hud.isSuit && power != null) {
                if (power.boardable.isNotEmpty()) Prompt(Icons.AutoMirrored.Filled.Login, "BOARD ${power.boardable.uppercase().take(12)}", ApogeeColors.Prograde, actions.onBoard)
                else if (power.climbOnto.isNotEmpty()) Prompt(Icons.Filled.Upgrade, "CLIMB OUT", ApogeeColors.Prograde, actions.onClimbOut)
                if (power.onLadder) Prompt(Icons.Filled.PanTool, "LET GO", ApogeeColors.Prograde) { actions.onGrab(false) }
                else if (power.canGrab) Prompt(Icons.Filled.PanTool, "GRAB LADDER", ApogeeColors.Accent) { actions.onGrab(true) }
            }
            if (power != null && power.canRight) Prompt(Icons.Filled.Refresh, "ROLL UPRIGHT", ApogeeColors.Caution, actions.onRight)
            if (power != null && power.hasWinch) {
                if (power.hooked) Prompt(Icons.Filled.LinkOff, "RELEASE", ApogeeColors.Caution, actions.onRelease)
                else if (power.canHook.isNotEmpty()) Prompt(Icons.Filled.Anchor, "HOOK ${power.canHook.uppercase().take(12)}", ApogeeColors.Accent, actions.onHook)
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

/** One prompt, solid so it reads against sky and ground. */
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

/**
 * The runway approach: which end, distance, the four slope lights (two white and two red on the
 * slope), high or low by how much, and how far off the centreline.
 */
@Composable
private fun ApproachChip(cue: com.rm.apogee.core.world.Approach.Cue) {
    val slope = com.rm.apogee.core.world.Approach.GLIDE_DEGREES
    val off = cue.angle - slope
    val (word, colour) = when {
        kotlin.math.abs(off) <= com.rm.apogee.core.world.Approach.ON_SLOPE -> "ON SLOPE" to ApogeeColors.Prograde
        off > 0 -> "HIGH ${kotlin.math.abs(cue.aboveSlope).toInt()} m" to ApogeeColors.Caution
        else -> "LOW ${kotlin.math.abs(cue.aboveSlope).toInt()} m" to ApogeeColors.Danger
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(Dimens.CornerPanel))
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (cue.sense > 0) "RWY 09" else "RWY 27", style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
        Text(if (cue.toThreshold > 0) formatDistance(cue.toThreshold) else "OVER", style = TelemetryTextStyle, color = ApogeeColors.Data)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            // As seen beside the runway, lowest setting outermost on the left.
            for (setAt in com.rm.apogee.core.world.Approach.LIGHTS) {
                val white = com.rm.apogee.core.world.Approach.white(cue.angle, setAt)
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (white) Color.White else Color(0xFFFF3B30)),
                )
            }
        }
        Text(word, style = TelemetryTextStyle, fontWeight = FontWeight.Bold, color = colour)
        val side = cue.offCentre
        if (kotlin.math.abs(side) >= CENTRED) {
            Text(
                "${kotlin.math.abs(side).toInt()} m ${if (side > 0) "RIGHT" else "LEFT"}",
                style = TelemetryTextStyle,
                color = if (kotlin.math.abs(side) > WIDE_OF_CENTRE) ApogeeColors.Caution else ApogeeColors.Data,
            )
        }
    }
}

/** Metres off the centreline: under [CENTRED] reads as centred, over [WIDE_OF_CENTRE] warns. */
private const val CENTRED = 5.0
private const val WIDE_OF_CENTRE = 25.0

@Composable
private fun SurveyToggle(hud: HudState) {
    // Only what this world has.
    val layers = buildList {
        if (hud.surveyedHere) { add("ORE"); add("H2O") }
        if (hud.currentsHere) add("CURRENTS")
        add("OFF")
    }
    val shown = if (hud.mapResource in layers) hud.mapResource else "OFF"
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
            .clickable { hud.mapResource = layers[(layers.indexOf(shown) + 1) % layers.size] }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("MAP", style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
        Spacer(Modifier.width(6.dp))
        Text(
            shown,
            style = TelemetryTextStyle,
            color = when (shown) {
                "ORE" -> Color(0xFFFF9E40)
                "H2O" -> Color(0xFF59CCFF)
                "CURRENTS" -> Color(0xFF59E0D0)
                else -> Color.White.alpha(ApogeeAlpha.SECONDARY)
            },
        )
    }
}

/** What's showing in the slot, as a key. When it changes, the controls wake up. */
fun promptKey(hud: HudState): String = buildString {
    hud.burn?.let { append("burn").append(if (it.startsIn <= 0.0) "now" else "") }
    hud.landing?.let { append("land") }
    if (hud.approach != null) append("approach")
    if (hud.dock != null) append("dock")
    if (hud.canJoin) append("join")
    if (hud.baseService?.canFound == true) append("found")
    hud.power?.let {
        if (it.boardable.isNotEmpty()) append("board")
        if (it.climbOnto.isNotEmpty()) append("climb")
        if (it.canGrab) append("grab")
        if (it.hooked) append("hooked") else if (it.canHook.isNotEmpty()) append("hook")
    }
    if (hud.autopilotNote.isNotEmpty()) append(hud.autopilotNote)
    hud.banner?.let { append(it.id) }
}
