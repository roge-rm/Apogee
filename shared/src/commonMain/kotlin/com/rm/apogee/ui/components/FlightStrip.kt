package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.apogee.game.FlightTelemetry
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.abs
import kotlin.math.roundToInt
import com.rm.apogee.platform.format

/** One number on the flight strip: what it is, what it reads, and in what colour. */
data class StripField(val label: String, val value: String, val colour: Color = ApogeeColors.Data)

/**
 * The three or four numbers that matter for what the craft's doing now. The rest is a tap away.
 *
 * - On the ground: speed, heading, and the moon's window.
 * - Low or in the air: height (above ground under 2 km), climb, airspeed, and a strong wind.
 * - Out of the air: apoapsis, periapsis, time to apoapsis, speed.
 * - Under the sea: depth, height off the floor, climb, and speed in tenths.
 * - On the water or driving: speed, wind and heading. Height and climb mean nothing there.
 *
 * A target takes the last place with its distance, and so do lift and a current. A dangerous load
 * of air always shows. Under sail the wind always shows, since a sail goes by it.
 */
fun stripFields(
    t: FlightTelemetry,
    /** The current it's floating in, in m/s and the compass bearing it runs toward, or null. */
    current: Pair<Float, Float>? = null,
    /** Whether the craft has a sail, so the wind always shows. */
    sailing: Boolean = false,
    /** Its gas cells' lift as a share of its weight, or below 0 with none. */
    lift: Float = -1f,
    /** Whether it's riding on the water or driving on the ground, so it reads like a boat or a car. */
    onSurface: Boolean = false,
): List<StripField> {
    if (t.destroyed != null) return emptyList()
    val out = ArrayList<StripField>(5)
    val onGround = t.heightAboveGround < GROUND_HEIGHT && t.surfaceSpeed < GROUND_SPEED
    val low = !onGround && (t.inAir || t.heightAboveGround < LOW_HEIGHT) && !t.inOrbit
    when {
        onSurface && !t.inOrbit -> {
            out += StripField("SRF", if (t.surfaceSpeed < 10.0) "%.1f".format(t.surfaceSpeed) else "${t.surfaceSpeed.roundToInt()}")
            if (t.inAir) out += StripField("WIND", windReading(t), if (t.windSpeed > STRONG_WIND) ApogeeColors.Caution else ApogeeColors.Data)
            out += StripField("HDG", "%03d\u00b0".format(t.heading.roundToInt() % 360))
        }
        t.depth > UNDER_DEPTH -> {
            out += StripField("DEPTH", formatDistance(t.depth), ApogeeColors.Data)
            if (t.belowFloor.isFinite()) {
                out += StripField("BELOW", formatDistance(t.belowFloor.coerceAtLeast(0.0)), if (t.belowFloor < FLOOR_NEAR) ApogeeColors.Caution else ApogeeColors.Data)
            }
            out += StripField("VS", (if (t.verticalSpeed >= 0) "+" else "\u2212") + "%.1f".format(abs(t.verticalSpeed)))
            out += StripField("SRF", "%.1f".format(t.surfaceSpeed))
        }
        onGround -> {
            out += StripField("SRF", "${t.surfaceSpeed.roundToInt()} m/s")
            out += StripField("HDG", "%03d\u00b0".format(t.heading.roundToInt() % 360))
            if (t.lunaWindow.isFinite() && t.moonName.isNotEmpty()) {
                val open = t.lunaWindow <= com.rm.apogee.game.GameSession.MOON_WINDOW_OPEN
                out += StripField(t.moonName.uppercase(), if (open) "go east" else formatDuration(t.lunaWindow), if (open) ApogeeColors.Prograde else ApogeeColors.Data)
            }
        }
        low -> {
            if (t.heightAboveGround < AGL_BELOW) {
                out += StripField("AGL", formatDistance(t.heightAboveGround), if (t.heightAboveGround < 200.0) ApogeeColors.Caution else ApogeeColors.Data)
            } else {
                out += StripField("ALT", formatDistance(t.altitude))
            }
            out += StripField(
                "VS",
                (if (t.verticalSpeed >= 0) "+" else "\u2212") + "${abs(t.verticalSpeed).roundToInt()}",
                if (t.verticalSpeed < -10.0 && t.heightAboveGround < 500.0) ApogeeColors.Caution else ApogeeColors.Data,
            )
            out += if (t.inAir) StripField("AIR", "${t.airspeed.roundToInt()}") else StripField("SRF", "${t.surfaceSpeed.roundToInt()}")
            if (t.inAir && t.windSpeed > STRONG_WIND) out += StripField("WIND", "${t.windSpeed.roundToInt()}", ApogeeColors.Caution)
        }
        else -> {
            out += StripField("AP", formatDistance(t.apoapsisAltitude), if (t.inOrbit) ApogeeColors.Prograde else ApogeeColors.Data)
            out += StripField(
                "PE",
                if (t.periapsisAltitude < 0) "suborbital" else formatDistance(t.periapsisAltitude),
                if (t.periapsisAltitude < 0) ApogeeColors.Caution else ApogeeColors.Prograde,
            )
            if (t.timeToApoapsis.isFinite() && t.apoapsisAltitude > 1_000) out += StripField("T-AP", formatDuration(t.timeToApoapsis))
            out += StripField("ORB", "${t.orbitalSpeed.roundToInt()}")
        }
    }
    if (sailing && !t.inOrbit && out.none { it.label == "WIND" }) {
        // Replaces the heading if there's no room.
        if (out.size >= MAX_FIELDS) out.removeAt(out.indexOfFirst { it.label == "HDG" }.takeIf { it >= 0 } ?: out.lastIndex)
        out.add(1.coerceAtMost(out.size), StripField("WIND", windReading(t), if (t.windSpeed > STRONG_WIND) ApogeeColors.Caution else ApogeeColors.Data))
    }
    // How much of its weight the gas cells hold up. Over 100% it rises.
    if (lift >= 0f && !t.inOrbit && t.depth <= UNDER_DEPTH) {
        if (out.size >= MAX_FIELDS) out.removeAt(out.lastIndex)
        out += StripField("LIFT", "${(lift * 100).roundToInt()}%", if (lift < 0.9f) ApogeeColors.Caution else ApogeeColors.Data)
    }
    if (current != null && t.inOrbit.not()) {
        if (out.size >= MAX_FIELDS) out.removeAt(out.lastIndex)
        out += StripField("CURRENT", "%.1f %s".format(current.first, compassPoint(current.second)), CURRENT_COLOUR)
    }
    if (t.targetName != null) {
        if (out.size >= MAX_FIELDS) out.removeAt(out.lastIndex)
        out += StripField("DST", formatDistance(t.targetDistance), TARGET_COLOUR)
    }
    if (t.highDynamicPressure) out.add(0, StripField("Q", "${"%.1f".format(t.dynamicPressure / 1000)} kPa", ApogeeColors.Danger))
    return out
}

