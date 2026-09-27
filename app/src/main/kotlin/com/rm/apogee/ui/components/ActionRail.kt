package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.Looks3
import androidx.compose.material.icons.filled.LooksOne
import androidx.compose.material.icons.filled.LooksTwo
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.apogee.core.world.DrillState
import com.rm.apogee.game.HudState
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

/** What the rail's switches do. */
class RailActions(
    val onBrakes: () -> Unit = {},
    val onReverse: () -> Unit = {},
    val onRcs: () -> Unit = {},
    val onDeploy: () -> Unit = {},
    val onDrill: () -> Unit = {},
    val onRefine: () -> Unit = {},
    val onJump: () -> Unit = {},
    val onFlag: () -> Unit = {},
    val onDive: () -> Unit = {},
    val onRise: () -> Unit = {},
    val onHold: () -> Unit = {},
    val onFlaps: () -> Unit = {},
    /** Switch an action group, 1 to 3. */
    val onGroup: (Int) -> Unit = {},
    /** Wind the winch in, or hold it. */
    val onWinch: () -> Unit = {},
)

/** One switch on the rail: its picture, its word, and how it's set. */
private class RailSwitch(val icon: ImageVector, val caption: String, val tint: Color, val on: Boolean, val onTap: () -> Unit)

/**
 * The craft's switches, beside the throttle: brakes and reverse on something with wheels, flaps,
 * the thrusters, sun wings and dishes, drills and converters, ballast to dive, rise and hold a
 * depth, the action groups the craft uses, the winch once it's hooked on, and on EVA, jumping and
 * planting a flag. Only the ones the craft has are shown. Each is a small
 * picture with its word under it, lit while on. It's green when working, amber when on but not
 * getting anywhere (with the reason in place of the word), and grey when off. Past [perColumn] they
 * go two across.
 */
