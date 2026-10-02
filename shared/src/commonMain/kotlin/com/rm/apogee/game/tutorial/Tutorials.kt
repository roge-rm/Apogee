package com.rm.apogee.game.tutorial

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.world.SasMode

/** Every tutorial, in the order the list shows them. */
object Tutorials {
    private val firstFlight = Tutorial(
        "first-flight", Topic.FIRST_FLIGHT, "First flight",
        TutorialStart.OnSite({ StockCraft.sounder(it) }, "cape"),
        listOf(
            Step("Push the throttle all the way up") { v, _ -> v.throttle >= 0.95 },
            Step("Tap Stage to light the engine") { v, r -> v.stage != r.first?.stage },
            Step("Turn on SAS to fly straight") { v, _ -> v.sasEnabled },
            Step("Climb past 1 km") { v, _ -> v.agl > 1_000.0 },
            Step("Wait for the top of the climb") { v, _ -> v.verticalSpeed < 0.0 && v.agl > 200.0 },
            Step("Stage until the chute is ready") { v, _ -> v.chute != null },
            Step("Float down and land", holdFor = 2.0) { v, _ -> v.resting },
        ),
    )

    private val rover = Tutorial(
        "rover", Topic.OTHER, "Rover",
        TutorialStart.OnSite({ StockCraft.rover(it) }, "cape"),
        listOf(
            Step("Push the throttle up to drive") { v, _ -> v.surfaceSpeed > 3.0 },
            Step("Steer round to face the other way") { v, r -> turned(v, r) > 90.0 },
            Step("Brake to a stop") { v, _ -> v.brakes && v.surfaceSpeed < 0.5 },
        ),
    )

    private val boat = Tutorial(
        "boat", Topic.OTHER, "Boat",
        TutorialStart.OnSite({ StockCraft.jetBoat(it) }, "harbour"),
        listOf(
            Step("Push the throttle up") { v, _ -> v.throttle > 0.5 },
            Step("Get up past 10 m/s") { v, _ -> v.surfaceSpeed > 10.0 },
            Step("Turn round to face the other way") { v, r -> turned(v, r) > 90.0 },
            Step("Cut the throttle and slow down") { v, _ -> v.throttle < 0.05 && v.surfaceSpeed < 1.5 },
        ),
    )

    private val helicopter = Tutorial(
        "helicopter", Topic.OTHER, "Helicopter",
        TutorialStart.OnSite({ StockCraft.hummingbird(it) }, "airfield"),
        listOf(
            Step("Throttle up to lift off") { v, _ -> v.agl > 5.0 },
            Step("Hover near 20 m", holdFor = 3.0) { v, _ -> v.agl in 12.0..30.0 && kotlin.math.abs(v.verticalSpeed) < 1.0 },
            Step("Fly forward") { v, _ -> !v.grounded && v.surfaceSpeed > 5.0 },
            Step("Set it down") { v, _ -> v.grounded && v.surfaceSpeed < 0.5 },
        ),
    )

    private val eva = Tutorial(
        "eva", Topic.OTHER, "Walking",
        TutorialStart.OnSite({ StockCraft.rover(it) }, "cape"),
        listOf(
            Step("Open the crew card and step out") { v, _ -> v.isSuit },
            Step("Walk around", holdFor = 2.0) { v, _ -> v.isSuit && v.surfaceSpeed > 0.5 },
            Step("Walk back and board") { v, _ -> !v.isSuit },
        ),
    )

    private val base = Tutorial(
        "base", Topic.OTHER, "Base",
        TutorialStart.OnSite({ StockCraft.baseCore(it) }, "cape"),
        listOf(
            Step("Tap Found Base") { v, _ -> v.anchored },
        ),
    )

    private val building = Tutorial(
        "building", Topic.BUILDING, "Build a rocket",
        TutorialStart.Builder,
        listOf(
            Step("Pick a pod from the parts and place it") { v, _ -> v.builder?.hasPod == true },
            Step("Add a fuel tank under the pod") { v, _ -> v.builder?.hasTank == true },
            Step("Add an engine under the tank") { v, _ -> v.builder?.hasEngine == true },
            Step("Put a chute on top of the pod") { v, _ -> v.builder?.hasChute == true },
            Step("Open the stages to check them") { v, _ -> v.builder?.stagingSeen == true },
            Step("Tap Launch") { v, _ -> v.builder == null },
            Step("Throttle up and stage") { v, r -> v.stage != r.first?.stage && v.throttle > 0.5 },
            Step("Stage until the chute is ready") { v, _ -> v.chute != null },
            Step("Float down and land", holdFor = 2.0) { v, _ -> v.resting },
        ),
    )

