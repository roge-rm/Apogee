package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.part.LandingLeg

/**
 * Stages [vessel] through [stages] and puts its landing legs straight down, standing it on them
 * where it is.
 *
 * Legs take a second and a half to swing out. Tests about what happens once they're out (landings,
 * craft resting on craft) start from there instead of each one waiting for the deploy.
 */
fun World.gearDown(vessel: Vessel, stages: Int = 3) {
    repeat(stages) { stage(vessel) }
    vessel.fitPose()
    for (i in vessel.defs.indices) {
        if (vessel.defs[i].hasModule<LandingLeg>() && vessel.isWorking(i)) vessel.setLegDeploy(i, 1.0)
    }
    setDown(vessel)
}
