package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.rm.apogee.core.world.ServerMessage
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

/**
 * A base, on the HUD: the one being flown, or the nearest founded one, with its power and its
 * stores. It also shows what the flown craft can do with it (be filled from it, empty its ore and
 * water into it, be founded where it stands, or let go) and switches its refinery on or off.
 */
@Composable
fun BasePanel(
    base: ServerMessage.BaseStatus?,
    service: ServerMessage.Service?,
    onFound: (Boolean) -> Unit,
    onRefuel: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Empty the flown craft's ore and water into the base, or stop. */
    onUnload: (Boolean) -> Unit = {},
    /** Switch base [base]'s refinery on or off. */
    onRefine: (base: ServerMessage.BaseStatus, on: Boolean) -> Unit = { _, _ -> },
) {
    val canDo = service != null && (service.canFound || service.founded || service.canRefuel || service.refuelling || service.canUnload || service.unloading)
    if (base == null && !canDo) return
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (base != null) {
            BaseCard(base)
            // The refinery runs while nobody's there, and it gets switched here, from nearby.
            if (base.hasRefinery) {
                if (base.refining) Chip("REFINING", "STOP", ApogeeColors.Prograde) { onRefine(base, false) }
                else Chip("REFINE", "ORE · WATER", ApogeeColors.Accent) { onRefine(base, true) }
            }
        }
        if (service != null) {
            if (service.refuelling) {
                Chip("STOP", "FILLING", ApogeeColors.Caution) { onRefuel(false) }
            } else if (service.canRefuel) {
                Chip("REFUEL", service.stopped.uppercase(), ApogeeColors.Prograde) { onRefuel(true) }
            }
            if (service.unloading) {
                Chip("STOP", "UNLOADING", ApogeeColors.Caution) { onUnload(false) }
            } else if (service.canUnload) {
                Chip("UNLOAD", "ORE · WATER", ApogeeColors.Prograde) { onUnload(true) }
            }
            if (service.canFound) Chip("FOUND BASE", "", ApogeeColors.Accent) { onFound(true) }
            if (service.founded) ArmedChip("LET GO", "UNFOUND") { onFound(false) }
        }
    }
}

@Composable
private fun BaseCard(base: ServerMessage.BaseStatus) {
    val colour = if (base.powered) ApogeeColors.Data else ApogeeColors.Caution
    Column(
        Modifier
            .clip(RoundedCornerShape(Dimens.CornerTight))
            // Dark, like the telemetry, so it reads against both sky and ground.
            .background(Color.Black.alpha(ApogeeAlpha.SCRIM))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BASE", style = MaterialTheme.typography.labelSmall, color = colour, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            val where = if (base.distance >= 1f) " · ${base.distance.roundToInt()} m" else ""
            Text(base.name + where, style = MaterialTheme.typography.labelSmall, color = Color.White.alpha(ApogeeAlpha.BODY), maxLines = 1)
        }
        val power = if (!base.powered) "DARK" else
            "${base.charge.roundToInt()}/${base.chargeCapacity.roundToInt()} " +
                (if (base.net >= 0f) "+" else "−") + "%.1f/s".format(kotlin.math.abs(base.net))
        Line("POWER", power, colour)
        if (base.propellantCapacity > 0f) Line("PROP", "${base.propellant.roundToInt()}/${base.propellantCapacity.roundToInt()}", colour)
        if (base.monopropellantCapacity > 0f) Line("MONO", "${base.monopropellant.roundToInt()}/${base.monopropellantCapacity.roundToInt()}", colour)
        if (base.oreCapacity > 0f) Line("ORE", "${base.ore.roundToInt()}/${base.oreCapacity.roundToInt()}", colour)
        if (base.waterCapacity > 0f) Line("WATER", "${base.water.roundToInt()}/${base.waterCapacity.roundToInt()}", colour)
        if (base.pads > 0) Line("PADS", "${base.pads}", colour)
    }
}

@Composable
private fun Line(label: String, value: String, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colour.alpha(0.8f), maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(value, style = TelemetryTextStyle, color = colour, maxLines = 1)
    }
}

/** An action, solid like JOIN, so it reads against a bright sky as well as the ground. */
@Composable
private fun Chip(verb: String, note: String, colour: Color, onTap: () -> Unit) {
    val ink = Color(0xFF0C1824)
    Row(
        Modifier
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(colour.alpha(0.88f))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(verb, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
        if (note.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(note, style = MaterialTheme.typography.labelSmall, color = ink.alpha(0.75f), maxLines = 1)
        }
    }
}

/** A chip that asks first. A second tap within three seconds does it. */
@Composable
private fun ArmedChip(verb: String, note: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            kotlinx.coroutines.delay(3_000)
            armed = false
        }
    }
    val colour = if (armed) ApogeeColors.Caution else Color.White.alpha(ApogeeAlpha.SECONDARY)
    Row(
        Modifier
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(if (armed) ApogeeColors.Caution.alpha(0.22f) else Color.White.alpha(ApogeeAlpha.CONTROL_FILL))
            .clickable { if (armed) { armed = false; onConfirm() } else armed = true }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (armed) "TAP AGAIN" else verb, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colour, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(note, style = MaterialTheme.typography.labelSmall, color = colour, maxLines = 1)
    }
}
