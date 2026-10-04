package com.rm.apogee

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.hardware.input.InputManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rm.apogee.core.FileFolder
import com.rm.apogee.core.Folder
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.game.AndroidServerBrowser
import com.rm.apogee.game.ServerBrowser
import com.rm.apogee.input.PadMode
import com.rm.apogee.platform.AndroidPerfHints
import com.rm.apogee.platform.PerfHints
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.GlSurfaceRenderer
import com.rm.apogee.render.PictureStore
import com.rm.apogee.render.PngPictures
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.detect
import com.rm.apogee.settings.GameSettings
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * The Android host for [ApogeeApp]: a FrameLayout with the 3D surface and one ComposeView over it
 * for every screen. The app lives in :shared; this gives it Android's files, surface, touches,
 * share sheet and system bars.
 */
class MainActivity : ComponentActivity(), AppHost {

    private lateinit var app: ApogeeApp
    private var surfaceView: GLSurfaceView? = null

    override val scope: CoroutineScope get() = lifecycleScope
    override lateinit var settings: GameSettings
        private set
    override val pictures: PictureStore by lazy { PngPictures(cacheDir) }
    override lateinit var serverBrowser: ServerBrowser
        private set

    override fun folder(name: String): Folder = FileFolder(File(filesDir, name))

