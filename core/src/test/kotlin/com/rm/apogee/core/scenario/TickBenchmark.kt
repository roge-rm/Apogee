package com.rm.apogee.core.scenario

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World

/**
 * How much CPU one simulation tick costs, against vessel count.
 *
 * `./gradlew :core:tickBenchmark`
 *
 * The number that decides how many players a server holds. A tick has 16.7ms
 * of wall clock to fit in at 60Hz; what fraction of that a given craft count
 * uses is the capacity question, and everything else about server sizing
 * follows from it.
 *
 * Deliberately crude - no JMH, no forked JVMs. It measures the thing that
 * matters to an order of magnitude, which is all that is needed to answer
 * "is the JVM fast enough for this".
 */
fun main() {
    val catalog = StockParts.catalog
    val dt = 1.0 / 60.0

    // Two scenarios, because they cost wildly different amounts and the
    // difference is the whole story. A craft under power climbs away from the
    // ground and stops sampling the height field within a minute; a craft
    // parked on a pad samples it under every contact point forever. A
    // persistent world is mostly the second kind, and measuring only the
    // first is how "capacity" ends up quoted a factor of ten too high.
    for (flying in booleanArrayOf(true, false)) {
        println()
        println(if (flying) "ASCENDING (staged, full throttle)" else "PARKED (resting on the pad)")
        println("vessels   parts   ms/tick   %% of 60Hz budget   max craft at 60Hz")
        println("-".repeat(70))

    for (count in intArrayOf(1, 5, 10, 25, 50, 100, 200)) {
        val world = World.default(catalog)
        repeat(count) { index ->
            val vessel = world.spawnOnSurface(
                StockCraft.starterRocket(catalog),
                World.launchSites.first(),
                pad = index,
            )
            if (flying) {
                // Under power, so thrust, drag and mass recomputation are all
                // in the measured path.
                world.apply(Command.Stage(vessel.id.raw))
                world.apply(Command.SetThrottle(vessel.id.raw, 1.0))
            }
        }

        // Let the JIT settle before measuring; a cold JVM measures the
        // interpreter, not the server.
        repeat(WARMUP_TICKS) { world.step(dt) }

        val started = System.nanoTime()
        repeat(MEASURED_TICKS) { world.step(dt) }
        val elapsed = System.nanoTime() - started

        val msPerTick = elapsed / 1e6 / MEASURED_TICKS
        val budget = msPerTick / (1000.0 / 60.0) * 100.0
        val capacity = if (msPerTick > 0) (1000.0 / 60.0 / msPerTick * count).toInt() else 0

        println(
            "%7d %7d %9.3f %17.1f%% %19d".format(
                count, count * 13, msPerTick, budget, capacity,
            )
        )
    }
    }

    println()
    val runtime = Runtime.getRuntime()
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    println("Heap in use after the run: ${usedMb} MB")
    println("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
}

private const val WARMUP_TICKS = 1_200
private const val MEASURED_TICKS = 2_400
