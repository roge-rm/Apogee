package com.rm.apogee.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rm.apogee.game.StageCard
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

/**
 * The STAGE button: round, with the next stage's number on it and the fuel of the stage burning now
 * as a ring round its edge, red for the last few percent. It's a target for your thumb, not a bar
 * across the view.
 */
@Composable
fun RoundStageButton(
    stage: Int,
    onStage: () -> Unit,
    modifier: Modifier = Modifier,
    /** The stage burning now, whose fuel the ring shows. */
    current: StageCard? = null,
    size: Dp = STAGE_BUTTON,
) {
    val ink = Color(0xFF1A1030)
    val fraction = current?.fuelFraction
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onStage),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(ApogeeColors.Accent.alpha(0.88f))
            if (fraction != null) {
                val stroke = RING.toPx()
                val inset = stroke / 2f + 2.dp.toPx()
                val arc = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(ink.alpha(0.2f), 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
                drawArc(
                    if (fraction < 0.05f) ApogeeColors.Danger.copy(red = 0.7f) else ink.alpha(0.85f),
                    -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.KeyboardDoubleArrowUp, contentDescription = "Stage", tint = ink, modifier = Modifier.size(16.dp))
            Text("$stage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ink, lineHeight = 18.sp)
        }
    }
}

/**
 * The stages still to fire, folded into one small tab beside the STAGE button: the next one, what
 * it does, and how many more come after it. Tap it and it opens every stage in full (fuels, delta-v
 * and burn time) over the view, and tap it again to fold it away.
 */
@Composable
fun StageTab(
    stages: List<StageCard>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** How wide the detail opens. */
    detailWidth: Dp = 260.dp,
) {
    if (stages.isEmpty()) return
    val waiting = stages.filter { !it.current }
    val next = waiting.firstOrNull() ?: stages.first()
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(Dimens.CornerTight))
                .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (next.current) "NOW" else "S${next.index}", style = ChipText, color = ApogeeColors.Accent)
            Spacer(Modifier.width(5.dp))
            Text(
                next.contents,
                style = ChipText,
                color = Color.White.alpha(ApogeeAlpha.SECONDARY),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 90.dp),
            )
            if (waiting.size > 1) {
                Spacer(Modifier.width(5.dp))
                Text("+${waiting.size - 1}", style = ChipText, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            }
            Icon(
                if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = "All stages",
                tint = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                modifier = Modifier.size(14.dp),
            )
        }
        // Over the view, not in the layout, so opening it doesn't move anything else.
        if (expanded) {
            Popup(
                alignment = Alignment.BottomCenter,
                properties = PopupProperties(focusable = false),
            ) {
                StageDetail(stages, onToggle, detailWidth)
            }
        }
    }
}

/** Small print for the tab. It only gets glanced at, and every row costs view. */
private val ChipText = TelemetryTextStyle.copy(fontSize = 11.sp, lineHeight = 13.sp)

/** The round STAGE button's size, and its fuel ring's width. */
val STAGE_BUTTON = 64.dp
private val RING = 5.dp

/** Every stage in full, soonest at the bottom, scrolled down there to start with. */
@Composable
private fun StageDetail(stages: List<StageCard>, onToggle: () -> Unit, width: Dp, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }
    // Nearly opaque, unlike the chips. It lies over the navball and the stack, and through a scrim
    // they read as clutter behind the numbers.
    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = ApogeeColors.Surface.alpha(0.95f),
        modifier = modifier.width(width).clickable(onClick = onToggle),
    ) {
        Column(
            Modifier
                .heightIn(max = 280.dp)
                .verticalScrollbar(scroll)
                .verticalScroll(scroll)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (card in stages.asReversed()) StageRow(card)
        }
    }
}

@Composable
private fun StageRow(card: StageCard) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (card.current) {
                    Modifier.border(1.dp, ApogeeColors.Accent.alpha(0.7f), RoundedCornerShape(Dimens.CornerTight))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (card.current) "NOW" else "S${card.index}",
                style = TelemetryTextStyle,
                color = ApogeeColors.Accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                card.contents,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.alpha(ApogeeAlpha.BODY),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        for (gauge in card.fuel) {
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    gauge.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.alpha(ApogeeAlpha.SECONDARY),
                    modifier = Modifier.width(92.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FuelBar(gauge.fraction, Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text(percent(gauge.fraction), style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.BODY))
            }
        }
        val deltaV = card.deltaV
        if (deltaV != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                "Δv ${"%,d".format(deltaV.roundToInt())} m/s" +
                    (card.burnTime?.let { " · ${duration(it)}" } ?: ""),
                style = TelemetryTextStyle,
                color = ApogeeColors.Data,
            )
        }
    }
}

/**
 * A fuel gauge: accent while there's plenty, caution under a fifth, and danger for the last few
 * percent.
 */
@Composable
fun FuelBar(fraction: Float, modifier: Modifier = Modifier, track: Color = Color.White.alpha(0.15f), fill: Color? = null) {
    val colour = fill ?: when {
        fraction < 0.05f -> ApogeeColors.Danger
        fraction < 0.2f -> ApogeeColors.Caution
        else -> ApogeeColors.Accent
    }
    Box(modifier.height(5.dp).clip(RoundedCornerShape(3.dp)).background(track)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(5.dp)
                .background(colour),
        )
    }
}

private fun percent(fraction: Float): String = "${(fraction * 100).roundToInt()}%"

private fun duration(seconds: Double): String {
    val s = seconds.roundToInt()
    return if (s >= 60) "${s / 60}m ${"%02d".format(s % 60)}s" else "${s}s"
}
