package com.rm.apogee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.HudState
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha

/**
 * The overlay drawn on top of the rendered world.
 *
 * Note there is no backdrop here: this composable is transparent, sitting in a
 * ComposeView above the GLSurfaceView. Every panel carries its own scrim so it
 * stays readable against whatever is behind it.
 */
@Composable
fun FlightScreen(
    hud: HudState,
    controlOpacity: Float,
    showDebugOverlay: Boolean,
    onExit: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(12.dp)
                .alpha(controlOpacity),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.HudGroupGap),
        ) {
            FilledTonalIconButton(onClick = onExit) {
                Icon(Icons.Filled.Close, contentDescription = "Leave flight")
            }
        }

        if (showDebugOverlay) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(Dimens.CornerSmall))
                    .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text("frame  %5.2f ms".format(hud.frameTimeMillis), style = TelemetryTextStyle)
                Text("step   %5.3f ms".format(hud.simStepMillis), style = TelemetryTextStyle)
                Text("tick   %d".format(hud.simTick), style = TelemetryTextStyle)
                Text("items  %d".format(hud.drawnItems), style = TelemetryTextStyle)
            }
        }

        Text(
            "M0 — render path, fixed-step clock and frame hand-off",
            style = TelemetryTextStyle,
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(12.dp),
        )
    }
}
