package com.rm.apogee.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.FlightTelemetry
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

/**
 * The parts worth a look, worst first: each one's health, heat and load. This is what the damage,
 * heat and load chips open.
 */
@Composable
internal fun PartList(telemetry: FlightTelemetry, width: Dp, height: Dp, onClose: () -> Unit) {
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
                .heightIn(max = height)
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

private val COLUMN = 44.dp

internal fun percent(share: Double): String = "${(share * 100).roundToInt()}%"

internal fun severity(share: Double): Color = if (share >= 0.9) ApogeeColors.Danger else ApogeeColors.Caution

/** Plain below caution, then amber, then red. For health, [share] is what's missing. */
private fun colourFor(share: Double, healthy: Boolean = false): Color = when {
    share >= 0.9 -> ApogeeColors.Danger
    share >= FlightTelemetry.CAUTION || (healthy && share > 0.3) -> ApogeeColors.Caution
    else -> ApogeeColors.Data
}
