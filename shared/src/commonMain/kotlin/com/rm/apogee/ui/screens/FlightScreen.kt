package com.rm.apogee.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.HudFade
import com.rm.apogee.game.HudState
import com.rm.apogee.ui.components.ActionRail
import com.rm.apogee.ui.components.ApogeeButton
import com.rm.apogee.ui.components.AttitudeStick
import com.rm.apogee.ui.components.CraftSwitcher
import com.rm.apogee.ui.components.FlightStrip
import com.rm.apogee.ui.components.HoldButton
import com.rm.apogee.ui.components.NavBall
import com.rm.apogee.ui.components.NudgeButton
import com.rm.apogee.ui.components.PromptActions
import com.rm.apogee.ui.components.PromptSlot
import com.rm.apogee.ui.components.RailActions
import com.rm.apogee.ui.components.RoundStageButton
import com.rm.apogee.ui.components.StageTab
import com.rm.apogee.ui.components.StatusActions
import com.rm.apogee.ui.components.StatusRow
import com.rm.apogee.ui.components.VerticalAxisSlider
import com.rm.apogee.ui.components.WarpButton
import com.rm.apogee.ui.components.promptKey
import com.rm.apogee.ui.components.padFocus
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt
import com.rm.apogee.platform.System
import com.rm.apogee.platform.format

/**
 * The overlay drawn over the world. It's transparent (a ComposeView above the GLSurfaceView), so
 * every panel has its own scrim.
 *
 * Everything sits in the corners and along the sides, leaving the middle for the craft. Throttle
 * and attitude are on opposite edges so the thumbs never cross, and they swap in left-hand mode.
 */
