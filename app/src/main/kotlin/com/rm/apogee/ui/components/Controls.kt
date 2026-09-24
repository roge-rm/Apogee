package com.rm.apogee.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.alpha

/**
 * The signature menu control: a full-pill button carrying a translucent accent
 * wash rather than a solid fill, with an optional second line.
 */
@Composable
fun ApogeeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = { com.rm.apogee.audio.Sounds.click(); onClick() },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ApogeeColors.Accent.alpha(ApogeeAlpha.CONTROL_FILL),
            contentColor = Color.White,
            disabledContainerColor = Color.White.alpha(ApogeeAlpha.FILL_FAINT),
            disabledContentColor = Color.White.alpha(ApogeeAlpha.BORDER),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier.padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = ApogeeColors.Accent,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** An accent section heading with a hairline rule under it. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = ApogeeColors.Accent,
        )
        HorizontalDivider(
            color = Color.White.alpha(ApogeeAlpha.DIVIDER),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.fillMaxWidth(0.78f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ApogeeColors.Accent,
                checkedTrackColor = ApogeeColors.Accent.alpha(0.4f),
                uncheckedThumbColor = Color.White.alpha(ApogeeAlpha.SECONDARY),
                uncheckedTrackColor = Color.White.alpha(ApogeeAlpha.CONTROL_FILL),
            ),
        )
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = ApogeeColors.Accent)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = ApogeeColors.Accent,
                activeTrackColor = ApogeeColors.Accent.alpha(0.7f),
                inactiveTrackColor = Color.White.alpha(0.2f),
            ),
        )
    }
}

/**
 * One of several, each with a line saying what it does.
 *
 * Radio buttons rather than a dropdown: the options are few, and the
 * description under each is the point - "Aircraft" alone does not say which
 * way the stick will go.
 */
@Composable
fun <T> ChoiceGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = ApogeeColors.Accent,
                        unselectedColor = Color.White.alpha(ApogeeAlpha.SECONDARY),
                    ),
                )
                Column {
                    Text(label(option), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Text(
                        description(option),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                    )
                }
            }
        }
    }
}
