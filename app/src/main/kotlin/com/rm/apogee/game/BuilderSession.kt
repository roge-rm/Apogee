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

/**
 * The vehicle assembly building: editing state, its rendering, and picking.
 *
 * Unlike [GameSession] there is no server here. A design under construction is
 * not part of the world - nothing simulates it, nobody else can see it, and it
 * has no position. It becomes a world object only when it is launched, at which
 * point it travels as a spawn command like any other craft.
 */
class BuilderSession(
    private val frameBus: FrameBus,
    private val catalog: PartCatalog,
    private val store: CraftStore,
) {
    val builder = CraftBuilder(catalog)
    // FIXED, not RADIAL: there is no planet in the assembly building, and a
    // design centred below the origin would otherwise render upside down.
    val camera = CameraController(UpReference.FIXED)

    /**
     * What is in hand, waiting to be put on: a part picked from the drawer -
     * kept in hand to put on again and again - or a copy of a piece of the
     * craft, put on once.
     */
    class Held(val assembly: Assembly, val partId: String?, val once: Boolean)

    var held: Held? by mutableStateOf(null)
        private set

    /** The drawer part in hand, if what is held is one. */
    val heldPartId: String? get() = held?.partId

    /** An already-placed part the player has tapped: the action bar is for it. */
    var selectedPartIndex: Int? by mutableStateOf(null)
        private set

    /** Where the selected part is on screen, pixels, for the action bar to sit beside; null off screen. */
    var selectionAnchor: Offset? by mutableStateOf(null)
        private set

    /**
     * Something being carried under a finger: a part dragged out of the
     * drawer, or a piece of the craft lifted off it - [rest] the craft
     * without it, drawn and snapped to meanwhile.
     */
    private class Carry(val assembly: Assembly, val lifted: Int?, val rest: CraftDesign, val symmetry: SymmetryMode)

    @Volatile private var carry: Carry? = null

    /** Where the carrying finger is, pixels; null when nothing is carried. */
    var carryPoint: Offset? by mutableStateOf(null)
        private set

    /** The part being carried, for the picture under the finger. */
    var carryPartId: String? by mutableStateOf(null)
        private set

    /** Whether what is carried has a node to go on where the finger is. */
    var carrySnapped: Boolean by mutableStateOf(false)
        private set

    @Volatile private var carryTarget: OpenNode? = null

    /** Moves the view's target off the craft's middle: two fingers slide it. */
    private val pan = Vec3()
    @Volatile private var viewWidth = 1f
    @Volatile private var viewHeight = 1f

    /**
     * Editing the staging sequence rather than the structure: the left panel
     * lists stages, and a tap on the craft moves a part into the chosen one.
     */
    var stagingMode: Boolean by mutableStateOf(false)
        private set

    /** The stage parts are tapped into, and whose parts are lit up. */
    var selectedStage: Int? by mutableStateOf(null)
        private set

    /**
     * Where LAUNCH puts the craft: a [com.rm.apogee.core.world.LaunchSite]
     * id, or null to let the design decide (the sea for hulls, the pad for
     * everything else).
     */
    var launchSiteId: String? by mutableStateOf(null)

    var stats: CraftStats by mutableStateOf(CraftStats.analyze(builder.design, catalog))
        private set

    var savedCraft: List<SavedCraft> by mutableStateOf(emptyList())
        private set

    var statusMessage: String? by mutableStateOf(null)

    /** Bumped on every edit so Compose recomposes off a plain mutable model. */
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

    /** A part from the drawer into the hand - or, [partId] null, the hand emptied. */
    fun selectPart(partId: String?) {
        held = partId?.let { Held(Assembly.of(it), it, once = false) }
        selectedPartIndex = null
        revision++
    }

    /** Puts down whatever is in hand. */
    fun dropHeld() = selectPart(null)

    fun setViewSize(width: Float, height: Float) {
        if (width > 0f && height > 0f) { viewWidth = width; viewHeight = height }
    }

    /**
     * Pixels down the left covered by an open panel: the craft is drawn in
     * the middle of what is left, not behind the drawer.
     */
    @Volatile var leftInset = 0f

    /** Pixels up the right covered by the open stats card. */
    @Volatile var rightInset = 0f

    /** The covered sides' difference as drawn: eased there, not jumped. */
    private var shownInset = 0f

    // --- the selected part's actions -------------------------------------------

    /** What the action bar says about the selected part. */
    class Selection(val index: Int, val title: String, val count: Int, val root: Boolean, val stage: Int, val stageable: Boolean)

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
            )
        }

    fun clearSelection() {
        selectedPartIndex = null
        selectionAnchor = null
        revision++
    }

    /** The selected part and everything below it, copied into the hand to put on elsewhere. */
    fun duplicateSelected() {
        val index = selectedPartIndex ?: return
        val copy = builder.duplicate(index) ?: return
        held = Held(copy, null, once = true)
        selectedPartIndex = null
        statusMessage = "Copy in hand - tap a green node, or drag it on"
        revision++
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

    /** One stage as the panel lists it: its parts by name, like parts together. */
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

    /** A new stage just after the selected one - or last - chosen, ready to fill. */
    fun addStage() {
        val at = (selectedStage ?: (builder.design.stages.size - 1)) + 1
        if (builder.addStage(at)) {
            selectedStage = at
            statusMessage = "Stage $at added - tap parts to move them into it"
            onEdited()
        }
    }

    fun removeStage(index: Int) {
        if (builder.removeStage(index)) {
            selectedStage = selectedStage?.let { minOf(it, builder.design.stages.size - 1) }?.takeIf { it >= 0 }
            onEdited()
        } else {
            statusMessage = "The only stage cannot be removed while it fires something"
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
            // Nothing to move it to: show where it is instead.
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
     * Stands the craft up or lays it down. Nothing moves in design space; the
     * camera turns so the new "up" is up on screen, and the mounting rules
     * follow.
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
     * Acts on a tap in the 3D view.
     *
     * With a part held, the tap goes to the nearest open attach node; with
     * nothing held, it selects a placed part. Node picking works in *screen*
     * space rather than by casting a ray at the node's sphere, because a
     * generous radius in pixels is a constant-size target no matter how far the
     * camera has zoomed out - a world-space radius is either unusable when
     * zoomed out or covers the whole craft when zoomed in.
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
            // Off the craft altogether: put it down.
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
            statusMessage = "That part will not fit here"
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

    /** Two fingers moved the view by ([dx], [dy]) pixels: slide what is looked at along with them. */
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
     * Held still on a part: lift it - with its partners and all below - to
     * carry somewhere else. False over nothing, or the first part.
     */
    fun liftAt(x: Float, y: Float, width: Float, height: Float): Boolean {
        setViewSize(width, height)
        if (stagingMode) return false
        val index = pickPart(x, y, width, height) ?: return false
        val lifted = builder.lift(index) ?: run {
            statusMessage = "The first part holds everything else - it stays"
            return false
        }
        // As many as were lifted go back on: four fins stay four.
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

    /** The carrying finger is at ([x], [y]): snap to the best node near it. */
    fun carryTo(x: Float, y: Float) {
        val c = carry ?: return
        carryPoint = Offset(x, y)
        // A thumb's width above the fingertip, so the node is not under it.
        val target = if (c.rest.parts.isEmpty()) null
            else pickNode(x, y - viewHeight * FINGER_LIFT, viewWidth, viewHeight, c.assembly.rootPartId, c.rest)
        carryTarget = target
        carrySnapped = target != null || c.rest.parts.isEmpty()
    }

    /** Let go: on to the node it snapped to, or back where it came from. */
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
                if (builder.move(c.lifted, target, c.symmetry).isEmpty()) statusMessage = "That will not fit there" else onEdited()
            }
            else -> {
                val saved = builder.symmetry
                if (builder.attachAssembly(c.assembly, target).isEmpty()) statusMessage = "That part will not fit here" else onEdited()
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
            statusMessage = "The root part cannot be removed"
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
            .onFailure { statusMessage = "Could not save: ${it.message}" }
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
            .onFailure { statusMessage = "Could not load: ${it.message}" }
    }

    fun delete(saved: SavedCraft) {
        store.delete(saved.fileName)
        refreshSavedList()
    }

    private fun refreshSavedList() {
        savedCraft = store.list()
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

        // Frame the craft rather than the design origin, so a tall stack stays
        // centred as it grows instead of drifting off the top of the screen.
        val centre = designCentre(design)
        camera.frameAtLeast(designExtent(design, centre))
        // Half the difference between the covered strips, so the craft sits
        // in the middle of the open space between the panels.
        // Not while a finger carries something: the craft would slide out
        // from under the node it is being taken to.
        if (c == null) shownInset += (leftInset - rightInset - shownInset) * INSET_EASE
        val metresPerPixel = 2.0 * tan(FOV_Y * 0.5) * camera.distance / viewHeight
        val aside = cameraRotation.rotate(Vec3.unitX(), Vec3()).mulInPlace(-shownInset * 0.5 * metresPerPixel)
        camera.solve(Vec3().setTo(centre).addInPlace(pan).addInPlace(aside), cameraPosition, cameraRotation)

        camera.fixedUp.setTo(design.orientation.up)
        val caps = StackCaps.forDesign(design, catalog)
        // In staging, the chosen stage's parts light up in the accent; the
        // selected part lights up with its partners.
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

        // Attach-node markers, shown only while a part is in hand - they are
        // clutter the rest of the time - and only the ones it can go on, so
        // a wheel held over a horizontal craft shows its underside and nothing
        // else rather than inviting taps that will be refused.
        val rootId = c?.assembly?.rootPartId ?: held?.assembly?.rootPartId
        val nodes = rootId?.let { usableNodes(design, it) }.orEmpty()
        visibleNodes = nodes
        val target = carryTarget.takeIf { c != null }
        for (node in nodes) {
            if (node === target) continue
            items.add(RenderItem(shape = NODE_MARKER, position = node.position.copy(), rotation = Quat.identity(), color = NODE_COLOR))
        }

        // What is carried, where it would go: a see-through ghost, copies and all.
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

    /** Furthest part from [centre], so the camera can pull back to fit it. */
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
     * Projects a design-space point to screen pixels, or null if it is behind
     * the camera.
     *
     * Done directly rather than through the render matrices: the camera pose is
     * already here, and going via [com.rm.apogee.core.math.Mat4] would mean
     * narrowing to float for a calculation that only needs to be pixel-accurate.
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
     * The part under ([x], [y]): the nearest whose box the line of sight
     * through that pixel passes through - a small part in front of a big
     * one is the small one, and a big part is hit anywhere on it, not only
     * near its middle. Failing that, the part whose middle is nearest the
     * finger, within reach.
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
        /** Of the screen's height: how far above the fingertip a carried part snaps from. */
        const val FINGER_LIFT = 0.04f

        private const val PRESENT_INTERVAL_MILLIS = 16L
        const val FOV_Y = Math.PI / 180.0 * 55.0
        const val NEAR_PLANE = 0.2

        /**
         * Snap radius in pixels. Deliberately generous - assembly on a
         * touchscreen is the hardest interaction in this project, and a
         * fingertip covers far more than a node marker does.
         */
        const val TAP_RADIUS_PIXELS = 110f
        const val PART_TAP_RADIUS_PIXELS = 80f


        /** Share of the way to a new panel inset covered each frame. */
        private const val INSET_EASE = 0.2f

        /** Metres past the craft's reach the view can be slid. */
        const val PAN_MARGIN = 4.0

        /** Metres added round each part's box when picking: thin parts are still hittable. */
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
