package com.rm.apogee.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.rm.apogee.ApogeeApp
import com.rm.apogee.AppHost
import com.rm.apogee.WorldGestures
import com.rm.apogee.core.FileFolder
import com.rm.apogee.core.Folder
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.game.DiscoveredServer
import com.rm.apogee.game.ServerBrowser
import com.rm.apogee.net.LanDiscovery
import com.rm.apogee.platform.Log
import com.rm.apogee.platform.PerfHints
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.PictureStore
import com.rm.apogee.render.QualityTier
import com.rm.apogee.settings.GameSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** What the desktop gives the app: folders on disk, the off-screen world and file dialogs. */
class DesktopHost(
    private val data: File,
    config: File,
    private val cache: File,
    val world: WorldView,
) : AppHost {
    lateinit var app: ApogeeApp

    /** The window, for file dialogs. */
    var frame: Frame? = null

    /** Closes the window and the app. */
    var onQuit: () -> Unit = {}

    /** The window's state, for full screen. */
    var window: WindowState? = null

    /** Where the window goes back to from full screen: as it was, maximised or not. */
    private var before = WindowPlacement.Floating

    override val scope: CoroutineScope = MainScope()
    override val settings = GameSettings(config)
    override fun folder(name: String): Folder = FileFolder(File(data, name))

    override val pictures: PictureStore = PngPictures(File(cache, "pictures"))

    override val serverBrowser: ServerBrowser = DesktopServerBrowser(StockParts.catalog.contentHash)

    override val worldInputInCompose = true
    override val keyboardFirst = true
    override val savesToFiles = true
    override val canScaleRender = true
    override val canQuit = true

    /** What [renderAt] last asked for, which the window's size is multiplied by. */
    @Volatile var renderScale = 1.0
        private set

    override fun renderAt(scale: Double) { renderScale = scale }

    override fun showSurface(renderer: GlRenderer, gestures: WorldGestures) = world.show(renderer)

    override fun hideSurface() = world.hide()

    /** From the GL card's name, cores and memory. A software renderer is always low. */
    override fun detectTier(): QualityTier {
        val name = world.rendererName.lowercase()
        val cores = Runtime.getRuntime().availableProcessors()
        val memoryGb = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize?.let { it / 1e9 } ?: 4.0
        val software = "llvmpipe" in name || "softpipe" in name || "swiftshader" in name || "software" in name
        val tier = when {
            software -> QualityTier.LOW
            cores >= 8 && memoryGb >= 12 -> QualityTier.HIGH
            cores >= 4 && memoryGb >= 6 -> QualityTier.MEDIUM
            else -> QualityTier.LOW
        }
        Log.i("ApogeeQuality", "quality=$tier memory=${"%.1f".format(memoryGb)}GB cores=$cores renderer='${world.rendererName}'")
        return tier
    }

    override fun perfHints(): PerfHints? = null

    override fun fullscreen(on: Boolean) {}

    override val canFullScreen = true

    override val isFullScreen: Boolean get() = window?.placement == WindowPlacement.Fullscreen

    override fun setFullScreen(on: Boolean) {
        val state = window ?: return
        if (on == isFullScreen) return
        if (on) {
            before = state.placement
            state.placement = WindowPlacement.Fullscreen
        } else {
            state.placement = before
        }
        settings.fullScreen = on
    }

    override fun shareCraft(name: String, text: String) = save(name, "craft", ".apogee.json", text)

    override fun shareWorld(name: String, text: String) = save(name, "universe", ".apogee-world.json", text)

    override fun pickSharedCraft() {
        val dialog = FileDialog(frame, "Open a file", FileDialog.LOAD)
        dialog.directory = lastFolder.path
        dialog.isVisible = true
        val file = dialog.file?.let { File(dialog.directory, it) } ?: return
        lastFolder = file.parentFile
        app.importFile(runCatching { file.readText() }.getOrNull(), file.name)
    }

    /** Where the file dialogs open: the last folder used, at first the home folder. */
    private var lastFolder = File(System.getProperty("user.home"))

    /** Asks where to save [text], under a name made from [name]. */
    private fun save(name: String, fallback: String, suffix: String, text: String) {
        val safe = name.map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("").trim('-').ifBlank { fallback }
        val dialog = FileDialog(frame, "Save $name", FileDialog.SAVE)
        dialog.directory = lastFolder.path
        dialog.file = "$safe$suffix"
        dialog.isVisible = true
        val file = dialog.file?.let { File(dialog.directory, it) } ?: return
        lastFolder = file.parentFile
        runCatching { file.writeText(text) }
            .onSuccess { toast("Saved ${file.name}") }
            .onFailure { toast("Couldn't save ${file.name}") }
    }

    /** The note on screen now, if any, which the window shows at the bottom. */
    var note: String? by mutableStateOf(null)
        private set
    private var noteJob: Job? = null

    override fun toast(message: String) = runOnMain {
        note = message
        noteJob?.cancel()
        noteJob = scope.launch { delay(3500); note = null }
    }

    override fun debugSwitch(name: String): Boolean = File(data, name).exists()

    override fun timeNow(): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())

    override fun dateTime(epochMillis: Long): String =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(epochMillis))

    override fun runOnMain(block: () -> Unit) = com.rm.apogee.platform.runOnMain(block)

    override fun quit() = onQuit()
}

