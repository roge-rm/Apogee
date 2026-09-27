package com.rm.apogee.core.career

import com.rm.apogee.core.craft.CraftBuilder
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The career's feats earned the way a player earns them: flown, driven and
 * sailed, through the commands the game sends, from a launch site - and the
 * program told only by watching.
 */
class FeatsFlownTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** The speed a jet climbs best at, m/s. */
    private val climbSpeed = 140.0

    private fun careerWorld(): World = World.default(catalog).also { it.program = Program() }

    private fun launch(world: World, design: CraftDesign, site: String, owner: String = "p1"): Vessel =
        world.spawnFor(Command.SpawnCraft(design, site), owner).also { it.ownerName = owner }

    private fun earned(world: World, owner: String = "p1"): Map<String, Int> = world.program!!.careerOf(owner).feats

    private fun fuel(v: Vessel) = v.amountOf(ResourceType.PROPELLANT)

    /** A design put together in the builder, node by node, as a player would: [build] given the builder and an attach-at function. */
    private fun assemble(name: String, orientation: CraftOrientation, root: String, build: ((Int, String, String) -> Int) -> Unit): CraftDesign {
        val builder = CraftBuilder(catalog).also { it.orientation = orientation; it.name = name }
        check(builder.placeRoot(root))
        build { parent, node, part ->
            val target = builder.openNodes().first { it.partIndex == parent && it.node.id == node }
            builder.attach(part, target).first()
        }
        builder.restage()
        return builder.design
    }

    /** The Sparrow with a second short fuselage ahead of the first: a third more fuel, and the weight of it forward. */
    private fun longSparrow(): CraftDesign = assemble("Long Sparrow", CraftOrientation.HORIZONTAL, "cockpit-sparrow") { on ->
        val front = on(0, "bottom", "fuselage-short")
        val forward = on(front, "bottom", "fuselage-short")
        val aft = on(forward, "bottom", "fuselage-long")
        val jet = on(aft, "bottom", "engine-zephyr")
        on(aft, "side-right-fore", "wing-swept")
        on(aft, "side-left-fore", "wing-swept")
        on(jet, "surface-0", "tail-stabilator")
        on(jet, "surface-1", "tail-stabilator")
        on(jet, "surface-2", "tail-rudder")
        on(0, "surface-3", "wheel-gear-nose")
        on(aft, "belly-right-2", "wheel-gear-main")
        on(aft, "belly-left-2", "wheel-gear-main")
    }

    /**
     * The Sparrow off the airfield's runway, eastward; out over the sea
     * [out] metres, round, and back down the glide slope onto the runway
     * westward, braked to a stop. What [each] sees along the way, every tick.
     */
    private fun circuit(world: World, jet: Vessel, out: Double, cruise: Double = 0.7, limit: Double = 3_000.0): Pilot {
        val pilot = Pilot(world, jet)
        world.apply(Command.Stage(jet.id.raw))
        pilot.throttle(1.0)
        var phase = 0
        var t = 0.0
        while (t < limit) {
            val (east, _) = pilot.cape()
            when (phase) {
                // Rolling: the stick back at flying speed, let go ten metres up.
                0 -> { pilot.rotate(if (pilot.speed() > 50.0) 10.0 else null); if (pilot.height() > 10.0) phase = 1 }
                1 -> { pilot.fly(pilot.toRunway(1.0), Pilot.CRUISE); pilot.throttle(cruise); if (east > out) phase = 2 }
                2 -> if (pilot.land(-1.0, 2_000.0)) phase = 3
                3 -> { pilot.land(-1.0, 2_000.0); if (pilot.speed() < 0.2) break }
            }
            world.step(dt); t += dt
        }
        // A few looks more, at rest.
        repeat(60) { world.step(dt) }
        return pilot
    }

    // --- aviation ----------------------------------------------------------------

    @Test
    fun `the Sparrow off the runway, round, and back down on it is a First Flight`() {
        val world = careerWorld()
        val jet = launch(world, StockCraft.sparrow(catalog), "airfield")
        val pilot = circuit(world, jet, out = 12_000.0)
        val (east, north) = pilot.cape()
        assertTrue("never landed: ${pilot.speed()} m/s, ${pilot.height()} m up", pilot.speed() < 0.5 && jet.touchingGround)
        assertTrue("stopped off the runway, at $east, $north", east in Pilot.RUNWAY_WEST..Pilot.RUNWAY_EAST && kotlin.math.abs(north - Pilot.CENTRELINE) < 25.0)
        assertTrue("it broke: ${jet.broken.count { it }} parts", jet.broken.none { it })
        assertTrue("no First Flight: ${earned(world)}, log from ${jet.log?.fromRunway} on ${jet.log?.onRunway} peak ${jet.log?.peak}", Feat.FIRST_FLIGHT.id in earned(world))
        // Only twenty-odd kilometres: not yet a long haul.
        assertTrue(Feat.LONG_HAUL.id !in earned(world))
    }

    @Test
    fun `fifty kilometres out over the sea and home again is a Long Haul`() {
        val world = careerWorld()
        val jet = launch(world, StockCraft.sparrow(catalog), "airfield")
        val pilot = circuit(world, jet, out = 52_000.0, cruise = 0.55)
        assertTrue("never landed: ${pilot.speed()} m/s, ${pilot.height()} m up, fuel ${fuel(jet)}", pilot.speed() < 0.5 && jet.touchingGround)
        val feats = earned(world)
        assertTrue("no Long Haul: $feats, flown ${jet.log?.flown}, fuel left ${fuel(jet)}", Feat.LONG_HAUL.id in feats)
        assertTrue("no First Flight: $feats", Feat.FIRST_FLIGHT.id in feats)
    }

    @Test
    fun `a Sparrow with more fuel, climbed past five kilometres on its jet, is a High Flyer - and no faster than sound`() {
        val world = careerWorld()
        val jet = launch(world, longSparrow(), "airfield")
        val pilot = Pilot(world, jet)
        world.apply(Command.Stage(jet.id.raw))
        pilot.throttle(1.0)
        var airborne = false
        var t = 0.0
        while (t < 1_500.0 && Feat.HIGH_FLYER.id !in earned(world)) {
            if (!airborne) {
                pilot.rotate(if (pilot.speed() > 50.0) 10.0 else null)
                airborne = pilot.height() > 10.0
            } else if (pilot.height() < 300.0) {
                pilot.fly(pilot.toRunway(1.0), Pilot.CRUISE)
            } else {
                // Climbing at its best speed: the nose up more when faster, less when slower.
                pilot.attitude(pilot.path() + (3.0 + (pilot.speed() - climbSpeed) * 0.15).coerceIn(1.0, 8.0), pilot.track(), 0.0)
            }
            world.step(dt); t += dt
        }
        val altitude = world.attractorFor(jet).altitudeOf(jet.body.position)
        assertTrue("no High Flyer: ${earned(world)} at $altitude m, fuel ${fuel(jet)}", Feat.HIGH_FLYER.id in earned(world))
        assertTrue(altitude > Program.HIGH_FLYER)
        // Level, a jet is nowhere near the speed of sound.
        assertTrue(Feat.SUPERSONIC.id !in earned(world))
    }

    @Test
    fun `dropped from above the air, pulled out of the dive and brought down onto the runway - Supersonic, and Glide Home`() {
        val world = careerWorld()
        val terra = world.system.body("terra")
        // A hundred and twenty kilometres out along the runway's line, just above the air, gliding west.
        val up = terra.rotationAt(world.time).rotate(com.rm.apogee.core.orbit.SolarSystem.capeDirection(120_000.0, Pilot.CENTRELINE))
        val position = up.copy().mulInPlace(terra.radius + terra.atmosphereHeight + 1_000.0)
        val velocity = terra.surfaceVelocityAt(position, com.rm.apogee.core.math.Vec3())
        val west = velocity.copy().normalizeInPlace().mulInPlace(-1.0)
        velocity.addScaledInPlace(west, 300.0)
        val jet = world.spawnAt(StockCraft.sparrow(catalog), "terra", position, velocity, com.rm.apogee.core.math.quatFromTo(com.rm.apogee.core.math.Vec3.unitY(), west))
        world.assignOwner(jet, "p1")
        world.apply(Command.Stage(jet.id.raw))
        val pilot = Pilot(world, jet)
        var down = false
        var t = 0.0
        while (t < 2_000.0) {
            if (!down && terra.altitudeOf(jet.body.position) > 20_000.0) {
                // Falling: engine off, nose along the way it is going.
                pilot.throttle(0.0)
                pilot.attitude(pilot.path(), pilot.track(), 0.0)
            } else {
                down = pilot.land(-1.0, 2_000.0)
                if (down && pilot.speed() < 0.2) break
            }
            world.step(dt); t += dt
        }
        repeat(60) { world.step(dt) }
        val (east, north) = pilot.cape()
        assertTrue("it broke up: ${jet.broken.count { it }} parts", world.vessel(jet.id) != null && jet.broken.none { it })
        assertTrue("stopped off the runway, at $east, $north, ${pilot.speed()} m/s", east in Pilot.RUNWAY_WEST..Pilot.RUNWAY_EAST && kotlin.math.abs(north - Pilot.CENTRELINE) < 25.0)
        val feats = earned(world)
        assertTrue("no Supersonic: $feats", Feat.SUPERSONIC.id in feats)
        assertTrue("no Glide Home: $feats, above air ${jet.log?.aboveAir}, on runway ${jet.log?.onRunway}", Feat.GLIDE_HOME.id in feats)
    }

    // --- ground and sea -------------------------------------------------------------

    @Test
    fun `the Trundler driven round and round the airfield for ten kilometres is a Road Trip`() {
        val world = careerWorld()
        val rover = launch(world, StockCraft.rover(catalog), "cape")
        val driver = Driver(world, rover, sense = Driver.ROVER)
        val pad = com.rm.apogee.core.orbit.SolarSystem.capeDirection(0.0, 0.0)
        // Out to the runway, then round in a wide circle over it and the grass
        // either side: kept clear, and no hairpin to roll it in.
        val middle = 1_500.0 to Pilot.CENTRELINE
        val entry = middle.first to middle.second + 300.0
        var circling = false
        var t = 0.0
        while (t < 3_000.0 && Feat.ROAD_TRIP.id !in earned(world)) {
            val (east, north) = driver.offset(pad)
            val dx = middle.first - east
            val dy = middle.second - north
            if (!circling) {
                circling = kotlin.math.hypot(entry.first - east, entry.second - north) < 30.0
                driver.drive(Math.toDegrees(kotlin.math.atan2(entry.second - north, entry.first - east)), cruise = 12.0)
            } else {
                // Round the middle, three hundred metres out: along the circle, turned in by how far off it is.
                val out = kotlin.math.hypot(dx, dy)
                val along = Math.toDegrees(kotlin.math.atan2(-dy, -dx)) + 90.0 + ((out - 300.0) / 5.0).coerceIn(-45.0, 45.0)
                driver.drive(along, cruise = 12.0)
            }
            world.step(dt); t += dt
        }
        assertTrue("no Road Trip: ${earned(world)}, driven ${rover.log?.driven} m in $t s", Feat.ROAD_TRIP.id in earned(world))
        val up = rover.body.orientation.rotate(rover.design.orientation.up) dot driver.up()
        assertTrue("rolled over: up $up", world.vessel(rover.id) != null && up > 0.8)
    }

    @Test
    fun `the Trundler driven two kilometres across Luna's mare is an Off-World Rover`() {
        val world = careerWorld()
        val luna = world.system.body("luna")
        val terrain = luna.terrain!!
        // Level basalt on the mare, away from the test base.
        val start = (0 until 40).asSequence().flatMap { ring -> (0 until 8).asSequence().map { k ->
            com.rm.apogee.core.math.Vec3(
                luna.radius,
                30_000.0 + ring * 150.0 * kotlin.math.sin(k * Math.PI / 4),
                25_000.0 + ring * 150.0 * kotlin.math.cos(k * Math.PI / 4),
            ).normalizeInPlace()
        } }.first { d ->
            val h = terrain.elevation(d)
            terrain.material(d, h, 0.0) == com.rm.apogee.core.terrain.SurfaceMaterial.BASALT &&
                (0 until 8).all { a ->
                    val e = d.copy().mulInPlace(luna.radius).addInPlace(com.rm.apogee.core.math.Vec3(0.0, 6.0 * kotlin.math.sin(a * Math.PI / 4), 6.0 * kotlin.math.cos(a * Math.PI / 4))).normalizeInPlace()
                    kotlin.math.abs(terrain.elevation(e) - h) < 0.4
                }
        }
        val site = com.rm.apogee.core.world.LaunchSite("mare", "Mare", "luna", kotlin.math.asin(start.y), kotlin.math.atan2(start.z, start.x))
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), site)
        world.assignOwner(rover, "p1")
        world.seatCrew(rover)
        val driver = Driver(world, rover, sense = Driver.ROVER)
        repeat(120) { world.step(dt) }
        // Straight on the way it faces, gently: in a sixth of Terra's weight a
        // hard turn or a heavy foot puts it on its back.
        val heading = driver.heading()
        var t = 0.0
        while (t < 1_500.0 && Feat.ROVER_OFF_WORLD.id !in earned(world)) {
            driver.drive(heading, cruise = 3.0, most = 0.25)
            world.step(dt); t += dt
        }
        assertTrue("no Off-World Rover: ${earned(world)}, driven ${rover.log?.driven} m in $t s", Feat.ROVER_OFF_WORLD.id in earned(world))
        val up = rover.body.orientation.rotate(rover.design.orientation.up) dot driver.up()
        assertTrue("crashed, or rolled: up $up", world.vessel(rover.id) != null && up > 0.8)
        // Set down there, not landed: no Touchdown. And driving is not a Road Trip off Terra.
        assertTrue(Feat.TOUCHDOWN.id !in earned(world))
        assertTrue(Feat.ROAD_TRIP.id !in earned(world))
    }

    @Test
    fun `the Skiff sailed round the harbour for five kilometres and stopped there is Seaworthy`() {
        val world = careerWorld()
        val boat = launch(world, StockCraft.skiff(catalog), "harbour")
        world.apply(Command.Stage(boat.id.raw))
        val driver = Driver(world, boat, sense = Driver.BOAT)
        var t = 0.0
        while (t < 3_000.0 && (boat.log?.sailed ?: 0.0) < 5_200.0) {
            // Round and round, tiller over.
            driver.wheel(0.5)
            driver.throttle(1.0)
            world.step(dt); t += dt
        }
        assertTrue("sailed only ${boat.log?.sailed} m", (boat.log?.sailed ?: 0.0) >= 5_000.0)
        // Engine off, and let her come to rest.
        driver.wheel(0.0)
        driver.throttle(0.0)
        t = 0.0
        while (t < 300.0 && Feat.SEAWORTHY.id !in earned(world)) { world.step(dt); t += dt }
        assertTrue("no Seaworthy: ${earned(world)}, ${driver.speed()} m/s, resting ${boat.log?.resting}", Feat.SEAWORTHY.id in earned(world))
    }

    @Test
    fun `a Skiff under way on Aurantia's sea is an Alien Sea`() {
        val world = careerWorld()
        val aurantia = world.system.body("aurantia")
        val terrain = aurantia.terrain!!
        // Somewhere well out to sea.
        val sea = (0 until 2_000).asSequence().map { k ->
            val lat = kotlin.math.asin(-1.0 + 2.0 * ((k * 0.618034) % 1.0))
            val lon = 2.0 * Math.PI * ((k * 0.414214) % 1.0)
            lat to lon
        }.first { (lat, lon) -> terrain.elevation(com.rm.apogee.core.orbit.SolarSystem.surfaceDirection(lat, lon)) < -50.0 }
        val site = com.rm.apogee.core.world.LaunchSite("sea", "Sea", "aurantia", sea.first, sea.second)
        val boat = world.spawnOnSurface(StockCraft.skiff(catalog), site)
        world.assignOwner(boat, "p1")
        world.seatCrew(boat)
        world.apply(Command.Stage(boat.id.raw))
        val driver = Driver(world, boat, sense = Driver.BOAT)
        var t = 0.0
        while (t < 300.0 && Feat.ALIEN_SEA.id !in earned(world)) {
            driver.throttle(1.0)
            world.step(dt); t += dt
        }
        assertTrue("no Alien Sea: ${earned(world)}, sailed ${boat.log?.sailed} m, afloat ${boat.buoyed}, ${driver.speed()} m/s", Feat.ALIEN_SEA.id in earned(world))
    }

    // --- off Terra --------------------------------------------------------------------

    @Test
    fun `a coast past Luna, on rails, that flings it on with no engine is a Gravity Assist`() {
        val world = careerWorld()
        val luna = world.system.body("luna")
        val mu = luna.gravitationalParameter
        // Luna now, about Terra, and the plane it moves in.
        val lunaAt = world.system.positionOf("luna", world.time).subInPlace(world.system.positionOf("terra", world.time))
        val lunaGoing = world.system.velocityOf("luna", world.time).subInPlace(world.system.velocityOf("terra", world.time))
        val normal = lunaAt.cross(lunaGoing).normalizeInPlace()
        // A pass three hundred kilometres over Luna, six hundred metres a second
        // faster than Luna's own pull would have it, its low point behind Luna:
        // swung round the back of it, it is flung on the way Luna goes.
        val low = luna.radius + 300_000.0
        val out = lunaGoing.normalized().mulInPlace(-1.0)
        val along = normal.cross(out).normalizeInPlace()
        val pass = com.rm.apogee.core.orbit.Orbit(out.copy().mulInPlace(low), along.mulInPlace(kotlin.math.sqrt(600.0 * 600.0 + 2.0 * mu / low)), mu)
        // Back along it to just outside Luna's pull; there, measured from Terra.
        var back = 0.0
        while (pass.propagate(-back).position.length < luna.sphereOfInfluence * 1.02) back += 60.0
        val entry = pass.propagate(-back)
        val craft = world.spawnAt(
            StockCraft.sounder(catalog), "terra",
            entry.position.addInPlace(lunaAt), entry.velocity.addInPlace(lunaGoing), com.rm.apogee.core.math.Quat.identity(),
        )
        world.assignOwner(craft, "p1")
        val before = world.orbitOf(craft)
        // In, past, and out, as a player warps it.
        var visited = false
        var coasted = 0.0
        while (coasted < 4.0 * back + 20_000.0 && !(visited && craft.referenceBodyId == "terra")) {
            coasted += world.advanceOnRails(60.0).coerceAtLeast(1.0)
            if (craft.referenceBodyId == "luna") visited = true
        }
        assertTrue("never went by Luna", visited)
        assertTrue("never came out again", craft.referenceBodyId == "terra")
        val after = world.orbitOf(craft)
        val feats = earned(world)
        assertTrue("no Gravity Assist: $feats, orbit $before -> $after, log body '${craft.log?.flybyBody}' energy ${craft.log?.flybyEnergy} thrust ${craft.log?.flybyThrust}, coasted $coasted of $back", Feat.GRAVITY_ASSIST.id in feats)
        // And going round Luna on the way is its first orbit there... not: it never was bound to it.
        assertTrue(!world.program!!.careerOf("p1").visited("luna", Visit.ORBIT))
    }

    @Test
    fun `a probe beyond Luna, flown through a relay beside it, is a Relay`() {
        val world = careerWorld()
        val luna = world.system.body("luna")
        fun craft(root: String, extra: String) = CraftDesign(
            name = "Test $root", parts = listOf(com.rm.apogee.core.craft.PlacedPart(root, com.rm.apogee.core.math.Vec3(0.0, 0.0, 0.0)), com.rm.apogee.core.craft.PlacedPart(extra, com.rm.apogee.core.math.Vec3(0.7, 0.0, 0.0), parentIndex = 0)),
            stages = emptyList(), manualStaging = true, catalogHash = catalog.contentHash,
        )
        fun nearLuna(design: CraftDesign, offset: com.rm.apogee.core.math.Vec3): Vessel {
            val along = offset.cross(com.rm.apogee.core.math.Vec3.unitY()).normalizeInPlace().mulInPlace(kotlin.math.sqrt(luna.gravitationalParameter / offset.length))
            return world.spawnAt(design, "luna", offset, along, com.rm.apogee.core.math.Quat.identity())
        }
        val toward = world.system.positionOf("luna", world.time).subInPlace(world.system.positionOf("terra", world.time)).normalizeInPlace()
        val across = toward.cross(com.rm.apogee.core.math.Vec3.unitY()).normalizeInPlace()
        // Behind Luna, out of Terra's sight: beyond the Moon.
        val probe = nearLuna(craft("probe-mote", "antenna-reed"), toward.copy().mulInPlace(luna.radius + 50_000.0))
        world.assignOwner(probe, "p1")
        repeat(30) { world.step(dt) }
        assertTrue("heard with nothing to relay it: ${earned(world)}", Feat.RELAY.id !in earned(world))
        // A relay off to one side, in sight of both, its dish out.
        val relay = nearLuna(craft("probe-mote", "dish-beacon"), toward.copy().mulInPlace(1_000_000.0).addScaledInPlace(across, 2_000_000.0))
        world.assignOwner(relay, "p1")
        relay.control.deployed = true
        repeat((6.0 / dt).toInt()) { world.step(dt) }
        assertTrue("not relayed: ${probe.signal}", probe.signalPath.isNotEmpty())
        assertTrue("no Relay: ${earned(world)}", Feat.RELAY.id in earned(world))
    }

    @Test
    fun `the Prospector refuelled from Luna's ore, and off again, is Live off the Land`() {
        val world = careerWorld()
        val site = World.launchSites.first { it.id == World.LUNA_TEST_SITE }
        val craft = world.spawnOnSurface(StockCraft.prospector(catalog), site, pad = 1)
        world.assignOwner(craft, "p1")
        world.seatCrew(craft)
        repeat((3.0 / dt).toInt()) { world.step(dt) }
        // Come a long way: most of its propellant spent getting here.
        craft.takeFrom(craft.defs.indices.toList(), ResourceType.PROPELLANT, fuel(craft) * 0.8)
        val low = fuel(craft)
        repeat((3.0 / dt).toInt()) { world.step(dt) }
        // Taking off again before refuelling is no feat.
        world.apply(Command.SetIndustry(craft.id.raw, drilling = true, refining = true))
        repeat((180.0 / dt).toInt()) { world.step(dt) }
        assertTrue("made no propellant: $low -> ${fuel(craft)}", fuel(craft) > low + 10.0)
        assertTrue(Feat.REFUEL_OFF_WORLD.id !in earned(world))
        world.apply(Command.SetIndustry(craft.id.raw, drilling = false, refining = false))
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        // Up.
        world.stage(craft)
        world.apply(Command.SetSas(craft.id.raw, true))
        world.apply(Command.SetThrottle(craft.id.raw, 1.0))
        var t = 0.0
        while (t < 60.0 && Feat.REFUEL_OFF_WORLD.id !in earned(world)) { world.step(dt); t += dt }
        assertTrue("no Live off the Land: ${earned(world)}, ${world.attractorFor(craft).heightAboveTerrain(craft.body.position, world.attractorFor(craft).toBodyFixed(craft.body.position, world.attractorFor(craft).rotationAt(world.time)))} m up", Feat.REFUEL_OFF_WORLD.id in earned(world))
    }

    @Test
    fun `the Stilt Lander brought down beside a craft already on the mare is a Precision Landing`() {
        val world = careerWorld()
        // The Flight Computer: this is a landing for the auto-land to make.
        world.program!!.restore(listOf(CareerState("p1", nodes = listOf("flight-computer"))), emptyList())
        val site = World.launchSites.first { it.id == "luna-mare" }
        val there = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 1)
        val craft = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 2)
        world.assignOwner(craft, "p1")
        world.seatCrew(craft)
        // Two kilometres up over the next pad, falling.
        world.stage(craft)
        craft.wake()
        val up = craft.body.position.copy().normalizeInPlace()
        craft.body.position.addScaledInPlace(up, 2_000.0)
        world.attractorFor(craft).surfaceVelocityAt(craft.body.position, craft.body.linearVelocity).addScaledInPlace(up, -30.0)
        // The chute is the next stage, useless here: the legs come after it.
        world.stage(craft)
        world.apply(Command.SetAutopilot(craft.id.raw, autoBurn = false, autoLand = true))
        var t = 0.0
        while (t < 400.0 && Feat.PRECISION_LANDING.id !in earned(world)) { world.step(dt); t += dt }
        val miss = craft.body.position.distanceTo(there.body.position)
        val state = world.program!!.careerOf("p1")
        assertTrue("no Precision Landing: ${state.feats}, $miss m from the other, ${craft.control.autopilotNote}", Feat.PRECISION_LANDING.id in state.feats)
        assertTrue(Feat.TOUCHDOWN.id in state.feats)
        assertTrue(state.visited("luna", Visit.LAND))
    }

    // --- under the sea -------------------------------------------------------------------

    /** [design] afloat at body-fixed [direction] of [body], crewed, the player's. */
    private fun afloatAt(world: World, design: CraftDesign, body: String, direction: com.rm.apogee.core.math.Vec3): Vessel {
        val sub = world.spawnOnSurface(design, com.rm.apogee.core.world.LaunchSite("sea", "Sea", body,
            com.rm.apogee.core.orbit.SolarSystem.latitudeOf(direction), com.rm.apogee.core.orbit.SolarSystem.longitudeOf(direction)))
        world.assignOwner(sub, "p1")
        sub.ownerName = "p1"
        world.seatCrew(sub)
        return sub
    }

    /** A point [east] and [north] metres off body-fixed unit [from], on Terra. */
    private fun beside(from: com.rm.apogee.core.math.Vec3, east: Double, north: Double): com.rm.apogee.core.math.Vec3 {
        val e = com.rm.apogee.core.math.Vec3.unitY().crossInPlace(from).normalizeInPlace()
        val n = from.copy().crossInPlace(e).normalizeInPlace()
        return from.copy().addScaledInPlace(e, east / 600_000.0).addScaledInPlace(n, north / 600_000.0).normalizeInPlace()
    }

    @Test
    fun `the Minnow flooded down onto the floor beside the Great Arch and blown back up - Dive, Seafloor, and the Arch found`() {
        val world = careerWorld()
        world.ensureStructures()
        val arch = com.rm.apogee.core.world.SeaWonders.byId("great-arch")!!
        val minnow = afloatAt(world, StockCraft.minnow(catalog), "terra", beside(arch.direction, 60.0, 30.0))
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        world.apply(Command.SetBallast(minnow.id.raw, 1))
        var t = 0.0
        // Down until it has sat on the floor a while.
        var sat = 0.0
        while (t < 600.0 && sat < 10.0) { world.step(dt); t += dt; if (minnow.touchingGround) sat += dt }
        assertTrue("never reached the floor: ${world.depthOf(minnow)} m", sat >= 10.0)
        world.apply(Command.SetBallast(minnow.id.raw, -1))
        t = 0.0
        while (t < 600.0 && Feat.DIVE.id !in earned(world)) { world.step(dt); t += dt }
        val state = world.program!!.careerOf("p1")
        assertTrue("no Dive: ${state.feats}, at ${world.depthOf(minnow)} m", Feat.DIVE.id in state.feats)
        assertTrue("no Seafloor: ${state.feats}", Feat.SEAFLOOR.id in state.feats)
        assertTrue("the Arch not found: ${state.visits}", "wonder:great-arch" in state.visits)
        assertEquals("p1", world.program!!.firsts.first { it.bodyId == "great-arch" }.owner)
    }

    @Test
    fun `the Nautilus taken down to Farrow's flank finds the Chimneys and their vents`() {
        val world = careerWorld()
        val chimneys = com.rm.apogee.core.world.SeaWonders.byId("chimneys")!!
        // Upstream of them: going down, the planet's turn carries it east,
        // a hundred and more metres by the time it is that deep.
        val sub = afloatAt(world, StockCraft.nautilus(catalog), "terra", beside(chimneys.direction, -130.0, 0.0))
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        world.apply(Command.SetBallast(sub.id.raw, 1))
        var t = 0.0
        while (t < 1_200.0 && Feat.VENTS.id !in earned(world)) { world.step(dt); t += dt }
        val state = world.program!!.careerOf("p1")
        assertTrue("no Vents: ${state.feats}, ${world.depthOf(sub)} m down", Feat.VENTS.id in state.feats)
        assertTrue("the Chimneys not found: ${state.visits}", "wonder:chimneys" in state.visits)
        assertTrue("crushed on the way", world.vessel(sub.id) != null && sub.health.all { it >= 1.0 })
    }

    @Test
    fun `a submarine under Aurantia's sea is an Alien Deep`() {
        val world = careerWorld()
        val site = com.rm.apogee.core.orbit.SolarSystem.surfaceDirection(Math.toRadians(com.rm.apogee.core.world.SeaWonders.KRAKEN_LAT), Math.toRadians(com.rm.apogee.core.world.SeaWonders.KRAKEN_LON))
        val sub = afloatAt(world, StockCraft.minnow(catalog), "aurantia", site)
        world.apply(Command.SetBallast(sub.id.raw, 1))
        var t = 0.0
        while (t < 300.0 && Feat.ALIEN_DEEP.id !in earned(world)) { world.step(dt); t += dt }
        assertTrue("no Alien Deep: ${earned(world)}, ${world.depthOf(sub)} m down", Feat.ALIEN_DEEP.id in earned(world))
    }
}
