package com.rm.apogee.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A scroll bar down the right edge, drawn only while the content overflows. Put it before
 * `verticalScroll` in the chain so it measures the viewport, not the whole content.
 */
fun Modifier.verticalScrollbar(state: ScrollState, width: Dp = 4.dp, inset: Dp = 2.dp): Modifier = composed {
    val shown by animateFloatAsState(if (state.maxValue > 0) 1f else 0f, label = "scrollbar")
    drawWithContent {
        drawContent()
        if (shown <= 0f || state.maxValue <= 0) return@drawWithContent
        val viewport = size.height
        val content = viewport + state.maxValue
        drawBar(
            viewFraction = viewport / content,
            position = state.value.toFloat() / state.maxValue,
            width = width.toPx(), inset = inset.toPx(), alpha = shown,
        )
    }
}

/**
 * The same for a lazy list. Its length is estimated from the rows in view, which is exact for
 * equal rows, as all of ours are.
 */
fun Modifier.verticalScrollbar(state: LazyListState, width: Dp = 4.dp, inset: Dp = 2.dp): Modifier = composed {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val overflows = visible.isNotEmpty() && (visible.size < info.totalItemsCount ||
        visible.first().offset < info.viewportStartOffset || visible.last().let { it.offset + it.size } > info.viewportEndOffset)
    val shown by animateFloatAsState(if (overflows) 1f else 0f, label = "scrollbar")
    drawWithContent {
        drawContent()
        if (shown <= 0f || !overflows) return@drawWithContent
        val rowSize = visible.sumOf { it.size }.toFloat() / visible.size
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val content = rowSize * info.totalItemsCount
        if (content <= viewport) return@drawWithContent
        val scrolled = state.firstVisibleItemIndex * rowSize + state.firstVisibleItemScrollOffset
        drawBar(
            viewFraction = viewport / content,
            position = (scrolled / (content - viewport)).coerceIn(0f, 1f),
            width = width.toPx(), inset = inset.toPx(), alpha = shown,
        )
    }
}

/** The same for a lazy grid, estimated from the rows in view. */
fun Modifier.verticalScrollbar(state: androidx.compose.foundation.lazy.grid.LazyGridState, width: Dp = 4.dp, inset: Dp = 2.dp): Modifier = composed {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val overflows = visible.isNotEmpty() && (visible.size < info.totalItemsCount ||
        visible.first().offset.y < info.viewportStartOffset || visible.last().let { it.offset.y + it.size.height } > info.viewportEndOffset)
    val shown by animateFloatAsState(if (overflows) 1f else 0f, label = "scrollbar")
    drawWithContent {
        drawContent()
        if (shown <= 0f || !overflows) return@drawWithContent
        // Items per row, from how many share the first row's top.
        val perRow = visible.count { it.offset.y == visible.first().offset.y }.coerceAtLeast(1)
        val rowSize = visible.first().size.height.toFloat() + (visible.getOrNull(perRow)?.let { it.offset.y - visible.first().offset.y - visible.first().size.height } ?: 0).toFloat()
        val rows = (info.totalItemsCount + perRow - 1) / perRow
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val content = rowSize * rows
        if (content <= viewport) return@drawWithContent
        val scrolled = (state.firstVisibleItemIndex / perRow) * rowSize + state.firstVisibleItemScrollOffset
        drawBar(
            viewFraction = viewport / content,
            position = (scrolled / (content - viewport)).coerceIn(0f, 1f),
            width = width.toPx(), inset = inset.toPx(), alpha = shown,
        )
    }
}

private val Track = Color.White.copy(alpha = 0.10f)
private val Thumb = Color.White.copy(alpha = 0.45f)

private fun DrawScope.drawBar(viewFraction: Float, position: Float, width: Float, inset: Float, alpha: Float) {
    val x = size.width - width - inset
    val trackLength = size.height - inset * 2
    val thumbLength = (trackLength * viewFraction).coerceIn(width * 6, trackLength)
    val thumbTop = inset + (trackLength - thumbLength) * position.coerceIn(0f, 1f)
    val corner = CornerRadius(width / 2, width / 2)
    drawRoundRect(Track, Offset(x, inset), Size(width, trackLength), corner, alpha = alpha)
    drawRoundRect(Thumb, Offset(x, thumbTop), Size(width, thumbLength), corner, alpha = alpha)
}