@Composable
fun FlightScreen(
    hud: HudState,
    controlOpacity: Float,
    showDebugOverlay: Boolean,
    leftHandMode: Boolean,
    /** Let the controls fade back when they haven't been touched for a few seconds. */
    fadeWhenIdle: Boolean = true,
    /** A controller's flying, so the touch stick and roll buttons step aside. */
    hideTouchStick: Boolean = false,
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
    /** The worlds' names on the map, placed on a screen this wide and high. */
    mapLabels: (Float, Float) -> List<com.rm.apogee.game.GameSession.MapLabel> = { _, _ -> emptyList() },
    onToggleBrakes: () -> Unit,
    /** Arm the thrusters, or stand them down. */
    onToggleRcs: () -> Unit = {},
    /** Drive the wheels backwards, or forwards again. */
    onToggleReverse: () -> Unit = {},
    /** Fold the sun wings and dishes out, or away. */
    onToggleDeploy: () -> Unit = {},
    /** Switch the drills, and the converters. */
    onToggleDrill: () -> Unit = {},
    onToggleRefine: () -> Unit = {},
    onDive: () -> Unit = {},
    onRise: () -> Unit = {},
    onHoldDepth: () -> Unit = {},
    /** Flaps down or up, an action group switched, and the winch wound in or held. */
    onToggleFlaps: () -> Unit = {},
    onGroup: (Int) -> Unit = {},
    onWinch: () -> Unit = {},
    /** The keeper core holding the craft still, or not. */
    onStationKeep: () -> Unit = {},
    /** The winch's line hooked on, or let go. */
    onHook: () -> Unit = {},
    onReleaseLine: () -> Unit = {},
    onRightCraft: () -> Unit = {},
    /** Hold a plane's height and heading, or stop. */
    onCruise: (Boolean) -> Unit = {},
    /** Empty the craft's ore and water into a base or docked craft, or switch a base's refinery. */
    onUnload: (Boolean) -> Unit = {},
    onRefine: (com.rm.apogee.core.world.ServerMessage.BaseStatus, Boolean) -> Unit = { _, _ -> },
    /** With the thrusters armed, the stick slides the craft (true) or turns it. */
    onStickMode: (Boolean) -> Unit = {},
    /** The stick read by the screen (true) or by the craft's nose. */
    onSteering: (Boolean) -> Unit = {},
    /** The camera's next way of following the craft. */
    onCameraMode: () -> Unit = {},
    /** Let go at a docking part. */
    onUndock: (Int) -> Unit = {},
    onFound: (Boolean) -> Unit = {},
    onRefuel: (Boolean) -> Unit = {},
    /** Shared with another player: who flies it ("me", "them" or "both"). */
    onDockPilot: (String) -> Unit = {},
    onToggleMap: () -> Unit,
    onJoin: () -> Unit,
    onExit: () -> Unit,
    /** A tutorial finished: keep flying, or back to the list. */
    onTutorialKeep: () -> Unit = {},
    onTutorials: () -> Unit = {},
    /** Taking a save point, going back to it, and reverting to the launch. */
    rewind: RewindActions = RewindActions(),
    /** Run the world at this many times real time. 0 pauses it. */
    onWarp: (Double) -> Unit = {},
    /** The player's craft, asked for when the list opens. */
    craftChoices: () -> List<CraftSummary> = { emptyList() },
    /** The craft being flown, for the list, asked for when it opens. */
    currentCraft: () -> Long? = { null },
    onFlyCraft: (Long) -> Unit = {},
    onRemoveCraft: (Long) -> Unit = {},
    /** Take the craft being flown out of the world and go back to the menu. */
    onRetire: () -> Unit = {},
    /** Planning burns, and the autopilots. */
    burnActions: com.rm.apogee.ui.components.BurnActions = com.rm.apogee.ui.components.BurnActions(),
    crewActions: com.rm.apogee.ui.components.CrewActions = com.rm.apogee.ui.components.CrewActions(),
    /** This player's id, for the program's firsts. [onUnlock] gives null or why it failed. */
    me: String = "",
    onUnlock: (String) -> String? = { null },
) {
    // BoxWithConstraints, not the configuration's orientation, so it's right in a resized window
    // and in multi-window too.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxWidth < maxHeight
        // Remembered, so the rail, status row and prompts get the same holders and can skip.
        val sas = remember(onToggleSas, onSasMode, targetChoices, onTarget, onStickMode, onCruise, onSteering, burnActions) {
            SasActions(onToggleSas, onSasMode, targetChoices, onTarget, onStickMode, onCruise, onSteering, burnActions.onAutoLand)
        }
        val railActions = remember(onToggleBrakes, onToggleReverse, onToggleRcs, onToggleDeploy, onToggleDrill, onToggleRefine, crewActions, onDive, onRise, onHoldDepth, onToggleFlaps, onGroup, onWinch, onStationKeep) {
            RailActions(
                onBrakes = onToggleBrakes, onReverse = onToggleReverse, onRcs = onToggleRcs, onDeploy = onToggleDeploy,
                onDrill = onToggleDrill, onRefine = onToggleRefine, onJump = crewActions.onJump, onFlag = crewActions.onFlag,
                onDive = onDive, onRise = onRise, onHold = onHoldDepth,
                onFlaps = onToggleFlaps, onGroup = onGroup, onWinch = onWinch, onStationKeep = onStationKeep,
            )
        }
        val statusActions = remember(crewActions, onUndock, onFound, onRefuel, onUnload, onRefine, onDockPilot) {
            StatusActions(crewActions, onUndock, onFound, onRefuel, onUnload, onRefine, onDockPilot)
        }
        val promptActions = remember(onJoin, onFound, crewActions, onHook, onReleaseLine, onRightCraft, onTutorialKeep, onTutorials) {
            PromptActions(
                onJoin, onFound, crewActions.onBoard, crewActions.onGrab, onHook, onReleaseLine, onRightCraft, crewActions.onClimbOut,
                onTutorialKeep = onTutorialKeep, onTutorials = onTutorials,
            )
        }

        if (hud.connectionError != null) {
            ConnectionProblem(hud.connectionError!!, onExit)
            return@BoxWithConstraints
        }
        // The program, over the flight. In someone else's world it's the only way to see it.
        val career = hud.career
        if (hud.programOpen && career != null) {
            com.rm.apogee.ui.BackHandler { hud.programOpen = false }
            ProgramScreen(career, hud.worldFirsts, me, onUnlock, onClose = { hud.programOpen = false })
            return@BoxWithConstraints
        }
        if (hud.connecting || !hud.surfaceReady) {
            // Opaque. Until the terrain patch lands, the live GL view behind shows the craft over
            // a globe too coarse to have ground under it, and that looks broken.
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

        if (hud.exitMenuOpen) ExitMenu(hud, rewind, onExit, onClose = { hud.exitMenuOpen = false })

        // --- fading when idle -----------------------------------------------
        //
        // A few seconds untouched and the controls fade. A touch, a new warning or prompt, or the
        // engines running brings them back.
        var idle by remember { mutableStateOf(false) }
        // Derived, so readouts changing ten times a second only recompose these, and the rest
        // hears only when a key changes.
        val statusKey by remember(hud) { derivedStateOf { statusKey(hud) } }
        val promptKey by remember(hud) { derivedStateOf { promptKey(hud) } }
        LaunchedEffect(statusKey, promptKey) { hud.touched() }
        LaunchedEffect(fadeWhenIdle) {
            while (true) {
                val now = System.nanoTime()
                if (hud.throttle > 0f) hud.fade.wake(now)
                idle = fadeWhenIdle && hud.fade.idle(now)
                kotlinx.coroutines.delay(FADE_POLL_MS)
            }
        }
        val alpha by animateFloatAsState(
            targetValue = if (idle) controlOpacity * HudFade.FADED else controlOpacity,
            animationSpec = tween(if (idle) Dimens.MOTION_FADE_MS else FADE_BACK_MS),
            label = "hud fade",
        )

        if (hud.mapMode) MapNames(mapLabels)

        // --- top left: exit, map, craft, warp, and the craft's name ----------
        //
        // Only the horizontal cutout inset, so these sit hard against the top edge. That's the one
        // that matters in landscape. A hole in the top edge the buttons step round: the one that
        // would be under it goes past, and the rest follow.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            com.rm.apogee.ui.components.CutoutRow(
                modifier = Modifier.alpha(alpha),
                spacing = Dimens.HudGroupGap,
            ) {
                FilledTonalIconButton(
                    // Alone in your own world it opens a menu with save points. Otherwise it leaves.
                    onClick = { if (hud.canRewind) hud.exitMenuOpen = true else onExit() },
                    modifier = Modifier.size(Dimens.HudIconSize),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Leave")
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
                // Camera mode, with its name shown for a moment.
                if (!hud.mapMode) CameraButton(hud.cameraMode, onCameraMode, Dimens.HudIconSize)
                // The list of craft, and retiring this one.
                if (hud.ownedCraft > 0) {
                    CraftSwitcher(
                        current = currentCraft,
                        craft = craftChoices,
                        onFly = onFlyCraft,
                        onRemove = onRemoveCraft,
                        onRetire = onRetire,
                        size = Dimens.HudIconSize,
                        listOpen = hud.craftListOpen,
                        onListOpen = { hud.craftListOpen = it },
                    )
                }
                // Pause and time warp, only in a world nobody else is in.
                if (hud.warpAllowed) {
                    WarpButton(
                        warp = hud.warp,
                        requested = hud.warpRequested,
                        actual = hud.warpActual,
                        expanded = hud.warpPickerOpen,
                        onExpand = { hud.warpPickerOpen = it },
                        onWarp = onWarp,
                        size = Dimens.HudIconSize,
                    )
                }
                // The career's program: what to spend the insight you just earned on.
                if (career != null) {
                    FilledTonalIconButton(
                        onClick = { hud.programOpen = true },
                        modifier = Modifier.size(Dimens.HudIconSize),
                    ) {
                        Icon(Icons.Filled.AccountTree, contentDescription = "Program")
                    }
                }
                // The name is the first to go on a narrow screen, where it'd meet the flight strip.
                if (!portrait && hud.telemetry.craftName.isNotEmpty()) {
                    Text(
                        hud.telemetry.craftName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.alpha(ApogeeAlpha.SECONDARY),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
            }
            if (showDebugOverlay) {
                Spacer(Modifier.height(8.dp))
                DebugOverlay(hud)
            }
        }

        // Gone, so no controls, only what happened to it.
        hud.telemetry.destroyed?.let { report ->
            CrashCard(
                name = hud.telemetry.craftName,
                report = report,
                lost = hud.telemetry.lost,
                crewLost = hud.crewLost,
                onLeave = onExit,
                // Opens the craft list at the top.
                onFlyAnother = if (hud.ownedCraft > 0) ({ hud.craftListOpen = true }) else null,
                // Low, because the wreck is in the middle of the view.
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp),
            )
            return@BoxWithConstraints
        }

        // --- top right: the flight strip, and the status chips under it ------
        //
        // A few numbers, tapped open for the rest, then the craft's state in chips. In portrait
        // it goes below the buttons, since there's no room beside them.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .padding(top = if (portrait) Dimens.HudIconSize + 6.dp else 0.dp),
            horizontalAlignment = Alignment.End,
        ) {
            FlightStrip(
                hud.telemetry, hud.stripOpen, { hud.stripOpen = !hud.stripOpen },
                twoColumns = !portrait, power = hud.power,
                modifier = Modifier.alpha(alpha),
                current = if (hud.currentSpeed > 0f) hud.currentSpeed to hud.currentBearing else null,
                sailing = hud.hasSails,
                onSurface = hud.afloat || (hud.driving && hud.telemetry.heightAboveGround < DRIVING_HEIGHT),
                lift = hud.power?.lift ?: -1f,
                perLine = if (portrait) PORTRAIT_STRIP_PER_LINE else LANDSCAPE_STRIP_PER_LINE,
            )
            Spacer(Modifier.height(6.dp))
            StatusRow(
                hud, hud.chute, statusActions,
                fadedAlpha = alpha,
                detailHeight = if (portrait) 220.dp else LANDSCAPE_DETAIL_HEIGHT,
            )
        }

        // --- top middle: whatever asks for something now ---------------------
        PromptSlot(
            hud, burnActions, promptActions,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(top = if (portrait) PORTRAIT_PROMPT_TOP else LANDSCAPE_PROMPT_TOP, start = 12.dp, end = 12.dp),
        )

        // --- the bottom corners: throttle and switches, stick, navball, stage -
        //
        // Switches on the throttle's inner side, and the navball and STAGE low in the middle.
        val throttleGroup: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val rail: @Composable () -> Unit = {
                    ActionRail(hud, railActions, perColumn = if (portrait) PORTRAIT_RAIL_PER_COLUMN else LANDSCAPE_RAIL_PER_COLUMN)
                }
                if (leftHandMode) rail()
                ThrottleControl(hud, onThrottleChange, if (portrait) PORTRAIT_THROTTLE_HEIGHT else THROTTLE_HEIGHT)
                if (!leftHandMode) rail()
            }
        }
        val attitude: @Composable () -> Unit = {
            AttitudeCluster(
                hud, onAttitude, onRoll, sas,
                if (portrait) PORTRAIT_STICK_SIZE else STICK_SIZE,
                Modifier.cornerInset(leftHandMode, if (portrait) PORTRAIT_STICK_INSET else STICK_INSET),
                touchStick = !hideTouchStick,
            )
        }
        val navball: @Composable () -> Unit = {
            NavBall(
                // Read as it's drawn, so a new attitude each frame only redraws the ball.
                live = { hud.liveTelemetry },
                size = if (portrait) PORTRAIT_NAVBALL_SIZE else NAVBALL_SIZE,
                frame = hud.telemetry.frame,
                frameManual = hud.telemetry.frameChosen != com.rm.apogee.core.world.NavFrame.AUTO,
                onCycleFrame = onCycleFrame,
            )
        }
        // Someone on EVA has nothing to stage.
        val staging: @Composable () -> Unit = {
            if (!hud.isSuit) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StageTab(hud.stages, hud.stagesExpanded, { hud.stagesExpanded = !hud.stagesExpanded })
                    if (hud.stages.isNotEmpty()) Spacer(Modifier.height(6.dp))
                    RoundStageButton(hud.telemetry.stage, onStage, current = hud.stages.firstOrNull { it.current }, hold = hud.stageHold)
                }
            }
        }

        if (portrait) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .alpha(alpha),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (leftHandMode) attitude() else throttleGroup()
                // Stacked, since side by side they don't fit between the thumbs in portrait.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    navball()
                    Spacer(Modifier.height(8.dp))
                    staging()
                }
                if (leftHandMode) throttleGroup() else attitude()
            }
        } else {
            Box(
                modifier = Modifier
                    .align(if (leftHandMode) Alignment.BottomEnd else Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .alpha(alpha),
            ) { throttleGroup() }
            Box(
                modifier = Modifier
                    .align(if (leftHandMode) Alignment.BottomStart else Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .alpha(alpha),
            ) { attitude() }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 10.dp)
                    .alpha(alpha),
                horizontalArrangement = Arrangement.spacedBy(Dimens.HudGroupGap),
                verticalAlignment = Alignment.Bottom,
            ) {
                navball()
                staging()
            }
        }
    }
}

