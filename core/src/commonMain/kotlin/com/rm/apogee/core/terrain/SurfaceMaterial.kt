package com.rm.apogee.core.terrain

/**
 * What the ground is made of and what that does to a craft. The renderer colours a facet by it and
 * the collider grips, drags and sinks by it, so ground that looks like ice is ice.
 *
 * @param friction Coulomb coefficient, dry grip. Grass and packed dirt are 0.6.
 * @param rollingDrag multiplies a wheel's own rolling resistance.
 * @param softness metres of sinking per unit of contact pressure ratio. Zero for anything firm. See
 *     GroundContact.
 * @param bog how much sunk-in wheels resist rolling, per metre of sinking.
 */
enum class SurfaceMaterial(
    val friction: Double,
    val rollingDrag: Double,
    val softness: Double,
    val bog: Double,
) {
    ROCK(friction = 0.75, rollingDrag = 0.8, softness = 0.0, bog = 0.0),
    SCREE(friction = 0.5, rollingDrag = 2.0, softness = 0.02, bog = 1.0),
    GRASS(friction = 0.6, rollingDrag = 1.0, softness = 0.0, bog = 0.0),
    /** Packed earth, firm. The launch complex stands on it. */
    DIRT(friction = 0.6, rollingDrag = 1.2, softness = 0.0, bog = 0.0),
    SAND(friction = 0.45, rollingDrag = 3.0, softness = 0.06, bog = 2.0),
    // On soft ground drag must stay under grip for a light load, or nothing moves. Too much bog and
    // a rover in mud barely crawls. Sinking scales with load, so heavy craft still bog down.
    MUD(friction = 0.35, rollingDrag = 2.5, softness = 0.12, bog = 1.0),
    SNOW(friction = 0.3, rollingDrag = 2.0, softness = 0.08, bog = 1.0),
    ICE(friction = 0.08, rollingDrag = 0.6, softness = 0.0, bog = 0.0),
    REGOLITH(friction = 0.55, rollingDrag = 2.0, softness = 0.05, bog = 1.5),
    BASALT(friction = 0.7, rollingDrag = 0.9, softness = 0.0, bog = 0.0),

    /** Badlands earth: red, firm when dry, a little soft. */
    CLAY(friction = 0.55, rollingDrag = 1.4, softness = 0.02, bog = 1.0),

    /** Forest floor: loam and leaf litter, grippy and a little soft. */
    FOREST(friction = 0.6, rollingDrag = 1.5, softness = 0.02, bog = 1.0),

    /** The launch pad: poured concrete, firm and grippy. */
    CONCRETE(friction = 0.8, rollingDrag = 0.6, softness = 0.0, bog = 0.0),

    /** The runway: smooth asphalt, the easiest rolling there is. */
    ASPHALT(friction = 0.8, rollingDrag = 0.5, softness = 0.0, bog = 0.0),

    /** Rubra's ground: fine rust-red dust over rock, soft underfoot and under wheel. */
    RED_DUST(friction = 0.5, rollingDrag = 2.2, softness = 0.04, bog = 1.2),

    /** Fornax's plains: sulfur frost and crust, firm. */
    SULFUR(friction = 0.6, rollingDrag = 1.0, softness = 0.0, bog = 0.0),

    /** Molten rock, like Fornax's lakes and Caligo's fresh flows. Nothing that touches it survives. */
    LAVA(friction = 0.3, rollingDrag = 3.0, softness = 0.0, bog = 0.0),

    /** Aurantia's dunes: grains of frozen hydrocarbon, dark and soft. */
    ORGANIC_SAND(friction = 0.45, rollingDrag = 3.0, softness = 0.06, bog = 2.0),

    /** Frozen nitrogen, like Ultima's plain and Aversa's cap. Slicker than water ice, a little soft. */
    NITROGEN_ICE(friction = 0.06, rollingDrag = 0.7, softness = 0.01, bog = 0.5),

    /** Dark red organic dust, on Ultima's and Portitor's reddened ground. */
    THOLIN(friction = 0.55, rollingDrag = 1.8, softness = 0.03, bog = 1.2),

    /** Caligo's folded highland rock: rough and grippy. */
    TESSERA(friction = 0.8, rollingDrag = 1.3, softness = 0.0, bog = 0.0),

    /** The deep sea's floor: fine pale ooze settled over ages, soft. */
    OOZE(friction = 0.35, rollingDrag = 2.5, softness = 0.1, bog = 1.5),

    /** Ooze scattered with dark lumps of metal, fist-sized and rich in ore. */
    NODULES(friction = 0.5, rollingDrag = 2.0, softness = 0.06, bog = 1.2),

    /** Around a vent: rock crusted rust and black with what the hot water leaves behind. */
    VENT_CRUST(friction = 0.7, rollingDrag = 1.2, softness = 0.0, bog = 0.0);

    companion object {
        private val all = entries.toTypedArray()
        fun of(ordinal: Int): SurfaceMaterial = all[ordinal]
    }
}
