package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.Seabed
import com.rm.apogee.core.math.Math

/**
 * Named places to find, under the sea and out on other worlds. Under the sea you find one by getting
 * close and nearly as deep; on land by getting close on or near the ground. In a career the first
 * find pays insight and gets the world first.
 */
object Wonders {
    /** A place on [bodyId] at body-fixed unit [direction]. */
    class Wonder(
        val id: String,
        val name: String,
        val bodyId: String,
        val direction: Vec3,
        val blurb: String,
        val insight: Int,
        /** A wreck or arch the world places there, or blank. See [props]. */
        val landmark: String = "",
        /** On dry ground, not under a sea. */
        val onLand: Boolean = false,
        /** How near counts as there, along the ground, in metres. */
        val reach: Double = REACH,
    )

    /** What the world places at a wonder: a craft, tipped over by [tip] radians. */
    class Prop(val design: (com.rm.apogee.core.part.PartCatalog) -> com.rm.apogee.core.craft.CraftDesign, val tip: Double)

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
    ) + OUT_THERE

    fun byId(id: String): Wonder? = all.firstOrNull { it.id == id }

    /** A target that's a place: this, then the place's id. Sent and saved in a craft's target body. */
    const val TARGET_PREFIX = "place:"

    /** The place a target body names, or null if it names a world or nothing. */
    fun targeted(targetBody: String): Wonder? =
        if (targetBody.startsWith(TARGET_PREFIX)) byId(targetBody.removePrefix(TARGET_PREFIX)) else null

    /**
     * Where [place] is relative to [attractor]'s centre at [time], inertial, into [position], and how
     * it's moving with its world's turn, into [velocity]. False if its world isn't in [system].
     */
    fun locate(place: Wonder, system: com.rm.apogee.core.orbit.SolarSystem, attractor: com.rm.apogee.core.orbit.CelestialBody, time: Double, position: Vec3, velocity: Vec3): Boolean {
        val body = system.bodies[place.bodyId] ?: return false
        val ground = body.radius + (body.terrain?.elevation(place.direction) ?: 0.0)
        body.rotationAt(time).rotate(place.direction, position).normalizeInPlace().mulInPlace(ground)
        body.surfaceVelocityAt(position, velocity)
        if (body.id != attractor.id) {
            position.addInPlace(system.positionOf(body.id, time)).subInPlace(system.positionOf(attractor.id, time))
            velocity.addInPlace(system.velocityOf(body.id, time)).subInPlace(system.velocityOf(attractor.id, time))
        }
        return true
    }

    /** Each world's, for looking through only the ones where a craft is. */
    val byBody: Map<String, List<Wonder>> = all.groupBy { it.bodyId }

    /** The ones under the sea. */
    val sea: List<Wonder> get() = all.filter { !it.onLand }

    /** The ones on dry ground. */
    val land: List<Wonder> get() = all.filter { it.onLand }

    /** Found on land within this height over the ground, in metres: landed, driving, walking or a low hop. */
    const val NEAR_GROUND = 30.0

    /** What stands at each landmark, by its name. */
    val props: Map<String, Prop> = mapOf(
        GREAT_ARCH to Prop({ c -> com.rm.apogee.core.craft.CraftDesign("Rock Arch", listOf(com.rm.apogee.core.craft.PlacedPart("rock-arch", Vec3.zero())), catalogHash = c.contentHash) }, 0.0),
        LOST_SOUNDER to Prop({ c -> com.rm.apogee.core.craft.StockCraft.sounder(c) }, Math.toRadians(84.0)),
        CANYON_WRECK to Prop({ c -> com.rm.apogee.core.craft.StockCraft.trawler(c) }, Math.toRadians(22.0)),
        PLAIN_ROCKET to Prop({ c -> com.rm.apogee.core.craft.StockCraft.starterRocket(c) }, Math.toRadians(88.0)),
        "The First Lander" to Prop({ c -> com.rm.apogee.core.craft.StockCraft.lander(c) }, Math.toRadians(35.0)),
        "The Old Probe" to Prop({ c -> com.rm.apogee.core.craft.StockCraft.moteProbe(c) }, Math.toRadians(70.0)),
        "The Old Rover" to Prop({ c -> com.rm.apogee.core.craft.StockCraft.rover(c) }, Math.toRadians(20.0)),
        "The Window" to Prop({ c -> com.rm.apogee.core.craft.CraftDesign("Rock Arch", listOf(com.rm.apogee.core.craft.PlacedPart("rock-arch", Vec3.zero())), catalogHash = c.contentHash) }, 0.0),
        "The Ice Probe" to Prop({ c -> com.rm.apogee.core.craft.StockCraft.lander(c) }, Math.toRadians(15.0)),
        "The Lost Probe" to Prop({ c -> com.rm.apogee.core.craft.StockCraft.moteProbe(c) }, Math.toRadians(80.0)),
    )

    /** Found within this far along the ground, in metres... */
    const val REACH = 150.0

    /** ...and at least this share of its depth down. */
    const val DEPTH_SHARE = 0.8

    private fun land(id: String, name: String, body: String, lat: Double, lon: Double, blurb: String, insight: Int, landmark: String = "", reach: Double = REACH) =
        Wonder(id, name, body, latLon(lat, lon), blurb, insight, landmark, onLand = true, reach = reach)

    private fun prop(id: String, name: String, blurb: String, insight: Int): Wonder {
        val spot = com.rm.apogee.core.terrain.Worlds.PROPS.getValue(id)
        return land(id, name, spot.bodyId, spot.latitude, spot.longitude, blurb, insight, landmark = name)
    }

    /** The places out on other worlds' ground. */
    private val OUT_THERE: List<Wonder> get() = listOf(
        // Luna.
        prop("first-lander", "The First Lander", "An early lander that came down hard on the mare and never flew again.", 5),
        land("skylight", "The Skylight", "luna", 7.2, 8.3, "A sheer pit into a lava tube, sixty metres across and nearly as deep.", 10, reach = 120.0),
        land("bright-crater", "The Bright Crater", "luna", -20.0, 40.0, "A young crater with a peak in its middle and bright rays across the old ground.", 10, reach = 1_500.0),
        land("cold-floor", "The Cold Floor", "luna", 88.0, 30.0, "A crater floor by the pole that sunlight never reaches, frozen hard.", 15, reach = 2_000.0),
        // Celer.
        prop("old-probe", "The Old Probe", "A probe that landed on the basin floor and ran until its batteries died.", 10),
        land("the-hollows", "The Hollows", "celer", 25.0, 160.0, "A crater floor pocked with shallow bright hollows, where the rock is boiling away.", 15, reach = 3_000.0),
        land("long-cliff", "The Long Cliff", "celer", 25.0, -55.0, "A cliff a kilometre high and hundreds long, where the world shrank as it cooled.", 20, reach = 3_000.0),
        land("jumbled-hills", "The Jumbled Hills", "celer", -30.0, -10.0, "Broken hills on the far side from the Great Basin, where its shock met itself.", 20, reach = 5_000.0),
        land("cold-trap", "The Cold Trap", "celer", 88.0, 40.0, "Ice in a polar crater's shade, on the world nearest the sun.", 25, reach = 2_000.0),
        // Caligo.
        land("maxwell-summit", "Maxwell's Summit", "caligo", 66.0, 5.0, "The top of the tallest peak, frosted with metal.", 20, reach = 800.0),
        land("lava-lake", "The Lava Lake", "caligo", 24.0, -110.0 + 0.83, "The rim of a volcano with an open lake of lava in its caldera.", 20, reach = 600.0),
        land("the-crown", "The Crown", "caligo", -30.0, 20.0, "A ring of ridges forty kilometres across round a sunken middle.", 15, reach = 3_000.0),
        land("the-pancakes", "The Pancakes", "caligo", 20.0, -100.0, "Flat-topped domes of thick lava on the plains.", 10, reach = 5_000.0),
        // Rubra.
        prop("old-rover", "The Old Rover", "A rover that drove into the Rift and stopped there.", 10),
        prop("the-window", "The Window", "An arch of rock at the foot of the Great Mount.", 15),
        land("the-steps", "The Steps", "rubra", -11.0, -52.0, "Layered mesas standing in rows at the Rift's east end.", 20, reach = 4_000.0),
        land("the-caldera", "The Caldera", "rubra", 18.0, -134.0, "The floor of the crater on top of the Great Mount, the tallest volcano anywhere.", 25, reach = 1_500.0),
        land("the-spiral", "The Spiral", "rubra", 86.0, 60.0, "Troughs winding through the northern ice cap, its layers bare in their walls.", 25, reach = 4_000.0),
        // Timor and Pavor.
        land("stickney-floor", "Stickney Floor", "timor", 0.0, 50.0, "The bottom of the crater that nearly broke the moon apart.", 10, reach = 200.0),
        land("big-rock", "The Big Rock", "timor", 8.0, 35.0, "A mound of rock taller than a house, on a moon you could walk round.", 10, reach = 60.0),
        land("far-grooves", "The Far Grooves", "timor", 0.0, -150.0, "Grooves and chains of pits running across the far side.", 15, reach = 300.0),
        land("lone-boulder", "The Lone Boulder", "pavor", 10.0, 40.0, "A great boulder sitting alone on the smooth ground.", 10, reach = 50.0),
        land("the-saddle", "The Saddle", "pavor", -80.0, 0.0, "A broad dip at the south pole.", 15, reach = 300.0),
        land("bright-streaks", "Bright Streaks", "pavor", 30.0, 120.0, "A small crater with bright rubble slid down its walls.", 15, reach = 150.0),
        // Fornax.
        land("the-fumaroles", "The Fumaroles", "fornax", -11.4, 58.6, "Cones of sulfur fuming on red ground near Marius.", 15, reach = 300.0),
        land("fire-lake-shore", "The Fire Lake Shore", "fornax", -12.0, 51.9, "The edge of an open lake of lava, glowing.", 20, reach = 400.0),
        land("red-ring", "The Red Ring", "fornax", 25.0, -136.3, "Red sulfur round a lava lake, laid down by its plumes.", 15, reach = 2_000.0),
        land("fire-peak", "Fire Peak", "fornax", -20.0, 70.0, "The tallest mountain on Fornax, nine kilometres up.", 30, reach = 1_000.0),
        // Crusta.
        prop("ice-probe", "The Ice Probe", "A lander that came down near the Crossing and froze in place.", 15),
        land("the-spires", "The Spires", "crusta", -2.0, 10.0, "A field of ice blades taller than a person.", 15, reach = 2_000.0),
        land("the-dome", "The Dome", "crusta", -15.0, 25.0, "A dome where warm ice pushed up, stained red on top.", 15, reach = 2_500.0),
        land("broken-field", "The Broken Field", "crusta", 12.0, -20.0, "Blocks of the crust that broke, drifted and froze again.", 20, reach = 3_000.0),
        // Maxima.
        land("groove-bottom", "Groove Bottom", "maxima", 9.503, 35.445, "The floor of one of the long grooves.", 10, reach = 300.0),
        land("rayed-crater", "The Rayed Crater", "maxima", 20.0, 50.0, "A young crater with a pit in its middle and bright rays.", 20, reach = 3_000.0),
        land("the-ghost", "The Ghost", "maxima", -10.0, 10.0, "A flat bright disc where a crater was, its rim long gone.", 15, reach = 5_000.0),
        land("the-furrows", "The Furrows", "maxima", 40.0, -30.0, "Arcs of troughs in the dark ground round an old impact.", 15, reach = 8_000.0),
        // Cicatrix.
        land("the-knobs", "The Knobs", "cicatrix", 14.425, -60.669, "Frosted buttes, all that's left of old crater rims.", 15, reach = 400.0),
        land("the-chain", "The Chain", "cicatrix", 2.5, -12.5, "A line of craters where a broken comet struck.", 20, reach = 4_000.0),
        land("last-ring", "The Last Ring", "cicatrix", 15.0, -21.0, "The outermost ring round the Great Scar.", 25, reach = 4_000.0),
        // Aurantia.
        land("landing-stones", "The Landing Stones", "aurantia", 4.595, -39.205, "A bright flat between the dunes, strewn with pebbles of ice.", 10, reach = 250.0),
        land("ring-lake", "Ring Lake", "aurantia", 50.0, 60.0, "A dry lake bed with a raised rim.", 20, reach = 4_000.0),
        land("the-labyrinth", "The Labyrinth", "aurantia", -50.0, 100.0, "A plateau cut into a maze of canyons.", 25, reach = 8_000.0),
        land("great-dune", "The Great Dune", "aurantia", 2.0, -30.0, "A dune three times taller than any other.", 15, reach = 1_000.0),
        // Fons.
        land("the-fountains", "The Fountains", "fons", -83.48, 23.3, "Geysers along a Stripe, throwing ice into space.", 20, reach = 300.0),
        land("ice-house", "Ice House", "fons", -75.0, 40.0, "A block of ice standing alone.", 15, reach = 60.0),
        land("stripe-floor", "The Stripe Floor", "fons", -79.47, -48.12, "The bottom of the outermost Stripe.", 25, reach = 200.0),
        land("old-north", "Old North", "fons", 60.0, 0.0, "The old cratered north, far from the Stripes.", 20, reach = 3_000.0),
        // Aversa.
        land("dark-plume", "The Dark Plume", "aversa", -50.8, 30.9, "A geyser throwing dark dust into the sky.", 20, reach = 250.0),
        land("walled-plains", "The Walled Plains", "aversa", 10.0, 60.0, "Plains flooded by ice lava, walled in by cliffs.", 25, reach = 8_000.0),
        land("melon-skin", "Melon Skin", "aversa", 10.0, -150.0, "Ground packed with round pits like the skin of a melon.", 20, reach = 6_000.0),
        // Ultima.
        prop("lost-probe", "The Lost Probe", "A probe that landed on the Heart and was never heard from.", 15),
        land("cell-edge", "Cell Edge", "ultima", 14.756, 176.842, "A ridge between two of the Heart's churning cells.", 10, reach = 300.0),
        land("floating-hills", "The Floating Hills", "ultima", 25.0, 150.0, "Mountains of water ice floating on the nitrogen.", 25, reach = 8_000.0),
        land("the-blades", "The Blades", "ultima", 10.0, 90.0, "Knife-edge ridges of ice hundreds of metres tall.", 30, reach = 6_000.0),
        land("hollow-mountain", "The Hollow Mountain", "ultima", -30.0, 170.0, "A mountain with a pit for a summit, maybe an ice volcano.", 40, reach = 2_000.0),
        // Portitor.
        land("canyon-floor", "Canyon Floor", "portitor", 0.806, 2.709, "The bottom of the Belt, a kilometre and a half down.", 15, reach = 300.0),
        land("moat-mountain", "The Moat Mountain", "portitor", -40.0, 30.0, "A mountain standing in a trough of its own.", 30, reach = 3_000.0),
        land("red-cap", "The Red Cap", "portitor", 88.0, 0.0, "The north pole, stained red.", 20, reach = 6_000.0),
        land("white-crater", "The White Crater", "portitor", 20.0, -60.0, "A young crater with bright rays.", 20, reach = 2_000.0),
    )

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