/** What's showing in the status row, as a key. When it changes, the controls wake up. */
private fun statusKey(hud: HudState): String = buildString {
    val t = hud.telemetry
    if (t.overheating) append("heat")
    if (t.straining) append("load")
    append(t.damaged).append('/').append(t.lost)
    hud.power?.let { if (!it.powered) append("dark"); if (it.low) append("low"); append(it.blocked) }
    append(hud.chute)
    append(hud.joints.size)
    if (hud.nearBase != null) append("base")
}

/** The throttle, with its readout and label. The craft's switches ride beside it, on the [ActionRail]. */
@Composable
private fun ThrottleControl(
    hud: HudState,
    onThrottleChange: (Float) -> Unit,
    height: Dp,
) {
    val throttle = hud.throttle
    // Out of touch, it doesn't move anything, so it's shown faded.
    Column(
        Modifier.alpha(if (hud.power?.outOfTouch != null) OUT_OF_TOUCH_ALPHA else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${(throttle * 100).roundToInt()}%",
            style = TelemetryTextStyle,
            color = ApogeeColors.Accent,
        )
        Spacer(Modifier.height(4.dp))
        NudgeButton("+", { onThrottleChange((hud.throttle + THROTTLE_STEP).coerceIn(0f, 1f)) })
        Spacer(Modifier.height(4.dp))
        VerticalAxisSlider(
            value = throttle,
            onValueChange = onThrottleChange,
            modifier = Modifier.width(44.dp).height(height),
            snapToZero = THROTTLE_SNAP,
        )
        Spacer(Modifier.height(4.dp))
        NudgeButton("−", { onThrottleChange((hud.throttle - THROTTLE_STEP).coerceIn(0f, 1f)) })
        Spacer(Modifier.height(4.dp))
        Text(
            "THR",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
        )
    }
}

