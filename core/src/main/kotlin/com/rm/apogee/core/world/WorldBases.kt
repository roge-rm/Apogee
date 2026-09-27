package com.rm.apogee.core.world

/**
 * These are the world's own bases. There is one on every world that can hold one, so in free play
 * you can launch and refuel there without flying out first. Each one stands next to that world's
 * test site, on the row of pads the site lays out. Each is named after the astronomer who found or
 * mapped the real world it stands in for.
 *
 * They only exist in free play. A career has none of them, because players build their own, and a
 * career world that had them from an older save loses them.
 */
object WorldBases {
    /** A base on [bodyId] called [name], next to launch site [siteId]. */
    class Base(val bodyId: String, val name: String, val siteId: String)

    /**
     * Every world with ground to stand on and air a base can survive, except Caligo. Its air is
     * nine times what a base's parts are built for and would crush one where it stood. If it ever
     * gets one, it should be Lomonosov Base, after the man who first saw Venus's atmosphere.
     */
    val all: List<Base> = listOf(
        // Riccioli named the Moon's seas and craters.
        Base("luna", "Riccioli Base", World.LUNA_TEST_SITE),
        // Le Verrier found that Mercury's orbit didn't quite close.
        Base("celer", "Le Verrier Station", "celer-basin"),
        // Schiaparelli mapped Mars.
        Base("rubra", "Schiaparelli Station", "rubra-rift"),
        // Angeline Stickney, who Phobos's big crater is named after.
        Base("timor", "Stickney Base", "timor"),
        // Asaph Hall found both of Mars's moons.
        Base("pavor", "Hall's Rest", "pavor"),
        // Simon Marius gave Io its name.
        Base("fornax", "Marius Station", "fornax-lake"),
        // Galileo found Europa.
        Base("crusta", "Galileo Station", "crusta-lineae"),
        // Kepler was the one who called Jupiter's moons satellites.
        Base("maxima", "Kepler Base", "maxima-grooves"),
        // Barnard hunted Jupiter's moons.
        Base("cicatrix", "Barnard Base", "cicatrix-scar"),
        // Huygens found Titan, and his probe landed on it.
        Base("aurantia", "Huygens Landing", "aurantia-dunes"),
        // William Herschel found Enceladus, and a fons is a spring.
        Base("fons", "Herschel Springs", "fons-stripes"),
        // Lassell found Triton.
        Base("aversa", "Lassell Base", "aversa-cap"),
        // Tombaugh found Pluto.
        Base("ultima", "Tombaugh Station", "ultima-heart"),
        // James Christy found Charon, the ferryman's moon.
        Base("portitor", "Christy Crossing", "portitor-belt"),
    )

    /**
     * Which pad in the site's row the base sits on. It's out along the row, clear of where craft
     * get put down.
     */
    const val PAD = 6

    /**
     * What Luna's base was called before it had a proper name, for older saves that still use it.
     */
    const val OLD_LUNA_NAME = "Luna Test Base"

    fun named(name: String): Base? = all.firstOrNull { it.name == name }
}
