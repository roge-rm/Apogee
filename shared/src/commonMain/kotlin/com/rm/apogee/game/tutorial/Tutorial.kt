package com.rm.apogee.game.tutorial

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.world.LaunchTime

/** What a tutorial is about, for the headings on the list. */
enum class Topic(val title: String) {
    FIRST_FLIGHT("First flight"),
    BUILDING("Building"),
    ORBIT("Orbit and landing"),
    OTHER("Other craft"),
}

/** Where a tutorial starts you. */
sealed interface TutorialStart {
    /** [craft] on [siteId], ready to go. */
    class OnSite(val craft: (PartCatalog) -> CraftDesign, val siteId: String) : TutorialStart

    /**
     * [craft] in a circular orbit [altitude] metres over Terra, with its first [dropStages] stages
     * already gone. [partner], if given, floats [partnerGap] metres ahead to dock with.
     */
    class InOrbit(
        val craft: (PartCatalog) -> CraftDesign,
        val altitude: Double,
        val dropStages: Int = 0,
        val partner: ((PartCatalog) -> CraftDesign)? = null,
        val partnerGap: Double = 40.0,
    ) : TutorialStart

    /** An empty Vehicle Assembly, then a flight in whatever gets built. */
    data object Builder : TutorialStart
}

/** One thing to do: [text] on screen until [check] holds for [holdFor] seconds. */
class Step(
    val text: String,
    val holdFor: Double = 0.0,
    val check: (TutorialView, TutorialRun) -> Boolean,
)

/** A guided flight: where it starts and the steps through it. */
class Tutorial(
    val id: String,
    val topic: Topic,
    val title: String,
    val start: TutorialStart,
    val steps: List<Step>,
    val launchTime: LaunchTime = LaunchTime.NOON,
)
