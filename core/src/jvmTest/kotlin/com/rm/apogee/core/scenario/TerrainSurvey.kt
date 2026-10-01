package com.rm.apogee.core.scenario

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.TerrainField
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Prints what the terrain function really makes: `./gradlew :core:terrainSurvey`. A histogram says
 * more about settings like sea fraction and sharpness than the constants do.
 */
fun main() {
    val field = TerrainField(bodyRadius = 600_000.0, homeDirection = Vec3(1.0, 0.0, 0.0))
    val radius = 600_000.0
    surveyHomeRelief(field, radius, Vec3(1.0, 0.0, 0.0))
    surveyMeshError(field, radius, Vec3(1.0, 0.0, 0.0))

    // Fibonacci sphere: an even spread that doesn't bunch at the poles like a lat/long grid.
    val samples = 40_000
    val golden = PI * (3.0 - kotlin.math.sqrt(5.0))
    val elevations = DoubleArray(samples)

    for (i in 0 until samples) {
        val y = 1.0 - (i.toDouble() / (samples - 1)) * 2.0
        val r = kotlin.math.sqrt(1.0 - y * y)
        val theta = golden * i
        elevations[i] = field.elevation(Vec3(cos(theta) * r, y, sin(theta) * r))
    }

    val ocean = elevations.count { it < 0.0 }
    println("samples      %,d".format(samples))
    println("ocean        %.1f%%   land %.1f%%".format(
        ocean * 100.0 / samples, (samples - ocean) * 100.0 / samples))
    println("lowest       %,.0f m".format(elevations.min()))
    println("highest      %,.0f m".format(elevations.max()))
    println("mean land    %,.0f m".format(
        elevations.filter { it > 0 }.average()))

    println()
    println("elevation distribution")
    val buckets = intArrayOf(-3000, -2000, -1000, -1, 0, 250, 500, 1000, 2000, 3000, 4000, 6000)
    for (i in 0 until buckets.size - 1) {
        val low = buckets[i]
        val high = buckets[i + 1]
        val count = elevations.count { it >= low && it < high }
        val bar = "#".repeat((count * 60 / samples).coerceAtMost(60))
        println("%6d..%-6d %5.1f%% %s".format(low, high, count * 100.0 / samples, bar))
    }

    println()
    println("the launch complex")
    val home = Vec3(1.0, 0.0, 0.0)
    println("  elevation at the pad   %,.0f m".format(field.elevation(home)))
    println("  ocean there?           ${field.isOcean(home)}")
    // A pad on a cliff edge is as bad as a pad in the sea.
    val normal = field.surfaceNormal(home)
    val slopeDegrees = Math.toDegrees(acos((normal dot home).coerceIn(-1.0, 1.0)))
    println("  local slope            %.1f degrees".format(slopeDegrees))

    var dryWithin = 0
    val ring = 40
    for (i in 0 until ring) {
        val angle = 2.0 * PI * i / ring
        // 5 km out, the scale a launch site takes up.
        val offset = 5_000.0 / radius
        val probe = Vec3(1.0, sin(angle) * offset, cos(angle) * offset)
        if (!field.isOcean(probe)) dryWithin++
    }
    println("  dry within 5km         $dryWithin of $ring directions")

    println()
    println("determinism")
    val again = TerrainField(bodyRadius = 600_000.0, homeDirection = Vec3(1.0, 0.0, 0.0))
    val differences = (0 until 5_000).count {
        again.elevation(Vec3(cos(it * 0.7), sin(it * 0.3), cos(it * 1.1))) !=
            field.elevation(Vec3(cos(it * 0.7), sin(it * 0.3), cos(it * 1.1)))
    }
    println("  identical across instances: ${differences == 0}")

    surveyCost(field)
}

/** What one sample of the height field costs. The collider and the mesh builder both pay it. */
fun surveyCost(field: TerrainField) {
    val directions = Array(20_000) { i ->
        Vec3(cos(i * 0.37), sin(i * 0.11), cos(i * 0.73)).normalizeInPlace()
    }
    var sink = 0.0
    // Warm up the JIT before timing anything.
    repeat(3) { directions.forEach { sink += field.elevation(it) } }
    val rounds = 5
    val start = System.nanoTime()
    repeat(rounds) { directions.forEach { sink += field.elevation(it) } }
    val nanos = (System.nanoTime() - start).toDouble() / (rounds * directions.size)
    println()
    println("cost")
    println("  per elevation sample   %.0f ns".format(nanos))
    println("  per 65x65 tile         %.2f ms  (on this machine; a phone is several times slower)"
        .format(nanos * 65 * 65 / 1e6))

    // Real tiles, built and thrown away, including the material pass.
    val tiles = field.tiles.tilesPerFace
    repeat(3) { com.rm.apogee.core.terrain.TerrainTile.build(field, 0, tiles / 2 + it, tiles / 2, tiles) }
    val tileStart = System.nanoTime()
    val built = 20
    repeat(built) { com.rm.apogee.core.terrain.TerrainTile.build(field, 0, tiles / 2 + it, tiles / 2 + 7, tiles) }
    println("  per tile, measured     %.2f ms  (%d x %d cells, %.0f m across)".format(
        (System.nanoTime() - tileStart) / 1e6 / built,
        com.rm.apogee.core.terrain.TerrainTile.CELLS, com.rm.apogee.core.terrain.TerrainTile.CELLS,
        field.bodyRadius * Math.PI / 2.0 / tiles))
    if (sink == 42.0) println()
}

