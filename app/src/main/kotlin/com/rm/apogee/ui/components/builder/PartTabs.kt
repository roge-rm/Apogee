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

/** The drawer's tabs: parts by the job they do, whatever kind of craft they go on. */
enum class PartTab(val label: String) {
    ALL("All"),
    PODS("Pods"),
    TANKS("Tanks"),
    ENGINES("Engines"),
    STRUCTURE("Structure"),
    WINGS("Wings & tail"),
    GROUND("Wheels & legs"),
    WATER("Water"),
    UTILITY("Docking & utility"),
    BASE("Base"),
    BUILDINGS("Buildings"),
}

object PartTabs {

    /**
     * The tab [def] belongs in, worked out from what it does rather than
     * written into the parts file: a part added later sorts itself, and the
     * catalogue's content hash - what a join between players checks - does
     * not change for the sake of the drawer.
     */
    fun of(def: PartDef): PartTab {
        OVERRIDES[def.id]?.let { return it }
        val docking = def.module<DockingPort>()
        // By what they are before what they do: a base's core is a command
        // part and its depot a tank, but they belong with the base.
        if (def.category == PartCategory.BASE) return PartTab.BASE
        if (def.category == PartCategory.STRUCTURE) return PartTab.BUILDINGS
        return when {
            def.hasModule<Command>() -> PartTab.PODS
            docking != null && (docking.kind == DockKind.HITCH_BALL || docking.kind == DockKind.HITCH_COUPLING) -> PartTab.GROUND
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

    /** Parts on [tab], in drawer order: tabs in order, then the catalogue's own order within each. */
    fun parts(catalog: PartCatalog, tab: PartTab): List<PartDef> {
        // Never a part that only comes off another: a fairing's half.
        val all = ORDER.flatMap { category -> catalog.byCategory(category) }.filter { !it.hidden }
        val sorted = all.sortedBy { of(it).ordinal }
        return if (tab == PartTab.ALL) sorted else sorted.filter { of(it) == tab }
    }

    private val ORDER = listOf(
        PartCategory.COMMAND, PartCategory.FUEL, PartCategory.PROPULSION,
        PartCategory.STRUCTURAL, PartCategory.AERO, PartCategory.UTILITY,
        PartCategory.GROUND, PartCategory.BASE, PartCategory.STRUCTURE,
    )

    /** The odd ones the rules do not place. */
    private val OVERRIDES = mapOf(
        // Lamps, no module to say so.
        "light-bar" to PartTab.UTILITY,
        // A submarine's float and keel: foam and lead, no module to say so.
        "float-foam" to PartTab.WATER,
        "keel-lead" to PartTab.WATER,
    )
}
