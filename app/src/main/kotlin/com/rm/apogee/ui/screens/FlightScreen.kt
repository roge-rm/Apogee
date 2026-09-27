package com.rm.apogee.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

/**
 * The overlay drawn on top of the rendered world.
 *
 * It's transparent by nature (it sits in a ComposeView above the GLSurfaceView), so every panel has
 * its own scrim to stay readable against whatever the camera happens to be pointing at.
 *
 * The layout is anchored to the four corners and the two side edges, with nothing in the middle.
 * The craft is what the player is looking at, and a control that drifts into the middle of the
 * screen is a control in the way. Throttle and attitude sit on opposite edges so the two thumbs
 * never cross, and they swap sides together when left-hand mode is on.
 */
@Composable
fun FlightScreen(
    hud: HudState,
    controlOpacity: Float,
    showDebugOverlay: Boolean,
    leftHandMode: Boolean,
    /** Let the controls fade back when they haven't been touched for a few seconds. */
    fadeWhenIdle: Boolean = true,
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
    /** Hold a plane's height and heading, or stop. */
    onCruise: (Boolean) -> Unit = {},
    /** Empty the craft's ore and water into a base or docked craft, or switch a base's refinery. */
    onUnload: (Boolean) -> Unit = {},
    onRefine: (com.rm.apogee.core.world.ServerMessage.BaseStatus, Boolean) -> Unit = { _, _ -> },
    /** With the thrusters armed, the stick slides the craft (true) or turns it. */
    onStickMode: (Boolean) -> Unit = {},
    /** Let go at a docking part. */
    onUndock: (Int) -> Unit = {},
    onFound: (Boolean) -> Unit = {},
    onRefuel: (Boolean) -> Unit = {},
    /** Shared with another player: who flies it ("me", "them" or "both"). */
    onDockPilot: (String) -> Unit = {},
    onToggleMap: () -> Unit,
    onJoin: () -> Unit,
    onExit: () -> Unit,
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
    /**
     * This player's id, for the program's firsts, and spending insight there: null if it went
     * through, or the reason it didn't.
     */
    me: String = "",
    onUnlock: (String) -> String? = { null },
) {
    // BoxWithConstraints instead of the configuration's orientation. This is a question about the
    // space that's really available, and the answer has to be right in a resized window and in
    // multi-window as well as after a rotation. Asking the layout is asking the thing that decides.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxWidth < maxHeight
        val sas = SasActions(onToggleSas, onSasMode, targetChoices, onTarget, onStickMode, onCruise)
        val railActions = RailActions(
            onBrakes = onToggleBrakes, onReverse = onToggleReverse, onRcs = onToggleRcs, onDeploy = onToggleDeploy,
            onDrill = onToggleDrill, onRefine = onToggleRefine, onJump = crewActions.onJump, onFlag = crewActions.onFlag,
            onDive = onDive, onRise = onRise, onHold = onHoldDepth,
            onFlaps = onToggleFlaps, onGroup = onGroup, onWinch = onWinch, onStationKeep = onStationKeep,
        )
        val statusActions = StatusActions(crewActions, onUndock, onFound, onRefuel, onUnload, onRefine, onDockPilot)
        val promptActions = PromptActions(onJoin, onFound, crewActions.onBoard, crewActions.onGrab, onHook, onReleaseLine)

        if (hud.connectionError != null) {
            ConnectionProblem(hud.connectionError!!, onExit)
            return@BoxWithConstraints
        }
        // The program, over the flight. In a world someone else hosts, it's the only place a
        // player's career there can be seen and spent.
        val career = hud.career
        if (hud.programOpen && career != null) {
            androidx.activity.compose.BackHandler { hud.programOpen = false }
            ProgramScreen(career, hud.worldFirsts, me, onUnlock, onClose = { hud.programOpen = false })
            return@BoxWithConstraints
        }
        if (hud.connecting || !hud.surfaceReady) {
            // Opaque, with nothing drawn over it. The GL surface behind this is live, and until the
            // terrain patch lands it shows a craft hanging over a globe too coarse to have the
            // ground under it, which looks like a broken world instead of one still loading. A
            // spinner floating over that picture doesn't help. Covering it does.
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

        var exitMenu by remember { mutableStateOf(false) }
        if (exitMenu) ExitMenu(hud, rewind, onExit, onClose = { exitMenu = false })

        // --- fading when idle -----------------------------------------------
        //
        // A few seconds with nothing touched and the controls fade back to let the view through.
        // Any touch, a new warning or prompt, or the engines running brings them straight back.
        var idle by remember { mutableStateOf(false) }
        val statusKey = statusKey(hud)
        val promptKey = promptKey(hud)
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
        // Only the *horizontal* cutout inset, so these sit right up against the top edge. Padding
        // for the full cutout pushes them a notch's height down the screen to clear something that
        // isn't above them. A punch-hole or a notch is in the middle of the top edge, and both of
        // these corners are beside it, not under it. The horizontal inset still applies, which is
        // what matters in landscape, where the cutout is down one side and really is in the way.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.alpha(alpha),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.HudGroupGap),
            ) {
                FilledTonalIconButton(
                    // Alone in your own world, it's a menu, with the save points in it. Otherwise
                    // it just leaves.
                    onClick = { if (hud.canRewind) exitMenu = true else onExit() },
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
                // The craft name is the first thing to go when the screen is narrow. The flight
                // strip opposite isn't optional, and the two meet in the middle on a portrait
                // phone.
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

        // Gone, so there are no controls to fly it with, only what happened to it.
        hud.telemetry.destroyed?.let { report ->
            CrashCard(
                name = hud.telemetry.craftName,
                report = report,
                lost = hud.telemetry.lost,
                crewLost = hud.crewLost,
                onLeave = onExit,
                // Opens the craft list, from the button at the top, to choose which.
                onFlyAnother = if (hud.ownedCraft > 0) ({ hud.craftListOpen = true }) else null,
                // Low, where the controls were, because the wreck is in the middle of the view.
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp),
            )
            return@BoxWithConstraints
        }

        // --- top right: the flight strip, and the status chips under it ------
        //
        // Along the edge, out of the view: a few numbers, tapped open for the rest, then the
        // craft's state in small chips, each tapped open for what's behind it. In portrait it's a
        // row below the buttons, not beside them, because there's no room beside them, and a wide
        // number ("suborbital") pushed the strip over the warp button.
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
        // Throttle and attitude on opposite edges so the two thumbs never cross, swapping sides
        // together in left-hand mode. The switches go on the throttle's inner side, and the navball
        // and the STAGE button sit low in the middle between them. Nothing is higher than it has to
        // be.
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
            )
        }
        val navball: @Composable () -> Unit = {
            NavBall(
                rotation = hud.telemetry.rotation,
                worldUp = hud.telemetry.up,
                prograde = hud.telemetry.prograde,
                size = if (portrait) PORTRAIT_NAVBALL_SIZE else NAVBALL_SIZE,
                normal = hud.telemetry.normal,
                radialOut = hud.telemetry.radialOut,
                frame = hud.telemetry.frame,
                frameManual = hud.telemetry.frameChosen != com.rm.apogee.core.world.NavFrame.AUTO,
                onCycleFrame = onCycleFrame,
                toTarget = hud.telemetry.toTarget,
                throughAir = hud.telemetry.throughAir,
                burn = hud.telemetry.burn,
            )
        }
        // Someone on EVA has nothing to stage.
        val staging: @Composable () -> Unit = {
            if (!hud.isSuit) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StageTab(hud.stages, hud.stagesExpanded, { hud.stagesExpanded = !hud.stagesExpanded })
                    if (hud.stages.isNotEmpty()) Spacer(Modifier.height(6.dp))
                    RoundStageButton(hud.telemetry.stage, onStage, current = hud.stages.firstOrNull { it.current })
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
                // The ball above the button, because side by side they're wider than a portrait
                // phone has between the thumbs.
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
 * The X button's menu, alone in your own world: take a save point, go back to it, start the flight
 * again from its launch, or leave. Going back asks first, since whatever's happened since is lost,
 * feats and all.
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
                    (if (loading) "Everything goes back to how it was at ${hud.savePoint}." else "Everything goes back to just before this launch.") +
                        if (hud.career != null) " Feats earned since then are taken back too." else "",
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
                MenuRow("Save point", hud.savePoint?.let { "Last taken at $it" } ?: "Take one now, to come back to") {
                    rewind.onSavePoint(); onClose()
                }
                MenuRow("Load save point", hud.savePoint?.let { "Back to $it" } ?: "None taken yet", enabled = hud.savePoint != null) {
                    confirm = "load"
                }
                MenuRow("Revert to launch", if (hud.canRevert) "Start again from the launch" else "Not launched this time", enabled = hud.canRevert) {
                    confirm = "revert"
                }
                MenuRow("Leave", "Back to the menu") { onClose(); onExit() }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text(hud.going.keep) } },
    )
}

