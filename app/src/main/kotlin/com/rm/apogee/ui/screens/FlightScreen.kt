package com.rm.apogee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.Dp
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
    // BoxWithConstraints rather than the configuration's orientation: this is
    // a question about the space actually available, and the answer has to be
    // right in a resized window and in multi-window as well as after a
    // rotation. Asking the layout is asking the thing that decides.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxWidth < maxHeight

        if (hud.connectionError != null) {
            ConnectionProblem(hud.connectionError!!, onExit)
            return@BoxWithConstraints
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
                // The craft name is the first thing to go when the screen is
                // narrow: the telemetry panel opposite is not optional and
                // the two meet in the middle on a portrait phone.
                if (!portrait && hud.telemetry.craftName.isNotEmpty()) {
                    Text(
                        hud.telemetry.craftName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.alpha(ApogeeAlpha.SECONDARY),
                    )
                }
            }
            if (showDebugOverlay) {
                Spacer(Modifier.height(8.dp))
                DebugOverlay(hud)
            }
        }

        // --- top right: telemetry -------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            TelemetryPanel(hud.telemetry, Modifier.alpha(controlOpacity))
        }

        if (portrait) {
            // Everything in one bottom stack, because the controls that sit in
            // opposite corners of a landscape screen do not fit side by side
            // on a portrait one: throttle, navball, staging and the attitude
            // cluster want about 640dp of width and a portrait phone has 390.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .alpha(controlOpacity),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.HudGroupGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavBall(
                        rotation = hud.telemetry.rotation,
                        worldUp = hud.telemetry.up,
                        prograde = hud.telemetry.prograde,
                        size = PORTRAIT_NAVBALL_SIZE,
                    )
                    // Takes whatever is left rather than a fixed width, so the
                    // pair always spans the screen exactly once.
                    StageButton(hud.telemetry.stage, onStage, Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (leftHandMode) {
                        AttitudeCluster(hud, onAttitude, onRoll, onToggleSas, PORTRAIT_STICK_SIZE)
                        ThrottleControl(hud.throttle, onThrottleChange, PORTRAIT_THROTTLE_HEIGHT)
                    } else {
                        ThrottleControl(hud.throttle, onThrottleChange, PORTRAIT_THROTTLE_HEIGHT)
                        AttitudeCluster(hud, onAttitude, onRoll, onToggleSas, PORTRAIT_STICK_SIZE)
                    }
                }
            }
        } else {
            // Landscape: anchored to the corners, with nothing in the middle.
            // Throttle and attitude sit on opposite edges so the two thumbs
            // never cross, and swap sides together in left-hand mode.
            val throttleAlignment =
                if (leftHandMode) Alignment.BottomEnd else Alignment.BottomStart
            Box(
                modifier = Modifier
                    .align(throttleAlignment)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .alpha(controlOpacity),
            ) {
                ThrottleControl(hud.throttle, onThrottleChange, THROTTLE_HEIGHT)
            }

            val stickAlignment =
                if (leftHandMode) Alignment.BottomStart else Alignment.BottomEnd
            Box(
                modifier = Modifier
                    .align(stickAlignment)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .alpha(controlOpacity),
            ) {
                AttitudeCluster(hud, onAttitude, onRoll, onToggleSas, STICK_SIZE)
            }

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
                StageButton(
                    hud.telemetry.stage,
                    onStage,
                    Modifier.width(Dimens.HudActionBarWidth),
                )
            }
        }
    }
}

/** The throttle, with its readout and label. */
@Composable
private fun ThrottleControl(
    throttle: Float,
    onThrottleChange: (Float) -> Unit,
    height: Dp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${(throttle * 100).roundToInt()}%",
            style = TelemetryTextStyle,
            color = ApogeeColors.Accent,
        )
        Spacer(Modifier.height(6.dp))
        VerticalAxisSlider(
            value = throttle,
            onValueChange = onThrottleChange,
            modifier = Modifier.width(44.dp).height(height),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "THR",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
        )
    }
}

/** Roll, stability assist and the attitude stick, as one block. */
@Composable
private fun AttitudeCluster(
    hud: HudState,
    onAttitude: (pitch: Float, yaw: Float) -> Unit,
    onRoll: (Float) -> Unit,
    onToggleSas: () -> Unit,
    stickSize: Dp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoldButton("↺", { held -> onRoll(if (held) -1f else 0f) }, size = 40.dp)
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
            HoldButton("↻", { held -> onRoll(if (held) 1f else 0f) }, size = 40.dp)
        }
        Spacer(Modifier.height(8.dp))
        AttitudeStick(onChange = onAttitude, size = stickSize)
    }
}

@Composable
private fun StageButton(stage: Int, onStage: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(Dimens.CornerActionBar),
        color = ApogeeColors.Accent.alpha(0.85f),
        contentColor = Color(0xFF1A1030),
        modifier = modifier,
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
                "STAGE $stage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
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

// Portrait has about half the width and rather more height, so the controls
// shrink sideways and the stack grows downward instead.
private val PORTRAIT_THROTTLE_HEIGHT = 150.dp
private val PORTRAIT_STICK_SIZE = 118.dp
private val PORTRAIT_NAVBALL_SIZE = 96.dp
