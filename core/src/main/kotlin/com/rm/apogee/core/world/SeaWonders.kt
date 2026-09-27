package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.Seabed

/**
 * The named places under the sea. They give you somewhere to go under the water. You find one by
 * getting close to it and nearly as deep, and in a career the first find pays insight the same way
 * reaching a world does. The first player to find each one gets the world first. Most of them are
 * off the Cape, within reach of a slow submarine, and two are under Aurantia's sea.
 */
object SeaWonders {
    /** A place under [bodyId]'s sea at body-fixed unit [direction], with its name, what it is, and what finding it pays. */
    class Wonder(
        val id: String,
        val name: String,
        val bodyId: String,
        val direction: Vec3,
        val blurb: String,
        val insight: Int,
        /**
         * Something standing there to find, like a wreck or an arch, which the world puts in place.
         * Blank for none.
         */
        val landmark: String = "",
    )

    private fun cape(east: Double, north: Double) = SolarSystem.capeDirection(east, north)

    private fun latLon(lat: Double, lon: Double) = SolarSystem.surfaceDirection(Math.toRadians(lat), Math.toRadians(lon))

    val all: List<Wonder> = listOf(
        Wonder(
            "lost-sounder", "The Lost Sounder", "terra", cape(300.0, 5_600.0),
            "A sounding rocket that came down short and now lies in the shallows off the Cape.", 5, landmark = LOST_SOUNDER,
        ),
        Wonder(
            "great-arch", "The Great Arch", "terra", cape(-2_600.0, 7_100.0),
            "A span of rock on the edge of the shelf, wide enough to drive a submarine through.", 10, landmark = GREAT_ARCH,
        ),
        Wonder(
            "cape-canyon", "Cape Canyon", "terra", cape(700.0, 7_300.0),
            "A canyon cut down through the shelf from the harbour mouth. It gets deeper the further out it runs, over a kilometre deep at the end.", 10,
        ),
        Wonder(
            "farrow-seamount", "Farrow Seamount", "terra", cape(Seabed.FARROW_EAST, Seabed.FARROW_NORTH),
            "A drowned volcano sixty metres under the surface. Waves planed its top flat a long time ago.", 10,
        ),
        Wonder(
            "canyon-wreck", "The Canyon Wreck", "terra", cape(-1_500.0, 10_200.0),
            "A trawler that sank in a storm and slid down into the canyon.", 15, landmark = CANYON_WRECK,
        ),
        Wonder(
            "chimneys", "The Chimneys", "terra", cape(Seabed.CHIMNEYS_EAST, Seabed.CHIMNEYS_NORTH),
            "Hot vents on the side of Farrow Seamount. Chimneys of mineral build up around water hot enough to scald you, black with everything it carries.", 20,
        ),
        Wonder(
            "nodule-plain", "The Nodule Plain", "terra", cape(-16_440.0, 5_990.0),
            "A flat terrace of ooze two and a half kilometres down, covered in lumps of metal. There's an old rocket down there too.", 25, landmark = PLAIN_ROCKET,
        ),
        Wonder(
            "terra-deep", "The Terra Deep", "terra", cape(-15_556.0, 15_556.0),
            "The bottom of the trench off the Cape. It's seven kilometres down, the deepest place on Terra.", 40,
        ),
        Wonder(
            "kraken-deep", "Kraken Deep", "aurantia", latLon(KRAKEN_LAT, KRAKEN_LON),
            "The deepest point of Aurantia's methane sea.", 30,
        ),
        Wonder(
            "ligeia-spires", "Ligeia Spires", "aurantia", latLon(LIGEIA_LAT, LIGEIA_LON),
            "Spires of ice under the methane, where cold seeps rise from the floor.", 20,
        ),
    )

    fun byId(id: String): Wonder? = all.firstOrNull { it.id == id }

    /** To count as found you need to be within this far of it along the ground, in metres... */
    const val REACH = 150.0

    /** ...and at least this share of its depth down. */
    const val DEPTH_SHARE = 0.8

    const val LOST_SOUNDER = "The Lost Sounder"
    const val GREAT_ARCH = "The Great Arch"
    const val CANYON_WRECK = "The Canyon Wreck"
    const val PLAIN_ROCKET = "The Plain Rocket"

    // Aurantia's two, picked by looking around: the deepest point of its sea, and a rise with
    // spires on it.
    const val KRAKEN_LAT = 77.0
    const val KRAKEN_LON = 16.0
    const val LIGEIA_LAT = 82.0
    const val LIGEIA_LON = 30.0
}