/** Relief near the launch complex, which is what the eye judges height and drift against. */
fun surveyHomeRelief(field: TerrainField, radius: Double, pad: Vec3) {
    val east = (if (kotlin.math.abs(pad.y) < 0.9) Vec3.unitY() else Vec3.unitX())
        .cross(pad).normalizeInPlace()
    val north = pad.cross(east).normalizeInPlace()

    println()
    println("relief around the launch complex:")
    println("  at the pad: %.1f m".format(field.elevation(pad)))
    for (ring in listOf(200.0, 500.0, 1_000.0, 2_500.0, 5_000.0, 10_000.0, 25_000.0, 60_000.0)) {
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        var ocean = 0
        val samples = 96
        repeat(samples) { i ->
            val angle = 2.0 * PI * i / samples
            val step = ring / radius
            val d = Vec3(
                pad.x + (east.x * cos(angle) + north.x * sin(angle)) * step,
                pad.y + (east.y * cos(angle) + north.y * sin(angle)) * step,
                pad.z + (east.z * cos(angle) + north.z * sin(angle)) * step,
            ).normalizeInPlace()
            val e = field.elevation(d)
            if (e < low) low = e
            if (e > high) high = e
            if (e < 0.0) ocean++
        }
        println(
            "  %6.0f m out: %8.1f .. %8.1f   spread %7.1f m   ocean %d/96"
                .format(ring, low, high, high - low, ocean)
        )
    }
}

/**
 * How far the drawn ground sits from the collider's. The mesh is a grid of flat triangles and the
 * collider is exact, so any gap shows as a craft floating above or sunk into the ground.
 */
fun surveyMeshError(field: TerrainField, radius: Double, pad: Vec3) {
    val up = pad.normalized()
    val east = (if (kotlin.math.abs(up.y) < 0.9) Vec3.unitY() else Vec3.unitX())
        .cross(up).normalizeInPlace()
    val north = up.cross(east).normalizeInPlace()

    fun direction(alongEast: Double, alongNorth: Double) = Vec3(
        up.x * radius + east.x * alongEast + north.x * alongNorth,
        up.y * radius + east.y * alongEast + north.y * alongNorth,
        up.z * radius + east.z * alongEast + north.z * alongNorth,
    ).normalizeInPlace()

    fun height(alongEast: Double, alongNorth: Double) =
        kotlin.math.max(field.elevation(direction(alongEast, alongNorth)), 0.0)

    println()
    println("drawn-vs-true ground error, by mesh density:")
    println("  extent  verts  facet      at pad   mean     p99      worst")
    for ((extent, resolution) in listOf(
        4_000.0 to 48, 4_000.0 to 64, 4_000.0 to 80, 4_000.0 to 128,
        1_500.0 to 48, 1_500.0 to 64, 1_000.0 to 48,
    )) {
        val cell = 2.0 * extent / (resolution - 1)

        // Bilinear interpolation of the grid, exactly the way the mesh draws it.
        fun drawnHeight(e: Double, n: Double): Double {
            val gx = (e + extent) / cell
            val gy = (n + extent) / cell
            val x0 = kotlin.math.floor(gx).toInt().coerceIn(0, resolution - 2)
            val y0 = kotlin.math.floor(gy).toInt().coerceIn(0, resolution - 2)
            val fx = gx - x0
            val fy = gy - y0
            val e0 = x0 * cell - extent
            val n0 = y0 * cell - extent
            val h00 = height(e0, n0)
            val h10 = height(e0 + cell, n0)
            val h01 = height(e0, n0 + cell)
            val h11 = height(e0 + cell, n0 + cell)
            return (h00 * (1 - fx) + h10 * fx) * (1 - fy) +
                (h01 * (1 - fx) + h11 * fx) * fy
        }

        val errors = ArrayList<Double>()
        var sum = 0.0
        val samples = 120
        for (i in 0 until samples) {
            for (j in 0 until samples) {
                // Sample well inside the patch, where a craft actually flies.
                val e = (i / (samples - 1.0) - 0.5) * extent
                val n = (j / (samples - 1.0) - 0.5) * extent
                val error = kotlin.math.abs(drawnHeight(e, n) - height(e, n))
                errors.add(error)
                sum += error
            }
        }
        errors.sort()
        println(
            "  %6.0f  %5d  %5.0fm   %7.2f %7.2f %7.2f  %8.2f".format(
                extent, resolution, cell,
                kotlin.math.abs(drawnHeight(0.0, 0.0) - height(0.0, 0.0)),
                sum / errors.size,
                errors[(errors.size * 0.99).toInt()],
                errors.last(),
            )
        )
    }
}
