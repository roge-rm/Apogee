package com.rm.apogee.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rm.apogee.core.world.SasMode
import com.rm.apogee.game.GameSession
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt
import com.rm.apogee.platform.format

/**
 * Stability assist. Tap to turn it on and off. Press and hold to choose what it holds (the attitude
 * when you let go, any navball marker, or on a plane in the air, its height and heading) and a
 * target to steer by. The button shows what it's holding.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SasButton(
    enabled: Boolean,
    mode: SasMode?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onExpand: (Boolean) -> Unit,
    onMode: (SasMode) -> Unit,
    targetChoices: () -> List<GameSession.TargetChoice>,
    currentTarget: String?,
    onTarget: (Long) -> Unit,
    /** Whether it can hold height and heading here, whether it is, and to switch that. */
    canCruise: Boolean = false,
    cruising: Boolean = false,
    onCruise: (Boolean) -> Unit = {},
    /** Held still by the keeper core. */
    keeping: Boolean = false,
    /** Whether the auto-land can bring it down here, whether it is, and to switch that. */
    canLand: Boolean = false,
    landing: Boolean = false,
    onLand: (Boolean) -> Unit = {},
) {
    val holdingMarker = enabled && mode != null && mode != SasMode.HOLD
    Box {
        Surface(
            shape = CircleShape,
            color = if (enabled) ApogeeColors.Prograde.alpha(0.3f) else Color.White.alpha(ApogeeAlpha.CONTROL_FILL),
            modifier = Modifier.size(40.dp),
        ) {
            Box(
                Modifier.combinedClickable(onClick = onToggle, onLongClick = { onExpand(true) }),
                contentAlignment = Alignment.Center,
            ) {
                if (landing) {
                    Text("LND", style = TelemetryTextStyle, color = ApogeeColors.Prograde, maxLines = 1)
                } else if (keeping) {
                    Text("STN", style = TelemetryTextStyle, color = ApogeeColors.Prograde, maxLines = 1)
                } else if (cruising) {
                    Text("A+H", style = TelemetryTextStyle, color = ApogeeColors.Prograde, maxLines = 1)
                } else if (holdingMarker) {
                    Text(short(mode!!), style = TelemetryTextStyle, color = ApogeeColors.Prograde, maxLines = 1)
                } else {
                    Icon(
                        Icons.Filled.Explore, contentDescription = "Stability assist",
                        tint = if (enabled) ApogeeColors.Prograde else Color.White.alpha(ApogeeAlpha.SECONDARY),
                    )
                }
            }
        }
        if (expanded) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = androidx.compose.ui.unit.IntOffset(0, -140),
                onDismissRequest = { onExpand(false) },
                properties = PopupProperties(focusable = true),
            ) {
                Picker(
                    mode, currentTarget, targetChoices(), onMode = { onMode(it); onExpand(false) }, onTarget = { onTarget(it); onExpand(false) },
                    canCruise = canCruise, cruising = cruising, onCruise = { onCruise(it); onExpand(false) },
                    canLand = canLand, landing = landing, onLand = { onLand(it); onExpand(false) },
                )
            }
        }
    }
}

