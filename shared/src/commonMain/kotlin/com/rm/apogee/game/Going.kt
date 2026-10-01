package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftKind
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartCategory
import com.rm.apogee.core.world.World

/**
 * How a craft gets about, for the words the game uses: you fly a plane, drive a rover, sail a boat,
 * dive a sub, walk or swim in a suit, and visit a base.
 */
enum class Going(val verb: String, val doing: String, val keep: String) {
    FLY("Fly", "Flying", "Keep flying"),
    DRIVE("Drive", "Driving", "Keep driving"),
    SAIL("Sail", "Sailing", "Keep sailing"),
    DIVE("Dive", "Diving", "Keep diving"),
    WALK("Walk", "Walking", "Keep walking"),
    SWIM("Swim", "Swimming", "Keep swimming"),
    VISIT("Visit", "Visiting", "Stay");

    companion object {
        /**
         * How [design] gets about. A founded base ([anchored]) is visited, a suit walks or swims
         * ([swimming]), and the rest go by [CraftKind]. A base not founded yet goes by what carries
         * it, so a hauler is driven, a lander flown and a sea platform sailed.
         */
        fun of(design: CraftDesign, catalog: PartCatalog, anchored: Boolean, swimming: Boolean = false): Going = when {
            anchored -> VISIT
            design.parts.singleOrNull()?.partId == World.SUIT_PART -> if (swimming) SWIM else WALK
            else -> when (CraftKind.of(carrier(design, catalog), catalog)) {
                CraftKind.ROVER -> DRIVE
                CraftKind.BOAT -> SAIL
                CraftKind.SUB -> DIVE
                else -> FLY
            }
        }

        /** [design] without its base parts: what carries them. */
        private fun carrier(design: CraftDesign, catalog: PartCatalog): CraftDesign =
            design.copy(parts = design.parts.filter { catalog[it.partId]?.category != PartCategory.BASE })

        /** Up in the air over a world: "Flying", or "Floating" for something held up by gas. */
        fun aloft(design: CraftDesign, catalog: PartCatalog): String =
            if (CraftKind.of(design, catalog) == CraftKind.AIRSHIP) "Floating" else "Flying"
    }
}
