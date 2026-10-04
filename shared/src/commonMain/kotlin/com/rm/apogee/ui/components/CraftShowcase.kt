package com.rm.apogee.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rm.apogee.core.craft.CraftKind
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.alpha

/** One craft in a [CraftShowcase]: how it's listed, and its pictures. */
class ShowcaseItem(
    val key: String,
    val name: String,
    /** A line under its name in the list. */
    val line: String,
    val kind: CraftKind?,
    val picture: ImageBitmap?,
    val largePicture: ImageBitmap?,
)

/**
 * Craft to choose from: the chosen one shown large with its details, beside the list on a wide
 * screen and above it on a tall one. [actions] go along the bottom, for the chosen craft.
 */
@Composable
fun CraftShowcase(
    items: List<ShowcaseItem>,
    chosen: String?,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Asks for the chosen craft's large picture. */
    onShow: (String) -> Unit = {},
    /** Under the chosen craft's name. */
    details: @Composable ColumnScope.(ShowcaseItem) -> Unit = {},
    /** Buttons for the chosen craft, each given the modifier to lay it out with. */
    actions: @Composable (item: ShowcaseItem?, each: Modifier) -> Unit = { _, _ -> },
    /** Tabs by kind, when there are kinds. */
    byKind: Boolean = true,
) {
    val picked = items.firstOrNull { it.key == chosen }
    LaunchedEffect(picked?.key) { picked?.let { onShow(it.key) } }
    // Something's always chosen, so the large picture is never empty.
    LaunchedEffect(picked == null, items.firstOrNull()?.key) { if (picked == null) items.firstOrNull()?.let { onChoose(it.key) } }
    var shownKind by rememberSaveable { mutableStateOf<String?>(null) }
    val kinds = if (byKind) CraftKind.entries.filter { k -> items.any { it.kind == k } } else emptyList()
    val kind = kinds.firstOrNull { it.name == shownKind }
    val shown = if (kind == null) items else items.filter { it.kind == kind }

    @Composable
    fun tabs(short: Boolean) {
        if (kinds.size > 1) {
            KindPicker(
                kinds, kind,
                count = { k -> if (k == null) items.size else items.count { it.kind == k } },
                onSelect = { shownKind = it?.name },
                modifier = Modifier.padding(bottom = 8.dp),
                showCount = !short,
            )
        }
    }

    @Composable
    fun ColumnScope.list(withTabs: Boolean, short: Boolean) {
        if (withTabs) tabs(short)
        val state = rememberLazyListState()
        // Scrolled to the chosen craft when the list first shows.
        LaunchedEffect(items.isEmpty()) {
            val at = shown.indexOfFirst { it.key == chosen }
            if (at > 0) state.scrollToItem(at)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().verticalScrollbar(state), state = state) {
            items(shown, key = { it.key }) { item -> ShowcaseRow(item, item.key == chosen) { onChoose(item.key) } }
        }
    }

    BoxWithConstraints(modifier) {
    // Side by side on any screen about as wide as it's tall, a square handheld's too.
    val wideScreen = maxWidth >= maxHeight * 0.95f && maxWidth >= 400.dp
    // Too short to stack the buttons.
    val short = maxHeight < 520.dp
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = wideScreen
            val tall = maxHeight
            if (wide) {
                // The tabs across the top, so each has room for its icon.
                Column(Modifier.fillMaxSize()) {
                tabs(short)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // The picture as tall as there's room for, its name and details beside it.
                    BoxWithConstraints(Modifier.weight(if (short) 1f else 1.3f).fillMaxHeight()) {
                        // Room left beside it for the name and details.
                        val beside = minOf(maxHeight, maxWidth * 0.62f, maxWidth - DETAILS_WIDTH)
                        if (beside >= 120.dp) {
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                Showpiece(picked, Modifier.size(beside))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) { picked?.let { Named(it, details, short) } }
                            }
                        } else {
                            // Too narrow for the details beside it: the picture over a strip with its
                            // name and first details line.
                            val strip = 58.dp
                            val side = minOf(maxWidth, maxHeight - strip)
                            Column(Modifier.width(side)) {
                                Showpiece(picked, Modifier.size(side))
                                picked?.let { item ->
                                    Text(item.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                    Text(item.line, style = MaterialTheme.typography.bodySmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(if (short) 12.dp else 20.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) { list(withTabs = false, short = short) }
                }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().heightIn(max = tall * 0.42f), verticalAlignment = Alignment.CenterVertically) {
                        Showpiece(picked, Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = true))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { picked?.let { Named(it, details, short) } }
                    }
                    Spacer(Modifier.height(12.dp))
                    list(withTabs = true, short = short)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Side by side on a wide screen, one above another on a tall one.
        if (wideScreen || short) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                actions(picked, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth()) { actions(picked, Modifier.fillMaxWidth()) }
        }
    }
    }
}

/** The chosen craft's picture: the large one once it's drawn, the small one till then. */
@Composable
private fun Showpiece(item: ShowcaseItem?, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(Dimens.CornerPanel))
            .background(Color.White.alpha(ApogeeAlpha.FILL_FAINT)),
        contentAlignment = Alignment.Center,
    ) {
        val picture = item?.largePicture ?: item?.picture
        picture?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** The chosen craft's name and [details], in the details' text style, smaller on a [short] screen. */
@Composable
private fun ColumnScope.Named(item: ShowcaseItem, details: @Composable ColumnScope.(ShowcaseItem) -> Unit, short: Boolean) {
    Spacer(Modifier.height(8.dp))
    Text(
        item.name,
        style = if (short) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(4.dp))
    androidx.compose.material3.ProvideTextStyle(if (short) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge) {
        details(item)
    }
}

/** The narrowest the name and details beside the picture get. */
private val DETAILS_WIDTH = 170.dp

/** One craft in the list, lit when chosen. */
@Composable
private fun ShowcaseRow(item: ShowcaseItem, chosen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(if (chosen) ApogeeColors.Accent.alpha(0.22f) else Color.Transparent)
            .padFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(Dimens.CornerTight)).background(Color.White.alpha(ApogeeAlpha.FILL_FAINT)),
            contentAlignment = Alignment.Center,
        ) {
            item.picture?.let { Image(it, contentDescription = null, modifier = Modifier.size(40.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name, style = MaterialTheme.typography.bodyLarge,
                color = if (chosen) ApogeeColors.Accent else Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (item.line.isNotEmpty()) {
                Text(item.line, style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