/** One slim line of [stripFields] along the top edge. A tap opens the full panel under it. */
@Composable
fun FlightStrip(
    telemetry: FlightTelemetry,
    open: Boolean,
    onToggle: () -> Unit,
    twoColumns: Boolean,
    power: com.rm.apogee.game.HudState.PowerReadout?,
    modifier: Modifier = Modifier,
    /** The current it's floating in, in m/s and the bearing it runs toward, or null. */
    current: Pair<Float, Float>? = null,
    /** Whether the craft has a sail, so the wind always shows. */
    sailing: Boolean = false,
    /** Its gas cells' lift as a share of its weight, or below 0 with none. */
    lift: Float = -1f,
    /** Whether it's riding on the water or driving on the ground, so it reads like a boat or a car. */
    onSurface: Boolean = false,
    /** Numbers per line. */
    perLine: Int = MAX_FIELDS + 1,
) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(
            Modifier
                .clip(RoundedCornerShape(Dimens.CornerSmall))
                .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
                .clickable(onClick = onToggle)
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.End) {
                for (line in stripFields(telemetry, current, sailing, lift, onSurface).chunked(perLine.coerceAtLeast(1))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (field in line) Cell(field)
                    }
                }
            }
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Fewer readouts" else "All readouts",
                tint = Color.White.alpha(ApogeeAlpha.SECONDARY),
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
        }
        if (open) {
            Spacer(Modifier.height(4.dp))
            TelemetryPanel(telemetry, twoColumns = twoColumns, power = power, onSurface = onSurface)
        }
    }
}

