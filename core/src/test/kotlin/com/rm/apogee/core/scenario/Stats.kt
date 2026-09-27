package com.rm.apogee.core.scenario

import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts

/**
 * Prints the stock rocket's stage analysis: `./gradlew :core:craftStats`.
 *
 * This is the other half of the ascent scenario. That one flies the rocket, and this one says what
 * the builder will *predict* about it. When the two disagree, one of them is wrong, and having both
 * printable side by side is how you find out which.
 */
fun main() {
    val catalog = StockParts.catalog
    val stats = CraftStats.analyze(StockCraft.starterRocket(catalog), catalog)

    println(
        "mass=%.0fkg dry=%.0fkg parts=%d liftoffTWR=%.2f totalDv=%.0f m/s".format(
            stats.totalMass, stats.dryMass, stats.partCount, stats.liftoffTwr, stats.totalDeltaV,
        )
    )
    stats.stages.forEach {
        println(
            "  stage %d %-6s engines=%d m0=%7.0f mf=%7.0f prop=%6.0fkg thrust=%8.0fN dv=%6.0f twr=%.2f burn=%.0fs"
                .format(
                    it.index, if (it.isBurn) "burn" else "-", it.engineCount,
                    it.startMass, it.endMass, it.propellantMass,
                    it.thrustVacuum, it.deltaVVacuum, it.twrSeaLevel, it.burnTime,
                )
        )
    }
    if (stats.problems.isNotEmpty()) println("problems: ${stats.problems}")
}
