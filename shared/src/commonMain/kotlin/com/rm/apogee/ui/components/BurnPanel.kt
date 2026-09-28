package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.GameSession
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import com.rm.apogee.platform.format

/** What the burn panel and chips can ask for. */
class BurnActions(
    /** The next burn changed by this much along prograde, normal and radial, in m/s. */
    val onNudge: (Double, Double, Double) -> Unit = { _, _, _ -> },
    /** The next burn moved this many seconds later (earlier if negative). */
    val onShift: (Double) -> Unit = {},
    /** A slider or nudge was let go, so send the burn as it is now. */
    val onEdited: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onWarpTo: () -> Unit = {},
    val onAutoBurn: (Boolean) -> Unit = {},
    val onAutoLand: (Boolean) -> Unit = {},
)

/**
 * Planned burns and landings, on the HUD. On the map it's the next burn's editor: prograde, normal
 * and radial by rate sliders and nudges, its time by nudges (and by dragging its marker along the
 * path on the map), what it does to the orbit, and delete, warp-to and the auto-burn. With none
 * planned, it says how to plan one. In flight it's a chip counting down to the burn and saying when
 * to burn and when to cut, and coming down, one saying when it hits and when to brake, with the
 * auto-land.
 */
@Composable
fun BurnPanel(
    burn: GameSession.BurnReadout?,
    landing: GameSession.LandingReadout?,
    mapMode: Boolean,
    note: String,
    actions: BurnActions,
    modifier: Modifier = Modifier,
    window: GameSession.WindowReadout? = null,
    align: Alignment.Horizontal = Alignment.End,
    /** On the map, whether the path shown is one to plan burns on, or a course over the ground. */
    plannable: Boolean = true,
) {
    Column(modifier, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mapMode) {
            window?.let { WindowChip(it) }
            when {
                burn != null -> BurnEditor(burn, actions)
                plannable -> Hint("Tap your path for a burn, or a world to target")
                else -> Hint("Your course for the next ten minutes, a dot a minute")
            }
        } else {
            burn?.let { BurnChip(it, actions) }
            landing?.takeIf { it.impactIn in 0.0..LANDING_SHOWN }?.let { LandingChip(it, actions) }
        }
        if (note.isNotEmpty() && note != "Landed") Hint(note)
    }
}

private const val LANDING_SHOWN = 600.0

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.alpha(ApogeeAlpha.BODY),
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun BurnEditor(burn: GameSession.BurnReadout, actions: BurnActions) {
    // Folded to its first line, out of the way of the map, with a tap on it.
    var open by remember { mutableStateOf(true) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = !open }) {
            Text("BURN", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = BURN_BLUE)
            Spacer(Modifier.width(8.dp))
            Text(
                "${countdown(burn.startsIn)} · Δv ${burn.burn.deltaV.roundToInt()} m/s · ${duration(burn.duration)}",
                style = TelemetryTextStyle, color = Color.White, maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(if (open) "▴" else "▾", style = MaterialTheme.typography.labelMedium, color = Color.White.alpha(ApogeeAlpha.SECONDARY))
        }
        if (!open) return@Card
        Axis("PRO", ApogeeColors.Prograde, burn.burn.prograde, { actions.onNudge(it, 0.0, 0.0) }, actions.onEdited)
        Axis("NRM", NORMAL_PURPLE, burn.burn.normal, { actions.onNudge(0.0, it, 0.0) }, actions.onEdited)
        Axis("RAD", RADIAL_CYAN, burn.burn.radial, { actions.onNudge(0.0, 0.0, it) }, actions.onEdited)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TIME", style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.SECONDARY))
            for (s in listOf(-60.0, -10.0, 10.0, 60.0)) {
                SmallButton(if (s < 0) "−${(-s).roundToInt()}s" else "+${s.roundToInt()}s") { actions.onShift(s); actions.onEdited() }
            }
        }
        Text(after(burn), style = TelemetryTextStyle, color = ApogeeColors.Data, maxLines = 1)
        burn.meets?.let { moon ->
            Text("$moon · passes at ${distance(burn.meetsAt)}", style = TelemetryTextStyle, color = MOON_GREY, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("DELETE", ApogeeColors.Caution) { actions.onDelete() }
            if (burn.startsIn > 60.0) SmallButton("WARP TO") { actions.onWarpTo() }
            if (burn.canAuto) SmallButton(if (burn.auto) "AUTO ✓" else "AUTO", if (burn.auto) BURN_BLUE else Color.White) { actions.onAutoBurn(!burn.auto) }
        }
    }
}

