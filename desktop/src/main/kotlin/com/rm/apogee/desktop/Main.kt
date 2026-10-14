package com.rm.apogee.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.rm.apogee.ApogeeApp
import com.rm.apogee.ui.goBack
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import java.io.File

/** The XDG folder from [variable], or its default under the home folder. */
private fun xdg(variable: String, fallback: String): File =
    File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + fallback), "apogee")

/** Windows folders: saves and settings roam with the account, the cache stays on this machine. */
private fun windowsDir(variable: String, vararg under: String): File =
    under.fold(File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"), "Apogee")) { dir, name -> File(dir, name) }

internal val onWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** Whether the screen is too small for a 1280 x 800 window. */
private fun smallScreen(): Boolean = runCatching {
    val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    bounds.width < 1300 || bounds.height < 840
}.getOrDefault(false)

/** A Steam Deck, LCD or OLED, by the names its firmware gives it. */
private val steamDeck: Boolean = runCatching {
    File("/sys/class/dmi/id/sys_vendor").readText().trim() == "Valve" &&
        File("/sys/class/dmi/id/product_name").readText().trim() in setOf("Jupiter", "Galileo")
}.getOrDefault(false)

/** SteamOS's Game Mode, where there's no desktop and the window should fill the screen. */
private val gameMode: Boolean =
    System.getenv("XDG_CURRENT_DESKTOP") == "gamescope" || System.getenv("SteamGamepadUI") != null

/** The Deck's 7 inch screen at the desktop's usual density would make the controls tiny. */
private const val DECK_SCALE = 1.5f

private fun windowIcon(): BitmapPainter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("apogee.png")!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

fun main() {
    // On the Deck, Skia's OpenGL crashes on its first frame on the Mesa driver (as in Acidulous),
    // so the screens draw in software unless told otherwise. The world has its own context.
    if (steamDeck && System.getenv("SKIKO_RENDER_API") == null && System.getProperty("skiko.renderApi") == null) {
        System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
    }
    val config = (if (onWindows) windowsDir("APPDATA", "config") else xdg("XDG_CONFIG_HOME", ".config")).apply { mkdirs() }
    val data = (if (onWindows) windowsDir("APPDATA", "data") else xdg("XDG_DATA_HOME", ".local/share")).apply { mkdirs() }
    val cache = (if (onWindows) windowsDir("LOCALAPPDATA", "cache") else xdg("XDG_CACHE_HOME", ".cache")).apply { mkdirs() }
    // A second launch brings the running copy forward instead.
    if (!SingleInstance.claim(data)) return
    // Before anything else can throw. Also picks up crashes the JVM wrote on earlier runs.
    val crashes = CrashReports(data).apply { install(); collect() }

    val world = WorldView()
    val host = DesktopHost(data, config, cache, world)
    val app = ApogeeApp(host)
    host.app = app
    world.onPoll = Gamepads(app)::poll
    world.onNoGl = { host.toast("The world can't be drawn here: it needs OpenGL 3.3") }

    application {
        fun quit() {
            app.hidden()
            app.destroy()
            world.close()
            exitApplication()
        }
        host.onQuit = ::quit
        host.separateFullScreen = onWindows
        val startFull = gameMode || host.settings.fullScreen
        val window = rememberWindowState(
            size = DpSize(1280.dp, 800.dp),
            placement = when {
                startFull && !onWindows -> WindowPlacement.Fullscreen
                smallScreen() -> WindowPlacement.Maximized
                else -> WindowPlacement.Floating
            },
        )
        val fullWindow = rememberWindowState(placement = WindowPlacement.Fullscreen)
        remember { if (onWindows && startFull) host.inFullScreenWindow = true }
        host.window = window
        // On Windows, full screen is a second window without a frame in place of this one.
        val frameless = onWindows && host.inFullScreenWindow
        key(frameless) {
        Window(
            onCloseRequest = ::quit,
            title = "Apogee",
            icon = remember { windowIcon() },
            state = if (frameless) fullWindow else window,
            undecorated = frameless,
            onPreviewKeyEvent = {
                // F11 for full screen, as in a browser.
                if (it.key == Key.F11) {
                    if (it.type == KeyEventType.KeyDown) host.setFullScreen(!host.isFullScreen)
                    true
                } else false
            },
            // Keys the focused control didn't use: flying, then Esc for back.
            onKeyEvent = { event -> worldKey(app, event) },
        ) {
            host.frame = this.window
            val frame = this.window
            DisposableEffect(Unit) {
                SingleInstance.onRaise = {
                    java.awt.EventQueue.invokeLater {
                        window.isMinimized = false
                        frame.toFront()
                        frame.requestFocus()
                    }
                }
                onDispose { SingleInstance.onRaise = null }
            }
            DisposableEffect(Unit) {
                val focus = object : java.awt.event.WindowAdapter() {
                    override fun windowLostFocus(e: java.awt.event.WindowEvent) = app.releaseKeys()
                    override fun windowIconified(e: java.awt.event.WindowEvent) { app.pause(); app.hidden() }
                    override fun windowDeiconified(e: java.awt.event.WindowEvent) = app.resume()
                }
                this@Window.window.addWindowFocusListener(focus)
                this@Window.window.addWindowListener(focus)
                onDispose {
                    this@Window.window.removeWindowFocusListener(focus)
                    this@Window.window.removeWindowListener(focus)
                }
            }
            val system = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (steamDeck) Density(DECK_SCALE, system.fontScale) else system) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    WorldImage(host)
                    // In its own layer, so the world layer's cut-out shows the image under it.
                    Box(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                        app.Content()
                    }
                    Note(host.note)
                    com.rm.apogee.ui.theme.ApogeeTheme(textScale = host.settings.textScale) { CrashNotice(crashes) }
                }
            }
        }
        }
    }
}

