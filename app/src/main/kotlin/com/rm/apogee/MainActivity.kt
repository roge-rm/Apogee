package com.rm.apogee

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
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
 * The Android host for [ApogeeApp]: one FrameLayout holding the 3D surface and, above it, one
 * ComposeView holding every screen. The app itself is in :shared, the same as in a browser; this
 * only gives it Android's files, surface, touches, share sheet and system bars.
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
     * Every touch, wherever it lands (the view, a control, a dialog), wakes the flight controls
     * from their idle fade. It's only watched, never taken.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::app.isInitialized) app.touched()
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // The builder picks and pans in pixels, so it needs the view's size from the start, not
        // only once a finger has touched it.
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            gestures.size(v.width.toFloat(), v.height.toFloat())
        }
        surfaceView = view
    }

    override fun hideSurface() {
        surfaceView?.let { findViewById<FrameLayout>(R.id.game_surface_host).removeView(it) }
        surfaceView = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createSurfaceView(renderer: GlRenderer, gestures: WorldGestures): GLSurfaceView {
        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            // Keeping the context across pauses saves rebuilding every mesh and shader each time
            // the player checks a notification.
            preserveEGLContextOnPause = true
            // 4x multisampling where the device has it. Without it every facet edge is a hard pixel
            // staircase, and as the camera moves the staircases crawl, which on faceted ground is a
            // shimmer across the whole landscape. Tile-based mobile GPUs resolve MSAA on chip, so
            // it costs little, and a device without it falls back to none.
            setEGLConfigChooser(MultisampleConfigChooser)
            setRenderer(GlSurfaceRenderer(renderer))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        // Camera gestures are handled on the surface itself instead of in Compose, so a drag over
        // the 3D world doesn't have to travel through the overlay's hit testing to get here.
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

    override fun perfHints(): PerfHints = AndroidPerfHints.create(
        context = this,
        threadIds = intArrayOf(android.os.Process.myTid()),
        targetWorkNanos = TARGET_FRAME_NANOS,
    )

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
        surfaceView?.onPause()
        app.pause()
    }

    override fun onStop() {
        super.onStop()
        app.hidden()
    }

    override fun onResume() {
        super.onResume()
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

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Let the window into the display cutout as well.
        //
        // Hiding the bars isn't enough on its own. By default the window is laid out clear of the
        // cutout, so the scene stopped 136px short of the edge and the gap was drawn black, down
        // the side in landscape and across the top in portrait. Every HUD control already applies
        // WindowInsets.displayCutout itself, so nothing ends up under the notch. Only the 3D view
        // reaches into it, which is where a fullscreen game wants it.
        setCutoutMode(fillCutout = true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        // Back to the default. Menus are ordinary layouts and should sit clear of the notch instead
        // of having a title disappear behind it.
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

/**
 * Picks an RGBA8888 config with 24-bit depth and 4x multisampling if the device offers one, and
 * without multisampling otherwise.
 */
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
