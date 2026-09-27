package com.rm.apogee.core.career

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.part.PartCatalog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which kind of flying a feat or a node belongs to. */
enum class Branch(val title: String) {
    ROCKETRY("Rocketry"),
    TECHNIQUES("Techniques"),
    AVIATION("Aviation"),
    GROUND("Ground"),
    SEA("Sea"),
    SKIES("Skies"),
    DEEP("Deep"),
    SYSTEMS("Systems"),
    CREW("Crew"),
}

/** How well a feat was done, from just doing it to doing it cleverly. */
enum class Grade(val title: String) { BRONZE("Bronze"), SILVER("Silver"), GOLD("Gold") }

/**
 * What a graded feat is measured by, and which way is better, like a lighter rocket, a longer drive
 * or a closer landing.
 */
enum class Metric(val label: String, val unit: String, val lowerIsBetter: Boolean) {
    LAUNCH_MASS("launch mass", "t", true),
    DISTANCE("distance", "km", false),
    HEIGHT("height", "km", false),
    DEPTH("depth", "m", false),
    MISS("miss", "m", true),
    SHARE("share", "%", false),
}

/**
 * Something a craft can be seen to pull off. It's never a task handed out, because how you do it is
 * up to you. Graded feats pay more when you do them cleverly, and [silver] and [gold] are the
 * thresholds for the [metric], in its unit.
 */