/** What the flight's exit menu can do besides leave. */
class RewindActions(
    val onSavePoint: () -> Unit = {},
    val onLoadSavePoint: () -> Unit = {},
    val onRevert: () -> Unit = {},
)

/**
 * The X button's menu in your own world: save point, load it, revert to launch, or leave. Going
 * back asks first, since everything since is lost, feats too.
 */
@Composable
private fun ExitMenu(hud: HudState, rewind: RewindActions, onExit: () -> Unit, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) }
    val asking = confirm
    if (asking != null) {
        val loading = asking == "load"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (loading) "Load the save point?" else "Revert to the launch?") },
            text = {
                Text(
                    (if (loading) "Back to ${hud.savePoint}." else "Back to just before this launch.") +
                        if (hud.career != null) " Feats since then are lost." else "",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirm = null; onClose()
                    if (loading) rewind.onLoadSavePoint() else rewind.onRevert()
                }) { Text(if (loading) "Load" else "Revert") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = null }) { Text(hud.going.keep) } },
        )
        return
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(hud.telemetry.craftName.ifBlank { "Paused" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Save points only in your own world. Elsewhere Start and Back show just Leave.
                if (hud.canRewind) MenuRow("Save point", hud.savePoint?.let { "Last at $it" }) {
                    rewind.onSavePoint(); onClose()
                }
                if (hud.canRewind) MenuRow("Load save point", enabled = hud.savePoint != null) {
                    confirm = "load"
                }
                if (hud.canRewind) MenuRow("Revert to launch", enabled = hud.canRevert) {
                    confirm = "revert"
                }
                MenuRow("Leave") { onClose(); onExit() }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text(hud.going.keep) } },
    )
}

