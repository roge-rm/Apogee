@file:OptIn(ExperimentalWasmJsInterop::class)

package com.rm.apogee.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Back on a web page: Esc, or the browser's own back button. The screens have no back arrows of
 * their own, since a phone has one, so both of these have to reach them. The latest handler that's
 * on gets it, as on a phone.
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
 * Hooks Esc and the browser's back button up to the handlers. The page keeps an extra entry in the
 * browser's history, so the back button comes here first. When there's nothing left to go back from
 * (the main menu), it goes on back out of the page, as it would anywhere else.
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
    js("window.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !e.repeat) block(); })")