enum class Feat(
    val id: String,
    val title: String,
    val branch: Branch,
    /** What it takes, in one sentence. */
    val what: String,
    val metric: Metric? = null,
    val silver: Double = 0.0,
    val gold: Double = 0.0,
) {
    HOP("hop", "Hop", Branch.ROCKETRY, "Climb above a kilometre and bring the crew home."),
    STAGING("staging", "Staging", Branch.ROCKETRY, "Drop a spent stage in flight and bring the crew home. Higher is better.", Metric.HEIGHT, 45.0, 65.0),
    SPACE("space", "Space", Branch.ROCKETRY, "Climb out of Terra's air and bring the crew home.", Metric.LAUNCH_MASS, 12.0, 8.0),
    ORBIT("orbit", "Orbit", Branch.ROCKETRY, "Keep the low point of your orbit above Terra's air.", Metric.LAUNCH_MASS, 13.0, 10.0),
    HOME_AGAIN("home-again", "Home Again", Branch.ROCKETRY, "Come down from orbit and land the crew safely."),
    AEROBRAKE("aerobrake", "Aerobrake", Branch.TECHNIQUES, "Lower an orbit by a third or more using only the air, with no engines.", Metric.SHARE, 60.0, 90.0),
    RENDEZVOUS("rendezvous", "Rendezvous", Branch.TECHNIQUES, "In orbit, come within 50 m of another craft, drifting under 1 m/s."),
    DOCK_ORBIT("dock-orbit", "Dock in Orbit", Branch.TECHNIQUES, "Join two craft together while both are in orbit."),
    GRAVITY_ASSIST("gravity-assist", "Gravity Assist", Branch.TECHNIQUES, "Pass close by a world and come away with a tenth more or less energy, with no engines.", Metric.SHARE, 25.0, 50.0),
    TOUCHDOWN("touchdown", "Touchdown", Branch.TECHNIQUES, "Land on another world, in one piece."),
    PRECISION_LANDING("precision-landing", "Precision Landing", Branch.TECHNIQUES, "Land on another world within 200 m of something already there.", Metric.MISS, 50.0, 15.0),
    GLIDE_HOME("glide-home", "Glide Home", Branch.TECHNIQUES, "Come back from space and land on a runway."),
    REFUEL_OFF_WORLD("refuel-off-world", "Live off the Land", Branch.TECHNIQUES, "Refuel on another world and take off again."),
    FIRST_FLIGHT("first-flight", "First Flight", Branch.AVIATION, "Take off from a runway and land on one."),
    LONG_HAUL("long-haul", "Long Haul", Branch.AVIATION, "Fly 100 km and land.", Metric.DISTANCE, 250.0, 500.0),
    SUPERSONIC("supersonic", "Supersonic", Branch.AVIATION, "Fly faster than sound below 15 km. No jet will do it on its own."),
    HIGH_FLYER("high-flyer", "High Flyer", Branch.AVIATION, "Climb above 5 km on air-breathing engines alone."),
    ROAD_TRIP("road-trip", "Road Trip", Branch.GROUND, "Drive 10 km on Terra.", Metric.DISTANCE, 20.0, 40.0),
    ROVER_OFF_WORLD("rover-off-world", "Off-World Rover", Branch.GROUND, "Drive 2 km on another world.", Metric.DISTANCE, 5.0, 10.0),
    SEAWORTHY("seaworthy", "Seaworthy", Branch.SEA, "Sail 5 km and bring her back into harbour."),
    ALIEN_SEA("alien-sea", "Alien Sea", Branch.SEA, "Sail on another world's sea."),
    UNDER_SAIL("under-sail", "Under Sail", Branch.SEA, "Sail a kilometre on the wind alone, with no engine running.", Metric.DISTANCE, 3.0, 8.0),
    HOVER("hover", "Hover", Branch.AVIATION, "Hold a rotorcraft still over one spot for half a minute, twenty metres up or more, by hand.", Metric.MISS, 5.0, 2.0),
    ALIEN_SKIES("alien-skies", "Alien Skies", Branch.AVIATION, "Fly a rotorcraft, a balloon or an airship in another world's air."),
    UP_AND_AWAY("up-and-away", "Up and Away", Branch.SKIES, "Rise a kilometre on gas alone, with nothing running.", Metric.HEIGHT, 3.0, 8.0),
    LONG_FLOAT("long-float", "Long Float", Branch.SKIES, "Travel twenty kilometres floating on gas.", Metric.DISTANCE, 50.0, 100.0),
    CLOUD_CITY("cloud-city", "Cloud City", Branch.SKIES, "Found a base floating in another world's sky."),
    SEA_STEAD("sea-stead", "Sea Stead", Branch.SEA, "Found a base afloat on the sea."),
    DIVE("dive", "Dive", Branch.DEEP, "Take a crewed craft under the sea, and bring it back up.", Metric.DEPTH, 300.0, 1_000.0),
    SEAFLOOR("seafloor", "Seafloor", Branch.DEEP, "Set down on the sea floor more than a hundred metres down, and come back up."),
    VENTS("vents", "Vents", Branch.DEEP, "Find a vent on the sea floor, where the water comes up hot."),
    ABYSS("abyss", "Abyss", Branch.DEEP, "Go deeper than three thousand metres, and come back up."),
    ALIEN_DEEP("alien-deep", "Alien Deep", Branch.DEEP, "Dive under another world's sea."),
    SURVEY("survey", "Survey", Branch.SYSTEMS, "Survey a world's ore and ice from orbit."),
    RELAY("relay", "Relay", Branch.SYSTEMS, "Fly a probe beyond the Moon on a signal passed on by a relay."),
    OUTPOST("outpost", "Outpost", Branch.SYSTEMS, "Found a base on another world."),
    MOONWALK("moonwalk", "Moonwalk", Branch.CREW, "Walk on another world."),
    RESCUE("rescue", "Rescue", Branch.CREW, "Climb aboard a craft from a spacewalk."),
    ;

    val graded: Boolean get() = metric != null

    /** The grade that [value] earns, in the metric's unit. */
    fun gradeFor(value: Double): Grade {
        val m = metric ?: return Grade.BRONZE
        return if (m.lowerIsBetter) {
            when {
                value <= gold -> Grade.GOLD
                value <= silver -> Grade.SILVER
                else -> Grade.BRONZE
            }
        } else {
            when {
                value >= gold -> Grade.GOLD
                value >= silver -> Grade.SILVER
                else -> Grade.BRONZE
            }
        }
    }

    companion object {
        fun byId(id: String): Feat? = entries.firstOrNull { it.id == id }
    }
}

/** What each grade of a feat is worth in total. Doing better later pays the difference. */
object Insight {
    const val UNGRADED = 20
    fun worth(feat: Feat, grade: Grade): Int = if (!feat.graded) UNGRADED else when (grade) {
        Grade.BRONZE -> 10
        Grade.SILVER -> 20
        Grade.GOLD -> 35
    }
}

