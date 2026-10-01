package com.rm.apogee.core.scenario

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World

/**
 * How much CPU one tick costs against vessel count: `./gradlew :core:tickBenchmark`. A tick has
 * 16.7 ms at 60 Hz. Crude on purpose (no JMH); it only needs to be right to an order of magnitude.
 */
fun main() {
    val catalog = StockParts.catalog
    val dt = 1.0 / 60.0

    // They cost very different amounts. A climbing craft soon stops sampling the height field, a
    // parked one samples it under every contact forever, and a rover keeps reaching new ground.
    for (scenario in Scenario.entries) {
        val flying = scenario == Scenario.ASCENDING
        val driving = scenario == Scenario.DRIVING
        println()
        println(scenario.title)
        println(
            "vessels   parts   ms/tick   %% of 60Hz budget   max craft at 60Hz   asleep   worst ms"
        )
        println("-".repeat(90))

    for (count in intArrayOf(1, 5, 10, 25, 50, 100, 200)) {
        val world = World.default(catalog)
        repeat(count) { index ->
            val vessel = world.spawnOnSurface(
                if (driving) StockCraft.rover(catalog) else StockCraft.starterRocket(catalog),
                World.launchSites.first(),
                pad = index,
            )
            if (driving) world.apply(Command.SetThrottle(vessel.id.raw, 1.0))
            if (flying) {
                // Under power, so thrust, drag and mass recomputation are all in the measured path.
                world.apply(Command.Stage(vessel.id.raw))
                world.apply(Command.SetThrottle(vessel.id.raw, 1.0))
            }
        }

        // Let the JIT settle first.
        repeat(WARMUP_TICKS) { world.step(dt) }

        // The worst tick too, since terrain work comes in lumps that an average hides.
        var worst = 0L
        val started = System.nanoTime()
        repeat(MEASURED_TICKS) {
            val tickStart = System.nanoTime()
            world.step(dt)
            worst = maxOf(worst, System.nanoTime() - tickStart)
        }
        val elapsed = System.nanoTime() - started

        val msPerTick = elapsed / 1e6 / MEASURED_TICKS
        val budget = msPerTick / (1000.0 / 60.0) * 100.0
        val capacity = if (msPerTick > 0) (1000.0 / 60.0 / msPerTick * count).toInt() else 0

        val asleep = world.vessels.count { it.dormant }
        println(
            "%7d %7d %9.3f %17.1f%% %19d %8d %10.2f".format(
                count, world.vessels.sumOf { it.defs.size }, msPerTick, budget, capacity, asleep,
                worst / 1e6,
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

private enum class Scenario(val title: String) {
    ASCENDING("ASCENDING (staged, full throttle)"),
    PARKED("PARKED (resting on the pad)"),
    DRIVING("DRIVING (rovers at full throttle, off the pad and across the country)"),
}

private const val WARMUP_TICKS = 1_200
private const val MEASURED_TICKS = 2_400
