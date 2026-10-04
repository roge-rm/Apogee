package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.ScubaDiving
import androidx.compose.material.icons.filled.WindPower
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rm.apogee.core.craft.CraftKind
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.alpha

/**
 * Which kinds of craft to show: one even row of round icons, All then each kind, with the chosen
 * one's name and count underneath. Icons so it fits one line on a phone.
 */
@Composable
fun KindPicker(
    /** The kinds there are craft of, in order. */
    kinds: List<CraftKind>,
    /** The chosen kind, or null for All. */
    selected: CraftKind?,
    /** How many craft there are of a kind, or of all of them for null. */
    count: (CraftKind?) -> Int,
    onSelect: (CraftKind?) -> Unit,
    modifier: Modifier = Modifier,
    /** The chosen kind's name and count underneath. Left off where height is short. */
    showCount: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KindIcon(Icons.Filled.Apps, "All", selected == null, Modifier.weight(1f)) { onSelect(null) }
            for (kind in kinds) {
                KindIcon(iconOf(kind), kind.label, kind == selected, Modifier.weight(1f)) { onSelect(kind) }
            }
        }
        if (!showCount) return@Column
        Spacer(Modifier.height(6.dp))
        val n = count(selected)
        Text(
            (selected?.label ?: "All") + " · " + (if (n == 1) "1 craft" else "$n craft"),
            style = MaterialTheme.typography.labelLarge,
            color = ApogeeColors.Accent,
        )
    }
}

@Composable
private fun KindIcon(icon: ImageVector, label: String, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .sizeIn(maxWidth = 44.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(if (chosen) ApogeeColors.Accent.alpha(0.3f) else Color.White.alpha(ApogeeAlpha.FILL_FAINT))
                .padFocus(CircleShape)
                .clickable(onClick = onClick)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (chosen) ApogeeColors.Accent else Color.White.alpha(ApogeeAlpha.SECONDARY))
        }
    }
}

/** A kind's icon. */
fun iconOf(kind: CraftKind): ImageVector = when (kind) {
    CraftKind.ROCKET -> Icons.Filled.RocketLaunch
    CraftKind.PLANE -> Icons.Filled.Flight
    CraftKind.ROTORCRAFT -> Icons.Filled.WindPower
    CraftKind.AIRSHIP -> Icons.Filled.Cloud
    CraftKind.ROVER -> Icons.Filled.DirectionsCar
    CraftKind.BOAT -> Icons.Filled.Sailing
    CraftKind.SUB -> Icons.Filled.ScubaDiving
    CraftKind.BASE -> Icons.Filled.Domain
}
