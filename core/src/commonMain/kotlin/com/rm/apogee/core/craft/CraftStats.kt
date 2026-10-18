package com.rm.apogee.core.craft

import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.Tank
import kotlin.math.exp
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
    /** A solid motor burns in it, so its burn time doesn't change with the throttle. */
    val solid: Boolean = false,
) {
    val propellantMass: Double get() = startMass - endMass

    /** Vacuum for a rocket, sea level for air-breathers, which make nothing in vacuum. */
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
     * This stage burns something. Unlike [hasEngines], a parachute stage with a dry engine still lit
     * doesn't count.
     */
    val isBurn: Boolean get() = engineCount > 0 && propellantMass > 0.0
}

/** One fuel a stage's engines can reach: how much is left out of how much room there was. */
class FuelLevel(val type: ResourceType, val amount: Double, val capacity: Double) {
    val fraction: Double get() = if (capacity > 0.0) (amount / capacity).coerceIn(0.0, 1.0) else 0.0
}

/**
 * What a design will do, worked out without flying it, by stepping through staging the way the
 * simulation does.
 */
class CraftStats(
    val totalMass: Double,
    val dryMass: Double,
    val partCount: Int,
    val stages: List<StageStats>,
    /** Reasons the craft can't be placed at all. */
    val problems: List<String>,
    /** Worth knowing but not a reason to refuse it, like a lander's TWR below one. */
    val warnings: List<String> = emptyList(),
    /** How much charge it can hold, in units. */
    val powerCapacity: Double = 0.0,
    /** What its panels make in full sun with the wings out, in units a second. */
    val powerSunlit: Double = 0.0,
    /** What it uses just by being switched on (pods, cores, antennas), in units a second. */
    val powerIdle: Double = 0.0,
    /** Best drill rate and converter intake, in units a second, and whether it can survey. */
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

    /** How high it floats on Terra with ballonets empty, in metres, or 0 if it doesn't float. */
    val ceiling: Double get() = if (floatRatio > 1.0) TERRA_SCALE_HEIGHT * kotlin.math.ln(floatRatio) else 0.0

    /** Total over all stages, in vacuum. */
    val totalDeltaV: Double get() = stages.sumOf { it.deltaVVacuum }

    /** Thrust-to-weight of the first stage that actually burns. */
    val liftoffTwr: Double get() = stages.firstOrNull { it.isBurn }?.twrSeaLevel ?: 0.0

    /** Stages that do something, for the UI's stage list. */
    val burns: List<StageStats> get() = stages.filter { it.isBurn }

    val isFlyable: Boolean get() = problems.isEmpty()

    companion object {
        /**
         * Where a solid is on its thrust curve with [left] kg of [full] left, burning [rate] kg/s at
         * rated thrust. Along one straight piece the thrust falls or rises exponentially in time, as
         * flow follows thrust.
         */
        private fun solidCurve(engine: Engine, left: Double, full: Double, rate: Double): CurvePlace {
            val points = engine.thrustCurve.ifEmpty { FLAT }
            val f = if (full > 0.0) left / full else 0.0
            var j = 0
            while (j + 2 < points.size && points[j + 1][0] >= f - 1e-12) j++
            val hi = points[j]
            val lo = points[j + 1]
            val slope = (hi[1] - lo[1]) / (hi[0] - lo[0])
            val start = lo[1] + slope * (f - lo[0])
            // Share of the load a second at rated thrust.
            val k = if (full > 0.0) rate / full else 0.0
            val flat = kotlin.math.abs(slope) < 1e-12
            val seconds = when {
                k <= 0.0 -> Double.POSITIVE_INFINITY
                flat -> (f - lo[0]) / (k * start)
                else -> ln(start / lo[1]) / (slope * k)
            }
            return CurvePlace(maxOf(seconds, 0.0), lo[0] * full) { t ->
                val after = if (flat) f - k * start * t else lo[0] + (start * exp(-slope * k * t) - lo[1]) / slope
                maxOf(after, lo[0]) * full
            }
        }

        private val FLAT = listOf(listOf(1.0, 1.0), listOf(0.0, 1.0))

        /** Seconds to the next corner of a thrust curve, kg left there, and kg left after a time short of it. */
        private class CurvePlace(val seconds: Double, val endLeft: Double, val leftAfter: (Double) -> Double)

        /** Standard gravity, for turning specific impulse into mass flow. */
        private const val G0 = 9.80665

        /** The homeworld's surface gravity, which the quoted TWR is measured against. */
        private const val REFERENCE_GRAVITY = 9.81

        /** The share of rotor twist left uncancelled before it warns. */
        private const val UNCANCELLED_TWIST = 0.2

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
            // A probe with nothing to charge it dies when it runs flat.
            if (probe && !crewed && !charges && idle > 0.0) {
                warnings.add("Nothing charges it, so it goes flat in about ${(capacity / idle / 60.0).roundToInt()} min")
            }
            // A rotor twists the craft against its blades. It needs a tail rotor or a counter-turning
            // one to hold heading.
            val spins = Vessel.rotorSpins(design, resolved)
            var twist = 0.0
            var twisting = 0.0
            var tail = false
            for (i in resolved.indices) {
                val rotor = resolved[i].module<com.rm.apogee.core.part.Rotor>() ?: continue
                if (rotor.tail) { tail = true; continue }
                twist += spins[i] * rotor.torque * rotor.lift
                twisting += rotor.torque * rotor.lift
            }
            if (!tail && twisting > 0.0 && kotlin.math.abs(twist) > UNCANCELLED_TWIST * twisting) {
                warnings.add("Its rotor will spin it. Add a tail rotor, or a second rotor turning the other way")
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
         * The same analysis in flight, from the current stage with the fuel that's left. The first
         * entry is what's burning now; the rest are stages still to fire.
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
         * Steps through staging from [startStage] like the simulation: drop what decouplers let go,
         * light what each stage ignites, drain only tanks the lit engines reach. With [current], what's
         * burning now comes first. Drains [amounts] in place.
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

            // Whether the stage after [stageIndex] drops parts or lights an engine, so a liquid
            // burning on past the solids shows there rather than on a card of chutes alone.
            fun changesAfter(stageIndex: Int): Boolean {
                val next = design.stages.getOrNull(stageIndex + 1) ?: return false
                return next.activatedParts.any { part ->
                    part in live && (defs[part].module<com.rm.apogee.core.part.Decoupler>() != null || defs[part].module<Engine>() != null)
                }
            }

            fun burn(stageIndex: Int, last: Boolean) {
                // A spent solid stays lit but gives nothing.
                val engines = burning.filter { index ->
                    val engine = if (index in live) defs[index].module<Engine>() else null
                    engine != null && (!engine.solid || amounts[index][engine.propellant.ordinal] > 0.0)
                }.sorted()
                if (engines.isEmpty()) {
                    stageStats.add(emptyStage(stageIndex, liveMass()))
                    return
                }
                val solids = engines.filter { defs[it].module<Engine>()!!.solid }
                val liquids = engines.filter { !defs[it].module<Engine>()!!.solid }

                val startMass = liveMass()
                val groups = FuelGroups.compute(design, defs, live)
                val reachable = liquids.map { groups[it] }.toSet()
                // A stage burns only its outermost tanks, which the next staging drops. Same rule
                // as in flight (see [FuelGroups.tiers]). A solid burns only its own part.
                val tiers = FuelGroups.tiers(design, defs)
                val types = liquids.map { defs[it].module<Engine>()!!.propellant }.distinct()
                val reach = live.filter { groups[it] in reachable && it !in solids }
                val outer = reach.filter { part -> types.any { amounts[part][it.ordinal] > 0.0 } }.maxOfOrNull { tiers[it] } ?: 0
                val fuelParts = reach.filter { tiers[it] == outer || outer == 0 }
                val fuel = types.map { type ->
                    FuelLevel(
                        type,
                        amount = fuelParts.sumOf { amounts[it][type.ordinal] },
                        capacity = fuelParts.sumOf { capacity[it][type.ordinal] },
                    )
                } + solids.map { defs[it].module<Engine>()!!.propellant }.distinct().map { type ->
                    FuelLevel(
                        type,
                        amount = solids.sumOf { amounts[it][type.ordinal] },
                        capacity = solids.sumOf { capacity[it][type.ordinal] },
                    )
                }
                val liquidMass = fuelParts.sumOf { part -> types.sumOf { amounts[part][it.ordinal] * it.densityPerUnit } }

                var thrustVacuum = 0.0
                var thrustSeaLevel = 0.0
                var liquidThrustVacuum = 0.0
                var liquidThrustSeaLevel = 0.0
                var liquidFlowVacuum = 0.0
                var liquidFlowSeaLevel = 0.0
                for (index in engines) {
                    val engine = defs[index].module<Engine>()!!
                    thrustVacuum += engine.thrustVacuum
                    thrustSeaLevel += engine.thrustSeaLevel
                    if (engine.solid) continue
                    liquidThrustVacuum += engine.thrustVacuum
                    liquidThrustSeaLevel += engine.thrustSeaLevel
                    if (engine.ispVacuum > 0.0) liquidFlowVacuum += engine.thrustVacuum / (engine.ispVacuum * G0)
                    if (engine.ispSeaLevel > 0.0) liquidFlowSeaLevel += engine.thrustSeaLevel / (engine.ispSeaLevel * G0)
                }
                // Mass flow, kg/s. Air-breathers have no vacuum flow, so they use the sea-level rate.
                val liquidRate = if (liquidFlowVacuum > 0.0) liquidFlowVacuum else liquidFlowSeaLevel
                val solidRate = DoubleArray(solids.size) { k ->
                    val engine = defs[solids[k]].module<Engine>()!!
                    when {
                        engine.ispVacuum > 0.0 -> engine.thrustVacuum / (engine.ispVacuum * G0)
                        engine.ispSeaLevel > 0.0 -> engine.thrustSeaLevel / (engine.ispSeaLevel * G0)
                        else -> 0.0
                    }
                }
                val solidLeft = DoubleArray(solids.size) { k ->
                    val engine = defs[solids[k]].module<Engine>()!!
                    if (solidRate[k] > 0.0) amounts[solids[k]][engine.propellant.ordinal] * engine.propellant.densityPerUnit else 0.0
                }
                // Each solid's full load, kg, to read its thrust curve by the share left.
                val solidFull = DoubleArray(solids.size) { k ->
                    val engine = defs[solids[k]].module<Engine>()!!
                    capacity[solids[k]][engine.propellant.ordinal] * engine.propellant.densityPerUnit
                }

                // Phases, each ending as a solid burns out or reaches a corner of its thrust curve.
                // Once the solids are all out the liquids stop too if a later stage is to come,
                // carrying what's left on; in the last stage, or when nothing after changes the
                // craft, they burn dry.
                var mass = startMass
                var liquidLeft = if (liquidRate > 0.0) liquidMass else 0.0
                var seconds = 0.0
                var deltaVVacuum = 0.0
                var deltaVSeaLevel = 0.0
                var phases = 0
                while (phases++ < 1000) {
                    val solidsOn = solidLeft.any { it > 0.0 }
                    val liquidsOn = liquidLeft > 0.0 && (solidsOn || solids.isEmpty() || last)
                    if (!solidsOn && !liquidsOn) break
                    var span = if (liquidsOn) liquidLeft / liquidRate else Double.POSITIVE_INFINITY
                    for (k in solids.indices) if (solidLeft[k] > 0.0) {
                        span = minOf(span, solidCurve(defs[solids[k]].module<Engine>()!!, solidLeft[k], solidFull[k], solidRate[k]).seconds)
                    }
                    // Split in steps so the mix of Isps as the solids' thrust changes is followed.
                    val steps = if (solids.any { defs[it].module<Engine>()!!.thrustCurve.isNotEmpty() }) 8 else 1
                    val step = span / steps
                    // What ends this phase lands exactly on its end.
                    val liquidEnds = liquidsOn && liquidLeft / liquidRate <= span
                    val solidEnds = BooleanArray(solids.size) { k ->
                        solidLeft[k] > 0.0 && solidCurve(defs[solids[k]].module<Engine>()!!, solidLeft[k], solidFull[k], solidRate[k]).seconds <= span
                    }
                    for (n in 0 until steps) {
                        val ending = n == steps - 1
                        // Thrust and flow over the step, each as newton-seconds and kg.
                        var impulseVacuum = 0.0
                        var impulseSeaLevel = 0.0
                        var usedVacuum = 0.0
                        var usedSeaLevel = 0.0
                        var burnt = 0.0
                        if (liquidsOn) {
                            impulseVacuum += liquidThrustVacuum * step
                            impulseSeaLevel += liquidThrustSeaLevel * step
                            usedVacuum += liquidFlowVacuum * step
                            usedSeaLevel += liquidFlowSeaLevel * step
                            burnt += liquidRate * step
                            liquidLeft = if (ending && liquidEnds) 0.0 else liquidLeft - liquidRate * step
                        }
                        for (k in solids.indices) {
                            if (solidLeft[k] <= 0.0) continue
                            val engine = defs[solids[k]].module<Engine>()!!
                            val curve = solidCurve(engine, solidLeft[k], solidFull[k], solidRate[k])
                            val after = if (ending && solidEnds[k]) curve.endLeft else curve.leftAfter(step)
                            val gone = solidLeft[k] - after
                            // Seconds at rated thrust that burn what it burnt.
                            val rated = gone / solidRate[k]
                            impulseVacuum += engine.thrustVacuum * rated
                            impulseSeaLevel += engine.thrustSeaLevel * rated
                            if (engine.ispVacuum > 0.0) usedVacuum += engine.thrustVacuum / (engine.ispVacuum * G0) * rated
                            if (engine.ispSeaLevel > 0.0) usedSeaLevel += engine.thrustSeaLevel / (engine.ispSeaLevel * G0) * rated
                            burnt += gone
                            solidLeft[k] = after
                        }
                        val end = mass - burnt
                        // Combined Isp is total thrust over total mass flow, not the average Isp.
                        if (end > 0.0 && mass > end) {
                            val ratio = ln(mass / end)
                            if (usedVacuum > 0.0) deltaVVacuum += impulseVacuum / usedVacuum * ratio
                            if (usedSeaLevel > 0.0) deltaVSeaLevel += impulseSeaLevel / usedSeaLevel * ratio
                        }
                        mass = end
                    }
                    seconds += span
                }

                stageStats.add(
                    StageStats(
                        index = stageIndex,
                        startMass = startMass,
                        endMass = mass,
                        thrustVacuum = thrustVacuum,
                        thrustSeaLevel = thrustSeaLevel,
                        deltaVVacuum = deltaVVacuum,
                        deltaVSeaLevel = deltaVSeaLevel,
                        burnTime = seconds,
                        twrSeaLevel = thrustSeaLevel / (startMass * REFERENCE_GRAVITY),
                        engineCount = engines.size,
                        fuel = fuel,
                        solid = solids.isNotEmpty(),
                    )
                )

                // What's left for the next stage: the liquids' share in their tanks, and each solid's own.
                val keep = if (liquidMass > 0.0 && liquidLeft > 0.0) liquidLeft / liquidMass else 0.0
                for (part in fuelParts) for (type in types) amounts[part][type.ordinal] *= keep
                for (k in solids.indices) {
                    val engine = defs[solids[k]].module<Engine>()!!
                    if (solidRate[k] > 0.0) amounts[solids[k]][engine.propellant.ordinal] = solidLeft[k] / engine.propellant.densityPerUnit
                }
            }

            if (current) burn(startStage - 1, last = !changesAfter(startStage - 1))
            for (stageIndex in startStage until design.stages.size) {
                val stage = design.stages[stageIndex]
                // Decouplers let go before the stage's engines light.
                for (part in stage.activatedParts) {
                    if (part in live && defs[part].module<com.rm.apogee.core.part.Decoupler>() != null) {
                        design.subtreeOf(part).forEach { live.remove(it) }
                    }
                }
                burning.addAll(stage.activatedParts.filter { it in live })
                burn(stageIndex, last = !changesAfter(stageIndex))
            }
            return stageStats
        }

        private fun emptyStage(index: Int, mass: Double) = StageStats(
            index = index, startMass = mass, endMass = mass,
            thrustVacuum = 0.0, thrustSeaLevel = 0.0,
            deltaVVacuum = 0.0, deltaVSeaLevel = 0.0,
            burnTime = 0.0, twrSeaLevel = 0.0, engineCount = 0,
        )

        /** What's wrong with the craft, worded to save you a wasted flight. */
        private fun diagnose(
            design: CraftDesign,
            defs: List<PartDef>,
            stages: List<StageStats>,
        ): List<String> {
            val problems = ArrayList<String>()

            if (defs.none { it.module<com.rm.apogee.core.part.Command>() != null }) {
                problems.add("No command pod")
            }
            // Something has to move it: an engine, or a driven wheel, sail, rotor or gas cell.
            val driven = defs.any { (it.module<com.rm.apogee.core.part.Wheel>()?.motorForce ?: 0.0) > 0.0 } ||
                defs.any { it.module<com.rm.apogee.core.part.Sail>() != null } ||
                defs.any { it.module<com.rm.apogee.core.part.Rotor>() != null || it.module<com.rm.apogee.core.part.LiftGas>() != null } ||
                // A foundation is founded where it's put and needs nothing to move it.
                defs.any { it.module<com.rm.apogee.core.part.Foundation>() != null }
            if (!driven && defs.none { it.module<Engine>() != null }) {
                problems.add("No engines, sails, rotors, gas cells or driven wheels")
            }

            // An electric engine runs off any battery aboard; charge isn't on a fuel line.
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

        /** Warnings that don't stop it launching. */
        private fun advise(stages: List<StageStats>): List<String> {
            val warnings = ArrayList<String>()
            val first = stages.firstOrNull { it.hasEngines }
            if (first != null && first.twrSeaLevel < 1.0) {
                warnings.add(
                    "Thrust-to-weight is ${com.rm.apogee.core.fixed(first.twrSeaLevel, 2)}, too low to lift off"
                )
            }
            return warnings
        }
    }
}