/** The world's latest frame, stretched to fill and turned the right way up. */
@Composable
private fun WorldImage(host: DesktopHost) {
    val world = host.world
    var shown by remember { mutableLongStateOf(0L) }
    LaunchedEffect(world) {
        while (true) {
            withFrameNanos {
                world.tick()
                val frame = world.latest.get()
                if (frame != null && frame.number != shown) shown = frame.number
            }
        }
    }
    Canvas(Modifier.fillMaxSize().onSizeChanged { world.size((it.width * host.renderScale).toInt(), (it.height * host.renderScale).toInt()) }) {
        // Read so a new frame draws again.
        if (shown == 0L) return@Canvas
        val frame = world.latest.get() ?: return@Canvas
        // The size follows the window and the chosen resolution.
        world.size((size.width * host.renderScale).toInt(), (size.height * host.renderScale).toInt())
        drawIntoCanvas { canvas ->
            val skia = canvas.nativeCanvas
            skia.save()
            skia.translate(0f, size.height)
            skia.scale(1f, -1f)
            skia.drawImageRect(
                frame.image,
                Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()),
                Rect.makeWH(size.width, size.height),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                null,
                true,
            )
            skia.restore()
        }
    }
}

/** Once, after a crash: where its report is. */
@Composable
private fun CrashNotice(crashes: CrashReports) {
    var report by remember { mutableStateOf(crashes.unread()) }
    val file = report ?: return
    fun done() {
        crashes.markRead()
        report = null
    }
    AlertDialog(
        onDismissRequest = ::done,
        title = { Text("Apogee closed unexpectedly") },
        text = { Text("A report was saved in ${file.parentFile.path}") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { java.awt.Desktop.getDesktop().open(file.parentFile) }
                done()
            }) { Text("Show the report") }
        },
        dismissButton = { TextButton(onClick = ::done) { Text("OK") } },
    )
}

/** A short note at the bottom of the window. */
@Composable
private fun Note(text: String?) {
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    Box(Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(text != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                last,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xE0202428), RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/** A key the screens didn't use, to the craft in flight by its browser code, or Esc to go back. */
private fun worldKey(app: ApogeeApp, event: KeyEvent): Boolean {
    val down = when (event.type) {
        KeyEventType.KeyDown -> true
        KeyEventType.KeyUp -> false
        else -> return false
    }
    if (event.key == Key.Escape) return down && goBack()
    val code = keyCode(event.key) ?: return false
    return app.key(code, down)
}

/** The browser's code for [key], which is what the app goes by. */
private fun keyCode(key: Key): String? = when (key) {
    Key.DirectionUp -> "ArrowUp"
    Key.DirectionDown -> "ArrowDown"
    Key.DirectionLeft -> "ArrowLeft"
    Key.DirectionRight -> "ArrowRight"
    Key.Spacebar -> "Space"
    Key.ShiftLeft -> "ShiftLeft"
    Key.ShiftRight -> "ShiftRight"
    Key.CtrlLeft -> "ControlLeft"
    Key.CtrlRight -> "ControlRight"
    else -> LETTERS[key]?.let { "Key$it" }
}

private val LETTERS: Map<Key, Char> = mapOf(
    Key.A to 'A', Key.B to 'B', Key.C to 'C', Key.D to 'D', Key.E to 'E', Key.F to 'F', Key.G to 'G',
    Key.H to 'H', Key.I to 'I', Key.J to 'J', Key.K to 'K', Key.L to 'L', Key.M to 'M', Key.N to 'N',
    Key.O to 'O', Key.P to 'P', Key.Q to 'Q', Key.R to 'R', Key.S to 'S', Key.T to 'T', Key.U to 'U',
    Key.V to 'V', Key.W to 'W', Key.X to 'X', Key.Y to 'Y', Key.Z to 'Z',
)
