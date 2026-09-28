package com.rm.apogee

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.fillMaxSize
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldStore
import com.rm.apogee.game.BuilderSession
import com.rm.apogee.game.DiscoveredServer
import com.rm.apogee.game.GameSession
import com.rm.apogee.game.HudState
import com.rm.apogee.game.ServerBrowser
import com.rm.apogee.net.ServerAddress
import com.rm.apogee.platform.Log
import com.rm.apogee.platform.PerfHints
import com.rm.apogee.platform.System
import com.rm.apogee.platform.format
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.QualityTier
import com.rm.apogee.settings.GameSettings
import com.rm.apogee.ui.BackHandler
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
import kotlinx.coroutines.launch

/**
 * The whole app, the same on a phone and in a browser: which screen is up, the worlds and craft on
 * this device, starting and leaving flights and the assembly building, the flight controls, save
 * points, and the HUD's values each frame. What only the platform can do comes from [host].
 *
 * It's kept thin on purpose. The simulation lives in :core, the session wiring in [GameSession],
 * the render path in [GlRenderer], and observable UI state in [HudState]. This class only connects
 * them and decides which screen is up.
 */
class ApogeeApp(private val host: AppHost) {

    private val settings: GameSettings get() = host.settings
    private val scope: CoroutineScope get() = host.scope
    private var lastBusGains = FloatArray(0)
    val hudState = HudState()
    private val frameBus = FrameBus()

    /** The designs saved in free play, and in a career. A career starts from scratch, with none of the stock ones. */
    private val sandboxCraft = CraftStore(host.folder("craft"))
    private val careerCraft = CraftStore(host.folder("craft-career"))
    private val craftStore: CraftStore get() = if (careerMode) careerCraft else sandboxCraft
    /** The two worlds on this device: the sandbox, with everything unlocked, and the career. */
    private val worldFolder = host.folder("world")
    private val sandboxStore = WorldStore(worldFolder, "solo.json")
    private val careerStore = WorldStore(worldFolder, "career.json")
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

    private var renderer: GlRenderer? = null
    var session: GameSession? = null
        private set
    var builderSession: BuilderSession? = null
        private set

    /** The drawer's part pictures, drawn by the renderer once and kept. */
    private val partThumbnails by lazy { com.rm.apogee.render.PartThumbnails(host.pictures) }

    /** Set by the builder's Launch button, and used up when flight starts. */
    private var pendingLaunchDesign: CraftDesign? = null
    private var pendingLaunchSite: String? = null

    /** A launch that clears away the craft flown last time, as Quick Launch's do. */
    private var pendingFresh = false

    /** The saved craft for Quick Launch, read when it opens. Empty until then. */
    private var quickEntries: List<com.rm.apogee.game.CraftShelf.Entry> by mutableStateOf(emptyList())
    private var quickCraft: String? by mutableStateOf(null)
    private var quickSite: String? by mutableStateOf(null)
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
    private var perfHints: PerfHints? = null
    private var rendererTerrainSource: com.rm.apogee.render.TerrainSource? = null

    // Held between updates, because pitch/yaw and roll come from different controls but get sent as
    // one command.
    private var commandedPitch = 0f
    private var commandedYaw = 0f
    private var commandedRoll = 0f

    private val serverBrowser: ServerBrowser get() = host.serverBrowser

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

    /** Every touch, wherever it lands (the view, a control, a dialog), wakes the flight controls. */
    fun touched() {
        hudState.touched()
        // A touch brings the touch stick back.
        hudState.padActive = false
    }

    // --- a controller ----------------------------------------------------------

    /** The controller's name, from the host, for the settings. Null with none connected. */
    var controllerName: String? by mutableStateOf(null)

    private var padConfigText: String? = null
    private var padConfig = com.rm.apogee.input.PadConfig()

    /** The controller, fed by the host and run each frame. See [com.rm.apogee.input.PadInput]. */
    val pad = com.rm.apogee.input.PadInput {
        val text = settings.padBindings
        if (text != padConfigText) {
            padConfigText = text
            padConfig = padConfig.copy(bindings = com.rm.apogee.input.PadBindings.parse(text))
        }
        padConfig.copy(
            deadZone = settings.padDeadZone,
            lookSpeed = settings.padLookSpeed,
            invertLook = settings.padInvertLook,
            holdToStage = settings.padHoldToStage,
        )
    }

    /**
     * Where the controller goes now: the craft, the Vehicle Assembly's camera, or the menus. A
     * panel open over the flight has it too, so the D-pad and A work the panel.
     */
    fun padMode(): com.rm.apogee.input.PadMode = when {
        appScreen == AppScreen.FLIGHT && session != null && !hudState.panelOpen && hudState.surfaceReady ->
            com.rm.apogee.input.PadMode.FLIGHT
        appScreen == AppScreen.BUILDER && builderSession != null -> com.rm.apogee.input.PadMode.BUILDER
        else -> com.rm.apogee.input.PadMode.MENU
    }