/**
 * One axis of the burn: a slider that springs back (further means faster), and a nudge each way.
 */
@Composable
private fun Axis(label: String, colour: Color, value: Double, onChange: (Double) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colour, modifier = Modifier.width(30.dp))
        SmallButton("−") { onChange(-1.0); onDone() }
        RateSlider(colour, onChange, onDone)
        SmallButton("+") { onChange(1.0); onDone() }
        Text(
            "${if (value >= 0) "+" else "−"}${abs(value).roundToInt()}",
            style = TelemetryTextStyle, color = colour, maxLines = 1,
            modifier = Modifier.width(52.dp),
        )
    }
}

/**
 * Held off the middle, it changes the burn, slowly near the middle and fast at the ends, and it
 * springs back to the middle when you let go.
 */
@Composable
private fun RateSlider(colour: Color, onChange: (Double) -> Unit, onDone: () -> Unit) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(held) {
        while (held) {
            val share = (offset / (widthPx / 2f)).coerceIn(-1f, 1f)
            // Squared, so it's fine near the middle and a few hundred m/s a second at the ends.
            val rate = share * abs(share) * MOST_RATE
            if (rate != 0f) change((rate * TICK_SECONDS).toDouble())
            delay((TICK_SECONDS * 1000).toLong())
        }
    }
    Box(
        Modifier
            .width(120.dp)
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.alpha(ApogeeAlpha.CONTROL_FILL))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { held = true; offset = 0f },
                    onDragEnd = { held = false; offset = 0f; onDone() },
                    onDragCancel = { held = false; offset = 0f; onDone() },
                ) { change, drag ->
                    change.consume()
                    offset = (offset + drag.x).coerceIn(-widthPx / 2f, widthPx / 2f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(Color.White.alpha(ApogeeAlpha.SECONDARY)))
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .size(20.dp)
                .clip(CircleShape)
                .background(colour.alpha(if (held) 1f else 0.7f)),
        )
    }
}

private const val MOST_RATE = 200f
private const val TICK_SECONDS = 0.05f

@Composable
private fun BurnChip(burn: GameSession.BurnReadout, actions: BurnActions) {
    Card {
        val now = burn.startsIn <= 0.0
        val cut = now && burn.left < 1.0
        val (verb, colour) = when {
            cut -> "CUT" to ApogeeColors.Caution
            now -> "BURN NOW" to ApogeeColors.Caution
            else -> "BURN IN ${countdown(burn.startsIn).removePrefix("T−")}" to BURN_BLUE
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(verb, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colour, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text("${burn.left.roundToInt()} m/s · ${duration(burn.duration)}", style = TelemetryTextStyle, color = Color.White, maxLines = 1)
        }
        // How much of it is done.
        val done = if (burn.burn.deltaV > 0.0) (1.0 - burn.left / burn.burn.deltaV).coerceIn(0.0, 1.0) else 0.0
        Box(Modifier.width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.alpha(ApogeeAlpha.CONTROL_FILL))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(done.toFloat()).background(colour))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (burn.startsIn > 60.0) SmallButton("WARP TO") { actions.onWarpTo() }
            if (burn.canAuto) SmallButton(if (burn.auto) "AUTO ✓" else "AUTO", if (burn.auto) BURN_BLUE else Color.White) { actions.onAutoBurn(!burn.auto) }
        }
    }
}

