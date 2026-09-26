package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rm.apogee.game.FlightTelemetry
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

/**
 * Warnings that only appear when they mean something: a part running hot, a
 * joint near its limit, parts hurt or lost, power low or gone, a probe out
 * of touch - and a parachute armed or open.
 * Tap one for the parts behind it.
 */
@Composable
fun CautionChips(
    telemetry: FlightTelemetry,
    expanded: Boolean,
    onToggle: () -> Unit,
    listWidth: Dp = 260.dp,
    modifier: Modifier = Modifier,
    /** The flown craft's parachute: "ARMED" waiting for safe air, "OPEN" on its drogue, "FULL", or null. */
    chute: String? = null,
    /** Its power and link home: flat, low, or a probe out of touch. */
    power: com.rm.apogee.game.HudState.PowerReadout? = null,
) {
    val chips = buildList {
        if (power != null) {
            if (!power.powered) add(Chip("NO POWER", "0%", ApogeeColors.Danger))
            else if (power.low) add(Chip("LOW POWER", percent(power.share.toDouble()), ApogeeColors.Caution))
            if (power.needsSignal && power.powered && power.signal == com.rm.apogee.core.world.Signal.NONE) add(Chip("NO SIGNAL", "", ApogeeColors.Danger))
        }
        if (chute != null) add(Chip("CHUTE", chute, if (chute == "ARMED") ApogeeColors.Data else ApogeeColors.Prograde))
        if (telemetry.overheating) add(Chip("OVERHEAT", percent(telemetry.heat), severity(telemetry.heat)))
        if (telemetry.straining) add(Chip("STRUCTURE", percent(telemetry.structure), severity(telemetry.structure)))
        if (telemetry.hurt) {
            val text = buildString {
                if (telemetry.damaged > 0) append(telemetry.damaged)
                if (telemetry.lost > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${telemetry.lost} lost")
                }
            }
            add(Chip("DAMAGE", text, if (telemetry.lost > 0) ApogeeColors.Danger else ApogeeColors.Caution))
        }
    }
    if (chips.isEmpty() && !expanded) return

    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (chip in chips) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(Dimens.CornerTight))
                        .background(chip.colour.alpha(0.22f))
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(chip.label, style = MaterialTheme.typography.labelSmall, color = chip.colour, maxLines = 1)
                        if (chip.value.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(chip.value, style = TelemetryTextStyle, color = chip.colour, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (expanded) {
            // Over everything, controls included, until tapped away.
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(LocalDensity.current) { 34.dp.roundToPx() }),
                onDismissRequest = onToggle,
                properties = PopupProperties(focusable = true),
            ) {
                PartList(telemetry, listWidth, onToggle)
            }
        }
    }
}

@Composable
private fun PartList(telemetry: FlightTelemetry, width: Dp, onClose: () -> Unit) {
    // The parts worth a look, worst first.
    val shown = telemetry.parts
        .filter { it.health < 0.99 || it.heat > 0.3 || it.load > 0.3 }
        .sortedByDescending { maxOf(1.0 - it.health, it.heat, it.load) }
    val scroll = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = ApogeeColors.Surface.alpha(0.92f),
        modifier = Modifier.width(width).clickable(onClick = onClose),
    ) {
        Column(
            Modifier
                .heightIn(max = 220.dp)
                .verticalScrollbar(scroll)
                .verticalScroll(scroll)
                .padding(10.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text("PART", style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE), modifier = Modifier.weight(1f))
                Header("HP"); Header("HEAT"); Header("LOAD")
            }
            Spacer(Modifier.height(4.dp))
            if (shown.isEmpty()) {
                Text(
                    if (telemetry.lost > 0) "Nothing else hurt" else "All parts sound",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                )
            }
            for (part in shown) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        part.title, style = MaterialTheme.typography.bodySmall, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Cell(percent(part.health), colourFor(1.0 - part.health, healthy = true))
                    Cell(percent(part.heat), colourFor(part.heat))
                    Cell(percent(part.load), colourFor(part.load))
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text, style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
        modifier = Modifier.width(COLUMN), maxLines = 1,
    )
}

@Composable
private fun Cell(text: String, colour: Color) {
    Text(text, style = TelemetryTextStyle, color = colour, modifier = Modifier.width(COLUMN), maxLines = 1)
}

private class Chip(val label: String, val value: String, val colour: Color)

private val COLUMN = 44.dp

private fun percent(share: Double): String = "${(share * 100).roundToInt()}%"

private fun severity(share: Double): Color = if (share >= 0.9) ApogeeColors.Danger else ApogeeColors.Caution

/** Plain below caution, amber, then red; for health, [share] is what is missing. */
private fun colourFor(share: Double, healthy: Boolean = false): Color = when {
    share >= 0.9 -> ApogeeColors.Danger
    share >= FlightTelemetry.CAUTION || (healthy && share > 0.3) -> ApogeeColors.Caution
    else -> ApogeeColors.Data
}
