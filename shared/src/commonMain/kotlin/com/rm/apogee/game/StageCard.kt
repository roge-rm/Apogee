package com.rm.apogee.game

import com.rm.apogee.core.craft.StageStats
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.part.Decoupler
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.Parachute

/** One fuel on a stage card: its name and how full it is, 0..1. */
class FuelGauge(val name: String, val fraction: Float)

/**
 * One stage as the flight HUD shows it: what it fires, and for a stage that burns, how much fuel it
 * has left and what that's worth.
 */
class StageCard(
    /** The stage's number, the way the STAGE button counts. 0 fires first. */
    val index: Int,
    /** The stage burning now, as opposed to one still to fire. */
    val current: Boolean,
    /** What it fires, in a few words: "2 engines · separation", "chute". */
    val contents: String,
    val fuel: List<FuelGauge>,
    /** All its fuel together, or null for a stage that burns nothing. */
    val fuelFraction: Float?,
    val deltaV: Double?,
    /** Seconds of burn left. For the stage burning now, that's at the current throttle. */
    val burnTime: Double?,
) {
    companion object {
        /**
         * Cards for [vessel]'s stages from [stats], which is
         * [com.rm.apogee.core.craft.CraftStats.analyzeLive] of the same craft. The one burning now
         * comes first, if there is one, then each one still to fire, soonest first.
         */
        fun from(vessel: Vessel, stats: List<StageStats>, throttle: Double): List<StageCard> {
            val cards = ArrayList<StageCard>(stats.size)
            for (stat in stats) {
                val current = stat.index < vessel.currentStage
                val fired = vessel.design.stages.getOrNull(stat.index)?.activatedParts.orEmpty()
                val lightsEngine = fired.any { vessel.defs.getOrNull(it)?.module<Engine>() != null }
                // A stage burns if it lights something, or is the one burning. A chute stage behind
                // a spent engine is only a chute.
                val burns = stat.isBurn && (current || lightsEngine)
                if (current && !burns) continue
                val contents = if (current) "burning" else describe(vessel, fired)
                val burnTime = when {
                    !burns -> null
                    current && throttle > 0.05 -> stat.burnTime / throttle
                    else -> stat.burnTime
                }
                cards.add(
                    StageCard(
                        index = stat.index.coerceAtLeast(0),
                        current = current,
                        contents = contents,
                        fuel = if (burns) stat.fuel.map { FuelGauge(it.type.displayName, it.fraction.toFloat()) } else emptyList(),
                        fuelFraction = if (burns) stat.fuelFraction.toFloat() else null,
                        deltaV = if (burns) stat.deltaV else null,
                        burnTime = burnTime,
                    )
                )
            }
            return cards
        }

        private fun describe(vessel: Vessel, parts: List<Int>): String {
            var engines = 0
            var decouplers = 0
            var chutes = 0
            var legs = 0
            var fairings = 0
            for (index in parts) {
                val def = vessel.defs.getOrNull(index) ?: continue
                when {
                    def.module<Engine>() != null -> engines++
                    def.module<Decoupler>() != null -> decouplers++
                    def.module<Parachute>() != null -> chutes++
                    def.module<LandingLeg>() != null -> legs++
                    def.module<com.rm.apogee.core.part.Fairing>() != null -> fairings++
                }
            }
            val words = ArrayList<String>(4)
            if (engines > 0) words.add(if (engines == 1) "engine" else "$engines engines")
            if (decouplers > 0) words.add("separation")
            if (chutes > 0) words.add(if (chutes == 1) "chute" else "$chutes chutes")
            if (legs > 0) words.add("legs")
            if (fairings > 0) words.add("shroud")
            return words.joinToString(" · ").ifEmpty { "empty" }
        }
    }
}
