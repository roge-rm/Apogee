package com.rm.apogee.core.scenario

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.Terrain
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Shaded terrain maps for judging the generator by eye: `./gradlew :core:terrainAtlas`. Ground by
 * material, lit from the north-west, water by depth. The palette only roughly matches the game's.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/terrain-atlas").apply { mkdirs() }
    val system = SolarSystem.defaultSystem()
    val terra = system.body("terra")!!.terrain!!
    val cape = Vec3(1.0, 0.0, 0.0)
    val harbour = direction(0.102236, 0.102236)

    // Regional: what a pilot sees from altitude, and where the variety is.
    map(terra, cape, 600_000.0, 900, File(out, "terra-cape-600km.png"))
    map(terra, cape, 150_000.0, 900, File(out, "terra-cape-150km.png"))
    // Local: what a driver sees.
    map(terra, cape, 20_000.0, 800, File(out, "terra-cape-20km.png"))
    map(terra, cape, 4_000.0, 800, File(out, "terra-cape-4km.png"))
    map(terra, harbour, 60_000.0, 800, File(out, "terra-harbour-60km.png"))
    // Somewhere else entirely, for variety across the planet.
    map(terra, direction(0.9, 1.4), 150_000.0, 800, File(out, "terra-north-150km.png"))
    map(terra, direction(-0.3, -1.9), 150_000.0, 800, File(out, "terra-south-150km.png"))
    // Wherever the last look at the regional map raised a question.
    System.getenv("ATLAS_AT")?.split(",")?.map { it.trim().toDouble() }?.let { (north, east, width) ->
        val r = terra.bodyRadius
        map(terra, Vec3(1.0, north / r, east / r).normalizeInPlace(), width, 800, File(out, "terra-at.png"))
    }
    system.body("luna")?.terrain?.let { luna ->
        map(luna, Vec3(1.0, 0.0, 0.0), 80_000.0, 800, File(out, "luna-80km.png"))
        map(luna, Vec3(1.0, 0.0, 0.0), 10_000.0, 800, File(out, "luna-10km.png"))
    }
    println("atlas written to ${out.absolutePath}")
}

private fun direction(latitude: Double, longitude: Double) = Vec3(
    kotlin.math.cos(latitude) * kotlin.math.cos(longitude),
    kotlin.math.sin(latitude),
    kotlin.math.cos(latitude) * kotlin.math.sin(longitude),
)

private fun map(terrain: Terrain, centre: Vec3, widthMetres: Double, pixels: Int, file: File) {
    val started = System.nanoTime()
    val up = centre.normalized()
    val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).let {
        if (it.lengthSq < 1e-9) Vec3(1.0, 0.0, 0.0) else it.normalizeInPlace()
    }
    val north = up.cross(east).normalizeInPlace()
    val radius = terrain.bodyRadius
    val step = widthMetres / pixels

    // One sample border for slopes.
    val side = pixels + 2
    val heights = DoubleArray(side * side)
    val dirs = Array(side * side) { Vec3() }
    for (y in 0 until side) for (x in 0 until side) {
        val e = (x - side / 2.0) * step
        val n = (side / 2.0 - y) * step
        val d = dirs[y * side + x].setTo(
            up.x * radius + east.x * e + north.x * n,
            up.y * radius + east.y * e + north.y * n,
            up.z * radius + east.z * e + north.z * n,
        ).normalizeInPlace()
        heights[y * side + x] = terrain.elevation(d)
    }

    val image = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB)
    val counts = IntArray(SurfaceMaterial.entries.size)
    val floor = if (terrain.hasOcean) 0.0 else Double.NEGATIVE_INFINITY
    for (y in 0 until pixels) for (x in 0 until pixels) {
        val i = (y + 1) * side + (x + 1)
        val h = heights[i]
        val dhdx = (max(heights[i + 1], floor) - max(heights[i - 1], floor)) / (2 * step)
        val dhdy = (max(heights[i - side], floor) - max(heights[i + side], floor)) / (2 * step)
        val slopeCos = 1.0 / sqrt(1.0 + dhdx * dhdx + dhdy * dhdy)
        val slope = 1.0 - slopeCos
        // Light from the north-west, high.
        val nx = -dhdx; val ny = -dhdy; val nz = 1.0
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        val light = ((nx * -0.5 + ny * 0.5 + nz * 0.7) / nl / 0.99).coerceIn(0.15, 1.2)

        val rgb: Triple<Double, Double, Double>
        if (terrain.hasOcean && h < 0.0) {
            val t = (-h / 900.0).coerceIn(0.0, 1.0)
            rgb = Triple(0.10 - 0.08 * t, 0.30 - 0.21 * t, 0.46 - 0.24 * t)
        } else if (SHAPE_ONLY) {
            // Shape alone, grey tinted by height, so the landforms are easier to judge.
            val tone = 0.45 + ((h - floor.coerceAtLeast(-2_000.0)) / 4_000.0).coerceIn(0.0, 1.0) * 0.4
            rgb = Triple(tone * light, tone * light, tone * light)
        } else {
            val material = terrain.material(dirs[i], h, slope)
            counts[material.ordinal]++
            val c = swatch(material, h)
            rgb = Triple(c.first * light, c.second * light, c.third * light)
        }
        fun b(v: Double) = (v.coerceIn(0.0, 1.0) * 255).toInt()
        image.setRGB(x, y, (b(rgb.first) shl 16) or (b(rgb.second) shl 8) or b(rgb.third))
    }
    ImageIO.write(image, "png", file)
    val land = counts.sum().coerceAtLeast(1)
    val mix = SurfaceMaterial.entries.filter { counts[it.ordinal] > 0 }
        .joinToString("  ") { "%s %.0f%%".format(it.name.lowercase(), counts[it.ordinal] * 100.0 / land) }
    println("%-28s %6.1f s   relief %6.0f..%6.0f m   %s".format(
        file.name, (System.nanoTime() - started) / 1e9, heights.min(), heights.max(), mix))
}

