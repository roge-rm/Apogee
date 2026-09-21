package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
 * A vertical slider built by hand rather than from Material's.
 *
 * Material's slider is horizontal, and rotating it leaves the touch target
 * rotated too - the hit area ends up a wide, short band where the control looks
 * tall and thin, which is maddening under a thumb. Here the entire box is the
 * drag area, which is what a throttle needs.
 */
@Composable
fun VerticalAxisSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackWidth: androidx.compose.ui.unit.Dp = 6.dp,
    thumbSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    var heightPx by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onValueChange((1f - offset.y / heightPx).coerceIn(0f, 1f))
                    },
                ) { change, _ ->
                    change.consume()
                    onValueChange((1f - change.position.y / heightPx).coerceIn(0f, 1f))
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
        // Filled portion, drawn from the bottom up.
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
