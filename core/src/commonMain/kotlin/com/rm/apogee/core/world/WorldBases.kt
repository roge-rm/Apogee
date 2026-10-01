package com.rm.apogee.core.world

/**
 * The world's own bases, one on every world that can hold one, beside its test site, named after the
 * astronomer who found or mapped the real world it stands in for. Free play only; a career world
 * from an older save loses them.
 */
object WorldBases {
    /** A base on [bodyId] called [name], next to launch site [siteId]. */
    class Base(val bodyId: String, val name: String, val siteId: String)

    /**
     * Every world with ground and air a base can survive. Not Caligo: its air is nine times what
     * the parts are built for. If it ever gets one, call it Lomonosov Base.
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

    /** Which pad in the site's row the base sits on, clear of where craft get put down. */
    const val PAD = 6

    /** Luna's base's old name, for older saves. */
    const val OLD_LUNA_NAME = "Luna Test Base"

    fun named(name: String): Base? = all.firstOrNull { it.name == name }
}
