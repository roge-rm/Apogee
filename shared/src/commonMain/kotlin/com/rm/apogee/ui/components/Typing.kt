package com.rm.apogee.ui.components

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/** How many text fields have the keyboard, so game keys stay out of typing. UI thread only. */
object Typing {
    internal var fields = 0

    val active: Boolean get() = fields > 0
}

/** Counts this text field in [Typing] while it has focus. */
fun Modifier.typing(): Modifier = composed {
    val focused = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (focused[0]) Typing.fields--
            focused[0] = false
        }
    }
    onFocusChanged {
        if (it.isFocused != focused[0]) {
            focused[0] = it.isFocused
            Typing.fields += if (it.isFocused) 1 else -1
        }
    }
}
