package com.rm.apogee.render

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.math.quatLookAt
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.game.Worker
import com.rm.apogee.platform.imageBitmapOf
import com.rm.apogee.platform.runOnMain
import kotlin.math.cos
import kotlin.math.sin
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile
import com.rm.apogee.platform.synchronized

/**
 * Small pictures of every part, for the drawer, and of saved craft, for the load list. Each is
 * drawn once off screen by the GL renderer and kept on disk. Part pictures are keyed by the
 * catalogue's content hash and craft pictures by design, so changes get drawn again.
 */
class PartThumbnails(private val store: PictureStore) {

    /** Pictures ready so far, by part id or [craftKey]. Written on the main thread. */
    val pictures = mutableStateMapOf<String, ImageBitmap>()

    /** One picture to draw: what it's called, its pieces, and where the camera stands to see them. */
    class Job(val key: String, val items: List<RenderItem>, val cameraPosition: Vec3, val cameraRotation: Quat, val fovY: Double, val size: Int = SIZE)

    private val pending = com.rm.apogee.core.ConcurrentQueue<Job>()
    private val disk = Worker("thumbnails")
    @Volatile private var folder: String? = null
    private var requested: String? = null
    private val craftAsked = com.rm.apogee.core.concurrentSetOf<String>()

    /** Every part of [catalog], from disk if drawn before, otherwise queued for drawing. */
    fun request(catalog: PartCatalog) {
        if (requested == catalog.contentHash) return
        requested = catalog.contentHash
        val dir = folderFor(catalog)
        folder = dir
        val defs = catalog.parts.values.toList()
        disk.execute {
            for (def in defs) {
                val picture = store.load(dir, def.id)
                if (picture != null) runOnMain { pictures[def.id] = picture }
                else pending.add(jobFor(def))
            }
        }
    }

    /** The next picture to draw, for the GL thread. Null when they're all done. */
    fun next(): Job? = large.poll() ?: pending.poll()

    /** A picture was drawn, so show it and keep it. Called on the GL thread. */
    fun done(key: String, argb: IntArray, size: Int = SIZE) {
        runOnMain { pictures[key] = imageBitmapOf(argb, size, size) }
        val dir = folder ?: return
        disk.execute { runCatching { store.save(dir, key, argb, size) } }
    }

    /** Where [catalog]'s pictures are kept: by its content, so a changed part gets drawn again. */
    private fun folderFor(catalog: PartCatalog) = "thumbs/${catalog.contentHash.take(16)}-v$VERSION"

    /** The name [design]'s picture goes by, the same until the design changes. */
    fun craftKey(design: CraftDesign): String = "craft-" + (design.hashCode()).toUInt().toString(16)

    /**
     * A picture of [design], from disk or queued. A rocket stands upright; a plane, rover or boat
     * sits level, nose to the right.
     */
    fun requestCraft(design: CraftDesign, catalog: PartCatalog) {
        val key = craftKey(design)
        if (design.parts.isEmpty() || !craftAsked.add(key)) return
        val dir = folder ?: folderFor(catalog).also { folder = it }
        disk.execute {
            val picture = store.load(dir, key)
            if (picture != null) runOnMain { pictures[key] = picture }
            else runCatching { craftJob(key, design, catalog) }.getOrNull()?.let { pending.add(it) }
        }
    }

    /** The name [design]'s large picture goes by. See [requestLargeCraft]. */
    fun largeCraftKey(design: CraftDesign): String = craftKey(design) + com.rm.apogee.ui.screens.LARGE_SUFFIX

    /** The large pictures in [pictures] now, oldest first. Main thread. */
    private val largeShown = ArrayDeque<String>()

    /**
     * A [LARGE] picture of [design], for the craft picked in a list, from disk or queued ahead of
     * the small ones. Only the last few are kept in memory. Main thread.
     */
    fun requestLargeCraft(design: CraftDesign, catalog: PartCatalog) {
        val key = largeCraftKey(design)
        if (design.parts.isEmpty()) return
        largeShown.remove(key)
        largeShown.addLast(key)
        while (largeShown.size > LARGE_KEPT) largeShown.removeFirst().let { pictures.remove(it); craftAsked.remove(it) }
        if (key in pictures || !craftAsked.add(key)) return
        val dir = folder ?: folderFor(catalog).also { folder = it }
        disk.execute {
            val picture = store.load(dir, key)
            if (picture != null) runOnMain { pictures[key] = picture; craftAsked.remove(key) }
            else runCatching { craftJob(key, design, catalog, LARGE) }.getOrNull()?.let { large.add(it) }
        }
    }

    /** Large pictures waiting, drawn before the small ones. */
    private val large = com.rm.apogee.core.ConcurrentQueue<Job>()

