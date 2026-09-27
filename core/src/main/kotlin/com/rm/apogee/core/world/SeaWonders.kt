package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.Seabed

/**
 * The sea's named places: somewhere to go under the water, each found by
 * reaching it - close by, and near as deep - and paying the career insight
 * the first time, as reaching a world does; the first player to find each
 * is the world's first there. Off the Cape, most of them, in reach of a
 * slow submarine; two under Aurantia's sea.
 */
object SeaWonders {
    /** A place under [bodyId]'s sea at body-fixed unit [direction]: what it is called, what it is, and what finding it pays. */
    class Wonder(
        val id: String,
        val name: String,
        val bodyId: String,
        val direction: Vec3,
        val blurb: String,
        val insight: Int,
        /** Something standing there to be found: a wreck or an arch, raised by the world; blank for none. */
        val landmark: String = "",
    )

    private fun cape(east: Double, north: Double) = SolarSystem.capeDirection(east, north)

    private fun latLon(lat: Double, lon: Double) = SolarSystem.surfaceDirection(Math.toRadians(lat), Math.toRadians(lon))

    val all: List<Wonder> = listOf(
        Wonder(
            "lost-sounder", "The Lost Sounder", "terra", cape(300.0, 5_600.0),
            "A sounding rocket that came down short, lying in the shallows off the Cape.", 5, landmark = LOST_SOUNDER,
        ),
        Wonder(
            "great-arch", "The Great Arch", "terra", cape(-2_600.0, 7_100.0),
            "A span of rock on the shelf's edge, wide enough to drive a submarine through.", 10, landmark = GREAT_ARCH,
        ),
        Wonder(
            "cape-canyon", "Cape Canyon", "terra", cape(700.0, 7_300.0),
            "Cut down through the shelf from the harbour's mouth, deeper the further out it runs - a kilometre and more at its end.", 10,
        ),
        Wonder(
            "farrow-seamount", "Farrow Seamount", "terra", cape(Seabed.FARROW_EAST, Seabed.FARROW_NORTH),
            "A drowned volcano, its top planed flat by waves long ago, sixty metres under the surface.", 10,
        ),
        Wonder(
            "canyon-wreck", "The Canyon Wreck", "terra", cape(-1_500.0, 10_200.0),
            "A trawler that went down in a storm and slid into the canyon.", 15, landmark = CANYON_WRECK,
        ),
        Wonder(
            "chimneys", "The Chimneys", "terra", cape(Seabed.CHIMNEYS_EAST, Seabed.CHIMNEYS_NORTH),
            "Vents on Farrow's flank, stacks of mineral round water hot enough to scald, black with what it carries.", 20,
        ),
        Wonder(
            "nodule-plain", "The Nodule Plain", "terra", cape(-16_440.0, 5_990.0),
            "A terrace of ooze two and a half kilometres down, strewn with nodules of metal - and an old rocket.", 25, landmark = PLAIN_ROCKET,
        ),
        Wonder(
            "terra-deep", "The Terra Deep", "terra", cape(-15_556.0, 15_556.0),
            "The floor of the trench off the Cape: seven kilometres down, the deepest place on Terra.", 40,
        ),
        Wonder(
            "kraken-deep", "Kraken Deep", "aurantia", latLon(KRAKEN_LAT, KRAKEN_LON),
            "The deepest of Aurantia's methane sea.", 30,
        ),
        Wonder(
            "ligeia-spires", "Ligeia Spires", "aurantia", latLon(LIGEIA_LAT, LIGEIA_LON),
            "Spires of ice under the methane, where cold seeps rise.", 20,
        ),
    )

    fun byId(id: String): Wonder? = all.firstOrNull { it.id == id }

    /** Found: within this far of it, m, along the ground... */
    const val REACH = 150.0

    /** ...and at least this share of its depth down. */
    const val DEPTH_SHARE = 0.8

    const val LOST_SOUNDER = "The Lost Sounder"
    const val GREAT_ARCH = "The Great Arch"
    const val CANYON_WRECK = "The Canyon Wreck"
    const val PLAIN_ROCKET = "The Plain Rocket"

    // Aurantia's two, found by looking: its sea's deepest, and a rise with spires.
    const val KRAKEN_LAT = 77.0
    const val KRAKEN_LON = 16.0
    const val LIGEIA_LAT = 82.0
    const val LIGEIA_LON = 30.0
}
