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
 * Settings, with a tab for each kind of thing a player comes here to change.
 *
 * One page stopped working once it held more than a handful of rows, because the controls a player
 * really wants were several screens down. The layout follows ScorchDroid's: a fixed title and tab
 * row with only the settings under them scrolling, tabs sharing the width evenly instead of
 * bunching at one edge, and each tab starting at its own top.
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
    /** Whether the 3D view can be drawn at less than full resolution here, for the Resolution choice. */
    canScaleRender: Boolean = false,
    /** The controller connected, if there is one, and opening its buttons page. */
    controllerName: String? = null,
    onController: () -> Unit = {},
) {
    // Remembered across tab switches only, not across visits. Coming back to Settings starts where
    // the screen starts.
    var tab by remember { mutableStateOf(SettingsTab.PLAYER) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(ApogeeColors.BackdropTop, ApogeeColors.BackdropBottom))
            )
            // Clear of the notch, now that the menus are full screen too.
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
            // Back on the title row, which stays put with the tabs while only the settings scroll.
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
                        // Its own padding, narrower than the stock tab's. A quarter of an upright
                        // phone is a hair too narrow for "Controls" inside that, and it broke as
                        // "Control / s".
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

            // Keyed on the tab, so each one starts at its own top instead of taking on how far the
            // last one was scrolled.
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
        // Said plainly, because people would assume the opposite. A craft belongs to this install,
        // not this name, so two people can share a name without sharing anything else, and changing
        // it renames your craft instead of abandoning it.
        "Shown to other players. Your craft are tied to this device, not " +
            "to the name, so you can change it freely.",
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
            ". All your crew wear it, so others can tell whose they are. Each one's visor is their " +
            "own, and you can change it on the Crew screen. A new stripe shows from your next flight.",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
    )

    SectionHeading("Weather")
    ChoiceGroup(
        title = "In games you host",
        options = com.rm.apogee.core.weather.WeatherIntensity.entries,
        selected = settings.weatherIntensity,
        label = { it.label },
        description = {
            when (it) {
                com.rm.apogee.core.weather.WeatherIntensity.CALM -> "Light winds, gentle thermals, no storms"
                com.rm.apogee.core.weather.WeatherIntensity.NORMAL -> "Changeable: breezes, cloud, the odd storm"
                com.rm.apogee.core.weather.WeatherIntensity.WILD -> "Strong winds, frequent storms, lightning"
            }
        },
        onSelect = { settings.weatherIntensity = it },
    )
    ChoiceGroup(
        title = "Cloud cover",
        options = com.rm.apogee.core.weather.CloudCover.entries,
        selected = settings.cloudCover,
        label = { it.label },
        description = {
            when (it) {
                com.rm.apogee.core.weather.CloudCover.LIGHT -> "Scattered cloud, plenty of clear sky"
                com.rm.apogee.core.weather.CloudCover.NORMAL -> "Big patches of cloud, some of it overcast"
                com.rm.apogee.core.weather.CloudCover.HEAVY -> "Often overcast, sometimes breaking up"
            }
        },
        onSelect = { settings.cloudCover = it },
    )
    Text(
        "Everyone in a game flies in the host's weather. Your own world takes " +
            "the change from your next flight.",
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
        description = { it.description },
        onSelect = { settings.pitchStyle = it },
    )
    ChoiceGroup(
        title = "Steer by the screen",
        options = com.rm.apogee.settings.SteeringStyle.entries,
        selected = settings.steeringStyle,
        label = { it.label },
        description = { it.description },
        onSelect = { settings.steeringStyle = it },
    )
    Text(
        "By the screen, push the stick toward where you want to go as you see it. By the nose, " +
            "it works the craft's own controls. The chip under the stick swaps them for a flight.",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
    )

    SectionHeading("Layout")
    SwitchRow(
        title = "Left-hand layout",
        subtitle = "Mirror the flight controls",
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
        subtitle = "Let the view show through after a few seconds untouched",
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
        controllerName?.let { "Connected: $it" } ?: "No controller found. Connect one, or use the ones built in to a handheld.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
    )
    com.rm.apogee.ui.components.ApogeeButton(
        "Controller buttons",
        onController,
        subtitle = "Which button does what, flying and on foot",
    )
    SwitchRow(
        title = "Hide the touch stick",
        subtitle = "While a controller's flying, until the screen's touched",
        checked = settings.padHideTouch,
        onCheckedChange = { settings.padHideTouch = it },
    )
    SwitchRow(
        title = "Hold A to stage",
        subtitle = "So a brushed button can't drop a stage on the pad",
        checked = settings.padHoldToStage,
        onCheckedChange = { settings.padHoldToStage = it },
    )
}

