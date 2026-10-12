package com.rm.apogee.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rm.apogee.core.world.WorldSlots
import com.rm.apogee.ui.components.ApogeeButton
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.components.verticalScrollbar
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.alpha

/** What the Worlds screen can do with a world. */
class WorldActions(
    val onPlay: (WorldSlots.Slot) -> Unit = {},
    val onNew: (String) -> Unit = {},
    val onRename: (WorldSlots.Slot, String) -> Unit = { _, _ -> },
    val onDelete: (WorldSlots.Slot) -> Unit = {},
    val onExport: (WorldSlots.Slot) -> Unit = {},
    val onImport: () -> Unit = {},
)

/**
 * The worlds of the kind being played, sandbox or career: which one is played, and making,
 * renaming, deleting, sending and bringing in others.
 */
@Composable
fun WorldsScreen(
    worlds: List<WorldSlots.Slot>,
    current: String,
    career: Boolean,
    actions: WorldActions,
    /** When a world was last saved, as words. */
    whenSaved: (Long) -> String,
    onBack: () -> Unit,
) {
    var naming by remember { mutableStateOf<WorldSlots.Slot?>(null) }
    var making by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WorldSlots.Slot?>(null) }
    var picked by remember(current) { mutableStateOf(current) }
    val chosen = worlds.firstOrNull { it.id == picked } ?: worlds.first()

    Backdrop(title = if (career) "Careers" else "Universes", onBack = onBack, maxContentWidth = Dimens.PanelContentMaxWidth) { contentModifier ->
        val list = rememberLazyListState()
        LazyColumn(contentModifier.heightIn(max = 360.dp).verticalScrollbar(list), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(worlds, key = { it.id }) { slot ->
                val on = slot.id == chosen.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dimens.CornerPanel))
                        .background(if (on) ApogeeColors.Accent.alpha(0.25f) else Color.White.alpha(ApogeeAlpha.FILL_FAINT))
                        .clickable { picked = slot.id }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(slot.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (on) ApogeeColors.Accent else Color.White)
                        if (slot.id == current) Text("  playing", style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
                    }
                    Text(
                        if (slot.lastSaved == 0L) "New" else whenSaved(slot.lastSaved),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (chosen.id != current) ApogeeButton("Play ${chosen.name}", { actions.onPlay(chosen) }, contentModifier)
        Row(contentModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApogeeButton("Rename", { naming = chosen }, Modifier.weight(1f))
            ApogeeButton(if (com.rm.apogee.ui.LocalSavesToFiles.current) "Save" else "Send", { actions.onExport(chosen) }, Modifier.weight(1f), enabled = chosen.lastSaved != 0L)
            ApogeeButton("Delete", { deleting = chosen }, Modifier.weight(1f))
        }
        Row(contentModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApogeeButton(if (career) "New career" else "New universe", { making = true }, Modifier.weight(1f))
            ApogeeButton("Open a file", actions.onImport, Modifier.weight(1f))
        }
    }

    naming?.let { slot -> NameDialog("Rename", slot.name, { actions.onRename(slot, it); naming = null }) { naming = null } }
    if (making) NameDialog(if (career) "New career" else "New universe", "", { actions.onNew(it); making = false }) { making = false }
    deleting?.let { slot ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${slot.name}?") },
            text = { Text(if (slot.id == worlds.first().id) "Everything in it goes, and it starts again empty." else "Everything in it goes.") },
            confirmButton = { TextButton(onClick = { actions.onDelete(slot); deleting = null }) { Text("Delete", color = ApogeeColors.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, start: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(32) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