@Composable
private fun Picker(
    mode: SasMode?,
    currentTarget: String?,
    targets: List<GameSession.TargetChoice>,
    onMode: (SasMode) -> Unit,
    onTarget: (Long) -> Unit,
    canCruise: Boolean,
    cruising: Boolean,
    onCruise: (Boolean) -> Unit,
    canLand: Boolean = false,
    landing: Boolean = false,
    onLand: (Boolean) -> Unit = {},
) {
    val scroll = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = ApogeeColors.Surface.alpha(0.95f),
        modifier = Modifier.width(250.dp),
    ) {
        Column(
            Modifier
                .heightIn(max = 360.dp)
                .verticalScrollbar(scroll)
                .verticalScroll(scroll)
                .padding(10.dp),
        ) {
            Text("HOLD", style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            Spacer(Modifier.height(6.dp))
            // A plane's height and heading, flown for you. The stick still steers, and it holds
            // wherever you let go.
            if (canCruise || cruising) {
                Chip(
                    if (cruising) "ALT + HDG · off" else "ALT + HDG", ApogeeColors.Prograde,
                    selected = cruising, enabled = true, modifier = Modifier.fillMaxWidth(),
                ) { onCruise(!cruising) }
                Spacer(Modifier.height(6.dp))
            }
            // Down where it is, the way it flies: a plane glides in and flares, a helicopter or
            // an airship comes straight down.
            if (canLand || landing) {
                Chip(
                    if (landing) "LAND · off" else "LAND", ApogeeColors.Prograde,
                    selected = landing, enabled = true, modifier = Modifier.fillMaxWidth(),
                ) { onLand(!landing) }
                Spacer(Modifier.height(6.dp))
            }
            val rows = listOf(
                listOf(SasMode.HOLD),
                listOf(SasMode.PROGRADE, SasMode.RETROGRADE),
                listOf(SasMode.NORMAL, SasMode.ANTI_NORMAL),
                listOf(SasMode.RADIAL_OUT, SasMode.RADIAL_IN),
                listOf(SasMode.TARGET, SasMode.ANTI_TARGET),
            )
            for (row in rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in row) {
                        val usable = m != SasMode.TARGET && m != SasMode.ANTI_TARGET || currentTarget != null
                        Chip(m.label, colourOf(m), selected = m == mode, enabled = usable, modifier = Modifier.weight(1f)) { onMode(m) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text("TARGET", style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            Spacer(Modifier.height(6.dp))
            if (currentTarget != null) {
                Chip("Clear · $currentTarget", Color.White, selected = false, enabled = true, modifier = Modifier.fillMaxWidth()) { onTarget(-1L) }
                Spacer(Modifier.height(6.dp))
            }
            if (targets.isEmpty()) {
                Text("No other craft nearby", style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            }
            for (t in targets) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dimens.CornerTight))
                        .clickable { onTarget(t.id) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t.name, style = MaterialTheme.typography.bodySmall, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(distance(t.distance), style = TelemetryTextStyle, color = ApogeeColors.Data)
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, colour: Color, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(if (selected) colour.alpha(0.3f) else Color.White.alpha(ApogeeAlpha.FILL_FAINT))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelMedium,
            color = if (enabled) colour else Color.White.alpha(ApogeeAlpha.BORDER), maxLines = 1,
        )
    }
}

private fun colourOf(mode: SasMode): Color = when (mode) {
    SasMode.HOLD -> Color.White
    SasMode.PROGRADE -> ApogeeColors.Prograde
    SasMode.RETROGRADE -> ApogeeColors.Retrograde
    SasMode.NORMAL, SasMode.ANTI_NORMAL -> Color(0xFFD27CFF)
    SasMode.RADIAL_OUT, SasMode.RADIAL_IN -> Color(0xFF6FE3FF)
    SasMode.TARGET, SasMode.ANTI_TARGET -> Color(0xFFFF5FD2)
    SasMode.BURN -> Color(0xFF4FA3FF)
}

private fun short(mode: SasMode): String = when (mode) {
    SasMode.HOLD -> "H"
    SasMode.PROGRADE -> "PRO"
    SasMode.RETROGRADE -> "RET"
    SasMode.NORMAL -> "NRM"
    SasMode.ANTI_NORMAL -> "ANM"
    SasMode.RADIAL_OUT -> "R+"
    SasMode.RADIAL_IN -> "R−"
    SasMode.TARGET -> "TGT"
    SasMode.ANTI_TARGET -> "ATG"
    SasMode.BURN -> "BRN"
}

private fun distance(metres: Double): String =
    if (metres >= 10_000) "${(metres / 1000).roundToInt()} km"
    else if (metres >= 1_000) "%.1f km".format(metres / 1000)
    else "${metres.roundToInt()} m"
