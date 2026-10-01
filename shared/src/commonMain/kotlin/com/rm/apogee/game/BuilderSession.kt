package com.rm.apogee.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.rm.apogee.core.craft.Assemblies
import com.rm.apogee.core.craft.Assembly
import com.rm.apogee.core.craft.Attachment
import com.rm.apogee.core.craft.CraftBuilder
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.craft.OpenNode
import com.rm.apogee.core.craft.SavedCraft
import com.rm.apogee.core.craft.SymmetryMode
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.RenderFrame
import com.rm.apogee.render.RenderItem
import com.rm.apogee.render.StackCaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.tan
import com.rm.apogee.platform.System
import com.rm.apogee.platform.format
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile

/**
 * The vehicle assembly building: editing state, rendering and picking.
 *
 * Unlike [GameSession] there's no server. A design being built isn't part of the world: nothing
 * simulates it, nobody else sees it, and it has no position until it's launched as a spawn command.
 */
class BuilderSession(
    private val frameBus: FrameBus,
    private val catalog: PartCatalog,
    private val store: CraftStore,
    /** Saved pieces of craft, to put on again. Null for none. */
    private val assemblies: com.rm.apogee.core.craft.AssemblyStore? = null,
) {
    val builder = CraftBuilder(catalog)
    // FIXED, not RADIAL: there's no planet here, and a design centred below the origin would draw
    // upside down.
    val camera = CameraController(UpReference.FIXED)

    /**
     * What's in hand to put on: a drawer part (kept in hand to place again and again) or a copy of
     * a piece of the craft, put on once.
     */
    class Held(val assembly: Assembly, val partId: String?, val once: Boolean, val name: String? = null)

    var held: Held? by mutableStateOf(null)
        private set

    /** The drawer part in hand, if what's being held is one. */
    val heldPartId: String? get() = held?.partId

    /** A part already placed that the player has tapped. The action bar is for it. */
    var selectedPartIndex: Int? by mutableStateOf(null)
        private set

    /**
     * Where the selected part is on screen, in pixels, for the action bar. Null when off screen.
     */
    var selectionAnchor: Offset? by mutableStateOf(null)
        private set

    /**
     * Something carried under a finger: a part dragged from the drawer or a piece lifted off the
     * craft. [rest] is the craft without it, drawn and snapped to meanwhile.
     */
    private class Carry(val assembly: Assembly, val lifted: Int?, val rest: CraftDesign, val symmetry: SymmetryMode)

    @Volatile private var carry: Carry? = null

    /** Where the carrying finger is, in pixels. Null when nothing is being carried. */
    var carryPoint: Offset? by mutableStateOf(null)
        private set

    /** The part being carried, for the picture under the finger. */
    var carryPartId: String? by mutableStateOf(null)
        private set

    /** Whether what's being carried has a node to go on where the finger is. */
    var carrySnapped: Boolean by mutableStateOf(false)
        private set

    @Volatile private var carryTarget: OpenNode? = null

    /** Moves the view's target off the middle of the craft. Two fingers slide it. */
    private val pan = Vec3()
    @Volatile private var viewWidth = 1f
    @Volatile private var viewHeight = 1f

    /**
     * Editing staging instead of structure: the panel lists stages, and a tap moves a part into the
     * chosen one.
     */
    var stagingMode: Boolean by mutableStateOf(false)
        private set

    /** The stage that tapped parts go into, and whose parts are lit up. */
    var selectedStage: Int? by mutableStateOf(null)
        private set

    /**
     * Where LAUNCH puts the craft: a [com.rm.apogee.core.world.LaunchSite] id, or null for the
     * design to decide (the sea for hulls, the pad for the rest).
     */
    var launchSiteId: String? by mutableStateOf(null)

    /**
     * Pads on the player's founded bases, offered as launch sites beside the Cape's. A craft
     * launched from one fills up there.
     */
    var baseSites: List<com.rm.apogee.core.world.LaunchSite> by mutableStateOf(emptyList())

    /** Every site you can launch from: the Cape's, then the player's bases'. */
    fun allSites(): List<com.rm.apogee.core.world.LaunchSite> = com.rm.apogee.core.world.World.launchSites + baseSites

    var stats: CraftStats by mutableStateOf(CraftStats.analyze(builder.design, catalog))
        private set

    var savedCraft: List<SavedCraft> by mutableStateOf(emptyList())
        private set

    var statusMessage: String? by mutableStateOf(null)

    /** The player's career, or null in a sandbox, where everything is unlocked and unlimited. */
    var career: com.rm.apogee.core.career.CareerState? by mutableStateOf(null)
    val tree: com.rm.apogee.core.career.TechTree get() = com.rm.apogee.core.career.TechTree.stock

    /** Parts the career hasn't unlocked yet, mapped to the title of the node that unlocks each. */
    fun lockedParts(): Map<String, String> {
        val c = career ?: return emptyMap()
        val have = c.parts(tree)
        return tree.nodes.flatMap { node -> node.parts.map { it to node.title } }.filter { it.first !in have }.toMap()
    }

    /** Why the career won't let this launch where it's going, or null if it will (or this is a sandbox). */
    fun careerRefusal(): String? {
        val c = career ?: return null
        val design = designForLaunch() ?: return null
        return com.rm.apogee.core.career.CareerRules.refusal(tree, c, design, launchSiteId ?: automaticSite().id, catalog)
    }

    /**
     * What the launching facility can take against what this is: "12.3 / 45 t · 22 / 40 parts".
     * Null outside a career.
     */
    fun careerLimits(): Pair<String, Boolean>? {
        val c = career ?: return null
        val site = launchSiteId ?: automaticSite().id
        val facility = if (site.startsWith(com.rm.apogee.core.world.LaunchSite.BASE_SITE_PREFIX)) com.rm.apogee.core.career.Facility.PAD
            else com.rm.apogee.core.career.CareerRules.facilityFor(site)
        val limits = com.rm.apogee.core.career.CareerRules.limits(tree, c, facility) ?: return "No ${facility.title.lowercase()} yet" to true
        val mass = com.rm.apogee.core.career.CareerRules.massOf(builder.design, catalog)
        val parts = builder.design.parts.size
        val massText = if (limits.mass > 0.0) "%.1f / %.0f t".format(mass / 1000.0, limits.mass / 1000.0) else "%.1f t".format(mass / 1000.0)
        val partText = if (limits.parts > 0) "$parts / ${limits.parts} parts" else "$parts parts"
        val over = (limits.mass > 0.0 && mass > limits.mass) || (limits.parts > 0 && parts > limits.parts)
        return "${facility.title.uppercase()} · $massText · $partText" to over
    }

    /** Goes up on every edit so Compose recomposes off a plain mutable model. */
    var revision: Int by mutableIntStateOf(0)
        private set

    private var job: Job? = null

    /** Open nodes as of the last frame, with their design-space positions. */
    @Volatile private var visibleNodes: List<OpenNode> = emptyList()

    private val cameraPosition = Vec3()
    private val cameraRotation = Quat.identity()
    private val scratch = Vec3()

    fun start(scope: CoroutineScope) {
        refreshSavedList()
        refreshAssemblies()
        camera.distance = 18.0
        camera.pitch = 0.1
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                publishFrame()
                delay(PRESENT_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        frameBus.clear()
    }

    // --- editing -------------------------------------------------------------

    /** A part from the drawer into the hand, or with [partId] null, the hand emptied. */
    fun selectPart(partId: String?) {
        held = partId?.let { Held(Assembly.of(it), it, once = false) }
        selectedPartIndex = null
        revision++
    }

    /** Puts down whatever's in hand. */
    fun dropHeld() = selectPart(null)

    fun setViewSize(width: Float, height: Float) {
        if (width > 0f && height > 0f) { viewWidth = width; viewHeight = height }
    }

    /** Pixels down the left covered by an open panel. The craft is centred in what's left. */
    @Volatile var leftInset = 0f

    /** Pixels up the right covered by the open stats card. */
    @Volatile var rightInset = 0f

    /** The difference between the covered sides as drawn, eased rather than jumping. */
    private var shownInset = 0f

    // --- the selected part's actions -------------------------------------------

    /** What the action bar says about the selected part. */
    class Selection(
        val index: Int, val title: String, val count: Int, val root: Boolean, val stage: Int, val stageable: Boolean,
        /** Whether an action group can switch it, and which it's in, 0 for none. */
        val groupable: Boolean = false, val group: Int = 0,
    )

    val selection: Selection?
        get() {
            val index = selectedPartIndex ?: return null
            val design = builder.design
            if (index !in design.parts.indices) return null
            val placed = design.parts[index]
            val group = placed.symmetryGroup
            val roots = if (group >= 0) design.parts.indices.filter { design.parts[it].symmetryGroup == group } else listOf(index)
            val count = roots.flatMap { design.subtreeOf(it) }.toSet().size
            return Selection(
                index, catalog[placed.partId]?.title ?: placed.partId, count, placed.parentIndex < 0,
                builder.stageOf(index), builder.isStageable(index),
                builder.groupable(index), placed.group,
            )
        }

    fun clearSelection() {
        selectedPartIndex = null
        selectionAnchor = null
        revision++
    }

    /** The selected part and everything below it, copied into the hand to put on somewhere else. */
    fun duplicateSelected() {
        val index = selectedPartIndex ?: return
        val copy = builder.duplicate(index) ?: return
        held = Held(copy, null, once = true)
        selectedPartIndex = null
        statusMessage = "Copy in hand. Tap a green node, or drag it on"
        revision++
    }

    /** The selected part (and its symmetry partners) into the next action group: none, 1, 2, 3. */
    fun cycleGroupSelected() {
        val index = selectedPartIndex ?: return
        val next = (builder.design.parts[index].group + 1) % (com.rm.apogee.core.craft.Vessel.GROUPS + 1)
        if (builder.setGroup(index, next)) {
            statusMessage = if (next == 0) "In no group" else "In group $next. Its switch is on the action rail"
            onEdited()
        }
    }

    // --- saved pieces ------------------------------------------------------------

    /** The saved pieces, newest first, with their files. */
    var savedAssemblies: List<Pair<com.rm.apogee.core.craft.SavedAssembly, String>> by mutableStateOf(emptyList())
        private set

    fun refreshAssemblies() {
        savedAssemblies = assemblies?.list() ?: emptyList()
    }

    /** The selected part and everything below it, saved as [name] to put on again. */
    fun saveSelectedAssembly(name: String) {
        val index = selectedPartIndex ?: return
        val store = assemblies ?: return
        val piece = builder.duplicate(index) ?: return
        store.save(name, piece)
            .onSuccess { statusMessage = "Saved. It's in the drawer's Saved tab"; refreshAssemblies() }
            .onFailure { statusMessage = "Couldn't save it: ${it.message}" }
    }

    /** A saved piece into the hand, to put on once. */
    fun holdAssembly(fileName: String) {
        val saved = savedAssemblies.firstOrNull { it.second == fileName }?.first ?: return
        val locked = lockedParts()
        saved.parts.firstOrNull { it.partId in locked }?.let {
            statusMessage = "${catalog[it.partId]?.title ?: it.partId} needs ${locked[it.partId]}"
            return
        }
        if (saved.parts.any { catalog[it.partId] == null }) {
            statusMessage = "That piece has parts this version doesn't have"
            return
        }
        held = Held(saved.assembly, null, once = true, name = saved.name)
        selectedPartIndex = null
        statusMessage = "${saved.name} in hand. Tap a green node, or drag it on"
        revision++
    }

    fun deleteAssembly(fileName: String) {
        assemblies?.delete(fileName)
        refreshAssemblies()
    }

    fun turnSelected() {
        val index = selectedPartIndex ?: return
        if (builder.turn(index)) onEdited() else statusMessage = "The first part has nothing to turn about"
    }

    /** Over to the staging, with the selected part's stage chosen. */
    fun stageSelected() {
        val index = selectedPartIndex ?: return
        val stage = builder.stageOf(index)
        if (!stagingMode) toggleStagingMode()
        selectedStage = stage.takeIf { it >= 0 }
        revision++
    }

    // --- staging ---------------------------------------------------------------

    /** One stage as the panel lists it: its parts by name, with the same parts grouped together. */
    class StageEntry(val index: Int, val parts: List<String>)

    val stageEntries: List<StageEntry>
        get() = builder.design.stages.mapIndexed { i, stage ->
            val names = stage.activatedParts
                .mapNotNull { builder.design.parts.getOrNull(it)?.partId }
                .groupingBy { it }.eachCount()
                .map { (id, count) ->
                    val title = catalog[id]?.title ?: id
                    if (count > 1) "$title ×$count" else title
                }
            StageEntry(i, names)
        }

    val manualStaging: Boolean get() = builder.design.manualStaging

    fun toggleStagingMode() {
        stagingMode = !stagingMode
        held = null
        selectedPartIndex = null
        selectionAnchor = null
        selectedStage = if (stagingMode) builder.design.stages.indices.firstOrNull() else null
        statusMessage = if (stagingMode) "Tap a stage, then tap parts to move them into it" else null
        revision++
    }

    fun selectStage(index: Int?) {
        selectedStage = index?.takeIf { it in builder.design.stages.indices }
        revision++
    }

    /** A new stage just after the selected one (or last), chosen and ready to fill. */
    fun addStage() {
        val at = (selectedStage ?: (builder.design.stages.size - 1)) + 1
        if (builder.addStage(at)) {
            selectedStage = at
            statusMessage = "Stage $at added. Tap parts to move them into it"
            onEdited()
        }
    }

    fun removeStage(index: Int) {
        if (builder.removeStage(index)) {
            selectedStage = selectedStage?.let { minOf(it, builder.design.stages.size - 1) }?.takeIf { it >= 0 }
            onEdited()
        } else {
            statusMessage = "The only stage can't be removed while it fires something"
        }
    }

    /** Moves [index] one place later in the firing order ([by] 1) or earlier (-1). */
    fun shiftStage(index: Int, by: Int) {
        val to = index + by
        if (builder.moveStage(index, to)) {
            selectedStage = to
            onEdited()
        }
    }

    fun useAutomaticStaging() {
        builder.useAutomaticStaging()
        selectedStage = builder.design.stages.indices.firstOrNull()
        statusMessage = "Staging is automatic again"
        onEdited()
    }

    private fun tapToStage(x: Float, y: Float, width: Float, height: Float) {
        val index = pickPart(x, y, width, height, stageableOnly = true) ?: run {
            statusMessage = "Only engines, decouplers, chutes and legs are staged"
            return
        }
        val target = selectedStage
        val current = builder.stageOf(index)
        val title = catalog[builder.design.parts[index].partId]?.title ?: "Part"
        if (target == null || target == current) {
            // Nothing to move it to, so show where it is instead.
            selectedStage = current.takeIf { it >= 0 }
            statusMessage = if (current >= 0) "$title fires in stage $current" else "$title is in no stage"
            revision++
            return
        }
        if (builder.moveToStage(index, target)) {
            statusMessage = "$title moved to stage $target"
            onEdited()
        }
    }

    fun toggleSymmetry() {
        builder.symmetry = builder.symmetry.next(builder.orientation)
        revision++
    }

    val symmetry: SymmetryMode get() = builder.symmetry

    val orientation: CraftOrientation get() = builder.orientation

    /**
     * Stands the craft up or lays it down. Nothing moves in design space: the camera turns so the
     * new up is up on screen, and the mounting rules follow.
     */
    fun toggleOrientation() {
        builder.orientation = builder.orientation.other()
        statusMessage = "${builder.orientation.label}: " + when (builder.orientation) {
            CraftOrientation.VERTICAL -> "stands on its tail"
            CraftOrientation.HORIZONTAL -> "lies along the ground, nose forward"
        }
        onEdited()
    }

    /**
     * Acts on a tap in the 3D view: with a part held, onto the nearest open attach node; with
     * nothing held, selects a placed part. Nodes are picked in screen space, so the generous pixel
     * radius stays the same size at any zoom.
     */
    fun tap(x: Float, y: Float, width: Float, height: Float) {
        setViewSize(width, height)
        if (stagingMode) {
            tapToStage(x, y, width, height)
            return
        }
        val inHand = held
        if (inHand == null) {
            selectedPartIndex = pickPart(x, y, width, height)
            if (selectedPartIndex == null) selectionAnchor = null
            revision++
            return
        }

        if (builder.isEmpty) {
            builder.placeRoot(inHand.assembly.rootPartId)
            onEdited()
            return
        }

        val node = pickNode(x, y, width, height, inHand.assembly.rootPartId, builder.design) ?: run {
            // Off the craft altogether, so put it down.
            if (pickPart(x, y, width, height) == null) {
                held = null
                statusMessage = null
                revision++
            } else {
                statusMessage = "No attachment point there"
            }
            return
        }
        val added = builder.attachAssembly(inHand.assembly, node)
        if (added.isEmpty()) {
            statusMessage = "That part won't fit here"
        } else {
            statusMessage = null
            if (inHand.once) held = null
            onEdited()
        }
    }

    /** Two taps: back to the whole craft, in the middle. */
    fun recentre() {
        pan.setZero()
        val design = builder.design
        camera.frameFor(designExtent(design, designCentre(design)))
    }

    /** Two fingers moved the view by ([dx], [dy]) pixels, so slide the view target with them. */
    fun panBy(dx: Float, dy: Float) {
        val metresPerPixel = 2.0 * kotlin.math.tan(FOV_Y * 0.5) * camera.distance / viewHeight
        val right = cameraRotation.rotate(Vec3.unitX(), Vec3())
        val up = cameraRotation.rotate(Vec3.unitY(), Vec3())
        pan.addScaledInPlace(right, -dx * metresPerPixel).addScaledInPlace(up, dy * metresPerPixel)
        val design = builder.design
        val limit = designExtent(design, designCentre(design)) + PAN_MARGIN
        if (pan.length > limit) pan.mulInPlace(limit / pan.length)
    }

    // --- carrying ----------------------------------------------------------------

    /** A part dragged out of the drawer. */
    fun beginCarry(partId: String) {
        if (stagingMode) return
        selectedPartIndex = null
        startCarry(Carry(Assembly.of(partId), null, builder.design, builder.symmetry), partId)
    }

    /**
     * Held still on a part: lifts it, with its partners and everything below, to carry elsewhere.
     * False over nothing, or over the first part.
     */
    fun liftAt(x: Float, y: Float, width: Float, height: Float): Boolean {
        setViewSize(width, height)
        if (stagingMode) return false
        val index = pickPart(x, y, width, height) ?: return false
        val lifted = builder.lift(index) ?: run {
            statusMessage = "The first part holds everything else, so it stays"
            return false
        }
        // As many as were lifted go back on, so four fins stay four.
        val symmetry = when {
            lifted.copies <= 1 -> SymmetryMode.NONE
            builder.orientation == CraftOrientation.HORIZONTAL -> SymmetryMode.MIRROR
            else -> SymmetryMode.entries.firstOrNull { it.count == lifted.copies } ?: builder.symmetry
        }
        selectedPartIndex = null
        selectionAnchor = null
        startCarry(Carry(lifted.assembly, index, lifted.rest, symmetry), lifted.assembly.rootPartId)
        carryTo(x, y)
        return true
    }

    private fun startCarry(c: Carry, partId: String) {
        carry = c
        carryPartId = partId
        carryTarget = null
        carrySnapped = false
        revision++
    }

    /** The carrying finger is at ([x], [y]), so snap to the best node near it. */
    fun carryTo(x: Float, y: Float) {
        val c = carry ?: return
        carryPoint = Offset(x, y)
        // A thumb's width above the fingertip, so the node isn't under it.
        val target = if (c.rest.parts.isEmpty()) null
            else pickNode(x, y - viewHeight * FINGER_LIFT, viewWidth, viewHeight, c.assembly.rootPartId, c.rest)
        carryTarget = target
        carrySnapped = target != null || c.rest.parts.isEmpty()
    }

    /** Let go: onto the node it snapped to, or back where it came from. */
    fun endCarry() {
        val c = carry ?: return
        val target = carryTarget
        when {
            c.rest.parts.isEmpty() && c.lifted == null -> {
                builder.placeRoot(c.assembly.rootPartId)
                onEdited()
            }
            target == null -> statusMessage = if (c.lifted != null) "Put back where it was" else null
            c.lifted != null -> {
                if (builder.move(c.lifted, target, c.symmetry).isEmpty()) statusMessage = "That won't fit there" else onEdited()
            }
            else -> {
                val saved = builder.symmetry
                if (builder.attachAssembly(c.assembly, target).isEmpty()) statusMessage = "That part won't fit here" else onEdited()
                builder.symmetry = saved
            }
        }
        cancelCarry()
    }

    fun cancelCarry() {
        carry = null
        carryTarget = null
        carryPoint = null
        carryPartId = null
        carrySnapped = false
        revision++
    }

    fun deleteSelected() {
        val index = selectedPartIndex ?: return
        if (builder.remove(index)) {
            selectedPartIndex = null
            selectionAnchor = null
            onEdited()
        } else {
            statusMessage = "The root part can't be removed"
        }
    }

    fun undo() {
        if (builder.undo()) onEdited() else statusMessage = "Nothing to undo"
    }

    fun redo() {
        if (builder.redo()) onEdited()
    }

    fun clear() {
        builder.clear()
        selectedPartIndex = null
        onEdited()
    }

    // --- persistence ---------------------------------------------------------

    fun rename(name: String) {
        builder.name = name
        onEdited()
    }

    fun save() {
        if (builder.isEmpty) {
            statusMessage = "Nothing to save"
            return
        }
        store.save(builder.design)
            .onSuccess {
                statusMessage = "Saved \"${it.name}\""
                refreshSavedList()
            }
            .onFailure { statusMessage = "Couldn't save: ${it.message}" }
    }

    fun load(saved: SavedCraft) {
        store.load(saved.fileName)
            .onSuccess {
                // Old stock designs had their fins and legs all pointing one way.
                builder.load(com.rm.apogee.core.craft.StockCraft.facingOutward(it, catalog))
                selectedPartIndex = null
                statusMessage = "Loaded \"${it.name}\""
                onEdited()
            }
            .onFailure { statusMessage = "Couldn't load: ${it.message}" }
    }

    /** A craft someone shared, already saved as [design]'s name, onto the floor to build on. */
    fun openShared(design: CraftDesign) {
        builder.load(design)
        selectedPartIndex = null
        statusMessage = "Opened \"${design.name}\". It's saved with your craft"
        refreshSavedList()
        onEdited()
    }

    fun delete(saved: SavedCraft) {
        store.delete(saved.fileName)
        refreshSavedList()
    }

    private fun refreshSavedList() {
        savedCraft = store.list()
        refreshLoadEntries()
    }

    /** For the craft pictures in the load list. The app sets it. */
    var thumbnails: com.rm.apogee.render.PartThumbnails? = null

    /** Every saved craft, newest first, once worked out. Empty until then. */
    var loadEntries: List<CraftShelf.Entry> by mutableStateOf(emptyList())
        private set

    private var loadJob: Job? = null

    /**
     * Reads and describes each saved craft off the main thread (it's every design in the store) and
     * asks for any pictures not drawn yet.
     */
    private fun refreshLoadEntries() {
        val list = savedCraft
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Default).launch {
            val entries = CraftShelf.read(list, store, catalog, thumbnails)
            loadEntries = entries
        }
    }

    private fun onEdited() {
        selectedStage = selectedStage?.takeIf { it in builder.design.stages.indices }
        stats = CraftStats.analyze(builder.design, catalog)
        revision++
    }

    /** Where Automatic would send the current design. */
    fun automaticSite(): com.rm.apogee.core.world.LaunchSite =
        com.rm.apogee.core.world.World.launchSiteFor(builder.design, catalog)

    /** The design as it would be launched. */
    fun designForLaunch(): CraftDesign? =
        if (builder.isEmpty || !stats.isFlyable) null else builder.design.withoutEmptyStages()

    // --- rendering -----------------------------------------------------------

    private fun publishFrame() {
        val c = carry
        // Carrying a piece lifted off, the craft is drawn without it.
        val design = c?.rest ?: builder.design
        val items = ArrayList<RenderItem>(design.parts.size + 16)

        // Frame the craft, not the design origin, so a tall stack stays centred as it grows.
        val centre = designCentre(design)
        camera.frameAtLeast(designExtent(design, centre))
        // Half the difference between the covered strips, so the craft sits centred between the
        // panels. Not while carrying, or the craft would slide out from under the target node.
        if (c == null) shownInset += (leftInset - rightInset - shownInset) * INSET_EASE
        val metresPerPixel = 2.0 * tan(FOV_Y * 0.5) * camera.distance / viewHeight
        val aside = cameraRotation.rotate(Vec3.unitX(), Vec3()).mulInPlace(-shownInset * 0.5 * metresPerPixel)
        camera.solve(Vec3().setTo(centre).addInPlace(pan).addInPlace(aside), cameraPosition, cameraRotation)

        camera.fixedUp.setTo(design.orientation.up)
        val caps = StackCaps.forDesign(design, catalog)
        // In staging the chosen stage's parts light up in the accent colour, and the selected part
        // with its partners.
        val staged = selectedStage?.takeIf { stagingMode }?.let { design.stages.getOrNull(it)?.activatedParts?.toSet() }.orEmpty()
        val selected = selectedPartIndex?.takeIf { c == null && it in design.parts.indices }
        val partners = selected?.let { index ->
            val group = design.parts[index].symmetryGroup
            if (group < 0) setOf(index) else design.parts.indices.filter { design.parts[it].symmetryGroup == group }.toSet()
        }.orEmpty()
        design.parts.forEachIndexed { index, placed ->
            val highlight = when {
                index in partners -> SELECTED_COLOR
                index in staged -> STAGE_COLOR
                else -> null
            }
            addPart(items, design, placed, caps[index], centre, highlight)
        }

        // Attach-node markers only while a part is in hand, and only the ones it can go on, so a
        // wheel held over a horizontal craft shows its underside and nothing that would be refused.
        val rootId = c?.assembly?.rootPartId ?: held?.assembly?.rootPartId
        val nodes = rootId?.let { usableNodes(design, it) }.orEmpty()
        visibleNodes = nodes
        val target = carryTarget.takeIf { c != null }
        for (node in nodes) {
            if (node === target) continue
            items.add(RenderItem(shape = NODE_MARKER, position = node.position.copy(), rotation = Quat.identity(), color = NODE_COLOR))
        }

        // What's being carried, where it would go: a see-through ghost, copies and all.
        if (c != null && target != null) {
            Assemblies.attach(design, c.assembly, target, c.symmetry, catalog)?.let { done ->
                val ghostCaps = StackCaps.forDesign(done.design, catalog)
                for (index in done.added) addPart(items, done.design, done.design.parts[index], ghostCaps[index], centre, GHOST_COLOR)
            }
        }

        // Where the action bar goes.
        val anchor = selected?.let { project(design.parts[it].position, viewWidth, viewHeight) }
        val was = selectionAnchor
        val now = anchor?.let { Offset(it.first, it.second) }
        if (now == null) { if (was != null) selectionAnchor = null }
        else if (was == null || (was - now).getDistance() > 2f) selectionAnchor = now

        frameBus.publish(
            RenderFrame(
                simTick = revision.toLong(),
                timestampNanos = System.nanoTime(),
                cameraPosition = cameraPosition.copy(),
                cameraRotation = cameraRotation.copy(),
                fovYRadians = FOV_Y,
                items = items,
            )
        )
    }

    private fun addPart(
        items: ArrayList<RenderItem>,
        design: CraftDesign,
        placed: com.rm.apogee.core.craft.PlacedPart,
        caps: Int,
        centre: Vec3,
        highlight: FloatArray?,
    ) {
        val def = catalog[placed.partId] ?: return
        val body = highlight ?: com.rm.apogee.render.PartModels.bodyColour(placed.partId)
        val leaves = ArrayList<com.rm.apogee.render.PartModels.Leaf>()
        val anim = com.rm.apogee.render.PartAnim()
        com.rm.apogee.render.PartModels.alignWheel(def, placed.rotation, design.orientation.forward, design.orientation.up, anim)
        com.rm.apogee.render.PartModels.alignSurface(def, placed.rotation, Vec3().setTo(placed.position).subInPlace(centre), anim)
        com.rm.apogee.render.PartModels.expand(def, caps, anim, leaves)
        com.rm.apogee.render.ShroudLook.forDesign(design, catalog).getOrNull(design.parts.indexOf(placed))?.let { shroud ->
            com.rm.apogee.render.ShroudLook.leaf(def, placed, shroud)?.let(leaves::add)
        }
        for (leaf in leaves) {
            items.add(
                RenderItem(
                    caps = leaf.caps,
                    shape = leaf.shape,
                    position = placed.rotation.rotate(leaf.position).addInPlace(placed.position),
                    rotation = placed.rotation * leaf.rotation,
                    color = highlight ?: com.rm.apogee.render.PartModels.colour(leaf.tint, body),
                )
            )
        }
    }

    /** Open nodes of [design] that part [partId] can go on. */
    private fun usableNodes(design: CraftDesign, partId: String): List<OpenNode> {
        val def = catalog[partId] ?: return emptyList()
        return Attachment.openNodes(design, catalog).filter {
            Attachment.accepts(def, it, design.orientation) && Attachment.mountNodeFor(def, it) != null
        }
    }

    /** The furthest part from [centre], so the camera can pull back to fit it. */
    private fun designExtent(design: CraftDesign, centre: Vec3): Double {
        if (design.parts.isEmpty()) return 0.0
        return design.parts.maxOf { placed ->
            val reach = catalog[placed.partId]?.boundsHalfExtents?.length ?: 0.0
            placed.position.distanceTo(centre) + reach
        }
    }

    private fun designCentre(design: CraftDesign): Vec3 {
        if (design.parts.isEmpty()) return Vec3.zero()
        val centre = Vec3.zero()
        design.parts.forEach { centre.addInPlace(it.position) }
        return centre.mulInPlace(1.0 / design.parts.size)
    }

    // --- picking -------------------------------------------------------------

    /**
     * Projects a design-space point to screen pixels, or null if it's behind the camera. Done
     * directly from the camera pose rather than through [com.rm.apogee.core.math.Mat4], since a
     * pixel's accuracy doesn't need narrowing to float.
     */
    private fun project(world: Vec3, width: Float, height: Float): Pair<Float, Float>? {
        scratch.setTo(world).subInPlace(cameraPosition)
        cameraRotation.inverseRotate(scratch, scratch)
        // The camera looks down -Z, so anything with z >= 0 is behind it.
        if (scratch.z > -NEAR_PLANE) return null

        val f = 1.0 / tan(FOV_Y * 0.5)
        val aspect = width / height
        val ndcX = (f / aspect) * scratch.x / -scratch.z
        val ndcY = f * scratch.y / -scratch.z
        return Pair(
            ((ndcX + 1.0) * 0.5 * width).toFloat(),
            ((1.0 - ndcY) * 0.5 * height).toFloat(),
        )
    }

    private fun pickNode(x: Float, y: Float, width: Float, height: Float, held: String, design: CraftDesign): OpenNode? {
        var best: OpenNode? = null
        var bestDistance = TAP_RADIUS_PIXELS * TAP_RADIUS_PIXELS
        for (node in usableNodes(design, held)) {
            val screen = project(node.position, width, height) ?: continue
            val dx = screen.first - x
            val dy = screen.second - y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = node
            }
        }
        return best
    }

    /**
     * The part under ([x], [y]): the nearest whose box the line of sight through that pixel hits,
     * so a small part in front wins and a big one is hit anywhere. Failing that, the part whose
     * middle is nearest the finger, within reach.
     */
    private fun pickPart(x: Float, y: Float, width: Float, height: Float, stageableOnly: Boolean = false): Int? {
        val design = builder.design
        val f = 1.0 / tan(FOV_Y * 0.5)
        val aspect = width / height
        val ndcX = (x / width) * 2.0 - 1.0
        val ndcY = 1.0 - (y / height) * 2.0
        val direction = cameraRotation.rotate(Vec3(ndcX * aspect / f, ndcY / f, -1.0).normalizeInPlace())
        val origin = cameraPosition.copy()

        var nearest: Int? = null
        var nearestT = Double.MAX_VALUE
        val o = Vec3(); val d = Vec3()
        design.parts.forEachIndexed { index, placed ->
            if (stageableOnly && !builder.isStageable(index)) return@forEachIndexed
            val half = catalog[placed.partId]?.boundsHalfExtents ?: return@forEachIndexed
            placed.rotation.inverseRotate(o.setTo(origin).subInPlace(placed.position), o)
            placed.rotation.inverseRotate(d.setTo(direction), d)
            val t = BoxRay.hit(o, d, half, PICK_PADDING) ?: return@forEachIndexed
            if (t < nearestT) { nearestT = t; nearest = index }
        }
        if (nearest != null) return nearest

        var best: Int? = null
        var bestDistance = PART_TAP_RADIUS_PIXELS * PART_TAP_RADIUS_PIXELS
        design.parts.forEachIndexed { index, placed ->
            if (stageableOnly && !builder.isStageable(index)) return@forEachIndexed
            val screen = project(placed.position, width, height) ?: return@forEachIndexed
            val dx = screen.first - x
            val dy = screen.second - y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    companion object {
        /**
         * A share of the screen's height: how far above the fingertip a carried part snaps from.
         */
        const val FINGER_LIFT = 0.04f

        private const val PRESENT_INTERVAL_MILLIS = 16L
        const val FOV_Y = Math.PI / 180.0 * 55.0
        const val NEAR_PLANE = 0.2

        /**
         * The snap radius in pixels. Generous on purpose: a fingertip covers far more than a node
         * marker.
         */
        const val TAP_RADIUS_PIXELS = 110f
        const val PART_TAP_RADIUS_PIXELS = 80f


        /** The share of the way to a new panel inset covered each frame. */
        private const val INSET_EASE = 0.2f

        /** Metres past the craft's reach that the view can be slid. */
        const val PAN_MARGIN = 4.0

        /** Metres added around each part's box when picking, so thin parts can still be hit. */
        const val PICK_PADDING = 0.15

        val NODE_MARKER = MeshSpec.Sphere(0.22)
        val NODE_COLOR = floatArrayOf(0.48f, 1.0f, 0.70f, 1f)
        val SELECTED_COLOR = floatArrayOf(1.0f, 0.82f, 0.45f, 1f)

        /** Where a carried part would go: the nodes' green, see-through. */
        val GHOST_COLOR = floatArrayOf(0.48f, 1.0f, 0.70f, 0.5f)

        /** The theme's accent, for the parts of the stage being edited. */
        val STAGE_COLOR = floatArrayOf(0.70f, 0.62f, 1.0f, 1f)
    }
}
