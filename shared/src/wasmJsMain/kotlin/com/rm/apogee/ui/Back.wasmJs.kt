@file:OptIn(ExperimentalWasmJsInterop::class)

package com.rm.apogee.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Back on a web page: Esc or the browser's back button, since the screens have no back arrows.
 * The latest enabled handler gets it, as on a phone.
 */
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

/** Goes back a step, if something's there to go back from. */
private fun goBack(): Boolean {
    val handler = handlers.lastOrNull { it.enabled } ?: return false
    handler.onBack()
    return true
}

/**
 * Hooks Esc and the browser's back button up to the handlers. An extra history entry makes back
 * come here first; with nothing left to go back from, it leaves the page. Esc is caught before the
 * screens see it, or a dialog closing itself would let back reopen the flight menu.
 */
fun installBack() {
    pushEntry()
    onPopState {
        if (goBack()) pushEntry() else historyBack()
    }
    onEscape { goBack() }
}

private fun pushEntry(): Unit = js("history.pushState({ apogee: true }, '')")

private fun historyBack(): Unit = js("history.back()")

private fun onPopState(block: () -> Unit): Unit = js("window.addEventListener('popstate', () => block())")

private fun onEscape(block: () -> Unit): Unit =
    js("window.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !e.repeat) block(); }, true)")