    private val padTarget = object : com.rm.apogee.input.PadTarget {
        override fun steer(pitch: Float, yaw: Float) = onAttitude(pitch, yaw)
        override fun roll(roll: Float) = onRoll(roll)
        override fun look(yaw: Double, pitch: Double) {
            val camera = if (appScreen == AppScreen.BUILDER) builderSession?.camera else activeCamera()
            camera?.orbitBy(deltaYaw = yaw, deltaPitch = pitch)
        }
        override fun zoom(factor: Float) = gestures.zoom(factor)
        override val throttle: Float get() = hudState.throttle
        override fun setThrottle(value: Float) = onThrottleChange(value)
        override fun act(action: com.rm.apogee.input.PadAction) = padAction(action)
        override fun stageHold(fraction: Float) { hudState.stageHold = fraction }
        override fun used() { hudState.padActive = true }
    }

    /** What a controller button does once, as it goes down. */
    private fun padAction(action: com.rm.apogee.input.PadAction) {
        val current = session ?: return
        val power = hudState.power
        when (action) {
            com.rm.apogee.input.PadAction.STAGE -> if (!hudState.isSuit) onStage()
            com.rm.apogee.input.PadAction.THROTTLE_FULL -> onThrottleChange(1f)
            com.rm.apogee.input.PadAction.THROTTLE_CUT -> onThrottleChange(0f)
            com.rm.apogee.input.PadAction.SAS -> onToggleSas()
            com.rm.apogee.input.PadAction.RCS -> onToggleRcs()
            com.rm.apogee.input.PadAction.STICK_MODE -> if (hudState.rcsArmed) onStickMode(!hudState.rcsSlide)
            com.rm.apogee.input.PadAction.STEERING -> onSteering(!hudState.steerByScreen)
            com.rm.apogee.input.PadAction.CRUISE -> if (hudState.canCruise) onCruise(power?.cruising != true)
            com.rm.apogee.input.PadAction.KEEPER -> if (power != null) onStationKeep()
            com.rm.apogee.input.PadAction.BRAKES -> onToggleBrakes()
            com.rm.apogee.input.PadAction.DEPLOY -> onToggleDeploy()
            com.rm.apogee.input.PadAction.FLAPS -> if (hudState.hasFlaps) onToggleFlaps()
            com.rm.apogee.input.PadAction.REVERSE -> onToggleReverse()
            com.rm.apogee.input.PadAction.GROUP_1 -> scope.launch { current.toggleGroup(1) }
            com.rm.apogee.input.PadAction.GROUP_2 -> scope.launch { current.toggleGroup(2) }
            com.rm.apogee.input.PadAction.GROUP_3 -> scope.launch { current.toggleGroup(3) }
            com.rm.apogee.input.PadAction.HOOK -> when {
                power == null || !power.hasWinch -> Unit
                power.hooked -> scope.launch { current.releaseLine() }
                power.canHook.isNotEmpty() -> scope.launch { current.hook() }
            }
            com.rm.apogee.input.PadAction.WINCH -> if (power?.hooked == true) onWinch()
            com.rm.apogee.input.PadAction.DOCK -> if (hudState.canJoin) onJoin()
            com.rm.apogee.input.PadAction.MAP -> onToggleMap()
            com.rm.apogee.input.PadAction.CAMERA_MODE -> if (!hudState.mapMode) onCameraMode()
            com.rm.apogee.input.PadAction.WARP_FASTER, com.rm.apogee.input.PadAction.WARP_SLOWER -> {
                if (!hudState.warpAllowed) return
                val next = com.rm.apogee.input.PadInput.warpStep(
                    hudState.warpRequested, action == com.rm.apogee.input.PadAction.WARP_FASTER, WARP_STEPS,
                ) ?: return
                scope.launch { current.setWarp(next) }
            }
            com.rm.apogee.input.PadAction.FLIGHT_MENU -> hudState.exitMenuOpen = true
            com.rm.apogee.input.PadAction.JUMP -> if (hudState.isSuit) scope.launch { current.jump() }
            com.rm.apogee.input.PadAction.GRAB -> when {
                !hudState.isSuit || power == null -> Unit
                power.onLadder -> scope.launch { current.grab(false) }
                power.canGrab -> scope.launch { current.grab(true) }
            }
            com.rm.apogee.input.PadAction.BOARD -> if (hudState.isSuit && power?.boardable?.isNotEmpty() == true) scope.launch { current.board() }
            com.rm.apogee.input.PadAction.FLAG -> if (hudState.isSuit) scope.launch { current.plantFlag() }
            else -> Unit
        }
    }

    /** Back in flight closes what's open over it, or brings up the flight menu. */
    private fun flightBack() {
        if (!hudState.closePanel()) hudState.exitMenuOpen = true
    }