/** A planet targeted from another: when the window opens, and what it costs. */
@Composable
private fun WindowChip(window: GameSession.WindowReadout) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TO ${window.target.uppercase()}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = BURN_BLUE, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            val open = window.waitFor < WINDOW_OPEN
            Text(
                if (open) "WINDOW OPEN" else "WINDOW IN ${long(window.waitFor)}",
                style = TelemetryTextStyle, color = if (open) ApogeeColors.Caution else Color.White, maxLines = 1,
            )
        }
        Text(
            "PHASE ${window.phase.roundToInt()}° · NEED ${window.needed.roundToInt()}° · CROSSING ${long(window.flight)}",
            style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.BODY), maxLines = 1,
        )
        Text(
            "Δv ${window.departure.roundToInt()} m/s OUT · ${window.arrival.roundToInt()} m/s IN",
            style = TelemetryTextStyle, color = Color.White, maxLines = 1,
        )
    }
}

/** Days and hours, for waits and crossings of weeks. */
private fun long(seconds: Double): String {
    if (seconds.isNaN() || seconds.isInfinite()) return "—"
    val h = (seconds / 3600.0).roundToInt()
    return if (h >= 48) "${h / 24} d ${h % 24} h" else duration(seconds)
}

/** Within this many seconds of the window, it's open. A day either side is near enough. */
private const val WINDOW_OPEN = 86_400.0

@Composable
private fun LandingChip(landing: GameSession.LandingReadout, actions: BurnActions) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("IMPACT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ApogeeColors.Caution)
            Spacer(Modifier.width(8.dp))
            Text("${duration(landing.impactIn)} · ${landing.impactSpeed.roundToInt()} m/s", style = TelemetryTextStyle, color = Color.White, maxLines = 1)
        }
        // When to brake, for a pilot flying it, while there's still speed to lose.
        if (!landing.auto && !landing.brakeIn.isNaN() && landing.impactSpeed > 5.0) {
            val now = landing.brakeIn <= 0.0
            Text(
                if (now) "BRAKE NOW" else "BRAKE IN ${duration(landing.brakeIn)}",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (now) ApogeeColors.Caution else ApogeeColors.Data,
            )
        }
        if (landing.canAuto) SmallButton(if (landing.auto) "AUTO LAND ✓" else "AUTO LAND", if (landing.auto) BURN_BLUE else Color.White) { actions.onAutoLand(!landing.auto) }
    }
}

@Composable
private fun SmallButton(text: String, colour: Color = Color.White, onTap: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = colour,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(Color.White.alpha(ApogeeAlpha.CONTROL_FILL))
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

private fun after(burn: GameSession.BurnReadout): String = when {
    burn.periapsis.isNaN() -> "working it out…"
    burn.apoapsis.isInfinite() -> "ESCAPE · PE ${distance(burn.periapsis)}"
    else -> "AP ${distance(burn.apoapsis)} · PE ${if (burn.periapsis < 0) "under ground" else distance(burn.periapsis)}"
}

private fun countdown(seconds: Double): String = if (seconds < 0) "NOW" else "T−${duration(seconds)}"

private fun duration(seconds: Double): String {
    if (seconds.isNaN() || seconds.isInfinite()) return "—"
    val s = seconds.coerceAtLeast(0.0).roundToInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun distance(metres: Double): String = when {
    metres.isNaN() -> "—"
    abs(metres) >= 100_000 -> "%,d km".format((metres / 1000).roundToInt())
    abs(metres) >= 1_000 -> "%.1f km".format(metres / 1000)
    else -> "${metres.roundToInt()} m"
}

private val BURN_BLUE = Color(0xFF4FA3FF)
private val NORMAL_PURPLE = Color(0xFFD27CFF)
private val RADIAL_CYAN = Color(0xFF6FE3FF)
private val MOON_GREY = Color(0xFFD9D9E0)
