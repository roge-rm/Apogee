package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * The stages still to fire, stacked above the STAGE button: the next one
 * nearest the button, later ones above it, each a slim chip with its fuel.
 * Only a few show; the rest wait behind a count. Tapped, the stack opens into
 * full detail - every stage's fuels, delta-v and burn time - and tapped again
 * it folds away, so the view above stays clear unless asked for.
 *
 * The stage burning now is not here: its gauge is on the button itself.
 */
@Composable
fun StageStack(
    stages: List<StageCard>,
    expanded: Boolean,
    onToggle: () -> Unit,
    width: Dp,
    maxChips: Int,
    modifier: Modifier = Modifier,
    /** How wide the detail opens - wider than the stack, where the stack is narrow. */
    detailWidth: Dp = width,
) {
    if (stages.isEmpty()) return
    // With nothing left to fire, the burning stage gets the one chip, so the
    // detail can still be opened.
    val chips = stages.filter { !it.current }.ifEmpty { stages }
    val shown = chips.take(maxChips)
    Box(modifier.width(width)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CornerSmall))
                .clickable(onClick = onToggle),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val hidden = chips.size - shown.size
            if (hidden > 0) {
                Text(
                    "+$hidden more",
                    style = ChipText,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            // Latest at the top, so the stack reads upward from the button.
            for (card in shown.asReversed()) StageChip(card)
        }
        // Over the stack, not in the layout: opening it moves nothing else on
        // the HUD, and it may be wider than the column it rises from.
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

/** Small print for the chips: they are glanced at, and every row costs view. */
private val ChipText = TelemetryTextStyle.copy(fontSize = 11.sp, lineHeight = 13.sp)

@Composable
private fun StageChip(card: StageCard) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM), RoundedCornerShape(Dimens.CornerTight))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("S${card.index}", style = ChipText, color = ApogeeColors.Accent)
        Spacer(Modifier.width(6.dp))
        val fraction = card.fuelFraction
        if (card.current) {
            Text(
                "now \u00b7 \u0394v ${"%,d".format((card.deltaV ?: 0.0).roundToInt())} m/s",
                style = ChipText,
                color = ApogeeColors.Data,
                modifier = Modifier.weight(1f),
            )
        } else if (fraction != null) {
            FuelBar(fraction, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Text(percent(fraction), style = ChipText, color = Color.White.alpha(ApogeeAlpha.BODY))
        } else {
            Text(
                card.contents,
                style = ChipText,
                color = Color.White.alpha(ApogeeAlpha.SECONDARY),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Every stage in full, soonest at the bottom, scrolled there to start. */
@Composable
private fun StageDetail(stages: List<StageCard>, onToggle: () -> Unit, width: Dp, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }
    // Near opaque, unlike the chips: it lies over the navball and the
    // stack, and through a scrim they read as clutter behind the numbers.
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
 * A fuel gauge: accent while there is plenty, caution under a fifth, danger
 * at the last few percent.
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