    /**
     * Any touch anywhere wakes the flight controls from their idle fade. Only watched, never
     * consumed.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::app.isInitialized) app.touched()
        return super.dispatchTouchEvent(ev)
    }

    // --- a controller ----------------------------------------------------------

    private val gamepad = GamepadReader()

    /**
     * Controller buttons. In flight they fly the craft and nothing else sees them. In menus (or
     * over a panel in flight) the D-pad moves, A presses and B goes back. Android usually maps A to
     * D-pad centre and B to Back itself; this covers controllers that don't.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::app.isInitialized || !gamepad.key(event)) return super.dispatchKeyEvent(event)
        app.pad.update(gamepad.state)
        event.device?.takeIf { !it.isVirtual }?.let { app.controllerName = it.name }
        if (app.padMode() == PadMode.FLIGHT) return true
        // Only the D-pad, A and B work the menus. Others are blocked so Android's stand-ins (Y as
        // Back, stick clicks as presses) don't fire, which matters on the controller page.
        val navigates = event.keyCode == KeyEvent.KEYCODE_BUTTON_A || event.keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT
        if (!navigates) return true
        if (super.dispatchKeyEvent(event)) return true
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> super.dispatchKeyEvent(
                KeyEvent(
                    event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                    event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
                ),
            )
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> false
        }
    }

    /** A controller's sticks and triggers. In the menus, Android turns the left stick into the D-pad. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::app.isInitialized && gamepad.motion(event)) {
            app.pad.update(gamepad.state)
            if (app.padMode() == PadMode.FLIGHT) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Controllers coming and going, for the settings, and so a pulled one doesn't stay held. */
    private val controllers = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshController()
        override fun onInputDeviceChanged(deviceId: Int) = refreshController()
        override fun onInputDeviceRemoved(deviceId: Int) {
            gamepad.clear()
            app.pad.update(gamepad.state)
            refreshController()
        }
    }

    private fun refreshController() {
        app.controllerName = GamepadReader.connectedName()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.PERF) PerfKit.start(this)
        settings = GameSettings(this)
        serverBrowser = AndroidServerBrowser(this, StockParts.catalog.contentHash)
        app = ApogeeApp(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        // A craft file this was opened with, once everything's set up.
        window.decorView.post { handleSharedIntent(intent) }
        findViewById<ComposeView>(R.id.hud_compose_view).setContent { app.Content() }
    }

    // --- the world's surface -------------------------------------------------

    override fun showSurface(renderer: GlRenderer, gestures: WorldGestures) {
        val view = createSurfaceView(renderer, gestures)
        findViewById<FrameLayout>(R.id.game_surface_host).addView(view)
        // The builder picks and pans in pixels, so it needs the view's size before the first touch.
        view.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            gestures.size(v.width.toFloat(), v.height.toFloat())
            // A new or rotated view draws at the requested resolution.
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fitSurface(view)
        }
        surfaceView = view
    }

    override val canScaleRender: Boolean get() = true

    private var renderScale = 1.0

    /**
     * Draws the 3D view at [scale] of screen resolution; the display's scaler stretches it for
     * free. The HUD is a separate view, so it stays sharp.
     */
    override fun renderAt(scale: Double) {
        renderScale = scale
        surfaceView?.let { fitSurface(it) }
    }

    private fun fitSurface(view: GLSurfaceView) {
        if (renderScale >= 1.0 || view.width == 0) view.holder.setSizeFromLayout()
        else view.holder.setFixedSize((view.width * renderScale).toInt().coerceAtLeast(1), (view.height * renderScale).toInt().coerceAtLeast(1))
    }

    override fun hideSurface() {
        surfaceView?.let { findViewById<FrameLayout>(R.id.game_surface_host).removeView(it) }
        surfaceView = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createSurfaceView(renderer: GlRenderer, gestures: WorldGestures): GLSurfaceView {
        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            // Keep the context across pauses, so meshes and shaders aren't rebuilt.
            preserveEGLContextOnPause = true
            // 4x MSAA where the device has it, else none. Without it facet edges crawl as the
            // camera moves. Tile-based GPUs resolve it on chip, so it's cheap.
            setEGLConfigChooser(MultisampleConfigChooser)
            setRenderer(GlSurfaceRenderer(renderer))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        // Camera gestures go straight to the surface, skipping Compose hit testing.
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                gestures.zoom(detector.scaleFactor)
                return true
            }
        })

        view.setOnTouchListener { v, event ->
            gestures.size(v.width.toFloat(), v.height.toFloat())
            val building = app.builderSession != null && app.session == null
            if (!building) pinch.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestures.down(event.x, event.y, event.eventTime)
                    if (building) v.postDelayed({ gestures.tick(android.os.SystemClock.uptimeMillis()) }, com.rm.apogee.game.BuilderGestures.LONG_PRESS + 20)
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                    gestures.secondDown(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                    gestures.move(event.getX(0), event.getY(0), event.getX(1), event.getY(1), measurePinch = false)
                } else if (!pinch.isInProgress) {
                    gestures.move(event.x, event.y)
                }
                MotionEvent.ACTION_POINTER_UP -> gestures.secondUp()
                MotionEvent.ACTION_UP -> gestures.up(event.x, event.y, event.eventTime)
                MotionEvent.ACTION_CANCEL -> gestures.cancel()
            }
            true
        }
        return view
    }

    override fun detectTier(): QualityTier = QualityTier.detect(this)

    override fun perfHints(): PerfHints = AndroidPerfHints.create(context = this, targetWorkNanos = TARGET_FRAME_NANOS)

    // --- sharing craft ------------------------------------------------------------

    override fun shareCraft(name: String, text: String) {
        val folder = File(cacheDir, "shared").apply { mkdirs() }
        val safe = name.map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("").trim('-').ifBlank { "craft" }
        val file = File(folder, "$safe.apogee.json")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "$name, an Apogee craft")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share $name"))
    }

    /** The system's file picker, for a craft file someone sent. */
    private val openShared = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { app.importCraft(read(it)) }
    }

    override fun pickSharedCraft() {
        openShared.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
    }

    private fun read(uri: Uri): String? =
        runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()

    /** A craft file sent or opened from outside the app, if that's what [intent] is. */
    private fun handleSharedIntent(intent: Intent?) {
        intent ?: return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        app.importCraft(read(uri))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Debug switches, as files in the app's own storage so adb can flip them mid-flight. */
    override fun debugSwitch(name: String): Boolean = File(filesDir, name).exists()

    override fun timeNow(): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())

    override fun runOnMain(block: () -> Unit) = runOnUiThread(block)

    // --- lifecycle -----------------------------------------------------------

    override fun onPause() {
        super.onPause()
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(controllers)
        surfaceView?.onPause()
        app.pause()
    }

    override fun onStop() {
        super.onStop()
        app.hidden()
    }

    override fun onResume() {
        super.onResume()
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(controllers, null)
        refreshController()
        surfaceView?.onResume()
        app.resume()
    }

    override fun onDestroy() {
        app.destroy()
        super.onDestroy()
    }

    // --- system bars ---------------------------------------------------------

    override fun fullscreen(on: Boolean) = if (on) hideSystemBars() else showSystemBars()

    override val fullscreenMenus: Boolean get() = true

    override val canQuit: Boolean get() = true

    override fun quit() = finishAndRemoveTask()

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Let the window into the display cutout too, or the scene stops short and the gap is
        // black. HUD controls apply WindowInsets.displayCutout themselves, so only the 3D view
        // reaches under the notch.
        setCutoutMode(fillCutout = true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        // Back to the default, so menu titles stay clear of the notch.
        setCutoutMode(fillCutout = false)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /** API 28+. On 27 the window just has no cutout to deal with. */
    private fun setCutoutMode(fillCutout: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (fillCutout) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }

    private companion object {
        /** The 60 fps budget, for the ADPF hint. */
        const val TARGET_FRAME_NANOS = 16_666_667L
    }
}

/** An RGBA8888 config with 24-bit depth and 4x multisampling if offered, else without. */
private object MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(
        egl: javax.microedition.khronos.egl.EGL10,
        display: javax.microedition.khronos.egl.EGLDisplay,
    ): javax.microedition.khronos.egl.EGLConfig {
        fun find(samples: Int): javax.microedition.khronos.egl.EGLConfig? {
            val attributes = intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 24,
                // EGL_OPENGL_ES3_BIT_KHR: a config GLES 3 can use.
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 0x40,
                javax.microedition.khronos.egl.EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                javax.microedition.khronos.egl.EGL10.EGL_SAMPLES, samples,
                javax.microedition.khronos.egl.EGL10.EGL_NONE,
            )
            val count = IntArray(1)
            val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
            if (!egl.eglChooseConfig(display, attributes, configs, 1, count) || count[0] == 0) return null
            return configs[0]
        }
        return find(4) ?: find(0) ?: throw IllegalStateException("No usable EGL config")
    }
}
