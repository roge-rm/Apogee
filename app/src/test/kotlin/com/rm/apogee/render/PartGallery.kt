package com.rm.apogee.render

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.Shape
import com.rm.apogee.core.part.StockParts
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * Pictures of every part and stock craft, rendered off the device.
 *
 * `GALLERY=1 ./gradlew :app:testDebugUnitTest --tests '*PartGallery*'` writes PNGs to
 * app/build/part-gallery, each from three angles, flat-shaded the way the game draws them, through
 * the same [PartModels] and [ModelShapes] code. It's for judging looks without a phone, a pinch and
 * a screenshot every time.
 */
class PartGallery {

    private val catalog = StockParts.catalog
    private val out = File("build/part-gallery").apply { mkdirs() }

    @Test
    fun gallery() {
        assumeTrue(System.getenv("GALLERY") == "1")
        for (def in catalog.parts.values.sortedBy { it.id }) {
            val tris = ArrayList<Tri>()
            addPart(def, StackCaps.BOTH, Vec3.zero(), Quat.identity(), PartAnim(), tris)
            render(tris, File(out, "part-${def.id}.png"))
        }
        val craft = listOf(
            StockCraft.starterRocket(catalog), StockCraft.lander(catalog), StockCraft.rover(catalog),
            StockCraft.aeroplane(catalog), StockCraft.boat(catalog), StockCraft.sparrow(catalog),
            StockCraft.buggy(catalog), StockCraft.hauler(catalog), StockCraft.skiff(catalog),
            StockCraft.cutter(catalog),
        )
        for (design in craft) {
            val tris = ArrayList<Tri>()
            addDesign(design, tris)
            render(tris, File(out, "craft-${design.name.lowercase().replace(' ', '-')}.png"))
        }
        println("gallery written to ${out.absolutePath}")
    }

    // --- scene --------------------------------------------------------------

    private class Tri(val a: Vec3, val b: Vec3, val c: Vec3, val n: Vec3, val colour: FloatArray)

    private fun addDesign(design: CraftDesign, tris: MutableList<Tri>) {
        val caps = StackCaps.forDesign(design, catalog)
        design.parts.forEachIndexed { index, placed ->
            val def = catalog[placed.partId] ?: return@forEachIndexed
            val anim = PartAnim()
            PartModels.alignWheel(def, placed.rotation, design.orientation.forward, design.orientation.up, anim)
            addPart(def, caps[index], placed.position, placed.rotation, anim, tris)
        }
    }

    private fun addPart(def: PartDef, caps: Int, position: Vec3, rotation: Quat, anim: PartAnim, tris: MutableList<Tri>) {
        val leaves = ArrayList<PartModels.Leaf>()
        PartModels.expand(def, caps, anim, leaves)
        val body = PartModels.bodyColour(def.id)
        for (leaf in leaves) {
            val data = meshData(leaf.shape, leaf.caps)
            val rot = rotation * leaf.rotation
            val pos = rotation.rotate(leaf.position).addInPlace(position)
            val colour = PartModels.colour(leaf.tint, body)
            val v = data.vertices
            val s = Mesh.STRIDE_FLOATS
            for (t in 0 until data.indices.size / 3) {
                val p = Array(3) { k ->
                    val i = data.indices[t * 3 + k] * s
                    rot.rotate(Vec3(v[i].toDouble(), v[i + 1].toDouble(), v[i + 2].toDouble())).addInPlace(pos)
                }
                val i0 = data.indices[t * 3] * s
                val n = rot.rotate(Vec3(v[i0 + 3].toDouble(), v[i0 + 4].toDouble(), v[i0 + 5].toDouble()))
                tris.add(Tri(p[0], p[1], p[2], n, colour))
            }
        }
    }

    private fun meshData(shape: Shape, caps: Int): MeshData = when (shape) {
        is MeshSpec.Cylinder -> MeshShapes.cylinder(shape.radius.toFloat(), shape.height.toFloat(), caps = caps)
        is MeshSpec.Cone -> MeshShapes.frustum(shape.bottomRadius.toFloat(), shape.topRadius.toFloat(), shape.height.toFloat(), caps = caps)
        is MeshSpec.Box -> MeshShapes.box((shape.width * 0.5).toFloat(), (shape.height * 0.5).toFloat(), (shape.depth * 0.5).toFloat())
        is MeshSpec.Sphere -> MeshShapes.sphere(shape.radius.toFloat())
        is ModelSpec -> ModelShapes.build(shape, caps)
        else -> error("unknown shape $shape")
    }


    // --- rasteriser ---------------------------------------------------------

    /** Three views side by side: three-quarter, side, and from below. */
    private fun render(tris: List<Tri>, file: File) {
        val views = listOf(
            Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(35.0)) *
                Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(-20.0)),
            Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(90.0)),
            Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(70.0)) *
                Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(25.0)),
        )
        val size = 420
        val image = BufferedImage(size * views.size, size, BufferedImage.TYPE_INT_RGB)
        // Frame everything the same way in every view, by the bounding sphere.
        val centre = Vec3.zero()
        for (t in tris) { centre.addInPlace(t.a) }
        centre.mulInPlace(1.0 / max(1, tris.size))
        var radius = 0.1
        for (t in tris) for (p in listOf(t.a, t.b, t.c)) radius = max(radius, p.distanceTo(centre))
        val light = Vec3(0.4, 0.8, 0.45).normalizeInPlace()
        for ((vi, view) in views.withIndex()) {
            val depth = DoubleArray(size * size) { Double.NEGATIVE_INFINITY }
            for (i in 0 until size * size) image.setRGB(vi * size + i % size, i / size, 0x14161c)
            val scale = size * 0.45 / radius
            fun project(p: Vec3): Vec3 {
                val q = view.inverseRotate(Vec3().setTo(p).subInPlace(centre))
                return Vec3(size / 2 + q.x * scale, size / 2 - q.y * scale, q.z)
            }
            for (t in tris) {
                val n = view.inverseRotate(t.n)
                if (n.z <= 0.0) continue // facing away, so culled, like on the GPU
                val a = project(t.a); val b = project(t.b); val c = project(t.c)
                val shade = (0.25 + 0.75 * max(0.0, t.n dot light)).toFloat()
                val rgb = (((t.colour[0] * shade).coerceIn(0f, 1f) * 255).toInt() shl 16) or
                    (((t.colour[1] * shade).coerceIn(0f, 1f) * 255).toInt() shl 8) or
                    ((t.colour[2] * shade).coerceIn(0f, 1f) * 255).toInt()
                val minX = max(0, min(a.x, min(b.x, c.x)).toInt())
                val maxX = min(size - 1, max(a.x, max(b.x, c.x)).toInt() + 1)
                val minY = max(0, min(a.y, min(b.y, c.y)).toInt())
                val maxY = min(size - 1, max(a.y, max(b.y, c.y)).toInt() + 1)
                val area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
                if (kotlin.math.abs(area) < 1e-9) continue
                for (y in minY..maxY) for (x in minX..maxX) {
                    val px = x + 0.5; val py = y + 0.5
                    val w0 = ((b.x - px) * (c.y - py) - (b.y - py) * (c.x - px)) / area
                    val w1 = ((c.x - px) * (a.y - py) - (c.y - py) * (a.x - px)) / area
                    val w2 = 1 - w0 - w1
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue
                    val z = w0 * a.z + w1 * b.z + w2 * c.z
                    val k = y * size + x
                    if (z > depth[k]) {
                        depth[k] = z
                        image.setRGB(vi * size + x, y, rgb)
                    }
                }
            }
        }
        ImageIO.write(image, "png", file)
    }
}
