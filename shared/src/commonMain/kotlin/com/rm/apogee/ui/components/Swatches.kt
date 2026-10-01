package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.apogee.render.SuitColours
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.alpha

/** A row of colour dots, the picked one ringed. With [autoLabel], a first dot means auto (-1). */
@Composable
fun Swatches(
    choices: List<SuitColours.Choice>,
    selected: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    autoLabel: String? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (autoLabel != null) {
            Dot(Color.White.alpha(ApogeeAlpha.FILL_FAINT), selected < 0, size, autoLabel, { onPick(-1) }) {
                Text("A", color = Color.White.alpha(ApogeeAlpha.SUBTITLE), fontSize = 12.sp)
            }
        }
        choices.forEachIndexed { i, choice ->
            val (r, g, b) = choice.rgb
            Dot(Color(r, g, b), selected == i, size, choice.name, { onPick(i) })
        }
    }
}

@Composable
private fun Dot(
    colour: Color,
    picked: Boolean,
    size: Dp,
    name: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(if (picked) 3.dp else 1.dp, if (picked) Color.White else Color.White.alpha(0.25f), CircleShape)
            .background(colour, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = name + if (picked) ", picked" else "" },
        contentAlignment = Alignment.Center,
    ) { content() }
}
