package com.rm.apogee.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
fun PlayScreen(onNavigate: (AppScreen) -> Unit) {
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
            subtitle = "Solo, running against a local server",
        )
        ApogeeButton(
            "Vehicle Assembly",
            { onNavigate(AppScreen.BUILDER) },
            contentModifier,
            subtitle = "Not yet available",
            enabled = false,
        )
        ApogeeButton(
            "Host a Game",
            { onNavigate(AppScreen.HOST_GAME) },
            contentModifier,
            subtitle = "Not yet available",
            enabled = false,
        )
        ApogeeButton(
            "Join a Game",
            { onNavigate(AppScreen.JOIN_GAME) },
            contentModifier,
            subtitle = "Not yet available",
            enabled = false,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "Single-player already runs as a one-player server, so hosting and " +
                "joining are a transport swap rather than a new code path.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            textAlign = TextAlign.Center,
            modifier = contentModifier,
        )
    }
}
