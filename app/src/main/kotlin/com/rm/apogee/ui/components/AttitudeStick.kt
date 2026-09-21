package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.alpha
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A self-centring two-axis stick for pitch and yaw.
 *
 * Springs back to neutral on release, because a rocket left with a held
 * deflection will happily rotate until it is pointing at the ground and the
 * player has no reason to expect a thumb lift to mean "keep turning".
 *
 * The touch target is the whole box rather than the knob: chasing a small knob
 * with a thumb is precisely the interaction that makes touch flight controls
 * feel broken.
 */
@Composable
fun AttitudeStick(
    onChange: (pitch: Float, yaw: Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    knobSize: Dp = 46.dp,
) {
    var extentPx by remember { mutableStateOf(1f) }
    var knob by remember { mutableStateOf(Offset.Zero) }

    /** Maps a touch point to a clamped unit deflection, and reports it. */
    fun apply(position: Offset) {
        val half = extentPx / 2f
        var dx = (position.x - half) / half
        var dy = (position.y - half) / half
        val magnitude = hypot(dx, dy)
        if (magnitude > 1f) {
            dx /= magnitude
            dy /= magnitude
        }
        knob = Offset(dx, dy)
        // Screen +Y is down, and pulling the stick back should pitch the nose
        // up - so the vertical axis is inverted here, as on an aircraft stick.
        onChange(-dy, dx)
    }

    fun release() {
        knob = Offset.Zero
        onChange(0f, 0f)
    }

    Box(
        modifier = modifier
            .size(size)
            .onSizeChanged { extentPx = it.width.toFloat().coerceAtLeast(1f) }
            .clip(CircleShape)
            .background(Color.White.alpha(ApogeeAlpha.FILL_FAINT))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { apply(it) },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, _ ->
                    change.consume()
                    apply(change.position)
                }
            },
    ) {
        // Neutral marker, so the centre is visible when the stick is released.
        Box(
            Modifier
                .align(androidx.compose.ui.Alignment.Center)
                .size(knobSize / 3)
                .clip(CircleShape)
                .background(Color.White.alpha(ApogeeAlpha.BORDER_FAINT)),
        )
        Box(
            Modifier
                .offset {
                    val travel = (extentPx - knobSize.toPx()) / 2f
                    IntOffset(
                        ((extentPx - knobSize.toPx()) / 2f + knob.x * travel).roundToInt(),
                        ((extentPx - knobSize.toPx()) / 2f + knob.y * travel).roundToInt(),
                    )
                }
                .size(knobSize)
                .clip(CircleShape)
                .background(ApogeeColors.Accent.alpha(0.85f)),
        )
    }
}

/**
 * A momentary button that reports held/released, for roll.
 *
 * Roll gets buttons rather than a third stick axis: a twist gesture competes
 * with the camera drag, and roll is used in discrete corrections rather than
 * continuously.
 */
@Composable
fun HoldButton(
    label: String,
    onHold: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    var held by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (held) ApogeeColors.Accent.alpha(0.5f)
                else Color.White.alpha(ApogeeAlpha.CONTROL_FILL)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    held = true
                    onHold(true)
                    // Wait for every pointer to lift. A drag that wanders off
                    // the button still has to release it, or roll sticks on
                    // with no visible cause.
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    held = false
                    onHold(false)
                }
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = Color.White.alpha(ApogeeAlpha.SECONDARY),
        )
    }
}
