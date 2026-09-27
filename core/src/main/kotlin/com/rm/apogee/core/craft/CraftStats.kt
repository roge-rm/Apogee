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
    /** Mass at ignition, in kg. */
    val startMass: Double,
    /** Mass at burnout, in kg. */
    val endMass: Double,
    /** Vacuum thrust of the engines lit in this stage, in N. */
    val thrustVacuum: Double,
    /** Sea-level thrust, in N. */
    val thrustSeaLevel: Double,
    val deltaVVacuum: Double,
    val deltaVSeaLevel: Double,
    /** Burn time at full throttle in vacuum, in seconds. */
    val burnTime: Double,
    /** Thrust-to-weight at ignition, against the homeworld's surface gravity. */
    val twrSeaLevel: Double,
    val engineCount: Int,
    /** What this stage burns: each fuel its engines draw on, as it is now. */
    val fuel: List<FuelLevel> = emptyList(),
) {
    val propellantMass: Double get() = startMass - endMass

    /**
     * The number worth showing: vacuum for a rocket, and sea level for a stage of air-breathers,
     * which make nothing in vacuum and would read zero.
     */
    val deltaV: Double get() = if (thrustVacuum > 0.0) deltaVVacuum else deltaVSeaLevel

    /** All its fuels together, 0..1, for a single gauge. */
    val fuelFraction: Double
        get() {
            val capacity = fuel.sumOf { it.capacity }
            return if (capacity > 0.0) fuel.sumOf { it.amount } / capacity else 0.0
        }

    /** An engine is lit during this stage, even if it's only a leftover. */
    val hasEngines: Boolean get() = engineCount > 0

    /**
     * This stage actually burns something.
     *
     * It's different from [hasEngines] on purpose. A final parachute stage still has the upper
     * stage engine lit behind it, with nothing left to feed it. The UI should show delta-v for
     * burns, not for stages that just happen to have an engine attached.
     */
    val isBurn: Boolean get() = engineCount > 0 && propellantMass > 0.0
}

/** One fuel a stage's engines can reach: how much is left out of how much room there was. */
class FuelLevel(val type: ResourceType, val amount: Double, val capacity: Double) {
    val fraction: Double get() = if (capacity > 0.0) (amount / capacity).coerceIn(0.0, 1.0) else 0.0
}

/**
 * What a design will actually do, worked out without flying it.
 *
 * This is the builder's whole reason to exist beyond looking nice. You need to know a rocket is
 * short on delta-v *before* you watch it run dry at 40 km. The numbers come from stepping through
 * the staging sequence exactly like the simulation would: throwing away what each decoupler drops,
 * lighting what each stage ignites, and draining only the tanks the lit engines can actually reach.
 */
