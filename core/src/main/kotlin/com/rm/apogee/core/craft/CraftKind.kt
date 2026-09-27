package com.rm.apogee.core.craft

import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.Ballast
import com.rm.apogee.core.part.Buoyancy
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.Exhaust
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartCategory
import com.rm.apogee.core.part.Sail
import com.rm.apogee.core.part.Wheel

/**
 * What sort of craft a design is, for sorting the saved craft: worked out from its parts, so a
 * design of your own sorts itself without being told.
 */
enum class CraftKind(val label: String) {
    ROCKET("Rockets"),
    PLANE("Planes"),
    ROVER("Rovers"),
    BOAT("Boats"),
    SUB("Subs"),
    BASE("Bases");

    companion object {
        /** Lifting surface, in m², it takes lying down to be a plane and not a rover with a fin. */
        const val WINGS = 3.0

        /**
         * [design]'s kind, by what it's built from. A base part makes it a base, or something to
         * carry one, which is where it's wanted. Trim tanks make a submarine, and a hull, a sail
         * or a water screw a boat. Lying down, wings make a plane and wheels a rover. Anything else,
         * standing up, is a rocket: landers, probes and tugs too.
         */
        fun of(design: CraftDesign, catalog: PartCatalog): CraftKind {
            val defs = design.parts.mapNotNull { catalog[it.partId] }
            if (defs.any { it.category == PartCategory.BASE }) return BASE
            if (defs.any { it.hasModule<Ballast>() }) return SUB
            if (defs.any { it.hasModule<Buoyancy>() || it.hasModule<Sail>() || it.module<Engine>()?.exhaustKind == Exhaust.WATER }) return BOAT
            val lying = design.orientation == CraftOrientation.HORIZONTAL
            val wings = defs.sumOf { d -> d.module<AeroSurface>()?.takeIf { it.liftCoefficient > 0.0 }?.area ?: 0.0 }
            if (lying && wings >= WINGS) return PLANE
            if (defs.any { it.hasModule<Wheel>() } && lying) return ROVER
            return ROCKET
        }
    }
}
