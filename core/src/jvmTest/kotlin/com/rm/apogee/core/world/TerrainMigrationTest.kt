package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.TerrainField
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loading a world saved on older ground, as every save from before 0.3.0 was. */
class TerrainMigrationTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** The save [world] would have written on the old ground, with [move] applied to each craft's position. */
    private fun oldSave(world: World, felled: List<Long>, move: (VesselSave) -> Vec3): WorldSave {
        val save = world.save()
        return WorldSave(
            formatVersion = save.formatVersion,
            catalogHash = save.catalogHash,
            universeTime = save.universeTime,
            nextVesselId = save.nextVesselId,
            felledScatter = felled,
            terrainGeneration = 1,
            vessels = save.vessels.map { v ->
                VesselSave(
                    id = v.id, name = v.name, owner = v.owner, ownerName = v.ownerName,
                    design = v.design, referenceBodyId = v.referenceBodyId,
                    position = move(v), rotation = v.rotation, velocity = v.velocity,
                    angularVelocity = v.angularVelocity, currentStage = v.currentStage,
                    activatedParts = v.activatedParts, brokenParts = v.brokenParts,
                    throttle = v.throttle, sasEnabled = v.sasEnabled, brakes = v.brakes,
                    resources = v.resources,
                )
            },
        )
    }

    /** How far the craft's lowest contact sits above the ground under it. */
    private fun clearance(world: World, id: com.rm.apogee.core.craft.VesselId): Double {
        val vessel = world.vessel(id)!!
        val body = world.attractorFor(vessel)
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        val bf = body.toBodyFixed(up, body.rotationAt(world.time, Quat.identity()))
        val ground = body.surfaceRadiusInBodyFrame(bf)
        var lowest = Double.MAX_VALUE
        val offset = Vec3()
        for (i in vessel.defs.indices) for (p in vessel.defs[i].contactPoints.indices) {
            vessel.contactOffsetWorld(i, p, offset)
            lowest = minOf(lowest, vessel.body.position.length + (offset dot up))
        }
        return lowest - ground
    }

    @Test
    fun `parked craft are set back on the new ground and orbiting ones are left alone`() {
        val world = World.default(catalog)
        val site = World.launchSites.first()
        val buried = world.spawnOnSurface(StockCraft.rover(catalog), site, pad = 0)
        val floating = world.spawnOnSurface(StockCraft.rover(catalog), site, pad = 1)
        val terra = world.system.body("terra")
        val orbiting = world.spawnInOrbit(
            StockCraft.probe(catalog), "terra",
            Orbit.circular(terra.radius + 120_000.0, terra.gravitationalParameter),
        )
        buried.control.brakes = true
        floating.control.brakes = true
        repeat(120) { world.step(dt) }
        val orbitPosition = orbiting.body.position.copy()

        // The old ground was 4 m higher under one rover and 3 m lower under the other, so as
        // saved one is buried and one hovers.
        val save = oldSave(world, felled = listOf(42L, 43L)) { v ->
            val p = Vec3().setTo(v.position)
            val up = p.normalized()
            when (v.id) {
                buried.id.raw -> p.addScaledInPlace(up, -4.0)
                floating.id.raw -> p.addScaledInPlace(up, 3.0)
                else -> p
            }
        }

        val restored = World.default(catalog)
        val problems = restored.restore(save)
        assertTrue("the load should say what it did: $problems", problems.any { "terrain changed" in it })
        assertTrue("felled trees regrow on new ground", restored.felledScatter.isEmpty())

        for (id in listOf(buried.id, floating.id)) {
            val c = clearance(restored, id)
            assertTrue("craft $id should be set on the ground, clearance $c m", c in 0.0..0.2)
        }
        assertTrue(
            "the orbiting craft must not be touched",
            restored.vessel(orbiting.id)!!.body.position.approxEquals(orbitPosition, 1e-6),
        )

        // And they stay put, not flung out of the ground or dropped onto it.
        repeat(180) { restored.step(dt) }
        for (id in listOf(buried.id, floating.id)) {
            val v = assertNotNull(restored.vessel(id)).let { restored.vessel(id)!! }
            val surface = restored.attractorFor(v).surfaceVelocityAt(v.body.position, Vec3())
            val speed = Vec3().setTo(v.body.linearVelocity).subInPlace(surface).length
            assertTrue("craft $id should be at rest, moving at $speed m/s", speed < 0.3)
            assertTrue("craft $id should be undamaged", v.broken.none { it })
        }
    }

    @Test
    fun `a save from the current terrain loads exactly as saved`() {
        val world = World.default(catalog)
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), World.launchSites.first())
        repeat(60) { world.step(dt) }
        world.felledScatter.add(7L)
        val save = world.save()
        assertEquals(TerrainField.GENERATION, save.terrainGeneration)

        val restored = World.default(catalog)
        assertTrue(restored.restore(save).isEmpty())
        assertEquals(setOf(7L), restored.felledScatter.toSet())
        assertTrue(rover.body.position.approxEquals(restored.vessel(rover.id)!!.body.position, 1e-9))
    }

    @Test
    fun `a save from before terrain generations counts as the first`() {
        val json = Json { ignoreUnknownKeys = true }
        val save = json.decodeFromString(
            WorldSave.serializer(),
            """{"catalogHash":"x","universeTime":0.0,"nextVesselId":1}""",
        )
        assertEquals(1, save.terrainGeneration)
    }
}
