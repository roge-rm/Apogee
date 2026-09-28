@file:OptIn(ExperimentalWasmJsInterop::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.rm.apogee.web

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
 * Apogee in a browser: the same app as on a phone, with the world drawn by WebGL2 on a canvas under
 * the screens, and everything kept in the browser's storage. There's no network play from a page,
 * so it's solo, in the career or the sandbox.
 */
fun main() {
    val canvas = document.getElementById("world") as HTMLCanvasElement
    webGl = context(canvas)
    if (webGl == null) {
        document.getElementById("nogl")?.setAttribute("style", "display: block")
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

/**
 * Keys to [onKey], by their codes, except while something's being typed. A key the game used does
 * nothing else (Space doesn't scroll, Ctrl doesn't reach the browser's shortcuts with it).
 */
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

private fun pickFile(onText: (String?) -> Unit): Unit = js("""{
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json,application/json,text/plain';
    input.onchange = () => {
        const file = input.files && input.files[0];
        if (!file) return;
        file.text().then(onText, () => onText(null));
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

private fun fullscreenOn(on: Boolean): Unit = js("""{
    try {
        if (on && !document.fullscreenElement && matchMedia('(pointer: coarse)').matches) document.documentElement.requestFullscreen().catch(() => {});
        if (!on && document.fullscreenElement) document.exitFullscreen().catch(() => {});
    } catch (e) {}
}""")

private fun cores(): Int = js("navigator.hardwareConcurrency || 4")

private fun memoryGb(): Double = js("navigator.deviceMemory || 4")

private fun touchScreen(): Boolean = js("matchMedia('(pointer: coarse)').matches")

/**
 * The first gamepad connected, as one line: its buttons (how far each is pressed), then its
 * axes, then its name, split by |. Empty with none. A line a frame is cheaper than going back
 * and forth for every button.
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
 * A key sent to the screens, as if typed: to whatever in them has the page's focus, or their canvas.
 * True if they used it. That's how the controller works the menus, which answer Tab, Enter and Esc
 * as a keyboard's, and the arrows where something takes them (a slider).
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
                val scale = devicePixelRatio()
                val w = (canvas.clientWidth * scale).toInt().coerceAtLeast(1)
                val h = (canvas.clientHeight * scale).toInt().coerceAtLeast(1)
                if (w != width || h != height) {
                    width = w; height = h
                    canvas.width = w; canvas.height = h
                    r.onSurfaceChanged(w, h)
                }
                r.onDrawFrame()
                // Building the ground and the rest, a few milliseconds a frame, since a page has
                // one thread for everything.
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
     * From what the browser will say: its cores and memory, and whether it's a phone or tablet. The
     * GL renderer doesn't tell. Never high on its own: a page does everything on one thread (the
     * world, the sea, the terrain and the drawing), and high on a fast PC ran at 34 frames a
     * second where medium ran at 55. It can be turned up in Settings.
     */
    override fun detectTier(): QualityTier = when {
        !touchScreen() && cores() >= 8 && memoryGb() >= 8.0 -> QualityTier.MEDIUM
        else -> QualityTier.LOW
    }

    override fun perfHints(): PerfHints? = null

    override fun fullscreen(on: Boolean) = fullscreenOn(on)

    override fun shareCraft(name: String, text: String) {
        val safe = name.map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("").trim('-').ifBlank { "craft" }
        download("$safe.apogee.json", text)
    }

    override fun pickSharedCraft() = pickFile { text -> app.importCraft(text) }

    override fun toast(message: String) = showToast(message)

    override fun debugSwitch(name: String): Boolean = window.location.search.contains(name)

    override fun timeNow(): String = shortTime()

    override fun runOnMain(block: () -> Unit) = block()

    private val gamepad = com.rm.apogee.input.PadState()
    private var hadGamepad = false

    /**
     * The browser's gamepad, asked for every frame on every screen (a page isn't told when a stick
     * moves), in the standard layout: A, B, X, Y, L1, R1, L2, R2, Select, Start, L3, R3, then the
     * D-pad, and the two sticks' axes. In flight the app flies with it. Anywhere else it works the
     * screens the way a keyboard would, as Android's own handling does on a phone.
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
        if (app.padMode() != com.rm.apogee.input.PadMode.FLIGHT) workScreens(time) else heading = null
        wasA = gamepad[com.rm.apogee.input.PadButton.A] > 0.5f
        wasB = gamepad[com.rm.apogee.input.PadButton.B] > 0.5f
    }

    // The way the D-pad or stick is held in the menus, and when it next moves again on its own.
    private var heading: com.rm.apogee.input.PadButton? = null
    private var nextStep = 0.0
    private var wasA = false
    private var wasB = false

    /** The D-pad or left stick moves between things, A presses the one it's on, and B goes back. */
    private fun workScreens(time: Double) {
        val way = when {
            gamepad[com.rm.apogee.input.PadButton.UP] > 0.5f || gamepad.leftY < -STICK_STEP -> com.rm.apogee.input.PadButton.UP
            gamepad[com.rm.apogee.input.PadButton.DOWN] > 0.5f || gamepad.leftY > STICK_STEP -> com.rm.apogee.input.PadButton.DOWN
            gamepad[com.rm.apogee.input.PadButton.LEFT] > 0.5f || gamepad.leftX < -STICK_STEP -> com.rm.apogee.input.PadButton.LEFT
            gamepad[com.rm.apogee.input.PadButton.RIGHT] > 0.5f || gamepad.leftX > STICK_STEP -> com.rm.apogee.input.PadButton.RIGHT
            else -> null
        }
        if (way == null) {
            heading = null
        } else if (way != heading) {
            heading = way
            nextStep = time + FIRST_REPEAT_MS
            step(way)
        } else if (time >= nextStep) {
            nextStep = time + REPEAT_MS
            step(way)
        }
        if (gamepad[com.rm.apogee.input.PadButton.A] > 0.5f && !wasA) screensKey("Enter", false)
        if (gamepad[com.rm.apogee.input.PadButton.B] > 0.5f && !wasB) screensKey("Escape", false)
    }

    /**
     * One step [way]. The screens on a page don't move between things by the arrows, so up and down
     * are Shift-Tab and Tab. (Sent as arrows, a scrolling page took them to scroll by, and nothing
     * moved.) Left and right go as arrows first, for whatever takes them (a slider), and as Tab or
     * Shift-Tab when nothing does.
     */
    private fun step(way: com.rm.apogee.input.PadButton) {
        when (way) {
            com.rm.apogee.input.PadButton.UP -> screensKey("Tab", true)
            com.rm.apogee.input.PadButton.DOWN -> screensKey("Tab", false)
            com.rm.apogee.input.PadButton.LEFT -> if (!screensKey("ArrowLeft", false)) screensKey("Tab", true)
            else -> if (!screensKey("ArrowRight", false)) screensKey("Tab", false)
        }
    }

    private companion object {
        const val BACKGROUND_MILLIS = 6.0

        /** How far the stick goes to move in a menu, and how soon a held one moves again, in ms. */
        const val STICK_STEP = 0.6f
        const val FIRST_REPEAT_MS = 400.0
        const val REPEAT_MS = 150.0

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
