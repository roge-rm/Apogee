package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.part.LandingLeg

/**
 * Stages [vessel] through [stages], snaps its legs fully out and stands it on them where it is,
 * so tests skip the second and a half of deploy.
 */
fun World.gearDown(vessel: Vessel, stages: Int = 3) {
    repeat(stages) { stage(vessel) }
    vessel.fitPose()
    for (i in vessel.defs.indices) {
        if (vessel.defs[i].hasModule<LandingLeg>() && vessel.isWorking(i)) vessel.setLegDeploy(i, 1.0)
    }
    setDown(vessel)
}
