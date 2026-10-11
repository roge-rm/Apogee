package com.rm.apogee.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.rm.apogee.game.CraftShelf
import com.rm.apogee.ui.components.ApogeeButton
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.alpha

/**
 * What to fly in a game joined from here: the player's own craft, which go up to the host, and
 * the host's others. Over the flight while the world loads behind it.
 */
@Composable
fun JoinCraftScreen(
    /** The player's own craft first, then the host's. */
    entries: List<CraftShelf.Entry>,
    pictures: Map<String, ImageBitmap>,
    chosen: String?,
    onChoose: (String) -> Unit,
    /** The name of the craft the player already has here, or null if they have none. */
    craftHere: String?,
    /** Whether the host is still sending its craft. */
    loading: Boolean,
    onFly: (CraftShelf.Entry) -> Unit,
    onKeep: () -> Unit,
    onLeave: () -> Unit,
    /** Asks for the craft's large picture, by its key. */
    onShow: (String) -> Unit = {},
) {
    Backdrop(title = "Choose your craft", onBack = onLeave, fillHeight = true, maxContentWidth = Dimens.WideContentMaxWidth) { contentModifier ->
        if (loading) {
            Text("Getting the host's craft…", color = Color.White.alpha(ApogeeAlpha.SUBTITLE), modifier = contentModifier)
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
                if (craftHere != null) ApogeeButton("Keep flying $craftHere", onKeep, each)
                ApogeeButton(
                    item?.let { "Fly ${it.name}" } ?: "Fly",
                    { entries.firstOrNull { it.saved.fileName == item?.key }?.let(onFly) },
                    each,
                    enabled = item != null,
                )
            },
        )
    }
}
