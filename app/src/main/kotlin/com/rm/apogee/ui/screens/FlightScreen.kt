package com.rm.apogee.ui.screens

import com.rm.apogee.ui.components.StageStack
import com.rm.apogee.ui.components.FuelBar
import com.rm.apogee.game.StageCard
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SwapHoriz
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
    /** Hold a navball marker. */
    onSasMode: (com.rm.apogee.core.world.SasMode) -> Unit = {},
    /** The next navball frame. */
    onCycleFrame: () -> Unit = {},
    /** Craft that can be targeted, nearest first, asked for when the picker opens. */
    targetChoices: () -> List<com.rm.apogee.game.GameSession.TargetChoice> = { emptyList() },
    /** Steer by a craft, or -1 for none. */
    onTarget: (Long) -> Unit = {},
    onToggleBrakes: () -> Unit,
    onToggleMap: () -> Unit,
    onJoin: () -> Unit,
    onSwitchCraft: () -> Unit,
    onExit: () -> Unit,
) {
    // BoxWithConstraints rather than the configuration's orientation: this is
    // a question about the space actually available, and the answer has to be
    // right in a resized window and in multi-window as well as after a
    // rotation. Asking the layout is asking the thing that decides.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxWidth < maxHeight
        val sas = SasActions(onToggleSas, onSasMode, targetChoices, onTarget)

        if (hud.connectionError != null) {
            ConnectionProblem(hud.connectionError!!, onExit)
            return@BoxWithConstraints
        }
        if (hud.connecting || !hud.surfaceReady) {
            // Opaque, and nothing drawn over it. The GL surface behind this is
            // live, and until the terrain patch lands it is showing a craft
            // suspended over a globe too coarse to have the ground under it -
            // which reads as a broken world rather than as one still loading.
            // A spinner floating over that picture does not help; covering it
            // does.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(ApogeeColors.BackdropBottom),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ApogeeColors.Accent)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        if (hud.connecting) "Joining\u2026" else "Shaping the ground\u2026",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.alpha(ApogeeAlpha.BODY),
                    )
                }
            }
            return@BoxWithConstraints
        }

        // --- top left: exit, craft name, diagnostics ------------------------
        // Only the *horizontal* cutout inset, so these sit up against the top
        // edge. Padding for the full cutout pushes them a notch's height down
        // the screen to clear something that is not above them: a punch-hole
        // or a notch is in the middle of the top edge, and both of these
        // corners are beside it, not under it. The horizontal inset still
        // applies, which is what matters in landscape where the cutout is
        // down one side and genuinely is in the way.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 6.dp),
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
                // Only worth showing once there is somewhere to switch to.
                if (hud.ownedCraft > 1) {
                    FilledTonalIconButton(
                        onClick = onSwitchCraft,
                        modifier = Modifier.size(Dimens.HudIconSize),
                    ) {
                        Icon(
                            Icons.Filled.SwapHoriz,
                            contentDescription = "Fly another craft",
                        )
                    }
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
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            TelemetryPanel(hud.telemetry, Modifier.alpha(controlOpacity), twoColumns = !portrait)
        }

        if (portrait) {
            // Throttle on one edge, attitude on the other, and the navball
            // above staging in between - low and to the left, where the eye
            // can take it in without leaving the controls.
            //
            // It sits beside the throttle rather than beneath it. Stacking the
            // two in one column put the ball in the extreme corner and cost
            // the throttle most of its travel, and neither was an improvement.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .alpha(controlOpacity),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                val throttle: @Composable () -> Unit = {
                    ThrottleControl(
                        hud,
                        onThrottleChange,
                        onToggleBrakes,
                        PORTRAIT_THROTTLE_HEIGHT,
                    )
                }
                // The stage stack rides above the attitude controls, on their
                // side of the screen: in the middle it stacked on the navball
                // and pushed both up into the view.
                val attitude: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StageStack(
                            hud.stages, hud.stagesExpanded, { hud.stagesExpanded = !hud.stagesExpanded },
                            width = PORTRAIT_CLUSTER_WIDTH, maxChips = 3,
                            detailWidth = 300.dp,
                        )
                        if (hud.stages.isNotEmpty()) Spacer(Modifier.height(10.dp))
                        AttitudeCluster(hud, onAttitude, onRoll, sas, PORTRAIT_STICK_SIZE)
                    }
                }

                if (leftHandMode) attitude() else throttle()

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (hud.canJoin) {
                        JoinButton(onJoin)
                        Spacer(Modifier.height(8.dp))
                    }
                    NavBall(
                        rotation = hud.telemetry.rotation,
                        worldUp = hud.telemetry.up,
                        prograde = hud.telemetry.prograde,
                        size = PORTRAIT_NAVBALL_SIZE,
                        normal = hud.telemetry.normal,
                        radialOut = hud.telemetry.radialOut,
                        frame = hud.telemetry.frame,
                        frameManual = hud.telemetry.frameChosen != com.rm.apogee.core.world.NavFrame.AUTO,
                        onCycleFrame = onCycleFrame,
                        toTarget = hud.telemetry.toTarget,
                        throughAir = hud.telemetry.throughAir,
                    )
                    Spacer(Modifier.height(10.dp))
                    StageButton(hud.telemetry.stage, onStage, current = hud.stages.firstOrNull { it.current })
                }

                if (leftHandMode) throttle() else attitude()
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
                ThrottleControl(hud, onThrottleChange, onToggleBrakes, THROTTLE_HEIGHT)
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
                AttitudeCluster(hud, onAttitude, onRoll, sas, STICK_SIZE)
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
                    normal = hud.telemetry.normal,
                    radialOut = hud.telemetry.radialOut,
                    frame = hud.telemetry.frame,
                    frameManual = hud.telemetry.frameChosen != com.rm.apogee.core.world.NavFrame.AUTO,
                    onCycleFrame = onCycleFrame,
                    toTarget = hud.telemetry.toTarget,
                    throughAir = hud.telemetry.throughAir,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (hud.canJoin) {
                        JoinButton(onJoin)
                        Spacer(Modifier.height(8.dp))
                    }
                    StageStack(
                        hud.stages, hud.stagesExpanded, { hud.stagesExpanded = !hud.stagesExpanded },
                        width = Dimens.HudActionBarWidth, maxChips = 3,
                    )
                    if (hud.stages.isNotEmpty()) Spacer(Modifier.height(6.dp))
                    StageButton(
                        hud.telemetry.stage,
                        onStage,
                        Modifier.width(Dimens.HudActionBarWidth),
                        current = hud.stages.firstOrNull { it.current },
                    )
                }
            }
        }
    }
}

