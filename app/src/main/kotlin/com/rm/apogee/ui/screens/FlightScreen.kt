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
import androidx.compose.material.icons.filled.Public
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
import com.rm.apogee.ui.components.ApogeeButton
import com.rm.apogee.ui.components.AttitudeStick
import com.rm.apogee.ui.components.HoldButton
import com.rm.apogee.ui.components.NavBall
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
 *
 * Layout is anchored to the four corners and the two side edges, with nothing
 * in the middle: the craft is what the player is looking at, and a control that
 * drifts into the centre of the screen is a control in the way. Throttle and
 * attitude sit on opposite edges so the two thumbs never cross, and swap sides
 * together when left-hand mode is on.
 */
@Composable
fun FlightScreen(
    hud: HudState,
    controlOpacity: Float,
    showDebugOverlay: Boolean,
    leftHandMode: Boolean,
    onThrottleChange: (Float) -> Unit,
    onAttitude: (pitch: Float, yaw: Float) -> Unit,
    onRoll: (Float) -> Unit,
    onStage: () -> Unit,
    onToggleSas: () -> Unit,
    onToggleMap: () -> Unit,
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

        // --- top left: exit, craft name, diagnostics ------------------------
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.alpha(controlOpacity),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.HudGroupGap),
            ) {
                FilledTonalIconButton(
                    onClick = onExit,
                    modifier = Modifier.size(Dimens.HudIconSize),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Leave flight")
                }
                FilledTonalIconButton(
                    onClick = onToggleMap,
                    modifier = Modifier.size(Dimens.HudIconSize),
                    colors = if (hud.mapMode) {
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = ApogeeColors.Accent.alpha(0.35f),
                            contentColor = ApogeeColors.Accent,
                        )
                    } else {
                        IconButtonDefaults.filledTonalIconButtonColors()
                    },
                ) {
                    Icon(Icons.Filled.Public, contentDescription = "Map view")
                }
                if (hud.telemetry.craftName.isNotEmpty()) {
                    Text(
                        hud.telemetry.craftName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.alpha(ApogeeAlpha.SECONDARY),
                    )
                }
            }
            // The only corner with nothing competing for it - the right side
            // carries telemetry above and the attitude cluster below.
            if (showDebugOverlay) {
                Spacer(Modifier.height(8.dp))
                DebugOverlay(hud)
            }
        }

        // --- top right: telemetry, with diagnostics stacked under it --------
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            TelemetryPanel(hud.telemetry, Modifier.alpha(controlOpacity))
        }

        // --- throttle, on the player's chosen side --------------------------
        // Bottom-anchored, like the attitude cluster opposite it: in landscape
        // both thumbs rest in the bottom corners, and a vertically-centred
        // control has to be reached up for.
        val throttleAlignment = if (leftHandMode) Alignment.BottomEnd else Alignment.BottomStart
        Column(
            modifier = Modifier
                .align(throttleAlignment)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(controlOpacity),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${(hud.throttle * 100).roundToInt()}%",
                style = TelemetryTextStyle,
                color = ApogeeColors.Accent,
            )
            Spacer(Modifier.height(6.dp))
            VerticalAxisSlider(
                value = hud.throttle,
                onValueChange = onThrottleChange,
                modifier = Modifier
                    .width(44.dp)
                    .height(THROTTLE_HEIGHT),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "THR",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            )
        }

        // --- attitude cluster, opposite the throttle ------------------------
        // Anchored to the bottom corner rather than the vertical centre: the
        // telemetry stack grows downward from the top corner, and a centred
        // stick ends up underneath it exactly when there is most to read.
        val stickAlignment = if (leftHandMode) Alignment.BottomStart else Alignment.BottomEnd
        Column(
            modifier = Modifier
                .align(stickAlignment)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(controlOpacity),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HoldButton("\u21ba", { held -> onRoll(if (held) -1f else 0f) }, size = 40.dp)
                // Stability assist lives with the attitude controls, not with
                // staging: it is the thing that holds an attitude for you.
                FilledTonalIconButton(
                    onClick = onToggleSas,
                    modifier = Modifier.size(40.dp),
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
                HoldButton("\u21bb", { held -> onRoll(if (held) 1f else 0f) }, size = 40.dp)
            }
            Spacer(Modifier.height(8.dp))
            AttitudeStick(onChange = onAttitude, size = STICK_SIZE)
        }

        // --- bottom strip: navball and staging ------------------------------
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp)
                .alpha(controlOpacity),
            horizontalArrangement = Arrangement.spacedBy(Dimens.HudGroupGap),
            verticalAlignment = Alignment.Bottom,
        ) {
            NavBall(
                rotation = hud.telemetry.rotation,
                worldUp = hud.telemetry.up,
                prograde = hud.telemetry.prograde,
                size = NAVBALL_SIZE,
            )

            Surface(
                shape = RoundedCornerShape(Dimens.CornerActionBar),
                color = ApogeeColors.Accent.alpha(0.85f),
                contentColor = Color(0xFF1A1030),
                modifier = Modifier.width(Dimens.HudActionBarWidth),
            ) {
                Row(
                    Modifier
                        .clickable(onClick = onStage)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
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
        // Above the ground, not above the datum. The launch complex sits most
        // of a kilometre up, so the two disagree from the moment you spawn,
        // and only one of them tells you whether you are about to land.
        if (telemetry.heightAboveGround < 20_000.0) {
            Readout(
                "AGL",
                formatDistance(telemetry.heightAboveGround),
                colour = if (telemetry.heightAboveGround < 200.0) ApogeeColors.Caution
                else ApogeeColors.Data,
            )
        }
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
            if (telemetry.periapsisAltitude < 0) "suborbital"
            else formatDistance(telemetry.periapsisAltitude),
            colour = if (telemetry.periapsisAltitude < 0) ApogeeColors.Caution
            else ApogeeColors.Prograde,
        )
        if (telemetry.timeToApoapsis.isFinite() && telemetry.apoapsisAltitude > 1_000) {
            Readout("T-AP", formatDuration(telemetry.timeToApoapsis))
        }
        if (telemetry.dynamicPressure > 100.0) {
            Spacer(Modifier.height(4.dp))
            Readout(
                "Q",
                "${(telemetry.dynamicPressure / 1000).format(1)} kPa",
                colour = if (telemetry.highDynamicPressure) ApogeeColors.Danger
                else ApogeeColors.Data,
            )
        }
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
                ApogeeButton("Back", onExit)
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

/** Seconds as m:ss, which is how a burn countdown is actually read. */
private fun formatDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "--"
    val total = seconds.roundToInt()
    return if (total >= 60) "%d:%02d".format(total / 60, total % 60) else "${total}s"
}

private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)

private val THROTTLE_HEIGHT = 170.dp
private val STICK_SIZE = 132.dp
private val NAVBALL_SIZE = 128.dp
