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
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.game.BuilderSession
import com.rm.apogee.game.DiscoveredServer
import com.rm.apogee.game.ServerBrowser
import com.rm.apogee.game.GameSession
import com.rm.apogee.game.HudState
import com.rm.apogee.platform.PerfHints
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.QualityTier
import com.rm.apogee.settings.GameSettings
import com.rm.apogee.ui.screens.AboutScreen
import com.rm.apogee.ui.screens.AppScreen
import com.rm.apogee.ui.screens.BuilderScreen
import com.rm.apogee.ui.screens.FlightScreen
import com.rm.apogee.ui.screens.HostGameScreen
import com.rm.apogee.ui.screens.JoinGameScreen
import com.rm.apogee.ui.screens.MainMenuScreen
import com.rm.apogee.ui.screens.PlayScreen
import com.rm.apogee.ui.screens.SettingsScreen
import com.rm.apogee.ui.theme.ApogeeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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

    private lateinit var craftStore: CraftStore

    private var surfaceView: GLSurfaceView? = null
    private var renderer: GlRenderer? = null
    private var session: GameSession? = null
    private var builderSession: BuilderSession? = null

    /** Set by the builder's Launch button; consumed when flight starts. */
    private var pendingLaunchDesign: CraftDesign? = null
    private var frameClockJob: Job? = null
    private var perfHints: PerfHints? = null
    private var rendererTerrainSource: com.rm.apogee.render.TerrainSource? = null

    // Held between updates because pitch/yaw and roll arrive from different
    // controls but are sent as one command.
    private var commandedPitch = 0f
    private var commandedYaw = 0f
    private var commandedRoll = 0f

    private lateinit var serverBrowser: ServerBrowser

    /** How the next flight should be started. */
    private var pendingMode: SessionMode = SessionMode.Solo
    private var joinError by mutableStateOf<String?>(null)
    private var connectingTo by mutableStateOf<String?>(null)
    private var serverName by mutableStateOf("")

    private var appScreen by mutableStateOf(AppScreen.MENU)
    private var detectedTier by mutableStateOf<QualityTier?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = GameSettings(this)
        hudState = HudState()
        frameBus = FrameBus()
        craftStore = CraftStore(File(filesDir, "craft"))
        // So there is something to fly, and something to land, before the
        // player has built anything.
        craftStore.seedStockDesigns(StockParts.catalog)
        serverBrowser = ServerBrowser(this, StockParts.catalog.contentHash)
        serverName = "${settings.playerName}'s Game"
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
                        onAttitude = ::onAttitude,
                        onRoll = ::onRoll,
                        onStage = ::onStage,
                        onToggleSas = ::onToggleSas,
                        onToggleMap = ::onToggleMap,
                        onExit = { navigateTo(AppScreen.PLAY) },
                    )
                    AppScreen.BUILDER -> builderSession?.let { builder ->
                        BuilderScreen(
                            session = builder,
                            catalog = StockParts.catalog,
                            onExit = { navigateTo(AppScreen.PLAY) },
                            onLaunch = ::launchFromBuilder,
                        )
                    }
                    AppScreen.HOST_GAME -> HostGameScreen(
                        serverName = serverName,
                        onServerNameChange = { serverName = it },
                        onStartHosting = {
                            pendingMode = SessionMode.Host(serverName.ifBlank { "Apogee Game" })
                            navigateTo(AppScreen.FLIGHT)
                        },
                    )

                    AppScreen.JOIN_GAME -> JoinGameScreen(
                        browser = serverBrowser,
                        connectingTo = connectingTo,
                        error = joinError,
                        onJoin = ::joinServer,
                    )
                }
            }
        }
    }

    private fun navigateTo(target: AppScreen) {
        if (target == appScreen) return
        val wasInWorld = appScreen.needsWorldSurface
        val wasBrowsing = appScreen == AppScreen.JOIN_GAME
        appScreen = target

        // Discovery holds a multicast lock and a socket; it runs only while the
        // browser is actually on screen.
        if (target == AppScreen.JOIN_GAME) {
            joinError = null
            serverBrowser.start(lifecycleScope)
        } else if (wasBrowsing) {
            serverBrowser.stop()
        }

        when {
            // Builder and flight both want the surface but different sessions,
            // so moving between them tears down and rebuilds rather than
            // trying to hand one session's state to the other.
            target.needsWorldSurface && wasInWorld -> {
                leaveWorld()
                enterWorld(target)
            }
            target.needsWorldSurface -> enterWorld(target)
            wasInWorld -> leaveWorld()
        }
    }

    private fun launchFromBuilder() {
        pendingLaunchDesign = builderSession?.designForLaunch() ?: return
        pendingMode = SessionMode.Solo
        navigateTo(AppScreen.FLIGHT)
    }

    /**
     * Connects to a discovered host, then enters flight.
     *
     * The connection is made *before* navigating, so a host that has gone away
     * produces an error on the list where the player can pick another, rather
     * than dropping them into an empty world to work it out themselves.
     */
    private fun joinServer(server: DiscoveredServer) {
        if (connectingTo != null) return
        connectingTo = server.beacon.serverName
        joinError = null

        lifecycleScope.launch {
            val result = GameSession.join(
                frameBus = frameBus,
                perfHints = null,
                playerName = settings.playerName,
                host = server.beacon.address,
                port = server.beacon.port,
            )
            connectingTo = null
            result
                .onSuccess { joined ->
                    pendingMode = SessionMode.Joined(joined)
                    navigateTo(AppScreen.FLIGHT)
                }
                .onFailure { joinError = "Could not connect: ${it.message ?: "host unreachable"}" }
        }
    }

    // --- flight controls -----------------------------------------------------

    private fun onThrottleChange(value: Float) {
        hudState.throttle = value
        val current = session ?: return
        lifecycleScope.launch { current.setThrottle(value.toDouble()) }
    }

    /**
     * Attitude input.
     *
     * Pitch and yaw come from the stick and roll from its buttons, but they
     * travel as one command - the server takes all three axes together, and
     * splitting them would let a stick update arrive between a roll press and
     * its release and silently cancel it.
     */
    private fun onAttitude(pitch: Float, yaw: Float) {
        commandedPitch = pitch
        commandedYaw = yaw
        sendAttitude()
    }

    private fun onRoll(roll: Float) {
        commandedRoll = roll
        sendAttitude()
    }

    private fun sendAttitude() {
        val current = session ?: return
        val pitch = commandedPitch.toDouble()
        val yaw = commandedYaw.toDouble()
        val roll = commandedRoll.toDouble()
        lifecycleScope.launch { current.setAttitude(pitch, yaw, roll) }
    }

    private fun onToggleMap() {
        val current = session ?: return
        val enabled = !hudState.mapMode
        hudState.mapMode = enabled
        current.mapMode = enabled
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

    private fun enterWorld(screen: AppScreen) {
        hideSystemBars()
        hudState.reset()
        commandedPitch = 0f; commandedYaw = 0f; commandedRoll = 0f

        val host = findViewById<FrameLayout>(R.id.game_surface_host)
        val glRenderer = GlRenderer(this, frameBus) { tier ->
            // Arrives on the GL thread. The terrain builder needs the tier to
            // know how finely to sample, so it is created here rather than
            // guessed at earlier.
            settings.lastDetectedTier = tier
            detectedTier = tier
            session?.attachTerrain(rendererTerrainSource!!, settings.qualityOverride ?: tier)
        }
        rendererTerrainSource = glRenderer.terrainSource
        val view = createSurfaceView(glRenderer)
        host.addView(view)

        renderer = glRenderer
        surfaceView = view

        perfHints = PerfHints.create(
            context = this,
            threadIds = intArrayOf(android.os.Process.myTid()),
            targetWorkNanos = TARGET_FRAME_NANOS,
        )

        if (screen == AppScreen.BUILDER) {
            val builder = BuilderSession(frameBus, StockParts.catalog, craftStore)
            builder.start(lifecycleScope)
            builderSession = builder
        } else {
            val newSession = when (val mode = pendingMode) {
                is SessionMode.Solo -> GameSession.hostLocal(
                    frameBus = frameBus,
                    perfHints = perfHints,
                    playerName = settings.playerName,
                    design = pendingLaunchDesign,
                    scope = lifecycleScope,
                )

                is SessionMode.Host -> GameSession.hostLan(
                    frameBus = frameBus,
                    perfHints = perfHints,
                    playerName = settings.playerName,
                    serverName = mode.name,
                    design = pendingLaunchDesign,
                    scope = lifecycleScope,
                )

                // Already connected: joining happens before navigation so a
                // failure can be shown on the browser instead of in an empty world.
                is SessionMode.Joined -> mode.session
            }
            pendingLaunchDesign = null
            pendingMode = SessionMode.Solo
            rendererTerrainSource?.let { source ->
                newSession.attachTerrain(source, settings.qualityOverride ?: detectedTier
                    ?: QualityTier.MEDIUM)
            }
            newSession.start(lifecycleScope)
            session = newSession
        }

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
        var downX = 0f
        var downY = 0f
        var downTime = 0L

        view.setOnTouchListener { v, event ->
            pinch.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    downX = event.x; downY = event.y
                    downTime = event.eventTime
                }

                MotionEvent.ACTION_MOVE -> if (!pinch.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x; lastY = event.y
                    activeCamera()?.orbitBy(
                        deltaYaw = -dx * ORBIT_RADIANS_PER_PIXEL,
                        deltaPitch = dy * ORBIT_RADIANS_PER_PIXEL,
                    )
                }

                MotionEvent.ACTION_UP -> {
                    // A tap is a touch that neither travelled nor lingered. The
                    // slop has to be generous: on a phone, a finger placed to
                    // tap always moves a few pixels, and treating that as a drag
                    // makes placing a part feel broken.
                    val travelled = kotlin.math.hypot(event.x - downX, event.y - downY)
                    val duration = event.eventTime - downTime
                    if (travelled < TAP_SLOP_PIXELS && duration < TAP_TIMEOUT_MILLIS) {
                        builderSession?.tap(
                            event.x, event.y,
                            v.width.toFloat(), v.height.toFloat(),
                        )
                    }
                }
            }
            true
        }
        return view
    }

    /** Whichever camera the current view is looking through. */
    private fun activeCamera(): com.rm.apogee.game.CameraController? {
        session?.let { return if (it.mapMode) it.mapCamera else it.camera }
        return builderSession?.camera
    }

    private fun leaveWorld() {
        frameClockJob?.cancel(); frameClockJob = null

        session?.stop(); session = null
        builderSession?.stop(); builderSession = null
        perfHints?.close(); perfHints = null

        surfaceView?.let { findViewById<FrameLayout>(R.id.game_surface_host).removeView(it) }
        surfaceView = null
        renderer = null
        rendererTerrainSource = null

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
                val glRenderer = renderer ?: continue
                hudState.frameTimeMillis = glRenderer.lastFrameTimeNanos.get() / 1_000_000f

                session?.let { current ->
                    hudState.frameBuildMillis = current.lastFrameBuildNanos.get() / 1_000_000f
                    hudState.telemetry = current.telemetry
                    hudState.connecting = !current.connected && current.rejectionReason == null
                    hudState.connectionError = current.rejectionReason
                }

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
        serverBrowser.stop()
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

    /** How the next flight should be started. */
    private sealed interface SessionMode {
        data object Solo : SessionMode
        data class Host(val name: String) : SessionMode
        data class Joined(val session: GameSession) : SessionMode
    }

    private companion object {
        /** 60 fps budget, for the ADPF hint. */
        const val TARGET_FRAME_NANOS = 16_666_667L

        const val ORBIT_RADIANS_PER_PIXEL = 0.005

        /** How far a touch may travel and still count as a tap. */
        const val TAP_SLOP_PIXELS = 28f
        const val TAP_TIMEOUT_MILLIS = 400L
    }
}
