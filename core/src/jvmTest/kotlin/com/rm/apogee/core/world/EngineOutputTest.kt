package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/** An engine's output drives its flame and sound, and drops to nothing once it's dry. */
class EngineOutputTest {

    @Test
    fun `a dry engine puts out nothing, whatever the throttle`() {
        val catalog = StockParts.catalog
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val position = Vec3(terra.radius + 400_000.0, 0.0, 0.0)
        val rocket = world.spawnAt(
            StockCraft.starterRocket(catalog), "terra", position,
            Vec3(0.0, 0.0, sqrt(terra.gravitationalParameter / position.x)),
            quatFromTo(Vec3.unitY(), Vec3(1.0, 0.0, 0.0)),
        )
        world.stage(rocket)
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        world.step(1.0 / 60.0)
        val engine = rocket.defs.indexOfFirst { it.id == "engine-ember" }
        assertEquals("lit and fed, full output", 1.0, rocket.engineOutput[engine], 1e-9)

        // Empty every tank feeding it.
        for (i in rocket.defs.indices) rocket.takeFromPart(i, ResourceType.PROPELLANT, 1e9)
        world.step(1.0 / 60.0)
        assertEquals("dry, nothing", 0.0, rocket.engineOutput[engine], 0.0)
        assertEquals("with the throttle still open", 1.0, rocket.control.throttle, 0.0)
    }
}