    init {
        careerMode = settings.careerMode
        // So there's something to fly, and something to land, before the player has built anything,
        // in free play. A career's designs are all its own.
        sandboxCraft.seedStockDesigns(StockParts.catalog)
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
    }

    /** Every screen, one at a time, over the world's surface when there is one. */
    @Composable
    fun Content() {
            LaunchedEffect(Unit) { if (host.fullscreenMenus) host.fullscreen(true) }
            ApogeeTheme {
                // Back is wired by hand from AppScreen.parent. Flight swallows it on purpose, so a
                // stray gesture can't throw away a flight.
                BackHandler(enabled = appScreen.parent != null) {
                    navigateTo(appScreen.parent ?: AppScreen.MENU)
                }
                // In flight, Back (the device's, or B on a controller over a panel) closes what's
                // open, or brings up the flight menu, which has Leave in it.
                BackHandler(enabled = appScreen == AppScreen.FLIGHT) { if (session != null) flightBack() }
                // The HUD's values, each frame the display shows, while there's a world.
                if (appScreen.needsWorldSurface) {
                    LaunchedEffect(Unit) {
                        while (true) {
                            withFrameNanos { }
                            frameTick()
                        }
                    }
                }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                if (appScreen.needsWorldSurface && host.worldInputInCompose) WorldInputLayer(gestures)

                when (appScreen) {
                    AppScreen.MENU -> MainMenuScreen(::navigateTo)
                    AppScreen.PLAY -> PlayScreen(
                        ::navigateTo, settings.launchTime, { settings.launchTime = it },
                        career = careerMode,
                        onCareer = ::switchMode,
                        insight = careerState?.insight,
                        networked = host.networked,
                        onBack = ::goBack,
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
                            onClose = ::goBack,
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
                        onBack = ::goBack,
                    )
                    AppScreen.QUICK_LAUNCH -> com.rm.apogee.ui.screens.QuickLaunchScreen(
                        entries = quickEntries,
                        pictures = partThumbnails.pictures,
                        chosen = quickCraft,
                        onChoose = { quickCraft = it; settings.quickCraft = it },
                        site = quickSite,
                        automatic = quickEntries.firstOrNull { it.saved.fileName == quickCraft }
                            ?.let { World.launchSiteFor(it.design, StockParts.catalog).displayName } ?: "the pad",
                        bases = runCatching { openSoloWorld().baseSites(settings.clientId) }.getOrDefault(emptyList()),
                        onSite = { quickSite = it; settings.quickSite = it ?: "" },
                        onLaunch = ::quickLaunch,
                        onBack = ::goBack,
                    )
                    AppScreen.CREW -> com.rm.apogee.ui.screens.CrewScreen(
                        crewList,
                        onVisor = { id, visor ->
                            openSoloWorld().setVisor(id, visor)
                            saveSoloWorld()
                            refreshCrew()
                        },
                        onBack = ::goBack,
                    )
                    AppScreen.SETTINGS -> SettingsScreen(
                        settings, detectedTier, onBack = ::goBack,
                        controllerName = controllerName,
                        onController = { navigateTo(AppScreen.CONTROLLER) },
                    )
                    AppScreen.CONTROLLER -> com.rm.apogee.ui.screens.ControllerScreen(
                        settings, pad, controllerName, onBack = ::goBack,
                    )
                    AppScreen.ABOUT -> AboutScreen(onBack = ::goBack)
                    AppScreen.FLIGHT -> FlightScreen(
                        hud = hudState,
                        controlOpacity = settings.controlOpacity,
                        showDebugOverlay = settings.showDebugOverlay,
                        leftHandMode = settings.leftHandMode,
                        fadeWhenIdle = settings.fadeWhenIdle,
                        hideTouchStick = settings.padHideTouch && hudState.padActive,
                        onThrottleChange = ::onThrottleChange,
                        onAttitude = ::onAttitude,
                        onRoll = ::onRoll,
                        onStage = ::onStage,
                        onToggleSas = ::onToggleSas,
                        onSasMode = { mode ->
                            hudState.sasEnabled = true
                            session?.let { s -> scope.launch { s.setSasMode(mode) } }
                        },
                        onCycleFrame = {
                            session?.let { s -> scope.launch { s.cycleNavFrame() } }
                        },
                        targetChoices = { session?.targetChoices() ?: emptyList() },
                        mapLabels = { w, h -> session?.mapLabels(w, h) ?: emptyList() },
                        onTarget = { id -> session?.let { s -> scope.launch { s.setTarget(id) } } },
                        onToggleBrakes = ::onToggleBrakes,
                        onToggleRcs = ::onToggleRcs,
                        onToggleReverse = ::onToggleReverse,
                        onToggleDeploy = ::onToggleDeploy,
                        onUndock = { part -> session?.let { s -> scope.launch { s.undock(part) } } },
                        onFound = { founded -> session?.let { s -> scope.launch { s.found(founded) } } },
                        onRefuel = { on -> session?.let { s -> scope.launch { s.refuel(on) } } },
                        onUnload = { on -> session?.let { s -> scope.launch { s.unload(on) } } },
                        onRefine = { base, on -> session?.let { s -> scope.launch { s.refine(base, on) } } },
                        onToggleDrill = ::onToggleDrill,
                        onToggleRefine = ::onToggleRefine,
                        onDive = { onBallast(1) },
                        onRise = { onBallast(-1) },
                        onHoldDepth = ::onToggleHoldDepth,
                        onToggleFlaps = ::onToggleFlaps,
                        onGroup = { group -> session?.let { s -> scope.launch { s.toggleGroup(group) } } },
                        onWinch = ::onWinch,
                        onStationKeep = ::onStationKeep,
                        onHook = { session?.let { s -> scope.launch { s.hook() } } },
                        onReleaseLine = { session?.let { s -> scope.launch { s.releaseLine() } } },
                        onCruise = ::onCruise,
                        onDockPilot = { who ->
                            session?.let { s ->
                                val shared = s.sharedWith ?: return@let
                                val pilot = when (who) { "me" -> s.myId; "them" -> shared.otherId; else -> "" }
                                scope.launch { s.setDockPilot(pilot) }
                            }
                        },
                        onStickMode = ::onStickMode,
                        onSteering = ::onSteering,
                        onCameraMode = ::onCameraMode,
                        onToggleMap = ::onToggleMap,
                        onJoin = ::onJoin,
                        onExit = { navigateTo(AppScreen.PLAY) },
                        rewind = com.rm.apogee.ui.screens.RewindActions(
                            onSavePoint = ::takeSavePoint,
                            onLoadSavePoint = ::loadSavePoint,
                            onRevert = ::revertToLaunch,
                        ),
                        onWarp = { rate -> session?.let { s -> scope.launch { s.setWarp(rate) } } },
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
                            if (why == null) session?.let { s -> scope.launch { s.unlock(id) } }
                            why
                        },
                        crewActions = com.rm.apogee.ui.components.CrewActions(
                            onEva = { id -> session?.let { s -> scope.launch { s.eva(id) } }; hudState.statusOpen = null },
                            onMove = { id -> session?.let { s -> scope.launch { s.moveCrew(id) } } },
                            onBoard = { session?.let { s -> scope.launch { s.board() } } },
                            onJump = { session?.let { s -> scope.launch { s.jump() } } },
                            onGrab = { on -> session?.let { s -> scope.launch { s.grab(on) } } },
                            onFlag = { session?.let { s -> scope.launch { s.plantFlag() } } },
                        ),
                        burnActions = com.rm.apogee.ui.components.BurnActions(
                            onNudge = { p, n, r -> session?.let { s -> scope.launch { s.nudgeBurn(p, n, r) } } },
                            onShift = { dt -> session?.let { s -> scope.launch { s.shiftBurn(dt) } } },
                            onEdited = { session?.let { s -> scope.launch { s.burnEdited() } } },
                            onDelete = { session?.let { s -> scope.launch { s.deleteBurn() } } },
                            onWarpTo = { session?.let { s -> scope.launch { s.warpToBurn() } } },
                            onAutoBurn = { on -> session?.let { s -> scope.launch { s.setAutopilot(on, s.localAutoLand) } } },
                            onAutoLand = { on -> session?.let { s -> scope.launch { s.setAutopilot(s.localAutoBurn, on) } } },
                        ),
                        craftChoices = { session?.myCraft() ?: emptyList() },
                        currentCraft = { session?.controlledCraft },
                        onFlyCraft = { id -> session?.let { s -> scope.launch { s.flyCraft(id) } } },
                        onRemoveCraft = { id -> session?.let { s -> scope.launch { s.removeCraft(id) } } },
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
                            onOpenShared = { host.pickSharedCraft() },
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
                        onBack = ::goBack,
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
                        onBack = ::goBack,
                    )
                }
                }
            }
    }

    /**
     * A renderer with nothing to show, under Quick Launch's list, there only to draw the craft
     * pictures. They're drawn by GL, which the menus don't otherwise run, so a craft that hadn't
     * been drawn in the Vehicle Assembly or in flight had an empty square. The menu covers it.
     */
    private var pictureRenderer: GlRenderer? = null

    private fun showPictureSurface() {
        if (pictureRenderer != null || renderer != null) return
        val drawer = GlRenderer({ host.detectTier() }, FrameBus()) { tier -> settings.lastDetectedTier = tier }
        drawer.thumbnails = partThumbnails
        pictureRenderer = drawer
        host.showSurface(drawer, gestures)
    }

    private fun hidePictureSurface() {
        if (pictureRenderer == null) return
        host.hideSurface()
        pictureRenderer = null
    }

    /** Back a screen, from a Back button or the system's back. */
    private fun goBack() = navigateTo(appScreen.parent ?: AppScreen.MENU)

    fun navigateTo(target: AppScreen) {
        if (target == appScreen) return
        val wasInWorld = appScreen.needsWorldSurface
        val wasBrowsing = appScreen == AppScreen.JOIN_GAME
        appScreen = target
        if (target == AppScreen.QUICK_LAUNCH) showPictureSurface() else hidePictureSurface()
        if (target == AppScreen.RESUME_FLIGHT) refreshResumeCraft()
        if (target == AppScreen.CREW) refreshCrew()
        if (target == AppScreen.QUICK_LAUNCH) refreshQuickLaunch()
        if (target == AppScreen.PLAY || target == AppScreen.PROGRAM) refreshProgram()

        // Discovery holds a multicast lock and a socket, so it only runs while the browser is
        // actually on screen.
        if (target == AppScreen.JOIN_GAME) {
            joinError = null
            if (manualAddress.isEmpty()) manualAddress = settings.lastServerAddress
            serverBrowser.start(scope)
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

    /** The craft on the floor, as a file, out through the share sheet (or a download). */
    private fun shareCraft() {
        val builder = builderSession ?: return
        val design = builder.builder.design
        if (design.parts.isEmpty()) return
        runCatching { host.shareCraft(design.name, craftStore.encode(design)) }
            .onFailure { builder.statusMessage = "Couldn't share it: ${it.message}" }
    }

    /**
     * A craft file someone shared, as [text], or null when it couldn't be read: checked, saved with
     * the player's own craft under a free name, and opened in the builder. One with parts this
     * version doesn't have is refused, with why.
     */
    fun importCraft(text: String?) {
        // Not in the middle of a flight. It waits in the builder for next time.
        if (session != null) {
            host.toast("Go back to the menu to open a shared craft")
            return
        }
        val result = if (text == null) Result.failure(IllegalArgumentException("Couldn't read that file")) else craftStore.decode(text, StockParts.catalog)
        result.onFailure {
            host.toast(it.message ?: "That isn't a craft file")
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

    /** Reads the saved craft for Quick Launch, off the main thread, choosing the last one again. */
    private fun refreshQuickLaunch() {
        quickSite = settings.quickSite.ifBlank { null }
        val store = craftStore
        scope.launch {
            val entries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.rm.apogee.game.CraftShelf.read(store.list(), store, StockParts.catalog, partThumbnails)
            }
            quickEntries = entries
            quickCraft = settings.quickCraft.takeIf { name -> entries.any { it.saved.fileName == name } }
                ?: entries.firstOrNull { it.saved.name == "Starter I" }?.saved?.fileName
                ?: entries.firstOrNull()?.saved?.fileName
        }
    }

    /** The chosen craft onto the chosen site, in place of the one flown last. */
    private fun quickLaunch() {
        val entry = quickEntries.firstOrNull { it.saved.fileName == quickCraft } ?: return
        pendingLaunchDesign = entry.design.withoutEmptyStages()
        pendingLaunchSite = quickSite
        pendingFresh = true
        navigateTo(AppScreen.FLIGHT)
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

        scope.launch {
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
        scope.launch { current.setThrottle(value.toDouble()) }
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
        if (current.steerByScreen) {
            // Read by the screen: up is up the screen, whatever the craft. Only a nose pointed
            // toward it keeps the pitch style, as it always has on a rocket.
            val reversed = !current.screenTilts && settings.pitchStyle.reverses(current.controlledOrientation)
            val pitch = commandedPitch.toDouble() * if (reversed) -1.0 else 1.0
            val yaw = commandedYaw.toDouble()
            val roll = commandedRoll.toDouble()
            scope.launch { current.setAttitude(pitch, yaw, roll) }
            return
        }
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
        scope.launch { current.setAttitude(pitch, yaw, roll) }
    }

    // --- a keyboard ------------------------------------------------------------

    private val keysDown = HashSet<String>()
    private var throttleKeysAt = 0L

    /**
     * A key [down] or up, by its code ("KeyW", "Space", "ShiftLeft"), for a host with a keyboard.
     * In flight, W and S pitch, A and D yaw, Q and E roll (the same as the stick, so a rover or a
     * boat steers with them too), Shift and Ctrl open and close the throttle, Z and X set it full
     * or off, Space stages, and M, T, R, B, G and F are the map, stability, thrusters, brakes, gear
     * and flaps. True if it was used.
     */
    fun key(code: String, down: Boolean): Boolean {
        if (appScreen != AppScreen.FLIGHT || session == null) {
            keysDown.clear()
            return false
        }
        val was = code in keysDown
        if (down) keysDown.add(code) else keysDown.remove(code)
        val pressed = down && !was
        when (code) {
            "KeyW", "KeyS", "KeyA", "KeyD", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight" -> {
                val pitch = axis("KeyW", "ArrowUp", "KeyS", "ArrowDown")
                val yaw = axis("KeyD", "ArrowRight", "KeyA", "ArrowLeft")
                onAttitude(pitch, yaw)
            }
            "KeyQ", "KeyE" -> onRoll(axis("KeyE", "KeyE", "KeyQ", "KeyQ"))
            "ShiftLeft", "ShiftRight", "ControlLeft", "ControlRight" -> throttleKeysAt = 0L
            "KeyZ" -> if (pressed) onThrottleChange(1f)
            "KeyX" -> if (pressed) onThrottleChange(0f)
            "Space" -> if (pressed) onStage()
            "KeyM" -> if (pressed) onToggleMap()
            "KeyV" -> if (pressed) onSteering(!hudState.steerByScreen)
            "KeyC" -> if (pressed) onCameraMode()
            "KeyT" -> if (pressed) onToggleSas()
            "KeyR" -> if (pressed) onToggleRcs()
            "KeyB" -> if (pressed) onToggleBrakes()
            "KeyG" -> if (pressed) onToggleDeploy()
            "KeyF" -> if (pressed) onToggleFlaps()
            else -> return false
        }
        return true
    }

    /** Every key let go: the window lost the keyboard, and their key-ups won't come. */
    fun releaseKeys() {
        for (code in keysDown.toList()) key(code, false)
        keysDown.clear()
    }

    /** +1, -1 or 0, from which of the keys for each way are down. */
    private fun axis(plus: String, plus2: String, minus: String, minus2: String): Float =
        (if (plus in keysDown || plus2 in keysDown) 1f else 0f) - (if (minus in keysDown || minus2 in keysDown) 1f else 0f)

    /** Shift and Ctrl held move the throttle, all the way in a second and a half. */
    private fun throttleKeys(nowNanos: Long) {
        val up = "ShiftLeft" in keysDown || "ShiftRight" in keysDown
        val down = "ControlLeft" in keysDown || "ControlRight" in keysDown
        if (up == down) { throttleKeysAt = 0L; return }
        if (throttleKeysAt != 0L) {
            val step = ((nowNanos - throttleKeysAt) / 1e9f / THROTTLE_KEY_SECONDS) * if (up) 1f else -1f
            val next = (hudState.throttle + step).coerceIn(0f, 1f)
            if (next != hudState.throttle) onThrottleChange(next)
        }
        throttleKeysAt = nowNanos
    }

    /** The camera's next way of following the craft, kept for the next flight too. */
    private fun onCameraMode() {
        val current = session ?: return
        val next = current.camera.mode.next()
        current.camera.mode = next
        settings.cameraMode = next
        hudState.cameraMode = next
    }

    /** Reads the stick by the screen, or by the craft's nose, for this flight. */
    private fun onSteering(byScreen: Boolean) {
        val current = session ?: return
        if (hudState.rcsSlide) onStickMode(false)
        current.chooseSteering(byScreen)
        hudState.steerByScreen = byScreen
    }

    private fun onToggleMap() {
        val current = session ?: return
        val enabled = !hudState.mapMode
        hudState.mapMode = enabled
        current.mapMode = enabled
    }

    private fun onStage() {
        val current = session ?: return
        scope.launch { current.stage() }
    }

    private fun onJoin() {
        val current = session ?: return
        scope.launch { current.join() }
    }

    /**
     * Takes the craft being flown out of the world and goes back to the menu, once the server has
     * done it, or the world saved on the way out would still have it.
     */
    private fun onRetire() {
        val current = session ?: return
        scope.launch {
            current.retire()
            navigateTo(AppScreen.PLAY)
        }
    }

    private fun onToggleDrill() {
        val power = hudState.power ?: return
        hudState.power = power.copy(drilling = !power.drilling)
        val current = session ?: return
        scope.launch { current.setIndustry(!power.drilling, power.refining) }
    }

    private fun onToggleRefine() {
        val power = hudState.power ?: return
        hudState.power = power.copy(refining = !power.refining)
        val current = session ?: return
        scope.launch { current.setIndustry(power.drilling, !power.refining) }
    }

    /** Floods the tanks ([mode] 1) or blows them (-1), or stops if it's tapped again. */
    private fun onBallast(mode: Int) {
        val power = hudState.power ?: return
        val next = if (power.ballastMode == mode) 0 else mode
        hudState.power = power.copy(ballastMode = next, holdingDepth = -1f)
        val current = session ?: return
        scope.launch { current.setBallast(next) }
    }

    private fun onToggleHoldDepth() {
        val power = hudState.power ?: return
        val on = power.holdingDepth < 0f
        hudState.power = power.copy(ballastMode = 0, holdingDepth = if (on) hudState.telemetry.depth.toFloat().coerceAtLeast(0f) else -1f)
        val current = session ?: return
        scope.launch { current.holdDepth(on) }
    }

    private fun onToggleFlaps() {
        val down = !hudState.flaps
        hudState.flaps = down
        val current = session ?: return
        scope.launch { current.setFlaps(down) }
    }

    /** The winch: winding in if it's holding, holding if it's winding in. */
    private fun onWinch() {
        val power = hudState.power ?: return
        val mode = if (power.reel > 0) 0 else 1
        hudState.power = power.copy(reel = mode)
        val current = session ?: return
        scope.launch { current.reel(mode) }
    }

    /** The keeper core: holding still where it is, or letting go. */
    private fun onStationKeep() {
        val power = hudState.power ?: return
        val on = !power.keeping
        hudState.power = power.copy(keeping = on)
        if (on) hudState.sasEnabled = true
        val current = session ?: return
        scope.launch { current.setStationKeep(on) }
    }

    private fun onCruise(on: Boolean) {
        if (on) hudState.sasEnabled = true
        val current = session ?: return
        scope.launch { current.setCruise(on) }
    }

    private fun onToggleDeploy() {
        val deployed = !(hudState.power?.deployed ?: false)
        hudState.power = hudState.power?.copy(deployed = deployed)
        val current = session ?: return
        scope.launch { current.setDeployed(deployed) }
    }

    private fun onToggleReverse() {
        val engaged = !hudState.reverse
        hudState.reverse = engaged
        val current = session ?: return
        scope.launch { current.setReverse(engaged) }
    }

    private fun onToggleBrakes() {
        val engaged = !hudState.brakes
        hudState.brakes = engaged
        val current = session ?: return
        scope.launch { current.setBrakes(engaged) }
    }

    private fun onToggleSas() {
        val enabled = !hudState.sasEnabled
        hudState.sasEnabled = enabled
        val current = session ?: return
        scope.launch { current.setSas(enabled) }
    }

    // --- the 3D world's lifecycle -------------------------------------------

    private fun enterWorld(screen: AppScreen) {
        host.fullscreen(true)
        hudState.reset()
        commandedPitch = 0f; commandedYaw = 0f; commandedRoll = 0f
        slideRight = 0f; slideAway = 0f; slideLift = 0f

        val glRenderer = GlRenderer({ host.detectTier() }, frameBus) { tier ->
            // This arrives on the GL thread. The terrain builder needs the tier to know how finely
            // to sample, so it's created here instead of guessed at earlier.
            settings.lastDetectedTier = tier
            host.runOnMain {
                detectedTier = tier
                session?.attachTerrain(rendererTerrainSource!!, settings.qualityOverride ?: tier)
            }
        }
        rendererTerrainSource = glRenderer.terrainSource
        glRenderer.thumbnails = partThumbnails
        renderer = glRenderer
        perfHints = host.perfHints()

        if (screen == AppScreen.BUILDER) {
            val builder = BuilderSession(
                frameBus, StockParts.catalog, craftStore,
                com.rm.apogee.core.craft.AssemblyStore(host.folder(if (careerMode) "assemblies-career" else "assemblies")),
            )
            // Somewhere of the player's own to launch from, as well as the Cape.
            builder.baseSites = runCatching { openSoloWorld().baseSites(settings.clientId) }.getOrDefault(emptyList())
            // In a career, only what the player has unlocked, and no more than their pad can take.
            builder.career = openSoloWorld().program?.careerOf(settings.clientId)
            partThumbnails.request(StockParts.catalog)
            builder.thumbnails = partThumbnails
            builder.start(scope)
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
                    scope = scope,
                    world = openSoloWorld(),
                    siteId = pendingLaunchSite,
                    // Quick Launch is a new flight: the craft flown last time gets cleared away
                    // and the chosen one put on its site. A launch from the builder adds its craft
                    // to the world, and Out There names the one to fly.
                    freshFlight = (pendingLaunchDesign == null || pendingFresh) && pendingResume == null,
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
                    scope = scope,
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
            pendingFresh = false
            pendingResume = null
            pendingMode = SessionMode.Solo
            rendererTerrainSource?.let { source ->
                newSession.attachTerrain(source, settings.qualityOverride ?: detectedTier
                    ?: QualityTier.MEDIUM)
            }
            newSession.start(scope)
            newSession.camera.mode = settings.cameraMode
            hudState.cameraMode = settings.cameraMode
            session = newSession
        }

        // The builder picks and pans in pixels, so the gestures give it the surface's size from the
        // start, not only once a finger has touched it.
        host.showSurface(glRenderer, gestures)
    }

    /** The touches on the world's surface. */
    val gestures = WorldGestures(this)

    /** Whichever camera the current view is looking through. */
    fun activeCamera(): com.rm.apogee.game.CameraController? {
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

    /** This world's save point, and the craft and time beside it. */
    private fun savePointStore(): WorldStore = WorldStore(worldFolder, if (careerMode) "career-savepoint.json" else "solo-savepoint.json")
    private fun savePointNote(): String = if (careerMode) "career-savepoint.txt" else "solo-savepoint.txt"

    /** This world's save point, read from disk the first time it's asked for. */
    private fun currentSavePoint(): SavePoint? {
        if (savePoints.containsKey(careerMode)) return savePoints[careerMode]
        val point = runCatching {
            val save = savePointStore().load().getOrThrow()
            val note = (worldFolder.read(savePointNote()) ?: "").lines()
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
            val at = host.timeNow()
            host.runOnMain {
                savePoints[career] = SavePoint(save, vessel, at)
                hudState.banner = HudState.Banner("SAVE POINT", "Taken at $at", good = true, id = System.nanoTime())
            }
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                savePointStore().save(save)
                    .onFailure { Log.w(TAG, "Could not keep the save point: ${it.message}") }
                runCatching { worldFolder.write(savePointNote(), "${vessel ?: ""}\n$at\n") }
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
        // Before tearing the session down, while the world still hangs together.
        if (session != null) saveSoloWorld()

        session?.stop(); session = null
        builderSession?.stop(); builderSession = null
        perfHints?.close(); perfHints = null

        host.hideSurface()
        renderer = null
        rendererTerrainSource = null

        frameBus.clear()
        hudState.reset()
        if (!host.fullscreenMenus) host.fullscreen(false)
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
            current.debugHideSea = host.debugSwitch("debug-no-sea")
            perfLogging = host.debugSwitch("debug-perf")
            glRenderer.timePasses = host.debugSwitch("debug-perf-passes")
            com.rm.apogee.audio.AudioEngine.logging = host.debugSwitch("debug-sound")
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
            Log.i(
                "ApogeePerf",
                "fps %.1f draw %.1f ms build %.1f ms sea-build %.1f ms clouds-list %.0f ms sea %s".format(
                    frames / seconds, 1000.0 * seconds / frames.coerceAtLeast(1), perfBuild / perfSamples,
                    perfSea / perfSamples, current.cloudListMillis, if (current.debugHideSea) "off" else "on",
                ),
            )
            glRenderer.takePassReport()?.let { Log.i("ApogeePerf", "passes $it") }
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

    /** The HUD's values brought up to date, once each frame the display shows. */
    private fun frameTick() {
                val glRenderer = renderer ?: return
                glRenderer.shadowChoice = settings.shadowQuality
                hudState.frameTimeMillis = glRenderer.lastFrameTimeNanos.get() / 1_000_000f

                if (keysDown.isNotEmpty()) throttleKeys(System.nanoTime())
                pad.tick(
                    System.nanoTime(), padMode(),
                    if (hudState.isSuit) com.rm.apogee.input.PadLayer.ON_FOOT else com.rm.apogee.input.PadLayer.FLYING,
                    padTarget,
                )
                session?.let { current ->
                    current.steeringStyle = settings.steeringStyle
                    hudState.steerByScreen = current.steerByScreen
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

    // --- lifecycle -----------------------------------------------------------

    /** The app's gone to the background. */
    fun pause() {
        com.rm.apogee.audio.AudioEngine.pause(true)
    }

    /**
     * Saves the solo world whenever the app goes out of sight, not only when leaving a flight
     * through the menu. Android can end a backgrounded app without another word, and a closed tab is
     * gone, and everything flown since the last save would go with it. It's taken on the server's
     * tick thread, between steps, since the world is still running.
     */
    fun hidden() {
        val world = soloWorld ?: return
        val running = session
        // In a flight the server is still stepping it, and otherwise it's idle. Joined to someone
        // else's game, there's no solo world running.
        if (running == null) saveSoloWorld()
        else running.betweenTicks { soloWorldStore.save(world.save()) }
    }

    /** Back from the background. */
    fun resume() {
        com.rm.apogee.audio.AudioEngine.pause(false)
        // On a phone, full screen everywhere, menus too, as ScorchDroid is. The system bars come
        // back with a swipe and go again by themselves.
        if (host.fullscreenMenus || appScreen.needsWorldSurface) host.fullscreen(true)
    }

    fun destroy() {
        serverBrowser.stop()
        leaveWorld()
        com.rm.apogee.audio.AudioEngine.stop()
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

        /** How long Shift or Ctrl takes to move the throttle from off to full, in seconds. */
        const val THROTTLE_KEY_SECONDS = 1.5f

        /** The time warps a controller steps through, the same as the warp picker's. */
        val WARP_STEPS: List<Double> = listOf(0.0, 1.0, 2.0, 4.0) + World.WARP_RATES.filter { it > World.PHYSICS_WARP }

        const val TAG = "Apogee"
    }
}
