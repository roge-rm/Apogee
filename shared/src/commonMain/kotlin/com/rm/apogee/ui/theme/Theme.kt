package com.rm.apogee.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The one dark scheme. [isSystemInDarkTheme] is ignored, so light mode can't give a white HUD.
 * Without a scheme, `MaterialTheme.colorScheme.*` falls back to Material's baseline light palette.
 */
private val ApogeeColorScheme = darkColorScheme(
    primary = ApogeeColors.Accent,
    onPrimary = Color(0xFF1A1030),
    primaryContainer = ApogeeColors.AccentDim,
    onPrimaryContainer = Color.White,

    secondary = ApogeeColors.Data,
    onSecondary = Color(0xFF00212F),
    secondaryContainer = ApogeeColors.SurfaceRaised,
    onSecondaryContainer = Color.White,

    tertiary = ApogeeColors.Prograde,
    onTertiary = Color(0xFF00301A),

    background = ApogeeColors.BackdropTop,
    onBackground = Color.White,

    surface = ApogeeColors.Surface,
    onSurface = Color.White,
    surfaceVariant = ApogeeColors.SurfaceRaised,
    onSurfaceVariant = Color.White.alpha(ApogeeAlpha.SECONDARY),

    outline = Color.White.alpha(ApogeeAlpha.BORDER),
    outlineVariant = Color.White.alpha(ApogeeAlpha.DIVIDER),

    error = ApogeeColors.Danger,
    onError = Color(0xFF3A0000),

    scrim = Color.Black,
)

/** Plain Roboto. Only the display and large title sizes are overridden. */
private val ApogeeTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        ),
        titleLarge = base.titleLarge.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    )
}

/** Monospace for telemetry, so digits don't jitter. */
val TelemetryTextStyle = TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontSize = 14.sp,
    color = ApogeeColors.Data,
)

@Composable
fun ApogeeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ApogeeColorScheme,
        typography = ApogeeTypography,
        content = content,
    )
}
