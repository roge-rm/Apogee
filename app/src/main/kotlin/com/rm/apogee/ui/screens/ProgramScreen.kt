package com.rm.apogee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.rm.apogee.core.career.CareerState
import com.rm.apogee.core.career.Feat
import com.rm.apogee.core.career.Grade
import com.rm.apogee.core.career.Insight
import com.rm.apogee.core.career.TechNode
import com.rm.apogee.core.career.TechTree
import com.rm.apogee.core.career.Visit
import com.rm.apogee.core.career.WorldFirst
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha

/**
 * A career's program: what insight there is to spend and where to spend it (the tech tree by
 * branch), what the player has pulled off and how well, and where they've been, beside who got
 * there first.
 */
@Composable
fun ProgramScreen(
    state: CareerState,
    firsts: List<WorldFirst>,
    /** This player's id, to tell their own firsts from other people's. */
    me: String,
    /** Unlocks a node. Null if it worked, or the reason it didn't. */
    onUnlock: (String) -> String?,
    tree: TechTree = TechTree.stock,
    /** Opened over a flight: the way back to it. */
    onClose: (() -> Unit)? = null,
) {
    var tab by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth) { content ->
        Column(content, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Program", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("${state.insight} insight to spend", style = TelemetryTextStyle, color = ApogeeColors.Accent)
            onClose?.let {
                Spacer(Modifier.height(10.dp))
                com.rm.apogee.ui.components.ApogeeButton("Back to flight", it)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, label) in listOf("Tree", "Feats", "Worlds").withIndex()) {
                    val on = k == tab
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) ApogeeColors.Accent else ApogeeColors.SurfaceRaised)
                            .clickable { tab = k }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) ApogeeColors.Surface else Color.White.alpha(ApogeeAlpha.BODY))
                    }
                }
            }
            note?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = ApogeeColors.Caution)
            }
            Spacer(Modifier.height(14.dp))
            when (tab) {
                0 -> TreeTab(state, tree) { id -> note = onUnlock(id) }
                1 -> FeatsTab(state)
                else -> WorldsTab(state, tree, firsts, me)
            }
        }
    }
}

// --- the tree ------------------------------------------------------------------

private val BRANCH_ORDER = listOf("rocketry", "systems", "aviation", "ground", "sea", "deep", "crew")

@Composable
private fun TreeTab(state: CareerState, tree: TechTree, onUnlock: (String) -> Unit) {
    for (branch in BRANCH_ORDER) {
        val nodes = tree.nodes.filter { it.branch == branch }
        if (nodes.isEmpty()) continue
        Heading(branch.replaceFirstChar { it.uppercase() })
        for (node in nodes) NodeCard(node, state, tree, onUnlock)
    }
}

@Composable
private fun NodeCard(node: TechNode, state: CareerState, tree: TechTree, onUnlock: (String) -> Unit) {
    val have = state.has(node.id)
    val blocker = state.blocker(tree, node)
    val ready = !have && blocker == null
    Card(highlight = ready) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (have) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                contentDescription = if (have) "Unlocked" else "Locked",
                tint = if (have) ApogeeColors.Prograde else Color.White.alpha(ApogeeAlpha.SUBTITLE),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(node.title, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
            if (!have) Text("${node.cost}", style = TelemetryTextStyle, color = if (ready) ApogeeColors.Accent else Color.White.alpha(ApogeeAlpha.SECONDARY))
        }
        if (node.blurb.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(node.blurb, style = MaterialTheme.typography.bodySmall, color = Color.White.alpha(ApogeeAlpha.BODY))
        }
        val gives = gives(node)
        if (gives.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(gives, style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.SECONDARY))
        }
        if (!have) {
            Spacer(Modifier.height(6.dp))
            if (ready) {
                Text(
                    "UNLOCK · ${node.cost}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1030),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Dimens.CornerActionBar))
                        .background(ApogeeColors.Accent)
                        .clickable { onUnlock(node.id) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            } else if (blocker != null) {
                Text(blocker, style = MaterialTheme.typography.labelSmall, color = ApogeeColors.Caution)
            }
        }
    }
}

/** What a node gives, in a line: its parts by name, a facility level, or an ability. */
private fun gives(node: TechNode): String {
    val parts = node.parts.mapNotNull { StockParts.catalog[it]?.title }
    val extra = buildList {
        if (node.pad > 0) add("Launch Pad ${roman(node.pad)}")
        if (node.hangar > 0) add("Hangar ${roman(node.hangar)}")
        if (node.harbour > 0) add("Harbour ${roman(node.harbour)}")
        if (node.crew > 0) add("room for ${node.crew} crew")
        if (TechTree.AUTOPILOT in node.abilities) add("auto-burn and auto-land")
    }
    return (parts + extra).joinToString(" · ")
}

private fun roman(n: Int) = listOf("", "I", "II", "III", "IV", "V").getOrElse(n) { "$n" }

// --- feats -----------------------------------------------------------------------

