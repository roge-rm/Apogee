package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel

/**
 * Stages [vessel] through [stages], puts its gear down, snaps it fully out and stands it on it
 * where it is, so tests skip the second and a half of deploy.
 */
fun World.gearDown(vessel: Vessel, stages: Int = 3) {
    repeat(stages) { stage(vessel) }
    vessel.fitPose()
    vessel.control.gear = true
    for (i in vessel.defs.indices) {
        if (vessel.defs[i].fold != null && vessel.gearDown(i)) vessel.setLegDeploy(i, 1.0)
    }
    setDown(vessel)
}
