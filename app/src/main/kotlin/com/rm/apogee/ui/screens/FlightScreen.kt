package com.rm.apogee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.FlightTelemetry
import com.rm.apogee.game.HudState
import com.rm.apogee.ui.components.VerticalAxisSlider
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The overlay drawn on top of the rendered world.
 *
 * Transparent by construction - this sits in a ComposeView above the
 * GLSurfaceView - so every panel carries its own scrim to stay readable against
 * whatever the camera happens to be pointing at.
 */
@Composable
fun FlightScreen(
    hud: HudState,
    controlOpacity: Float,
    showDebugOverlay: Boolean,
    leftHandMode: Boolean,
    onThrottleChange: (Float) -> Unit,
    onStage: () -> Unit,
    onToggleSas: () -> Unit,
    onExit: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {

        if (hud.connectionError != null) {
            ConnectionProblem(hud.connectionError!!, onExit)
            return@Box
        }
        if (hud.connecting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ApogeeColors.Accent)
            }
        }

        // --- top left: exit and craft name ---------------------------------
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(12.dp)
                .alpha(controlOpacity),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.HudGroupGap),
        ) {
            FilledTonalIconButton(onClick = onExit, modifier = Modifier.size(Dimens.HudIconSize)) {
                Icon(Icons.Filled.Close, contentDescription = "Leave flight")
            }
            if (hud.telemetry.craftName.isNotEmpty()) {
                Text(
                    hud.telemetry.craftName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.alpha(ApogeeAlpha.SECONDARY),
                )
            }
        }

        // --- top right: telemetry ------------------------------------------
        TelemetryPanel(
            telemetry = hud.telemetry,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(12.dp)
                .alpha(controlOpacity),
        )

        // --- throttle, on the side the player asked for ---------------------
        val throttleAlignment = if (leftHandMode) Alignment.CenterEnd else Alignment.CenterStart
        Column(
            modifier = Modifier
                .align(throttleAlignment)
                .padding(horizontal = 20.dp)
                .alpha(controlOpacity),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${(hud.throttle * 100).roundToInt()}%",
                style = TelemetryTextStyle,
                color = ApogeeColors.Accent,
            )
            Spacer(Modifier.height(8.dp))
            VerticalAxisSlider(
                value = hud.throttle,
                onValueChange = onThrottleChange,
                modifier = Modifier
                    .width(48.dp)
                    .height(200.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "THR",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            )
        }

        // --- bottom: stage and SAS ------------------------------------------
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
                .alpha(controlOpacity),
            horizontalArrangement = Arrangement.spacedBy(Dimens.HudGroupGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onToggleSas,
                modifier = Modifier.size(Dimens.HudIconSize),
                colors = if (hud.sasEnabled) {
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = ApogeeColors.Prograde.alpha(0.3f),
                        contentColor = ApogeeColors.Prograde,
                    )
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                },
            ) {
                Icon(Icons.Filled.Explore, contentDescription = "Stability assist")
            }

            Surface(
                shape = RoundedCornerShape(Dimens.CornerActionBar),
                color = ApogeeColors.Accent.alpha(0.85f),
                contentColor = Color(0xFF1A1030),
                modifier = Modifier.width(Dimens.HudActionBarWidth),
            ) {
                Row(
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .clickable(onClick = onStage),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.KeyboardDoubleArrowUp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "STAGE ${hud.telemetry.stage}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        if (showDebugOverlay) {
            DebugOverlay(
                hud = hud,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun TelemetryPanel(telemetry: FlightTelemetry, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.CornerSmall))
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Readout("ALT", formatDistance(telemetry.altitude))
        Readout("SRF", "${telemetry.surfaceSpeed.roundToInt()} m/s")
        Readout("ORB", "${telemetry.orbitalSpeed.roundToInt()} m/s")
        Spacer(Modifier.height(4.dp))
        Readout(
            "AP",
            formatDistance(telemetry.apoapsisAltitude),
            colour = if (telemetry.inOrbit) ApogeeColors.Prograde else ApogeeColors.Data,
        )
        Readout(
            "PE",
            // A periapsis underground is not a number, it is a warning: it
            // means the current trajectory ends in the ground.
            if (telemetry.periapsisAltitude < 0) "suborbital" else formatDistance(telemetry.periapsisAltitude),
            colour = if (telemetry.periapsisAltitude < 0) ApogeeColors.Caution else ApogeeColors.Prograde,
        )
    }
}

@Composable
private fun Readout(label: String, value: String, colour: Color = ApogeeColors.Data) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = TelemetryTextStyle,
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
        )
        Spacer(Modifier.width(10.dp))
        Text(value, style = TelemetryTextStyle, color = colour)
    }
}

@Composable
private fun DebugOverlay(hud: HudState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.CornerSmall))
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text("frame %5.2f ms".format(hud.frameTimeMillis), style = TelemetryTextStyle)
        Text("build %5.3f ms".format(hud.frameBuildMillis), style = TelemetryTextStyle)
        Text("tick  %d".format(hud.simTick), style = TelemetryTextStyle)
        Text("parts %d".format(hud.drawnItems), style = TelemetryTextStyle)
    }
}

@Composable
private fun ConnectionProblem(message: String, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(Dimens.CornerPanel),
            color = Color.Black.alpha(ApogeeAlpha.SCRIM_HEAVY),
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Could not join",
                    style = MaterialTheme.typography.titleMedium,
                    color = ApogeeColors.Danger,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.alpha(ApogeeAlpha.BODY),
                )
                Spacer(Modifier.height(16.dp))
                com.rm.apogee.ui.components.ApogeeButton("Back", onExit)
            }
        }
    }
}

/** Metres below a kilometre, kilometres above it. */
private fun formatDistance(metres: Double): String {
    val magnitude = abs(metres)
    return when {
        magnitude >= 1_000_000 -> "%.1f Mm".format(metres / 1_000_000)
        magnitude >= 1_000 -> "%.2f km".format(metres / 1_000)
        else -> "%d m".format(metres.roundToInt())
    }
}
