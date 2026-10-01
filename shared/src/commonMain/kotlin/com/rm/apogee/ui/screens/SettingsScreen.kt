package com.rm.apogee.ui.screens

import com.rm.apogee.ui.components.verticalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rm.apogee.BuildInfo
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Protocol
import com.rm.apogee.render.QualityTier
import com.rm.apogee.settings.GameSettings
import com.rm.apogee.settings.PitchStyle
import com.rm.apogee.ui.components.ApogeeButton
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.components.ChoiceGroup
import com.rm.apogee.ui.components.SectionHeading
import com.rm.apogee.ui.components.padFocus
import com.rm.apogee.ui.components.SliderRow
import com.rm.apogee.ui.components.SwitchRow
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

/**
 * The Settings tabs. The title and tabs stay fixed and only the settings under them scroll. Tabs
 * share the width evenly.
 */
private enum class SettingsTab(val label: String) {
    PLAYER("Player"),
    CONTROLS("Controls"),
    DISPLAY("Display"),
    AUDIO("Audio"),
}

@Composable
fun SettingsScreen(
    settings: GameSettings,
    detectedTier: QualityTier?,
    onBack: () -> Unit = {},
    /** Whether the 3D view can be drawn below full resolution, to show the Resolution choice. */
    canScaleRender: Boolean = false,
    /** The connected controller's name, if any. [onController] opens its buttons page. */
    controllerName: String? = null,
    onController: () -> Unit = {},
) {
    // Not kept across visits: Settings always opens on the first tab.
    var tab by remember { mutableStateOf(SettingsTab.PLAYER) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(ApogeeColors.BackdropTop, ApogeeColors.BackdropBottom))
            )
            // Clear of the notch.
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout)
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.PanelContentMaxWidth)
                .fillMaxSize()
                .align(Alignment.TopCenter)
                .padding(horizontal = Dimens.ScreenPaddingH, vertical = Dimens.ScreenPaddingV),
        ) {
            // Back sits on the fixed title row.
            com.rm.apogee.ui.components.TitleRow("Settings", onBack)
            Spacer(Modifier.height(8.dp))

            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = Color.Transparent,
                contentColor = ApogeeColors.Accent,
                divider = { HorizontalDivider(color = Color.White.alpha(ApogeeAlpha.DIVIDER)) },
            ) {
                SettingsTab.entries.forEach { candidate ->
                    Tab(
                        selected = candidate == tab,
                        onClick = { tab = candidate },
                        modifier = Modifier.padFocus(),
                        selectedContentColor = ApogeeColors.Accent,
                        unselectedContentColor = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                        // Narrower padding than the stock tab's, so "Controls" fits a quarter of
                        // an upright phone without wrapping.
                    ) {
                        Text(
                            candidate.label,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                        )
                    }
                }
            }

            // Keyed on the tab, so each one starts at its top.
            val scroll = remember(tab) { ScrollState(0) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScrollbar(scroll)
                    .verticalScroll(scroll)
                    .padding(top = 8.dp, bottom = 16.dp),
            ) {
                when (tab) {
                    SettingsTab.PLAYER -> PlayerTab(settings)
                    SettingsTab.CONTROLS -> ControlsTab(settings, controllerName, onController)
                    SettingsTab.DISPLAY -> DisplayTab(settings, detectedTier, canScaleRender)
                    SettingsTab.AUDIO -> AudioTab(settings)
                }
            }
        }
    }
}

@Composable
private fun PlayerTab(settings: GameSettings) {
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = settings.playerName,
        onValueChange = { settings.playerName = it.take(32) },
        singleLine = true,
        label = { Text("Name") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        // Craft belong to this install, not the name, so renaming keeps them.
        "Shown to other players. Changing it keeps your craft.",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
    )

    SectionHeading("Suit stripe")
    com.rm.apogee.ui.components.Swatches(
        choices = com.rm.apogee.render.SuitColours.STRIPES,
        selected = settings.suitStripe,
        onPick = { settings.suitStripe = it },
        size = 28.dp,
        autoLabel = "Picked for you",
    )
    Spacer(Modifier.height(6.dp))
    Text(
        (com.rm.apogee.render.SuitColours.STRIPES.getOrNull(settings.suitStripe)?.name ?: "Picked for you") +
            ". From your next flight.",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
    )

    SectionHeading("Weather")
    ChoiceGroup(
        title = "In games you host",
        options = com.rm.apogee.core.weather.WeatherIntensity.entries,
        selected = settings.weatherIntensity,
        label = { it.label },
        onSelect = { settings.weatherIntensity = it },
    )
    ChoiceGroup(
        title = "Cloud cover",
        options = com.rm.apogee.core.weather.CloudCover.entries,
        selected = settings.cloudCover,
        label = { it.label },
        onSelect = { settings.cloudCover = it },
    )
    Text(
        "From your next flight.",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
    )
}

