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

    @Test fun `old underwater landmarks move to the new sea floor without replacing them`() {
        val world = World.default(StockParts.catalog)
        world.ensureStructures()
        val wonder = Wonders.byId("great-arch")!!
        val mark = world.landmark(wonder)!!
        val body = world.system.body("terra")
        val oldUp = com.rm.apogee.core.orbit.SolarSystem.capeDirection(-2_600.0, 7_100.0)
        mark.unanchor()
        mark.body.position.setTo(oldUp).mulInPlace(body.radius + 50.0)
        world.pin(mark)
        val restored = World.default(StockParts.catalog)
        restored.restore(world.save())
        restored.ensureStructures()
        val moved = restored.landmark(wonder)!!
        assertEquals(mark.id, moved.id)
        assertTrue(moved.anchored)
        val fixed = body.toBodyFixed(moved.body.position, body.rotationAt(restored.time)).normalizeInPlace()
        assertTrue(fixed.distanceTo(wonder.direction) * body.radius < 1.0)
        assertTrue(body.altitudeOf(moved.body.position) < -100.0)
        assertEquals(mark.health.toList(), moved.health.toList())
        restored.ensureStructures()
        assertEquals(moved.id, restored.landmark(wonder)!!.id)
    }

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
    fun `generation nine parked craft on vanished land move ashore but afloat craft stay put`() = migrateParked(9)

    @Test
    fun `generation ten parked craft on vanished land move ashore but afloat craft stay put`() = migrateParked(10)

    private fun migrateParked(fromGeneration: Int) {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val terrain = terra.terrain as TerrainField
        var vanished: Vec3? = null
        var ocean: Vec3? = null
        for (i in 0 until 10_000) {
            val y = 1.0 - 2.0 * (i + 0.5) / 10_000.0
            val r = kotlin.math.sqrt(1.0 - y * y)
            val d = Vec3(r * kotlin.math.cos(i * 2.399963), y, r * kotlin.math.sin(i * 2.399963))
            if (terrain.elevation(d) >= -100.0) continue
            if (terrain.previousElevation(d, fromGeneration) > 50.0 && vanished == null) vanished = d
            if (terrain.previousElevation(d, fromGeneration) < -100.0 && ocean == null) ocean = d
            if (vanished != null && ocean != null) break
        }
        assertNotNull(vanished)
        assertNotNull(ocean)
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), World.launchSites.first())
        val afloat = world.spawnOnSurface(StockCraft.rover(catalog), World.launchSites.first(), pad = 1)
        for ((vessel, unit) in listOf(rover to vanished!!, afloat to ocean!!)) {
            val oldUp = vessel.body.position.normalized()
            val axis = oldUp.cross(unit)
            vessel.body.orientation.setTo(Quat(axis.x, axis.y, axis.z, 1.0 + (oldUp dot unit)).normalizeInPlace() * vessel.body.orientation)
            val height = if (vessel === rover) terrain.previousElevation(unit, fromGeneration) else 0.0
            // A rover's bottom lies a few metres below its centre; the migration allows 20 m.
            vessel.body.position.setTo(unit).mulInPlace(terra.radius + height + 3.0)
            terra.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        }
        val afloatPosition = afloat.body.position.copy()
        val json = Json
        val tree = json.parseToJsonElement(json.encodeToString(WorldSave.serializer(), world.save())) as kotlinx.serialization.json.JsonObject
        val old = kotlinx.serialization.json.JsonObject(tree + ("terrainGeneration" to kotlinx.serialization.json.JsonPrimitive(fromGeneration)))
        val save = json.decodeFromJsonElement(WorldSave.serializer(), old)
        val restored = World.default(catalog)
        restored.restore(save)
        val landed = restored.vessel(rover.id)!!
        val bf = terra.toBodyFixed(landed.body.position.normalized(), terra.rotationAt(restored.time, Quat.identity()))
        assertTrue("parked craft moved onto dry ground", terrain.elevation(bf) > 0.0)
        assertTrue("craft rests on the new surface", clearance(restored, rover.id) in 0.0..0.2)
        assertTrue("afloat craft stays put", restored.vessel(afloat.id)!!.body.position.approxEquals(afloatPosition, 1e-9))
    }

    @Test
    fun `generation eleven boats on filled harbour water move back to sea`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val terrain = terra.terrain as TerrainField
        val unit = terrain.worksDirection(-9000.0, 12000.0)
        assertTrue(terrain.previousElevation(unit, 11) < -3.0)
        assertTrue(terrain.elevation(unit) > 0.0)
        val boat = world.spawnOnSurface(StockCraft.rover(catalog), World.launchSites.first())
        boat.body.position.setTo(unit).mulInPlace(terra.radius + 1.0)
        terra.surfaceVelocityAt(boat.body.position, boat.body.linearVelocity)
        val tree = Json.parseToJsonElement(Json.encodeToString(WorldSave.serializer(), world.save())) as kotlinx.serialization.json.JsonObject
        val save = Json.decodeFromJsonElement(WorldSave.serializer(), kotlinx.serialization.json.JsonObject(tree +
            ("terrainGeneration" to kotlinx.serialization.json.JsonPrimitive(11))))
        val restored = World.default(catalog)
        restored.restore(save)
        val moved = restored.vessel(boat.id)!!
        val dir = terra.toBodyFixed(moved.body.position.normalized(), terra.rotationAt(restored.time, Quat.identity()))
        assertTrue("displaced boat returns to sea", terrain.elevation(dir) < -5.0)
        assertEquals("boat retains its waterline", 1.0, terra.altitudeOf(moved.body.position), .001)
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
