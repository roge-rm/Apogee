package com.rm.apogee

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.os.Build
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
import android.util.Log
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldStore
import com.rm.apogee.game.BuilderSession
import com.rm.apogee.game.BuilderGestures
import com.rm.apogee.game.DiscoveredServer
import com.rm.apogee.game.ServerBrowser
import com.rm.apogee.net.ServerAddress
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
    private var lastBusGains = FloatArray(0)
    private lateinit var hudState: HudState
    private lateinit var frameBus: FrameBus

    private lateinit var craftStore: CraftStore
    private lateinit var soloWorldStore: WorldStore

    /**
     * The single-player world, held across flights.
     *
     * Null until something needs it. Loaded from disk once and written back
     * when the player leaves, so landing a module and coming back with the
     * next one is the same world rather than a new one.
     */
    private var soloWorld: World? = null

    private var surfaceView: GLSurfaceView? = null
    private var renderer: GlRenderer? = null
    private var session: GameSession? = null
    private var builderSession: BuilderSession? = null

    /** The drawer's part pictures, drawn by the renderer once and kept. */
    private val partThumbnails by lazy { com.rm.apogee.render.PartThumbnails(cacheDir) }

    /** Set by the builder's Launch button; consumed when flight starts. */
    private var pendingLaunchDesign: CraftDesign? = null
    private var pendingLaunchSite: String? = null
    private var pendingResume: Long? = null
    private var resumeCraft by mutableStateOf(emptyList<com.rm.apogee.ui.screens.CraftSummary>())
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

    /**
     * What is typed in the join screen's address box. Seeded from the last
     * address that worked, so returning to a server is one tap.
     */
    private var manualAddress by mutableStateOf("")
    private var serverName by mutableStateOf("")

    private var appScreen by mutableStateOf(AppScreen.MENU)
    private var detectedTier by mutableStateOf<QualityTier?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = GameSettings(this)
        hudState = HudState()
        frameBus = FrameBus()
        craftStore = CraftStore(File(filesDir, "craft"))
        // One world, kept on disk, rather than a fresh universe per launch.
        soloWorldStore = WorldStore(File(filesDir, "world/solo.json"))
        // So there is something to fly, and something to land, before the
        // player has built anything.
        craftStore.seedStockDesigns(StockParts.catalog)
        serverBrowser = ServerBrowser(this, StockParts.catalog.contentHash)
        serverName = "${settings.playerName}'s Game"
        detectedTier = settings.lastDetectedTier

        // Sound: as many voices at once as the device's tier can carry.
        com.rm.apogee.audio.AudioEngine.start(
            when (settings.effectiveTier) {
                com.rm.apogee.render.QualityTier.LOW -> 16
                com.rm.apogee.render.QualityTier.HIGH -> 48
                else -> 32
            },
        )
        com.rm.apogee.audio.AudioEngine.busGains(settings.busGains())

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
                    AppScreen.PLAY -> PlayScreen(::navigateTo, settings.launchTime) { settings.launchTime = it }
                    AppScreen.RESUME_FLIGHT -> com.rm.apogee.ui.screens.ResumeFlightScreen(
                        craft = resumeCraft,
                        onFly = { id ->
                            // Claimed, if an older save had it under another name.
                            openSoloWorld().vessel(com.rm.apogee.core.craft.VesselId(id))?.let {
                                it.owner = settings.clientId
                                it.ownerName = settings.playerName
                            }
                            pendingResume = id
                            pendingMode = SessionMode.Solo
                            navigateTo(AppScreen.FLIGHT)
                        },
                        onReset = { id ->
                            openSoloWorld().resetToSite(com.rm.apogee.core.craft.VesselId(id))
                            saveSoloWorld()
                            refreshResumeCraft()
                        },
                        onRemove = { id ->
                            openSoloWorld().destroy(com.rm.apogee.core.craft.VesselId(id), "removed by its owner")
                            saveSoloWorld()
                            refreshResumeCraft()
                        },
                    )
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
                        onSasMode = { mode ->
                            hudState.sasEnabled = true
                            session?.let { s -> lifecycleScope.launch { s.setSasMode(mode) } }
                        },
                        onCycleFrame = {
                            session?.let { s -> lifecycleScope.launch { s.cycleNavFrame() } }
                        },
                        targetChoices = { session?.targetChoices() ?: emptyList() },
                        onTarget = { id -> session?.let { s -> lifecycleScope.launch { s.setTarget(id) } } },
                        onToggleBrakes = ::onToggleBrakes,
                        onToggleRcs = ::onToggleRcs,
                        onToggleReverse = ::onToggleReverse,
                        onUndock = { part -> session?.let { s -> lifecycleScope.launch { s.undock(part) } } },
                        onFound = { founded -> session?.let { s -> lifecycleScope.launch { s.found(founded) } } },
                        onRefuel = { on -> session?.let { s -> lifecycleScope.launch { s.refuel(on) } } },
                        onDockPilot = { who ->
                            session?.let { s ->
                                val shared = s.sharedWith ?: return@let
                                val pilot = when (who) { "me" -> s.myId; "them" -> shared.otherId; else -> "" }
                                lifecycleScope.launch { s.setDockPilot(pilot) }
                            }
                        },
                        onStickMode = ::onStickMode,
                        onToggleMap = ::onToggleMap,
                        onJoin = ::onJoin,
                        onSwitchCraft = ::onSwitchCraft,
                        onExit = { navigateTo(AppScreen.PLAY) },
                        onWarp = { rate -> session?.let { s -> lifecycleScope.launch { s.setWarp(rate) } } },
                        craftChoices = { session?.myCraft() ?: emptyList() },
                        currentCraft = { session?.controlledCraft },
                        onFlyCraft = { id -> session?.let { s -> lifecycleScope.launch { s.flyCraft(id) } } },
                        onRemoveCraft = { id -> session?.let { s -> lifecycleScope.launch { s.removeCraft(id) } } },
                        onRetire = ::onRetire,
                    )
                    AppScreen.BUILDER -> builderSession?.let { builder ->
                        BuilderScreen(
                            session = builder,
                            catalog = StockParts.catalog,
                            settings = settings,
                            pictures = partThumbnails.pictures,
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
                        manualAddress = manualAddress,
                        onManualAddressChange = { manualAddress = it },
                        defaultPort = GameSession.DEFAULT_PORT,
                        onJoin = ::joinServer,
                        onJoinAddress = ::joinAddress,
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
        if (target == AppScreen.RESUME_FLIGHT) refreshResumeCraft()

        // Discovery holds a multicast lock and a socket; it runs only while the
        // browser is actually on screen.
        if (target == AppScreen.JOIN_GAME) {
            joinError = null
            if (manualAddress.isEmpty()) manualAddress = settings.lastServerAddress
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
        pendingLaunchSite = builderSession?.launchSiteId
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
    private fun joinServer(server: DiscoveredServer) =
        connectTo(server.beacon.serverName, server.beacon.address, server.beacon.port)

    /**
     * Connects to an address the player typed.
     *
     * Remembered only once the connection succeeds: an address that failed is
     * as likely to be a typo as a server that is down, and offering it back as
     * the default next time would keep the typo alive.
     */
    private fun joinAddress(address: ServerAddress) {
        connectTo(address.label(GameSession.DEFAULT_PORT), address.host, address.port) {
            settings.lastServerAddress = manualAddress.trim()
        }
    }

    private fun connectTo(
        label: String,
        host: String,
        port: Int,
        onConnected: () -> Unit = {},
    ) {
        if (connectingTo != null) return
        connectingTo = label
        joinError = null

        lifecycleScope.launch {
            val result = GameSession.join(
                frameBus = frameBus,
                perfHints = null,
                playerName = settings.playerName,
                clientId = settings.clientId,
                host = host,
                port = port,
            )
            connectingTo = null
            result
                .onSuccess { joined ->
                    onConnected()
                    pendingMode = SessionMode.Joined(joined)
                    navigateTo(AppScreen.FLIGHT)
                }
                .onFailure {
                    joinError = "Could not reach $label: ${it.message ?: "host unreachable"}"
                }
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
        if (sliding()) {
            // Thrusters armed and the stick set to slide: up is away from
            // the camera, right is its right.
            slideRight = yaw; slideAway = pitch
            sendSlide()
            return
        }
        commandedPitch = pitch
        commandedYaw = yaw
        sendAttitude()
    }

    private fun onRoll(roll: Float) {
        if (sliding()) {
            // The roll buttons become down (left) and up (right) - gently:
            // a button has no half-way, and full thrust was metres a second
            // in the time it took to tap it.
            slideLift = roll * LIFT_BUTTON
            sendSlide()
            return
        }
        commandedRoll = roll
        sendAttitude()
    }

    // The thumbs' slide while the stick is sliding: right, away and up, -1..1.
    private var slideRight = 0f
    private var slideAway = 0f
    private var slideLift = 0f

    private fun sliding(): Boolean = hudState.rcsArmed && hudState.rcsSlide

    private fun sendSlide() {
        session?.setSlide(slideRight.toDouble(), slideAway.toDouble(), slideLift.toDouble())
    }

    /** Lets go of everything the stick and roll buttons were holding, in either mode. */
    private fun releaseStick() {
        commandedPitch = 0f; commandedYaw = 0f; commandedRoll = 0f
        slideRight = 0f; slideAway = 0f; slideLift = 0f
        sendAttitude()
        sendSlide()
    }

    private fun onToggleRcs() {
        val armed = !hudState.rcsArmed
        hudState.rcsArmed = armed
        if (!armed) hudState.rcsSlide = false
        releaseStick()
        session?.setRcs(armed)
    }

    /** The stick turns the craft, or with [slide] slides it on the thrusters. */
    private fun onStickMode(slide: Boolean) {
        if (hudState.rcsSlide == slide) return
        releaseStick()
        hudState.rcsSlide = slide
    }

    private fun sendAttitude() {
        val current = session ?: return
        // Read per command rather than cached, so switching to another craft
        // or changing the setting mid-flight takes effect on the next nudge.
        val reversed = settings.pitchStyle.reverses(current.controlledOrientation)
        val pitch = commandedPitch.toDouble() * if (reversed) -1.0 else 1.0
        // Positive yaw is about the design's +Z, which on a craft built lying
        // down is the sky: it turns anticlockwise seen from above - left -
        // while the stick gives positive to the right. Flipped, so stick left
        // turns a plane, boat or rover left.
        val flat = current.controlledOrientation == com.rm.apogee.core.craft.CraftOrientation.HORIZONTAL
        val yaw = commandedYaw.toDouble() * if (flat) -1.0 else 1.0
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

    private fun onJoin() {
        val current = session ?: return
        lifecycleScope.launch { current.join() }
    }

    /**
     * Takes the craft being flown out of the world and goes back to the
     * menu - once the server has done it, or the world saved on the way out
     * would still have it.
     */
    private fun onRetire() {
        val current = session ?: return
        lifecycleScope.launch {
            current.retire()
            navigateTo(AppScreen.PLAY)
        }
    }

    private fun onSwitchCraft() {
        val current = session ?: return
        lifecycleScope.launch { current.switchCraft() }
    }

    private fun onToggleReverse() {
        val engaged = !hudState.reverse
        hudState.reverse = engaged
        val current = session ?: return
        lifecycleScope.launch { current.setReverse(engaged) }
    }

    private fun onToggleBrakes() {
        val engaged = !hudState.brakes
        hudState.brakes = engaged
        val current = session ?: return
        lifecycleScope.launch { current.setBrakes(engaged) }
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
        slideRight = 0f; slideAway = 0f; slideLift = 0f

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
        glRenderer.thumbnails = partThumbnails
        val view = createSurfaceView(glRenderer)
        host.addView(view)
        // The builder picks and pans in pixels: it needs the view's size
        // from the start, not only once a finger has touched it.
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            builderSession?.setViewSize(v.width.toFloat(), v.height.toFloat())
        }

        renderer = glRenderer
        surfaceView = view

        perfHints = PerfHints.create(
            context = this,
            threadIds = intArrayOf(android.os.Process.myTid()),
            targetWorkNanos = TARGET_FRAME_NANOS,
        )

        if (screen == AppScreen.BUILDER) {
            val builder = BuilderSession(frameBus, StockParts.catalog, craftStore)
            // Somewhere of the player's own to launch from, as well as the Cape.
            builder.baseSites = runCatching { openSoloWorld().baseSites(settings.clientId) }.getOrDefault(emptyList())
            partThumbnails.request(StockParts.catalog)
            view.takeIf { it.width > 0 }?.let { builder.setViewSize(it.width.toFloat(), it.height.toFloat()) }
            builder.start(lifecycleScope)
            builderSession = builder
        } else {
            val newSession = when (val mode = pendingMode) {
                is SessionMode.Solo -> GameSession.hostLocal(
                    frameBus = frameBus,
                    perfHints = perfHints,
                    playerName = settings.playerName,
                    clientId = settings.clientId,
                    design = pendingLaunchDesign,
                    scope = lifecycleScope,
                    world = openSoloWorld(),
                    siteId = pendingLaunchSite,
                    // Free Flight from the menu is a new flight: the craft
                    // flown last time is cleared away and a fresh one put on
                    // the pad. A launch from the builder brings its own, and
                    // Resume Flight names the one to fly.
                    freshFlight = pendingLaunchDesign == null && pendingResume == null,
                    resumeVessel = pendingResume,
                    weather = settings.weatherIntensity,
                    clouds = settings.cloudCover,
                    launchTime = settings.launchTime,
                )

                is SessionMode.Host -> GameSession.hostLan(
                    frameBus = frameBus,
                    perfHints = perfHints,
                    playerName = settings.playerName,
                    clientId = settings.clientId,
                    serverName = mode.name,
                    design = pendingLaunchDesign,
                    scope = lifecycleScope,
                    weather = settings.weatherIntensity,
                    clouds = settings.cloudCover,
                )

                // Already connected: joining happens before navigation so a
                // failure can be shown on the browser instead of in an empty world.
                is SessionMode.Joined -> mode.session
            }
            pendingLaunchDesign = null
            pendingLaunchSite = null
            pendingResume = null
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
            // 4x multisampling where the device has it. Without it every facet
            // edge is a hard pixel staircase, and as the camera moves the
            // staircases crawl - on faceted ground, that is a shimmer across
            // the whole landscape. Tile-based mobile GPUs resolve MSAA on chip,
            // so it costs little; a device without it falls back to none.
            setEGLConfigChooser(MultisampleConfigChooser)
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
        // Set when a second finger lands, cleared when the next gesture
        // starts. A pinch ends with one finger lifting before the other, and
        // the one left behind used to carry on as a drag measured from where
        // the *first* finger had been before the pinch - one enormous move,
        // and the camera whipped round. After a pinch, the rest of that
        // gesture is the pinch's.
        var multiTouch = false

        // The assembly building's own gestures: taps, holds that lift a part,
        // two-finger pan and pinch - worked out in one place, and testable.
        val building = BuilderGestures(object : BuilderGestures.Listener {
            override fun tap(x: Float, y: Float) { builderSession?.tap(x, y, view.width.toFloat(), view.height.toFloat()) }
            override fun doubleTap(x: Float, y: Float) { builderSession?.recentre() }
            override fun longPress(x: Float, y: Float): Boolean =
                builderSession?.liftAt(x, y, view.width.toFloat(), view.height.toFloat()) == true
            override fun carry(x: Float, y: Float) { builderSession?.carryTo(x, y) }
            override fun drop(x: Float, y: Float) {
                if (x.isNaN()) builderSession?.cancelCarry() else builderSession?.endCarry()
            }
            override fun orbit(dx: Float, dy: Float) {
                builderSession?.camera?.orbitBy(deltaYaw = -dx * ORBIT_RADIANS_PER_PIXEL, deltaPitch = dy * ORBIT_RADIANS_PER_PIXEL)
            }
            override fun pan(dx: Float, dy: Float) { builderSession?.panBy(dx, dy) }
            override fun zoom(factor: Float) { builderSession?.camera?.zoomBy(factor.toDouble()) }
        })

        view.setOnTouchListener { v, event ->
            if (builderSession != null && session == null) {
                builderSession?.setViewSize(v.width.toFloat(), v.height.toFloat())
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        building.down(event.x, event.y, event.eventTime)
                        v.postDelayed({ building.tick(android.os.SystemClock.uptimeMillis()) }, BuilderGestures.LONG_PRESS + 20)
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                        building.secondDown(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
                    }
                    MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                        building.move(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
                    } else {
                        building.move(event.x, event.y)
                    }
                    MotionEvent.ACTION_POINTER_UP -> building.secondUp()
                    MotionEvent.ACTION_UP -> building.up(event.x, event.y, event.eventTime)
                    MotionEvent.ACTION_CANCEL -> building.cancel()
                }
                return@setOnTouchListener true
            }
            pinch.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    downX = event.x; downY = event.y
                    downTime = event.eventTime
                    multiTouch = false
                }

                MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true

                MotionEvent.ACTION_MOVE -> if (!multiTouch && !pinch.isInProgress && event.pointerCount == 1) {
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
                    if (!multiTouch && travelled < TAP_SLOP_PIXELS && duration < TAP_TIMEOUT_MILLIS) {
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

    /**
     * The single-player world, restored from disk the first time it is asked
     * for and kept in memory after that.
     *
     * A save written against a different part catalogue is reported rather
     * than discarded: the craft that still resolve are loaded, and the ones
     * that do not are named. Losing a base to a parts update would be far
     * worse than losing one craft out of it.
     */
    private fun openSoloWorld(): World {
        soloWorld?.let { return it }
        val world = World.default(StockParts.catalog)
        soloWorldStore.loadWithFallback()?.let { (save, warning) ->
            val problems = world.restore(save)
            if (warning != null) Log.w(TAG, "World save: $warning")
            for (problem in problems) Log.w(TAG, "World save: $problem")
        }
        // The Cape's buildings and Luna's test base, before anything asks
        // where it can launch from.
        world.ensureStructures()
        soloWorld = world
        return world
    }

    /** The player's craft in the solo world, for Resume Flight. */
    private fun refreshResumeCraft() {
        val world = openSoloWorld()
        val me = settings.clientId
        // The solo world is only ever played from this install - a hosted
        // game starts a world of its own - so every crewed craft in it is
        // the player's, whatever an older save recorded as its owner. Not
        // debris: spent stages have no one aboard.
        resumeCraft = world.vessels.filter { vessel ->
            vessel.owner != com.rm.apogee.core.world.World.WORLD_OWNER &&
                (vessel.owner == me || vessel.defs.any { it.hasModule<com.rm.apogee.core.part.Command>() })
        }.sortedWith(compareBy({ !it.anchored }, { it.name })).map { vessel ->
            val body = world.attractorFor(vessel)
            val bodyFixed = body.toBodyFixed(vessel.body.position, body.rotationAt(world.time))
            // From its lowest reach, not its centre: a rocket on the pad has
            // its centre eight metres up.
            val above = (body.heightAboveTerrain(vessel.body.position, bodyFixed) - vessel.contactRadius)
                .coerceAtLeast(0.0)
            val orbit = com.rm.apogee.core.orbit.Orbit(
                position = vessel.body.position, velocity = vessel.body.linearVelocity,
                mu = body.gravitationalParameter,
            )
            val floor = body.radius + body.atmosphereHeight + (body.terrain?.maxElevation ?: 0.0)
            val situation = when {
                // A base: where, and how it is keeping - its power and stores.
                vessel.anchored -> {
                    world.settlePower(vessel)
                    val pads = vessel.defs.count { it.hasModule<com.rm.apogee.core.part.LaunchPad>() }
                    "Base on ${body.displayName}" + (if (!vessel.powered) " · dark" else "") +
                        (if (pads > 0) " · $pads pad${if (pads > 1) "s" else ""}" else "")
                }
                above < 2.0 && body.terrain?.isOcean(bodyFixed) == true -> "Afloat on ${body.displayName}"
                above < 2.0 -> "Landed on ${body.displayName}"
                orbit.isBound && orbit.periapsis > floor -> "In orbit of ${body.displayName}"
                else -> "Flying over ${body.displayName}"
            }
            val height = if (above < 2.0) "on the surface"
                else if (above < 10_000.0) "%.0f m up".format(above) else "%.1f km up".format(above / 1000.0)
            com.rm.apogee.ui.screens.CraftSummary(vessel.id.raw, vessel.name, situation, height)
        }
    }

    private fun saveSoloWorld() {
        val world = soloWorld ?: return
        soloWorldStore.save(world.save())
            .onFailure { Log.w(TAG, "Could not save the world: ${it.message}") }
    }

    private fun leaveWorld() {
        frameClockJob?.cancel(); frameClockJob = null

        // Before tearing the session down, while the world is still coherent.
        if (session != null) saveSoloWorld()

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
    /**
     * Debug switches, as files in the app's own storage so adb can flip them
     * mid-flight: `debug-no-sea` builds and draws no sea; `debug-perf` logs
     * frame rate and build times every five seconds under "ApogeePerf".
     */
    private fun debugPerformance(glRenderer: com.rm.apogee.render.GlRenderer, current: GameSession) {
        val now = System.nanoTime()
        if (now - perfLookedNanos > 1_000_000_000L) {
            perfLookedNanos = now
            current.debugHideSea = java.io.File(filesDir, "debug-no-sea").exists()
            perfLogging = java.io.File(filesDir, "debug-perf").exists()
            glRenderer.timePasses = java.io.File(filesDir, "debug-perf-passes").exists()
        }
        if (!perfLogging) { perfSince = 0L; return }
        if (perfSince == 0L) {
            perfSince = now; perfFrames = glRenderer.framesDrawn.get(); perfBuild = 0.0; perfSea = 0.0; perfSamples = 0
            return
        }
        perfBuild += current.lastFrameBuildNanos.get() / 1e6
        perfSea += current.seaBuildMillis
        perfSamples++
        if (now - perfSince >= 5_000_000_000L) {
            val frames = glRenderer.framesDrawn.get() - perfFrames
            val seconds = (now - perfSince) / 1e9
            android.util.Log.i(
                "ApogeePerf",
                "fps %.1f draw %.1f ms build %.1f ms sea-build %.1f ms clouds-list %.0f ms sea %s".format(
                    frames / seconds, 1000.0 * seconds / frames.coerceAtLeast(1), perfBuild / perfSamples,
                    perfSea / perfSamples, current.cloudListMillis, if (current.debugHideSea) "off" else "on",
                ),
            )
            glRenderer.takePassReport()?.let { android.util.Log.i("ApogeePerf", "passes $it") }
            perfSince = now; perfFrames = glRenderer.framesDrawn.get(); perfBuild = 0.0; perfSea = 0.0; perfSamples = 0
        }
    }

    private var perfLookedNanos = 0L
    private var perfLogging = false
    private var perfSince = 0L
    private var perfFrames = 0L
    private var perfBuild = 0.0
    private var perfSea = 0.0
    private var perfSamples = 0

    private fun startFrameClock(): Job =
        CoroutineScope(AndroidUiDispatcher.CurrentThread).launch {
            while (isActive) {
                withFrameNanos { }
                val glRenderer = renderer ?: continue
                glRenderer.shadowChoice = settings.shadowQuality
                hudState.frameTimeMillis = glRenderer.lastFrameTimeNanos.get() / 1_000_000f

                session?.let { current ->
                    debugPerformance(glRenderer, current)
                    hudState.frameBuildMillis = current.lastFrameBuildNanos.get() / 1_000_000f
                    hudState.telemetry = current.telemetry
                    // Only when it changes: a new list every frame would
                    // recompose the stack sixty times a second for nothing.
                    if (hudState.stages !== current.stageCards) hudState.stages = current.stageCards
                    hudState.connecting = !current.connected && current.rejectionReason == null
                    hudState.surfaceReady = current.surfaceReady
                    hudState.connectionError = current.rejectionReason
                    hudState.canJoin = current.joinable
                    // Only when changed: new messages arrive each second, not each frame.
                    current.baseService.let { if (it != hudState.baseService) hudState.baseService = it }
                    current.nearestBase.let { if (it != hudState.nearBase) hudState.nearBase = it }
                    hudState.chute = current.chuteState
                    hudState.dock = current.dockReadout
                    hudState.joints = current.joints
                    val shared = current.sharedWith
                    if (shared == null) {
                        hudState.sharedWith = null
                    } else {
                        // Newly shared: open the card, so the two of them choose.
                        if (hudState.sharedWith == null) hudState.sharedOpen = true
                        hudState.sharedWith = shared.other
                        hudState.sharedPilot = when (shared.pilot) {
                            "" -> "both"
                            current.myId -> "me"
                            else -> "them"
                        }
                    }
                    hudState.ownedCraft = current.ownedCraftCount
                    hudState.hasWheels = current.controlledHasWheels
                    hudState.hasRcs = current.controlledHasRcs
                    // The session decides - switching craft stands the thrusters down.
                    hudState.rcsArmed = current.rcsArmed
                    if (!hudState.rcsArmed) hudState.rcsSlide = false
                    hudState.rcsLeft = if (hudState.hasRcs) current.rcsLeft else null
                    if (settings.showDebugOverlay) hudState.voices = com.rm.apogee.audio.AudioEngine.activeVoices
                    // The mix follows the settings as they are moved.
                    val gains = settings.busGains()
                    if (!gains.contentEquals(lastBusGains)) {
                        lastBusGains = gains
                        com.rm.apogee.audio.AudioEngine.busGains(gains)
                    }
                    current.clock?.let { clock ->
                        hudState.warp = clock.warp
                        hudState.warpRequested = clock.warpRequested
                        hudState.warpAllowed = clock.warpAllowed
                    }
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
        com.rm.apogee.audio.AudioEngine.pause(true)
    }

    /**
     * Saves the solo world whenever the app goes to the background, not
     * only on leaving a flight through the menu: Android may end a
     * backgrounded app without another word, and everything flown since the
     * last save would go with it. Taken on the server's tick thread, between
     * steps, since the world is still running.
     */
    override fun onStop() {
        super.onStop()
        val world = soloWorld ?: return
        val running = session
        // In a flight the server is still stepping it; otherwise it is idle.
        // Joined to someone else's game, there is no solo world running.
        if (running == null) saveSoloWorld()
        else running.betweenTicks { soloWorldStore.save(world.save()) }
    }

    override fun onResume() {
        super.onResume()
        surfaceView?.onResume()
        com.rm.apogee.audio.AudioEngine.pause(false)
        if (appScreen.needsWorldSurface) hideSystemBars()
    }

    override fun onDestroy() {
        serverBrowser.stop()
        leaveWorld()
        com.rm.apogee.audio.AudioEngine.stop()
        super.onDestroy()
    }

    // --- system bars ---------------------------------------------------------

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Let the window into the display cutout as well.
        //
        // Hiding the bars is not enough on its own: by default the window is
        // laid out clear of the cutout, so the scene stopped 136px short of
        // the edge and the gap was drawn black - down the side in landscape,
        // across the top in portrait. Every HUD control already applies
        // WindowInsets.displayCutout itself, so nothing ends up under the
        // notch; only the 3D view extends into it, which is where a fullscreen
        // game wants it.
        setCutoutMode(fillCutout = true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        // Back to the default: menus are ordinary layouts and should sit
        // clear of the notch rather than have a title disappear behind it.
        setCutoutMode(fillCutout = false)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /** API 28+; on 27 the window simply has no cutout to negotiate. */
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

    /** How the next flight should be started. */
    private sealed interface SessionMode {
        data object Solo : SessionMode
        data class Host(val name: String) : SessionMode
        data class Joined(val session: GameSession) : SessionMode
    }

    private companion object {
        /** The up and down buttons' slide, before the stick's cubed response: about a third of a metre a second squared on a tug. */
        const val LIFT_BUTTON = 0.35f

        const val TAG = "Apogee"

        /** 60 fps budget, for the ADPF hint. */
        const val TARGET_FRAME_NANOS = 16_666_667L

        const val ORBIT_RADIANS_PER_PIXEL = 0.005

        /** How far a touch may travel and still count as a tap. */
        const val TAP_SLOP_PIXELS = 28f
        const val TAP_TIMEOUT_MILLIS = 400L
    }
}

/**
 * Picks an RGBA8888, 24-bit depth config with 4x multisampling if the device
 * offers one, and without it otherwise.
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
