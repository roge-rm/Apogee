package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
 * The navy-to-plum gradient every screen but flight sits on.
 *
 * Without a [title] it's a menu: centred, scrolling only on overflow, with a [BackButton] under
 * its buttons. With one it's a page: the title and Back stay fixed at the top and only what's
 * under them scrolls.
 */
@Composable
fun Backdrop(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = Dimens.MenuContentMaxWidth,
    /** A page's title, in its top row. */
    title: String? = null,
    /** Back, on the title row, for a page with a [title]. */
    onBack: (() -> Unit)? = null,
    /**
     * The list fills the height under the title row and scrolls itself, instead of the page. The
     * list takes `Modifier.weight(1f)`.
     */
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.(Modifier) -> Unit,
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
            // Clear of the notch.
            .windowInsetsPadding(WindowInsets.displayCutout)
            // After the background, so the gradient fills the screen while the content centres
            // in the space above the keyboard.
            .imePadding()
            // On the full-screen box so the bar sits at the screen's right edge. A list that
            // scrolls itself has its own.
            .let { if (fillHeight) it else it.verticalScrollbar(scroll) },
        contentAlignment = if (title == null) Alignment.Center else Alignment.TopCenter,
    ) {
        if (title == null) {
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
        } else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // A short screen, like a handheld's, gets narrower margins.
            val short = maxHeight < SHORT_SCREEN
            Column(
                Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxSize()
                    .padding(
                        horizontal = if (short) Dimens.ScreenPaddingH / 2 else Dimens.ScreenPaddingH,
                        vertical = if (short) Dimens.ScreenPaddingV / 2 else Dimens.ScreenPaddingV,
                    ),
            ) {
                TitleRow(title, onBack)
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.weight(1f).let { if (fillHeight) it else it.verticalScroll(scroll) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    content(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Shorter than this, a page's margins are halved. */
private val SHORT_SCREEN = 480.dp

/** A page's title, and Back at the other end of the same row. */
@Composable
fun TitleRow(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
        onBack?.let { BackButton(it) }
    }
}

/** Back: under a short menu's buttons, or at the end of a page's title row. */
@Composable
fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onBack, modifier = modifier.padFocus(androidx.compose.foundation.shape.RoundedCornerShape(50))) {
        Text("Back", style = MaterialTheme.typography.labelLarge, color = ApogeeColors.Accent)
    }
}