@Composable
private fun MenuRow(title: String, detail: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Color.White else Color.White.alpha(ApogeeAlpha.BORDER))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.White.alpha(if (enabled) ApogeeAlpha.SUBTITLE else ApogeeAlpha.BORDER))
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
) {
    // Sliding on the thrusters, the roll buttons become down and up instead.
    val sliding = hud.rcsArmed && hud.rcsSlide
    val deaf = hud.power?.outOfTouch
    Column(modifier.alpha(if (deaf != null) OUT_OF_TOUCH_ALPHA else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoldButton(if (sliding) "▼" else "↺", { held -> onRoll(if (held) -1f else 0f) }, size = 40.dp)
            // Stability assist lives with the attitude controls, not with staging, because it's the
            // thing that holds an attitude for you.
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
            )
            HoldButton(if (sliding) "▲" else "↻", { held -> onRoll(if (held) 1f else 0f) }, size = 40.dp)
        }
        Spacer(Modifier.height(8.dp))
        Box(contentAlignment = Alignment.Center) {
            AttitudeStick(onChange = onAttitude, size = stickSize)
            // Why it does nothing, written across it.
            if (deaf != null) {
                Text(deaf, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ApogeeColors.Danger)
            }
        }
        // What the stick does, right under it, while the thrusters are armed. Its room is kept when
        // they aren't, so the stick sits at the same height, a little up from the corner, on every
        // craft.
        Spacer(Modifier.height(8.dp))
        StickModeChip(sliding, sas.onStickMode, shown = hud.rcsArmed)
    }
}

