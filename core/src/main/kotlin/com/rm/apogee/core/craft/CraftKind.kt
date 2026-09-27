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
    ROTORCRAFT("Rotorcraft"),
    AIRSHIP("Airships"),
    ROVER("Rovers"),
    BOAT("Boats"),
    SUB("Subs"),
    BASE("Bases");

    companion object {
        /** Lifting surface, in m², it takes lying down to be a plane and not a rover with a fin. */
        const val WINGS = 3.0

        /**
         * [design]'s kind, by what it's built from. A base part makes it a base, or something to
         * carry one, which is where it's wanted. Gas cells make an airship (a balloon or a sky
         * platform too), and lifting rotors a rotorcraft. Trim tanks make a submarine, and a hull or a
         * water screw a boat, and so does a sail with no wheels under it. Lying down, wings make a plane and wheels a rover. Anything else,
         * standing up, is a rocket: landers, probes and tugs too.
         */
        fun of(design: CraftDesign, catalog: PartCatalog): CraftKind {
            val defs = design.parts.mapNotNull { catalog[it.partId] }
            if (defs.any { it.category == PartCategory.BASE }) return BASE
            // Floating on gas, or held up by rotors, before anything else it might also have.
            if (defs.any { it.hasModule<com.rm.apogee.core.part.LiftGas>() }) return AIRSHIP
            if (defs.any { it.module<com.rm.apogee.core.part.Rotor>()?.tail == false }) return ROTORCRAFT
            if (defs.any { it.hasModule<Ballast>() }) return SUB
            val wheels = defs.any { it.hasModule<Wheel>() }
            if (defs.any { it.hasModule<Buoyancy>() || it.module<Engine>()?.exhaustKind == Exhaust.WATER }) return BOAT
            // A sail on wheels is a land yacht, and that's a rover.
            if (!wheels && defs.any { it.hasModule<Sail>() }) return BOAT
            val lying = design.orientation == CraftOrientation.HORIZONTAL
            val wings = defs.sumOf { d -> d.module<AeroSurface>()?.takeIf { it.liftCoefficient > 0.0 }?.area ?: 0.0 }
            if (lying && wings >= WINGS) return PLANE
            if (wheels && lying) return ROVER
            return ROCKET
        }
    }
}