@Composable
fun ActionRail(
    hud: HudState,
    actions: RailActions,
    perColumn: Int,
    modifier: Modifier = Modifier,
    /** Height above the ground under which someone on EVA is on their feet. */
    groundedBelow: Double = 3.0,
) {
    val idle = Color.White.alpha(ApogeeAlpha.SECONDARY)
    val switches = buildList {
        if (hud.hasWheels) {
            add(RailSwitch(Icons.Filled.SwapVert, "REV", if (hud.reverse) ApogeeColors.Caution else idle, hud.reverse, actions.onReverse))
            add(RailSwitch(Icons.Filled.PanTool, "BRK", if (hud.brakes) ApogeeColors.Danger else idle, hud.brakes, actions.onBrakes))
        }
        if (hud.hasFlaps) {
            add(RailSwitch(Icons.Filled.FlightLand, "FLAPS", if (hud.flaps) ApogeeColors.Accent else idle, hud.flaps, actions.onFlaps))
        }
        if (hud.hasRcs) {
            val left = hud.rcsLeft
            val caption = if (hud.rcsArmed && left != null) "RCS ${(left * 100).roundToInt()}%" else "RCS"
            val tint = when {
                !hud.rcsArmed -> idle
                left != null && left < 0.1f -> ApogeeColors.Danger
                else -> ApogeeColors.Accent
            }
            add(RailSwitch(Icons.Filled.Air, caption, tint, hud.rcsArmed, actions.onRcs))
        }
        val power = hud.power
        if (hud.hasFoldouts) {
            val out = power?.deployed == true
            add(RailSwitch(Icons.Filled.SolarPower, "DEPLOY", if (out) ApogeeColors.Prograde else idle, out, actions.onDeploy))
        }
        if (hud.hasDrill) {
            val on = power?.drilling == true
            val digging = power?.drillState == DrillState.DIGGING
            val tint = when {
                !on -> idle
                digging -> ApogeeColors.Prograde
                else -> ApogeeColors.Caution
            }
            add(RailSwitch(Icons.Filled.Hardware, if (on && !digging) drillNote(power.drillState) else "DRILL", tint, on, actions.onDrill))
        }
        if (hud.hasConverter) {
            val on = power?.refining == true
            add(RailSwitch(Icons.Filled.Science, "REFINE", if (on) ApogeeColors.Prograde else idle, on, actions.onRefine))
        }
        if (power != null && power.ballast >= 0f) {
            val full = "${(power.ballast * 100).roundToInt()}%"
            val diving = power.ballastMode > 0
            val rising = power.ballastMode < 0
            val holding = power.holdingDepth >= 0f
            add(RailSwitch(Icons.Filled.ArrowDownward, if (diving) "DIVE $full" else "DIVE", if (diving) ApogeeColors.Accent else idle, diving, actions.onDive))
            add(RailSwitch(Icons.Filled.ArrowUpward, if (rising) "RISE $full" else "RISE", if (rising) ApogeeColors.Accent else idle, rising, actions.onRise))
            add(RailSwitch(
                Icons.Filled.VerticalAlignCenter,
                if (holding) "${power.holdingDepth.roundToInt()} m" else "HOLD",
                if (holding) ApogeeColors.Prograde else idle, holding, actions.onHold,
            ))
        }
        for (group in hud.groupsUsed) {
            // Left alone or switched on, its parts run as they would. Switched off, they don't.
            val off = power?.group(group) == -1
            val icon = when (group) { 1 -> Icons.Filled.LooksOne; 2 -> Icons.Filled.LooksTwo; else -> Icons.Filled.Looks3 }
            add(RailSwitch(icon, if (off) "$group OFF" else "GROUP $group", if (off) idle else ApogeeColors.Prograde, !off) { actions.onGroup(group) })
        }
        if (power != null && power.hooked) {
            val reeling = power.reel > 0
            val tint = when {
                !reeling -> idle
                power.taut -> ApogeeColors.Prograde
                else -> ApogeeColors.Accent
            }
            add(RailSwitch(Icons.Filled.Cable, if (reeling) "REEL IN" else "WINCH", tint, reeling, actions.onWinch))
        }
        if (hud.isSuit && hud.telemetry.heightAboveGround < groundedBelow) {
            add(RailSwitch(Icons.Filled.KeyboardDoubleArrowUp, "JUMP", idle, false, actions.onJump))
            add(RailSwitch(Icons.Filled.Flag, "FLAG", idle, false, actions.onFlag))
        }
    }
    if (switches.isEmpty()) return
    val columns = switches.chunked(perColumn.coerceAtLeast(1))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(RAIL_GAP), verticalAlignment = Alignment.Bottom) {
        for (column in columns) {
            Column(verticalArrangement = Arrangement.spacedBy(RAIL_GAP)) {
                for (s in column) RailButton(s)
            }
        }
    }
}

@Composable
private fun RailButton(s: RailSwitch) {
    Column(
        Modifier
            .size(RAIL_BUTTON)
            .clip(RoundedCornerShape(Dimens.CornerSmall))
            .background(if (s.on) s.tint.alpha(0.28f) else Color.Black.alpha(ApogeeAlpha.SCRIM))
            .clickable(onClick = s.onTap)
            .padding(top = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(s.icon, contentDescription = s.caption, tint = s.tint, modifier = Modifier.size(20.dp))
        Text(
            s.caption,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            color = s.tint,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Why a drill that's switched on isn't digging, in a word. */
private fun drillNote(state: DrillState?): String = when (state) {
    DrillState.EXTENDING -> "BIT DOWN"
    DrillState.NO_GROUND -> "NO GROUND"
    DrillState.MOVING -> "MOVING"
    DrillState.FULL -> "FULL"
    DrillState.BARREN -> "BARREN"
    DrillState.NO_POWER -> "NO POWER"
    else -> "DRILL"
}

/** One switch, square, a thumb's width. */
val RAIL_BUTTON = 44.dp
private val RAIL_GAP = 6.dp
