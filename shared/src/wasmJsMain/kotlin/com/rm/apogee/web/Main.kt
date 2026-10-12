@file:OptIn(ExperimentalWasmJsInterop::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.rm.apogee.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeViewport
import com.rm.apogee.ApogeeApp
import com.rm.apogee.AppHost
import com.rm.apogee.WorldGestures
import com.rm.apogee.core.Folder
import com.rm.apogee.core.runBackgroundWork
import com.rm.apogee.game.DiscoveredServer
import com.rm.apogee.game.ServerBrowser
import com.rm.apogee.platform.PerfHints
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.PictureStore
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.gl.webGl
import com.rm.apogee.settings.GameSettings
import com.rm.apogee.ui.installBack
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import org.w3c.dom.HTMLCanvasElement

/**
 * Apogee in a browser: the phone app, with the world drawn by WebGL2 on a canvas under the screens
 * and everything in the browser's storage. Solo only; a page has no network play.
 */
fun main() {
    val canvas = document.getElementById("world") as HTMLCanvasElement
    webGl = context(canvas)
    if (webGl == null) {
        document.getElementById("loading")?.remove()
        document.getElementById("nogl")?.setAttribute("style", "display: flex")
        return
    }
    val host = WebHost(canvas)
    val app = ApogeeApp(host)
    host.app = app
    ComposeViewport(document.getElementById("screens")!!) { app.Content() }
    installBack()
    onKeys(
        { code, down -> app.key(code, down) },
        { app.releaseKeys() },
    )
    host.startGamepad()
    document.getElementById("loading")?.remove()
    window.addEventListener("pagehide") { app.hidden() }
    document.addEventListener("visibilitychange") {
        if (hiddenPage()) { app.hidden(); app.pause() } else app.resume()
    }
}

private fun context(canvas: HTMLCanvasElement): JsAny? =
    js("canvas.getContext('webgl2', { antialias: true, alpha: false, depth: true, stencil: false, powerPreference: 'high-performance' })")

/** Keys to [onKey] by code, except while typing. Keys the game uses do nothing else in the page. */
private fun onKeys(onKey: (String, Boolean) -> Boolean, onLost: () -> Unit): Unit = js("""{
    const typing = () => { const a = document.activeElement; return a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA' || a.isContentEditable); };
    const handle = (e, down) => {
        // Keys the controller sends to work the menus aren't the keyboard flying.
        if (!e.isTrusted || e.metaKey || e.altKey || typing()) return;
        if (e.ctrlKey && !e.code.startsWith('Control')) return;
        if (onKey(e.code, down)) e.preventDefault();
    };
    window.addEventListener('keydown', (e) => handle(e, true));
    window.addEventListener('keyup', (e) => handle(e, false));
    window.addEventListener('blur', () => onLost());
}""")

private fun hiddenPage(): Boolean = js("document.visibilityState === 'hidden'")

private fun devicePixelRatio(): Double = js("window.devicePixelRatio || 1")

private fun requestFrame(callback: (Double) -> Unit): Int = js("requestAnimationFrame(callback)")

private fun cancelFrame(id: Int): Unit = js("cancelAnimationFrame(id)")

private fun download(name: String, text: String): Unit = js("""{
    const blob = new Blob([text], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
}""")

private fun pickFile(onText: (String?, String) -> Unit): Unit = js("""{
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json,application/json,text/plain';
    input.onchange = () => {
        const file = input.files && input.files[0];
        if (!file) return;
        file.text().then((t) => onText(t, file.name), () => onText(null, file.name));
    };
    input.click();
}""")

private fun showToast(message: String): Unit = js("""{
    const t = document.getElementById('toast');
    t.textContent = message;
    t.style.opacity = '1';
    clearTimeout(window.__apogeeToast);
    window.__apogeeToast = setTimeout(() => { t.style.opacity = '0'; }, 3500);
}""")

private fun shortTime(): String = js("new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })")

private fun dateText(epochMillis: Double): String = js("new Date(epochMillis).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })")

private fun fullscreenOn(on: Boolean): Unit = js("""{
    try {
        if (on && !document.fullscreenElement && matchMedia('(pointer: coarse)').matches) document.documentElement.requestFullscreen().catch(() => {});
        if (!on && document.fullscreenElement) document.exitFullscreen().catch(() => {});
    } catch (e) {}
}""")

/** Puts the page in or out of full screen. A browser only allows it from a press or a key. */
private fun fullscreenChoose(on: Boolean): Unit = js("""{
    try {
        if (on && !document.fullscreenElement) document.documentElement.requestFullscreen().catch(() => {});
        if (!on && document.fullscreenElement) document.exitFullscreen().catch(() => {});
    } catch (e) {}
}""")

private fun fullscreenNow(): Boolean = js("!!document.fullscreenElement")

private fun onFullscreenChange(callback: () -> Unit): Unit = js("document.addEventListener('fullscreenchange', () => callback())")

private fun cores(): Int = js("navigator.hardwareConcurrency || 4")

private fun memoryGb(): Double = js("navigator.deviceMemory || 4")

private fun touchScreen(): Boolean = js("matchMedia('(pointer: coarse)').matches")

/**
 * The first connected gamepad as one line, cheaper than a call per button: button values, axes
 * and name, split by |. Empty with none.
 */
private fun gamepadLine(): String = js("""{
    const pads = navigator.getGamepads ? navigator.getGamepads() : [];
    for (const p of pads) {
        if (!p || !p.connected) continue;
        return p.buttons.map(b => b.value.toFixed(3)).join(',') + '|' +
            p.axes.map(a => a.toFixed(3)).join(',') + '|' + p.id;
    }
    return '';
}""")

/**
 * Sends a key to the screens as if typed, to the focused element or their canvas. True if used.
 * This is how the controller works the menus.
 */
private fun screensKey(key: String, shift: Boolean): Boolean = js("""{
    let canvas = null;
    for (const d of document.querySelectorAll('#screens div')) {
        if (d.shadowRoot) { canvas = d.shadowRoot.querySelector('canvas'); if (canvas) break; }
    }
    if (!canvas) return false;
    let target = canvas.getRootNode().activeElement;
    if (!target) { canvas.focus(); target = canvas; }
    const o = { key: key, code: key, shiftKey: shift, bubbles: true, cancelable: true, composed: true };
    const down = new KeyboardEvent('keydown', o);
    target.dispatchEvent(down);
    target.dispatchEvent(new KeyboardEvent('keyup', o));
    return down.defaultPrevented;
}""")

/** What the page gives the app: storage, the canvas, downloads and the file picker. */
private class WebHost(private val canvas: HTMLCanvasElement) : AppHost {
    lateinit var app: ApogeeApp

    override val scope: CoroutineScope = MainScope()
    override val settings = GameSettings(StoragePreferences())
    override fun folder(name: String): Folder = StorageFolder(name)

    /** Pictures are drawn again each visit: there's no room in local storage to keep them. */
    override val pictures = object : PictureStore {
        override fun load(folder: String, key: String) = null
        override fun save(folder: String, key: String, argb: IntArray, size: Int) {}
    }

    override val serverBrowser = object : ServerBrowser {
        override val servers: List<DiscoveredServer> = emptyList()
        override val scanning = false
        override fun start(scope: CoroutineScope) {}
        override fun stop() {}
        override fun reasonFor(server: DiscoveredServer): String? = "Not from a browser"
    }

    override val worldInputInCompose = true
    override val savesToFiles = true
    override val keyboardFirst: Boolean get() = !touchScreen()
    override val networked = false

    private var renderer: GlRenderer? = null
    private var frame = 0
    private var width = 0
    private var height = 0

    override fun showSurface(renderer: GlRenderer, gestures: WorldGestures) {
        this.renderer = renderer
        canvas.style.display = "block"
        width = 0; height = 0
        renderer.onSurfaceCreated()
        lateinit var draw: (Double) -> Unit
        draw = {
            val r = this.renderer
            if (r != null) {
                // Its buffer at the resolution asked for, which the page stretches to fill it.
                val scale = devicePixelRatio() * renderScale
                val w = (canvas.clientWidth * scale).toInt().coerceAtLeast(1)
                val h = (canvas.clientHeight * scale).toInt().coerceAtLeast(1)
                if (w != width || h != height) {
                    width = w; height = h
                    canvas.width = w; canvas.height = h
                    r.onSurfaceChanged(w, h)
                }
                r.onDrawFrame()
                // Background work gets a few milliseconds a frame; a page has one thread.
                runBackgroundWork(BACKGROUND_MILLIS)
                frame = requestFrame(draw)
            }
        }
        frame = requestFrame(draw)
    }

    override fun hideSurface() {
        renderer = null
        cancelFrame(frame)
        canvas.style.display = "none"
    }

    /**
     * From the browser's cores, memory and touch screen. Never high on its own, since a page does
     * everything on one thread; it can be turned up in Settings.
     */
    override fun detectTier(): QualityTier = when {
        !touchScreen() && cores() >= 8 && memoryGb() >= 8.0 -> QualityTier.MEDIUM
        else -> QualityTier.LOW
    }

    override fun perfHints(): PerfHints? = null

    /** Full screen chosen in Settings, which leaving the world doesn't undo. */
    private var chosenFullScreen = false

    override fun fullscreen(on: Boolean) {
        if (!chosenFullScreen) fullscreenOn(on)
    }

    override val canFullScreen = true

    /** Follows the page, which can also leave full screen by Esc or the browser's own controls. */
    private var fullScreenNow by androidx.compose.runtime.mutableStateOf(false)

    init {
        onFullscreenChange {
            fullScreenNow = fullscreenNow()
            if (!fullScreenNow) chosenFullScreen = false
        }
    }

    override val isFullScreen: Boolean get() = fullScreenNow

    override fun setFullScreen(on: Boolean) {
        chosenFullScreen = on
        fullscreenChoose(on)
    }

    override fun shareCraft(name: String, text: String) {
        val safe = name.map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("").trim('-').ifBlank { "craft" }
        download("$safe.apogee.json", text)
    }

    override fun pickSharedCraft() = pickFile { text, name -> app.importFile(text, name) }

    override fun dateTime(epochMillis: Long): String = dateText(epochMillis.toDouble())

    override fun shareWorld(name: String, text: String) {
        val safe = name.map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("").trim('-').ifBlank { "universe" }
        download("$safe.apogee-world.json", text)
    }

    override fun toast(message: String) = showToast(message)

    override fun debugSwitch(name: String): Boolean = window.location.search.contains(name)

    override val canScaleRender: Boolean get() = true

    /** What [renderAt] last asked for, read as each frame sizes the canvas. */
    var renderScale = 1.0

    override fun renderAt(scale: Double) { renderScale = scale }

    override fun timeNow(): String = shortTime()

    override fun runOnMain(block: () -> Unit) = block()

    private val gamepad = com.rm.apogee.input.PadState()
    private var hadGamepad = false

    /**
     * Polls the browser's gamepad every frame (a page isn't told when a stick moves), in the
     * standard layout. In flight the app flies with it; elsewhere it works the screens like a
     * keyboard.
     */
    fun startGamepad() {
        requestFrame(::gamepadFrame)
    }

    private fun gamepadFrame(time: Double) {
        requestFrame(::gamepadFrame)
        val line = gamepadLine()
        if (line.isEmpty()) {
            if (hadGamepad) {
                hadGamepad = false
                app.pad.clear()
                app.controllerName = null
            }
            return
        }
        val parts = line.split('|', limit = 3)
        val buttons = parts[0].split(',').map { it.toFloatOrNull() ?: 0f }
        val axes = parts.getOrNull(1)?.split(',')?.map { it.toFloatOrNull() ?: 0f } ?: emptyList()
        for ((i, button) in STANDARD_BUTTONS.withIndex()) gamepad[button] = buttons.getOrElse(i) { 0f }
        gamepad.leftX = axes.getOrElse(0) { 0f }
        gamepad.leftY = axes.getOrElse(1) { 0f }
        gamepad.rightX = axes.getOrElse(2) { 0f }
        gamepad.rightY = axes.getOrElse(3) { 0f }
        app.pad.update(gamepad)
        if (!hadGamepad) {
            hadGamepad = true
            app.controllerName = parts.getOrNull(2)
        }
        if (app.padMode() != com.rm.apogee.input.PadMode.FLIGHT) menus.update(gamepad, time) else menus.idle(gamepad)
    }

    private val menus = com.rm.apogee.input.MenuPad(::screensKey)

    private companion object {
        const val BACKGROUND_MILLIS = 6.0

        /** The standard gamepad's buttons, by their place in its list. */
        val STANDARD_BUTTONS = listOf(
            com.rm.apogee.input.PadButton.A, com.rm.apogee.input.PadButton.B,
            com.rm.apogee.input.PadButton.X, com.rm.apogee.input.PadButton.Y,
            com.rm.apogee.input.PadButton.L1, com.rm.apogee.input.PadButton.R1,
            com.rm.apogee.input.PadButton.L2, com.rm.apogee.input.PadButton.R2,
            com.rm.apogee.input.PadButton.SELECT, com.rm.apogee.input.PadButton.START,
            com.rm.apogee.input.PadButton.L3, com.rm.apogee.input.PadButton.R3,
            com.rm.apogee.input.PadButton.UP, com.rm.apogee.input.PadButton.DOWN,
            com.rm.apogee.input.PadButton.LEFT, com.rm.apogee.input.PadButton.RIGHT,
        )
    }
}
