package com.rm.apogee.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import com.rm.apogee.ui.displayCutouts

/**
 * A row that steps round the camera hole. A child that would land under a hole goes past it and
 * the row carries on from there. Padding the whole row would move every button for one hole.
 */
@Composable
fun CutoutRow(
    modifier: Modifier = Modifier,
    spacing: Dp,
    content: @Composable () -> Unit,
) {
    val cutouts = displayCutouts()
    // The row's window position, to bring the holes into its coordinates. Known only after
    // placement, so a row that moves settles a frame later.
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    Layout(
        content = content,
        modifier = modifier.onGloballyPositioned { origin = it.positionInWindow() },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val gap = spacing.roundToPx()
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val at = origin
        val holes = if (at == Offset.Unspecified) emptyList() else cutouts.map { it.translate(-at.x, -at.y) }
        val xs = IntArray(placeables.size)
        var x = 0
        placeables.forEachIndexed { i, p ->
            // Past every hole it would overlap, possibly several.
            var moved = true
            while (moved) {
                moved = false
                for (hole in holes) {
                    if (overlaps(hole, x, x + p.width, height)) {
                        x = kotlin.math.ceil(hole.right).toInt() + gap
                        moved = true
                    }
                }
            }
            xs[i] = x
            x += p.width + gap
        }
        val width = (x - gap).coerceAtLeast(0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { i, p -> p.place(xs[i], (height - p.height) / 2) }
        }
    }
}

private fun overlaps(hole: Rect, left: Int, right: Int, height: Int): Boolean =
    hole.left < right && hole.right > left && hole.top < height && hole.bottom > 0
