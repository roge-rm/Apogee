package com.rm.apogee.ui.components.builder

import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.Buoyancy
import com.rm.apogee.core.part.Command
import com.rm.apogee.core.part.DockKind
import com.rm.apogee.core.part.DockingPort
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.Exhaust
import com.rm.apogee.core.part.HeatShield
import com.rm.apogee.core.part.HydroSurface
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.Parachute
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartCategory
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.Rcs
import com.rm.apogee.core.part.Tank
import com.rm.apogee.core.part.Wheel

/** The drawer's tabs, by the job a part does. */
enum class PartTab(val label: String) {
    ALL("All"),
    PODS("Pods"),
    TANKS("Tanks"),
    ENGINES("Engines"),
    STRUCTURE("Structure"),
    WINGS("Wings & tail"),
    AIR("Rotors & gas"),
    GROUND("Wheels & legs"),
    WATER("Water"),
    UTILITY("Docking & utility"),
    BASE("Base"),
    BUILDINGS("Buildings"),

    /** Saved pieces of craft, not parts. */
    SAVED("Saved"),
}

object PartTabs {

    /**
     * The tab [def] belongs in, worked out from its modules. Not stored in the parts file, so the
     * catalogue hash a join checks doesn't change for the drawer.
     */
    fun of(def: PartDef): PartTab {
        OVERRIDES[def.id]?.let { return it }
        val docking = def.module<DockingPort>()
        // Category first: a base's core and depot belong with the base.
        if (def.category == PartCategory.BASE) return PartTab.BASE
        if (def.category == PartCategory.STRUCTURE) return PartTab.BUILDINGS
        return when {
            def.hasModule<Command>() -> PartTab.PODS
            docking != null && (docking.kind == DockKind.HITCH_BALL || docking.kind == DockKind.HITCH_COUPLING) -> PartTab.GROUND
            // Rotors, fans and gas cells together.
            def.hasModule<com.rm.apogee.core.part.Rotor>() || def.hasModule<com.rm.apogee.core.part.LiftGas>() -> PartTab.AIR
            // A ship's towing winch and tow points go with the hulls, a rover's winch with the hitches.
            def.module<com.rm.apogee.core.part.Winch>()?.anyWay == true || def.hasModule<com.rm.apogee.core.part.TowPoint>() -> PartTab.WATER
            def.hasModule<com.rm.apogee.core.part.Winch>() -> PartTab.GROUND
            def.hasModule<com.rm.apogee.core.part.Sail>() -> PartTab.WATER
            docking != null || def.hasModule<Rcs>() || def.hasModule<Parachute>() -> PartTab.UTILITY
            def.hasModule<Buoyancy>() || def.hasModule<HydroSurface>() || def.hasModule<com.rm.apogee.core.part.Ballast>() ||
                def.module<Engine>()?.exhaustKind == Exhaust.WATER -> PartTab.WATER
            def.hasModule<Wheel>() || def.hasModule<LandingLeg>() -> PartTab.GROUND
            def.hasModule<Engine>() -> PartTab.ENGINES
            def.hasModule<AeroSurface>() || def.category == PartCategory.AERO -> PartTab.WINGS
            def.hasModule<HeatShield>() -> PartTab.STRUCTURE
            def.hasModule<Tank>() -> PartTab.TANKS
            def.category == PartCategory.UTILITY -> PartTab.UTILITY
            else -> PartTab.STRUCTURE
        }
    }

    /** Parts on [tab]: by tab, then catalogue order within each. */
    fun parts(catalog: PartCatalog, tab: PartTab): List<PartDef> {
        // Not hidden parts, like a fairing's half.
        val all = ORDER.flatMap { category -> catalog.byCategory(category) }.filter { !it.hidden }
        val sorted = all.sortedBy { of(it).ordinal }
        return when (tab) {
            PartTab.ALL -> sorted
            PartTab.SAVED -> emptyList()
            else -> sorted.filter { of(it) == tab }
        }
    }

    private val ORDER = listOf(
        PartCategory.COMMAND, PartCategory.FUEL, PartCategory.PROPULSION,
        PartCategory.STRUCTURAL, PartCategory.AERO, PartCategory.UTILITY,
        PartCategory.GROUND, PartCategory.BASE, PartCategory.STRUCTURE,
    )

    /** Parts the rules can't place, mostly ones with no module to say what they are. */
    private val OVERRIDES = mapOf(
        "light-bar" to PartTab.UTILITY,
        "float-foam" to PartTab.WATER,
        "keel-lead" to PartTab.WATER,
        "knees-push" to PartTab.WATER,
        // A carrier's deck gear goes with the ships, the hook with the wheels.
        "deck-flight" to PartTab.WATER,
        "gear-arrest" to PartTab.WATER,
        "catapult-deck" to PartTab.WATER,
        "hook-tail" to PartTab.GROUND,
        "ladder-boat" to PartTab.WATER,
    )
}
