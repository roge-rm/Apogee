package com.rm.apogee.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rm.apogee.core.craft.CraftKind
import com.rm.apogee.core.world.LaunchSite
import com.rm.apogee.game.CraftShelf
import com.rm.apogee.ui.components.ApogeeButton
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.components.verticalScrollbar
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.alpha

/**
 * Quick Launch: pick a craft and where to launch it from, then go. It goes in place of the craft
 * you flew last, so the world doesn't fill up with half-flown ones. It used to put the stock rocket
 * on the Cape's pad every time, and anything else meant a trip through the Vehicle Assembly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickLaunchScreen(
    /** The saved craft, or empty while they're read. */
    entries: List<CraftShelf.Entry>,
    pictures: Map<String, ImageBitmap>,
    /** The chosen craft, by file name. */
    chosen: String?,
    onChoose: (String) -> Unit,
    /** The chosen site, by id, or null for Automatic. */
    site: String?,
    /** Where Automatic would send the chosen craft. */
    automatic: String,
    bases: List<LaunchSite>,
    onSite: (String?) -> Unit,
    onLaunch: () -> Unit,
) {
    var pickingSite by remember { mutableStateOf(false) }
    Backdrop { contentModifier ->
        Text("Quick Launch", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Column(contentModifier.fillMaxWidth()) {
            if (entries.isEmpty()) {
                Text("Reading your craft…", color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            } else {
                val kinds = CraftKind.entries.filter { k -> entries.any { it.kind == k } }
                var shownKind by rememberSaveable { mutableStateOf<String?>(null) }
                val kind = kinds.firstOrNull { it.name == shownKind }
                FlowRow(
                    Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KindTab("All", kind == null) { shownKind = null }
                    for (k in kinds) KindTab(k.label, k == kind) { shownKind = k.name }
                }
                val shown = if (kind == null) entries else entries.filter { it.kind == kind }
                val list = rememberLazyListState()
                // Opened on the craft chosen last time, wherever it is in the list.
                androidx.compose.runtime.LaunchedEffect(entries) {
                    val at = shown.indexOfFirst { it.saved.fileName == chosen }
                    if (at > 0) list.scrollToItem(at)
                }
                LazyColumn(Modifier.heightIn(max = 340.dp).verticalScrollbar(list), state = list) {
                    items(shown, key = { it.saved.fileName }) { entry ->
                        CraftRow(entry, pictures[entry.picture], entry.saved.fileName == chosen) { onChoose(entry.saved.fileName) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        ApogeeButton(
            "From: " + (site?.let { id -> (com.rm.apogee.core.world.World.launchSites + bases).firstOrNull { it.id == id }?.displayName } ?: "Automatic · $automatic"),
            { pickingSite = true },
            contentModifier,
            subtitle = "Where it's launched from",
        )
        ApogeeButton(
            "Launch",
            onLaunch,
            contentModifier,
            subtitle = entries.firstOrNull { it.saved.fileName == chosen }?.saved?.name?.let { "$it, in place of your last craft" }
                ?: "Pick a craft first",
            enabled = entries.any { it.saved.fileName == chosen },
        )
    }
    if (pickingSite) {
        SiteDialog(
            selected = site,
            automatic = automatic,
            bases = bases,
            onPick = { onSite(it); pickingSite = false },
            onDismiss = { pickingSite = false },
        )
    }
}

/** One craft in the list, lit when it's the one chosen. */
@Composable
private fun CraftRow(entry: CraftShelf.Entry, picture: ImageBitmap?, chosen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(if (chosen) ApogeeColors.Accent.alpha(0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(Dimens.CornerTight))
                .background(Color.White.alpha(ApogeeAlpha.FILL_FAINT)),
            contentAlignment = Alignment.Center,
        ) {
            picture?.let { Image(it, contentDescription = null, modifier = Modifier.size(48.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.saved.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (chosen) ApogeeColors.Accent else Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.summary, style = MaterialTheme.typography.labelSmall,
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
