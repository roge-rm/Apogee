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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rm.apogee.core.world.World
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha

/**
 * Time. Tap to pause, and tap again to carry on. Hold it for the warp rates, and while warped, a
 * tap brings it straight back to real time. It shows what the world is really running at, which the
 * world might be holding below what you asked for, near a planet or under power.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WarpButton(
    warp: Double,
    requested: Double,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onWarp: (Double) -> Unit,
    size: Dp,
) {
    val paused = warp <= 0.0
    val warped = requested > 1.0
    Box {
        Surface(
            shape = CircleShape,
            color = when {
                paused -> ApogeeColors.Caution.alpha(0.35f)
                warped -> ApogeeColors.Accent.alpha(0.35f)
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            modifier = Modifier.size(size),
        ) {
            Box(
                Modifier.combinedClickable(
                    onClick = { onWarp(if (paused || warped) 1.0 else 0.0) },
                    onLongClick = { onExpand(true) },
                ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    paused -> Icon(Icons.Filled.Pause, contentDescription = "Paused. Tap to carry on", tint = ApogeeColors.Caution)
                    warped -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.FastForward, contentDescription = "Time warp. Tap for real time", tint = ApogeeColors.Accent, modifier = Modifier.size(18.dp))
                        Text(rate(warp), style = TelemetryTextStyle, color = ApogeeColors.Accent, maxLines = 1)
                    }
                    else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Real time. Tap to pause, hold to warp")
                }
            }
        }
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(LocalDensity.current) { (size + 6.dp).roundToPx() }),
                onDismissRequest = { onExpand(false) },
                properties = PopupProperties(focusable = true),
            ) {
                Rates(warp, requested) { onWarp(it); onExpand(false) }
            }
        }
    }
}

@Composable
private fun Rates(warp: Double, requested: Double, onPick: (Double) -> Unit) {
    Surface(shape = RoundedCornerShape(Dimens.CornerPanel), color = ApogeeColors.Surface.alpha(0.95f)) {
        Column(Modifier.padding(10.dp)) {
            Text("TIME", style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            Spacer(Modifier.height(6.dp))
            // Four to a row, because the rates on rails now go up to a million.
            val rows = listOf(listOf(0.0, 1.0, 2.0, 4.0)) + World.WARP_RATES.filter { it > World.PHYSICS_WARP }.chunked(4)
            for (row in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (r in row) {
                        val selected = r == requested
                        Box(
                            Modifier
                                .width(56.dp)
                                .clip(RoundedCornerShape(Dimens.CornerTight))
                                .background(if (selected) ApogeeColors.Accent.alpha(0.3f) else Color.White.alpha(ApogeeAlpha.FILL_FAINT))
                                .clickable { onPick(r) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (r == 0.0) "Pause" else rate(r),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) ApogeeColors.Accent else Color.White,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (requested > warp && warp > 0.0) {
                Text(
                    "Held at ${rate(warp)}: too low, or under power",
                    style = MaterialTheme.typography.labelSmall,
                    color = ApogeeColors.Caution,
                )
            } else {
                Text(
                    "Past 4× only out of the air, and higher for more",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                )
            }
        }
    }
}

private fun rate(r: Double): String = when {
    r >= 1_000_000 -> "${(r / 1_000_000).toInt()}M×"
    r >= 1_000 -> "${(r / 1_000).toInt()}k×"
    else -> "${r.toInt()}×"
}
