package com.rm.apogee.core.career

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The career's numbers. The stock craft can be launched once what they're built from is unlocked,
 * and a player earning no better than bronze can play it all the way through, with nothing out of
 * reach and nothing needing what it unlocks.
 */
class CareerBalanceTest {
    private val catalog = StockParts.catalog
    private val tree = TechTree.stock

    /** The nodes [ids] need first, all the way back, together with them. */
    private fun closure(ids: Collection<String>): Set<String> {
        val out = HashSet<String>()
        fun add(id: String) { if (out.add(id)) tree.node(id)!!.requires.forEach(::add) }
        ids.forEach(::add)
        return out
    }

    /** The nodes that give [design]'s parts, and what those need first. */
    private fun nodesFor(design: CraftDesign): Set<String> =
        closure(design.parts.map { it.partId }.distinct().mapNotNull { p -> tree.nodes.firstOrNull { p in it.parts }?.id })

    /** Feats done in orbit or beyond, and the rocketry any of them takes first. */
    private val BEYOND_ORBIT = setOf("orbit", "touchdown", "survey", "outpost", "rendezvous", "dock-orbit", "aerobrake", "gravity-assist")

    /** Feats under the sea, and what any of them takes first: a boat home through the harbour. */
    private val UNDER_SEA = setOf("dive", "seafloor", "vents")
    private val ROCKETRY = setOf("hop", "staging", "space", "orbit")

    private val stock = listOf(
        StockCraft.sounder(catalog), StockCraft.starterRocket(catalog), StockCraft.moteProbe(catalog), StockCraft.prospector(catalog),
        StockCraft.surveyor(catalog), StockCraft.lander(catalog), StockCraft.moonshot(catalog), StockCraft.moduleTug(catalog),
        StockCraft.rover(catalog), StockCraft.aeroplane(catalog), StockCraft.boat(catalog), StockCraft.sparrow(catalog),
        StockCraft.buggy(catalog), StockCraft.hauler(catalog), StockCraft.skiff(catalog), StockCraft.cutter(catalog),
        StockCraft.trawler(catalog), StockCraft.portTug(catalog), StockCraft.dockProbe(catalog), StockCraft.towBuggy(catalog),
        StockCraft.cart(catalog), StockCraft.baseCore(catalog), StockCraft.baseCoreLander(catalog), StockCraft.moduleHauler(catalog),
        StockCraft.baseCoreHauler(catalog), StockCraft.padBase(catalog), StockCraft.depotHauler(catalog),
        StockCraft.minnow(catalog), StockCraft.nautilus(catalog), StockCraft.abyss(catalog),
    )

    @Test
    fun `every stock craft launches once its parts are unlocked, from a facility its feats have earned`() {
        for (design in stock) {
            val nodes = nodesFor(design)
            // Feats a player has had to do to get those parts, and for any done in orbit or beyond,
            // the rocketry that got them there. The facilities those open are theirs too.
            val needed = nodes.mapNotNull { tree.node(it)!!.needs }.toSet()
            val feats = (if (needed.any { it in BEYOND_ORBIT }) needed + ROCKETRY else needed) +
                (if (needed.any { it in UNDER_SEA }) setOf("seaworthy") else emptySet())
            val facilities = tree.nodes.filter { (it.pad > 0 || it.hangar > 0 || it.harbour > 0) && (it.needs == null || it.needs in feats) }
                .map { it.id }.filter { closure(listOf(it)).all { n -> tree.node(n)!!.needs == null || tree.node(n)!!.needs in feats } }
            val state = CareerState("p1", nodes = (closure(nodes + facilities)).toList(), feats = feats.associateWith { 0 })
            val site = World.launchSiteFor(design, catalog).id
            assertNull("${design.name} from $site with $nodes", CareerRules.refusal(tree, state, design, site, catalog))
        }
    }

    @Test
    fun `the first craft of each kind needs no feat to build`() {
        for (design in listOf(StockCraft.sounder(catalog), StockCraft.sparrow(catalog), StockCraft.rover(catalog), StockCraft.skiff(catalog))) {
            val needs = nodesFor(design).mapNotNull { tree.node(it)!!.needs }
            assertEquals("${design.name} needs feats $needs", emptyList<String>(), needs)
        }
    }

    // --- a career, played through -----------------------------------------------------

