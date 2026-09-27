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

/** One number on the flight strip: what it is, what it reads, and in what colour. */
data class StripField(val label: String, val value: String, val colour: Color = ApogeeColors.Data)

/**
 * The three or four numbers that matter for what the craft is doing now -
 * the rest of the panel is a tap away. Sitting on the ground: how fast it
 * rolls, which way it faces, and the moon's window when there is one.
 * Low, or in the air: how high (above the ground, under two kilometres),
 * climbing or sinking, how fast through the air, and a strong wind. Out
 * of the air: the orbit's high and low points, the time to the high one,
 * and the speed. A target, when there is one, takes the last place: how
 * far. A dangerous load of air always shows. Under the sea: how deep,
 * how far above its floor, climbing or sinking, and how fast - slow
 * enough there to want the tenths.
 */
fun stripFields(t: FlightTelemetry): List<StripField> {
    if (t.destroyed != null) return emptyList()
    val out = ArrayList<StripField>(5)
    val onGround = t.heightAboveGround < GROUND_HEIGHT && t.surfaceSpeed < GROUND_SPEED
    val low = !onGround && (t.inAir || t.heightAboveGround < LOW_HEIGHT) && !t.inOrbit
    when {
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
    if (t.targetName != null) {
        if (out.size >= MAX_FIELDS) out.removeAt(out.lastIndex)
        out += StripField("DST", formatDistance(t.targetDistance), TARGET_COLOUR)
    }
    if (t.highDynamicPressure) out.add(0, StripField("Q", "${"%.1f".format(t.dynamicPressure / 1000)} kPa", ApogeeColors.Danger))
    return out
}

/**
 * The flight strip: one slim line of [stripFields] along the top edge,
 * tapped to open the whole panel under it, and again to fold it away.
 */
@Composable
fun FlightStrip(
    telemetry: FlightTelemetry,
    open: Boolean,
    onToggle: () -> Unit,
    twoColumns: Boolean,
    power: com.rm.apogee.game.HudState.PowerReadout?,
    modifier: Modifier = Modifier,
    /** Numbers to a line: two in portrait, beside the top-left buttons; all of them in landscape. */
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
                for (line in stripFields(telemetry).chunked(perLine.coerceAtLeast(1))) {
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
            TelemetryPanel(telemetry, twoColumns = twoColumns, power = power)
        }
    }
}

/** One number: its name small above it, so a line of them stays narrow. */
@Composable
private fun Cell(field: StripField) {
    Column(horizontalAlignment = Alignment.End) {
        Text(field.label, style = LabelText, color = Color.White.alpha(ApogeeAlpha.SUBTITLE), maxLines = 1)
        Text(field.value, style = TelemetryTextStyle, color = field.colour, maxLines = 1)
    }
}

private val LabelText = TelemetryTextStyle.copy(fontSize = 9.sp, lineHeight = 10.sp)

private const val MAX_FIELDS = 4

/** Under this height and speed the craft is on the ground, m and m/s. */
private const val GROUND_HEIGHT = 50.0
private const val GROUND_SPEED = 5.0

/** Below this it is low flying, whatever the air, m. */
private const val LOW_HEIGHT = 20_000.0

/** Under this, height above the ground rather than above the datum, m. */
private const val AGL_BELOW = 2_000.0

/** Wind worth a place on the strip, m/s. */
private const val STRONG_WIND = 15.0

/** Deeper than this the craft is under the sea, not riding on it, m. */
private const val UNDER_DEPTH = 1.5

/** Closer than this to the sea's floor is worth a warning colour, m. */
private const val FLOOR_NEAR = 10.0

internal val TARGET_COLOUR = Color(0xFFFF5FD2)

@Composable
internal fun TelemetryPanel(
    telemetry: FlightTelemetry,
    modifier: Modifier = Modifier,
    twoColumns: Boolean = false,
    power: com.rm.apogee.game.HudState.PowerReadout? = null,
) {
    // Two columns in landscape - near the ground, then the orbit and target -
    // where one tall column ran down over the roll and SAS buttons.
    val panel = modifier
        .clip(RoundedCornerShape(Dimens.CornerSmall))
        .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
        .padding(horizontal = 12.dp, vertical = 8.dp)
    if (twoColumns) {
        Row(panel, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(horizontalAlignment = Alignment.End) { SurfaceReadouts(telemetry) }
            Column(horizontalAlignment = Alignment.End) { OrbitReadouts(telemetry); PowerReadout(power) }
        }
    } else {
        Column(panel, horizontalAlignment = Alignment.End) {
            SurfaceReadouts(telemetry)
            OrbitReadouts(telemetry)
            PowerReadout(power)
        }
    }
}