/** One number, with its name small above it to keep the line narrow. */
@Composable
private fun Cell(field: StripField) {
    Column(horizontalAlignment = Alignment.End) {
        Text(field.label, style = LabelText, color = Color.White.alpha(ApogeeAlpha.SUBTITLE), maxLines = 1)
        Text(field.value, style = TelemetryTextStyle, color = field.colour, maxLines = 1)
    }
}

private val LabelText = TelemetryTextStyle.copy(fontSize = 9.sp, lineHeight = 10.sp)

private const val MAX_FIELDS = 4

/** Under this height and speed the craft is on the ground, in m and m/s. */
private const val GROUND_HEIGHT = 50.0
private const val GROUND_SPEED = 5.0

/** Below this it's low flying, whatever the air, in metres. */
private const val LOW_HEIGHT = 20_000.0

/** Under this, it's height above the ground instead of above the datum, in metres. */
private const val AGL_BELOW = 2_000.0

/** Wind worth a place on the strip, in m/s. */
private const val STRONG_WIND = 15.0

/** Deeper than this the craft is under the sea, not riding on it, in metres. */
private const val UNDER_DEPTH = 1.5

/** Closer than this to the sea floor is worth a warning colour, in metres. */
private const val FLOOR_NEAR = 10.0

internal val TARGET_COLOUR = Color(0xFFFF5FD2)

@Composable
internal fun TelemetryPanel(
    telemetry: FlightTelemetry,
    modifier: Modifier = Modifier,
    twoColumns: Boolean = false,
    power: com.rm.apogee.game.HudState.PowerReadout? = null,
    onSurface: Boolean = false,
) {
    // Two columns in landscape, so it doesn't run down over the roll and SAS buttons.
    val panel = modifier
        .clip(RoundedCornerShape(Dimens.CornerSmall))
        .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
        .padding(horizontal = 12.dp, vertical = 8.dp)
    if (twoColumns) {
        Row(panel, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(horizontalAlignment = Alignment.End) { SurfaceReadouts(telemetry, onSurface) }
            Column(horizontalAlignment = Alignment.End) { OrbitReadouts(telemetry, onSurface); PowerReadout(power) }
        }
    } else {
        Column(panel, horizontalAlignment = Alignment.End) {
            SurfaceReadouts(telemetry, onSurface)
            OrbitReadouts(telemetry, onSurface)
            PowerReadout(power)
        }
    }
}

/** Charge, what it holds, and whether it's filling or draining. Only on a craft with a battery. */
@Composable
private fun PowerReadout(power: com.rm.apogee.game.HudState.PowerReadout?) {
    if (power == null) return
    // What the ground below holds, from a scanner low enough.
    if (power.ore >= 0f) {
        Spacer(Modifier.height(4.dp))
        Readout("GROUND", "ORE ${(power.ore * 100).roundToInt()}%  H2O ${(power.water * 100).roundToInt()}%")
    }
    // What it's dug up.
    power.held?.let { h ->
        if (h[1] > 0f) Readout("ORE", "${h[0].roundToInt()}/${h[1].roundToInt()}")
        if (h[3] > 0f) Readout("WATER", "${h[2].roundToInt()}/${h[3].roundToInt()}")
    }
    if (power.survey >= 0f) {
        Readout(
            "SCAN",
            if (power.survey >= 1f) "SURVEYED" else "${(power.survey * 100).roundToInt()}%",
            colour = if (power.survey >= 1f) ApogeeColors.Prograde else ApogeeColors.Data,
        )
    }
    if (power.capacity <= 0f) return
    val rate = power.net
    val sign = if (rate >= 0f) "+" else "\u2212"
    Spacer(Modifier.height(4.dp))
    Readout(
        "PWR",
        "${power.charge.roundToInt()}/${power.capacity.roundToInt()} $sign${"%.2f".format(kotlin.math.abs(rate))}/s",
        colour = when {
            !power.powered -> ApogeeColors.Danger
            power.low -> ApogeeColors.Caution
            else -> ApogeeColors.Data
        },
    )
}

