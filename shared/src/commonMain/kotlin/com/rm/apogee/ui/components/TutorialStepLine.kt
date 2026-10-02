package com.rm.apogee.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.tutorial.TutorialLine
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha

/** A tutorial's step: which of how many, what to do, and a tick once it's done. */
@Composable
fun TutorialStepLine(line: TutorialLine, modifier: Modifier = Modifier) {
    val tick by animateFloatAsState(if (line.ticked) 1f else 0f, label = "tutorial tick")
    Row(
        modifier
            .clip(RoundedCornerShape(Dimens.CornerPanel))
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${line.number}/${line.count}", style = TelemetryTextStyle, color = ApogeeColors.Accent)
        Spacer(Modifier.width(10.dp))
        Text(
            line.text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (line.ticked) ApogeeColors.Prograde else Color.White,
        )
        if (tick > 0f) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = "Done", tint = ApogeeColors.Prograde, modifier = Modifier.size(20.dp).scale(tick))
        }
    }
}