/** The throttle, with its readout and label. */
@Composable
private fun ThrottleControl(
    hud: HudState,
    onThrottleChange: (Float) -> Unit,
    onToggleBrakes: () -> Unit,
    height: Dp,
) {
    val throttle = hud.throttle
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
        // Brakes live under the throttle: the thumb that pulls the power off
        // slides straight on to them. Only on a craft with wheels to brake.
        if (hud.hasWheels) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (hud.brakes) ApogeeColors.Danger.alpha(0.3f)
                    else Color.White.alpha(ApogeeAlpha.CONTROL_FILL),
                modifier = Modifier.clickable(onClick = onToggleBrakes),
            ) {
                Text(
                    "BRK",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (hud.brakes) ApogeeColors.Danger else Color.White.alpha(ApogeeAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** What the SAS button and its picker do. */
class SasActions(
    val onToggle: () -> Unit,
    val onMode: (com.rm.apogee.core.world.SasMode) -> Unit,
    val targetChoices: () -> List<com.rm.apogee.game.GameSession.TargetChoice>,
    val onTarget: (Long) -> Unit,
)

/** Roll, stability assist and the attitude stick, as one block. */
@Composable
private fun AttitudeCluster(
    hud: HudState,
    onAttitude: (pitch: Float, yaw: Float) -> Unit,
    onRoll: (Float) -> Unit,
    sas: SasActions,
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
            com.rm.apogee.ui.components.SasButton(
                enabled = hud.sasEnabled,
                mode = hud.telemetry.sasMode,
                expanded = hud.sasPickerOpen,
                onToggle = sas.onToggle,
                onExpand = { hud.sasPickerOpen = it },
                onMode = sas.onMode,
                targetChoices = sas.targetChoices,
                currentTarget = hud.telemetry.targetName,
                onTarget = sas.onTarget,
            )
            HoldButton("↻", { held -> onRoll(if (held) 1f else 0f) }, size = 40.dp)
        }
        Spacer(Modifier.height(8.dp))
        AttitudeStick(onChange = onAttitude, size = stickSize)
    }
}

/**
 * Offered only while a weld would actually take.
 *
 * A button that is present but inert teaches the player that the control is
 * unreliable; one that appears the moment two modules are touching and still
 * teaches them the rule. The client works out the same condition the world
 * checks, so the two agree.
 */
@Composable
private fun JoinButton(onJoin: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Dimens.CornerActionBar),
        color = ApogeeColors.Prograde.alpha(0.85f),
        contentColor = Color(0xFF0C2418),
    ) {
        Row(
            Modifier
                .clickable(onClick = onJoin)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Link, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                "JOIN",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * @param compact drops the chevron and tightens the padding, for the portrait
 *   bottom row where the bar only gets the width the two controls leave it.
 *   Without it "STAGE 0" wraps onto two lines at about 130dp.
 */
@Composable
private fun StageButton(
    stage: Int,
    onStage: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** The stage burning now, whose fuel the button carries as a gauge. */
    current: StageCard? = null,
) {
    val ink = Color(0xFF1A1030)
    Surface(
        shape = RoundedCornerShape(Dimens.CornerActionBar),
        color = ApogeeColors.Accent.alpha(0.85f),
        contentColor = ink,
        modifier = modifier,
    ) {
        Column(
            Modifier
                .clickable(onClick = onStage)
                .padding(
                    horizontal = if (compact) 12.dp else 20.dp,
                    vertical = if (current?.fuelFraction != null) 8.dp else 12.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!compact) {
                    Icon(Icons.Filled.KeyboardDoubleArrowUp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    "STAGE $stage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            // The burning stage's fuel, in the button's own ink - a gauge in
            // accent would vanish against it - with a number beside it.
            val fraction = current?.fuelFraction
            if (fraction != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuelBar(
                        fraction,
                        Modifier.width(96.dp),
                        track = ink.alpha(0.2f),
                        fill = if (fraction < 0.05f) ApogeeColors.Danger.copy(red = 0.7f) else ink.alpha(0.85f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${(fraction * 100).roundToInt()}%", style = TelemetryTextStyle, color = ink)
                }
            }
        }
    }
}
@Composable
private fun TelemetryPanel(telemetry: FlightTelemetry, modifier: Modifier = Modifier, twoColumns: Boolean = false) {
    // Two columns in landscape - near the ground, then the orbit and target -
    // where one tall column ran down over the roll and SAS buttons.
    val panel = modifier
        .clip(RoundedCornerShape(Dimens.CornerSmall))
        .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
        .padding(horizontal = 12.dp, vertical = 8.dp)
    if (twoColumns) {
        Row(panel, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(horizontalAlignment = Alignment.End) { SurfaceReadouts(telemetry) }
            Column(horizontalAlignment = Alignment.End) { OrbitReadouts(telemetry) }
        }
    } else {
        Column(panel, horizontalAlignment = Alignment.End) {
            SurfaceReadouts(telemetry)
            OrbitReadouts(telemetry)
        }
    }
}

@Composable
private fun SurfaceReadouts(telemetry: FlightTelemetry) {
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
    // Climb or sink, and which way the nose points on the compass.
    Readout(
        "VS",
        (if (telemetry.verticalSpeed >= 0) "+" else "\u2212") + "${kotlin.math.abs(telemetry.verticalSpeed).roundToInt()} m/s",
        colour = if (telemetry.verticalSpeed < -10.0 && telemetry.heightAboveGround < 500.0) ApogeeColors.Caution else ApogeeColors.Data,
    )
    Readout("HDG", "%03d\u00b0".format(telemetry.heading.roundToInt() % 360))
    // Through the air, and the air itself - only where there is some.
    if (telemetry.inAir) {
        Readout("AIR", "${telemetry.airspeed.roundToInt()} m/s")
        Readout(
            "WIND",
            "${windArrow(telemetry.windFrom)} ${telemetry.windSpeed.roundToInt()} m/s",
            colour = if (telemetry.windSpeed > 15.0) ApogeeColors.Caution else ApogeeColors.Data,
        )
    }
}

@Composable
private fun OrbitReadouts(telemetry: FlightTelemetry) {
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
    telemetry.targetName?.let { name ->
        Spacer(Modifier.height(4.dp))
        Readout("TGT", name.take(12), colour = TARGET_COLOUR)
        Readout("DST", formatDistance(telemetry.targetDistance), colour = TARGET_COLOUR)
        Readout(
            "CLS",
            (if (telemetry.closingSpeed >= 0) "" else "\u2212") + "${kotlin.math.abs(telemetry.closingSpeed).format(1)} m/s",
            colour = TARGET_COLOUR,
        )
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
/**
 * An arrow for the way the wind is blowing, relative to the nose: the
 * direction it goes, so a headwind (from 0 degrees) points down the screen.
 */
private fun windArrow(fromDegrees: Double): String {
    val towards = ((fromDegrees + 180.0) % 360.0 + 360.0) % 360.0
    val arrows = arrayOf("\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196")
    return arrows[((towards + 22.5) / 45.0).toInt() % 8]
}

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
private val TARGET_COLOUR = androidx.compose.ui.graphics.Color(0xFFFF5FD2)
private val NAVBALL_SIZE = 128.dp

// Portrait is short of width and generous with height, so the throttle takes
// the height: a longer throttle is a finer throttle, over the same 0-100%.
private val PORTRAIT_THROTTLE_HEIGHT = 210.dp
private val PORTRAIT_STICK_SIZE = 122.dp
private val PORTRAIT_NAVBALL_SIZE = 100.dp

/** The roll and SAS row above the portrait stick: three 40dp buttons and their gaps. */
private val PORTRAIT_CLUSTER_WIDTH = 140.dp
