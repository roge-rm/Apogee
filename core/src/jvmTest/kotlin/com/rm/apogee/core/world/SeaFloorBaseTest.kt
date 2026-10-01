package com.rm.apogee.core.world

import com.rm.apogee.core.career.Feat
import com.rm.apogee.core.career.Program
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sea Floor Bases: floated out, let down onto the bottom and founded, or left loose. */
class SeaFloorBaseTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }

    private fun world(): World = World.default(catalog).also {
        it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.NORMAL)
        it.program = Program()
    }

    /** The open sea off the Cape, about fifty metres deep. */
    private val site: LaunchSite get() {
        val d = SolarSystem.capeDirection(-6_000.0, 2_000.0)
        return LaunchSite("open", "Open sea", "terra", SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d))
    }

    private fun launch(world: World, design: CraftDesign): Vessel {
        val craft = world.spawnOnSurface(design, site)
        world.assignOwner(craft, "p1")
        world.seatCrew(craft)
        return craft
    }

    /** How far its centre is above the sea floor under it, in metres. */
    private fun aboveFloor(world: World, v: Vessel): Double {
        val terra = world.attractorFor(v)
        val fixed = terra.toBodyFixed(v.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
        return v.body.position.length - terra.solidRadiusInBodyFrame(fixed)
    }

    /** Where it is on Terra, turning with it, so a save and a load can be compared. */
    private fun fixed(world: World, v: Vessel): Vec3 {
        val terra = world.attractorFor(v)
        return terra.toBodyFixed(v.body.position, terra.rotationAt(world.time), Vec3())
    }

    @Test
    fun `a Base Core let go of on the sea floor comes up and floats, instead of hanging where it fell asleep`() {
        val world = world()
        val base = launch(world, StockCraft.baseCore(catalog))
        val terra = world.attractorFor(base)
        // Put it on the floor, upright.
        val up = base.body.position.normalized()
        val fixedUp = terra.toBodyFixed(base.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
        base.body.position.setTo(up).mulInPlace(terra.solidRadiusInBodyFrame(fixedUp) + 3.0)
        terra.surfaceVelocityAt(base.body.position, base.body.linearVelocity)
        base.wake()
        run(world, 60.0)
        assertTrue("it hung ${world.depthOf(base)} m down", world.depthOf(base) < 2.0)
        assertTrue("not afloat", base.buoyed && !base.submerged)
    }

    @Test
    fun `the Sea Floor Base floats out, goes down onto the floor when its float is flooded, and is founded there`() {
        val world = world()
        val base = launch(world, StockCraft.seaFloorBase(catalog))
        run(world, 20.0)
        assertTrue("it sank at launch", world.depthOf(base) < 5.0 && base.buoyed && !base.touchingGround)
        world.apply(Command.SetBallast(base.id.raw, 1))
        var down = 0.0
        repeat(240) {
            run(world, 1.0)
            if (base.touchingGround) down++
        }
        assertTrue("never reached the floor: ${aboveFloor(world, base)} m above it, ${world.depthOf(base)} m down", down > 0 && aboveFloor(world, base) < 1.5)
        assertTrue("it broke on the way down: ${base.broken.count { it }}", base.broken.none { it })
        assertTrue(world.canAnchor(base))
        assertTrue(world.anchor(base))
        val feats = world.program!!.careerOf("p1").feats
        assertTrue("no Sea Floor Base: $feats", Feat.SEA_FLOOR_BASE.id in feats)
        assertFalse("a Sea Stead on the bottom", Feat.SEA_STEAD.id in feats)
        val where = fixed(world, base)
        run(world, 600.0)
        assertTrue("moved ${fixed(world, base).distanceTo(where)} m", fixed(world, base).distanceTo(where) < 0.5)
        assertTrue("crushed", base.broken.none { it })
        val restored = World.default(catalog)
        restored.restore(world.save())
        val again = restored.vessels.single { it.name == "Sea Floor Base" }
        run(restored, 5.0)
        assertTrue(again.anchored)
        assertTrue("moved ${fixed(restored, again).distanceTo(where)} m after a save", fixed(restored, again).distanceTo(where) < 0.5)
    }

    @Test
    fun `blown again before it's founded, it comes back up`() {
        val world = world()
        val base = launch(world, StockCraft.seaFloorBase(catalog))
        run(world, 10.0)
        world.apply(Command.SetBallast(base.id.raw, 1))
        run(world, 240.0)
        assertTrue("not down: ${aboveFloor(world, base)} m off the floor", aboveFloor(world, base) < 1.5)
        world.apply(Command.SetBallast(base.id.raw, -1))
        run(world, 240.0)
        assertTrue("still down, ${aboveFloor(world, base)} m off the floor", aboveFloor(world, base) > 30.0 && base.buoyed && !base.submerged)
    }

    @Test
    fun `hanging under water, a craft can't be founded there`() {
        val world = world()
        val base = launch(world, StockCraft.seaFloorBase(catalog))
        run(world, 10.0)
        world.apply(Command.SetBallast(base.id.raw, 1))
        // On its way down, clear of the floor.
        repeat(240) {
            run(world, 0.5)
            if (world.depthOf(base) > 20.0 && aboveFloor(world, base) > 5.0) {
                assertFalse("founded hanging ${world.depthOf(base)} m down", world.canAnchor(base))
                return
            }
        }
        assertNotNull("never got down to twenty metres", null)
    }

    @Test
    fun `the sun barely reaches a base on the sea floor`() {
        val world = World.default(catalog)
        val terra = world.system.bodies.getValue("terra")
        val up = Vec3(terra.radius, 0.0, 0.0)
        assertTrue(Power.seaShade(terra, up.copy().mulInPlace(1.0 + 10.0 / terra.radius)) == 1.0)
        val down = Power.seaShade(terra, up.copy().mulInPlace(1.0 - 50.0 / terra.radius))
        assertTrue("$down of the daylight fifty metres down", down < 0.1)
    }

    @Test
    fun `a submarine carrying a Base Connector docks at a base on the floor, and her crew come aboard it`() {
        val world = World.default(catalog).also { it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.CALM) }
        val shallow = SolarSystem.capeDirection(-3_000.0, 2_000.0)
        val base = world.spawnOnSurface(StockCraft.seaFloorBase(catalog), LaunchSite("sea", "Sea", "terra", SolarSystem.latitudeOf(shallow), SolarSystem.longitudeOf(shallow)))
        world.assignOwner(base, "p1")
        world.seatCrew(base)
        run(world, 10.0)
        world.apply(Command.SetBallast(base.id.raw, 1))
        run(world, 200.0)
        assertTrue("not founded", world.anchor(base))
        // A Nautilus with a Base Connector on her side.
        val builder = com.rm.apogee.core.craft.CraftBuilder(catalog)
        builder.load(StockCraft.nautilus(catalog))
        val hull = builder.design.parts.indexOfFirst { it.partId == "hull-nautilus" }
        builder.attach("base-connector", builder.openNodes().first { it.partIndex == hull && it.node.id == "side-right" })
        val design = builder.design
        // Set down beside the base's first connector, facing it, half a metre off.
        val basePort = base.defs.indices.first { base.defs[it].id == "base-connector" }
        val theirs = com.rm.apogee.core.physics.PortRef(base, basePort, base.defs[basePort].module<com.rm.apogee.core.part.DockingPort>()!!).update()
        val up = theirs.face.normalized()
        val subPort = design.parts.indexOfLast { it.partId == "base-connector" }
        val local = design.parts[subPort].rotation.rotate(Vec3.unitY(), Vec3())
        val upright = com.rm.apogee.core.math.quatFromTo(design.orientation.up, up)
        val facing = upright.rotate(local, Vec3()).let { it.addScaledInPlace(up, -(it dot up)).normalizeInPlace() }
        val want = theirs.axis.copy().addScaledInPlace(up, -(theirs.axis dot up)).normalizeInPlace().mulInPlace(-1.0)
        val rotation = (com.rm.apogee.core.math.quatFromTo(facing, want) * upright).normalizeInPlace()
        val target = theirs.face.copy().addScaledInPlace(theirs.axis, 0.5)
        val sub = world.spawnAt(design, "terra", target, world.attractorFor(base).surfaceVelocityAt(target, Vec3()), rotation)
        world.assignOwner(sub, "p1")
        world.seatCrew(sub)
        val mine = com.rm.apogee.core.physics.PortRef(sub, subPort, sub.defs[subPort].module<com.rm.apogee.core.part.DockingPort>()!!).update()
        sub.body.position.addInPlace(target.copy().subInPlace(mine.face))
        // Trimmed to hang where she is: tanks half full, holding her depth.
        for (i in sub.defs.indices) sub.defs[i].module<com.rm.apogee.core.part.Ballast>()?.let { sub.flooded[i] = it.volume * 1025.0 * 0.5 }
        sub.recomputeMass()
        world.apply(Command.HoldDepth(sub.id.raw, true))
        val aboard = base.crewAboard + sub.crewAboard
        run(world, 15.0)
        assertFalse("never docked", world.vessels.any { it === sub })
        assertTrue("the base isn't founded now", base.anchored)
        assertEquals("crew went missing", aboard, base.crewAboard)
    }
}
