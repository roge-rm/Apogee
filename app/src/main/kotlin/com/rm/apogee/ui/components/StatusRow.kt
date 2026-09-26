package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Paragliding
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rm.apogee.core.world.ServerMessage
import com.rm.apogee.game.HudState
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha

/** What the status chips' details can do. */
class StatusActions(
    val crew: CrewActions = CrewActions(),
    val onUndock: (Int) -> Unit = {},
    val onFound: (Boolean) -> Unit = {},
    val onRefuel: (Boolean) -> Unit = {},
    val onUnload: (Boolean) -> Unit = {},
    val onRefine: (ServerMessage.BaseStatus, Boolean) -> Unit = { _, _ -> },
    val onDockPilot: (String) -> Unit = {},
)

/** One chip: its picture, what it reads, its colour, whether it is an alarm, and what it opens. */
private class StatusChip(
    val key: String,
    val icon: ImageVector,
    val text: String,
    val colour: Color,
    val danger: Boolean,
    val opens: String?,
)

/**
 * The craft's state in a line of small chips under the flight strip, each
 * there only while it means something: power low or gone, out of touch, the
 * chute, parts running hot or strained or hurt, who is aboard, what it is
 * docked to, the base it is at, and who flies a shared craft. Tapped, each
 * opens what lies behind it - the parts, the crew, the joints, the base -
 * over the view, until tapped away.
 *
 * The chips fade with the rest of the controls, all but the alarms.
 */
@Composable
fun StatusRow(
    hud: HudState,
    chute: String?,
    actions: StatusActions,
    /** The controls' opacity just now: faded or not. */
    fadedAlpha: Float,
    /** How tall a detail may grow before it scrolls. */
    detailHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val telemetry = hud.telemetry
    val power = hud.power
    val base = hud.nearBase
    val service = hud.baseService
    val chips = buildList {
        if (power != null) {
            if (!power.powered) add(StatusChip("power", Icons.Filled.BatteryAlert, "NO POWER", ApogeeColors.Danger, true, null))
            else if (power.low) add(StatusChip("power", Icons.Filled.BatteryAlert, percent(power.share.toDouble()), ApogeeColors.Caution, false, null))
            if (power.needsSignal && power.powered && power.blocked == "NO SIGNAL") {
                add(StatusChip("signal", Icons.Filled.SignalWifiOff, "NO SIGNAL", ApogeeColors.Danger, true, null))
            }
        }
        if (chute != null) add(StatusChip("chute", Icons.Filled.Paragliding, chute, if (chute == "ARMED") ApogeeColors.Data else ApogeeColors.Prograde, false, null))
        if (telemetry.overheating) {
            add(StatusChip("heat", Icons.Filled.Whatshot, percent(telemetry.heat), severity(telemetry.heat), telemetry.heat >= 0.9, HudState.STATUS_PARTS))
        }
        if (telemetry.straining) {
            add(StatusChip("load", Icons.Filled.Warning, percent(telemetry.structure), severity(telemetry.structure), telemetry.structure >= 0.9, HudState.STATUS_PARTS))
        }
        if (telemetry.hurt) {
            val text = buildString {
                if (telemetry.damaged > 0) append(telemetry.damaged)
                if (telemetry.lost > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${telemetry.lost} lost")
                }
            }
            add(StatusChip("damage", Icons.Filled.Build, text, if (telemetry.lost > 0) ApogeeColors.Danger else ApogeeColors.Caution, telemetry.lost > 0, HudState.STATUS_PARTS))
        }
        if (!hud.isSuit && hud.crewSeats > 0) {
            val aboard = hud.crew.size
            add(StatusChip("crew", Icons.Filled.Groups, "$aboard/${hud.crewSeats}", if (aboard == 0) ApogeeColors.Caution else ApogeeColors.Data, false, HudState.STATUS_CREW))
        }
        if (hud.joints.isNotEmpty()) {
            add(StatusChip("dock", Icons.Filled.Link, "${hud.joints.size}", ApogeeColors.Data, false, HudState.STATUS_DOCK))
        }
        val serviceBusy = service != null && (service.founded || service.canRefuel || service.refuelling || service.canUnload || service.unloading)
        if (base != null || serviceBusy) {
            val busy = service != null && (service.refuelling || service.unloading)
            val name = base?.name?.take(BASE_NAME)?.uppercase() ?: "BASE"
            add(StatusChip("base", Icons.Filled.Home, name, if (busy) ApogeeColors.Prograde else if (base?.powered == false) ApogeeColors.Caution else ApogeeColors.Data, false, HudState.STATUS_BASE))
        }
        hud.sharedWith?.let { other ->
            add(StatusChip("shared", Icons.Filled.People, pilotLabel(other, hud.sharedPilot), ApogeeColors.Accent, false, HudState.STATUS_SHARED))
        }
    }
    if (chips.isEmpty()) return
    Box(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (chip in chips) Chip(chip, if (chip.danger) 1f else fadedAlpha) {
                val opens = chip.opens ?: return@Chip
                hud.statusOpen = if (hud.statusOpen == opens) null else opens
            }
        }
        val open = hud.statusOpen
        if (open != null && chips.any { it.opens == open }) {
            val close = { hud.statusOpen = null }
            // Over everything, controls included, until tapped away.
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(LocalDensity.current) { CHIP_HEIGHT.roundToPx() + 4.dp.roundToPx() }),
                onDismissRequest = close,
                properties = PopupProperties(focusable = true),
            ) {
                when (open) {
                    HudState.STATUS_PARTS -> PartList(telemetry, PART_LIST_WIDTH, detailHeight, close)
                    HudState.STATUS_CREW -> Detail { CrewList(hud, actions.crew) }
                    HudState.STATUS_DOCK -> Detail { DockingPanel(null, hud.joints, actions.onUndock) }
                    HudState.STATUS_BASE -> Detail {
                        BasePanel(base, service, actions.onFound, actions.onRefuel, onUnload = actions.onUnload, onRefine = actions.onRefine)
                    }
                    HudState.STATUS_SHARED -> Detail {
                        SharedChooser(hud.sharedWith ?: "", hud.sharedPilot, { actions.onDockPilot(it); hud.statusOpen = null })
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(chip: StatusChip, alpha: Float, onTap: () -> Unit) {
    Row(
        Modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(if (chip.danger) chip.colour.alpha(0.3f) else Color.Black.alpha(ApogeeAlpha.SCRIM))
            .clickable(onClick = onTap)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(chip.icon, contentDescription = chip.key, tint = chip.colour, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(chip.text, style = TelemetryTextStyle, color = chip.colour, maxLines = 1)
    }
}

/** A detail opened from a chip: on a near-solid panel, read over whatever is behind. */
@Composable
private fun Detail(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = ApogeeColors.Surface.alpha(0.92f),
    ) {
        Box(Modifier.padding(10.dp)) { content() }
    }
}

private val CHIP_HEIGHT = 26.dp
private val PART_LIST_WIDTH = 260.dp

/** A base's name is cut to this on its chip. */
private const val BASE_NAME = 12