private val SHAPE_ONLY = System.getenv("ATLAS_SHAPE") == "1"

/** A rough copy of the game's palette, for the atlas only. */
private fun swatch(material: SurfaceMaterial, h: Double): Triple<Double, Double, Double> = when (material) {
    SurfaceMaterial.GRASS -> when {
        h < 220 -> Triple(0.22, 0.42, 0.18)
        h < 520 -> Triple(0.30, 0.46, 0.20)
        else -> Triple(0.35, 0.40, 0.21)
    }
    SurfaceMaterial.DIRT -> Triple(0.48, 0.44, 0.27)
    SurfaceMaterial.SAND -> Triple(0.72, 0.66, 0.46)
    SurfaceMaterial.ROCK -> Triple(0.38, 0.35, 0.32)
    SurfaceMaterial.SCREE -> Triple(0.50, 0.47, 0.43)
    SurfaceMaterial.MUD -> Triple(0.31, 0.26, 0.19)
    SurfaceMaterial.SNOW -> Triple(0.92, 0.94, 0.97)
    SurfaceMaterial.ICE -> Triple(0.78, 0.87, 0.95)
    SurfaceMaterial.REGOLITH -> Triple(0.56, 0.55, 0.53)
    SurfaceMaterial.BASALT -> Triple(0.26, 0.26, 0.28)
    SurfaceMaterial.CLAY -> Triple(0.64, 0.37, 0.23)
    SurfaceMaterial.FOREST -> Triple(0.15, 0.31, 0.14)
    SurfaceMaterial.CONCRETE -> Triple(0.66, 0.65, 0.62)
    SurfaceMaterial.ASPHALT -> Triple(0.17, 0.17, 0.18)
    SurfaceMaterial.RED_DUST -> Triple(0.66, 0.33, 0.19)
    SurfaceMaterial.SULFUR -> Triple(0.87, 0.77, 0.27)
    SurfaceMaterial.LAVA -> Triple(1.0, 0.35, 0.08)
    SurfaceMaterial.ORGANIC_SAND -> Triple(0.27, 0.20, 0.13)
    SurfaceMaterial.NITROGEN_ICE -> Triple(0.91, 0.87, 0.84)
    SurfaceMaterial.THOLIN -> Triple(0.47, 0.25, 0.18)
    SurfaceMaterial.TESSERA -> Triple(0.50, 0.39, 0.28)
    SurfaceMaterial.OOZE -> Triple(0.58, 0.56, 0.50)
    SurfaceMaterial.NODULES -> Triple(0.30, 0.28, 0.26)
    SurfaceMaterial.VENT_CRUST -> Triple(0.45, 0.24, 0.14)
}