/**
 * Going somewhere new: into orbit around a world, down onto it, and back home from it with the
 * crew.
 */
enum class Visit(val id: String, val title: String, val multiplier: Int) {
    ORBIT("orbit", "Orbit", 1),
    LAND("land", "Landing", 2),
    RETURN("return", "Return", 3),
}

/**
 * One level of a facility: the most a craft launched from it can weigh (kg) and how many parts it
 * can have. 0 means no limit.
 */
@Serializable
data class FacilityLevel(val mass: Double = 0.0, val parts: Int = 0)

@Serializable
data class Facilities(
    val pad: List<FacilityLevel> = emptyList(),
    val hangar: List<FacilityLevel> = emptyList(),
    val harbour: List<FacilityLevel> = emptyList(),
)

/** What a new career starts with. */
@Serializable
data class StartingKit(
    val insight: Int = 0,
    val parts: List<String> = emptyList(),
    val pad: Int = 1,
    val hangar: Int = 0,
    val harbour: Int = 0,
    val crew: Int = 4,
)

/** One node of the tree: what it costs, what it needs first, and what it gives you. */
@Serializable
data class TechNode(
    val id: String,
    val title: String,
    val branch: String,
    val cost: Int,
    val blurb: String = "",
    /** Nodes you need first. */
    val requires: List<String> = emptyList(),
    /** A feat you need to have done first, by id. */
    val needs: String? = null,
    val parts: List<String> = emptyList(),
    val pad: Int = 0,
    val hangar: Int = 0,
    val harbour: Int = 0,
    val crew: Int = 0,
    val abilities: List<String> = emptyList(),
)

/** The whole tree, as it ships in `career/tree.json`. */
@Serializable
data class TechTree(
    val start: StartingKit = StartingKit(),
    val facilities: Facilities = Facilities(),
    /** How much each world is worth for a [Visit], by id. */
    val worlds: Map<String, Int> = emptyMap(),
    val nodes: List<TechNode> = emptyList(),
) {
    fun node(id: String): TechNode? = nodes.firstOrNull { it.id == id }

    companion object {
        const val AUTOPILOT = "autopilot"

        /** The ability to hold an aircraft's height and heading. */
        const val CRUISE = "cruise"

        private val json = Json { ignoreUnknownKeys = true }

        /** The tree the game ships with. */
        val stock: TechTree by lazy {
            val text = TechTree::class.java.getResourceAsStream("/career/tree.json")?.bufferedReader()?.readText()
                ?: error("career/tree.json is missing from :core's resources")
            json.decodeFromString(serializer(), text)
        }
    }
}

/** A world first on the server: who got there first, and when. */
@Serializable
data class WorldFirst(val bodyId: String, val visit: String, val owner: String, val ownerName: String, val time: Double)

/**
 * One player's career: the insight they can spend, what they've unlocked, the feats they've done
 * and how well, and where they've been.
 */
@Serializable
data class CareerState(
    val owner: String,
    val insight: Int = 0,
    val nodes: List<String> = emptyList(),
    /** Feat id to the best [Grade] ordinal reached. */
    val feats: Map<String, Int> = emptyMap(),
    /** Where they've been, as "body:visit". */
    val visits: List<String> = emptyList(),
) {
    fun has(node: String) = node in nodes
    fun grade(feat: Feat): Grade? = feats[feat.id]?.let { Grade.entries[it] }
    fun visited(bodyId: String, visit: Visit) = "$bodyId:${visit.id}" in visits

    /** Every part they're allowed to build with. */
    fun parts(tree: TechTree): Set<String> = (tree.start.parts + nodes.mapNotNull { tree.node(it) }.flatMap { it.parts }).toSet()

    fun padLevel(tree: TechTree) = maxOf(tree.start.pad, nodes.mapNotNull { tree.node(it)?.pad }.maxOrNull() ?: 0)
    fun hangarLevel(tree: TechTree) = maxOf(tree.start.hangar, nodes.mapNotNull { tree.node(it)?.hangar }.maxOrNull() ?: 0)
    fun harbourLevel(tree: TechTree) = maxOf(tree.start.harbour, nodes.mapNotNull { tree.node(it)?.harbour }.maxOrNull() ?: 0)
    fun crewCap(tree: TechTree) = maxOf(tree.start.crew, nodes.mapNotNull { tree.node(it)?.crew }.maxOrNull() ?: 0)
    fun ability(tree: TechTree, ability: String) = nodes.any { tree.node(it)?.abilities?.contains(ability) == true }

    /** Why [node] can't be unlocked yet, or null if it can. */
    fun blocker(tree: TechTree, node: TechNode): String? {
        if (has(node.id)) return "Already unlocked"
        val missing = node.requires.filter { !has(it) }.mapNotNull { tree.node(it)?.title }
        if (missing.isNotEmpty()) return "Needs ${missing.joinToString(" and ")}"
        node.needs?.let { id -> if (feats[id] == null) return "Needs the ${Feat.byId(id)?.title ?: id} feat" }
        if (insight < node.cost) return "Needs ${node.cost} insight"
        return null
    }
}

