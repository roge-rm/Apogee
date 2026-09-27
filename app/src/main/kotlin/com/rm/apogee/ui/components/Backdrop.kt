package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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
 * The full-screen navy-to-plum gradient every screen except flight sits on.
 *
 * It only scrolls when its content overflows, so a short menu stays centred instead of sticking to
 * the top.
 */
@Composable
fun Backdrop(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = Dimens.MenuContentMaxWidth,
    content: @Composable (Modifier) -> Unit,
) {
    val scroll = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(ApogeeColors.BackdropTop, ApogeeColors.BackdropBottom),
                )
            )
            // After the background, so the gradient still fills the screen while the content
            // centres itself in the space the keyboard leaves. Without it the join screen's Connect
            // button sits under the keyboard you raised to type the address into the field above
            // it.
            .imePadding()
            // On the full-screen box, not the content column, so the bar sits at the screen's right
            // edge, where you'd look for a scroll bar.
            .verticalScrollbar(scroll),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(scroll)
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
