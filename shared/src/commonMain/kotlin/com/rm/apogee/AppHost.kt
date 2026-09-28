package com.rm.apogee

import com.rm.apogee.core.Folder
import com.rm.apogee.game.ServerBrowser
import com.rm.apogee.platform.PerfHints
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.PictureStore
import com.rm.apogee.render.QualityTier
import com.rm.apogee.settings.GameSettings
import kotlinx.coroutines.CoroutineScope

/**
 * What [ApogeeApp] needs from the platform it runs on: an Android activity, or a web page. Each
 * gives it storage, a surface to draw the world on, and the few things only it can do (sharing a
 * file, the system bars, a performance hint).
 */
interface AppHost {
    /** Where the app's work runs, on the main thread, for as long as the app does. */
    val scope: CoroutineScope

    val settings: GameSettings

    /** A named folder of the app's own files: "craft", "world", and so on. */
    fun folder(name: String): Folder

    /** Where the part pictures are kept between runs. */
    val pictures: PictureStore

    /** Games found on the local network. */
    val serverBrowser: ServerBrowser

    /**
     * Puts a surface up, under the screens, that [renderer] draws on every frame, with its touches
     * (or the mouse's) handed to [gestures].
     */
    fun showSurface(renderer: GlRenderer, gestures: WorldGestures)

    /** Takes the surface down again. */
    fun hideSurface()

    /** The device's quality tier. Called on the GL thread, once there's a context. */
    fun detectTier(): QualityTier

    /** Performance hints for the simulation's thread, where the platform has them. */
    fun perfHints(): PerfHints?

    /** Fullscreen for the world, or back to the ordinary window for the menus. */
    fun fullscreen(on: Boolean)

    /** Sends a craft file somewhere: the share sheet, or a download. */
    fun shareCraft(name: String, text: String)

    /** Asks for a craft file to open, and gives its text to [ApogeeApp.importCraft] if one's chosen. */
    fun pickSharedCraft()

    /** A short note on screen. */
    fun toast(message: String)

    /** Whether the debug switch [name] is on ("debug-perf", "debug-sound", ...). */
    fun debugSwitch(name: String): Boolean

    /** The time now, the short way the device shows it, for a save point. */
    fun timeNow(): String

    /** Runs [block] on the main thread. */
    fun runOnMain(block: () -> Unit)

    /**
     * Whether the world's touches come through Compose ([WorldInputLayer]), because the screens
     * are drawn over the surface and take every pointer event, as on a web page. Android's surface
     * takes its own.
     */
    val worldInputInCompose: Boolean get() = false

    /** Whether games can be hosted and joined: not from a web page, which can't open sockets. */
    val networked: Boolean get() = true
}