@Composable
private fun MenuRow(title: String, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .padFocus()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Color.White else Color.White.alpha(ApogeeAlpha.BORDER))
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.White.alpha(if (enabled) ApogeeAlpha.SUBTITLE else ApogeeAlpha.BORDER))
    }
}

/** What the SAS button and its picker do. */
class SasActions(
    val onToggle: () -> Unit,
    val onMode: (com.rm.apogee.core.world.SasMode) -> Unit,
    val targetChoices: () -> List<com.rm.apogee.game.GameSession.TargetChoice>,
    val onTarget: (Long) -> Unit,
    /** With the thrusters armed, the stick slides (true) or turns. */
    val onStickMode: (Boolean) -> Unit = {},
    /** Hold a plane's height and heading, or stop. */
    val onCruise: (Boolean) -> Unit = {},
    /** The stick read by the screen (true) or by the craft's nose. */
    val onSteering: (Boolean) -> Unit = {},
    /** The auto-land on or off, for something that flies on the air. */
    val onLand: (Boolean) -> Unit = {},
)

/** Roll, stability assist and the attitude stick, as one block. */
@Composable
private fun AttitudeCluster(
    hud: HudState,
    onAttitude: (pitch: Float, yaw: Float) -> Unit,
    onRoll: (Float) -> Unit,
    sas: SasActions,
    stickSize: Dp,
    modifier: Modifier = Modifier,
    /** The stick and roll buttons, or only SAS and what the stick does, with a controller flying. */
    touchStick: Boolean = true,
) {
    // Sliding on the thrusters, the roll buttons become down and up instead.
    val sliding = hud.rcsArmed && hud.rcsSlide
    val deaf = hud.power?.outOfTouch
    Column(modifier.alpha(if (deaf != null) OUT_OF_TOUCH_ALPHA else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (touchStick) HoldButton(if (sliding) "▼" else "↺", { held -> onRoll(if (held) -1f else 0f) }, size = 40.dp)
            // SAS sits with the attitude controls since it holds an attitude for you.
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
                canCruise = hud.canCruise,
                cruising = hud.power?.cruising == true,
                onCruise = sas.onCruise,
                keeping = hud.power?.keeping == true,
                canLand = hud.canLand,
                landing = hud.autoLanding && hud.canLand,
                onLand = sas.onLand,
            )
            if (touchStick) HoldButton(if (sliding) "▲" else "↻", { held -> onRoll(if (held) 1f else 0f) }, size = 40.dp)
        }
        Spacer(Modifier.height(8.dp))
        if (touchStick) {
            Box(contentAlignment = Alignment.Center) {
                AttitudeStick(onChange = onAttitude, size = stickSize)
                // Why it does nothing, written across it.
                if (deaf != null) {
                    Text(deaf, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ApogeeColors.Danger)
                }
            }
        } else if (deaf != null) {
            Text(deaf, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ApogeeColors.Danger)
        }
        // What the stick does, right under it.
        Spacer(Modifier.height(8.dp))
        StickModeChip(sliding, hud.steerByScreen, hud.rcsArmed, sas.onStickMode, sas.onSteering)
    }
}

