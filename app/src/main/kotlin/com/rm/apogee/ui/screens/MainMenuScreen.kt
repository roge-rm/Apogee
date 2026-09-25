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
) {
    var chosen by remember { mutableStateOf(launchTime) }
    Backdrop { contentModifier ->
        Text(
            "Play",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        Spacer(Modifier.height(28.dp))

        ApogeeButton(
            "Free Flight",
            { onNavigate(AppScreen.FLIGHT) },
            contentModifier,
            subtitle = "A fresh craft on the pad, in place of your last one",
        )
        // When in the day to go up - for Free Flight and the builder's launches.
        LaunchTimeRow(chosen, contentModifier) { chosen = it; onLaunchTime(it) }
        ApogeeButton(
            "Resume Flight",
            { onNavigate(AppScreen.RESUME_FLIGHT) },
            contentModifier,
            subtitle = "Fly any craft you left out there, or tidy them away",
        )
        ApogeeButton(
            "Vehicle Assembly",
            { onNavigate(AppScreen.BUILDER) },
            contentModifier,
            subtitle = "Build a craft, then launch it",
        )
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
