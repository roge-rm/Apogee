package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

/**
 * A hand-built vertical slider. A rotated Material slider keeps a wide, short hit area. Here the
 * whole box is the drag area.
 */
@Composable
fun VerticalAxisSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackWidth: androidx.compose.ui.unit.Dp = 6.dp,
    thumbSize: androidx.compose.ui.unit.Dp = 22.dp,
    /** Anything at or below this reads as zero, so the bottom means off. Zero for none. */
    snapToZero: Float = 0f,
) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { thumbSize.toPx() }
    // Same travel the thumb is drawn on, so touching the thumb reads its own value.
    fun at(y: Float): Float {
        val travel = (heightPx - thumbPx).coerceAtLeast(1f)
        val v = (1f - (y - thumbPx / 2f) / travel).coerceIn(0f, 1f)
        return if (v <= snapToZero) 0f else v
    }

    Box(
        modifier = modifier
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            // A tap sets it too, so tapping the bottom cuts the power.
            .pointerInput(Unit) {
                detectTapGestures { offset -> onValueChange(at(offset.y)) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> onValueChange(at(offset.y)) },
                ) { change, _ ->
                    change.consume()
                    onValueChange(at(change.position.y))
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .width(trackWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(trackWidth / 2))
                .background(Color.White.alpha(ApogeeAlpha.BORDER)),
        )
        // The filled part, drawn from the bottom up.
        Box(
            Modifier
                .width(trackWidth)
                .fillMaxHeight(value.coerceIn(0f, 1f))
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(trackWidth / 2))
                .background(ApogeeColors.Accent.alpha(0.7f)),
        )
        Box(
            Modifier
                .offset {
                    val travel = heightPx - thumbSize.toPx()
                    IntOffset(0, ((1f - value.coerceIn(0f, 1f)) * travel).roundToInt())
                }
                .size(thumbSize)
                .clip(CircleShape)
                .background(ApogeeColors.Accent),
        )
    }
}

/** A step button for the end of a slider. A tap is one step. Holding repeats, speeding up. */
@Composable
fun NudgeButton(label: String, onNudge: () -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val nudge by rememberUpdatedState(onNudge)
    // Only the repeat. The first step fires on the press, or a quick tap could be missed.
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        delay(400)
        var interval = 140L
        while (pressed) {
            nudge()
            delay(interval)
            interval = (interval * 4 / 5).coerceAtLeast(30L)
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(30.dp)
            .background(Color.White.alpha(ApogeeAlpha.CONTROL_FILL), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        nudge()
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            },
    ) {
        Text(label, color = Color.White.alpha(ApogeeAlpha.SECONDARY), style = MaterialTheme.typography.titleMedium)
    }
}
