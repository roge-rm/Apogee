package com.rm.apogee.core.scenario

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.TerrainField
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Prints what the terrain function actually produces.
 *
 * `./gradlew :core:terrainSurvey`
 *
 * Parameters like sea fraction and sharpness are only meaningful as the world
 * they generate, and a histogram answers "is this a planet or a bath" faster
 * than any amount of staring at the constants.
 */
fun main() {
    val field = TerrainField(bodyRadius = 600_000.0, homeDirection = Vec3(1.0, 0.0, 0.0))
    val radius = 600_000.0
    surveyHomeRelief(field, radius, Vec3(1.0, 0.0, 0.0))

    // Fibonacci sphere: an even spread without clustering at the poles, which
    // a naive latitude/longitude grid gives and which would skew every number
    // below toward whatever the poles happen to look like.
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
        // 5 km out, the scale a launch site occupies.
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
}

/**
 * Relief near the launch complex, which is what the eye actually judges
 * altitude and drift against. A planet can have six-kilometre peaks and still
 * present a flat green sheet from the pad.
 */
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
