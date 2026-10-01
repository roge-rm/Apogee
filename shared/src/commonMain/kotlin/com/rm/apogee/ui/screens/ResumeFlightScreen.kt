package com.rm.apogee.ui.screens

import com.rm.apogee.ui.components.padFocus
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

/** One of the player's craft, as the Out There list shows it. */
class CraftSummary(
    val id: Long,
    val name: String,
    /** Where it is, in words: "Landed on Terra", "In orbit of Luna". */
    val situation: String,
    /** Height above the ground, or altitude, formatted. */
    val height: String,
    /** What removing it does to whoever's aboard. Blank with nobody aboard. */
    val crewNote: String = "",
    /** Whether it can be reset to its launch site, and flown. Not for someone on EVA or a flag. */
    val canReset: Boolean = true,
    val canFly: Boolean = true,
    /** Its button's verb: FLY, DRIVE, SAIL and so on. */
    val going: com.rm.apogee.game.Going = com.rm.apogee.game.Going.FLY,
)

/** Out There: your craft in the solo world, to fly, reset to the launch site, or remove. */
@Composable
fun ResumeFlightScreen(
    craft: List<CraftSummary>,
    onFly: (Long) -> Unit,
    onReset: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onBack: () -> Unit = {},
) {
    var confirmRemove by remember { mutableStateOf<CraftSummary?>(null) }
    var confirmReset by remember { mutableStateOf<CraftSummary?>(null) }

    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth, title = "Out There", onBack = onBack, fillHeight = true) { contentModifier ->
        if (craft.isEmpty()) {
            Text(
                "Nothing out there yet",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                textAlign = TextAlign.Center,
                modifier = contentModifier,
            )
        } else {
            val list = rememberLazyListState()
            LazyColumn(contentModifier.weight(1f).verticalScrollbar(list), state = list) {
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
                    "It's gone for good. A saved design stays in Vehicle Assembly." +
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
            text = { Text("A fresh one goes back on its launch site. This one's gone.") },
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
            RowAction(summary.going.verb.uppercase(), ApogeeColors.Accent, onFly)
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
            .padFocus(RoundedCornerShape(Dimens.CornerSmall))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}