/**
 * TURN | SLIDE: what the stick does while the thrusters are armed. Unless [shown], it only takes up
 * the room.
 */
@Composable
private fun StickModeChip(sliding: Boolean, onStickMode: (Boolean) -> Unit, shown: Boolean = true) {
    Row(
        Modifier
            .alpha(if (shown) 1f else 0f)
            .clip(RoundedCornerShape(Dimens.CornerActionBar))
            .background(Color.White.alpha(ApogeeAlpha.CONTROL_FILL)),
    ) {
        for ((label, slide) in listOf("TURN" to false, "SLIDE" to true)) {
            val chosen = slide == sliding
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (chosen) Color(0xFF1A1030) else Color.White.alpha(ApogeeAlpha.SECONDARY),
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.CornerActionBar))
                    .background(if (chosen) ApogeeColors.Accent.alpha(0.85f) else Color.Transparent)
                    .clickable(enabled = shown) { onStickMode(slide) }
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

/**
 * How far the stick is kept in from its corner, beyond the screen's own margin. Tucked right into
 * the corner, it was too close to control easily, and my thumb had to bend back to reach it.
 */
private val STICK_INSET = 40.dp

/** How tall a status chip's detail can grow in landscape before it scrolls, clear of the stage button. */
private val LANDSCAPE_DETAIL_HEIGHT = 150.dp

/** Numbers per line on the flight strip. */
private const val PORTRAIT_STRIP_PER_LINE = 5
private const val LANDSCAPE_STRIP_PER_LINE = 5

/**
 * Where the prompts start down from the top: under the buttons, the strip and the chips in
 * portrait, and the top row in landscape.
 */
private val PORTRAIT_PROMPT_TOP = 150.dp
private val LANDSCAPE_PROMPT_TOP = 58.dp

/**
 * Switches per column of the rail, before a second one. Portrait has the height, and landscape
 * doesn't.
 */
private const val PORTRAIT_RAIL_PER_COLUMN = 6
private const val LANDSCAPE_RAIL_PER_COLUMN = 4

/** How often the idle fade is checked, in ms, and how fast the controls come back, in ms. */
private const val FADE_POLL_MS = 200L
private const val FADE_BACK_MS = 150

private val PORTRAIT_STICK_INSET = 14.dp

/** Keeps the stick [inset] in from the screen edge it sits against (the left in left-hand mode) and up from the bottom. */
private fun Modifier.cornerInset(leftHand: Boolean, inset: Dp): Modifier =
    padding(start = if (leftHand) inset else 0.dp, end = if (leftHand) 0.dp else inset, bottom = inset * 0.5f)
private val NAVBALL_SIZE = 112.dp

// Portrait is short of width and generous with height, so the throttle takes the height. A longer
// throttle is a finer throttle, over the same 0-100%.
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
            // Who went with it, and who's on the memorial now.
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
            // One over the other, full width, so they're the same size whatever they say.
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
                // A found place is a small dot on the world, nothing more. Its name is in the
                // Program.
                Box(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(label.x.toInt(), label.y.toInt()) }
                        .offset(-PLACE_DOT / 2, -PLACE_DOT / 2)
                        .size(PLACE_DOT)
                        .background(Color.Black.alpha(0.6f), androidx.compose.foundation.shape.CircleShape)
                        .padding(1.dp)
                        .background(ApogeeColors.Accent, androidx.compose.foundation.shape.CircleShape),
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
private val PLACE_DOT = 6.dp
