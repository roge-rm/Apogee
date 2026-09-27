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
 * The host for the whole app: one FrameLayout holding the 3D surface and, above it, one ComposeView
 * holding every screen.
 *
 * It's kept thin on purpose. The simulation lives in :core, the session wiring in [GameSession],
 * the render path in [GlRenderer], and observable UI state in [HudState]. This class only connects
 * them and decides which screen is up.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: GameSettings
    private var lastBusGains = FloatArray(0)
    private lateinit var hudState: HudState
    private lateinit var frameBus: FrameBus

    /** The designs saved in free play, and in a career. A career starts from scratch, with none of the stock ones. */
    private lateinit var sandboxCraft: CraftStore
    private lateinit var careerCraft: CraftStore
    private val craftStore: CraftStore get() = if (careerMode) careerCraft else sandboxCraft
    /** The two worlds on this device: the sandbox, with everything unlocked, and the career. */
    private lateinit var sandboxStore: WorldStore
    private lateinit var careerStore: WorldStore
    private val soloWorldStore: WorldStore get() = if (careerMode) careerStore else sandboxStore

    /** Whether the Play screen is on the career world, as the player last chose. */
    private var careerMode by mutableStateOf(false)

    /** The player's career in it, for the Play and Program screens. Null in the sandbox. */
    private var careerState by mutableStateOf<com.rm.apogee.core.career.CareerState?>(null)
    private var worldFirsts by mutableStateOf(emptyList<com.rm.apogee.core.career.WorldFirst>())

    /**
     * The single-player world, held across flights.
     *
     * It's null until something needs it. It's loaded from disk once and written back when the
     * player leaves, so landing a module and coming back with the next one is the same world, not a
     * new one.
     */
    private var soloWorld: World? = null

    private var surfaceView: GLSurfaceView? = null
    private var renderer: GlRenderer? = null
    private var session: GameSession? = null
    private var builderSession: BuilderSession? = null

    /** The drawer's part pictures, drawn by the renderer once and kept. */
    private val partThumbnails by lazy { com.rm.apogee.render.PartThumbnails(cacheDir) }

    /** Set by the builder's Launch button, and used up when flight starts. */
    private var pendingLaunchDesign: CraftDesign? = null
    private var pendingLaunchSite: String? = null
    private var pendingResume: Long? = null

    /**
     * A save point: the whole world as it was (careers and all), the craft that was being flown,
     * and when it was taken, by the clock. There's one for each world, the sandbox and the career,
     * kept on disk beside it.
     */
    private class SavePoint(val save: com.rm.apogee.core.world.WorldSave, val vessel: Long?, val at: String)
    private val savePoints = HashMap<Boolean, SavePoint?>()

    /**
     * The world just before this flight's launch, and what was launched from where, to revert to.
     * Null when the flight wasn't a launch (Resume Flight), or it's not a solo flight.
     */
    private class LaunchPoint(val save: com.rm.apogee.core.world.WorldSave, val design: CraftDesign?, val site: String?)
    private var launchPoint: LaunchPoint? = null

    /** Whether the flight up is solo, in this device's own world, and a rewind is starting it. */
    private var flyingSolo = false
    private var rewinding = false
    private var resumeCraft by mutableStateOf(emptyList<com.rm.apogee.ui.screens.CraftSummary>())
    private var crewList by mutableStateOf(emptyList<com.rm.apogee.ui.screens.CrewSummary>())

    /** The solo world's crew for the Crew screen: the living in the order they joined, then the lost. */
    private fun refreshCrew() {
        val world = openSoloWorld()
        val me = settings.clientId
        crewList = world.crew.values.filter { it.owner == me || it.owner.isEmpty() }.map { member ->
            val status = when (member.status) {
                com.rm.apogee.core.crew.CrewStatus.AVAILABLE -> "At home, ready to go"
                com.rm.apogee.core.crew.CrewStatus.ABOARD -> {
                    val craft = world.vessel(com.rm.apogee.core.craft.VesselId(member.vessel))
                    val where = craft?.let { world.attractorFor(it).displayName }.orEmpty()
                    if (craft != null && craft.design.parts.singleOrNull()?.partId == com.rm.apogee.core.world.World.SUIT_PART) "On EVA on $where"
                    else "Aboard ${craft?.name ?: "a craft"}" + if (where.isNotEmpty()) " · $where" else ""
                }
                com.rm.apogee.core.crew.CrewStatus.LOST ->
                    member.lostHow.replaceFirstChar { it.uppercase() } + if (member.lostWhere.isNotEmpty()) " · ${member.lostWhere}" else ""
            }
            com.rm.apogee.ui.screens.CrewSummary(
                member.id, member.name, status, member.status == com.rm.apogee.core.crew.CrewStatus.LOST,
                visor = com.rm.apogee.core.crew.Crew.visorOf(member),
            )
        }.sortedBy { it.lost }
    }
    private var frameClockJob: Job? = null
    private var perfHints: PerfHints? = null
    private var rendererTerrainSource: com.rm.apogee.render.TerrainSource? = null

    // Held between updates, because pitch/yaw and roll come from different controls but get sent as
    // one command.
    private var commandedPitch = 0f
    private var commandedYaw = 0f
    private var commandedRoll = 0f

    private lateinit var serverBrowser: ServerBrowser

    /** How the next flight should be started. */
    private var pendingMode: SessionMode = SessionMode.Solo
    private var joinError by mutableStateOf<String?>(null)
    private var connectingTo by mutableStateOf<String?>(null)

    /**
     * What's typed in the join screen's address box. It starts with the last address that worked,
     * so going back to a server is one tap.
     */
    private var manualAddress by mutableStateOf("")
    private var serverName by mutableStateOf("")

    private var appScreen by mutableStateOf(AppScreen.MENU)
    private var detectedTier by mutableStateOf<QualityTier?>(null)

    /**
     * Every touch, wherever it lands (the view, a control, a dialog), wakes the flight controls
     * from their idle fade. It's only watched, never taken.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && ::hudState.isInitialized) hudState.touched()
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = GameSettings(this)
        hudState = HudState()
        frameBus = FrameBus()
        sandboxCraft = CraftStore(File(filesDir, "craft"))
        careerCraft = CraftStore(File(filesDir, "craft-career"))
        // One world, kept on disk, instead of a fresh universe per launch.
        sandboxStore = WorldStore(File(filesDir, "world/solo.json"))
        careerStore = WorldStore(File(filesDir, "world/career.json"))
        careerMode = settings.careerMode
        // So there's something to fly, and something to land, before the player has built anything,
        // in free play. A career's designs are all its own.
        sandboxCraft.seedStockDesigns(StockParts.catalog)
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
        // A craft file this was opened with, once everything's set up.
        window.decorView.post { handleSharedIntent(intent) }

        findViewById<ComposeView>(R.id.hud_compose_view).setContent {
            ApogeeTheme {
                // Back is wired by hand from AppScreen.parent. Flight swallows it on purpose, so a
                // stray gesture can't throw away a flight.
                BackHandler(enabled = appScreen.parent != null) {
                    navigateTo(appScreen.parent ?: AppScreen.MENU)
                }

                when (appScreen) {
                    AppScreen.MENU -> MainMenuScreen(::navigateTo)
                    AppScreen.PLAY -> PlayScreen(
                        ::navigateTo, settings.launchTime, { settings.launchTime = it },
                        career = careerMode,
                        onCareer = ::switchMode,
                        insight = careerState?.insight,
                    )
                    AppScreen.PROGRAM -> careerState?.let { state ->
                        com.rm.apogee.ui.screens.ProgramScreen(
                            state = state,
                            firsts = worldFirsts,
                            me = settings.clientId,
                            onUnlock = { id ->
                                val refused = openSoloWorld().unlock(settings.clientId, id)
                                saveSoloWorld()
                                refreshProgram()
                                refused
                            },
                        )
                    }
                    AppScreen.RESUME_FLIGHT -> com.rm.apogee.ui.screens.ResumeFlightScreen(
                        craft = resumeCraft,
                        onFly = { id ->
                            // Claimed, if an older save had it under another name.
                            val world = openSoloWorld()
                            world.vessel(com.rm.apogee.core.craft.VesselId(id))?.let {
                                world.claim(it, settings.clientId)
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
                            openSoloWorld().destroy(com.rm.apogee.core.craft.VesselId(id), com.rm.apogee.core.world.World.REMOVED_REASON)
                            saveSoloWorld()
                            refreshResumeCraft()
                        },
                    )
                    AppScreen.CREW -> com.rm.apogee.ui.screens.CrewScreen(crewList) { id, visor ->
                        openSoloWorld().setVisor(id, visor)
                        saveSoloWorld()
                        refreshCrew()
                    }
                    AppScreen.SETTINGS -> SettingsScreen(settings, detectedTier)
                    AppScreen.ABOUT -> AboutScreen()
                    AppScreen.FLIGHT -> FlightScreen(
                        hud = hudState,
                        controlOpacity = settings.controlOpacity,
                        showDebugOverlay = settings.showDebugOverlay,
                        leftHandMode = settings.leftHandMode,
                        fadeWhenIdle = settings.fadeWhenIdle,
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
                        mapLabels = { w, h -> session?.mapLabels(w, h) ?: emptyList() },
                        onTarget = { id -> session?.let { s -> lifecycleScope.launch { s.setTarget(id) } } },
                        onToggleBrakes = ::onToggleBrakes,
                        onToggleRcs = ::onToggleRcs,
                        onToggleReverse = ::onToggleReverse,
                        onToggleDeploy = ::onToggleDeploy,
                        onUndock = { part -> session?.let { s -> lifecycleScope.launch { s.undock(part) } } },
                        onFound = { founded -> session?.let { s -> lifecycleScope.launch { s.found(founded) } } },
                        onRefuel = { on -> session?.let { s -> lifecycleScope.launch { s.refuel(on) } } },
                        onUnload = { on -> session?.let { s -> lifecycleScope.launch { s.unload(on) } } },
                        onRefine = { base, on -> session?.let { s -> lifecycleScope.launch { s.refine(base, on) } } },
                        onToggleDrill = ::onToggleDrill,
                        onToggleRefine = ::onToggleRefine,
                        onDive = { onBallast(1) },
                        onRise = { onBallast(-1) },
                        onHoldDepth = ::onToggleHoldDepth,
                        onToggleFlaps = ::onToggleFlaps,
                        onGroup = { group -> session?.let { s -> lifecycleScope.launch { s.toggleGroup(group) } } },
                        onWinch = ::onWinch,
                        onStationKeep = ::onStationKeep,
                        onHook = { session?.let { s -> lifecycleScope.launch { s.hook() } } },
                        onReleaseLine = { session?.let { s -> lifecycleScope.launch { s.releaseLine() } } },
                        onCruise = ::onCruise,
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
                        onExit = { navigateTo(AppScreen.PLAY) },
                        rewind = com.rm.apogee.ui.screens.RewindActions(
                            onSavePoint = ::takeSavePoint,
                            onLoadSavePoint = ::loadSavePoint,
                            onRevert = ::revertToLaunch,
                        ),
                        onWarp = { rate -> session?.let { s -> lifecycleScope.launch { s.setWarp(rate) } } },
                        me = settings.clientId,
                        onUnlock = { id ->
                            // Checked here, so the answer is instant, and asked of the server,
                            // whose career it is.
                            val state = hudState.career
                            val node = com.rm.apogee.core.career.TechTree.stock.node(id)
                            val why = when {
                                state == null -> "Not a career"
                                node == null -> "No such node"
                                else -> state.blocker(com.rm.apogee.core.career.TechTree.stock, node)
                            }
                            if (why == null) session?.let { s -> lifecycleScope.launch { s.unlock(id) } }
                            why
                        },
                        crewActions = com.rm.apogee.ui.components.CrewActions(
                            onEva = { id -> session?.let { s -> lifecycleScope.launch { s.eva(id) } }; hudState.statusOpen = null },
                            onMove = { id -> session?.let { s -> lifecycleScope.launch { s.moveCrew(id) } } },
                            onBoard = { session?.let { s -> lifecycleScope.launch { s.board() } } },
                            onJump = { session?.let { s -> lifecycleScope.launch { s.jump() } } },
                            onGrab = { on -> session?.let { s -> lifecycleScope.launch { s.grab(on) } } },
                            onFlag = { session?.let { s -> lifecycleScope.launch { s.plantFlag() } } },
                        ),
                        burnActions = com.rm.apogee.ui.components.BurnActions(
                            onNudge = { p, n, r -> session?.let { s -> lifecycleScope.launch { s.nudgeBurn(p, n, r) } } },
                            onShift = { dt -> session?.let { s -> lifecycleScope.launch { s.shiftBurn(dt) } } },
                            onEdited = { session?.let { s -> lifecycleScope.launch { s.burnEdited() } } },
                            onDelete = { session?.let { s -> lifecycleScope.launch { s.deleteBurn() } } },
                            onWarpTo = { session?.let { s -> lifecycleScope.launch { s.warpToBurn() } } },
                            onAutoBurn = { on -> session?.let { s -> lifecycleScope.launch { s.setAutopilot(on, s.localAutoLand) } } },
                            onAutoLand = { on -> session?.let { s -> lifecycleScope.launch { s.setAutopilot(s.localAutoBurn, on) } } },
                        ),
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
                            onShare = ::shareCraft,
                            onOpenShared = { openShared.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                        )
                    }
                    AppScreen.HOST_GAME -> HostGameScreen(
                        serverName = serverName,
                        onServerNameChange = { serverName = it },
                        career = careerMode,
                        onCareer = ::switchMode,
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
        if (target == AppScreen.CREW) refreshCrew()
        if (target == AppScreen.PLAY || target == AppScreen.PROGRAM) refreshProgram()

        // Discovery holds a multicast lock and a socket, so it only runs while the browser is
        // actually on screen.
        if (target == AppScreen.JOIN_GAME) {
            joinError = null
            if (manualAddress.isEmpty()) manualAddress = settings.lastServerAddress
            serverBrowser.start(lifecycleScope)
        } else if (wasBrowsing) {
            serverBrowser.stop()
        }

        when {
            // Builder and flight both want the surface but different sessions, so moving between
            // them tears one down and builds the other, instead of trying to hand one session's
            // state to the other.
            target.needsWorldSurface && wasInWorld -> {
                leaveWorld()
                enterWorld(target)
            }
            target.needsWorldSurface -> enterWorld(target)
            wasInWorld -> leaveWorld()
        }
    }

    // --- sharing craft ------------------------------------------------------------

    /** A shared craft opened from outside, to put on the floor when the builder comes up. */
    private var pendingShared: CraftDesign? = null

    /** The system's file picker, for a craft file someone sent. */
    private val openShared = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importCraft)
    }

    /** The craft on the floor, as a file, out through the share sheet. */
    private fun shareCraft() {
        val builder = builderSession ?: return
        val design = builder.builder.design
        if (design.parts.isEmpty()) return
        runCatching {
            val folder = File(cacheDir, "shared").apply { mkdirs() }
            val safe = design.name.map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("").trim('-').ifBlank { "craft" }
            val file = File(folder, "$safe.apogee.json")
            file.writeText(craftStore.encode(design))
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("application/json")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "${design.name}, an Apogee craft")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(android.content.Intent.createChooser(send, "Share ${design.name}"))
        }.onFailure { builder.statusMessage = "Couldn't share it: ${it.message}" }
    }

    /**
     * A craft file someone shared: checked, saved with the player's own craft under a free name,
     * and opened in the builder. One with parts this version doesn't have is refused, with why.
     */
    private fun importCraft(uri: android.net.Uri) {
        val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        val result = if (text == null) Result.failure(IllegalArgumentException("Couldn't read that file")) else craftStore.decode(text, StockParts.catalog)
        result.onFailure {
            android.widget.Toast.makeText(this, it.message ?: "That isn't a craft file", android.widget.Toast.LENGTH_LONG).show()
        }.onSuccess { shared ->
            val design = shared.copy(name = craftStore.freeName(shared.name))
            craftStore.save(design)
            val builder = builderSession
            if (builder != null) builder.openShared(design)
            else {
                pendingShared = design
                navigateTo(AppScreen.BUILDER)
            }
        }
    }

    /** A craft file sent or opened from outside the app, if that's what [intent] is. */
    private fun handleSharedIntent(intent: android.content.Intent?) {
        intent ?: return
        val uri = when (intent.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else -> null
        } ?: return
        // Not in the middle of a flight. It waits in the builder for next time.
        if (session != null) {
            android.widget.Toast.makeText(this, "Go back to the menu to open a shared craft", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        importCraft(uri)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    private fun launchFromBuilder() {
        pendingLaunchDesign = builderSession?.designForLaunch() ?: return
        pendingLaunchSite = builderSession?.launchSiteId
        pendingMode = SessionMode.Solo
        navigateTo(AppScreen.FLIGHT)
    }

    /**
     * Connects to a discovered host, then goes into flight.
     *
     * The connection is made *before* navigating, so a host that has gone away shows an error on
     * the list where the player can pick another one, instead of dropping them into an empty world
     * to work it out for themselves.
     */
    private fun joinServer(server: DiscoveredServer) =
        connectTo(server.beacon.serverName, server.beacon.address, server.beacon.port)

    /**
     * Connects to an address the player typed.
     *
     * It's only remembered once the connection works. An address that failed is as likely to be a
     * typo as a server that's down, and offering it back as the default next time would keep the
     * typo alive.
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
                stripe = settings.suitStripe,
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
                    joinError = "Couldn't reach $label: ${it.message ?: "host unreachable"}"
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
     * Pitch and yaw come from the stick and roll from its buttons, but they travel as one command.
     * The server takes all three axes together, and splitting them would let a stick update arrive
     * between a roll press and its release and quietly cancel it.
     */
    private fun onAttitude(pitch: Float, yaw: Float) {
        if (sliding()) {
            // Thrusters armed and the stick set to slide: up is away from the camera, and right is
            // its right.
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
            // The roll buttons become down (left) and up (right), gently. A button has no halfway,
            // and full thrust was metres a second in the time it took to tap it.
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
        // Read per command instead of cached, so switching to another craft or changing the setting
        // mid-flight takes effect on the next nudge.
        val reversed = settings.pitchStyle.reverses(current.controlledOrientation)
        val pitch = commandedPitch.toDouble() * if (reversed) -1.0 else 1.0
        // Positive yaw is about the design's +Z, which on a craft built lying down is the sky. It
        // turns anticlockwise seen from above, which is left, while the stick gives positive to the
        // right. It's flipped so stick left turns a plane, boat or rover left.
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
     * Takes the craft being flown out of the world and goes back to the menu, once the server has
     * done it, or the world saved on the way out would still have it.
     */
    private fun onRetire() {
        val current = session ?: return
        lifecycleScope.launch {
            current.retire()
            navigateTo(AppScreen.PLAY)
        }
    }

    private fun onToggleDrill() {
        val power = hudState.power ?: return
        hudState.power = power.copy(drilling = !power.drilling)
        val current = session ?: return
        lifecycleScope.launch { current.setIndustry(!power.drilling, power.refining) }
    }

    private fun onToggleRefine() {
        val power = hudState.power ?: return
        hudState.power = power.copy(refining = !power.refining)
        val current = session ?: return
        lifecycleScope.launch { current.setIndustry(power.drilling, !power.refining) }
    }

    /** Floods the tanks ([mode] 1) or blows them (-1), or stops if it's tapped again. */
    private fun onBallast(mode: Int) {
        val power = hudState.power ?: return
        val next = if (power.ballastMode == mode) 0 else mode
        hudState.power = power.copy(ballastMode = next, holdingDepth = -1f)
        val current = session ?: return
        lifecycleScope.launch { current.setBallast(next) }
    }

    private fun onToggleHoldDepth() {
        val power = hudState.power ?: return
        val on = power.holdingDepth < 0f
        hudState.power = power.copy(ballastMode = 0, holdingDepth = if (on) hudState.telemetry.depth.toFloat().coerceAtLeast(0f) else -1f)
        val current = session ?: return
        lifecycleScope.launch { current.holdDepth(on) }
    }

    private fun onToggleFlaps() {
        val down = !hudState.flaps
        hudState.flaps = down
        val current = session ?: return
        lifecycleScope.launch { current.setFlaps(down) }
    }

    /** The winch: winding in if it's holding, holding if it's winding in. */
    private fun onWinch() {
        val power = hudState.power ?: return
        val mode = if (power.reel > 0) 0 else 1
        hudState.power = power.copy(reel = mode)
        val current = session ?: return
        lifecycleScope.launch { current.reel(mode) }
    }

    /** The keeper core: holding still where it is, or letting go. */
    private fun onStationKeep() {
        val power = hudState.power ?: return
        val on = !power.keeping
        hudState.power = power.copy(keeping = on)
        if (on) hudState.sasEnabled = true
        val current = session ?: return
        lifecycleScope.launch { current.setStationKeep(on) }
    }

    private fun onCruise(on: Boolean) {
        if (on) hudState.sasEnabled = true
        val current = session ?: return
        lifecycleScope.launch { current.setCruise(on) }
    }

    private fun onToggleDeploy() {
        val deployed = !(hudState.power?.deployed ?: false)
        hudState.power = hudState.power?.copy(deployed = deployed)
        val current = session ?: return
        lifecycleScope.launch { current.setDeployed(deployed) }
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
            // This arrives on the GL thread. The terrain builder needs the tier to know how finely
            // to sample, so it's created here instead of guessed at earlier.
            settings.lastDetectedTier = tier
            detectedTier = tier
            session?.attachTerrain(rendererTerrainSource!!, settings.qualityOverride ?: tier)
        }
        rendererTerrainSource = glRenderer.terrainSource
        glRenderer.thumbnails = partThumbnails
        val view = createSurfaceView(glRenderer)
        host.addView(view)
        // The builder picks and pans in pixels, so it needs the view's size from the start, not
        // only once a finger has touched it.
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
            val builder = BuilderSession(
                frameBus, StockParts.catalog, craftStore,
                com.rm.apogee.core.craft.AssemblyStore(File(filesDir, if (careerMode) "assemblies-career" else "assemblies")),
            )
            // Somewhere of the player's own to launch from, as well as the Cape.
            builder.baseSites = runCatching { openSoloWorld().baseSites(settings.clientId) }.getOrDefault(emptyList())
            // In a career, only what the player has unlocked, and no more than their pad can take.
            builder.career = openSoloWorld().program?.careerOf(settings.clientId)
            partThumbnails.request(StockParts.catalog)
            builder.thumbnails = partThumbnails
            view.takeIf { it.width > 0 }?.let { builder.setViewSize(it.width.toFloat(), it.height.toFloat()) }
            builder.start(lifecycleScope)
            builderSession = builder
            pendingShared?.let { builder.openShared(it) }
            pendingShared = null
        } else {
            flyingSolo = pendingMode is SessionMode.Solo
            if (flyingSolo && !rewinding) {
                // A launch (not a resume) can be reverted to, so the world is kept as it was just
                // before it. Nothing's running yet, so it's safe to take here.
                launchPoint = if (pendingResume == null) LaunchPoint(openSoloWorld().save(), pendingLaunchDesign, pendingLaunchSite) else null
            }
            if (!flyingSolo) launchPoint = null
            rewinding = false
            val newSession = when (val mode = pendingMode) {
                is SessionMode.Solo -> GameSession.hostLocal(
                    frameBus = frameBus,
                    perfHints = perfHints,
                    playerName = settings.playerName,
                    clientId = settings.clientId,
                    stripe = settings.suitStripe,
                    design = pendingLaunchDesign,
                    scope = lifecycleScope,
                    world = openSoloWorld(),
                    siteId = pendingLaunchSite,
                    // Free Flight from the menu is a new flight. The craft flown last time gets
                    // cleared away and a fresh one put on the pad. A launch from the builder brings
                    // its own, and Resume Flight names the one to fly.
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
                    stripe = settings.suitStripe,
                    serverName = mode.name,
                    design = pendingLaunchDesign,
                    scope = lifecycleScope,
                    weather = settings.weatherIntensity,
                    clouds = settings.cloudCover,
                    // The chosen world, as solo play uses it. The others join it.
                    world = openSoloWorld(),
                )

                // Already connected. Joining happens before navigating so a failure can be shown on
                // the browser instead of in an empty world.
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
            // Keeping the context across pauses saves rebuilding every mesh and shader each time
            // the player checks a notification.
            preserveEGLContextOnPause = true
            // 4x multisampling where the device has it. Without it every facet edge is a hard pixel
            // staircase, and as the camera moves the staircases crawl, which on faceted ground is a
            // shimmer across the whole landscape. Tile-based mobile GPUs resolve MSAA on chip, so
            // it costs little, and a device without it falls back to none.
            setEGLConfigChooser(MultisampleConfigChooser)
            setRenderer(glRenderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        // Camera gestures are handled on the surface itself instead of in Compose, so a drag over
        // the 3D world doesn't have to travel through the overlay's hit testing to get here.
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // The map's own camera in map view, or it zoomed the flight view behind the map.
                session?.let { (if (it.mapMode) it.mapCamera else it.camera).zoomBy(detector.scaleFactor.toDouble()) }
                return true
            }
        })

        var lastX = 0f
        var lastY = 0f
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        // Set when a second finger lands, and cleared when the next gesture starts. A pinch ends
        // with one finger lifting before the other, and the one left behind used to carry on as a
        // drag measured from where the *first* finger had been before the pinch. That was one
        // enormous move, and the camera whipped round. After a pinch, the rest of that gesture
        // belongs to the pinch.
        var multiTouch = false
        // A finger holding the planned burn on the map, dragging it along the path.
        var holdingBurn = false

        // The assembly building's own gestures (taps, holds that lift a part, two-finger pan and
        // pinch), worked out in one place, and testable.
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
                    // On the map, a finger on the planned burn takes hold of it.
                    holdingBurn = session?.takeIf { it.mapMode }?.mapPress(event.x, event.y, v.width.toFloat(), v.height.toFloat()) == true
                }

                MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true

                MotionEvent.ACTION_MOVE -> if (holdingBurn) {
                    session?.mapDrag(event.x, event.y, v.width.toFloat(), v.height.toFloat())
                } else if (!multiTouch && !pinch.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x; lastY = event.y
                    activeCamera()?.orbitBy(
                        deltaYaw = -dx * ORBIT_RADIANS_PER_PIXEL,
                        deltaPitch = dy * ORBIT_RADIANS_PER_PIXEL,
                    )
                }

                MotionEvent.ACTION_UP -> {
                    // A tap is a touch that neither travelled nor lingered. The slop has to be
                    // generous. On a phone, a finger put down to tap always moves a few pixels, and
                    // treating that as a drag makes placing a part feel broken.
                    val travelled = kotlin.math.hypot(event.x - downX, event.y - downY)
                    val duration = event.eventTime - downTime
                    if (holdingBurn) {
                        holdingBurn = false
                        session?.mapRelease()
                    } else if (!multiTouch && travelled < TAP_SLOP_PIXELS && duration < TAP_TIMEOUT_MILLIS && session?.mapMode == true) {
                        session?.mapTap(event.x, event.y, v.width.toFloat(), v.height.toFloat())
                    } else if (!multiTouch && travelled < TAP_SLOP_PIXELS && duration < TAP_TIMEOUT_MILLIS) {
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
     * The single-player world, restored from disk the first time it's asked for and kept in memory
     * after that.
     *
     * A save written against a different part catalogue gets reported instead of thrown away. The
     * craft that still work are loaded, and the ones that don't are named. Losing a base to a parts
     * update would be far worse than losing one craft out of it.
     */
    private fun openSoloWorld(): World {
        soloWorld?.let { return it }
        val world = World.default(StockParts.catalog)
        soloWorldStore.loadWithFallback()?.let { (save, warning) ->
            val problems = world.restore(save)
            if (warning != null) Log.w(TAG, "World save: $warning")
            for (problem in problems) Log.w(TAG, "World save: $problem")
        }
        // A career world that's new to this device starts its program here.
        if (careerMode && world.program == null) world.program = com.rm.apogee.core.career.Program()
        // The Cape's buildings and Luna's test base, before anything asks where it can launch from.
        world.ensureStructures()
        soloWorld = world
        return world
    }

    /** The player's craft in the solo world, for Out There. */
    private fun refreshResumeCraft() {
        val world = openSoloWorld()
        val me = settings.clientId
        // The solo world is only ever played from this install (a hosted game starts a world of its
        // own), so every crewed craft in it is the player's, whatever an older save recorded as its
        // owner. Not debris, because spent stages have no one aboard.
        resumeCraft = world.vessels.filter { vessel ->
            vessel.owner != com.rm.apogee.core.world.World.WORLD_OWNER &&
                (vessel.owner == me || vessel.defs.any { it.hasModule<com.rm.apogee.core.part.Command>() }) ||
                // Flags stay, whoever's they are. They're listed so they can be taken down.
                vessel.design.parts.singleOrNull()?.partId == com.rm.apogee.core.world.World.FLAG_PART
        }.sortedWith(compareBy({ !it.anchored }, { it.name })).map { vessel ->
            val body = world.attractorFor(vessel)
            val bodyFixed = body.toBodyFixed(vessel.body.position, body.rotationAt(world.time))
            // From its lowest reach, not its centre, because a rocket on the pad has its centre
            // eight metres up.
            val above = (body.heightAboveTerrain(vessel.body.position, bodyFixed) - vessel.contactRadius)
                .coerceAtLeast(0.0)
            val orbit = com.rm.apogee.core.orbit.Orbit(
                position = vessel.body.position, velocity = vessel.body.linearVelocity,
                mu = body.gravitationalParameter,
            )
            val floor = body.radius + body.atmosphereHeight + (body.terrain?.maxElevation ?: 0.0)
            val only = vessel.design.parts.singleOrNull()?.partId
            val suit = only == com.rm.apogee.core.world.World.SUIT_PART
            val flag = only == com.rm.apogee.core.world.World.FLAG_PART
            val depth = world.depthOf(vessel)
            val situation = when {
                suit -> "On EVA on ${body.displayName}"
                flag -> "Planted on ${body.displayName}"
                // A base: where it is, and how it's keeping, with its power and stores.
                vessel.anchored -> {
                    world.settlePower(vessel)
                    val pads = vessel.defs.count { it.hasModule<com.rm.apogee.core.part.LaunchPad>() }
                    "Base on ${body.displayName}" + (if (!vessel.powered) " · dark" else "") +
                        (if (pads > 0) " · $pads pad${if (pads > 1) "s" else ""}" else "")
                }
                depth > com.rm.apogee.game.GameSession.UNDER_SEA -> "Under the sea off ${body.displayName}"
                above < 2.0 && body.terrain?.isOcean(bodyFixed) == true -> "Afloat on ${body.displayName}"
                above < 2.0 -> "Landed on ${body.displayName}"
                orbit.isBound && orbit.periapsis > floor -> "In orbit of ${body.displayName}"
                else -> "${com.rm.apogee.game.Going.aloft(vessel.design, StockParts.catalog)} over ${body.displayName}"
            }
            val height = if (depth > com.rm.apogee.game.GameSession.UNDER_SEA) "%.0f m down".format(depth)
                else if (above < 2.0) "on the surface"
                else if (above < 10_000.0) "%.0f m up".format(above) else "%.1f km up".format(above / 1000.0)
            val aboard = vessel.crewAboard
            val crewNote = when {
                aboard == 0 -> ""
                world.recoverable(vessel) -> "Its crew of $aboard come${if (aboard == 1) "s" else ""} home."
                else -> "Its crew of $aboard ${if (aboard == 1) "is" else "are"} lost with it."
            }
            com.rm.apogee.ui.screens.CraftSummary(
                vessel.id.raw, vessel.name, situation, height, crewNote,
                canReset = !suit && !flag, canFly = !flag,
                going = com.rm.apogee.game.Going.of(vessel.design, StockParts.catalog, vessel.anchored),
            )
        }
    }

    /**
     * Career or sandbox: the world on the Play and Host screens. The one that's open gets saved and
     * put away first (never mid-flight), and the other one is opened the next time something asks
     * for it.
     */
    private fun switchMode(career: Boolean) {
        if (career == careerMode || session != null) return
        saveSoloWorld()
        soloWorld = null
        careerMode = career
        settings.careerMode = career
        refreshProgram()
    }

    /** The player's career in the open world, for the Play and Program screens. */
    private fun refreshProgram() {
        val program = if (careerMode) openSoloWorld().program else null
        careerState = program?.careerOf(settings.clientId)
        worldFirsts = program?.firsts?.toList() ?: emptyList()
    }

    private fun saveSoloWorld() {
        val world = soloWorld ?: return
        soloWorldStore.save(world.save())
            .onFailure { Log.w(TAG, "Could not save the world: ${it.message}") }
    }

    /** This world's save point file, and the craft and time beside it. */
    private fun savePointFile(): File = File(filesDir, if (careerMode) "world/career-savepoint.json" else "world/solo-savepoint.json")
    private fun savePointNote(): File = File(filesDir, if (careerMode) "world/career-savepoint.txt" else "world/solo-savepoint.txt")

    /** This world's save point, read from disk the first time it's asked for. */
    private fun currentSavePoint(): SavePoint? {
        if (savePoints.containsKey(careerMode)) return savePoints[careerMode]
        val point = runCatching {
            val save = WorldStore(savePointFile()).load().getOrThrow()
            val note = savePointNote().readLines()
            SavePoint(save, note.getOrNull(0)?.toLongOrNull(), note.getOrNull(1) ?: "")
        }.getOrNull()
        savePoints[careerMode] = point
        return point
    }

    /** Takes a save point of the world as it is now, between the server's ticks. */
    private fun takeSavePoint() {
        val running = session ?: return
        val world = soloWorld ?: return
        val vessel = running.controlledId
        val career = careerMode
        running.betweenTicks {
            val save = world.save()
            val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            runOnUiThread {
                savePoints[career] = SavePoint(save, vessel, at)
                hudState.banner = HudState.Banner("SAVE POINT", "Taken at $at", good = true, id = System.nanoTime())
            }
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                WorldStore(savePointFile()).save(save)
                    .onFailure { Log.w(TAG, "Could not keep the save point: ${it.message}") }
                runCatching { savePointNote().writeText("${vessel ?: ""}\n$at\n") }
            }
        }
    }

    /**
     * Puts the world back as it was in [save] and flies again: [resume] if it names a craft, or else
     * [design] launched from [site], or a fresh craft on the pad when there's neither.
     */
    private fun rewindTo(save: com.rm.apogee.core.world.WorldSave, resume: Long?, design: CraftDesign?, site: String?) {
        leaveWorld()
        val world = World.default(StockParts.catalog)
        for (problem in world.restore(save)) Log.w(TAG, "Rewind: $problem")
        if (careerMode && world.program == null) world.program = com.rm.apogee.core.career.Program()
        world.ensureStructures()
        soloWorld = world
        saveSoloWorld()
        pendingResume = resume?.takeIf { id -> world.vessel(com.rm.apogee.core.craft.VesselId(id)) != null }
        pendingLaunchDesign = if (pendingResume == null) design else null
        pendingLaunchSite = site
        pendingMode = SessionMode.Solo
        rewinding = true
        enterWorld(AppScreen.FLIGHT)
    }

    private fun loadSavePoint() {
        val point = currentSavePoint() ?: return
        rewindTo(point.save, point.vessel, null, null)
    }

    private fun revertToLaunch() {
        val point = launchPoint ?: return
        rewindTo(point.save, null, point.design, point.site)
    }

    private fun leaveWorld() {
        frameClockJob?.cancel(); frameClockJob = null

        // Before tearing the session down, while the world still hangs together.
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
     * A display-rate loop for overlay values that mustn't lag behind the world.
     *
     * It uses [AndroidUiDispatcher.CurrentThread] specifically, because that dispatcher carries the
     * MonotonicFrameClock that [withFrameNanos] needs, and a plain main-thread scope throws.
     * Anything in screen space (projected labels, attach-node markers, the map cursor) has to be
     * refreshed here instead of at the simulation's slower pace, or it visibly trails the camera
     * whenever the view moves.
     */
    /**
     * Debug switches, as files in the app's own storage so adb can flip them mid-flight.
     * `debug-no-sea` builds and draws no sea, and `debug-perf` logs frame rate and build times
     * every five seconds under "ApogeePerf". `debug-sound` logs the sounds playing every two seconds
     * under "ApogeeSound".
     */
    private fun debugPerformance(glRenderer: com.rm.apogee.render.GlRenderer, current: GameSession) {
        val now = System.nanoTime()
        if (now - perfLookedNanos > 1_000_000_000L) {
            perfLookedNanos = now
            current.debugHideSea = java.io.File(filesDir, "debug-no-sea").exists()
            perfLogging = java.io.File(filesDir, "debug-perf").exists()
            glRenderer.timePasses = java.io.File(filesDir, "debug-perf-passes").exists()
            com.rm.apogee.audio.AudioEngine.logging = java.io.File(filesDir, "debug-sound").exists()
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
                    hudState.going = current.controlledGoing
                    // Only when it changes. A new list every frame would recompose the stack sixty
                    // times a second for nothing.
                    if (hudState.stages !== current.stageCards) hudState.stages = current.stageCards
                    hudState.connecting = !current.connected && current.rejectionReason == null
                    hudState.surfaceReady = current.surfaceReady
                    hudState.connectionError = current.rejectionReason
                    hudState.canJoin = current.joinable
                    // Only when it's changed, because new messages arrive each second, not each
                    // frame.
                    current.baseService.let { if (it != hudState.baseService) hudState.baseService = it }
                    current.nearestBase.let { if (it != hudState.nearBase) hudState.nearBase = it }
                    hudState.chute = current.chuteState
                    hudState.burn = current.burnReadout
                    // When it's flying itself (the autopilot, or the keeper core holding station),
                    // it has the throttle, so show where it has it.
                    if (current.localAutoBurn || current.localAutoLand || hudState.power?.keeping == true) hudState.throttle = current.telemetry.throttle.toFloat()
                    hudState.landing = current.landingReadout
                    if (hudState.mapPlannable != current.mapPlannable) hudState.mapPlannable = current.mapPlannable
                    // The career's news, one at a time, each for a few seconds.
                    if (hudState.banner == null) {
                        current.nextFeat()?.let { feat ->
                            val detail = listOf(feat.grade.uppercase(), if (feat.insight > 0) "+${feat.insight} insight" else "").filter { it.isNotEmpty() }.joinToString(" · ")
                            hudState.banner = HudState.Banner(feat.title.uppercase(), detail, good = true, id = System.nanoTime())
                        } ?: current.nextRefusal()?.let { reason ->
                            hudState.banner = HudState.Banner("NOT ALLOWED", reason, good = false, id = System.nanoTime())
                        }
                    }
                    hudState.window = current.windowReadout
                    hudState.autopilotNote = current.autopilotNote
                    hudState.dock = current.dockReadout
                    hudState.joints = current.joints
                    val shared = current.sharedWith
                    if (shared == null) {
                        hudState.sharedWith = null
                    } else {
                        // Newly shared, so open the card and the two of them can choose.
                        if (hudState.sharedWith == null) hudState.statusOpen = HudState.STATUS_SHARED
                        hudState.sharedWith = shared.other
                        hudState.sharedPilot = when (shared.pilot) {
                            "" -> "both"
                            current.myId -> "me"
                            else -> "them"
                        }
                    }
                    hudState.ownedCraft = current.ownedCraftCount
                    current.career.let { if (it != hudState.career) hudState.career = it }
                    current.worldFirsts.let { if (it != hudState.worldFirsts) hudState.worldFirsts = it }
                    hudState.hasWheels = current.controlledHasWheels
                    hudState.hasRcs = current.controlledHasRcs
                    hudState.hasFoldouts = current.controlledHasFoldouts
                    hudState.hasDrill = current.controlledHasDrill
                    hudState.isSuit = current.controlledIsSuit
                    hudState.crewLost = current.crewLostWith
                    hudState.crew = current.crewCard
                    hudState.crewSeats = current.controlledSeats
                    hudState.surveyedHere = current.surveyedHere
                    hudState.currentsHere = current.currentsHere
                    current.mapCurrents = hudState.mapResource == "CURRENTS"
                    current.mapResource = when (hudState.mapResource) {
                        "ORE" -> com.rm.apogee.core.part.ResourceType.ORE
                        "H2O" -> com.rm.apogee.core.part.ResourceType.WATER
                        else -> null
                    }
                    hudState.hasConverter = current.controlledHasConverter
                    hudState.hasFlaps = current.controlledHasFlaps
                    hudState.hasSails = current.controlledHasSails
                    hudState.canCruise = current.controlledCanCruise
                    current.controlledGroups.let { if (it != hudState.groupsUsed) hudState.groupsUsed = it }
                    hudState.approach = current.approachReadout
                    current.currentReadout.let {
                        hudState.currentSpeed = it?.first ?: 0f
                        hudState.currentBearing = it?.second ?: 0f
                    }
                    hudState.power = current.powerReadout
                    // The session decides, because switching craft stands the thrusters down.
                    hudState.rcsArmed = current.rcsArmed
                    if (!hudState.rcsArmed) hudState.rcsSlide = false
                    hudState.rcsLeft = if (hudState.hasRcs) current.rcsLeft else null
                    if (settings.showDebugOverlay) hudState.voices = com.rm.apogee.audio.AudioEngine.activeVoices
                    // The mix follows the settings as they're moved.
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
                    // Rewinding is only for your own world with nobody else in it, the same as
                    // warp.
                    hudState.canRewind = flyingSolo && hudState.warpAllowed
                    hudState.savePoint = currentSavePoint()?.at
                    hudState.canRevert = launchPoint != null
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
     * Saves the solo world whenever the app goes to the background, not only when leaving a flight
     * through the menu. Android can end a backgrounded app without another word, and everything
     * flown since the last save would go with it. It's taken on the server's tick thread, between
     * steps, since the world is still running.
     */
    override fun onStop() {
        super.onStop()
        val world = soloWorld ?: return
        val running = session
        // In a flight the server is still stepping it, and otherwise it's idle. Joined to someone
        // else's game, there's no solo world running.
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

    /** How the next flight should be started. */
    private sealed interface SessionMode {
        data object Solo : SessionMode
        data class Host(val name: String) : SessionMode
        data class Joined(val session: GameSession) : SessionMode
    }

    private companion object {
        /**
         * The slide from the up and down buttons, before the stick's cubed response: about a third
         * of a metre a second squared on a tug.
         */
        const val LIFT_BUTTON = 0.35f

        const val TAG = "Apogee"

        /** The 60 fps budget, for the ADPF hint. */
        const val TARGET_FRAME_NANOS = 16_666_667L

        const val ORBIT_RADIANS_PER_PIXEL = 0.005

        /** How far a touch can travel and still count as a tap. */
        const val TAP_SLOP_PIXELS = 28f
        const val TAP_TIMEOUT_MILLIS = 400L
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