    private sealed interface Step
    private data class Earn(val feat: Feat) : Step
    private data class Go(val body: String, val visit: Visit) : Step
    private data class Buy(val node: String) : Step
    /** Reach the sea's named place [wonder]. */
    private data class Found(val wonder: String) : Step
    /** Launch [design] from [site]. It has to be allowed by then. */
    private data class Fly(val design: CraftDesign, val site: String) : Step

    private fun earn(vararg feats: Feat) = feats.map { Earn(it) }
    private fun buy(vararg nodes: String) = nodes.map { Buy(it) }
    private fun go(body: String, vararg visits: Visit) = visits.map { Go(body, it) }
    private fun go(vararg wonders: String) = wonders.map { Found(it.removePrefix("wonder:")) }

    /**
     * A career the way a player might go at it, with every graded feat earned at bronze: the
     * Sounder, into space and orbit, the aircraft, the rover and the boat alongside, to Luna and
     * back, rendezvous and docking, probes, surveys and a base, and then out to the planets (Rubra,
     * its moon Timor, Caligo, and Aurantia's sea). Every unlock is affordable when it comes, and
     * every craft flown is allowed.
     */
    private val path: List<Step> = buildList {
        add(Fly(StockCraft.sounder(catalog), "cape")); addAll(earn(Feat.HOP, Feat.STAGING))
        addAll(buy("tanks", "vacuum"))
        addAll(earn(Feat.SPACE))
        addAll(buy("reentry"))
        add(Fly(StockCraft.starterRocket(catalog), "cape")); addAll(earn(Feat.ORBIT, Feat.HOME_AGAIN))
        addAll(buy("flight"))
        add(Fly(StockCraft.sparrow(catalog), "airfield")); addAll(earn(Feat.FIRST_FLIGHT, Feat.LONG_HAUL, Feat.HIGH_FLYER))
        addAll(buy("rovers", "boats"))
        add(Fly(StockCraft.rover(catalog), "cape")); addAll(earn(Feat.ROAD_TRIP))
        add(Fly(StockCraft.skiff(catalog), "harbour")); addAll(earn(Feat.SEAWORTHY))
        // A sloop out of the harbour, on the wind alone.
        addAll(buy("sails"))
        add(Fly(StockCraft.sloop(catalog), "harbour")); addAll(earn(Feat.UNDER_SAIL))
        // Under the sea, from the harbour: the Minnow, down its canyon and onto its floor.
        addAll(buy("submersibles"))
        add(Fly(StockCraft.minnow(catalog), "harbour")); addAll(earn(Feat.DIVE, Feat.SEAFLOOR))
        addAll(go("wonder:lost-sounder", "wonder:great-arch", "wonder:cape-canyon", "wonder:farrow-seamount"))
        addAll(buy("pad-2", "landing"))
        add(Fly(StockCraft.lander(catalog), "cape"))
        addAll(go("luna", Visit.ORBIT)); addAll(earn(Feat.TOUCHDOWN, Feat.MOONWALK)); addAll(go("luna", Visit.LAND, Visit.RETURN))
        addAll(buy("electrics", "attitude"))
        addAll(earn(Feat.RENDEZVOUS, Feat.AEROBRAKE, Feat.GRAVITY_ASSIST))
        addAll(buy("docking"))
        add(Fly(StockCraft.portTug(catalog), "cape")); addAll(earn(Feat.DOCK_ORBIT, Feat.RESCUE))
        addAll(buy("survey"))
        addAll(earn(Feat.SURVEY))
        addAll(buy("probes", "power"))
        add(Fly(StockCraft.moteProbe(catalog), "cape"))
        add(Fly(StockCraft.lander(catalog), "cape")); addAll(earn(Feat.PRECISION_LANDING))
        addAll(buy("outposts"))
        add(Fly(StockCraft.baseCoreLander(catalog), "cape")); addAll(earn(Feat.OUTPOST))
        addAll(buy("mining"))
        add(Fly(StockCraft.prospector(catalog), "cape")); addAll(earn(Feat.REFUEL_OFF_WORLD))
        addAll(buy("relays"))
        addAll(earn(Feat.RELAY, Feat.SUPERSONIC, Feat.GLIDE_HOME))
        add(Fly(StockCraft.rover(catalog), "cape")); addAll(earn(Feat.ROVER_OFF_WORLD))
        // What it takes to go further: a bigger pad, and bigger rockets.
        addAll(buy("pad-3", "broad", "fairings"))
        add(Fly(StockCraft.moonshot(catalog), "cape"))
        // Out to the planets, and everything that was put off on the way.
        addAll(go("rubra", Visit.ORBIT, Visit.LAND, Visit.RETURN))
        // A helicopter hovered by hand, and a balloon let go.
        addAll(buy("rotorcraft"))
        add(Fly(StockCraft.hummingbird(catalog), "airfield")); addAll(earn(Feat.HOVER))
        addAll(buy("balloons"))
        add(Fly(StockCraft.skylark(catalog), "cape")); addAll(earn(Feat.UP_AND_AWAY))
        addAll(buy("flight-computer", "airframes", "haulers", "ships"))
        addAll(go("timor", Visit.ORBIT, Visit.LAND, Visit.RETURN))
        // The Hotshell before Caligo, because that's what it takes to land in that air.
        addAll(buy("hot-worlds", "quarters", "industry", "spaceplanes"))
        addAll(go("caligo", Visit.ORBIT, Visit.LAND, Visit.RETURN))
        addAll(go("aurantia", Visit.ORBIT)); addAll(earn(Feat.ALIEN_SEA))
        // Deeper: the Nautilus to the vents, the Abyss to the bottom of the Terra Deep, and under
        // Aurantia's sea.
        addAll(buy("sonar", "deep-hulls"))
        add(Fly(StockCraft.nautilus(catalog), "harbour")); addAll(earn(Feat.VENTS)); addAll(go("wonder:canyon-wreck", "wonder:chimneys"))
        addAll(buy("abyssal-hulls"))
        add(Fly(StockCraft.abyss(catalog), "harbour")); addAll(earn(Feat.ABYSS)); addAll(go("wonder:nodule-plain", "wonder:terra-deep"))
        addAll(earn(Feat.ALIEN_DEEP)); addAll(go("wonder:kraken-deep", "wonder:ligeia-spires"))
        addAll(buy("astronaut-corps", "deep-space", "pad-4", "cruise-control"))
        // The keeper core, a drone, an airship across the country, a platform afloat, and one in
        // the sky.
        addAll(buy("station-keeping", "drones", "airships"))
        add(Fly(StockCraft.quad(catalog), "cape"))
        add(Fly(StockCraft.zeppelin(catalog), "airfield")); addAll(earn(Feat.LONG_FLOAT))
        addAll(buy("sea-platforms"))
        add(Fly(StockCraft.seaPlatform(catalog), "harbour")); addAll(earn(Feat.SEA_STEAD))
        addAll(buy("sky-platforms"))
        add(Fly(StockCraft.skyPlatform(catalog), "cape"))
        addAll(earn(Feat.ALIEN_SKIES, Feat.CLOUD_CITY))
    }

