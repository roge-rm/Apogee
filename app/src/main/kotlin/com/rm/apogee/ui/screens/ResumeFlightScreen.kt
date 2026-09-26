package com.rm.apogee.ui.screens

import androidx.compose.foundation.lazy.rememberLazyListState
import com.rm.apogee.ui.components.verticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha

/** One of the player's craft, as the Resume Flight list shows it. */
class CraftSummary(
    val id: Long,
    val name: String,
    /** "Landed on Terra", "In orbit of Luna" - where it is, in words. */
    val situation: String,
    /** Height above the ground, or altitude, formatted. */
    val height: String,
    /** What removing it does to whoever is aboard, in words; blank with nobody aboard. */
    val crewNote: String = "",
    /** Whether it can be put back on its launch site - not someone on EVA, nor a flag - and flown at all. */
    val canReset: Boolean = true,
    val canFly: Boolean = true,
)

/**
 * Every craft the player has out in the solo world: fly any of them, put one
 * back on its launch site, or take one away for good.
 *
 * The counterpart to Free Flight, which always starts fresh. A world people
 * leave bases in needs a way back to each of them - and a way to tidy up.
 */
@Composable
fun ResumeFlightScreen(
    craft: List<CraftSummary>,
    onFly: (Long) -> Unit,
    onReset: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    var confirmRemove by remember { mutableStateOf<CraftSummary?>(null) }
    var confirmReset by remember { mutableStateOf<CraftSummary?>(null) }

    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth) { contentModifier ->
        Text("Resume Flight", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))
        if (craft.isEmpty()) {
            Text(
                "Nothing out there yet. Free Flight or a launch from Vehicle Assembly puts a craft on the pad.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                textAlign = TextAlign.Center,
                modifier = contentModifier,
            )
        } else {
            val list = rememberLazyListState()
            LazyColumn(contentModifier.heightIn(max = 420.dp).verticalScrollbar(list), state = list) {
                items(craft, key = { it.id }) { summary ->
                    CraftRow(
                        summary,
                        onFly = { onFly(summary.id) },
                        onReset = { confirmReset = summary },
                        onRemove = { confirmRemove = summary },
                    )
                }
            }
        }
    }

    confirmRemove?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove \"${doomed.name}\"?") },
            text = {
                Text(
                    "It is taken out of the world for good. The design stays in Vehicle Assembly if you saved it." +
                        if (doomed.crewNote.isNotEmpty()) "\n\n${doomed.crewNote}" else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { onRemove(doomed.id); confirmRemove = null }) {
                    Text("Remove", color = ApogeeColors.Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }
    confirmReset?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmReset = null },
            title = { Text("Reset \"${target.name}\"?") },
            text = { Text("A fresh one goes back on its launch site, fuelled and unstaged. The craft as it is now is gone.") },
            confirmButton = {
                TextButton(onClick = { onReset(target.id); confirmReset = null }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CraftRow(summary: CraftSummary, onFly: () -> Unit, onReset: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(Dimens.CornerSmall))
            .background(Color.White.alpha(ApogeeAlpha.FILL_FAINT))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(summary.name, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(
                "${summary.situation}  ·  ${summary.height}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            )
        }
        if (summary.canReset) {
            RowAction("RESET", ApogeeColors.Caution, onReset)
            Spacer(Modifier.width(4.dp))
        }
        RowAction("REMOVE", ApogeeColors.Danger, onRemove)
        if (summary.canFly) {
            Spacer(Modifier.width(4.dp))
            RowAction("FLY", ApogeeColors.Accent, onFly)
        }
    }
}

@Composable
private fun RowAction(label: String, colour: Color, onClick: () -> Unit) {
    Text(
        label,
        style = TelemetryTextStyle,
        color = colour,
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.CornerSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}