    private fun craftJob(key: String, design: CraftDesign, catalog: PartCatalog, size: Int = SIZE): Job {
        // Turned so its up is up, and then its nose to the right.
        val orientation = design.orientation
        val turn = quatFromTo(orientation.up, Vec3.unitY())
        val nose = turn.rotate(orientation.forward).also { it.y = 0.0 }
        if (nose.length > 0.5) turn.setTo(quatFromTo(nose.normalizeInPlace(), Vec3.unitX()) * turn)
        val centre = Vec3()
        design.parts.forEach { centre.addInPlace(it.position) }
        centre.mulInPlace(1.0 / design.parts.size)
        val caps = StackCaps.forDesign(design, catalog)
        val items = ArrayList<RenderItem>()
        val low = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        val high = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        val leaves = ArrayList<PartModels.Leaf>()
        val corners = ArrayList<Vec3>()
        design.parts.forEachIndexed { index, placed ->
            val def = catalog[placed.partId] ?: return@forEachIndexed
            val anim = PartAnim()
            PartModels.alignWheel(def, placed.rotation, orientation.forward, orientation.up, anim)
            PartModels.alignSurface(def, placed.rotation, Vec3().setTo(placed.position).subInPlace(centre), anim)
            leaves.clear()
            PartModels.expand(def, caps[index], anim, leaves)
            ShroudLook.forDesign(design, catalog).getOrNull(index)?.let { shroud -> ShroudLook.leaf(def, placed, shroud)?.let(leaves::add) }
            val body = PartModels.bodyColour(placed.partId)
            for (leaf in leaves) {
                val at = turn.rotate(placed.rotation.rotate(leaf.position).addInPlace(placed.position).subInPlace(centre))
                val rotation = turn * placed.rotation * leaf.rotation
                items.add(RenderItem(caps = leaf.caps, shape = leaf.shape, position = at, rotation = rotation, color = PartModels.colour(leaf.tint, body)))
                // Its own box, turned as it's placed, so a wing counts as flat and a mast as thin.
                val (lo, hi) = extents(leaf.shape)
                for (i in 0 until 8) {
                    val c = rotation.rotate(Vec3(if (i and 1 == 0) lo.x else hi.x, if (i and 2 == 0) lo.y else hi.y, if (i and 4 == 0) lo.z else hi.z)).addInPlace(at)
                    corners.add(c)
                    low.setTo(minOf(low.x, c.x), minOf(low.y, c.y), minOf(low.z, c.z))
                    high.setTo(maxOf(high.x, c.x), maxOf(high.y, c.y), maxOf(high.z, c.z))
                }
            }
        }
        val middle = if (items.isEmpty()) Vec3() else Vec3().setTo(low).addInPlace(high).mulInPlace(0.5)
        val yaw = Math.toRadians(35.0)
        val pitch = Math.toRadians(22.0)
        val back = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))
        for (c in corners) c.subInPlace(middle)
        val distance = if (items.isEmpty()) 2.0 else fitted(back, corners)
        val from = Vec3().setTo(back).mulInPlace(distance).addInPlace(middle)
        val look = quatLookAt(Vec3().setTo(middle).subInPlace(from), Vec3.unitY())
        return Job(key, items, from, look, FOV_Y, size)
    }

    /** The box round [shape] in its own space, low corner then high, worked out once a shape. */
    private fun extents(shape: com.rm.apogee.core.part.Shape): Pair<Vec3, Vec3> = synchronized(boxes) {
        boxes.getOrPut(shape) {
            when (shape) {
                is com.rm.apogee.core.part.MeshSpec.Box -> Vec3(shape.width * 0.5, shape.height * 0.5, shape.depth * 0.5).let { it.copy().mulInPlace(-1.0) to it }
                is com.rm.apogee.core.part.MeshSpec.Cylinder -> Vec3(shape.radius, shape.height * 0.5, shape.radius).let { it.copy().mulInPlace(-1.0) to it }
                is com.rm.apogee.core.part.MeshSpec.Cone -> Vec3(maxOf(shape.bottomRadius, shape.topRadius), shape.height * 0.5, maxOf(shape.bottomRadius, shape.topRadius)).let { it.copy().mulInPlace(-1.0) to it }
                is com.rm.apogee.core.part.MeshSpec.Sphere -> Vec3(shape.radius, shape.radius, shape.radius).let { it.copy().mulInPlace(-1.0) to it }
                is com.rm.apogee.core.part.ModelSpec -> runCatching {
                    val v = ModelShapes.build(shape).vertices
                    val lo = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
                    val hi = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
                    var i = 0
                    while (i + 2 < v.size) {
                        lo.setTo(minOf(lo.x, v[i].toDouble()), minOf(lo.y, v[i + 1].toDouble()), minOf(lo.z, v[i + 2].toDouble()))
                        hi.setTo(maxOf(hi.x, v[i].toDouble()), maxOf(hi.y, v[i + 1].toDouble()), maxOf(hi.z, v[i + 2].toDouble()))
                        i += Mesh.STRIDE_FLOATS
                    }
                    if (lo.x > hi.x) null else lo to hi
                }.getOrNull() ?: sphereBox(shape)
                else -> sphereBox(shape)
            }
        }
    }

    private fun sphereBox(shape: com.rm.apogee.core.part.Shape): Pair<Vec3, Vec3> =
        PartModels.boundingRadius(shape).let { r -> Vec3(-r, -r, -r) to Vec3(r, r, r) }

    private val boxes = HashMap<com.rm.apogee.core.part.Shape, Pair<Vec3, Vec3>>()

    /**
     * How far back along [back] the camera stands so all of [points] (round the point it looks at)
     * just fit the picture: a long plane fills it as well as a tall rocket does.
     */
    private fun fitted(back: Vec3, points: List<Vec3>): Double {
        val forward = Vec3().setTo(back).mulInPlace(-1.0)
        val right = forward.cross(Vec3.unitY()).normalizeInPlace()
        val up = right.cross(forward)
        val fit = kotlin.math.tan(FOV_Y * 0.5) * FILL
        var distance = 0.3
        for (p in points) {
            val across = maxOf(kotlin.math.abs(p dot right), kotlin.math.abs(p dot up))
            distance = maxOf(distance, across / fit - (p dot forward))
        }
        return distance
    }

    private fun jobFor(def: PartDef): Job {
        val orientation = CraftOrientation.VERTICAL
        val anim = PartAnim()
        val rotation = Quat.identity()
        PartModels.alignWheel(def, rotation, orientation.forward, orientation.up, anim)
        // As if on the right side of something, so fins, legs and wheels stand out.
        PartModels.alignSurface(def, rotation, Vec3(1.0, 0.0, 0.0), anim)
        val leaves = ArrayList<PartModels.Leaf>()
        PartModels.expand(def, StackCaps.BOTH, anim, leaves)
        val body = PartModels.bodyColour(def.id)
        val items = leaves.map { leaf ->
            RenderItem(
                caps = leaf.caps,
                shape = leaf.shape,
                position = leaf.position.copy(),
                rotation = leaf.rotation.copy(),
                color = PartModels.colour(leaf.tint, body),
            )
        }
        // A three-quarter view from a little above, far enough back for everything drawn to fit.
        val low = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        val high = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (leaf in leaves) {
            val r = PartModels.boundingRadius(leaf.shape)
            low.setTo(minOf(low.x, leaf.position.x - r), minOf(low.y, leaf.position.y - r), minOf(low.z, leaf.position.z - r))
            high.setTo(maxOf(high.x, leaf.position.x + r), maxOf(high.y, leaf.position.y + r), maxOf(high.z, leaf.position.z + r))
        }
        val centre = if (leaves.isEmpty()) Vec3() else Vec3().setTo(low).addInPlace(high).mulInPlace(0.5)
        // The sphere around the drawn pieces, but never looser than the part's own box.
        val reach = (if (leaves.isEmpty()) def.boundsHalfExtents.length
            else minOf(Vec3().setTo(high).subInPlace(low).length * 0.5, maxOf(def.boundsHalfExtents.length, Vec3().setTo(high).subInPlace(low).length * 0.35)))
            .coerceAtLeast(0.2)
        val distance = reach / sin(FOV_Y * 0.5) * 1.02
        val yaw = Math.toRadians(35.0)
        val pitch = Math.toRadians(22.0)
        val from = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch)).mulInPlace(distance).addInPlace(centre)
        val look = quatLookAt(Vec3().setTo(centre).subInPlace(from), Vec3.unitY())
        return Job(def.id, items, from, look, FOV_Y)
    }

    companion object {
        /** Pixels square. */
        const val SIZE = 160

        /** Pixels square for the picked craft's picture. */
        const val LARGE = 512

        /** How many large pictures stay in memory. */
        private const val LARGE_KEPT = 4

        /** Goes up when the way pictures are drawn changes, so old ones get drawn again. */
        const val VERSION = 5

        private const val FOV_Y = Math.PI / 180.0 * 30.0

        /** How much of the picture a craft fills, across its widest way. */
        private const val FILL = 0.94
    }
}

/** Where pictures are kept between runs (PNG files on Android). Called off the main thread. */
interface PictureStore {
    /** The picture saved as [key] in [folder], or null if there isn't one. */
    fun load(folder: String, key: String): ImageBitmap?

    /** Keeps [argb], [size] square, as [key] in [folder]. */
    fun save(folder: String, key: String, argb: IntArray, size: Int)
}