    @Test
    fun `a career can be played through at bronze, from the Sounder to the planets and the whole tree`() {
        var state = CareerState("p1", insight = tree.start.insight)
        val log = StringBuilder()
        for (step in path) {
            when (step) {
                is Earn -> {
                    state = state.copy(insight = state.insight + Insight.worth(step.feat, Grade.BRONZE), feats = state.feats + (step.feat.id to 0))
                    log.append("${step.feat.id} ")
                }
                is Go -> {
                    state = state.copy(insight = state.insight + tree.worlds.getValue(step.body) * step.visit.multiplier, visits = state.visits + "${step.body}:${step.visit.id}")
                    log.append("${step.body}:${step.visit.id} ")
                }
                is Buy -> {
                    val node = tree.node(step.node)!!
                    assertNull("can't unlock ${node.id} with ${state.insight} after $log", state.blocker(tree, node))
                    state = state.copy(insight = state.insight - node.cost, nodes = state.nodes + node.id)
                    log.append("[${node.id} -> ${state.insight}] ")
                }
                is Found -> {
                    val wonder = com.rm.apogee.core.world.SeaWonders.byId(step.wonder)!!
                    state = state.copy(insight = state.insight + wonder.insight, visits = state.visits + "wonder:${wonder.id}")
                    log.append("${wonder.id} ")
                }
                is Fly -> assertNull("can't fly ${step.design.name} after $log", CareerRules.refusal(tree, state, step.design, step.site, catalog))
            }
        }
        assertEquals("not all bought: ${tree.nodes.map { it.id } - state.nodes.toSet()}", tree.nodes.size, state.nodes.size)
        println("Played through: $log")
        // Not so much left over that the tree was no squeeze at all.
        assertTrue("${state.insight} insight left: the tree is too cheap", state.insight < 150)
    }
}
