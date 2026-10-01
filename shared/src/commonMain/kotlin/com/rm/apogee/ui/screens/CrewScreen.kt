package com.rm.apogee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.components.verticalScrollbar
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.alpha

/** One of the player's crew, as the Crew screen shows them. */
class CrewSummary(
    val id: Long,
    val name: String,
    /** "At home", "Aboard Stilt Lander", or how they were lost. */
    val status: String,
    val lost: Boolean,
    /** Their visor, as [com.rm.apogee.core.crew.Crew] numbers them. */
    val visor: Int = 0,
)

/** Your crew in the solo world and where they are, then the lost. The living pick a visor colour. */
@Composable
fun CrewScreen(crew: List<CrewSummary>, onVisor: (Long, Int) -> Unit, onBack: () -> Unit = {}) {
    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth, title = "Crew", onBack = onBack, fillHeight = true) { contentModifier ->
        if (crew.isEmpty()) {
            Text(
                "Nobody yet",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                textAlign = TextAlign.Center,
                modifier = contentModifier,
            )
            return@Backdrop
        }
        val list = rememberLazyListState()
        val living = crew.filter { !it.lost }
        val lost = crew.filter { it.lost }
        LazyColumn(
            state = list,
            modifier = contentModifier.weight(1f).verticalScrollbar(list),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(living, key = { it.id }) { Row(it, onVisor) }
            if (lost.isNotEmpty()) {
                item {
                    Text(
                        "IN MEMORY",
                        style = MaterialTheme.typography.labelMedium,
                        color = ApogeeColors.Accent,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(lost, key = { it.id }) { Row(it, onVisor) }
            }
        }
    }
}

@Composable
private fun Row(member: CrewSummary, onVisor: (Long, Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerSmall))
            .background(Color.White.alpha(if (member.lost) 0.04f else 0.08f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(member.name, style = MaterialTheme.typography.bodyLarge, color = if (member.lost) Color.White.alpha(ApogeeAlpha.SUBTITLE) else Color.White)
        Text(member.status, style = MaterialTheme.typography.labelMedium, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
        if (!member.lost) {
            // So crew out together can be told apart.
            Spacer(Modifier.height(8.dp))
            com.rm.apogee.ui.components.Swatches(
                choices = com.rm.apogee.render.SuitColours.VISORS,
                selected = member.visor,
                onPick = { onVisor(member.id, it) },
                size = 26.dp,
            )
        }
    }
}
