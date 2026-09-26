package com.rm.apogee.core.scenario

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.Terrain
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws the Cape from above: `./gradlew :core:capeMap`.
 *
 * The ground in metres east and north of the pad - water by depth, land by
 * what it is, the works paved - with the launch sites marked, and [marks]
 * drawn over it: what is planned to stand where. For laying out the
 * spaceport, the airfield and the harbour without a device in the loop.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/cape-map").apply { mkdirs() }
    val terra = SolarSystem.defaultSystem().body("terra").terrain!!
    val pad = SolarSystem.surfaceDirection(SolarSystem.PAD_LATITUDE, SolarSystem.PAD_LONGITUDE)
    // What is planned to stand where: the Cape's buildings, from their own designs.
    val catalog = com.rm.apogee.core.part.StockParts.catalog
    marks = com.rm.apogee.core.craft.StockStructures.complexes.flatMap { complex ->
        complex.placements.map { p ->
            val m = catalog.require(p.partId).mesh
            val (w, d) = when (m) {
                is com.rm.apogee.core.part.MeshSpec.Box -> m.width to m.depth
                is com.rm.apogee.core.part.MeshSpec.Cylinder -> 2 * m.radius to 2 * m.radius
                is com.rm.apogee.core.part.MeshSpec.Cone -> 2 * m.bottomRadius to 2 * m.bottomRadius
                is com.rm.apogee.core.part.MeshSpec.Sphere -> 2 * m.radius to 2 * m.radius
            }
            val named = w * d > 30.0 && listOf("jetty", "paint", "lamp").none { p.partId.contains(it) }
            Mark(if (named) catalog.require(p.partId).title else "", p.east, p.north, w, d, p.facing)
        }
    } + listOf(
        Mark("pads", 0.0, 0.0, 12.0, 12.0), Mark("", 40.0, 0.0, 12.0, 12.0), Mark("", -40.0, 0.0, 12.0, 12.0),
        Mark("planes launch", 340.0, -400.0, 16.0, 10.0), Mark("boats launch", 2_700.0, 330.0, 8.0, 4.0),
    )
    capeMap(terra, pad, -600.0, 3_400.0, -900.0, 900.0, 2_000, File(out, "cape.png"))
    capeMap(terra, pad, -400.0, 400.0, -500.0, 300.0, 1_000, File(out, "cape-pad.png"))
    capeMap(terra, pad, 2_380.0, 2_880.0, 120.0, 520.0, 1_000, File(out, "cape-harbour.png"))
    capeMap(terra, pad, 150.0, 900.0, -500.0, -150.0, 1_000, File(out, "cape-airfield.png"))
    println("maps written to ${out.absolutePath}")
}

/** Something to draw over the map: a box [width] by [depth] m round ([east], [north]), turned [bearing] degrees from east. */
class Mark(val label: String, val east: Double, val north: Double, val width: Double, val depth: Double, val bearing: Double = 0.0)

var marks: List<Mark> = emptyList()

fun capeMap(terrain: Terrain, pad: Vec3, west: Double, eastEdge: Double, south: Double, northEdge: Double, pixels: Int, file: File) {
    val east = Vec3(0.0, 1.0, 0.0).crossInPlace(pad).normalizeInPlace()
    val north = pad.cross(east).normalizeInPlace()
    val scale = pixels / (eastEdge - west)
    val w = pixels
    val h = ((northEdge - south) * scale).toInt()
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val d = Vec3()
    val r = terrain.bodyRadius
    fun at(e: Double, n: Double): Vec3 = d.setTo(pad).mulInPlace(r).addScaledInPlace(east, e).addScaledInPlace(north, n).normalizeInPlace()
    for (y in 0 until h) for (x in 0 until w) {
        val e = west + (x + 0.5) / scale
        val n = northEdge - (y + 0.5) / scale
        val here = terrain.elevation(at(e, n))
        val dx = terrain.elevation(at(e + 2.0, n)) - here
        val dy = terrain.elevation(at(e, n + 2.0)) - here
        val shade = (1.0 - 0.9 * (dx - dy)).coerceIn(0.55, 1.35)
        val c = if (here < 0.0) {
            val t = (-here / 20.0).coerceIn(0.0, 1.0)
            Color((70 - 55 * t).toInt(), (180 - 110 * t).toInt(), (215 - 70 * t).toInt())
        } else {
            val slope = kotlin.math.sqrt(dx * dx + dy * dy) / 2.0
            val base = when (terrain.material(at(e, n), here, slope)) {
                SurfaceMaterial.CONCRETE -> Color(200, 200, 195)
                SurfaceMaterial.ASPHALT -> Color(70, 72, 76)
                SurfaceMaterial.SAND -> Color(220, 205, 150)
                SurfaceMaterial.GRASS -> Color(100, 160, 70)
                SurfaceMaterial.FOREST -> Color(50, 110, 50)
                SurfaceMaterial.DIRT, SurfaceMaterial.CLAY -> Color(150, 120, 80)
                SurfaceMaterial.MUD -> Color(110, 90, 60)
                SurfaceMaterial.ROCK, SurfaceMaterial.SCREE, SurfaceMaterial.BASALT -> Color(140, 135, 130)
                else -> Color(170, 170, 170)
            }
            Color((base.red * shade).toInt().coerceIn(0, 255), (base.green * shade).toInt().coerceIn(0, 255), (base.blue * shade).toInt().coerceIn(0, 255))
        }
        image.setRGB(x, y, c.rgb)
    }
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.font = Font(Font.SANS_SERIF, Font.BOLD, (pixels / 90).coerceIn(11, 18))
    // A grid every hundred metres, heavier every kilometre.
    for (k in (west / 100).toInt()..(eastEdge / 100).toInt()) {
        val x = ((k * 100.0 - west) * scale).toInt()
        g.color = Color(0, 0, 0, if (k % 10 == 0) 90 else 30)
        g.drawLine(x, 0, x, h)
    }
    for (k in (south / 100).toInt()..(northEdge / 100).toInt()) {
        val y = ((northEdge - k * 100.0) * scale).toInt()
        g.color = Color(0, 0, 0, if (k % 10 == 0) 90 else 30)
        g.drawLine(0, y, w, y)
    }
    g.stroke = BasicStroke(2f)
    for (m in marks) {
        val cx = (m.east - west) * scale
        val cy = (northEdge - m.north) * scale
        val t = g.transform
        g.translate(cx, cy)
        g.rotate(-Math.toRadians(m.bearing))
        g.color = Color(230, 60, 40)
        g.drawRect((-m.width / 2 * scale).toInt(), (-m.depth / 2 * scale).toInt(), (m.width * scale).toInt().coerceAtLeast(2), (m.depth * scale).toInt().coerceAtLeast(2))
        g.transform = t
        g.color = Color.BLACK
        g.drawString(m.label, (cx + m.width / 2 * scale + 3).toInt(), (cy + 4).toInt())
    }
    g.dispose()
    ImageIO.write(image, "png", file)
}