@Composable
private fun FeatsTab(state: CareerState) {
    for (branch in com.rm.apogee.core.career.Branch.entries) {
        val feats = Feat.entries.filter { it.branch == branch }
        if (feats.isEmpty()) continue
        Heading(branch.title)
        for (feat in feats) {
            val grade = state.grade(feat)
            Card(highlight = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(feat.title, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            grade == null -> "—"
                            feat.graded -> grade.title.uppercase()
                            else -> "DONE"
                        },
                        style = TelemetryTextStyle,
                        color = when (grade) {
                            null -> Color.White.alpha(ApogeeAlpha.SUBTITLE)
                            Grade.GOLD -> Color(0xFFFFC94D)
                            Grade.SILVER -> Color(0xFFD0D6E0)
                            Grade.BRONZE -> if (feat.graded) Color(0xFFD89A62) else ApogeeColors.Prograde
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(feat.what, style = MaterialTheme.typography.bodySmall, color = Color.White.alpha(ApogeeAlpha.BODY))
                val metric = feat.metric
                if (metric != null) {
                    Spacer(Modifier.height(4.dp))
                    val word = if (metric.lowerIsBetter) "under" else "over"
                    Text(
                        "Silver: ${metric.label} $word ${fmt(feat.silver)} ${metric.unit} · Gold: $word ${fmt(feat.gold)} ${metric.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.alpha(ApogeeAlpha.SECONDARY),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (feat.graded) "Worth ${Insight.worth(feat, Grade.BRONZE)} · ${Insight.worth(feat, Grade.SILVER)} · ${Insight.worth(feat, Grade.GOLD)}"
                    else "Worth ${Insight.UNGRADED}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                )
            }
        }
    }
}

private fun fmt(x: Double) = if (x == Math.floor(x)) "%.0f".format(x) else "%.1f".format(x)

// --- worlds ----------------------------------------------------------------------

@Composable
private fun WorldsTab(state: CareerState, tree: TechTree, firsts: List<WorldFirst>, me: String) {
    val system = remember { SolarSystem.defaultSystem() }
    Heading("Where you've been")
    for ((id, base) in tree.worlds.entries.sortedBy { it.value }) {
        val name = system.bodies[id]?.displayName ?: id
        Card(highlight = false) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Spacer(Modifier.height(4.dp))
            // A giant has no ground, and the star even less.
            val visits = if (system.bodies[id]?.terrain == null) listOf(Visit.ORBIT) else Visit.entries
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (visit in visits) {
                    val done = state.visited(id, visit)
                    Text(
                        (if (done) "✓ " else "") + "${visit.title} ${base * visit.multiplier}",
                        style = TelemetryTextStyle,
                        color = if (done) ApogeeColors.Prograde else Color.White.alpha(ApogeeAlpha.SECONDARY),
                    )
                }
            }
            val theirs = firsts.filter { it.bodyId == id }
            if (theirs.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    theirs.joinToString(" · ") { f ->
                        val who = if (f.owner == me) "you" else f.ownerName.ifBlank { "someone" }
                        "first to ${Visit.entries.firstOrNull { it.id == f.visit }?.title?.lowercase() ?: f.visit}: $who"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                )
            }
        }
    }
    UnderTheSea(state, firsts, me)
}

/**
 * The sea's named places: each by name and world, how deep it is, and what finding it pays (what it
 * actually is only shows once it's been found), and who found it first.
 */
@Composable
private fun UnderTheSea(state: CareerState, firsts: List<WorldFirst>, me: String) {
    val system = remember { SolarSystem.defaultSystem() }
    Heading("Under the sea")
    for (wonder in com.rm.apogee.core.world.SeaWonders.all) {
        val found = "${com.rm.apogee.core.career.Program.WONDER}:${wonder.id}" in state.visits
        val depth = remember(wonder.id) { -(system.bodies[wonder.bodyId]?.terrain?.elevation(wonder.direction) ?: 0.0) }
        Card(highlight = false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(wonder.name, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.weight(1f))
                Text(
                    if (found) "✓ FOUND" else "${wonder.insight}",
                    style = TelemetryTextStyle,
                    color = if (found) ApogeeColors.Prograde else Color.White.alpha(ApogeeAlpha.SECONDARY),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${system.bodies[wonder.bodyId]?.displayName ?: wonder.bodyId} · ${"%,d".format(depth.toInt())} m down",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.alpha(ApogeeAlpha.SECONDARY),
            )
            if (found) {
                Spacer(Modifier.height(4.dp))
                Text(wonder.blurb, style = MaterialTheme.typography.bodySmall, color = Color.White.alpha(ApogeeAlpha.BODY))
            }
            firsts.firstOrNull { it.bodyId == wonder.id && it.visit == com.rm.apogee.core.career.Program.WONDER }?.let { f ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "first found by " + if (f.owner == me) "you" else f.ownerName.ifBlank { "someone" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                )
            }
        }
    }
}

// --- pieces ----------------------------------------------------------------------

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = ApogeeColors.Accent,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun Card(highlight: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(Dimens.CornerPanel))
            .background(ApogeeColors.SurfaceRaised.alpha(0.85f))
            .then(if (highlight) Modifier.border(1.dp, ApogeeColors.Accent.alpha(0.7f), RoundedCornerShape(Dimens.CornerPanel)) else Modifier)
            .padding(12.dp),
    ) { content() }
}
