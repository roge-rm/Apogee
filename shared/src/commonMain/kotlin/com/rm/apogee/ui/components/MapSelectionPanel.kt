package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.*
import com.rm.apogee.ui.theme.*
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapSelectionPanel(selection: MapSelection, actions: BurnActions) {
    Column(
        Modifier.widthIn(max = 340.dp)
            .background(ApogeeColors.BackdropBottom.copy(alpha = 0.96f), RoundedCornerShape(Dimens.CornerTight))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(selection.selected?.name ?: "Choose an object", Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, color = ApogeeColors.Accent, fontWeight = FontWeight.Bold)
            TextButton(onClick = actions.onMapDismiss) { Text("Close") }
        }
        val pick = selection.selected
        if (pick == null) {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                for (choice in selection.choices) TextButton(onClick = { actions.onMapSelect(choice.key) }, modifier = Modifier.fillMaxWidth()) {
                    Text(choice.name)
                }
            }
        } else {
            val information = buildList {
                if (selection.inSeconds.isFinite()) add(if (selection.inSeconds > 0) "In ${formatDuration(selection.inSeconds)}" else "Passing now")
                if (selection.distance.isFinite()) add("${formatDistance(selection.distance)} away")
            }
            if (pick.altitude.isFinite()) Text("Altitude ${formatDistance(pick.altitude)}", style = MaterialTheme.typography.labelMedium, color = ApogeeColors.Data)
            if (pick.speed.isFinite()) Text("Speed ${pick.speed.roundToInt()} m/s", style = MaterialTheme.typography.labelMedium, color = ApogeeColors.Data)
            if (pick.separation.isFinite()) Text("Separation ${formatDistance(pick.separation)}", style = MaterialTheme.typography.labelMedium, color = ApogeeColors.Data)
            Text(information.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = ApogeeColors.Data)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (action in selection.actions) OutlinedButton(onClick = { actions.onMapAction(action) }) { Text(action.label) }
            }
        }
    }
}