/** Charge and what it holds, and whether it is filling or draining: only on a craft with a battery. */
@Composable
private fun PowerReadout(power: com.rm.apogee.game.HudState.PowerReadout?) {
    if (power == null) return
    // What the ground below holds, by a scanner low enough.
    if (power.ore >= 0f) {
        Spacer(Modifier.height(4.dp))
        Readout("GROUND", "ORE ${(power.ore * 100).roundToInt()}%  H2O ${(power.water * 100).roundToInt()}%")
    }
    // What it has dug up, on a craft that can hold any.
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

@Composable
private fun SurfaceReadouts(telemetry: FlightTelemetry) {
    Readout("ALT", formatDistance(telemetry.altitude))
    // Above the ground, not above the datum. The launch complex sits most
    // of a kilometre up, so the two disagree from the moment you spawn,
    // and only one of them tells you whether you are about to land.
    if (telemetry.heightAboveGround < 20_000.0) {
        Readout(
            "AGL",
            formatDistance(telemetry.heightAboveGround),
            colour = if (telemetry.heightAboveGround < 200.0) ApogeeColors.Caution
            else ApogeeColors.Data,
        )
    }
    Readout("SRF", "${telemetry.surfaceSpeed.roundToInt()} m/s")
    // Climb or sink, and which way the nose points on the compass.
    Readout(
        "VS",
        (if (telemetry.verticalSpeed >= 0) "+" else "\u2212") + "${kotlin.math.abs(telemetry.verticalSpeed).roundToInt()} m/s",
        colour = if (telemetry.verticalSpeed < -10.0 && telemetry.heightAboveGround < 500.0) ApogeeColors.Caution else ApogeeColors.Data,
    )
    Readout("HDG", "%03d\u00b0".format(telemetry.heading.roundToInt() % 360))
    // Through the air, and the air itself - only where there is some.
    if (telemetry.inAir) {
        Readout("AIR", "${telemetry.airspeed.roundToInt()} m/s")
        val windColour = if (telemetry.windSpeed > 15.0) ApogeeColors.Caution else ApogeeColors.Data
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("WIND", style = TelemetryTextStyle, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
            Spacer(Modifier.width(10.dp))
            // The way it blows, as seen on screen: turned smoothly, not
            // snapped to eight points, so it lines up with the windsock.
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
        }
    }
}

@Composable
private fun OrbitReadouts(telemetry: FlightTelemetry) {
    Readout("ORB", "${telemetry.orbitalSpeed.roundToInt()} m/s")
    Spacer(Modifier.height(4.dp))
    Readout(
        "AP",
        formatDistance(telemetry.apoapsisAltitude),
        colour = if (telemetry.inOrbit) ApogeeColors.Prograde else ApogeeColors.Data,
    )
    Readout(
        "PE",
        // A periapsis underground is not a number, it is a warning: it
        // means the current trajectory ends in the ground.
        if (telemetry.periapsisAltitude < 0) "suborbital"
        else formatDistance(telemetry.periapsisAltitude),
        colour = if (telemetry.periapsisAltitude < 0) ApogeeColors.Caution
        else ApogeeColors.Prograde,
    )
    if (telemetry.timeToApoapsis.isFinite() && telemetry.apoapsisAltitude > 1_000) {
        Readout("T-AP", formatDuration(telemetry.timeToApoapsis))
    }
    // Waiting on the pad: when to go for the moon - a due-east launch then
    // flies straight into its plane.
    if (telemetry.lunaWindow.isFinite() && telemetry.heightAboveGround < 50.0 && telemetry.surfaceSpeed < 5.0) {
        Spacer(Modifier.height(4.dp))
        val open = telemetry.lunaWindow <= com.rm.apogee.game.GameSession.MOON_WINDOW_OPEN
        Readout(
            telemetry.moonName.uppercase(),
            if (open) "go east" else formatDuration(telemetry.lunaWindow),
            colour = if (open) ApogeeColors.Prograde else ApogeeColors.Data,
        )
    }
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
    if (telemetry.dynamicPressure > 100.0) {
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

/** Metres below a kilometre, kilometres above it. */
internal fun formatDistance(metres: Double): String {
    val magnitude = abs(metres)
    return when {
        magnitude >= 1_000_000 -> "%.1f Mm".format(metres / 1_000_000)
        magnitude >= 1_000 -> "%.2f km".format(metres / 1_000)
        else -> "%d m".format(metres.roundToInt())
    }
}

/** Seconds as m:ss, which is how a burn countdown is actually read. */
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