@Composable
private fun ControlsTab(settings: GameSettings, controllerName: String?, onController: () -> Unit) {
    SectionHeading("Stick")
    ChoiceGroup(
        title = "Pull back to climb",
        options = PitchStyle.entries,
        selected = settings.pitchStyle,
        label = { it.label },
        description = { it.description.ifEmpty { null } },
        onSelect = { settings.pitchStyle = it },
    )
    ChoiceGroup(
        title = "Steer by the screen",
        options = com.rm.apogee.settings.SteeringStyle.entries,
        selected = settings.steeringStyle,
        label = { it.label },
        onSelect = { settings.steeringStyle = it },
    )

    SectionHeading("Layout")
    SwitchRow(
        title = "Left-hand layout",
        checked = settings.leftHandMode,
        onCheckedChange = { settings.leftHandMode = it },
    )
    SliderRow(
        title = "Control opacity",
        value = settings.controlOpacity,
        onValueChange = { settings.controlOpacity = it },
        valueLabel = "${(settings.controlOpacity * 100).roundToInt()}%",
        range = 0.3f..1.0f,
    )
    SwitchRow(
        title = "Fade controls when idle",
        checked = settings.fadeWhenIdle,
        onCheckedChange = { settings.fadeWhenIdle = it },
    )
    SwitchRow(
        title = "Haptics",
        checked = settings.hapticsEnabled,
        onCheckedChange = { settings.hapticsEnabled = it },
    )

    SectionHeading("Controller")
    Text(
        controllerName?.let { "Connected: $it" } ?: "No controller found",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
    )
    com.rm.apogee.ui.components.ApogeeButton(
        "Controller buttons",
        onController,
    )
    SwitchRow(
        title = "Hide the touch stick with a controller",
        checked = settings.padHideTouch,
        onCheckedChange = { settings.padHideTouch = it },
    )
    SwitchRow(
        title = "Hold A to stage",
        checked = settings.padHoldToStage,
        onCheckedChange = { settings.padHoldToStage = it },
    )
}

@Composable
private fun DisplayTab(settings: GameSettings, detectedTier: QualityTier?, canScaleRender: Boolean) {
    SectionHeading("Performance")
    Text(
        text = detectedTier?.let {
            "Detected: ${it.name.lowercase()}, up to ${it.maxPartsPerVessel} parts per craft"
        } ?: "Found on your first flight",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
    )
    Spacer(Modifier.height(8.dp))
    QualityTier.entries.forEach { tier ->
        val selected = settings.qualityOverride == tier
        ApogeeButton(
            label = if (selected) "✓  ${tier.name.lowercase()}" else tier.name.lowercase(),
            onClick = { settings.qualityOverride = if (selected) null else tier },
        )
    }
    Text(
        "None picked follows what's detected",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
    )

    if (canScaleRender) {
        SectionHeading("Resolution")
        var resolution by remember { mutableStateOf(settings.resolution) }
        ChoiceGroup(
            title = "3D view resolution",
            options = com.rm.apogee.render.Resolution.entries,
            selected = resolution,
            label = { it.label },
            onSelect = { resolution = it; settings.resolution = it },
        )
    }

    SectionHeading("Shadows")
    // Compose state, so a choice redraws the list at once.
    var shadows by remember { mutableStateOf(settings.shadowQualityOverride) }
    val byTier = com.rm.apogee.render.ShadowQuality.defaultFor(settings.effectiveTier ?: QualityTier.MEDIUM)
    ChoiceGroup(
        title = "Shadow quality",
        options = listOf<com.rm.apogee.render.ShadowQuality?>(null) + com.rm.apogee.render.ShadowQuality.entries,
        selected = shadows,
        label = { it?.label ?: "Automatic (${byTier.label.lowercase()})" },
        description = {
            when (it) {
                com.rm.apogee.render.ShadowQuality.LOW -> "No mountain shadows"
                else -> null
            }
        },
        onSelect = { shadows = it; settings.shadowQualityOverride = it },
    )

    SectionHeading("Diagnostics")
    SwitchRow(
        title = "Frame timing overlay",
        checked = settings.showDebugOverlay,
        onCheckedChange = { settings.showDebugOverlay = it },
    )
}

@Composable
private fun AudioTab(settings: GameSettings) {
    SectionHeading("Sounds")
    SwitchRow(
        title = "Vehicle sounds",
        subtitle = "Crashes still sound",
        checked = settings.vehicleSoundEnabled,
        onCheckedChange = { settings.vehicleSoundEnabled = it },
    )
    SwitchRow(
        title = "Ambient sounds",
        checked = settings.ambientSoundEnabled,
        onCheckedChange = { settings.ambientSoundEnabled = it },
    )
    SwitchRow(
        title = "Interface sounds",
        checked = settings.uiSoundEnabled,
        onCheckedChange = { settings.uiSoundEnabled = it },
    )

    SectionHeading("Volume")
    VolumeRow("Everything", settings.masterVolume) { settings.masterVolume = it }
    VolumeRow("Craft and crashes", settings.effectsVolume) { settings.effectsVolume = it }
    VolumeRow("Wind, weather and places", settings.ambienceVolume) { settings.ambienceVolume = it }
    VolumeRow("Interface", settings.interfaceVolume) { settings.interfaceVolume = it }
}

@Composable
private fun VolumeRow(title: String, value: Float, onChange: (Float) -> Unit) {
    SliderRow(
        title = title,
        value = value,
        onValueChange = onChange,
        valueLabel = "${(value * 100).roundToInt()}%",
        range = 0f..1f,
    )
}

@Composable
fun AboutScreen(onBack: () -> Unit = {}) {
    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth, title = "About", onBack = onBack) { contentModifier ->
        Text(
            "Build vehicles and take them anywhere: over land, through the air, on and under " +
                "the sea, and into space.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.alpha(ApogeeAlpha.BODY),
            modifier = contentModifier,
        )
        Spacer(Modifier.height(12.dp))

        // The protocol, terrain generation and parts hash decide whether a server lets you in.
        SectionHeading("Build", contentModifier)
        Text(
            "Apogee ${BuildInfo.VERSION_NAME}  (${BuildInfo.VERSION_CODE})\n" +
                "Protocol ${Protocol.VERSION}  \u00b7  " +
                "terrain ${com.rm.apogee.core.terrain.TerrainField.GENERATION}  \u00b7  " +
                "parts ${StockParts.catalog.contentHash}",
            style = TelemetryTextStyle,
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            textAlign = TextAlign.Center,
            modifier = contentModifier,
        )
    }
}
