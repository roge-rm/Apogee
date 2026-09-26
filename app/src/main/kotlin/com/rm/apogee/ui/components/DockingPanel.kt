package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.GameSession
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Docking, on the HUD: lining up - how far, how fast, how far off square,
 * green once the magnets would take it - and a way to let go of each thing
 * the craft is joined to, tapped twice so a thumb brushing it does not
 * undock a station.
 */
@Composable
fun DockingPanel(
    readout: GameSession.DockReadout?,
    joints: List<GameSession.Joint>,
    onUndock: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (readout == null && joints.isEmpty()) return
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (readout != null) {
            // Green when the magnets would take it; orange coming in too fast.
            val colour = when {
                readout.ready -> ApogeeColors.Prograde
                readout.tooFast -> ApogeeColors.Caution
                else -> ApogeeColors.Data
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(Dimens.CornerTight))
                    .background(colour.alpha(0.22f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("DOCK", style = MaterialTheme.typography.labelSmall, color = colour, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                val text = buildString {
                    append(distance(readout.distance))
                    append(" · ")
                    append(if (readout.closing >= 0) "−" else "+")
                    append("%.1f m/s".format(abs(readout.closing)))
                    if (readout.angle > 0.0) append(" · ${readout.angle.roundToInt()}°")
                }
                Text(text, style = TelemetryTextStyle, color = colour, maxLines = 1)
            }
        }
        for (joint in joints) UndockChip(joint, onUndock)
    }
}

@Composable
private fun UndockChip(joint: GameSession.Joint, onUndock: (Int) -> Unit) {
    var armed by remember(joint.part) { mutableStateOf(false) }
    // A first tap asks; a second within three seconds does it.
    LaunchedEffect(armed) {
        if (armed) {
            kotlinx.coroutines.delay(3_000)
            armed = false
        }
    }
    val colour = if (armed) ApogeeColors.Caution else Color.White.alpha(ApogeeAlpha.SECONDARY)
    val verb = if (joint.hitch) "UNHITCH" else "UNDOCK"
    Row(
        Modifier
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(if (armed) ApogeeColors.Caution.alpha(0.22f) else Color.White.alpha(ApogeeAlpha.CONTROL_FILL))
            .clickable {
                if (armed) { armed = false; onUndock(joint.part) } else armed = true
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (armed) "TAP AGAIN" else verb, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colour, maxLines = 1)
        if (joint.label.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(joint.label, style = MaterialTheme.typography.labelSmall, color = colour, maxLines = 1)
        }
    }
}

/**
 * Two players' craft docked into one: who flies it - you, them, or either -
 * chosen by either of you, and changeable any time from here. What the
 * shared chip opens.
 */
@Composable
internal fun SharedChooser(other: String, pilot: String, onChoose: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(230.dp)) {
        Text("Docked with $other", style = MaterialTheme.typography.titleSmall, color = Color.White)
        Spacer(Modifier.height(2.dp))
        Text("Who flies the craft now?", style = MaterialTheme.typography.bodySmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
        Spacer(Modifier.height(8.dp))
        for ((key, text) in listOf("me" to "I fly", "them" to "$other flies", "both" to "Either of us")) {
            val chosen = key == pilot
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (chosen) Color(0xFF1A1030) else Color.White,
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(Dimens.CornerActionBar))
                    .background(if (chosen) ApogeeColors.Accent.alpha(0.9f) else Color.White.alpha(ApogeeAlpha.CONTROL_FILL))
                    .clickable { onChoose(key) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Who flies a shared craft, in a couple of words: what the shared chip says. */
internal fun pilotLabel(other: String, pilot: String): String =
    when (pilot) { "me" -> "YOU FLY"; "them" -> "${other.uppercase()} FLIES"; else -> "BOTH FLY" }

private fun distance(m: Double): String = if (m < 10.0) "%.2f m".format(m) else if (m < 1_000.0) "${m.roundToInt()} m" else "%.1f km".format(m / 1_000.0)
