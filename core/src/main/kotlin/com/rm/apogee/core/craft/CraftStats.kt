package com.rm.apogee.core.craft

import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.Tank
import kotlin.math.ln

/** What one stage of the staging sequence will do. */
class StageStats(
    val index: Int,
    /** Mass at ignition, kg. */
    val startMass: Double,
    /** Mass at burnout, kg. */
    val endMass: Double,
    /** Vacuum thrust of the engines lit in this stage, N. */
    val thrustVacuum: Double,
    /** Sea-level thrust, N. */
    val thrustSeaLevel: Double,
    val deltaVVacuum: Double,
    val deltaVSeaLevel: Double,
    /** Burn time at full throttle in vacuum, seconds. */
    val burnTime: Double,
    /** Thrust-to-weight at ignition, against the homeworld's surface gravity. */
    val twrSeaLevel: Double,
    val engineCount: Int,
) {
    val propellantMass: Double get() = startMass - endMass

    /** An engine is lit during this stage - which may just be a leftover. */
    val hasEngines: Boolean get() = engineCount > 0

    /**
     * This stage actually burns something.
     *
     * Distinct from [hasEngines] on purpose: a final parachute stage still has
     * the upper-stage engine lit behind it, with nothing left to feed it. The
     * UI should quote delta-v for burns, not for stages that merely happen to
     * have an engine attached.
     */
    val isBurn: Boolean get() = engineCount > 0 && propellantMass > 0.0
}

/**
 * What a design will actually do, computed without flying it.
 *
 * This is the builder's whole reason to exist beyond looking pretty: a player
 * needs to know a rocket is short on delta-v *before* watching it run dry at
 * 40 km. The numbers come from stepping the staging sequence forward exactly as
 * the simulation would - discarding what each decoupler drops, lighting what
 * each stage ignites, and draining only the tanks the lit engines can actually
 * reach.
 */
