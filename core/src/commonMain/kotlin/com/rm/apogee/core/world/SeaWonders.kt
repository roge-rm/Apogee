package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.Seabed
import com.rm.apogee.core.math.Math

/**
 * Named places under the sea. You find one by getting close and nearly as deep. In a career the
 * first find pays insight and gets the world first. Most are off the Cape, two under Aurantia's sea.
 */
object SeaWonders {
    /** A place under [bodyId]'s sea at body-fixed unit [direction]. */
    class Wonder(
        val id: String,
        val name: String,
        val bodyId: String,
        val direction: Vec3,
        val blurb: String,
        val insight: Int,
        /** A wreck or arch the world places there, or blank. */
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
            "An arch of rock on the edge of the shelf, wide enough for a submarine.", 10, landmark = GREAT_ARCH,
        ),
        Wonder(
            "cape-canyon", "Cape Canyon", "terra", cape(700.0, 7_300.0),
            "A canyon running out from the harbour mouth, over a kilometre deep at its end.", 10,
        ),
        Wonder(
            "farrow-seamount", "Farrow Seamount", "terra", cape(Seabed.FARROW_EAST, Seabed.FARROW_NORTH),
            "A drowned volcano with a flat top, sixty metres down.", 10,
        ),
        Wonder(
            "canyon-wreck", "The Canyon Wreck", "terra", cape(-1_500.0, 10_200.0),
            "A trawler that sank in a storm and slid down into the canyon.", 15, landmark = CANYON_WRECK,
        ),
        Wonder(
            "chimneys", "The Chimneys", "terra", cape(Seabed.CHIMNEYS_EAST, Seabed.CHIMNEYS_NORTH),
            "Hot vents on Farrow Seamount, with chimneys of mineral round them.", 20,
        ),
        Wonder(
            "nodule-plain", "The Nodule Plain", "terra", cape(-16_440.0, 5_990.0),
            "A flat plain two and a half kilometres down, covered in lumps of metal, with an old rocket on it.", 25, landmark = PLAIN_ROCKET,
        ),
        Wonder(
            "terra-deep", "The Terra Deep", "terra", cape(-15_556.0, 15_556.0),
            "The bottom of the trench off the Cape, seven kilometres down and the deepest place on Terra.", 40,
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

    /** Found within this far along the ground, in metres... */
    const val REACH = 150.0

    /** ...and at least this share of its depth down. */
    const val DEPTH_SHARE = 0.8

    const val LOST_SOUNDER = "The Lost Sounder"
    const val GREAT_ARCH = "The Great Arch"
    const val CANYON_WRECK = "The Canyon Wreck"
    const val PLAIN_ROCKET = "The Plain Rocket"

    // Aurantia's two: the deepest point of its sea, and a rise with spires.
    const val KRAKEN_LAT = 77.0
    const val KRAKEN_LON = 16.0
    const val LIGEIA_LAT = 82.0
    const val LIGEIA_LON = 30.0
}
