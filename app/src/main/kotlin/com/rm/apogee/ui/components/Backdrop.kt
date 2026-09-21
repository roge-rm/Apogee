package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens

/**
 * The full-screen navy-to-plum gradient every non-flight screen sits on.
 *
 * Scrolls only when its content overflows, so a short menu stays vertically
 * centred instead of pinning to the top.
 */
@Composable
fun Backdrop(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = Dimens.MenuContentMaxWidth,
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(ApogeeColors.BackdropTop, ApogeeColors.BackdropBottom),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Dimens.ScreenPaddingH,
                    vertical = Dimens.ScreenPaddingV,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            content(Modifier.widthIn(max = maxContentWidth))
        }
    }
}
