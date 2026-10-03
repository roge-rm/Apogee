package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Looks3
import androidx.compose.material.icons.filled.Lightbulb
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
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    /** Hold the craft still where it is with its keeper core, or stop. */
    val onStationKeep: () -> Unit = {},
    /** Lights on or off, and set to off, on or by themselves. */
    val onLights: () -> Unit = {},
    val onLightMode: (com.rm.apogee.core.part.LightMode) -> Unit = {},
)

/** One switch on the rail: its picture, its word, how it's set, and what a long press does. */
private class RailSwitch(
    val icon: ImageVector, val caption: String, val tint: Color, val on: Boolean, val onTap: () -> Unit,
    val onLongPress: (() -> Unit)? = null,
)

/**
 * The craft's switches beside the throttle. Only the ones the craft has are shown. Green when
 * working, amber when on but stuck (the reason replaces the word), grey when off. Past
 * [perColumn] they go two across.
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
    var lightPicker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val switches = buildList {
        if (hud.hasWheels) {
            add(RailSwitch(Icons.Filled.SwapVert, "REV", if (hud.reverse) ApogeeColors.Caution else idle, hud.reverse, actions.onReverse))
            add(RailSwitch(Icons.Filled.PanTool, "BRK", if (hud.brakes) ApogeeColors.Danger else idle, hud.brakes, actions.onBrakes))
        }
        if (hud.hasFlaps) {
            add(RailSwitch(Icons.Filled.FlightLand, "FLAPS", if (hud.flaps) ApogeeColors.Accent else idle, hud.flaps, actions.onFlaps))
        }
        // No jetpack in the water. Swimming does it.
        if (hud.hasRcs && !(hud.isSuit && hud.power?.swimming == true)) {
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
        if (power != null && power.lamps) {
            val mode = power.lights
            val lit = mode != com.rm.apogee.core.part.LightMode.OFF
            add(RailSwitch(
                if (lit) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                when (mode) {
                    com.rm.apogee.core.part.LightMode.OFF -> "LIGHTS"
                    com.rm.apogee.core.part.LightMode.ON -> "LIGHTS ON"
                    com.rm.apogee.core.part.LightMode.AUTO -> "AUTO"
                },
                if (lit) ApogeeColors.Accent else idle, lit, actions.onLights,
                onLongPress = { lightPicker = true },
            ))
        }
        if (power != null && power.hasKeeper) {
            val keeping = power.keeping
            add(RailSwitch(Icons.Filled.GpsFixed, if (keeping) "HOLDING" else "STATION", if (keeping) ApogeeColors.Prograde else idle, keeping, actions.onStationKeep))
        }
        if (power != null && power.ballast >= 0f) {
            val full = "${(power.ballast * 100).roundToInt()}%"
            val diving = power.ballastMode > 0
            val rising = power.ballastMode < 0
            val holding = power.holdingDepth >= 0f
            // Up in the air on gas cells, the same buttons work the ballonets.
            val sink = if (power.lift >= 0f && hud.telemetry.depth <= 0.0) "SINK" else "DIVE"
            // Someone swimming has no tanks to be full.
            val tanks = if (hud.isSuit) "" else " $full"
            add(RailSwitch(Icons.Filled.ArrowDownward, if (diving) "$sink$tanks" else sink, if (diving) ApogeeColors.Accent else idle, diving, actions.onDive))
            add(RailSwitch(Icons.Filled.ArrowUpward, if (rising) "RISE$tanks" else "RISE", if (rising) ApogeeColors.Accent else idle, rising, actions.onRise))
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
            add(RailSwitch(icon, if (off) "$group OFF" else "GROUP $group", if (off) idle else ApogeeColors.Prograde, !off, { actions.onGroup(group) }))
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
        // Nothing to jump off or plant a flag in while swimming.
        if (hud.isSuit && hud.telemetry.heightAboveGround < groundedBelow && power?.swimming != true) {
            add(RailSwitch(Icons.Filled.KeyboardDoubleArrowUp, "JUMP", idle, false, actions.onJump))
            add(RailSwitch(Icons.Filled.Flag, "FLAG", idle, false, actions.onFlag))
        }
    }
    if (switches.isEmpty()) return
    if (lightPicker) LightPicker(hud.power?.lights, onPick = { actions.onLightMode(it); lightPicker = false }, onDismiss = { lightPicker = false })
    val columns = switches.chunked(perColumn.coerceAtLeast(1))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(RAIL_GAP), verticalAlignment = Alignment.Bottom) {
        for (column in columns) {
            Column(verticalArrangement = Arrangement.spacedBy(RAIL_GAP)) {
                for (s in column) RailButton(s)
            }
        }
    }
}

/** The light switch's three settings, from a long press. */
@Composable
private fun LightPicker(current: com.rm.apogee.core.part.LightMode?, onPick: (com.rm.apogee.core.part.LightMode) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Popup(
        alignment = Alignment.BottomStart,
        offset = androidx.compose.ui.unit.IntOffset(0, -260),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(Dimens.CornerSmall)).background(Color.Black.alpha(ApogeeAlpha.SCRIM)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((mode, word) in listOf(
                com.rm.apogee.core.part.LightMode.OFF to "OFF",
                com.rm.apogee.core.part.LightMode.ON to "ON",
                com.rm.apogee.core.part.LightMode.AUTO to "AUTO",
            )) {
                val picked = mode == current
                Text(
                    word,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (picked) ApogeeColors.Accent else Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Dimens.CornerSmall))
                        .background(if (picked) ApogeeColors.Accent.alpha(0.28f) else Color.Transparent)
                        .clickable { onPick(mode) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RailButton(s: RailSwitch) {
    Column(
        Modifier
            .size(RAIL_BUTTON)
            .clip(RoundedCornerShape(Dimens.CornerSmall))
            .background(if (s.on) s.tint.alpha(0.28f) else Color.Black.alpha(ApogeeAlpha.SCRIM))
            .then(
                if (s.onLongPress != null) Modifier.combinedClickable(onClick = s.onTap, onLongClick = s.onLongPress)
                else Modifier.clickable(onClick = s.onTap),
            )
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
