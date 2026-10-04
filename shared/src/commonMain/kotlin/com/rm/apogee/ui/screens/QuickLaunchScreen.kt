package com.rm.apogee.ui.screens

import com.rm.apogee.ui.components.padFocus
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * Quick Launch: pick a craft and a site, then go. It replaces the craft you flew last, so the world
 * doesn't fill up with half-flown ones.
 */
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
    onBack: () -> Unit = {},
    /** Asks for the craft's large picture, by file name. */
    onShow: (String) -> Unit = {},
) {
    var pickingSite by remember { mutableStateOf(false) }
    Backdrop(title = "Quick Launch", onBack = onBack, fillHeight = true, maxContentWidth = Dimens.WideContentMaxWidth) { contentModifier ->
        if (entries.isEmpty()) {
            Text("Reading your craft…", color = Color.White.alpha(ApogeeAlpha.SUBTITLE), modifier = contentModifier)
            return@Backdrop
        }
        val items = entries.map { entry ->
            com.rm.apogee.ui.components.ShowcaseItem(
                key = entry.saved.fileName,
                name = entry.saved.name,
                line = entry.summary.substringBefore('\n'),
                kind = entry.kind,
                picture = pictures[entry.picture],
                largePicture = pictures[entry.picture + LARGE_SUFFIX],
            )
        }
        com.rm.apogee.ui.components.CraftShowcase(
            items, chosen, onChoose,
            modifier = contentModifier.weight(1f),
            onShow = onShow,
            details = { item ->
                val entry = entries.first { it.saved.fileName == item.key }
                Text(entry.summary, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            },
            actions = { item, each ->
                ApogeeButton(
                    "From: " + (site?.let { id -> (com.rm.apogee.core.world.World.launchSites + bases).firstOrNull { it.id == id }?.displayName } ?: automatic),
                    { pickingSite = true },
                    each,
                )
                ApogeeButton(
                    item?.let { "Launch ${it.name}" } ?: "Launch",
                    onLaunch,
                    each,
                    subtitle = item?.let { "Replaces your last craft" },
                    enabled = item != null,
                )
            },
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

/** Added to a craft's picture key for its large picture (see PartThumbnails.largeCraftKey). */
internal const val LARGE_SUFFIX = "-large"