    private val orbit = Tutorial(
        "orbit", Topic.ORBIT, "Into orbit",
        TutorialStart.OnSite({ StockCraft.starterRocket(it) }, "cape"),
        listOf(
            Step("Throttle up and stage") { v, r -> v.stage != r.first?.stage && v.throttle > 0.9 },
            Step("Turn on SAS") { v, _ -> v.sasEnabled },
            Step("Past 1 km, lean a little east") { v, _ -> v.agl > 1_000.0 && v.lean > 8.0 },
            Step("Keep leaning more as you climb") { v, _ -> v.altitude > 10_000.0 && v.lean > 40.0 },
            Step("Stage when the first stage runs dry") { v, r -> v.stage != r.stepStart?.stage },
            Step("Cut the engine at an 80 km high point") { v, _ -> v.apoapsis >= 80_000.0 && v.throttle < 0.05 },
            Step("Open the map") { v, _ -> v.mapMode },
            Step("Plan a burn at the high point") { v, _ -> v.burnPlanned },
            Step("Do the burn to make an orbit") { v, _ -> v.inOrbit },
        ),
    )

    private val comingHome = Tutorial(
        "coming-home", Topic.ORBIT, "Coming home",
        TutorialStart.InOrbit({ StockCraft.starterRocket(it) }, altitude = 90_000.0, dropStages = 2),
        listOf(
            Step("Set SAS to point against your motion") { v, _ -> v.sasEnabled && v.sasMode == SasMode.RETROGRADE },
            Step("Burn until the low point is under 30 km") { v, _ -> v.periapsis < 30_000.0 },
            Step("Cut the engine and stage it off") { v, r -> v.stage != r.stepStart?.stage },
            Step("Let the air slow you down") { v, _ -> v.inAir && v.surfaceSpeed < 300.0 },
            Step("Stage for the chute") { v, _ -> v.chute != null },
            Step("Float down and land", holdFor = 2.0) { v, _ -> v.resting },
        ),
    )

    private val plane = Tutorial(
        "plane", Topic.ORBIT, "Plane",
        TutorialStart.OnSite({ StockCraft.sparrow(it) }, "airfield"),
        listOf(
            Step("Turn on SAS") { v, _ -> v.sasEnabled },
            Step("Throttle up and roll down the runway") { v, _ -> v.surfaceSpeed > 20.0 },
            Step("Pull back gently to take off") { v, _ -> !v.grounded && v.agl > 30.0 },
            Step("Climb to 300 m") { v, _ -> v.agl > 300.0 },
            Step("Turn round and head for the runway") { v, r -> v.onApproach && turnedFrom(v, r.first) > 120.0 },
            Step("Ease off and touch down") { v, _ -> v.grounded },
            Step("Brake to a stop") { v, _ -> v.brakes && v.surfaceSpeed < 1.0 },
        ),
    )

    private val docking = Tutorial(
        "docking", Topic.OTHER, "Docking",
        TutorialStart.InOrbit({ StockCraft.dockProbe(it) }, altitude = 100_000.0, partner = { StockCraft.dockProbe(it) }),
        listOf(
            Step("Hold SAS and pick the other probe") { v, _ -> v.targetName != null },
            Step("Set SAS to Target") { v, _ -> v.sasEnabled && v.sasMode == SasMode.TARGET },
            Step("Arm thrusters and close in slowly") { v, _ -> v.targetDistance < 10.0 && kotlin.math.abs(v.closingSpeed) < 1.0 },
            Step("Touch the rings together") { v, _ -> v.docked },
        ),
    )

    val all: List<Tutorial> = listOf(firstFlight, building, orbit, comingHome, plane, rover, boat, helicopter, eva, docking, base)

    fun byId(id: String): Tutorial? = all.firstOrNull { it.id == id }

    /** How far the heading has swung since this step began, 0 to 180 degrees. */
    private fun turned(v: TutorialView, r: TutorialRun): Double = turnedFrom(v, r.stepStart)

    private fun turnedFrom(v: TutorialView, from: TutorialView?): Double {
        val heading = from?.heading ?: return 0.0
        return kotlin.math.abs(((v.heading - heading) % 360.0 + 540.0) % 360.0 - 180.0)
    }
}