/** Part pictures kept as PNG files under [root]. */
private class PngPictures(private val root: File) : PictureStore {
    override fun load(folder: String, key: String): ImageBitmap? {
        val file = File(File(root, folder), "$key.png")
        if (!file.exists()) return null
        return runCatching { Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
    }

    override fun save(folder: String, key: String, argb: IntArray, size: Int) {
        val dir = File(root, folder).also { it.mkdirs() }
        // Packed ARGB ints are B, G, R, A bytes in memory order.
        val bytes = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val c = argb[i]
            bytes[i * 4] = c.toByte()
            bytes[i * 4 + 1] = (c shr 8).toByte()
            bytes[i * 4 + 2] = (c shr 16).toByte()
            bytes[i * 4 + 3] = (c ushr 24).toByte()
        }
        val image = Image.makeRaster(ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, size * 4)
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: return
        File(dir, "$key.png").writeBytes(png.bytes)
        image.close()
    }
}

/**
 * Watches the network for games and keeps a live list, one entry per address and port, dropping
 * hosts that stop announcing.
 */
private class DesktopServerBrowser(private val localCatalogHash: String) : ServerBrowser {
    override var servers: List<DiscoveredServer> by mutableStateOf(emptyList())
        private set

    override var scanning: Boolean by mutableStateOf(false)
        private set

    private val found = LinkedHashMap<String, DiscoveredServer>()
    private var listenJob: Job? = null
    private var expiryJob: Job? = null

    override fun start(scope: CoroutineScope) {
        if (listenJob != null) return
        scanning = true
        listenJob = LanDiscovery.listen(scope) { beacon ->
            synchronized(found) {
                val key = DiscoveredServer(beacon, 0L).key
                found[key] = DiscoveredServer.heard(found[key], beacon, System.currentTimeMillis())
                servers = found.values.sortedBy { it.beacon.serverName }
            }
        }
        expiryJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1_000L)
                // Hosts announce once a second, so this is three missed rounds.
                val cutoff = System.currentTimeMillis() - 4_000L
                synchronized(found) {
                    if (found.entries.removeAll { it.value.lastSeenMillis < cutoff }) {
                        servers = found.values.sortedBy { it.beacon.serverName }
                    }
                }
            }
        }
    }

    override fun stop() {
        scanning = false
        listenJob?.cancel(); listenJob = null
        expiryJob?.cancel(); expiryJob = null
        synchronized(found) { found.clear() }
        servers = emptyList()
    }

    override fun reasonFor(server: DiscoveredServer): String? = server.incompatibilityReason(localCatalogHash)
}
