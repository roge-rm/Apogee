package com.rm.apogee.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /** The part the player has picked from the drawer, waiting to be placed. */
    var heldPartId: String? by mutableStateOf(null)

    /** An already-placed part the player has tapped, for deletion. */
    var selectedPartIndex: Int? by mutableStateOf(null)

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

    fun selectPart(partId: String?) {
        heldPartId = partId
        selectedPartIndex = null
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
        val held = heldPartId
        if (held == null) {
            selectedPartIndex = pickPart(x, y, width, height)
            return
        }

        if (builder.isEmpty) {
            builder.placeRoot(held)
            onEdited()
            return
        }

        val node = pickNode(x, y, width, height, held) ?: run {
            statusMessage = "No attachment point there"
            return
        }
        val added = builder.attach(held, node)
        if (added.isEmpty()) {
            statusMessage = "That part will not fit here"
        } else {
            statusMessage = null
            onEdited()
        }
    }

    fun deleteSelected() {
        val index = selectedPartIndex ?: return
        if (builder.remove(index)) {
            selectedPartIndex = null
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
                builder.load(it)
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
        stats = CraftStats.analyze(builder.design, catalog)
        revision++
    }

    /** Where Automatic would send the current design. */
    fun automaticSite(): com.rm.apogee.core.world.LaunchSite =
        com.rm.apogee.core.world.World.launchSiteFor(builder.design, catalog)

    /** The design as it would be launched. */
    fun designForLaunch(): CraftDesign? =
        if (builder.isEmpty || !stats.isFlyable) null else builder.design

    // --- rendering -----------------------------------------------------------

    private fun publishFrame() {
        val design = builder.design
        val items = ArrayList<RenderItem>(design.parts.size + 16)

        // Frame the craft rather than the design origin, so a tall stack stays
        // centred as it grows instead of drifting off the top of the screen.
        val centre = designCentre(design)
        camera.frameAtLeast(designExtent(design, centre))
        camera.solve(centre, cameraPosition, cameraRotation)

        camera.fixedUp.setTo(design.orientation.up)
        val caps = StackCaps.forDesign(design, catalog)
        design.parts.forEachIndexed { index, placed ->
            val def = catalog[placed.partId] ?: return@forEachIndexed
            items.add(
                RenderItem(
                    caps = caps[index],
                    meshSpec = def.mesh,
                    position = placed.position.copy(),
                    rotation = placed.rotation.copy(),
                    color = if (index == selectedPartIndex) SELECTED_COLOR else colorFor(placed.partId),
                )
            )
        }

        // Attach-node markers, shown only while a part is held - they are
        // clutter the rest of the time - and only the ones it can go on, so
        // a wheel held over a horizontal craft shows its underside and nothing
        // else rather than inviting taps that will be refused.
        val heldDef = heldPartId?.let { catalog[it] }
        val nodes = if (heldDef == null) emptyList() else builder.openNodes().filter {
            Attachment.accepts(heldDef, it, design.orientation) &&
                Attachment.mountNodeFor(heldDef, it) != null
        }
        visibleNodes = nodes
        for (node in nodes) {
            items.add(
                RenderItem(
                    meshSpec = NODE_MARKER,
                    position = node.position.copy(),
                    rotation = Quat.identity(),
                    color = NODE_COLOR,
                )
            )
        }

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

    private fun pickNode(x: Float, y: Float, width: Float, height: Float, held: String): OpenNode? {
        val def = catalog[held] ?: return null
        var best: OpenNode? = null
        var bestDistance = TAP_RADIUS_PIXELS * TAP_RADIUS_PIXELS

        for (node in visibleNodes) {
            // Offer only nodes this part can actually use, so a near-miss never
            // lands on a node the part would be refused from.
            if (Attachment.mountNodeFor(def, node) == null) continue
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

    private fun pickPart(x: Float, y: Float, width: Float, height: Float): Int? {
        val design = builder.design
        var best: Int? = null
        var bestDistance = PART_TAP_RADIUS_PIXELS * PART_TAP_RADIUS_PIXELS

        design.parts.forEachIndexed { index, placed ->
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

    private fun colorFor(partId: String): FloatArray = when {
        partId.startsWith("engine") -> floatArrayOf(0.45f, 0.45f, 0.50f, 1f)
        partId.startsWith("tank") -> floatArrayOf(0.82f, 0.82f, 0.86f, 1f)
        partId.startsWith("pod") -> floatArrayOf(0.70f, 0.62f, 1.00f, 1f)
        partId.startsWith("decoupler") -> floatArrayOf(0.90f, 0.70f, 0.35f, 1f)
        partId.startsWith("fin") -> floatArrayOf(0.60f, 0.20f, 0.20f, 1f)
        partId.startsWith("parachute") -> floatArrayOf(0.55f, 0.55f, 0.60f, 1f)
        else -> floatArrayOf(0.75f, 0.75f, 0.78f, 1f)
    }

    private companion object {
        const val PRESENT_INTERVAL_MILLIS = 16L
        const val FOV_Y = Math.PI / 180.0 * 55.0
        const val NEAR_PLANE = 0.2

        /**
         * Snap radius in pixels. Deliberately generous - assembly on a
         * touchscreen is the hardest interaction in this project, and a
         * fingertip covers far more than a node marker does.
         */
        const val TAP_RADIUS_PIXELS = 110f
        const val PART_TAP_RADIUS_PIXELS = 80f

        val NODE_MARKER = MeshSpec.Sphere(0.22)
        val NODE_COLOR = floatArrayOf(0.48f, 1.0f, 0.70f, 1f)
        val SELECTED_COLOR = floatArrayOf(1.0f, 0.82f, 0.45f, 1f)
    }
}
