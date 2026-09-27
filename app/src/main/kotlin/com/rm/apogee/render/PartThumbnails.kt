package com.rm.apogee.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatLookAt
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small picture of every part, for the drawer. Each is drawn once by the GL renderer, off screen,
 * the way the builder draws it, and kept on disk for next time. They're keyed by the catalogue's
 * content hash, so a changed part gets drawn again.
 */
class PartThumbnails(private val cacheRoot: File) {

    /**
     * Pictures ready so far, by part id. Compose reads them, and they're written on the main
     * thread.
     */
    val pictures = mutableStateMapOf<String, ImageBitmap>()

    /** One part to draw: its pieces, and where the camera stands to see them. */
    class Job(val partId: String, val items: List<RenderItem>, val cameraPosition: Vec3, val cameraRotation: Quat, val fovY: Double)

    private val pending = ConcurrentLinkedQueue<Job>()
    private val main = Handler(Looper.getMainLooper())
    private val disk = Executors.newSingleThreadExecutor()
    @Volatile private var folder: File? = null
    private var requested: String? = null

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

    /** The next part to draw, for the GL thread. Null when they're all done. */
    fun next(): Job? = pending.poll()

    /** A part was drawn, so show it and keep it. Called on the GL thread. */
    fun done(partId: String, bitmap: Bitmap) {
        main.post { pictures[partId] = bitmap.asImageBitmap() }
        val dir = folder ?: return
        disk.execute {
            runCatching { File(dir, "$partId.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
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