/**
 * Height, speed, climb, heading and the air. With [onSurface], just speed, heading and the wind
 * with where it comes from.
 */
@Composable
private fun SurfaceReadouts(telemetry: FlightTelemetry, onSurface: Boolean = false) {
    if (!onSurface) Readout("ALT", formatDistance(telemetry.altitude))
    // Above the ground. The launch site is most of a kilometre above the datum, so ALT alone
    // won't tell you when you're about to land.
    if (!onSurface && telemetry.heightAboveGround < 20_000.0) {
        Readout(
            "AGL",
            formatDistance(telemetry.heightAboveGround),
            colour = if (telemetry.heightAboveGround < 200.0) ApogeeColors.Caution
            else ApogeeColors.Data,
        )
    }
    Readout("SRF", if (onSurface && telemetry.surfaceSpeed < 10.0) "${telemetry.surfaceSpeed.format(1)} m/s" else "${telemetry.surfaceSpeed.roundToInt()} m/s")
    if (!onSurface) Readout(
        "VS",
        (if (telemetry.verticalSpeed >= 0) "+" else "\u2212") + "${kotlin.math.abs(telemetry.verticalSpeed).roundToInt()} m/s",
        colour = if (telemetry.verticalSpeed < -10.0 && telemetry.heightAboveGround < 500.0) ApogeeColors.Caution else ApogeeColors.Data,
    )
    Readout("HDG", "%03d\u00b0".format(telemetry.heading.roundToInt() % 360))
    // Only where there's air.
    if (telemetry.inAir) {
        if (!onSurface) Readout("AIR", "${telemetry.airspeed.roundToInt()} m/s")
        val windColour = if (telemetry.windSpeed > 15.0) ApogeeColors.Caution else ApogeeColors.Data
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("WIND", style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            Spacer(Modifier.width(10.dp))
            // The way it blows on screen. Turns smoothly, so it lines up with the windsock.
            if (telemetry.windSpeed >= 0.5) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    tint = windColour,
                    modifier = Modifier.size(14.dp).rotate((telemetry.windFrom + 180.0).toFloat()),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text("${telemetry.windSpeed.roundToInt()} m/s", style = TelemetryTextStyle, color = windColour)
            if (onSurface && telemetry.windSpeed >= 0.5) {
                Text(
                    " from ${compassPoint(telemetry.windBearing.toFloat())}",
                    style = TelemetryTextStyle,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                )
            }
        }
    }
}

/** The orbit, the moon's window, the target and the air load. With [onSurface], only the target. */
@Composable
private fun OrbitReadouts(telemetry: FlightTelemetry, onSurface: Boolean = false) {
    if (!onSurface) OrbitNumbers(telemetry)
    telemetry.targetName?.let { name ->
        Spacer(Modifier.height(4.dp))
        Readout("TGT", name.take(12), colour = TARGET_COLOUR)
        Readout("DST", formatDistance(telemetry.targetDistance), colour = TARGET_COLOUR)
        Readout(
            "CLS",
            (if (telemetry.closingSpeed >= 0) "" else "\u2212") + "${kotlin.math.abs(telemetry.closingSpeed).format(1)} m/s",
            colour = TARGET_COLOUR,
        )
    }
    if (telemetry.dynamicPressure > 100.0 && (!onSurface || telemetry.highDynamicPressure)) {
        Spacer(Modifier.height(4.dp))
        Readout(
            "Q",
            "${(telemetry.dynamicPressure / 1000).format(1)} kPa",
            colour = if (telemetry.highDynamicPressure) ApogeeColors.Danger
            else ApogeeColors.Data,
        )
    }
}

