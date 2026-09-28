package com.rm.apogee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.rm.apogee.core.world.LaunchTime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rm.apogee.ui.components.ApogeeButton
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.alpha

@Composable
fun MainMenuScreen(onNavigate: (AppScreen) -> Unit) {
    Backdrop { contentModifier ->
        Text(
            "APOGEE",
            style = MaterialTheme.typography.displayLarge,
            color = Color.White,
        )
        Text(
            "build · launch · land · repeat",
            style = MaterialTheme.typography.bodyMedium,
            color = ApogeeColors.Accent,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))

        ApogeeButton("Play", { onNavigate(AppScreen.PLAY) }, contentModifier)
        ApogeeButton("Settings", { onNavigate(AppScreen.SETTINGS) }, contentModifier)
        ApogeeButton("About", { onNavigate(AppScreen.ABOUT) }, contentModifier)
    }
}

@Composable
fun PlayScreen(
    onNavigate: (AppScreen) -> Unit,
    launchTime: LaunchTime = LaunchTime.NOW,
    onLaunchTime: (LaunchTime) -> Unit = {},
    /** On the career world instead of the sandbox. */
    career: Boolean = false,
    onCareer: (Boolean) -> Unit = {},
    /** The career's insight to spend, when you're on it. */
    insight: Int? = null,
    /** Whether games can be hosted and joined here. A web page can't open the sockets they need. */
    networked: Boolean = true,
    onBack: () -> Unit = {},
) {
    var chosen by remember { mutableStateOf(launchTime) }
    Backdrop { contentModifier ->
        Text(
            "Play",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        Spacer(Modifier.height(16.dp))
        // Two worlds: a career grown from nothing, or everything at once.
        PillRow(listOf("Career" to true, "Sandbox" to false), career, onCareer)
        Spacer(Modifier.height(20.dp))
        if (career) {
            ApogeeButton(
                "Program",
                { onNavigate(AppScreen.PROGRAM) },
                contentModifier,
                subtitle = (insight?.let { "$it insight · " } ?: "") + "Your tech tree, your feats, the worlds",
            )
        }

        // Not in a career, because there's no stock craft to put on the pad. It starts from
        // scratch, in the Vehicle Assembly.
        if (!career) {
            ApogeeButton(
                "Quick Launch",
                { onNavigate(AppScreen.QUICK_LAUNCH) },
                contentModifier,
                subtitle = "Pick a craft and a site, and go",
            )
        }
        // When in the day to go up, for Free Flight and the builder's launches.
        LaunchTimeRow(chosen, contentModifier) { chosen = it; onLaunchTime(it) }
        ApogeeButton(
            "Out There",
            { onNavigate(AppScreen.RESUME_FLIGHT) },
            contentModifier,
            subtitle = "Go back to any craft you left out there, or tidy them away",
        )
        ApogeeButton(
            "Crew",
            { onNavigate(AppScreen.CREW) },
            contentModifier,
            subtitle = "Who's at home, who's out there, and who was lost",
        )
        ApogeeButton(
            "Vehicle Assembly",
            { onNavigate(AppScreen.BUILDER) },
            contentModifier,
            subtitle = "Build a craft, then launch it",
        )
        if (networked) {
            ApogeeButton(
                "Host a Game",
                { onNavigate(AppScreen.HOST_GAME) },
                contentModifier,
                subtitle = "Let others on your network join",
            )
            ApogeeButton(
                "Join a Game",
                { onNavigate(AppScreen.JOIN_GAME) },
                contentModifier,
                subtitle = "Find one nearby, or type an address",
            )
        }
        Spacer(Modifier.height(12.dp))
        com.rm.apogee.ui.components.BackButton(onBack)
    }
}

/** One of a few, as a row of pills, with the chosen one lit. */
@Composable
internal fun <T> PillRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, value) in options) {
            val on = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) ApogeeColors.Accent else ApogeeColors.SurfaceRaised)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) ApogeeColors.Surface else Color.White.alpha(ApogeeAlpha.BODY),
                )
            }
        }
    }
}

/** Launch now, or at the next dawn, noon, dusk or midnight at the pad. */
@Composable
private fun LaunchTimeRow(selected: LaunchTime, modifier: Modifier, onSelect: (LaunchTime) -> Unit) {
    Column(modifier.padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Launch at",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in LaunchTime.entries) {
                val on = option == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) ApogeeColors.Accent else ApogeeColors.SurfaceRaised)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) ApogeeColors.Surface else Color.White.alpha(ApogeeAlpha.BODY),
                    )
                }
            }
        }
    }
}
