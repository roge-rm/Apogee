package com.rm.apogee.core.terrain

/**
 * What the ground is made of, and what that does to a craft on it.
 *
 * One classification drives both the look and the physics: the renderer
 * colours a facet by its material and the collider grips, drags and sinks by
 * it, so ground that looks like ice is ice.
 *
 * @param friction Coulomb coefficient, dry grip. Grass and packed dirt sit at
 *   0.6, which is what all ground was before materials existed.
 * @param rollingDrag multiplies a wheel's own rolling resistance.
 * @param softness metres of sinkage per unit of contact pressure ratio; zero
 *   for anything firm. See GroundContact.
 * @param bog how much sunk-in wheels resist rolling, per metre of sinkage.
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
    /** Packed earth - firm. The launch complex stands on it. */
    DIRT(friction = 0.6, rollingDrag = 1.2, softness = 0.0, bog = 0.0),
    SAND(friction = 0.45, rollingDrag = 3.0, softness = 0.06, bog = 2.0),
    // Drag stays under grip for a light load on the soft ones, or nothing gets
    // anywhere. Bog is per metre of sinkage: at four, twelve centimetres into
    // mud cost half a rover's weight on its own, grip and drag cancelled
    // exactly, and it crept at a fifth of a metre a second. Sinkage is what
    // separates light from heavy - three times the load sinks three times as
    // deep - so a heavy craft still bogs.
    MUD(friction = 0.35, rollingDrag = 2.5, softness = 0.12, bog = 1.0),
    SNOW(friction = 0.3, rollingDrag = 2.0, softness = 0.08, bog = 1.0),
    ICE(friction = 0.08, rollingDrag = 0.6, softness = 0.0, bog = 0.0),
    REGOLITH(friction = 0.55, rollingDrag = 2.0, softness = 0.05, bog = 1.5),
    BASALT(friction = 0.7, rollingDrag = 0.9, softness = 0.0, bog = 0.0),

    /** Badlands earth: red, firm when dry, a little soft. */
    CLAY(friction = 0.55, rollingDrag = 1.4, softness = 0.02, bog = 1.0),

    /** Forest floor: loam and litter, grippy and a little soft. */
    FOREST(friction = 0.6, rollingDrag = 1.5, softness = 0.02, bog = 1.0);

    companion object {
        private val all = entries.toTypedArray()
        fun of(ordinal: Int): SurfaceMaterial = all[ordinal]
    }
}
