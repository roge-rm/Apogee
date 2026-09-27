package com.rm.apogee.core.craft

import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.Tank
import kotlin.math.ln
import kotlin.math.roundToInt

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
    /** What this stage burns: each fuel its engines draw on, as it stands. */
    val fuel: List<FuelLevel> = emptyList(),
) {
    val propellantMass: Double get() = startMass - endMass

    /**
     * The figure worth quoting: vacuum for a rocket, sea level for a stage
     * of air-breathers, which make nothing in vacuum and would read zero.
     */
    val deltaV: Double get() = if (thrustVacuum > 0.0) deltaVVacuum else deltaVSeaLevel

    /** All its fuels together, 0..1, for a single gauge. */
    val fuelFraction: Double
        get() {
            val capacity = fuel.sumOf { it.capacity }
            return if (capacity > 0.0) fuel.sumOf { it.amount } / capacity else 0.0
        }

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

/** One fuel a stage's engines can reach: how much is left of how much there was room for. */
class FuelLevel(val type: ResourceType, val amount: Double, val capacity: Double) {
    val fraction: Double get() = if (capacity > 0.0) (amount / capacity).coerceIn(0.0, 1.0) else 0.0
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
    /** Charge it can hold, units. */
    val powerCapacity: Double = 0.0,
    /** What its panels make in full sun, wings out, units a second. */
    val powerSunlit: Double = 0.0,
    /** What it uses just being on - pods, cores, antennas - units a second. */
    val powerIdle: Double = 0.0,
    /** What its drills dig at best, units a second, and its converters take in; whether it can survey. */
    val drillRate: Double = 0.0,
    val refineRate: Double = 0.0,
    val canSurvey: Boolean = false,
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

            val full = Array(design.parts.size) { index ->
                DoubleArray(ResourceType.entries.size).also { row ->
                    resolved[index].modules.filterIsInstance<Tank>().filter { it.resource.startsFull }.forEach { row[it.resource.ordinal] += it.capacity }
                }
            }
            val totalMass = design.parts.indices.sumOf { massOf(resolved, full, it) }
            val dryMass = resolved.sumOf { it.dryMass }
            val stageStats = simulate(design, resolved, full, startStage = 0, lit = emptySet(), current = false)

            var capacity = 0.0
            var sunlit = 0.0
            var idle = 0.0
            var charges = false
            var probe = false
            var crewed = false
            var drill = 0.0
            var refine = 0.0
            var scanner = false
            for (def in resolved) for (module in def.modules) when (module) {
                is com.rm.apogee.core.part.Battery -> capacity += module.capacity
                is com.rm.apogee.core.part.SolarPanel -> { sunlit += module.chargeRate; charges = true }
                is com.rm.apogee.core.part.FuelCell -> charges = true
                is com.rm.apogee.core.part.Command -> {
                    idle += module.idleDraw
                    if (module.crewCapacity > 0) crewed = true else probe = true
                }
                is com.rm.apogee.core.part.Antenna -> idle += module.draw
                is com.rm.apogee.core.part.Scanner -> { idle += module.draw; scanner = true }
                is com.rm.apogee.core.part.Drill -> drill += module.rate
                is com.rm.apogee.core.part.Converter -> refine += module.recipes.maxOfOrNull { it.inputRate } ?: 0.0
                else -> Unit
            }
            val warnings = advise(stageStats).toMutableList()
            // Nobody aboard to fly it by hand once it is flat.
            if (probe && !crewed && !charges && idle > 0.0) {
                warnings.add("Nothing to charge it: flat in about ${(capacity / idle / 60.0).roundToInt()} min, and then it cannot be flown")
            }
            return CraftStats(
                totalMass = totalMass,
                dryMass = dryMass,
                partCount = design.parts.size,
                stages = stageStats,
                problems = diagnose(design, resolved, stageStats),
                warnings = warnings,
                powerCapacity = capacity,
                powerSunlit = sunlit,
                powerIdle = idle,
                drillRate = drill,
                refineRate = refine,
                canSurvey = scanner,
            )
        }

        /**
         * The same analysis for a craft in flight: from the stage it is on,
         * with the engines already lit and the fuel actually left.
         *
         * The first entry is what is burning now - the engines lit by the
         * stages already fired, on the fuel they can still reach - indexed
         * by the stage that lit them; the rest are the stages still to fire.
         */
        fun analyzeLive(vessel: Vessel): List<StageStats> {
            val design = vessel.design
            val defs = vessel.defs
            val amounts = Array(design.parts.size) { index ->
                DoubleArray(ResourceType.entries.size) { vessel.amountInPart(index, ResourceType.entries[it]) }
            }
            val lit = design.parts.indices.filter { vessel.isWorking(it) }.toSet()
            return simulate(design, defs, amounts, vessel.currentStage, lit, current = true)
        }

        private fun massOf(defs: List<PartDef>, amounts: Array<DoubleArray>, index: Int): Double {
            var mass = defs[index].dryMass
            for (type in ResourceType.entries) mass += amounts[index][type.ordinal] * type.densityPerUnit
            return mass
        }

        /**
         * Steps the staging sequence from [startStage] exactly as the
         * simulation would - discarding what each decoupler drops, lighting
         * what each stage ignites, and draining only the tanks the lit
         * engines can reach, of the fuels those engines burn. With [current],
         * an entry for what is burning before the next stage fires comes
         * first. [amounts] is drained in place.
         */
        private fun simulate(
            design: CraftDesign,
            defs: List<PartDef>,
            amounts: Array<DoubleArray>,
            startStage: Int,
            lit: Set<Int>,
            current: Boolean,
        ): List<StageStats> {
            val capacity = Array(design.parts.size) { index ->
                DoubleArray(ResourceType.entries.size).also { row ->
                    defs[index].modules.filterIsInstance<Tank>().forEach { row[it.resource.ordinal] += it.capacity }
                }
            }
            val live = design.parts.indices.toMutableSet()
            val burning = HashSet(lit)
            fun liveMass() = live.sumOf { massOf(defs, amounts, it) }

            val stageStats = ArrayList<StageStats>()

            fun burn(stageIndex: Int) {
                val engines = burning.filter { it in live && defs[it].module<Engine>() != null }.sorted()
                if (engines.isEmpty()) {
                    stageStats.add(emptyStage(stageIndex, liveMass()))
                    return
                }

                val startMass = liveMass()
                val groups = FuelGroups.compute(design, defs, live)
                val reachable = engines.map { groups[it] }.toSet()
                val fuelParts = live.filter { groups[it] in reachable }
                val types = engines.map { defs[it].module<Engine>()!!.propellant }.distinct()
                val fuel = types.map { type ->
                    FuelLevel(
                        type,
                        amount = fuelParts.sumOf { amounts[it][type.ordinal] },
                        capacity = fuelParts.sumOf { capacity[it][type.ordinal] },
                    )
                }
                val fuelMass = fuel.sumOf { it.amount * it.type.densityPerUnit }

                var thrustVacuum = 0.0
                var thrustSeaLevel = 0.0
                var flowVacuum = 0.0
                var flowSeaLevel = 0.0
                for (index in engines) {
                    val engine = defs[index].module<Engine>()!!
                    thrustVacuum += engine.thrustVacuum
                    thrustSeaLevel += engine.thrustSeaLevel
                    // Effective Isp for several engines sharing a tank is total
                    // thrust over total mass flow, not an average of the Isps -
                    // a thirsty engine drags the combined figure down harder
                    // than its thrust share suggests.
                    if (engine.ispVacuum > 0.0) flowVacuum += engine.thrustVacuum / (engine.ispVacuum * G0)
                    if (engine.ispSeaLevel > 0.0) flowSeaLevel += engine.thrustSeaLevel / (engine.ispSeaLevel * G0)
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
                        fuel = fuel,
                    )
                )

                // Burned dry before the next stage.
                for (part in fuelParts) for (type in types) amounts[part][type.ordinal] = 0.0
            }

            if (current) burn(startStage - 1)
            for (stageIndex in startStage until design.stages.size) {
                val stage = design.stages[stageIndex]
                // Decouplers in this stage discard what they hold, before the
                // stage's own engines light.
                for (part in stage.activatedParts) {
                    if (part in live && defs[part].module<com.rm.apogee.core.part.Decoupler>() != null) {
                        design.subtreeOf(part).forEach { live.remove(it) }
                    }
                }
                burning.addAll(stage.activatedParts.filter { it in live })
                burn(stageIndex)
            }
            return stageStats
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
            // Something has to move it. An engine does, and so does a driven
            // wheel - a rover has no engine at all, and this check refusing it
            // meant the stock rover could be built but never launched.
            val driven = defs.any { (it.module<com.rm.apogee.core.part.Wheel>()?.motorForce ?: 0.0) > 0.0 }
            if (!driven && defs.none { it.module<Engine>() != null }) {
                problems.add("No engines or driven wheels")
            }

            // An electric one - a submarine's screw - runs off any battery
            // aboard: charge is the whole craft's, not a fuel line's.
            val charged = defs.any { it.hasModule<com.rm.apogee.core.part.Battery>() }
            val enginesWithoutFuel = design.parts.indices.filter { index ->
                val engine = defs[index].module<Engine>() ?: return@filter false
                if (engine.propellant == ResourceType.ELECTRIC_CHARGE) return@filter !charged
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
