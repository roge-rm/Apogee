package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.Terrain
import com.rm.apogee.core.world.World
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Maps and ground views of every world's test site, in the game's own colours, for judging the
 * ground by eye. Off unless WORLD_ATLAS names a folder; WORLDS picks bodies (comma separated) and
 * ATLAS_AT adds one spot as body,lat,lon in degrees.
 */
class WorldAtlas {

    private val system = SolarSystem.defaultSystem()

    @Test
    fun draw() {
        val out = File(System.getenv("WORLD_ATLAS") ?: return).apply { mkdirs() }
        val only = System.getenv("WORLDS")?.split(",")?.map { it.trim() }?.toSet()
        val spots = World.launchSites.filter { it.bodyId != SolarSystem.HOMEWORLD_ID && !it.onBase && System.getenv("ATLAS_SITES") != "0" }
            .map { Triple(it.id, it.bodyId, SolarSystem.surfaceDirection(it.latitude, it.longitude)) }
            .toMutableList()
        System.getenv("ATLAS_AT")?.split(";")?.forEachIndexed { k, spot ->
            val (body, lat, lon) = spot.split(",").map { it.trim() }
            spots += Triple("$body-at$k", body, SolarSystem.surfaceDirection(Math.toRadians(lat.toDouble()), Math.toRadians(lon.toDouble())))
        }
        for ((id, body, at) in spots) {
            if (only != null && body !in only) continue
            val terrain = system.body(body).terrain ?: continue
            for (width in listOf(20_000.0, 4_000.0, 800.0)) {
                val w = minOf(width, terrain.bodyRadius * 1.5)
                map(terrain, body, at, w, 600, File(out, "$id-${(w / 1000).let { if (it >= 1) "${it.toInt()}km" else "${(w).toInt()}m" }}.png"))
            }
            val views = (0 until 4).map { k -> ground(terrain, body, at, k * 90.0, 640, 300) }
            val sheet = BufferedImage(640 * 2, 300 * 2, BufferedImage.TYPE_INT_RGB)
            for ((k, v) in views.withIndex()) sheet.graphics.drawImage(v, (k % 2) * 640, (k / 2) * 300, null)
            ImageIO.write(sheet, "png", File(out, "$id-ground.png"))
            println("ATLAS $id ground views written")
        }
    }