@Composable
private fun OrbitNumbers(telemetry: FlightTelemetry) {
    Readout("ORB", "${telemetry.orbitalSpeed.roundToInt()} m/s")
    Spacer(Modifier.height(4.dp))
    Readout(
        "AP",
        formatDistance(telemetry.apoapsisAltitude),
        colour = if (telemetry.inOrbit) ApogeeColors.Prograde else ApogeeColors.Data,
    )
    Readout(
        "PE",
        // A periapsis underground means the path ends in the ground.
        if (telemetry.periapsisAltitude < 0) "suborbital"
        else formatDistance(telemetry.periapsisAltitude),
        colour = if (telemetry.periapsisAltitude < 0) ApogeeColors.Caution
        else ApogeeColors.Prograde,
    )
    if (telemetry.timeToApoapsis.isFinite() && telemetry.apoapsisAltitude > 1_000) {
        Readout("T-AP", formatDuration(telemetry.timeToApoapsis))
    }
    // On the pad: when a launch due east goes straight into the moon's plane.
    if (telemetry.lunaWindow.isFinite() && telemetry.heightAboveGround < 50.0 && telemetry.surfaceSpeed < 5.0) {
        Spacer(Modifier.height(4.dp))
        val open = telemetry.lunaWindow <= com.rm.apogee.game.GameSession.MOON_WINDOW_OPEN
        Readout(
            telemetry.moonName.uppercase(),
            if (open) "go east" else formatDuration(telemetry.lunaWindow),
            colour = if (open) ApogeeColors.Prograde else ApogeeColors.Data,
        )
    }
}

@Composable
private fun Readout(label: String, value: String, colour: Color = ApogeeColors.Data) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = TelemetryTextStyle,
            color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
        )
        Spacer(Modifier.width(10.dp))
        Text(value, style = TelemetryTextStyle, color = colour)
    }
}

/** The wind's speed and an arrow for the way it blows on screen (up is into the view). */
internal fun windReading(t: FlightTelemetry): String {
    val speed = "${t.windSpeed.roundToInt()}"
    if (t.windSpeed < 0.5) return speed
    val arrows = arrayOf("\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196")
    val toward = ((t.windFrom + 180.0) % 360.0 + 360.0) % 360.0
    return "$speed ${arrows[((toward + 22.5) / 45.0).toInt() % 8]}"
}

/** The nearest of the eight compass points to [bearing] degrees. */
internal fun compassPoint(bearing: Float): String {
    val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return points[(((bearing % 360f + 360f) % 360f + 22.5f) / 45f).toInt() % 8]
}

/** The current's field, the sea's blue-green. */
private val CURRENT_COLOUR = Color(0xFF59E0D0)

/** Metres below a kilometre, kilometres above, and megametres past a thousand of those. */
internal fun formatDistance(metres: Double): String {
    val magnitude = abs(metres)
    return when {
        magnitude >= 1_000_000 -> "%.1f Mm".format(metres / 1_000_000)
        magnitude >= 1_000 -> "%.2f km".format(metres / 1_000)
        else -> "%d m".format(metres.roundToInt())
    }
}

/** Seconds as m:ss, or h:mm:ss. */
internal fun formatDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "--"
    val total = seconds.roundToInt()
    return when {
        total >= 3_600 -> "%d:%02d:%02d".format(total / 3_600, total / 60 % 60, total % 60)
        total >= 60 -> "%d:%02d".format(total / 60, total % 60)
        else -> "${total}s"
    }
}

private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
