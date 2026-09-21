package com.rm.apogee.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rm.apogee.render.QualityTier
import com.rm.apogee.settings.GameSettings
import com.rm.apogee.ui.components.ApogeeButton
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.components.SectionHeading
import com.rm.apogee.ui.components.SliderRow
import com.rm.apogee.ui.components.SwitchRow
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(settings: GameSettings, detectedTier: QualityTier?) {
    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth) { contentModifier ->
        Text("Settings", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))

        SectionHeading("Controls", contentModifier)
        SwitchRow(
            title = "Left-hand layout",
            subtitle = "Mirror the flight controls",
            checked = settings.leftHandMode,
            onCheckedChange = { settings.leftHandMode = it },
            modifier = contentModifier,
        )
        SliderRow(
            title = "Control opacity",
            value = settings.controlOpacity,
            onValueChange = { settings.controlOpacity = it },
            valueLabel = "${(settings.controlOpacity * 100).roundToInt()}%",
            range = 0.3f..1.0f,
            modifier = contentModifier,
        )

        SectionHeading("Feedback", contentModifier)
        SwitchRow(
            title = "Interface sounds",
            checked = settings.uiSoundEnabled,
            onCheckedChange = { settings.uiSoundEnabled = it },
            modifier = contentModifier,
        )
        SwitchRow(
            title = "Haptics",
            checked = settings.hapticsEnabled,
            onCheckedChange = { settings.hapticsEnabled = it },
            modifier = contentModifier,
        )

        SectionHeading("Performance", contentModifier)
        Text(
            text = detectedTier?.let {
                "Detected: ${it.name.lowercase()} — up to " +
                    "${it.maxPartsPerVessel} parts per craft"
            } ?: "Measured on first flight — detection needs a graphics context.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            modifier = contentModifier,
        )
        Spacer(Modifier.height(8.dp))
        QualityTier.entries.forEach { tier ->
            val selected = settings.qualityOverride == tier
            ApogeeButton(
                label = if (selected) "✓  ${tier.name.lowercase()}" else tier.name.lowercase(),
                onClick = { settings.qualityOverride = if (selected) null else tier },
                modifier = contentModifier,
            )
        }
        Text(
            "Leave all three unselected to follow detection.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            modifier = contentModifier,
        )

        SectionHeading("Diagnostics", contentModifier)
        SwitchRow(
            title = "Frame timing overlay",
            subtitle = "Frame time, simulation step time, tick count",
            checked = settings.showDebugOverlay,
            onCheckedChange = { settings.showDebugOverlay = it },
            modifier = contentModifier,
        )
    }
}

@Composable
fun AboutScreen() {
    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth) { contentModifier ->
        Text("About", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(
            "Apogee is a sandbox for building vehicles and taking them wherever " +
                "they will go — across the ground, through the air, over and " +
                "under water, and into orbit.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.alpha(ApogeeAlpha.BODY),
            modifier = contentModifier,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "The simulation is plain Kotlin with no Android dependency, so the " +
                "same physics runs on this device and on a dedicated server.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.alpha(ApogeeAlpha.BODY),
            modifier = contentModifier,
        )
    }
}
