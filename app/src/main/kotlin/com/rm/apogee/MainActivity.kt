package com.rm.apogee

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rm.apogee.game.HudState
import com.rm.apogee.game.SimLoop
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
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

/**
 * Host for the whole app: one FrameLayout holding the 3D surface and, above it,
 * one ComposeView holding every screen.
 *
 * Kept deliberately thin. The reference app this borrows its look from ended up
 * with a 3400-line Activity that was simultaneously navigation host, tick loop,
 * input handler and state bridge, and its own notes call that out as the thing
 * not to repeat. Here the simulation lives in [SimLoop], the render path in
 * [GlRenderer], observable UI state in [HudState], and this class only wires
 * them together and decides which screen is showing.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: GameSettings
    private lateinit var hudState: HudState
    private lateinit var frameBus: FrameBus

    private var surfaceView: GLSurfaceView? = null
    private var renderer: GlRenderer? = null
    private var simLoop: SimLoop? = null
    private var simJob: Job? = null
    private var frameClockJob: Job? = null
    private var perfHints: PerfHints? = null

    private var appScreen by mutableStateOf(AppScreen.MENU)
    private var detectedTier by mutableStateOf<QualityTier?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = GameSettings(this)
        detectedTier = settings.lastDetectedTier
        hudState = HudState()
        frameBus = FrameBus()

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

    // --- the 3D world's lifecycle -------------------------------------------

    private fun enterWorld() {
        hideSystemBars()

        val host = findViewById<FrameLayout>(R.id.game_surface_host)
        val glRenderer = GlRenderer(this, frameBus) { tier ->
            // Arrives on the GL thread.
            settings.lastDetectedTier = tier
            detectedTier = tier
        }
        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            // Keeping the context across pauses avoids rebuilding every mesh
            // and shader each time the player checks a notification.
            preserveEGLContextOnPause = true
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            setRenderer(glRenderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        host.addView(view)

        renderer = glRenderer
        surfaceView = view

        perfHints = PerfHints.create(
            context = this,
            threadIds = intArrayOf(android.os.Process.myTid()),
            targetWorkNanos = SimLoop.DT_NANOS,
        )

        val loop = SimLoop(frameBus, perfHints)
        simLoop = loop
        simJob = loop.start(lifecycleScope)
        frameClockJob = startFrameClock()
    }

    private fun leaveWorld() {
        frameClockJob?.cancel(); frameClockJob = null
        simJob?.cancel(); simJob = null
        simLoop = null

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
                val loop = simLoop ?: continue
                val glRenderer = renderer ?: continue

                hudState.frameTimeMillis = glRenderer.lastFrameTimeNanos.get() / 1_000_000f
                hudState.simStepMillis = loop.lastStepNanos.get() / 1_000_000f
                hudState.simTick = loop.tick.get()
                hudState.drawnItems = frameBus.latest()?.latest?.items?.size ?: 0
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
}
