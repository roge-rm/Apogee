package com.rm.apogee.ui.screens

import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import com.rm.apogee.settings.GameSettings
import com.rm.apogee.ui.components.builder.CarryPicture
import com.rm.apogee.ui.components.builder.HeldChip
import com.rm.apogee.ui.components.builder.PaletteCarry
import com.rm.apogee.ui.components.builder.PartActionBar
import com.rm.apogee.ui.components.builder.PartPalette
import com.rm.apogee.ui.components.builder.PartTab
import com.rm.apogee.ui.components.builder.SlidePanel
import com.rm.apogee.ui.components.builder.StatsChip
import androidx.compose.foundation.lazy.rememberLazyListState
import com.rm.apogee.ui.components.verticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.SymmetryMode
import com.rm.apogee.core.part.PartCategory
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.world.World
import com.rm.apogee.game.BuilderSession
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

/**
 * The vehicle assembly building.
 *
 * Transparent, like the flight HUD - the craft itself is drawn by the GL
 * surface underneath, and every panel here carries its own scrim. The panels
 * slide away to the edges: the drawer by itself while a part is in hand, so
 * the craft is in view to put it on.
 */
@Composable
fun BuilderScreen(
    session: BuilderSession,
    catalog: PartCatalog,
    settings: GameSettings,
    pictures: Map<String, ImageBitmap>,
    onExit: () -> Unit,
    onLaunch: () -> Unit,
) {
    // Reading `revision` is what subscribes this composable to the plain
    // mutable builder model underneath.
    @Suppress("UNUSED_EXPRESSION") session.revision

    var showLoadDialog by remember { mutableStateOf(false) }
    var showSiteDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    // Opened by its handle while something is in hand: stays out until the next pick.
    var peek by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxWidth < maxHeight
        val density = LocalDensity.current
        val screen = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val carrying = session.carryPoint != null
        val holding = session.held != null

        // --- the drawer or the stages, left -------------------------------------
        // The same place for both: editing the staging is not placing parts,
        // and the craft stays clear down the middle either way.
        // Pinned by its top, at one height whatever the tab holds: centred
        // and sized to its parts, it jumped up and down from tab to tab
        // (Dan). Below the toolbar and the stats line in portrait, clear of
        // the launch button at the bottom.
        val top = if (portrait) 112.dp else 8.dp
        val bottom = if (portrait) 150.dp else 8.dp
        val paletteHeight = (maxHeight - top - bottom).coerceIn(200.dp, 560.dp)
        val leftModifier = Modifier
            .align(Alignment.TopStart)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(start = 8.dp, top = top)
        if (session.stagingMode) {
            SlidePanel(
                shown = settings.builderStagesOpen,
                onOpen = { settings.builderStagesOpen = true },
                handleLabel = "Show the stages",
                modifier = leftModifier,
                onCovers = { session.leftInset = it },
            ) {
                StagePanel(
                    session,
                    entries = session.stageEntries,
                    manual = session.manualStaging,
                    selected = session.selectedStage,
                    width = if (portrait) 190.dp else 230.dp,
                    onClose = { settings.builderStagesOpen = false },
                )
            }
        } else {
            val horizontal = session.orientation == CraftOrientation.HORIZONTAL
            val tabName = if (horizontal) settings.builderTabHorizontal else settings.builderTabVertical
            val tab = PartTab.entries.firstOrNull { it.name == tabName } ?: PartTab.ALL
            SlidePanel(
                // Tucked away while a part is in hand, carried or being worked
                // on with the action bar, unless pulled out.
                shown = settings.builderPartsOpen && !carrying && ((!holding && session.selectedPartIndex == null) || peek),
                onOpen = { settings.builderPartsOpen = true; peek = true },
                handleLabel = "Show the parts",
                modifier = leftModifier,
                onCovers = { session.leftInset = it },
            ) {
                PartPalette(
                    catalog = catalog,
                    pictures = pictures,
                    tab = tab,
                    onTab = {
                        if (horizontal) settings.builderTabHorizontal = it.name else settings.builderTabVertical = it.name
                    },
                    columns = 2,
                    tileSize = if (portrait) 66.dp else 70.dp,
                    heldPartId = session.heldPartId,
                    onPick = {
                        peek = false
                        session.selectPart(if (session.heldPartId == it) null else it)
                    },
                    carry = PaletteCarry(
                        start = { peek = false; session.beginCarry(it) },
                        move = { session.carryTo(it.x, it.y) },
                        end = session::endCarry,
                    ),
                    onClose = { settings.builderPartsOpen = false; peek = false },
                    modifier = Modifier.height(paletteHeight),
                )
            }
        }

        // --- stats, right ------------------------------------------------------
        // One line until asked for more - the whole card is out by default
        // only where there is room beside the craft.
        val statsOpen = if (portrait) settings.builderStatsOpenPortrait else settings.builderStatsOpenLandscape
        val setStats: (Boolean) -> Unit = { if (portrait) settings.builderStatsOpenPortrait = it else settings.builderStatsOpenLandscape = it }
        val statsModifier = Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .padding(top = if (portrait) 68.dp else 64.dp)
        if (statsOpen) {
            StatsPanel(
                stats = session.stats,
                craftName = session.builder.name,
                width = if (portrait) 200.dp else 230.dp,
                onClose = { setStats(false) },
                // The craft is drawn in the space left between it and the drawer.
                modifier = statsModifier.onGloballyPositioned { session.rightInset = screen.width - it.positionInWindow().x },
            )
        } else {
            LaunchedEffect(Unit) { session.rightInset = 0f }
            StatsChip(session.stats, onOpen = { setStats(true) }, modifier = statsModifier)
        }

        // --- toolbar, top -------------------------------------------------------
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.HudGroupGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Filled.Close, "Leave the assembly building", onExit)
            ToolButton(Icons.Filled.Undo, "Undo", session::undo)
            ToolButton(Icons.Filled.Redo, "Redo", session::redo)
            OrientationButton(session.orientation, session::toggleOrientation)
            SymmetryButton(session.symmetry, session::toggleSymmetry)
            StagesButton(session.stagingMode, session::toggleStagingMode)
            FileMenu(
                onSave = { if (session.builder.name == "Untitled") showNameDialog = true else session.save() },
                onSaveAs = { showNameDialog = true },
                onLoad = { showLoadDialog = true },
                onNew = { confirmNew = true },
            )
        }

        // --- in hand, below the toolbar ------------------------------------------
        val held = session.held
        if (held != null && !carrying) {
            val title = held.partId?.let { catalog[it]?.title }
                ?: "Copy of ${catalog[held.assembly.rootPartId]?.title ?: "part"}" + if (held.assembly.size > 1) " +${held.assembly.size - 1}" else ""
            HeldChip(
                title = title,
                picture = pictures[held.assembly.rootPartId],
                onDrop = { peek = false; session.dropHeld() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(top = if (portrait) 64.dp + 40.dp else 64.dp),
            )
        }

        // --- the tapped part's actions ---------------------------------------------
        val selection = session.selection
        val anchor = session.selectionAnchor
        if (selection != null && anchor != null && !session.stagingMode && !carrying) {
            PartActionBar(
                selection = selection,
                anchor = anchor,
                screen = screen,
                onDelete = session::deleteSelected,
                onCopy = session::duplicateSelected,
                onTurn = session::turnSelected,
                onStage = session::stageSelected,
                onClose = session::clearSelection,
            )
        }

        // --- what a finger carries, while it has nowhere to go ------------------------
        val point = session.carryPoint
        if (point != null && !session.carrySnapped) {
            CarryPicture(
                picture = session.carryPartId?.let { pictures[it] },
                at = point,
                lift = screen.height * BuilderSession.FINGER_LIFT,
            )
        }

        // --- launch, bottom ------------------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            session.statusMessage?.let { message ->
                Surface(
                    shape = RoundedCornerShape(Dimens.CornerSmall),
                    color = Color.Black.alpha(ApogeeAlpha.SCRIM),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.alpha(ApogeeAlpha.BODY),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Where it goes. Small and above the button rather than a step
            // before it: nearly every launch wants the default.
            val chosen = session.allSites().firstOrNull { it.id == session.launchSiteId }
            Surface(
                shape = RoundedCornerShape(Dimens.CornerSmall),
                color = Color.Black.alpha(ApogeeAlpha.SCRIM),
            ) {
                Text(
                    "FROM  " + (chosen?.displayName ?: "Automatic \u00b7 ${session.automaticSite().displayName}") + "  \u25BE",
                    style = TelemetryTextStyle,
                    color = Color.White.alpha(ApogeeAlpha.BODY),
                    modifier = Modifier
                        .clickable { showSiteDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            val launchable = session.designForLaunch() != null
            Surface(
                shape = RoundedCornerShape(Dimens.CornerActionBar),
                color = if (launchable) {
                    ApogeeColors.Accent.alpha(0.9f)
                } else {
                    Color.White.alpha(ApogeeAlpha.FILL_FAINT)
                },
                contentColor = if (launchable) Color(0xFF1A1030) else Color.White.alpha(ApogeeAlpha.BORDER),
                modifier = Modifier.widthIn(min = Dimens.HudActionBarWidth),
            ) {
                Row(
                    Modifier
                        .clickable(enabled = launchable, onClick = onLaunch)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.RocketLaunch, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "LAUNCH",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (showNameDialog) {
        NameDialog(
            initial = session.builder.name,
            onDismiss = { showNameDialog = false },
            onConfirm = { name ->
                session.rename(name)
                session.save()
                showNameDialog = false
            },
        )
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Start a new craft?") },
            text = { Text("This clears the building. Undo brings it back.") },
            confirmButton = { TextButton(onClick = { session.clear(); confirmNew = false }) { Text("New") } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Keep building") } },
        )
    }

    if (showSiteDialog) {
        SiteDialog(
            selected = session.launchSiteId,
            automatic = session.automaticSite().displayName,
            bases = session.baseSites,
            onPick = { session.launchSiteId = it; showSiteDialog = false },
            onDismiss = { showSiteDialog = false },
        )
    }

    if (showLoadDialog) {
        LoadDialog(
            session = session,
            onDismiss = { showLoadDialog = false },
        )
    }
}

/** Save, save as, load and new: the file things, out of the way. */
@Composable
private fun FileMenu(onSave: () -> Unit, onSaveAs: () -> Unit, onLoad: () -> Unit, onNew: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(Icons.Filled.MoreVert, "Save, load or start again", { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Save") }, leadingIcon = { Icon(Icons.Filled.Save, null) }, onClick = { open = false; onSave() })
            DropdownMenuItem(text = { Text("Save as…") }, leadingIcon = { Icon(Icons.Filled.Save, null) }, onClick = { open = false; onSaveAs() })
            DropdownMenuItem(text = { Text("Load") }, leadingIcon = { Icon(Icons.Filled.FolderOpen, null) }, onClick = { open = false; onLoad() })
            DropdownMenuItem(text = { Text("New") }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { open = false; onNew() })
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(Dimens.HudIconSize),
        colors = if (tint != null) {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = tint.alpha(0.25f),
                contentColor = tint,
            )
        } else {
            IconButtonDefaults.filledTonalIconButtonColors()
        },
    ) {
        Icon(icon, contentDescription = description)
    }
}

/**
 * Vertical or horizontal. Drawn as a bar standing up or lying down rather
 * than an icon, because the bar *is* the craft and that is the whole choice.
 */
@Composable
private fun OrientationButton(orientation: CraftOrientation, onToggle: () -> Unit) {
    // Takes the value rather than the session: the session is not observable
    // state, so a composable reading it through a stable parameter is skipped
    // on recomposition and the bar never turned over.
    val horizontal = orientation == CraftOrientation.HORIZONTAL
    Surface(
        shape = RoundedCornerShape(Dimens.HudIconSize / 2),
        color = if (horizontal) ApogeeColors.Accent.alpha(0.3f) else Color.White.alpha(ApogeeAlpha.CONTROL_FILL),
        modifier = Modifier.size(Dimens.HudIconSize),
    ) {
        Box(
            Modifier
                .clickable(onClick = onToggle)
                .semantics { contentDescription = "Build ${orientation.other().label.lowercase()}" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(
                        width = if (horizontal) Dimens.HudIconSize * 0.55f else Dimens.HudIconSize * 0.18f,
                        height = if (horizontal) Dimens.HudIconSize * 0.18f else Dimens.HudIconSize * 0.55f,
                    )
                    .background(
                        if (horizontal) ApogeeColors.Accent else Color.White.alpha(ApogeeAlpha.SECONDARY),
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

/** Switches the left panel between the parts and the staging sequence. */
@Composable
private fun StagesButton(active: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Dimens.HudIconSize / 2),
        color = if (active) ApogeeColors.Accent.alpha(0.3f) else Color.White.alpha(ApogeeAlpha.CONTROL_FILL),
        modifier = Modifier.size(Dimens.HudIconSize),
    ) {
        Box(
            Modifier
                .clickable(onClick = onToggle)
                .semantics { contentDescription = if (active) "Back to parts" else "Edit stages" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Layers,
                contentDescription = null,
                tint = if (active) ApogeeColors.Accent else Color.White.alpha(ApogeeAlpha.SECONDARY),
            )
        }
    }
}

/**
 * The staging sequence as cards, the stage that fires last at the top and
 * stage 0 at the bottom - the way the stack sits above the STAGE button in
 * flight. Tap a card to choose it, and its parts light up on the craft; tap
 * parts on the craft to move them into it. The chosen card carries its own
 * controls: fire earlier, fire later, remove.
 */
@Composable
private fun StagePanel(
    session: BuilderSession,
    // Values, not read through the session: it is not observable state, and
    // a composable given only it is skipped when the stages change.
    entries: List<BuilderSession.StageEntry>,
    manual: Boolean,
    selected: Int?,
    width: Dp,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = Color.Black.alpha(ApogeeAlpha.SCRIM),
        modifier = modifier.width(width).heightIn(max = 460.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "Put the stages away",
                    tint = Color.White.alpha(ApogeeAlpha.SECONDARY),
                    modifier = Modifier.clip(RoundedCornerShape(Dimens.CornerTight)).clickable(onClick = onClose).padding(2.dp),
                )
                Text("STAGES", style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.BODY))
                Spacer(Modifier.weight(1f))
                // Automatic until the player changes something; tapping it
                // when manual hands the sequence back.
                Text(
                    if (manual) "MANUAL · AUTO?" else "AUTO",
                    style = TelemetryTextStyle,
                    color = if (manual) ApogeeColors.Caution else ApogeeColors.Prograde,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Dimens.CornerTight))
                        .clickable(enabled = manual, onClick = session::useAutomaticStaging)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            val list = rememberLazyListState()
            LazyColumn(
                Modifier.weight(1f, fill = false).verticalScrollbar(list),
                state = list,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries.asReversed(), key = { it.index }) { entry ->
                    StageEntryCard(
                        entry,
                        selected = entry.index == selected,
                        last = entry.index == entries.size - 1,
                        onSelect = { session.selectStage(if (entry.index == selected) null else entry.index) },
                        onLater = { session.shiftStage(entry.index, 1) },
                        onEarlier = { session.shiftStage(entry.index, -1) },
                        onRemove = { session.removeStage(entry.index) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "+ STAGE",
                style = TelemetryTextStyle,
                color = ApogeeColors.Accent,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(Dimens.CornerTight))
                    .clickable(onClick = session::addStage)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StageEntryCard(
    entry: BuilderSession.StageEntry,
    selected: Boolean,
    last: Boolean,
    onSelect: () -> Unit,
    onLater: () -> Unit,
    onEarlier: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(if (selected) ApogeeColors.Accent.alpha(0.22f) else Color.White.alpha(ApogeeAlpha.FILL_FAINT))
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "S${entry.index}",
                style = TelemetryTextStyle,
                color = if (selected) ApogeeColors.Accent else Color.White.alpha(ApogeeAlpha.SECONDARY),
            )
            if (entry.index == 0) {
                Spacer(Modifier.width(6.dp))
                Text("fires first", style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            }
            Spacer(Modifier.weight(1f))
            if (selected) {
                StageAction("▲", "Fire later", enabled = !last, onClick = onLater)
                StageAction("▼", "Fire earlier", enabled = entry.index > 0, onClick = onEarlier)
                StageAction("✕", "Remove stage", enabled = true, onClick = onRemove, colour = ApogeeColors.Danger)
            }
        }
        if (entry.parts.isEmpty()) {
            Text(
                if (selected) "Empty - tap parts on the craft" else "Empty",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            )
        } else {
            for (name in entry.parts) {
                Text(name, style = MaterialTheme.typography.bodySmall, color = Color.White.alpha(ApogeeAlpha.BODY))
            }
        }
    }
}

@Composable
private fun StageAction(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit, colour: Color = ApogeeColors.Accent) {
    Text(
        symbol,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) colour else Color.White.alpha(ApogeeAlpha.BORDER),
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun SymmetryButton(symmetry: SymmetryMode, onToggle: () -> Unit) {
    // The value, not the session, for the same reason as the orientation
    // button: the session is not observable state.
    val active = symmetry.count > 1
    Surface(
        shape = RoundedCornerShape(Dimens.HudIconSize / 2),
        color = if (active) ApogeeColors.Accent.alpha(0.3f) else Color.White.alpha(ApogeeAlpha.CONTROL_FILL),
        modifier = Modifier.size(Dimens.HudIconSize),
    ) {
        Box(
            Modifier.clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                symmetry.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) ApogeeColors.Accent else Color.White.alpha(ApogeeAlpha.SECONDARY),
            )
        }
    }
}

@Composable
private fun StatsPanel(
    stats: CraftStats,
    craftName: String,
    width: Dp,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = Color.Black.alpha(ApogeeAlpha.SCRIM),
        modifier = modifier.width(width),
    ) {
        Column(Modifier.clickable(onClick = onClose).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    craftName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ExpandLess, contentDescription = "Just the line", tint = Color.White.alpha(ApogeeAlpha.SECONDARY))
            }
            Spacer(Modifier.height(6.dp))
            StatRow("PARTS", "${stats.partCount}")
            StatRow("MASS", "${(stats.totalMass / 1000).format(2)} t")
            StatRow(
                "Δv",
                "${stats.totalDeltaV.roundToInt()} m/s",
                if (stats.totalDeltaV > 3_400) ApogeeColors.Prograde else ApogeeColors.Data,
            )
            StatRow(
                "TWR",
                stats.liftoffTwr.format(2),
                if (stats.liftoffTwr >= 1.0) ApogeeColors.Prograde else ApogeeColors.Danger,
            )
            // Charge held, and a second's worth in full sun against just being on.
            if (stats.powerCapacity > 0.0) {
                StatRow("POWER", "${stats.powerCapacity.roundToInt()}")
                if (stats.powerSunlit > 0.0) StatRow("SUN", "+${stats.powerSunlit.format(2)}/s", ApogeeColors.Prograde)
                StatRow("IDLE", "\u2212${stats.powerIdle.format(2)}/s")
            }
            if (stats.drillRate > 0.0) StatRow("DRILL", "${stats.drillRate.format(1)}/s")
            if (stats.refineRate > 0.0) StatRow("REFINE", "${stats.refineRate.format(1)}/s")
            if (stats.canSurvey) StatRow("SCANNER", "survey")

            if (stats.burns.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.alpha(ApogeeAlpha.DIVIDER))
                Spacer(Modifier.height(6.dp))
                stats.burns.forEach { stage ->
                    StatRow(
                        "S${stage.index}",
                        "${stage.deltaV.roundToInt()} m/s · ${stage.burnTime.roundToInt()}s",
                    )
                }
            }

            if (stats.problems.isNotEmpty() || stats.warnings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.alpha(ApogeeAlpha.DIVIDER))
                Spacer(Modifier.height(6.dp))
                // Blockers read as danger, advice reads as caution. Showing
                // both in the same colour is what made a lander look broken.
                stats.problems.forEach { problem ->
                    Text(
                        problem,
                        style = MaterialTheme.typography.labelSmall,
                        color = ApogeeColors.Danger,
                    )
                }
                stats.warnings.forEach { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = ApogeeColors.Caution,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, colour: Color = ApogeeColors.Data) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
        Text(value, style = TelemetryTextStyle, color = colour)
    }
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save craft") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.ifBlank { "Untitled" }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LoadDialog(session: BuilderSession, onDismiss: () -> Unit) {
    // Deleting is permanent - there is no undo for a file - so it asks first.
    var confirmDelete by remember { mutableStateOf<com.rm.apogee.core.craft.SavedCraft?>(null) }
    confirmDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${doomed.name}\"?") },
            text = { Text("This removes the saved design for good. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    session.delete(doomed)
                    confirmDelete = null
                }) { Text("Delete", color = ApogeeColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load craft") },
        text = {
            if (session.savedCraft.isEmpty()) {
                Text("No saved craft yet.")
            } else {
                val list = rememberLazyListState()
                LazyColumn(Modifier.verticalScrollbar(list), state = list) {
                    items(session.savedCraft) { saved ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    session.load(saved)
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(saved.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${saved.partCount} parts",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(onClick = { confirmDelete = saved }) { Text("Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SiteDialog(
    selected: String?,
    automatic: String,
    bases: List<com.rm.apogee.core.world.LaunchSite>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Launch from") },
        text = {
            // A player with many bases has many pads: scrolled, with a bar to say so.
            val scroll = androidx.compose.foundation.rememberScrollState()
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScrollbar(scroll)
                    .verticalScroll(scroll),
            ) {
                val choices = listOf<Pair<String?, String>>(null to "Automatic") +
                    World.launchSites.map { it.id to it.displayName } +
                    bases.map { it.id to it.displayName }
                // Headed by world - Terra's pads, Luna's, then every other
                // world's test site - and then the player's own bases.
                val headings = HashMap<String?, String>()
                World.launchSites.groupBy { it.bodyId }.forEach { (body, sites) ->
                    headings[sites.first().id] = body.uppercase()
                }
                bases.firstOrNull()?.let { headings[it.id] = "YOUR BASES" }
                for ((id, name) in choices) {
                    headings[id]?.let { heading ->
                        Text(
                            heading,
                            style = MaterialTheme.typography.labelSmall,
                            color = ApogeeColors.Accent,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (id == selected) ApogeeColors.Accent else Color.Unspecified,
                            )
                            if (id == null) {
                                Text(
                                    "Boats to the harbour, planes to the airfield, everything else to the pad - now $automatic",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
