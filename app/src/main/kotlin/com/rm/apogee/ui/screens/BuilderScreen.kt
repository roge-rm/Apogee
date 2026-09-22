package com.rm.apogee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.part.PartCategory
import com.rm.apogee.core.part.PartCatalog
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
 * surface underneath, and every panel here carries its own scrim.
 */
@Composable
fun BuilderScreen(
    session: BuilderSession,
    catalog: PartCatalog,
    onExit: () -> Unit,
    onLaunch: () -> Unit,
) {
    // Reading `revision` is what subscribes this composable to the plain
    // mutable builder model underneath.
    @Suppress("UNUSED_EXPRESSION") session.revision

    var showLoadDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {

        // --- part drawer, left ------------------------------------------------
        PartDrawer(
            catalog = catalog,
            heldPartId = session.heldPartId,
            onSelect = { session.selectPart(if (session.heldPartId == it) null else it) },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(8.dp),
        )

        // --- stats, right ------------------------------------------------------
        StatsPanel(
            stats = session.stats,
            craftName = session.builder.name,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(8.dp),
        )

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
            SymmetryButton(session)
            ToolButton(Icons.Filled.Save, "Save", { showNameDialog = true })
            ToolButton(Icons.Filled.FolderOpen, "Load", { showLoadDialog = true })
            if (session.selectedPartIndex != null) {
                ToolButton(
                    Icons.Filled.Delete,
                    "Remove the selected part",
                    session::deleteSelected,
                    tint = ApogeeColors.Danger,
                )
            }
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

    if (showLoadDialog) {
        LoadDialog(
            session = session,
            onDismiss = { showLoadDialog = false },
        )
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

@Composable
private fun SymmetryButton(session: BuilderSession) {
    val active = session.symmetry.count > 1
    Surface(
        shape = RoundedCornerShape(Dimens.HudIconSize / 2),
        color = if (active) ApogeeColors.Accent.alpha(0.3f) else Color.White.alpha(ApogeeAlpha.CONTROL_FILL),
        modifier = Modifier.size(Dimens.HudIconSize),
    ) {
        Box(
            Modifier.clickable(onClick = session::toggleSymmetry),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                session.symmetry.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) ApogeeColors.Accent else Color.White.alpha(ApogeeAlpha.SECONDARY),
            )
        }
    }
}

@Composable
private fun PartDrawer(
    catalog: PartCatalog,
    heldPartId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Command first: the first part placed becomes the root, and a craft rooted
    // at its pod is one where staging discards the spent half.
    val ordered = remember(catalog) {
        listOf(
            PartCategory.COMMAND, PartCategory.FUEL, PartCategory.PROPULSION,
            PartCategory.STRUCTURAL, PartCategory.AERO, PartCategory.UTILITY,
            PartCategory.GROUND,
        ).flatMap { category -> catalog.byCategory(category).map { category to it } }
    }

    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = Color.Black.alpha(ApogeeAlpha.SCRIM),
        modifier = modifier.width(210.dp).heightIn(max = 420.dp),
    ) {
        LazyColumn(Modifier.padding(8.dp)) {
            items(ordered) { (category, part) ->
                val held = part.id == heldPartId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dimens.CornerTight))
                        .background(
                            if (held) ApogeeColors.Accent.alpha(0.25f) else Color.Transparent
                        )
                        .clickable { onSelect(part.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        part.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (held) ApogeeColors.Accent else Color.White,
                    )
                    Text(
                        "${category.name.lowercase()} · ${part.dryMass.roundToInt()} kg",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsPanel(
    stats: CraftStats,
    craftName: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(Dimens.CornerPanel),
        color = Color.Black.alpha(ApogeeAlpha.SCRIM),
        modifier = modifier.width(230.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                craftName,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
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

            if (stats.burns.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.alpha(ApogeeAlpha.DIVIDER))
                Spacer(Modifier.height(6.dp))
                stats.burns.forEach { stage ->
                    StatRow(
                        "S${stage.index}",
                        "${stage.deltaVVacuum.roundToInt()} m/s · ${stage.burnTime.roundToInt()}s",
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load craft") },
        text = {
            if (session.savedCraft.isEmpty()) {
                Text("No saved craft yet.")
            } else {
                LazyColumn {
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
                            TextButton(onClick = { session.delete(saved) }) { Text("Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
