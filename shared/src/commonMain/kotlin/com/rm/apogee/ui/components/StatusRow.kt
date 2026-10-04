package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Paragliding
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Radar
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
import kotlin.math.abs
import kotlin.math.roundToInt

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

/** One chip. [opens] is the detail it opens, if any. */
private class StatusChip(
    val key: String,
    val icon: ImageVector,
    val text: String,
    val colour: Color,
    val danger: Boolean,
    val opens: String?,
    /** A bar after the text, 0..1, or null for none. */
    val bar: Float? = null,
)

/**
 * The craft's state as small chips under the flight strip, each shown only while it matters. A
 * tap opens its detail over the view. Chips fade with the controls, except alarms.
 */
@Composable
fun StatusRow(
    hud: HudState,
    chute: String?,
    actions: StatusActions,
    /** The controls' current opacity. */
    fadedAlpha: Float,
    /** How tall a detail can grow before it scrolls. */
    detailHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val telemetry = hud.telemetry
    val power = hud.power
    val base = hud.nearBase
    val service = hud.baseService
    // Heat shows from a quarter of a part's limit, and stays a moment after it cools under that.
    var heatUntil by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val nowMillis = com.rm.apogee.platform.System.nanoTime() / 1_000_000
    if (telemetry.heat >= HEAT_SHOWN) heatUntil = nowMillis + HEAT_HOLD_MILLIS
    val showHeat = telemetry.heat >= HEAT_SHOWN || nowMillis < heatUntil
    val chips = buildList {
        if (power != null) {
            if (!power.powered) add(StatusChip("power", Icons.Filled.BatteryAlert, "NO POWER", ApogeeColors.Danger, true, null))
            else if (power.low) add(StatusChip("power", Icons.Filled.BatteryAlert, percent(power.share.toDouble()), ApogeeColors.Caution, false, null))
            if (power.needsSignal && power.powered && power.blocked == "NO SIGNAL") {
                add(StatusChip("signal", Icons.Filled.SignalWifiOff, "NO SIGNAL", ApogeeColors.Danger, true, null))
            }
        }
        if (power != null && power.deepCaution) {
            add(StatusChip("crush", Icons.Filled.Compress, "DEPTH LIMIT", if (power.deepDanger) ApogeeColors.Danger else ApogeeColors.Caution, power.deepDanger, HudState.STATUS_PARTS))
        }
        if (power != null && power.coldCaution) {
            add(StatusChip("cold", Icons.Filled.AcUnit, "COLD", if (power.coldDanger) ApogeeColors.Danger else ApogeeColors.Caution, power.coldDanger, null))
        }
        if (power != null && power.findRange >= 0f) {
            val side = power.findBearing.roundToInt()
            val way = when {
                abs(side) < 10 -> "AHEAD"
                side > 0 -> "R$side\u00b0"
                else -> "L${-side}\u00b0"
            }
            // The sonar under the sea, the finder on land.
            val icon = if (power.seabed >= 0f) Icons.Filled.Radar else Icons.Filled.Explore
            add(StatusChip("sonar", icon, "${formatDistance(power.findRange.toDouble())} $way", ApogeeColors.Accent, false, null))
        }
        if (chute != null) add(StatusChip("chute", Icons.Filled.Paragliding, chute, if (chute == "ARMED") ApogeeColors.Data else ApogeeColors.Prograde, false, null))
        if (showHeat) {
            val h = telemetry.heat
            val colour = when {
                h >= 0.9 -> ApogeeColors.Danger
                h >= 0.75 -> ApogeeColors.Caution
                else -> ApogeeColors.Data
            }
            add(StatusChip("heat", Icons.Filled.Whatshot, "HEAT", colour, h >= 0.95, HudState.STATUS_PARTS, bar = h.toFloat().coerceIn(0f, 1f)))
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
            // Over everything until tapped away.
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
        chip.bar?.let { fill ->
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(width = BAR_WIDTH, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.alpha(0.15f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(chip.colour))
            }
        }
    }
}

/** A chip's detail, on a nearly solid panel. */
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

/** Base name length on its chip. */
private const val BASE_NAME = 12

/** The heat bar shows from this share of a part's limit, and for this long after it drops under it. */
private const val HEAT_SHOWN = 0.25
private const val HEAT_HOLD_MILLIS = 3_000L

/** How long a chip's bar is. */
private val BAR_WIDTH = 44.dp
