package com.rm.apogee.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rm.apogee.input.PadAction
import com.rm.apogee.input.PadBindings
import com.rm.apogee.input.PadButton
import com.rm.apogee.input.PadGroup
import com.rm.apogee.input.PadInput
import com.rm.apogee.input.PadLayer
import com.rm.apogee.input.PadStick
import com.rm.apogee.input.StickUse
import com.rm.apogee.settings.GameSettings
import com.rm.apogee.ui.components.ApogeeButton
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.components.SectionHeading
import com.rm.apogee.ui.components.SliderRow
import com.rm.apogee.ui.components.SwitchRow
import com.rm.apogee.ui.components.padFocus
import com.rm.apogee.ui.components.verticalScrollbar
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.alpha
import kotlin.math.roundToInt

/**
 * Controller bindings, flying and on foot, and stick feel. Pressing a button lights and scrolls
 * to its row. Menus always use D-pad or left stick, A and B, so remapping can't lock you out.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ControllerScreen(
    settings: GameSettings,
    pad: PadInput,
    controllerName: String?,
    onBack: () -> Unit = {},
) {
    var layer by remember { mutableStateOf(PadLayer.FLYING) }
    val bindings = remember(settings.padBindings) { PadBindings.parse(settings.padBindings) }
    var picking by remember { mutableStateOf<PadButton?>(null) }
    var pickingStick by remember { mutableStateOf<PadStick?>(null) }

    // The button just pressed, lit for a moment.
    var lit by remember { mutableStateOf<PadButton?>(null) }
    LaunchedEffect(pad.presses) {
        if (pad.presses == 0) return@LaunchedEffect
        lit = pad.lastPressed
        kotlinx.coroutines.delay(LIT_MS)
        lit = null
    }

    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth, title = "Controller", onBack = onBack) { contentModifier ->
        Column(contentModifier) {
            Text(
                controllerName?.let { "Connected: $it" } ?: "No controller found",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            )
            Spacer(Modifier.height(12.dp))
            LayerSwitch(layer) { layer = it }

            SectionHeading("Sticks")
            for (stick in PadStick.entries) {
                BindingRow(stick.label, bindings.stick(layer, stick).label, lit = false) { pickingStick = stick }
            }
            ButtonSection("Shoulders and triggers", listOf(PadButton.L1, PadButton.R1, PadButton.L2, PadButton.R2), layer, bindings, lit) { picking = it }
            ButtonSection("Face buttons", listOf(PadButton.A, PadButton.B, PadButton.X, PadButton.Y), layer, bindings, lit) { picking = it }
            ButtonSection("D-pad", listOf(PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT), layer, bindings, lit) { picking = it }
            ButtonSection("Stick clicks, Start and Select", listOf(PadButton.L3, PadButton.R3, PadButton.START, PadButton.SELECT), layer, bindings, lit) { picking = it }

            SectionHeading("Feel")
            SliderRow(
                title = "Stick dead zone",
                value = settings.padDeadZone,
                onValueChange = { settings.padDeadZone = it },
                valueLabel = "${(settings.padDeadZone * 100).roundToInt()}%",
                range = 0.05f..0.4f,
            )
            SliderRow(
                title = "Look speed",
                value = settings.padLookSpeed,
                onValueChange = { settings.padLookSpeed = it },
                valueLabel = "${(settings.padLookSpeed * 100).roundToInt()}%",
                range = 0.3f..2.5f,
            )
            SwitchRow(
                title = "Invert look up and down",
                checked = settings.padInvertLook,
                onCheckedChange = { settings.padInvertLook = it },
            )
            SwitchRow(
                title = "Hold A to stage",
                checked = settings.padHoldToStage,
                onCheckedChange = { settings.padHoldToStage = it },
            )
            SwitchRow(
                title = "Hide the touch stick with a controller",
                checked = settings.padHideTouch,
                onCheckedChange = { settings.padHideTouch = it },
            )

            Spacer(Modifier.height(12.dp))
            ApogeeButton(
                "Reset to Retroid Pocket Mini",
                { settings.padBindings = "" },
                enabled = bindings != PadBindings.RETROID_MINI,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    // Back closes an open list, not the page.
    com.rm.apogee.ui.BackHandler(enabled = picking != null || pickingStick != null) {
        picking = null
        pickingStick = null
    }
    picking?.let { button ->
        ActionPicker(
            title = "${button.label}, ${layer.label.lowercase()}",
            current = bindings.action(layer, button),
            layer = layer,
            focusFirst = pad.presses > 0,
            onPick = { action ->
                settings.padBindings = bindings.with(layer, button, action).format()
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
    pickingStick?.let { stick ->
        StickPicker(
            title = "${stick.label}, ${layer.label.lowercase()}",
            current = bindings.stick(layer, stick),
            onPick = { use ->
                settings.padBindings = bindings.with(layer, stick, use).format()
                pickingStick = null
            },
            onDismiss = { pickingStick = null },
        )
    }
}

/** Flying or on foot: which layer of buttons the page shows. */
@Composable
private fun LayerSwitch(layer: PadLayer, onLayer: (PadLayer) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (candidate in PadLayer.entries) {
            val on = candidate == layer
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) ApogeeColors.Accent else ApogeeColors.SurfaceRaised)
                    .padFocus(RoundedCornerShape(50), if (on) Color.White else ApogeeColors.Accent)
                    .clickable { onLayer(candidate) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    candidate.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) ApogeeColors.BackdropBottom else Color.White,
                )
            }
        }
    }
}

