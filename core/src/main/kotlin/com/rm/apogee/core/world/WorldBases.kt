package com.rm.apogee.core.world

/**
 * The world's own bases: one on every world that can hold one, somewhere to
 * launch from and refuel at in free play without flying there first. Each
 * stands beside that world's test site, on the row of pads the site lays
 * out, and is named for the astronomer who found or mapped the world it
 * stands in for.
 *
 * Free play only. A career has none of them - its players build their own -
 * and a career world that has them from before loses them.
 */
object WorldBases {
    /** A base on [bodyId], called [name], beside launch site [siteId]. */
    class Base(val bodyId: String, val name: String, val siteId: String)

    /**
     * Every world with ground to stand on and air a base can stand, except
     * Caligo: its air, nine times what a base's parts are built for, would
     * crush one where it stood. Were it to have one: Lomonosov Base, for the
     * man who saw Venus's air.
     */
    val all: List<Base> = listOf(
        // He named the Moon's seas and craters.
        Base("luna", "Riccioli Base", World.LUNA_TEST_SITE),
        // He found Mercury's orbit would not close.
        Base("celer", "Le Verrier Station", "celer-basin"),
        // He mapped Mars.
        Base("rubra", "Schiaparelli Station", "rubra-rift"),
        // Angeline Stickney, for whom Phobos's great crater is named.
        Base("timor", "Stickney Base", "timor"),
        // Asaph Hall found both of Mars's moons.
        Base("pavor", "Hall's Rest", "pavor"),
        // Simon Marius gave Io its name.
        Base("fornax", "Marius Station", "fornax-lake"),
        // He found Europa.
        Base("crusta", "Galileo Station", "crusta-lineae"),
        // He called Jupiter's moons its satellites.
        Base("maxima", "Kepler Base", "maxima-grooves"),
        // The hunter of Jupiter's moons.
        Base("cicatrix", "Barnard Base", "cicatrix-scar"),
        // He found Titan, and his probe landed on it.
        Base("aurantia", "Huygens Landing", "aurantia-dunes"),
        // William Herschel found Enceladus; a fons is a spring.
        Base("fons", "Herschel Springs", "fons-stripes"),
        // He found Triton.
        Base("aversa", "Lassell Base", "aversa-cap"),
        // He found Pluto.
        Base("ultima", "Tombaugh Station", "ultima-heart"),
        // James Christy found Charon, the ferryman's moon.
        Base("portitor", "Christy Crossing", "portitor-belt"),
    )

    /** The pad in each site's row the base stands on: out along it, clear of where craft are put down. */
    const val PAD = 6

    /** What Luna's was called before it had a name of its own, for saves that still call it that. */
    const val OLD_LUNA_NAME = "Luna Test Base"

    fun named(name: String): Base? = all.firstOrNull { it.name == name }
}