class CraftStats(
    val totalMass: Double,
    val dryMass: Double,
    val partCount: Int,
    val stages: List<StageStats>,
    /** Reasons the craft cannot be placed at all. */
    val problems: List<String>,
    /**
     * Things worth knowing that are not reasons to refuse.
     *
     * Kept apart from [problems] because they are a different kind of
     * statement. A craft with no command pod cannot be flown by anyone; a
     * craft with a thrust-to-weight below one simply will not climb off the
     * pad under its own power - which is an accurate description of every
     * lander ever built, and not a fault.
     */
    val warnings: List<String> = emptyList(),
) {
    /** Sum over stages. Vacuum, which is the figure worth quoting for orbit. */
    val totalDeltaV: Double get() = stages.sumOf { it.deltaVVacuum }

    /** Thrust-to-weight of the first stage that actually burns. */
    val liftoffTwr: Double get() = stages.firstOrNull { it.isBurn }?.twrSeaLevel ?: 0.0

    /** Stages that do something, for the UI's stage list. */
    val burns: List<StageStats> get() = stages.filter { it.isBurn }

    val isFlyable: Boolean get() = problems.isEmpty()

    companion object {
        /** Standard gravity, for converting specific impulse to mass flow. */
        private const val G0 = 9.80665

        /** Homeworld surface gravity, the reference for quoted TWR. */
        private const val REFERENCE_GRAVITY = 9.81

        fun analyze(design: CraftDesign, catalog: PartCatalog): CraftStats {
            if (design.parts.isEmpty()) {
                return CraftStats(0.0, 0.0, 0, emptyList(), listOf("No parts"))
            }

            val defs = design.parts.map { catalog[it.partId] }
            val missing = design.parts.indices.filter { defs[it] == null }
            if (missing.isNotEmpty()) {
                return CraftStats(
                    0.0, 0.0, design.parts.size, emptyList(),
                    listOf("Unknown parts: ${missing.map { design.parts[it].partId }.distinct()}"),
                )
            }

            @Suppress("UNCHECKED_CAST")
            val resolved = defs as List<PartDef>

            // Mutable flight state: what is still attached, and how full it is.
            val live = design.parts.indices.toMutableSet()
            val propellant = DoubleArray(design.parts.size)
            resolved.forEachIndexed { index, def ->
                propellant[index] = def.modules.filterIsInstance<Tank>()
                    .filter { it.resource == ResourceType.PROPELLANT }
                    .sumOf { it.capacity }
            }

            fun massOf(index: Int) =
                resolved[index].dryMass +
                    propellant[index] * ResourceType.PROPELLANT.densityPerUnit

            fun liveMass() = live.sumOf { massOf(it) }

            val totalMass = liveMass()
            val dryMass = live.sumOf { resolved[it].dryMass }

            val stageStats = ArrayList<StageStats>()
            val lit = HashSet<Int>()

            design.stages.forEachIndexed { stageIndex, stage ->
                // Decouplers in this stage discard what they hold, before the
                // stage's own engines light.
                for (part in stage.activatedParts) {
                    if (resolved[part].module<com.rm.apogee.core.part.Decoupler>() != null) {
                        design.subtreeOf(part).forEach { live.remove(it) }
                    }
                }
                lit.addAll(stage.activatedParts.filter { it in live })

                val engines = lit.filter { it in live && resolved[it].module<Engine>() != null }
                if (engines.isEmpty()) {
                    stageStats.add(emptyStage(stageIndex, liveMass()))
                    return@forEachIndexed
                }

                val startMass = liveMass()
                val groups = FuelGroups.compute(design, resolved, live)
                val reachable = engines.map { groups[it] }.toSet()
                val fuelParts = live.filter { groups[it] in reachable }
                val fuelUnits = fuelParts.sumOf { propellant[it] }
                val fuelMass = fuelUnits * ResourceType.PROPELLANT.densityPerUnit

                var thrustVacuum = 0.0
                var thrustSeaLevel = 0.0
                var flowVacuum = 0.0
                var flowSeaLevel = 0.0
                for (index in engines) {
                    val engine = resolved[index].module<Engine>()!!
                    thrustVacuum += engine.thrustVacuum
                    thrustSeaLevel += engine.thrustSeaLevel
                    // Effective Isp for several engines sharing a tank is total
                    // thrust over total mass flow, not an average of the Isps -
                    // a thirsty engine drags the combined figure down harder
                    // than its thrust share suggests.
                    flowVacuum += engine.thrustVacuum / (engine.ispVacuum * G0)
                    flowSeaLevel += engine.thrustSeaLevel / (engine.ispSeaLevel * G0)
                }

                val endMass = startMass - fuelMass
                val ispVacuum = if (flowVacuum > 0) thrustVacuum / (flowVacuum * G0) else 0.0
                val ispSeaLevel = if (flowSeaLevel > 0) thrustSeaLevel / (flowSeaLevel * G0) else 0.0

                val ratio = if (endMass > 0) startMass / endMass else 1.0
                val deltaVVacuum = if (ratio > 1.0) ispVacuum * G0 * ln(ratio) else 0.0
                val deltaVSeaLevel = if (ratio > 1.0) ispSeaLevel * G0 * ln(ratio) else 0.0

                stageStats.add(
                    StageStats(
                        index = stageIndex,
                        startMass = startMass,
                        endMass = endMass,
                        thrustVacuum = thrustVacuum,
                        thrustSeaLevel = thrustSeaLevel,
                        deltaVVacuum = deltaVVacuum,
                        deltaVSeaLevel = deltaVSeaLevel,
                        // Air-breathers have no vacuum flow; they burn at
                        // their sea-level rate or not at all.
                        burnTime = when {
                            flowVacuum > 0 -> fuelMass / flowVacuum
                            flowSeaLevel > 0 -> fuelMass / flowSeaLevel
                            else -> 0.0
                        },
                        twrSeaLevel = thrustSeaLevel / (startMass * REFERENCE_GRAVITY),
                        engineCount = engines.size,
                    )
                )

                // Burned dry before the next stage.
                fuelParts.forEach { propellant[it] = 0.0 }
            }

            return CraftStats(
                totalMass = totalMass,
                dryMass = dryMass,
                partCount = design.parts.size,
                stages = stageStats,
                problems = diagnose(design, resolved, stageStats),
                warnings = advise(stageStats),
            )
        }

        private fun emptyStage(index: Int, mass: Double) = StageStats(
            index = index, startMass = mass, endMass = mass,
            thrustVacuum = 0.0, thrustSeaLevel = 0.0,
            deltaVVacuum = 0.0, deltaVSeaLevel = 0.0,
            burnTime = 0.0, twrSeaLevel = 0.0, engineCount = 0,
        )

        /**
         * Problems worth telling the player about before they launch.
         *
         * Phrased as what is wrong with the craft, not as validation errors -
         * the point is to save someone a flight, not to refuse to save a file.
         */
        private fun diagnose(
            design: CraftDesign,
            defs: List<PartDef>,
            stages: List<StageStats>,
        ): List<String> {
            val problems = ArrayList<String>()

            if (defs.none { it.module<com.rm.apogee.core.part.Command>() != null }) {
                problems.add("No command pod - nothing to fly it from")
            }
            if (defs.none { it.module<Engine>() != null }) {
                problems.add("No engines")
            }

            val enginesWithoutFuel = design.parts.indices.filter { index ->
                defs[index].module<Engine>() != null &&
                    FuelGroups.compute(design, defs).let { groups ->
                        design.parts.indices.none {
                            groups[it] == groups[index] &&
                                defs[it].modules.filterIsInstance<Tank>()
                                    .any { tank -> tank.resource == ResourceType.PROPELLANT }
                        }
                    }
            }
            if (enginesWithoutFuel.isNotEmpty()) {
                problems.add("${enginesWithoutFuel.size} engine(s) have no fuel tank connected")
            }

            return problems
        }

        /** Non-blocking observations about a craft that is otherwise fine. */
        private fun advise(stages: List<StageStats>): List<String> {
            val warnings = ArrayList<String>()
            val first = stages.firstOrNull { it.hasEngines }
            if (first != null && first.twrSeaLevel < 1.0) {
                warnings.add(
                    "Thrust-to-weight is %.2f - it will not climb off the pad"
                        .format(first.twrSeaLevel)
                )
            }
            return warnings
        }
    }
}
