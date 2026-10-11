package com.rm.apogee.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/** Back in a window: Esc or a controller's B, sent here by the window. The latest enabled handler gets it. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val handler = remember { Handler() }
    SideEffect {
        handler.enabled = enabled
        handler.onBack = onBack
    }
    DisposableEffect(handler) {
        handlers.add(handler)
        onDispose { handlers.remove(handler) }
    }
}

private class Handler {
    var enabled = false
    var onBack: () -> Unit = {}
}

private val handlers = mutableListOf<Handler>()

/** Goes back a step, if something's there to go back from. True if something did. */
fun goBack(): Boolean {
    val handler = handlers.lastOrNull { it.enabled } ?: return false
    handler.onBack()
    return true
}
