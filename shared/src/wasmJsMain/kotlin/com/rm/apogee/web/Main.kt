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
        if (e.metaKey || e.altKey || typing()) return;
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

    private companion object {
        const val BACKGROUND_MILLIS = 6.0
    }
}