    /** Local east and north at [up], for laying out a view. */
    private fun frame(up: Vec3): Pair<Vec3, Vec3> {
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).let { if (it.lengthSq < 1e-9) Vec3(1.0, 0.0, 0.0) else it.normalizeInPlace() }
        return east to up.cross(east).normalizeInPlace()
    }

    private val colour = FloatArray(3)

    private fun ground(material: SurfaceMaterial, h: Double, key: Int, world: String): FloatArray {
        TerrainPalette.colour(material, h, key, colour, 0, world)
        return colour
    }

    /** From above, lit from the north-west, in the game's colours. */
    private fun map(terrain: Terrain, world: String, centre: Vec3, widthMetres: Double, pixels: Int, file: File) {
        val started = System.nanoTime()
        val up = centre.normalized()
        val (east, north) = frame(up)
        val radius = terrain.bodyRadius
        val step = widthMetres / pixels
        val side = pixels + 2
        val heights = DoubleArray(side * side)
        val dirs = Array(side * side) { Vec3() }
        for (y in 0 until side) for (x in 0 until side) {
            val e = (x - side / 2.0) * step; val n = (side / 2.0 - y) * step
            val d = dirs[y * side + x].setTo(
                up.x * radius + east.x * e + north.x * n,
                up.y * radius + east.y * e + north.y * n,
                up.z * radius + east.z * e + north.z * n,
            ).normalizeInPlace()
            heights[y * side + x] = terrain.elevation(d)
        }
        val image = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB)
        val counts = IntArray(SurfaceMaterial.entries.size)
        for (y in 0 until pixels) for (x in 0 until pixels) {
            val i = (y + 1) * side + (x + 1)
            val h = heights[i]
            val dhdx = (heights[i + 1] - heights[i - 1]) / (2 * step)
            val dhdy = (heights[i - side] - heights[i + side]) / (2 * step)
            val slope = 1.0 - 1.0 / sqrt(1.0 + dhdx * dhdx + dhdy * dhdy)
            val nl = sqrt(dhdx * dhdx + dhdy * dhdy + 1.0)
            val light = ((-dhdx * -0.5 + -dhdy * 0.5 + 0.7) / nl / 0.99).coerceIn(0.15, 1.2)
            val rgb = if (terrain.hasOcean && h < 0.0) {
                TerrainPalette.water(-h, colour, 0, world); colour
            } else {
                val m = terrain.material(dirs[i], h, slope)
                counts[m.ordinal]++
                ground(m, h, x * 7919 + y, world)
            }
            image.setRGB(x, y, pack(rgb[0] * light, rgb[1] * light, rgb[2] * light))
        }
        ImageIO.write(image, "png", file)
        val land = counts.sum().coerceAtLeast(1)
        val mix = SurfaceMaterial.entries.filter { counts[it.ordinal] > 0 }
            .joinToString("  ") { "%s %.0f%%".format(it.name.lowercase(), counts[it.ordinal] * 100.0 / land) }
        println("ATLAS %-28s %5.1f s  relief %7.0f..%7.0f m  %s".format(file.name, (System.nanoTime() - started) / 1e9, heights.min(), heights.max(), mix))
    }

    /**
     * What a driver sees from 2 m up, facing [heading] degrees east of north: each column marched
     * out along the ground, near to far, filling up the screen as the ground rises into view.
     */
    private fun ground(terrain: Terrain, world: String, at: Vec3, heading: Double, w: Int, h: Int): BufferedImage {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val up = at.normalized()
        val (east, north) = frame(up)
        val radius = terrain.bodyRadius
        val eye = radius + terrain.elevation(up) + 2.0
        val focal = (w / 2) / tan(Math.toRadians(37.5))
        val horizon = h * 0.42
        val sky = if (system.body(world).atmosphere != null) intArrayOf(150, 160, 175) else intArrayOf(8, 8, 12)
        val far = minOf(30_000.0, radius * 0.5)
        // Sun from the north-west, 35 degrees up, in local axes.
        val sun = Vec3().setTo(north).mulInPlace(0.58).addScaledInPlace(east, -0.58).addScaledInPlace(up, 0.57).normalizeInPlace()
        val d = Vec3(); val side = Vec3(); val probe = Vec3()
        for (x in 0 until w) {
            val angle = Math.toRadians(heading) + atan((x - w / 2.0) / focal)
            val ahead = Vec3().setTo(north).mulInPlace(cos(angle)).addScaledInPlace(east, sin(angle))
            side.setTo(ahead).crossInPlace(up)
            var top = h
            var t = 0.5
            while (t < far && top > 0) {
                val theta = t / radius
                d.setTo(up).mulInPlace(cos(theta)).addScaledInPlace(ahead, sin(theta))
                val g = terrain.elevation(d)
                val r = radius + g
                val vertical = r * cos(theta) - eye
                val horizontal = r * sin(theta)
                val y = (horizon - focal * vertical / horizontal).toInt()
                if (y < top) {
                    val step = max(0.5, t * 0.004)
                    // Shade by the ground's tilt here.
                    probe.setTo(d).addScaledInPlace(ahead, step / radius).normalizeInPlace()
                    val ga = terrain.elevation(probe)
                    probe.setTo(d).addScaledInPlace(side, step / radius).normalizeInPlace()
                    val gs = terrain.elevation(probe)
                    val n = Vec3().setTo(up).addScaledInPlace(ahead, -(ga - g) / step).addScaledInPlace(side, -(gs - g) / step).normalizeInPlace()
                    val slope = 1.0 - (n dot up)
                    val light = (0.25 + 0.85 * (n dot sun).coerceAtLeast(0.0))
                    val rgb = if (terrain.hasOcean && g < 0.0) { TerrainPalette.water(-g, colour, 0, world); colour }
                        else ground(terrain.material(d, g, slope), g, (t * 3).toInt() * 31 + x, world)
                    val fog = (t / far).coerceIn(0.0, 1.0).let { it * it }
                    val c = pack(
                        rgb[0] * light * (1 - fog) + sky[0] / 255.0 * fog,
                        rgb[1] * light * (1 - fog) + sky[1] / 255.0 * fog,
                        rgb[2] * light * (1 - fog) + sky[2] / 255.0 * fog,
                    )
                    for (py in max(y, 0) until top) image.setRGB(x, py, c)
                    top = max(y, 0)
                }
                t += max(0.5, t * 0.004)
            }
            for (py in 0 until top) image.setRGB(x, py, (sky[0] shl 16) or (sky[1] shl 8) or sky[2])
        }
        image.graphics.apply { color = java.awt.Color.WHITE; drawString("$world %.0f°".format(heading), 6, 14) }
        return image
    }

    private fun pack(r: Double, g: Double, b: Double): Int {
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255).toInt()
        return (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}
