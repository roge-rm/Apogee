package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.HudState
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.alpha

/** What the crew card and the EVA controls do. */
class CrewActions(
    /** Crew member (by id) out on EVA. */
    val onEva: (Long) -> Unit = {},
    /** Crew member (by id) to the next free seat in the craft. */
    val onMove: (Long) -> Unit = {},
    val onBoard: () -> Unit = {},
    val onJump: () -> Unit = {},
    /** Take hold of the nearest ladder (true), or let go. */
    val onGrab: (Boolean) -> Unit = {},
    val onFlag: () -> Unit = {},
)

/**
 * Who is aboard, each by name and seat, with MOVE and EVA for the
 * player's own: what the crew chip opens.
 */
@Composable
internal fun CrewList(hud: HudState, actions: CrewActions, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .width(250.dp)
            .heightIn(max = 200.dp)
            .verticalScrollbar(scroll)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hud.crew.isEmpty()) {
            Text("Nobody aboard", style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
        }
        for (seat in hud.crew) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(seat.name, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(seat.where, style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (seat.mine) {
                    if (seat.canMove) SmallAction("MOVE") { actions.onMove(seat.id) }
                    Spacer(Modifier.width(6.dp))
                    SmallAction("EVA") { actions.onEva(seat.id) }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onTap: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = ApogeeColors.Accent,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ApogeeColors.Accent.alpha(0.18f))
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}
