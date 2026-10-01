package com.rm.apogee.ui.components.builder

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.apogee.core.part.Command
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.Tank
import com.rm.apogee.ui.components.verticalScrollbar
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.abs
import kotlin.math.roundToInt
import com.rm.apogee.platform.format

/** Callbacks for dragging a part out of the drawer. Positions are in window pixels. */
class PaletteCarry(
    val start: (String) -> Unit,
    val move: (Offset) -> Unit,
    val end: () -> Unit,
)

/**
 * The parts drawer: a rail of tabs and a grid of pictures. Tap a part to hold it, then tap a green
 * node, or drag it sideways out onto the node. Up or down scrolls.
 */
@Composable
fun PartPalette(
    catalog: PartCatalog,
    pictures: Map<String, ImageBitmap>,
    tab: PartTab,
    onTab: (PartTab) -> Unit,
    columns: Int,
    tileSize: Dp,
    heldPartId: String?,
    onPick: (String) -> Unit,
    carry: PaletteCarry,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** In a career, locked part ids mapped to the tech node that unlocks each. Shown faded. */
    locked: Map<String, String> = emptyMap(),
    /** A locked part was tapped. */
    onLocked: (String) -> Unit = {},
    /** The saved pieces, for the Saved tab. */
    saved: List<SavedPiece> = emptyList(),
    onPickSaved: (String) -> Unit = {},
    onDeleteSaved: (String) -> Unit = {},
) {
    val parts = remember(catalog, tab) { PartTabs.parts(catalog, tab) }
    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = Color.Black.alpha(ApogeeAlpha.SCRIM),
        modifier = modifier,
    ) {
        Row(Modifier.fillMaxHeight()) {
            TabRail(tab, onTab)
            Column(Modifier.fillMaxHeight().width(tileSize * columns + 14.dp).padding(end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                // The tab's name. Tap the chevron or swipe left to put the drawer away.
                var swipe by remember { mutableFloatStateOf(0f) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { swipe += it },
                            onDragStarted = { swipe = 0f },
                            onDragStopped = { if (swipe < -40f) onClose() },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.label.uppercase(),
                        style = TelemetryTextStyle,
                        color = Color.White.alpha(ApogeeAlpha.BODY),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = "Put the parts away",
                        tint = Color.White.alpha(ApogeeAlpha.SECONDARY),
                        modifier = Modifier.clip(RoundedCornerShape(Dimens.CornerTight)).clickable(onClick = onClose).padding(4.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                val grid = rememberLazyGridState()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    modifier = Modifier.verticalScrollbar(grid),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(parts, key = { it.id }) { def ->
                        if (def.id in locked) LockedTile(def, pictures[def.id], tileSize, onLocked)
                        else PartTile(def, pictures[def.id], tileSize, def.id == heldPartId, onPick, carry)
                    }
                    if (tab == PartTab.SAVED) {
                        if (saved.isEmpty()) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "Tap a part, then SAVE ASSEMBLY",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                                    modifier = Modifier.padding(6.dp),
                                )
                            }
                        }
                        items(saved, key = { it.file }) { piece ->
                            SavedTile(piece, pictures[piece.rootPartId], tileSize, locked, onPickSaved, onDeleteSaved)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabRail(tab: PartTab, onTab: (PartTab) -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .padding(4.dp)
            .verticalScrollbar(scroll, width = 2.dp, inset = 0.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (t in PartTab.entries) {
            val chosen = t == tab
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(Dimens.CornerTight))
                    .background(if (chosen) ApogeeColors.Accent.alpha(0.3f) else Color.Transparent)
                    .clickable { onTab(t) }
                    .semantics { contentDescription = t.label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconFor(t),
                    contentDescription = null,
                    tint = if (chosen) ApogeeColors.Accent else Color.White.alpha(ApogeeAlpha.SECONDARY),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun PartTile(
    def: PartDef,
    picture: ImageBitmap?,
    size: Dp,
    held: Boolean,
    onPick: (String) -> Unit,
    carry: PaletteCarry,
) {
    var where by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Column(
        Modifier
            .width(size)
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(if (held) ApogeeColors.Accent.alpha(0.22f) else Color.White.alpha(ApogeeAlpha.FILL_FAINT))
            .then(if (held) Modifier.border(1.5.dp, ApogeeColors.Accent, RoundedCornerShape(Dimens.CornerTight)) else Modifier)
            .onGloballyPositioned { where = it }
            .pointerInput(def.id) {
                // Sideways carries the part. Up or down is left to the grid to scroll.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var carrying = false
                    var moved = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (carrying) { change.consume(); carry.end() }
                            break
                        }
                        if (!carrying) {
                            moved += change.positionChange()
                            if (moved.getDistance() > viewConfiguration.touchSlop) {
                                if (abs(moved.x) > abs(moved.y) * 1.2f) {
                                    carrying = true
                                    carry.start(def.id)
                                } else {
                                    break
                                }
                            }
                        }
                        if (carrying) {
                            change.consume()
                            where?.let { carry.move(it.localToWindow(change.position)) }
                        }
                    }
                }
            }
            .clickable { onPick(def.id) }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size - 16.dp), contentAlignment = Alignment.Center) {
            if (picture != null) {
                Image(picture, contentDescription = null, modifier = Modifier.size(size - 16.dp))
            } else {
                Icon(iconFor(PartTabs.of(def)), contentDescription = null, tint = Color.White.alpha(ApogeeAlpha.BORDER))
            }
        }
        Text(
            def.title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
            color = if (held) ApogeeColors.Accent else Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            keyFigure(def),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            maxLines = 1,
        )
    }
}

/** A saved piece of craft, as the drawer shows it. */
class SavedPiece(val name: String, val rootPartId: String, val partIds: List<String>, val file: String)

/** A saved piece: tap to hold it, long-press to delete (asks first). Faded if any part is locked. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SavedTile(
    piece: SavedPiece,
    picture: ImageBitmap?,
    size: Dp,
    locked: Map<String, String>,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Delete ${piece.name}?") },
            text = { Text("It's gone for good. Craft built with it keep it.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { asking = false; onDelete(piece.file) }) { Text("Delete") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text("Keep") } },
        )
    }
    val usable = piece.partIds.none { it in locked }
    Column(
        Modifier
            .width(size)
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(Color.White.alpha(ApogeeAlpha.FILL_FAINT))
            .combinedClickable(onClick = { onPick(piece.file) }, onLongClick = { asking = true })
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size - 16.dp), contentAlignment = Alignment.Center) {
            if (picture != null) {
                Image(picture, contentDescription = null, modifier = Modifier.size(size - 16.dp).alpha(if (usable) 1f else 0.3f))
            } else {
                Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Color.White.alpha(ApogeeAlpha.BORDER))
            }
            if (!usable) Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Color.White.alpha(ApogeeAlpha.SECONDARY), modifier = Modifier.size(18.dp))
        }
        Text(
            piece.name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
            color = if (usable) Color.White else Color.White.alpha(ApogeeAlpha.SUBTITLE),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            if (piece.partIds.size == 1) "1 part" else "${piece.partIds.size} parts",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            maxLines = 1,
        )
    }
}

/** A part the career hasn't unlocked yet, shown but not usable. */
@Composable
private fun LockedTile(def: PartDef, picture: ImageBitmap?, size: Dp, onLocked: (String) -> Unit) {
    Column(
        Modifier
            .width(size)
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(Color.White.alpha(ApogeeAlpha.FILL_FAINT))
            .clickable { onLocked(def.id) }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size - 16.dp), contentAlignment = Alignment.Center) {
            if (picture != null) {
                Image(picture, contentDescription = null, modifier = Modifier.size(size - 16.dp).alpha(0.3f))
            }
            Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Color.White.alpha(ApogeeAlpha.SECONDARY), modifier = Modifier.size(18.dp))
        }
        Text(
            def.title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** A part's key number: thrust for an engine, capacity for a tank, otherwise mass. */
fun keyFigure(def: PartDef): String {
    val engine = def.module<Engine>()
    // The better of the two, since an air-breather or propeller has none in vacuum.
    if (engine != null) return "${(maxOf(engine.thrustVacuum, engine.thrustSeaLevel) / 1_000.0).roundToInt()} kN"
    val tank = def.module<Tank>()
    if (tank != null && !def.hasModule<Command>()) {
        val what = when (tank.resource) {
            com.rm.apogee.core.part.ResourceType.ORE -> "ore"
            com.rm.apogee.core.part.ResourceType.WATER -> "water"
            else -> "fuel"
        }
        return "${tank.capacity.roundToInt()} $what"
    }
    return if (def.dryMass >= 1_000.0) "%.1f t".format(def.dryMass / 1_000.0) else "${def.dryMass.roundToInt()} kg"
}

fun iconFor(tab: PartTab): ImageVector = when (tab) {
    PartTab.ALL -> Icons.Filled.Apps
    PartTab.PODS -> Icons.Filled.Person
    PartTab.TANKS -> Icons.Filled.LocalGasStation
    PartTab.ENGINES -> Icons.Filled.LocalFireDepartment
    PartTab.STRUCTURE -> Icons.Filled.Construction
    PartTab.WINGS -> Icons.Filled.Flight
    PartTab.AIR -> Icons.Filled.Cloud
    PartTab.GROUND -> Icons.Filled.TireRepair
    PartTab.WATER -> Icons.Filled.Sailing
    PartTab.UTILITY -> Icons.Filled.Hub
    PartTab.BASE -> Icons.Filled.Home
    PartTab.BUILDINGS -> Icons.Filled.Domain
    PartTab.SAVED -> Icons.Filled.Bookmark
}
