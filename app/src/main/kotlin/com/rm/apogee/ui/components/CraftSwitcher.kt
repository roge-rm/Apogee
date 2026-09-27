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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rm.apogee.ui.screens.CraftSummary
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha

/**
 * The player's craft. Tap it for the list of them all, where you can fly any of them or take one
 * out of the world. Hold it to retire the one you're flying and go back to the menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CraftSwitcher(
    current: () -> Long?,
    craft: () -> List<CraftSummary>,
    onFly: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onRetire: () -> Unit,
    size: Dp,
) {
    var listOpen by remember { mutableStateOf(false) }
    var retireAsked by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<CraftSummary?>(null) }

    Box {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(size),
        ) {
            Box(
                Modifier.combinedClickable(
                    onClick = { listOpen = true },
                    onLongClick = { retireAsked = true },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "Your craft. Hold to retire this one")
            }
        }
        if (listOpen) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(LocalDensity.current) { (size + 6.dp).roundToPx() }),
                onDismissRequest = { listOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                CraftList(
                    craft(), current(),
                    onFly = { onFly(it); listOpen = false },
                    onRemove = { confirmRemove = it },
                )
            }
        }
    }

    confirmRemove?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove \"${doomed.name}\"?") },
            text = {
                Text(
                    if (doomed.id == current()) "It's taken out of the world for good, and you go back to the menu."
                    else "It's taken out of the world for good. The design stays in Vehicle Assembly if you saved it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    listOpen = false
                    if (doomed.id == current()) onRetire() else onRemove(doomed.id)
                }) { Text("Remove", color = ApogeeColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }
    if (retireAsked) {
        AlertDialog(
            onDismissRequest = { retireAsked = false },
            title = { Text("Retire this craft?") },
            text = { Text("It's taken out of the world for good, and you go back to the menu.") },
            confirmButton = {
                TextButton(onClick = { retireAsked = false; onRetire() }) { Text("Retire", color = ApogeeColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { retireAsked = false }) { Text("Keep flying") } },
        )
    }
}

@Composable
private fun CraftList(
    craft: List<CraftSummary>,
    current: Long?,
    onFly: (Long) -> Unit,
    onRemove: (CraftSummary) -> Unit,
) {
    val list = rememberLazyListState()
    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = ApogeeColors.Surface.alpha(0.95f),
        modifier = Modifier.width(340.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("YOUR CRAFT", style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.heightIn(max = 260.dp).verticalScrollbar(list), state = list) {
                items(craft, key = { it.id }) { summary ->
                    val flying = summary.id == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(Dimens.CornerSmall))
                            .background(if (flying) ApogeeColors.Accent.alpha(0.15f) else Color.White.alpha(ApogeeAlpha.FILL_FAINT))
                            .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                summary.name, style = MaterialTheme.typography.bodyMedium, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${summary.situation}  ·  ${summary.height}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Action("REMOVE", ApogeeColors.Danger) { onRemove(summary) }
                        if (flying) {
                            Text(
                                "FLYING", style = TelemetryTextStyle, color = ApogeeColors.Accent,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        } else if (summary.canFly) {
                            Action("FLY", ApogeeColors.Accent) { onFly(summary.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, colour: Color, onClick: () -> Unit) {
    Text(
        label,
        style = TelemetryTextStyle,
        color = colour,
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.CornerSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}
