package com.rm.apogee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.tutorial.Topic
import com.rm.apogee.game.tutorial.Tutorial
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.components.padFocus
import com.rm.apogee.ui.components.verticalScrollbar
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.alpha

/** The tutorials by topic, with a tick on the ones done. */
@Composable
fun TutorialsScreen(
    tutorials: List<Tutorial>,
    isDone: (String) -> Boolean,
    onStart: (Tutorial) -> Unit,
    onBack: () -> Unit = {},
) {
    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth, title = "Tutorials", onBack = onBack, fillHeight = true) { contentModifier ->
        val list = rememberLazyListState()
        LazyColumn(
            state = list,
            modifier = contentModifier.weight(1f).verticalScrollbar(list),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (topic in Topic.entries) {
                val these = tutorials.filter { it.topic == topic }
                if (these.isEmpty()) continue
                item(key = topic.name) {
                    Text(
                        topic.title.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = ApogeeColors.Accent,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(these, key = { it.id }) { TutorialRow(it, isDone(it.id)) { onStart(it) } }
            }
        }
    }
}

@Composable
private fun TutorialRow(tutorial: Tutorial, done: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerSmall))
            .padFocus(RoundedCornerShape(Dimens.CornerSmall))
            .clickable(onClick = { com.rm.apogee.audio.Sounds.click(); onClick() })
            .background(Color.White.alpha(0.08f))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(tutorial.title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        if (done) Icon(Icons.Filled.Check, contentDescription = "Done", tint = ApogeeColors.Prograde, modifier = Modifier.size(20.dp))
    }
}