/**
 * NOSE | SCREEN, and SLIDE with the thrusters armed. NOSE works the craft's own controls, SCREEN
 * points where you see you want to go, and SLIDE moves the craft on its thrusters.
 */
@Composable
private fun StickModeChip(
    sliding: Boolean,
    byScreen: Boolean,
    canSlide: Boolean,
    onStickMode: (Boolean) -> Unit,
    onSteering: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Dimens.CornerActionBar))
            .background(Color.White.alpha(ApogeeAlpha.CONTROL_FILL)),
    ) {
        val choices = buildList {
            add("NOSE"); add("SCREEN")
            if (canSlide) add("SLIDE")
        }
        for (label in choices) {
            val chosen = when (label) {
                "SLIDE" -> sliding
                "SCREEN" -> !sliding && byScreen
                else -> !sliding && !byScreen
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (chosen) Color(0xFF1A1030) else Color.White.alpha(ApogeeAlpha.SECONDARY),
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.CornerActionBar))
                    .background(if (chosen) ApogeeColors.Accent.alpha(0.85f) else Color.Transparent)
                    .clickable {
                        when (label) {
                            "SLIDE" -> onStickMode(true)
                            else -> { onStickMode(false); onSteering(label == "SCREEN") }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
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
        Text("voices %d".format(hud.voices), style = TelemetryTextStyle)
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

private val THROTTLE_HEIGHT = 140.dp

/** One press of the throttle's + or -. */
private const val THROTTLE_STEP = 0.01f

/** At or below this the throttle is off: the bottom of the track, and a little above it. */
private const val THROTTLE_SNAP = 0.07f

/** How faded the throttle and stick are while nothing sent to the craft is getting heard. */
private const val OUT_OF_TOUCH_ALPHA = 0.35f
private val STICK_SIZE = 132.dp

/** How far the stick sits in from its corner, past the margin. Any closer and it's awkward to reach. */
private val STICK_INSET = 40.dp

/** How tall a status chip's detail can grow in landscape before it scrolls, clear of the stage button. */
private val LANDSCAPE_DETAIL_HEIGHT = 150.dp

/** Numbers per line on the flight strip. */
private const val PORTRAIT_STRIP_PER_LINE = 5

/** Under this height in metres, a rover's readouts are a car's. Higher, height and climb matter. */
private const val DRIVING_HEIGHT = 20.0
private const val LANDSCAPE_STRIP_PER_LINE = 5

/** Where the prompts start: under the buttons, strip and chips in portrait, the top row in landscape. */
private val PORTRAIT_PROMPT_TOP = 150.dp
private val LANDSCAPE_PROMPT_TOP = 58.dp

/** Switches per rail column before a second one. Portrait has more height. */
private const val PORTRAIT_RAIL_PER_COLUMN = 6
private const val LANDSCAPE_RAIL_PER_COLUMN = 4

/** How often the idle fade is checked, and how fast the controls come back, in ms. */
private const val FADE_POLL_MS = 200L
private const val FADE_BACK_MS = 150

private val PORTRAIT_STICK_INSET = 14.dp

/** Keeps the stick [inset] in from its side edge (left in left-hand mode) and up from the bottom. */
private fun Modifier.cornerInset(leftHand: Boolean, inset: Dp): Modifier =
    padding(start = if (leftHand) inset else 0.dp, end = if (leftHand) 0.dp else inset, bottom = inset * 0.5f)
private val NAVBALL_SIZE = 112.dp

// Portrait has height to spare, so the throttle's longer there, and finer.
private val PORTRAIT_THROTTLE_HEIGHT = 150.dp
private val PORTRAIT_STICK_SIZE = 122.dp
private val PORTRAIT_NAVBALL_SIZE = 100.dp


/** What happened to a craft that's gone, and where to go from here. */
@Composable
private fun CrashCard(
    name: String,
    report: String,
    lost: Int,
    onLeave: () -> Unit,
    onFlyAnother: (() -> Unit)?,
    crewLost: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = ApogeeColors.Surface.alpha(0.88f),
        modifier = modifier.width(300.dp),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${name.ifEmpty { "The craft" }} was lost",
                style = MaterialTheme.typography.titleMedium,
                color = ApogeeColors.Danger,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(report, style = MaterialTheme.typography.bodyMedium, color = Color.White.alpha(ApogeeAlpha.BODY))
            if (lost > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    if (lost == 1) "1 part lost" else "$lost parts lost",
                    style = TelemetryTextStyle,
                    color = Color.White.alpha(ApogeeAlpha.SECONDARY),
                )
            }
            // Who went with it.
            if (crewLost.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    crewLost.joinToString(" · ") + if (crewLost.size == 1) " was lost with it" else " were lost with it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ApogeeColors.Danger,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.height(14.dp))
            // Stacked full width, so they're the same size.
            if (onFlyAnother != null) ApogeeButton("Pick another craft", onClick = onFlyAnother)
            ApogeeButton("Leave", onClick = onLeave)
        }
    }
}

/** The worlds' names next to their marks on the map, placed afresh ten times a second. */
@Composable
private fun MapNames(labels: (Float, Float) -> List<com.rm.apogee.game.GameSession.MapLabel>) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var shown by remember { mutableStateOf(emptyList<com.rm.apogee.game.GameSession.MapLabel>()) }
    LaunchedEffect(size) {
        while (true) {
            if (size.width > 0) shown = labels(size.width.toFloat(), size.height.toFloat())
            kotlinx.coroutines.delay(100)
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        for (label in shown) {
            if (label.place) {
                // A found place is just a dot, its name in the Program. One still to find is a ring.
                val found = label.name != "?"
                Box(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(label.x.toInt(), label.y.toInt()) }
                        .offset(-PLACE_DOT / 2, -PLACE_DOT / 2)
                        .size(PLACE_DOT)
                        .background(Color.Black.alpha(0.6f), androidx.compose.foundation.shape.CircleShape)
                        .padding(1.dp)
                        .then(
                            if (found) Modifier.background(ApogeeColors.Accent, androidx.compose.foundation.shape.CircleShape)
                            else Modifier.border(1.5.dp, ApogeeColors.Accent, androidx.compose.foundation.shape.CircleShape),
                        ),
                )
                continue
            }
            Text(
                label.name.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.alpha(ApogeeAlpha.BODY),
                maxLines = 1,
                modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(label.x.toInt() + 10, label.y.toInt() - 20) },
            )
        }
    }
}

/** How big a found place's dot is on the map. */
private val PLACE_DOT = 8.dp

/** Steps to the next camera mode and shows its name for a moment. */
@Composable
private fun CameraButton(mode: com.rm.apogee.game.CameraMode, onNext: () -> Unit, size: androidx.compose.ui.unit.Dp) {
    var shownFor by remember { mutableStateOf<com.rm.apogee.game.CameraMode?>(null) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(mode) {
        if (first) { first = false; return@LaunchedEffect }
        shownFor = mode
        kotlinx.coroutines.delay(1_500)
        shownFor = null
    }
    // The button's own size whatever the name, so its neighbours stay put.
    Box(Modifier.size(size)) {
        FilledTonalIconButton(onClick = onNext, modifier = Modifier.size(size)) {
            Icon(Icons.Filled.Videocam, contentDescription = "Camera: ${mode.label}")
        }
        shownFor?.let { shown ->
            Text(
                shown.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .wrapContentSize(unbounded = true)
                    .offset(y = 26.dp)
                    .clip(RoundedCornerShape(Dimens.CornerSmall))
                    .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
