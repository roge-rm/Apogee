package com.rm.apogee.game.tutorial

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World

/** A world of its own for a tutorial. It's never saved. */
object TutorialWorld {
    /** An empty world, with the Cape's buildings. */
    fun fresh(catalog: PartCatalog): World = World.default(catalog).apply { ensureStructures() }

    /**
     * A world with [start]'s craft already in orbit, owned by [owner]. Returns the world and the
     * craft to fly.
     */
    fun inOrbit(start: TutorialStart.InOrbit, catalog: PartCatalog, owner: String): Pair<World, Long> {
        val world = fresh(catalog)
        val terra = world.system.body(SolarSystem.HOMEWORLD_ID)
        val orbit = Orbit.circular(terra.radius + start.altitude, terra.gravitationalParameter, epoch = world.time)
        val craft = world.spawnInOrbit(start.craft(catalog), terra.id, orbit)
        craft.body.angularVelocity.setZero()
        world.assignOwner(craft, owner)
        repeat(start.dropStages) { world.stage(craft) }
        // What it dropped goes, so it doesn't drift alongside.
        for (v in world.vessels.toList()) if (v !== craft) world.apply(Command.RemoveVessel(v.id.raw))
        start.partner?.let { partner ->
            val ahead = craft.body.linearVelocity.normalized()
            val position = craft.body.position.copy().addScaledInPlace(ahead, start.partnerGap)
            // Its nose, and the ring on it, back toward the craft coming to dock.
            val facing = quatFromTo(Vec3.unitY(), ahead.copy().negateInPlace())
            val other = world.spawnAt(partner(catalog), terra.id, position, craft.body.linearVelocity.copy(), facing)
            world.assignOwner(other, owner)
        }
        return world to craft.id.raw
    }
}