@Composable
private fun DisplayTab(settings: GameSettings, detectedTier: QualityTier?, canScaleRender: Boolean) {
    SectionHeading("Performance")
    Text(
        text = detectedTier?.let {
            "Detected: ${it.name.lowercase()}, up to " +
                "${it.maxPartsPerVessel} parts per craft"
        } ?: "Measured on your first flight, because detection needs a graphics context.",
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
        "Leave all three unselected to follow detection.",
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
            description = {
                when (it) {
                    com.rm.apogee.render.Resolution.AUTO -> "Full, or less when that keeps it smooth"
                    com.rm.apogee.render.Resolution.FULL -> "Every pixel the screen has: the sharpest, and the slowest"
                    com.rm.apogee.render.Resolution.EIGHTY -> "A little softer, and a good deal faster"
                    com.rm.apogee.render.Resolution.TWO_THIRDS -> "Softer, and faster again"
                    com.rm.apogee.render.Resolution.HALF -> "The fastest. The HUD stays sharp at any of these"
                }
            },
            onSelect = { resolution = it; settings.resolution = it },
        )
    }

    SectionHeading("Shadows")
    // Remembered as the Compose state, so choosing one redraws the list straight away.
    var shadows by remember { mutableStateOf(settings.shadowQualityOverride) }
    val byTier = com.rm.apogee.render.ShadowQuality.defaultFor(settings.effectiveTier ?: QualityTier.MEDIUM)
    ChoiceGroup(
        title = "Shadow quality",
        options = listOf<com.rm.apogee.render.ShadowQuality?>(null) + com.rm.apogee.render.ShadowQuality.entries,
        selected = shadows,
        label = { it?.label ?: "Automatic (${byTier.label.lowercase()})" },
        description = {
            when (it) {
                null -> "As suits this device's graphics quality"
                com.rm.apogee.render.ShadowQuality.OFF -> "No shadows: the fastest"
                com.rm.apogee.render.ShadowQuality.LOW -> "Craft, trees and clouds, but no mountains"
                com.rm.apogee.render.ShadowQuality.MEDIUM -> "Adds mountains' shadows at dawn and dusk"
                com.rm.apogee.render.ShadowQuality.HIGH -> "Softer edges and mountains further out"
            }
        },
        onSelect = { shadows = it; settings.shadowQualityOverride = it },
    )

    SectionHeading("Diagnostics")
    SwitchRow(
        title = "Frame timing overlay",
        subtitle = "Frame time, simulation step time, tick count",
        checked = settings.showDebugOverlay,
        onCheckedChange = { settings.showDebugOverlay = it },
    )
}

@Composable
private fun AudioTab(settings: GameSettings) {
    SectionHeading("Sounds")
    SwitchRow(
        title = "Vehicle sounds",
        subtitle = "Engines, wheels, rushing air and the hull. Crashes still sound.",
        checked = settings.vehicleSoundEnabled,
        onCheckedChange = { settings.vehicleSoundEnabled = it },
    )
    SwitchRow(
        title = "Ambient sounds",
        subtitle = "Wind, rain, thunder, surf and fires",
        checked = settings.ambientSoundEnabled,
        onCheckedChange = { settings.ambientSoundEnabled = it },
    )
    SwitchRow(
        title = "Interface sounds",
        subtitle = "Button taps and the caution chime",
        checked = settings.uiSoundEnabled,
        onCheckedChange = { settings.uiSoundEnabled = it },
    )

    SectionHeading("Volume")
    VolumeRow("Everything", settings.masterVolume) { settings.masterVolume = it }
    VolumeRow("Craft and crashes", settings.effectsVolume) { settings.effectsVolume = it }
    VolumeRow("Wind, weather and places", settings.ambienceVolume) { settings.ambienceVolume = it }
    VolumeRow("Interface", settings.interfaceVolume) { settings.interfaceVolume = it }
    Text(
        "Music: coming later.",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
    )
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
            "Apogee is a sandbox for building vehicles and taking them wherever " +
                "they'll go: across the ground, through the air, over and " +
                "under the water, and into orbit.",
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

        // It's worth showing all of them. The version answers "which build is on this phone", which
        // matters when the answer is usually "the one I side-loaded". But it's the protocol number,
        // the terrain generation and the catalogue hash that decide whether a server will let you
        // in, and until now there was no way to read any of them from the device being refused.
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
