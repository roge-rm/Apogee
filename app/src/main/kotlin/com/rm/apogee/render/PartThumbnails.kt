package com.rm.apogee.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.math.quatLookAt
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small picture of every part, for the drawer, and of each saved craft, for the load list. Each
 * is drawn once by the GL renderer, off screen, the way the builder draws it, and kept on disk for
 * next time. They're kept under the catalogue's content hash, so a changed part gets drawn again,
 * and a craft's picture is named for its design, so a changed craft does too.
 */
class PartThumbnails(private val cacheRoot: File) {

    /**
     * Pictures ready so far, by part id, or for a craft by [craftKey]. Compose reads them, and
     * they're written on the main thread.
     */
    val pictures = mutableStateMapOf<String, ImageBitmap>()

    /** One picture to draw: what it's called, its pieces, and where the camera stands to see them. */
    class Job(val key: String, val items: List<RenderItem>, val cameraPosition: Vec3, val cameraRotation: Quat, val fovY: Double)

    private val pending = ConcurrentLinkedQueue<Job>()
    private val main = Handler(Looper.getMainLooper())
    private val disk = Executors.newSingleThreadExecutor()
    @Volatile private var folder: File? = null
    private var requested: String? = null
    private val craftAsked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Every part of [catalog], from disk where it was drawn before, and queued for drawing where it
     * wasn't.
     */
    fun request(catalog: PartCatalog) {
        if (requested == catalog.contentHash) return
        requested = catalog.contentHash
        val dir = File(cacheRoot, "thumbs/${catalog.contentHash.take(16)}-v$VERSION").also { it.mkdirs() }
        folder = dir
        val defs = catalog.parts.values.toList()
        disk.execute {
            for (def in defs) {
                val file = File(dir, "${def.id}.png")
                val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
                if (bitmap != null) main.post { pictures[def.id] = bitmap.asImageBitmap() }
                else pending.add(jobFor(def))
            }
        }
    }

    /** The next picture to draw, for the GL thread. Null when they're all done. */
    fun next(): Job? = pending.poll()

    /** A picture was drawn, so show it and keep it. Called on the GL thread. */
    fun done(key: String, bitmap: Bitmap) {
        main.post { pictures[key] = bitmap.asImageBitmap() }
        val dir = folder ?: return
        disk.execute {
            runCatching { File(dir, "$key.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    /** The name [design]'s picture goes by, the same until the design changes. */
    fun craftKey(design: CraftDesign): String = "craft-" + Integer.toHexString(design.hashCode())

    /**
     * A picture of the whole of [design], from disk if it was drawn before, and queued to be drawn
     * if it wasn't. It's shown standing the way it's built to: a rocket upright, and a plane, rover
     * or boat level with its nose to the right.
     */
    fun requestCraft(design: CraftDesign, catalog: PartCatalog) {
        val key = craftKey(design)
        if (design.parts.isEmpty() || !craftAsked.add(key)) return
        val dir = folder ?: File(cacheRoot, "thumbs/${catalog.contentHash.take(16)}-v$VERSION").also { it.mkdirs(); folder = it }
        disk.execute {
            val file = File(dir, "$key.png")
            val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            if (bitmap != null) main.post { pictures[key] = bitmap.asImageBitmap() }
            else runCatching { craftJob(key, design, catalog) }.getOrNull()?.let { pending.add(it) }
        }
    }

    private fun craftJob(key: String, design: CraftDesign, catalog: PartCatalog): Job {
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
        design.parts.forEachIndexed { index, placed ->
            val def = catalog[placed.partId] ?: return@forEachIndexed
            val anim = PartAnim()
            PartModels.alignWheel(def, placed.rotation, orientation.forward, orientation.up, anim)
            PartModels.alignSurface(def, placed.rotation, Vec3().setTo(placed.position).subInPlace(centre), anim)
            leaves.clear()
            PartModels.expand(def, caps[index], anim, leaves)
            val body = PartModels.bodyColour(placed.partId)
            for (leaf in leaves) {
                val at = turn.rotate(placed.rotation.rotate(leaf.position).addInPlace(placed.position).subInPlace(centre))
                items.add(RenderItem(caps = leaf.caps, shape = leaf.shape, position = at, rotation = turn * placed.rotation * leaf.rotation, color = PartModels.colour(leaf.tint, body)))
                val r = PartModels.boundingRadius(leaf.shape)
                low.setTo(minOf(low.x, at.x - r), minOf(low.y, at.y - r), minOf(low.z, at.z - r))
                high.setTo(maxOf(high.x, at.x + r), maxOf(high.y, at.y + r), maxOf(high.z, at.z + r))
            }
        }
        val middle = if (items.isEmpty()) Vec3() else Vec3().setTo(low).addInPlace(high).mulInPlace(0.5)
        val reach = (if (items.isEmpty()) 1.0 else Vec3().setTo(high).subInPlace(low).length * 0.5).coerceAtLeast(0.3)
        val distance = reach / sin(FOV_Y * 0.5) * 1.02
        val yaw = Math.toRadians(35.0)
        val pitch = Math.toRadians(22.0)
        val from = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch)).mulInPlace(distance).addInPlace(middle)
        val look = quatLookAt(Vec3().setTo(middle).subInPlace(from), Vec3.unitY())
        return Job(key, items, from, look, FOV_Y)
    }

    private fun jobFor(def: PartDef): Job {
        val orientation = CraftOrientation.VERTICAL
        val anim = PartAnim()
        val rotation = Quat.identity()
        PartModels.alignWheel(def, rotation, orientation.forward, orientation.up, anim)
        // As if on the right side of something, so fins, legs and wheels stand out the way they
        // would.
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
        // A three-quarter view from a little above, far enough back for the whole part to fit.
        // That's all of what's drawn, which for a fairing is metres of shell over a ring a hand's
        // width deep.
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

        /** Goes up when the way pictures are drawn changes, so old ones get drawn again. */
        const val VERSION = 2

        private const val FOV_Y = Math.PI / 180.0 * 30.0
    }
}