/** Where a craft launches from, which decides whose limits it has to meet. */
enum class Facility(val title: String) { PAD("Launch Pad"), HANGAR("Hangar"), HARBOUR("Harbour") }

/** Whether a design can launch in a career, and if not, why not. */
object CareerRules {
    /**
     * The sites a career can launch from: the Cape's three, plus the pads on a player's own bases.
     */
    val CAREER_SITES = setOf("cape", "airfield", "harbour")

    fun facilityFor(siteId: String): Facility = when (siteId) {
        "airfield" -> Facility.HANGAR
        "harbour" -> Facility.HARBOUR
        else -> Facility.PAD
    }

    /** The limits [state] has at [facility], or null when it has none there at all. */
    fun limits(tree: TechTree, state: CareerState, facility: Facility): FacilityLevel? {
        val (level, table) = when (facility) {
            Facility.PAD -> state.padLevel(tree) to tree.facilities.pad
            Facility.HANGAR -> state.hangarLevel(tree) to tree.facilities.hangar
            Facility.HARBOUR -> state.harbourLevel(tree) to tree.facilities.harbour
        }
        if (level <= 0) return null
        return table.getOrNull(level - 1) ?: table.lastOrNull()
    }

    /** The parts in [design] that [state] hasn't unlocked, by title. */
    fun lockedParts(tree: TechTree, state: CareerState, design: CraftDesign, catalog: PartCatalog): List<String> {
        val have = state.parts(tree)
        return design.parts.map { it.partId }.distinct().filter { it !in have }.map { catalog[it]?.title ?: it }
    }

    /** The mass of [design] fully fuelled, in kg. */
    fun massOf(design: CraftDesign, catalog: PartCatalog): Double =
        design.parts.sumOf { placed -> catalog[placed.partId]?.wetMass ?: 0.0 }

    /**
     * Why [design] can't launch from [siteId] in [state]'s career, or null if it can. It might use
     * parts that aren't unlocked yet, a site a career can't use, or be more than the facility there
     * can take.
     */
    fun refusal(tree: TechTree, state: CareerState, design: CraftDesign, siteId: String, catalog: PartCatalog): String? {
        val locked = lockedParts(tree, state, design, catalog)
        if (locked.isNotEmpty()) return "Not unlocked yet: ${locked.joinToString(", ")}"
        val onBase = siteId.startsWith(com.rm.apogee.core.world.LaunchSite.BASE_SITE_PREFIX)
        if (!onBase && siteId !in CAREER_SITES) return "A career launches from the Cape or your own bases"
        val facility = if (onBase) Facility.PAD else facilityFor(siteId)
        val limits = limits(tree, state, facility) ?: return "No ${facility.title.lowercase()} yet"
        val mass = massOf(design, catalog)
        if (limits.mass > 0.0 && mass > limits.mass) {
            return "Too heavy for the ${facility.title.lowercase()}: ${"%.1f".format(mass / 1000.0)} t of ${"%.0f".format(limits.mass / 1000.0)} t"
        }
        if (limits.parts > 0 && design.parts.size > limits.parts) {
            return "Too many parts for the ${facility.title.lowercase()}: ${design.parts.size} of ${limits.parts}"
        }
        return null
    }
}
