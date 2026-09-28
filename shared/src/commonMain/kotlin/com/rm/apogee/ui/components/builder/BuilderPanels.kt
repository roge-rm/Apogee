package com.rm.apogee.ui.components.builder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.game.BuilderSession
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt
import com.rm.apogee.platform.format

/**
 * A panel on the left edge that slides away. It slides instead of being removed, so a part dragged
 * out of it keeps its finger while the panel tucks itself away. When it's away, a slim handle stays
 * at the edge. Tap it, or pull it out.
 */
@Composable
fun SlidePanel(
    shown: Boolean,
    onOpen: () -> Unit,
    handleLabel: String,
    modifier: Modifier = Modifier,
    /** How much of the left of the screen it covers, in pixels from the edge. 0 while it's away. */
    onCovers: (Float) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var width by remember { mutableStateOf(0) }
    val slide by animateFloatAsState(if (shown) 0f else 1f, label = "slide")
    var left by remember { mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(shown, left, width) { onCovers(if (shown) left + width else 0f) }
    Box(modifier.onGloballyPositioned { left = it.positionInWindow().x }) {
        Box(
            Modifier
                .onSizeChanged { width = it.width }
                .graphicsLayer {
                    translationX = -slide * (width + 40f)
                    alpha = 1f - slide * 0.6f
                },
        ) { content() }
        if (slide > 0.5f) {
            var pull by remember { mutableFloatStateOf(0f) }
            Surface(
                shape = RoundedCornerShape(topEnd = Dimens.CornerPanel, bottomEnd = Dimens.CornerPanel),
                color = Color.Black.alpha(ApogeeAlpha.SCRIM),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .semantics { contentDescription = handleLabel }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { pull += it },
                        onDragStarted = { pull = 0f },
                        onDragStopped = { if (pull > 30f) onOpen() },
                    )
                    .clickable(onClick = onOpen),
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.alpha(ApogeeAlpha.SECONDARY),
                    modifier = Modifier.padding(vertical = 26.dp, horizontal = 2.dp),
                )
            }
        }
    }
}

/**
 * The stats. One line shows delta-v, thrust to weight, mass, and a dot when something's wrong, and
 * tapping it shows the whole card.
 */
@Composable
fun StatsChip(stats: CraftStats, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(Dimens.CornerSmall),
        color = Color.Black.alpha(ApogeeAlpha.SCRIM),
        modifier = modifier,
    ) {
        Row(
            Modifier.clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dot = when {
                stats.problems.isNotEmpty() -> ApogeeColors.Danger
                stats.warnings.isNotEmpty() -> ApogeeColors.Caution
                else -> null
            }
            if (dot != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
            }
            Text("Δv ${stats.totalDeltaV.roundToInt()}", style = TelemetryTextStyle, color = ApogeeColors.Data)
            Text("  ·  TWR ", style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            Text(
                "%.2f".format(stats.liftoffTwr),
                style = TelemetryTextStyle,
                color = if (stats.liftoffTwr >= 1.0) ApogeeColors.Prograde else ApogeeColors.Danger,
            )
            Text("  ·  %.1f t".format(stats.totalMass / 1_000.0), style = TelemetryTextStyle, color = ApogeeColors.Data)
        }
    }
}

/** What's in your hand, and a way to put it down. */
@Composable
fun HeldChip(title: String, picture: ImageBitmap?, onDrop: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(Dimens.CornerActionBar),
        color = ApogeeColors.Accent.alpha(0.25f),
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (picture != null) Image(picture, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
            Icon(
                Icons.Filled.Close,
                contentDescription = "Put it down",
                tint = Color.White.alpha(ApogeeAlpha.SECONDARY),
                modifier = Modifier.clip(CircleShape).clickable(onClick = onDrop).padding(8.dp).size(18.dp),
            )
        }
    }
}

/**
 * Next to a tapped part: take it off, copy it, turn it, or see its stage, and under those, put it in
 * an action group or save it (with everything on it) to use again. It goes under the part where
 * there's room, otherwise over it, and it's always on screen.
 */
@Composable
fun PartActionBar(
    selection: BuilderSession.Selection,
    anchor: Offset,
    screen: IntSize,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onTurn: () -> Unit,
    onStage: () -> Unit,
    onClose: () -> Unit,
    onGroup: () -> Unit = {},
    onSaveAssembly: () -> Unit = {},
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(LocalDensity.current) { 44.dp.toPx() }
    val margin = with(LocalDensity.current) { 8.dp.toPx() }
    // Below the part, clear of the finger that tapped it, or above it where below would run into
    // the launch button.
    val bottomLimit = screen.height - with(LocalDensity.current) { 150.dp.toPx() }
    val below = anchor.y + gap
    val y = (if (below + size.height <= bottomLimit) below else anchor.y - gap - size.height)
        .coerceIn(margin * 8, (bottomLimit - size.height).coerceAtLeast(margin * 8))
    val x = (anchor.x - size.width / 2f).coerceIn(margin, (screen.width - size.width - margin).coerceAtLeast(margin))
    Surface(
        shape = RoundedCornerShape(Dimens.CornerSmall),
        color = Color.Black.alpha(0.82f),
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .onSizeChanged { size = it },
    ) {
        androidx.compose.foundation.layout.Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(
                selection.title,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp).width(84.dp),
            )
            if (!selection.root) {
                Action(if (selection.count > 1) "DELETE ${selection.count}" else "DELETE", ApogeeColors.Danger, onDelete)
                Action("COPY", ApogeeColors.Accent, onCopy)
                Action("TURN", ApogeeColors.Accent, onTurn)
            }
            if (selection.stageable) Action(if (selection.stage >= 0) "S${selection.stage}" else "STAGE", ApogeeColors.Prograde, onStage)
            Icon(
                Icons.Filled.Close,
                contentDescription = "Done",
                tint = Color.White.alpha(ApogeeAlpha.SECONDARY),
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp).size(16.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 94.dp, end = 4.dp, bottom = 2.dp)) {
            if (selection.groupable) {
                Action(if (selection.group > 0) "GROUP ${selection.group}" else "NO GROUP", if (selection.group > 0) ApogeeColors.Prograde else Color.White.alpha(ApogeeAlpha.SECONDARY), onGroup)
            }
            Action("SAVE ASSEMBLY", ApogeeColors.Accent, onSaveAssembly)
        }
        }
    }
}

@Composable
private fun Action(label: String, colour: Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = colour,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/**
 * The part under a carrying finger while it has nowhere to go: a picture of it, just above your
 * fingertip.
 */
@Composable
fun CarryPicture(picture: ImageBitmap?, at: Offset, lift: Float) {
    val half = with(LocalDensity.current) { 32.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((at.x - half).roundToInt(), (at.y - lift - half).roundToInt()) }
            .size(64.dp)
            .graphicsLayer { alpha = 0.85f },
    ) {
        if (picture != null) Image(picture, contentDescription = null, modifier = Modifier.size(64.dp))
        else Box(Modifier.size(24.dp).align(Alignment.Center).clip(CircleShape).background(ApogeeColors.Accent.alpha(0.6f)))
    }
}
