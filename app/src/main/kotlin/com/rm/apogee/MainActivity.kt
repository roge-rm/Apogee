package com.rm.apogee

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rm.apogee.game.GameSession
import com.rm.apogee.game.HudState
import com.rm.apogee.platform.PerfHints
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.QualityTier
import com.rm.apogee.settings.GameSettings
import com.rm.apogee.ui.screens.AboutScreen
import com.rm.apogee.ui.screens.AppScreen
import com.rm.apogee.ui.screens.FlightScreen
import com.rm.apogee.ui.screens.MainMenuScreen
import com.rm.apogee.ui.screens.PlayScreen
import com.rm.apogee.ui.screens.SettingsScreen
import com.rm.apogee.ui.theme.ApogeeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Host for the whole app: one FrameLayout holding the 3D surface and, above it,
 * one ComposeView holding every screen.
 *
 * Kept deliberately thin. The simulation lives in :core, the session wiring in
 * [GameSession], the render path in [GlRenderer], and observable UI state in
 * [HudState]; this class only connects them and decides which screen is up.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: GameSettings
    private lateinit var hudState: HudState
    private lateinit var frameBus: FrameBus

    private var surfaceView: GLSurfaceView? = null
    private var renderer: GlRenderer? = null
    private var session: GameSession? = null
    private var frameClockJob: Job? = null
    private var perfHints: PerfHints? = null

    private var appScreen by mutableStateOf(AppScreen.MENU)
    private var detectedTier by mutableStateOf<QualityTier?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = GameSettings(this)
        hudState = HudState()
        frameBus = FrameBus()
        detectedTier = settings.lastDetectedTier

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        findViewById<ComposeView>(R.id.hud_compose_view).setContent {
            ApogeeTheme {
                // Back is hand-wired from AppScreen.parent. Flight deliberately
                // swallows it so a stray gesture cannot discard a flight.
                BackHandler(enabled = appScreen.parent != null) {
                    navigateTo(appScreen.parent ?: AppScreen.MENU)
                }

                when (appScreen) {
                    AppScreen.MENU -> MainMenuScreen(::navigateTo)
                    AppScreen.PLAY -> PlayScreen(::navigateTo)
                    AppScreen.SETTINGS -> SettingsScreen(settings, detectedTier)
                    AppScreen.ABOUT -> AboutScreen()
                    AppScreen.FLIGHT -> FlightScreen(
                        hud = hudState,
                        controlOpacity = settings.controlOpacity,
                        showDebugOverlay = settings.showDebugOverlay,
                        leftHandMode = settings.leftHandMode,
                        onThrottleChange = ::onThrottleChange,
                        onStage = ::onStage,
                        onToggleSas = ::onToggleSas,
                        onExit = { navigateTo(AppScreen.PLAY) },
                    )
                    // M2/M4 screens; the enum carries them so navigation and
                    // back-handling are already correct when they land.
                    AppScreen.BUILDER, AppScreen.HOST_GAME, AppScreen.JOIN_GAME ->
                        PlayScreen(::navigateTo)
                }
            }
        }
    }

    private fun navigateTo(target: AppScreen) {
        if (target == appScreen) return
        val wasInWorld = appScreen.needsWorldSurface
        appScreen = target

        if (target.needsWorldSurface && !wasInWorld) {
            enterWorld()
        } else if (!target.needsWorldSurface && wasInWorld) {
            leaveWorld()
        }
    }

    // --- flight controls -----------------------------------------------------

    private fun onThrottleChange(value: Float) {
        hudState.throttle = value
        val current = session ?: return
        lifecycleScope.launch { current.setThrottle(value.toDouble()) }
    }

    private fun onStage() {
        val current = session ?: return
        lifecycleScope.launch { current.stage() }
    }

    private fun onToggleSas() {
        val enabled = !hudState.sasEnabled
        hudState.sasEnabled = enabled
        val current = session ?: return
        lifecycleScope.launch { current.setSas(enabled) }
    }

    // --- the 3D world's lifecycle -------------------------------------------

    private fun enterWorld() {
        hideSystemBars()
        hudState.reset()

        val host = findViewById<FrameLayout>(R.id.game_surface_host)
        val glRenderer = GlRenderer(this, frameBus) { tier ->
            // Arrives on the GL thread.
            settings.lastDetectedTier = tier
            detectedTier = tier
        }
        val view = createSurfaceView(glRenderer)
        host.addView(view)

        renderer = glRenderer
        surfaceView = view

        perfHints = PerfHints.create(
            context = this,
            threadIds = intArrayOf(android.os.Process.myTid()),
            targetWorkNanos = TARGET_FRAME_NANOS,
        )

        val newSession = GameSession.hostLocal(
            frameBus = frameBus,
            perfHints = perfHints,
            playerName = settings.playerName,
            scope = lifecycleScope,
        )
        newSession.start(lifecycleScope)
        session = newSession

        frameClockJob = startFrameClock()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createSurfaceView(glRenderer: GlRenderer): GLSurfaceView {
        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            // Keeping the context across pauses avoids rebuilding every mesh
            // and shader each time the player checks a notification.
            preserveEGLContextOnPause = true
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            setRenderer(glRenderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        // Camera gestures are handled on the surface itself rather than in
        // Compose, so a drag over the 3D world does not have to travel through
        // the overlay's hit testing to get here.
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                session?.camera?.zoomBy(detector.scaleFactor.toDouble())
                return true
            }
        })

        var lastX = 0f
        var lastY = 0f
        view.setOnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> if (!pinch.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x; lastY = event.y
                    session?.camera?.orbitBy(
                        deltaYaw = -dx * ORBIT_RADIANS_PER_PIXEL,
                        deltaPitch = dy * ORBIT_RADIANS_PER_PIXEL,
                    )
                }
            }
            true
        }
        return view
    }

    private fun leaveWorld() {
        frameClockJob?.cancel(); frameClockJob = null

        session?.stop(); session = null
        perfHints?.close(); perfHints = null

        surfaceView?.let { findViewById<FrameLayout>(R.id.game_surface_host).removeView(it) }
        surfaceView = null
        renderer = null

        frameBus.clear()
        hudState.reset()
        showSystemBars()
    }

    /**
     * A display-rate loop for overlay values that must not lag the world.
     *
     * Uses [AndroidUiDispatcher.CurrentThread] specifically because that
     * dispatcher carries the MonotonicFrameClock that [withFrameNanos] needs -
     * a plain main-thread scope throws. Anything screen-space (projected
     * labels, attach-node markers, the map cursor) has to be refreshed here
     * rather than on the simulation's slower cadence, or it visibly trails the
     * camera whenever the view moves.
     */
    private fun startFrameClock(): Job =
        CoroutineScope(AndroidUiDispatcher.CurrentThread).launch {
            while (isActive) {
                withFrameNanos { }
                val current = session ?: continue
                val glRenderer = renderer ?: continue

                hudState.frameTimeMillis = glRenderer.lastFrameTimeNanos.get() / 1_000_000f
                hudState.frameBuildMillis = current.lastFrameBuildNanos.get() / 1_000_000f
                hudState.telemetry = current.telemetry
                hudState.connecting = !current.connected && current.rejectionReason == null
                hudState.connectionError = current.rejectionReason

                frameBus.latest()?.latest?.let { frame ->
                    hudState.simTick = frame.simTick
                    hudState.drawnItems = frame.items.size
                }
            }
        }

    // --- lifecycle -----------------------------------------------------------

    override fun onPause() {
        super.onPause()
        surfaceView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        surfaceView?.onResume()
        if (appScreen.needsWorldSurface) hideSystemBars()
    }

    override fun onDestroy() {
        leaveWorld()
        super.onDestroy()
    }

    // --- system bars ---------------------------------------------------------

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private companion object {
        /** 60 fps budget, for the ADPF hint. */
        const val TARGET_FRAME_NANOS = 16_666_667L

        const val ORBIT_RADIANS_PER_PIXEL = 0.005
    }
}