class CraftStats(
    val totalMass: Double,
    val dryMass: Double,
    val partCount: Int,
    val stages: List<StageStats>,
    /** Reasons the craft can't be placed at all. */
    val problems: List<String>,
    /**
     * Things worth knowing that aren't reasons to refuse it.
     *
     * These are kept apart from [problems] because they're a different kind of statement. A craft
     * with no command pod can't be flown by anyone. A craft with a thrust-to-weight below one just
     * won't climb off the pad under its own power, which describes every lander ever built, and
     * isn't a fault.
     */
    val warnings: List<String> = emptyList(),
    /** How much charge it can hold, in units. */
    val powerCapacity: Double = 0.0,
    /** What its panels make in full sun with the wings out, in units a second. */
    val powerSunlit: Double = 0.0,
    /** What it uses just by being switched on (pods, cores, antennas), in units a second. */
    val powerIdle: Double = 0.0,
    /**
     * What its drills dig at best in units a second, what its converters take in, and whether it
     * can survey.
     */
    val drillRate: Double = 0.0,
    val refineRate: Double = 0.0,
    val canSurvey: Boolean = false,
    /** What its rotors lift at full collective in Terra's air at sea level, in newtons. */
    val rotorLift: Double = 0.0,
    /** The gas its cells hold, in m³. */
    val gasVolume: Double = 0.0,
) {
    /** Its rotors' lift over its weight on Terra, or 0 with none. Above 1 it can hover. */
    val hoverRatio: Double get() = if (totalMass > 0.0) rotorLift / (totalMass * REFERENCE_GRAVITY) else 0.0

    /** Its gas cells' lift over its weight at sea level on Terra, or 0 with none. Above 1 it floats. */
    val floatRatio: Double get() =
        if (totalMass > 0.0) gasVolume * TERRA_AIR * (1.0 - com.rm.apogee.core.world.Aerostatics.GAS_SHARE) / totalMass else 0.0

    /**
     * How high it floats on Terra with its ballonets empty, in metres: where the air has thinned
     * until its gas lifts just its weight. Zero if it doesn't float at all.
     */
    val ceiling: Double get() = if (floatRatio > 1.0) TERRA_SCALE_HEIGHT * kotlin.math.ln(floatRatio) else 0.0

    /** The total over all stages, in vacuum, which is the number worth showing for orbit. */
    val totalDeltaV: Double get() = stages.sumOf { it.deltaVVacuum }

    /** Thrust-to-weight of the first stage that actually burns. */
    val liftoffTwr: Double get() = stages.firstOrNull { it.isBurn }?.twrSeaLevel ?: 0.0

    /** Stages that do something, for the UI's stage list. */
    val burns: List<StageStats> get() = stages.filter { it.isBurn }

    val isFlyable: Boolean get() = problems.isEmpty()

    companion object {
        /** Standard gravity, for turning specific impulse into mass flow. */
        private const val G0 = 9.80665

        /** The homeworld's surface gravity, which the quoted TWR is measured against. */
        private const val REFERENCE_GRAVITY = 9.81

        /** Terra's air at sea level, in kg/m³, and how fast it thins, in metres per e-fold. */
        private const val TERRA_AIR = 1.225
        private const val TERRA_SCALE_HEIGHT = 5_600.0

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
            // There's nobody aboard to fly it by hand once it runs flat.
            if (probe && !crewed && !charges && idle > 0.0) {
                warnings.add("Nothing to charge it. It goes flat in about ${(capacity / idle / 60.0).roundToInt()} min, and then it can't be controlled")
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
                rotorLift = resolved.sumOf { d -> d.module<com.rm.apogee.core.part.Rotor>()?.takeIf { !it.tail }?.lift ?: 0.0 },
                gasVolume = resolved.sumOf { it.module<com.rm.apogee.core.part.LiftGas>()?.volume ?: 0.0 },
            )
        }

        /**
         * The same analysis for a craft in flight, starting from the stage it's on, with the
         * engines already lit and the fuel that's actually left.
         *
         * The first entry is what's burning now: the engines lit by stages already fired, on the
         * fuel they can still reach, indexed by the stage that lit them. The rest are the stages
         * still to fire.
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
         * Steps through the staging sequence from [startStage] exactly like the simulation would:
         * throwing away what each decoupler drops, lighting what each stage ignites, and draining
         * only the tanks the lit engines can reach, of the fuels those engines burn. With
         * [current], an entry for what's burning before the next stage fires comes first. [amounts]
         * is drained in place.
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
                    // The effective Isp for several engines sharing a tank is total thrust over
                    // total mass flow, not an average of the Isps. A thirsty engine drags the
                    // combined number down harder than its share of the thrust suggests.
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
                        // Air-breathers have no vacuum flow. They burn at their sea-level rate or
                        // not at all.
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
                // Decouplers in this stage let go of what they hold before the stage's own engines
                // light.
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
         * Problems worth telling you about before you launch.
         *
         * They're worded as what's wrong with the craft, not as validation errors. The point is to
         * save someone a wasted flight, not to refuse to save a file.
         */
        private fun diagnose(
            design: CraftDesign,
            defs: List<PartDef>,
            stages: List<StageStats>,
        ): List<String> {
            val problems = ArrayList<String>()

            if (defs.none { it.module<com.rm.apogee.core.part.Command>() != null }) {
                problems.add("No command pod, so there's nothing to control it from")
            }
            // Something has to move it. An engine does, and so does a driven wheel. A rover has no
            // engine at all, and when this check refused it, the stock rover could be built but
            // never launched.
            val driven = defs.any { (it.module<com.rm.apogee.core.part.Wheel>()?.motorForce ?: 0.0) > 0.0 } ||
                defs.any { it.module<com.rm.apogee.core.part.Sail>() != null } ||
                defs.any { it.module<com.rm.apogee.core.part.Rotor>() != null || it.module<com.rm.apogee.core.part.LiftGas>() != null } ||
                // Something with a foundation is meant to be founded where it's put, like a sea
                // platform launched in the harbour, and needs nothing to move it.
                defs.any { it.module<com.rm.apogee.core.part.Foundation>() != null }
            if (!driven && defs.none { it.module<Engine>() != null }) {
                problems.add("No engines, sails, rotors, gas cells or driven wheels")
            }

            // An electric one, like a submarine's screw, runs off any battery aboard, because
            // charge belongs to the whole craft, not to a fuel line.
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

        /**
         * Things worth pointing out about a craft that's otherwise fine. They don't stop it
         * launching.
         */
        private fun advise(stages: List<StageStats>): List<String> {
            val warnings = ArrayList<String>()
            val first = stages.firstOrNull { it.hasEngines }
            if (first != null && first.twrSeaLevel < 1.0) {
                warnings.add(
                    "Thrust-to-weight is %.2f, so it won't climb off the pad"
                        .format(first.twrSeaLevel)
                )
            }
            return warnings
        }
    }
}