@Composable
private fun ButtonSection(
    title: String,
    buttons: List<PadButton>,
    layer: PadLayer,
    bindings: PadBindings,
    lit: PadButton?,
    onPick: (PadButton) -> Unit,
) {
    SectionHeading(title)
    for (button in buttons) {
        BindingRow(button.label, bindings.action(layer, button).label, lit = button == lit) { onPick(button) }
    }
}

/** One control and what it does. Lit when it's the button just pressed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BindingRow(control: String, does: String, lit: Boolean, onClick: () -> Unit) {
    val into = remember { BringIntoViewRequester() }
    LaunchedEffect(lit) { if (lit) into.bringIntoView() }
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(into)
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(if (lit) ApogeeColors.Accent.alpha(0.3f) else Color.Transparent)
            .padFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            control,
            style = MaterialTheme.typography.bodyLarge,
            color = if (lit) ApogeeColors.Accent else Color.White,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            does,
            style = MaterialTheme.typography.bodyMedium,
            color = if (does == PadAction.NONE.label) Color.White.alpha(ApogeeAlpha.BORDER) else ApogeeColors.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Every action a button can take, grouped, the current one lit. On foot, on-foot ones come first. */
@Composable
private fun ActionPicker(
    title: String,
    current: PadAction,
    layer: PadLayer,
    focusFirst: Boolean,
    onPick: (PadAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = if (layer == PadLayer.ON_FOOT) {
        listOf(PadGroup.ON_FOOT) + PadGroup.entries.filter { it != PadGroup.ON_FOOT }
    } else {
        PadGroup.entries
    }
    // Headings and actions, as one list.
    val rows: List<Any> = groups.flatMap { group -> listOf<Any>(group) + PadAction.entries.filter { it.group == group } }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val at = rows.indexOf(current)
    LaunchedEffect(Unit) {
        if (at > 0) list.scrollToItem((at - 1).coerceAtLeast(0))
        if (focusFirst) runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = PICKER_HEIGHT).verticalScrollbar(list), state = list) {
                items(rows.size) { i ->
                    when (val row = rows[i]) {
                        is PadGroup -> Text(
                            row.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = ApogeeColors.Accent,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                        is PadAction -> ChoiceRow(
                            row.label, row == current,
                            if (row == current) Modifier.focusRequester(focus) else Modifier,
                        ) { onPick(row) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StickPicker(title: String, current: StickUse, onPick: (StickUse) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for (use in StickUse.entries) ChoiceRow(use.label, use == current) { onPick(use) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(label: String, chosen: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (chosen) ApogeeColors.Accent else Color.White,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CornerTight))
            .background(if (chosen) ApogeeColors.Accent.alpha(0.18f) else Color.Transparent)
            .padFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

private const val LIT_MS = 1_500L
private val PICKER_HEIGHT = 360.dp
