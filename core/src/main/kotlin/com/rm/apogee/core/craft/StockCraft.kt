package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.StockParts

/**
 * Reference craft, built in code.
 *
 * These exist so the simulation had something real to fly before the builder existed, and so the
 * headless ascent test has a fixed subject. If thrust, drag or staging breaks, it shows up as this
 * craft failing to make orbit, which is a much clearer signal than a number moving in a unit test.
 *
 * Layout rules: +Y is up, the design origin is at the very bottom of the stack, and the tree is
 * rooted at the command pod with everything hanging below it. Rooting at the pod is what makes
 * staging work. Separating the part of the tree below a decoupler throws away the spent stage and
 * leaves the crew flying, not the other way round.
 */
object StockCraft {

    /**
     * How far a ladder stands off the axis of a 1.25 m tank, in metres, so its rails sit on the
     * skin.
     */
    private const val LADDER_RADIUS = 0.675


    /**
     * The Sounder: what a career's starting kit can build and nothing more. It's a pod on a parting
     * ring, two small tanks and an Ember, four fins and a chute. A career isn't given it, but it's
     * the first thing you'd build, and the balance is worked out from it. Go up, let the spent
     * stage go, and come down under the canopy, and you get the Hop and Staging feats in one
     * flight.
     */
    fun sounder(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()
        fun add(partId: String, y: Double, parent: Int, x: Double = 0.0, z: Double = 0.0): Int {
            parts.add(PlacedPart(partId = partId, position = Vec3(x, y, z), rotation = Quat.identity(), parentIndex = parent))
            return parts.size - 1
        }
        val pod = add("pod-halo", 2.2, -1)
        val chute = add("chute-canopy", 3.0, pod)
        val ring = add("decoupler-ring", 1.5, pod)
        val upperTank = add("tank-cask2", 0.4, ring)
        val lowerTank = add("tank-cask2", -1.6, upperTank)
        val engine = add("engine-ember", -3.3, lowerTank)
        val finRadius = 0.975
        add("fin-vane", -1.6, lowerTank, x = finRadius)
        add("fin-vane", -1.6, lowerTank, x = -finRadius)
        add("fin-vane", -1.6, lowerTank, z = finRadius)
        add("fin-vane", -1.6, lowerTank, z = -finRadius)
        faceOutward(parts, catalog)
        return CraftDesign(
            name = "Sounder",
            parts = parts,
            stages = listOf(Stage(listOf(engine)), Stage(listOf(ring)), Stage(listOf(chute))),
            catalogHash = catalog.contentHash,
        )
    }

    /**
     * A two-stage launcher sized to reach a ~100 km orbit with some margin.
     *
     * It has roughly 3.5 km/s of delta-v against the ~3.4 km/s the homeworld needs, with a liftoff
     * thrust-to-weight of about 1.4.
     */
    fun starterRocket(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()

        fun add(partId: String, y: Double, parent: Int, x: Double = 0.0, z: Double = 0.0): Int {
            parts.add(
                PlacedPart(
                    partId = partId,
                    position = Vec3(x, y, z),
                    rotation = Quat.identity(),
                    parentIndex = parent,
                )
            )
            return parts.size - 1
        }

        // Built top down so the pod can be the root, but placed in the bottom-up coordinates the
        // stack actually sits in.
        val pod = add("pod-halo", 15.6, -1)
        add("chute-canopy", 16.4, pod)
        // A shield under the pod and a ring under that, so the pod comes home on its own, shield
        // first.
        val shield = add("shield-halo", 14.9, pod)
        val podRing = add("decoupler-ring", 14.7, shield)
        // The long tank on the upper stage. From the coast, at sea level, the short one reached
        // orbit with almost nothing left.
        val upperTank = add("tank-cask4", 12.6, podRing)
        val upperEngine = add("engine-vesper", 10.1, upperTank)
        val decoupler = add("decoupler-ring", 9.5, upperEngine)
        val lowerTankTop = add("tank-cask4", 7.4, decoupler)
        val lowerTankMid = add("tank-cask4", 3.4, lowerTankTop)
        val lowerTankBottom = add("tank-cask4", -0.6, lowerTankMid)
        // Below the tank, not inside it. The Ember is 1.4m tall and the bottom tank ends at -2.6,
        // so its centre belongs at -3.3. At -2.0 all but the last ten centimetres of the engine was
        // buried in the tank above it, which is what made the bottom of the stack look wrong.
        val mainEngine = add("engine-ember", -3.3, lowerTankBottom)

        // Fins low on the stack, well behind the centre of mass, which is what makes them stabilise
        // the rocket instead of the opposite.
        val finRadius = 0.975
        add("fin-vane", -0.6, lowerTankBottom, x = finRadius)
        add("fin-vane", -0.6, lowerTankBottom, x = -finRadius)
        add("fin-vane", -0.6, lowerTankBottom, z = finRadius)
        add("fin-vane", -0.6, lowerTankBottom, z = -finRadius)

        val chute = 1

        val stages = listOf(
            // Light the lifter.
            Stage(listOf(mainEngine)),
            // Separate the spent first stage and light the vacuum engine, both in one stage, so
            // there's no coasting gap.
            Stage(listOf(decoupler, upperEngine)),
            // Let go of the upper stage, and the pod goes home behind its shield.
            Stage(listOf(podRing)),
            // The chute, for the last part of the way down.
            Stage(listOf(chute)),
        )

        faceOutward(parts, catalog)

        return CraftDesign(
            name = "Starter I",
            parts = parts,
            stages = stages,
            catalogHash = catalog.contentHash,
        )
    }

    /**
     * The Starter I's lifter with nobody aboard: a Mote Probe Core on a Vesper upper stage, two Sun
     * Wings and a Whip Antenna. It can only be flown while it can hear home. The stages are the
     * lifter, then the first stage let go and the Vesper lit, then the wings out.
     */
    fun moteProbe(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()
        fun add(partId: String, y: Double, parent: Int, x: Double = 0.0, z: Double = 0.0): Int {
            parts.add(PlacedPart(partId, Vec3(x, y, z), Quat.identity(), parentIndex = parent))
            return parts.size - 1
        }
        val core = add("probe-mote", 14.8, -1)
        val upperTank = add("tank-cask4", 12.6, core)
        val upperEngine = add("engine-vesper", 10.1, upperTank)
        val decoupler = add("decoupler-ring", 9.5, upperEngine)
        val lowerTankTop = add("tank-cask4", 7.4, decoupler)
        val lowerTankMid = add("tank-cask4", 3.4, lowerTankTop)
        val lowerTankBottom = add("tank-cask4", -0.6, lowerTankMid)
        val mainEngine = add("engine-ember", -3.3, lowerTankBottom)
        val finRadius = 0.975
        add("fin-vane", -0.6, lowerTankBottom, x = finRadius)
        add("fin-vane", -0.6, lowerTankBottom, x = -finRadius)
        add("fin-vane", -0.6, lowerTankBottom, z = finRadius)
        add("fin-vane", -0.6, lowerTankBottom, z = -finRadius)
        // Wings high on the upper tank, hanging down it while folded.
        val wings = listOf(
            add("wing-kite", 14.2, upperTank, x = 0.725),
            add("wing-kite", 14.2, upperTank, x = -0.725),
        )
        add("antenna-reed", 13.8, upperTank, z = 0.675)
        faceOutward(parts, catalog)
        return CraftDesign(
            name = "Mote Probe",
            parts = parts,
            stages = listOf(
                Stage(listOf(mainEngine)),
                Stage(listOf(decoupler, upperEngine)),
                Stage(wings),
            ),
            manualStaging = true,
            catalogHash = catalog.contentHash,
        )
    }

    /**
     * A mining lander that doesn't need a base. It's the Stilt Lander's engine, tank and legs under
     * an Ore Bin, a Water Tank, a Small Converter and the pod, with an Auger Drill low on the tank,
     * four Kite Sun Wings and a battery pack. Set it down on ice or rock, dig, refine, and fly on.
     * The wings come out with DEPLOY, not by staging, because they tear off in air.
     */
    fun prospector(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()
        fun add(partId: String, y: Double, parent: Int, x: Double = 0.0, z: Double = 0.0): Int {
            parts.add(PlacedPart(partId, Vec3(x, y, z), Quat.identity(), parentIndex = parent))
            return parts.size - 1
        }
        // Bottom up from y = 0: engine 0-1, tank 1-3, ore 3-4.5, water 4.5-6, converter 6-7.2, pod
        // 7.2-8.4, chute on top.
        val pod = add("pod-halo", 7.8, -1)
        val chute = add("chute-canopy", 8.6, pod)
        val converter = add("converter-small", 6.6, pod)
        val water = add("tank-water", 5.25, converter)
        val ore = add("bin-ore", 3.75, water)
        val tank = add("tank-cask2", 2.0, ore)
        val engine = add("engine-vesper", 0.5, tank)
        // Legs like the Stilt Lander's, where they reach below the bell.
        for ((x, z) in listOf(1.0 to 0.0, -1.0 to 0.0, 0.0 to 1.0, 0.0 to -1.0)) add("leg-stilt", 0.2, tank, x = x, z = z)
        val ladderOut = LADDER_RADIUS / kotlin.math.sqrt(2.0)
        add("ladder-rung", 1.5, tank, x = -ladderOut, z = ladderOut)
        // The drill between two legs, low down, with its bit reaching the ground.
        val diagonal = 0.775 / kotlin.math.sqrt(2.0)
        add("drill-auger", 1.3, tank, x = diagonal, z = diagonal)
        // Wings high on the water tank, hanging down the stack while folded.
        for ((x, z) in listOf(0.725 to 0.0, -0.725 to 0.0, 0.0 to 0.725, 0.0 to -0.725)) add("wing-kite", 5.9, water, x = x, z = z)
        val packOut = (0.625 + 0.125) / kotlin.math.sqrt(2.0)
        add("battery-hoard", 3.75, ore, x = -packOut, z = -packOut)
        faceOutward(parts, catalog)
        return CraftDesign(
            name = "Prospector",
            parts = parts,
            stages = listOf(
                Stage(listOf(engine)),
                Stage(listOf(chute)),
                Stage(parts.indices.filter { parts[it].partId == "leg-stilt" }),
            ),
            catalogHash = catalog.contentHash,
        )
    }

    /** The Mote Probe with a Survey Scanner, for a low orbit over the poles. */
    fun surveyor(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val probe = moteProbe(catalog)
        val upperTank = probe.parts.indexOfFirst { it.partId == "tank-cask4" }
        val parts = ArrayList(probe.parts)
        parts.add(PlacedPart("scanner-survey", Vec3(0.0, 13.8, -0.75), Quat.identity(), parentIndex = upperTank))
        // So it gets through the night side of a low orbit on what it stored during the day.
        val pack = (0.625 + 0.125) / kotlin.math.sqrt(2.0)
        parts.add(PlacedPart("battery-hoard", Vec3(pack, 12.0, pack), Quat.identity(), parentIndex = upperTank))
        faceOutward(parts, catalog)
        return probe.copy(name = "Surveyor", parts = parts)
    }

    /**
     * A lander: engine, tank, pod, chute and four sprung legs.
     *
     * This is deliberately not the starter rocket with legs bolted on. Arriving is a different
     * problem from leaving, and this is the craft the descent test flies. It has a wide footprint,
     * gear that reaches below the engine bell, and enough propellant for a retro burn but not for
     * orbit.
     */
    fun lander(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()

        fun add(partId: String, y: Double, parent: Int, x: Double = 0.0, z: Double = 0.0): Int {
            parts.add(
                PlacedPart(
                    partId = partId,
                    position = Vec3(x, y, z),
                    rotation = Quat.identity(),
                    parentIndex = parent,
                )
            )
            return parts.size - 1
        }

        // The bottom of the stack is y = 0: engine 0.0-1.0, tank 1.0-3.0, pod 3.0-4.2, chute on
        // top.
        val pod = add("pod-halo", 3.6, -1)
        val chute = add("chute-canopy", 4.4, pod)
        val tank = add("tank-cask2", 2.0, pod)
        val engine = add("engine-vesper", 0.5, tank)

        // Feet at y = -0.6. That's further below the engine bell at 0.0 than the suspension's 0.4 m
        // of travel, so the leg can compress all the way and the bell still clears the ground by
        // 0.2 m. Gear that reaches less far than it compresses is just decoration. The first firm
        // landing bottoms the springs out and puts the engine in the dirt anyway, which is exactly
        // what the first version of this craft did.
        //
        // A metre out from the axis each way gives a two-metre footprint. The footprint is what
        // stops a lander tipping over. The legs' strength only decides whether it survives the
        // arrival.
        val legReach = 1.0
        val legHeight = 0.2
        add("leg-stilt", legHeight, tank, x = legReach)
        add("leg-stilt", legHeight, tank, x = -legReach)
        add("leg-stilt", legHeight, tank, z = legReach)
        add("leg-stilt", legHeight, tank, z = -legReach)
        // Between two legs, from near the ground up to the pod, as the way back in.
        val ladderOut = LADDER_RADIUS / kotlin.math.sqrt(2.0)
        add("ladder-rung", 1.5, tank, x = -ladderOut, z = ladderOut)

        val stages = listOf(
            Stage(listOf(engine)),
            Stage(listOf(chute)),
            // Gear last, because it's the last thing you want out.
            Stage(parts.indices.filter { catalog[parts[it].partId]?.id == "leg-stilt" }),
        )

        faceOutward(parts, catalog)

        return CraftDesign(
            name = "Stilt Lander",
            parts = parts,
            stages = stages,
            catalogHash = catalog.contentHash,
        )
    }

    /**
     * To Luna and down onto it. It's a Forge first stage on two Broad Cask-8s, an Ember upper stage
     * on a Broad Cask-4 that finishes the climb to orbit and sends it on its way, and the Stilt
     * Lander on top under a Shroud, to brake into orbit around Luna and set down there. It's a
     * one-way trip.
     *
     * The stages are: the Forge, then the first stage let go and the Ember lit, then the Shroud
     * opened, then the lander let go and lit, then its chute, then its legs.
     */
    fun moonshot(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val lander = lander(catalog)
        val parts = ArrayList<PlacedPart>()
        fun add(partId: String, y: Double, parent: Int, x: Double = 0.0, z: Double = 0.0, rotation: Quat = Quat.identity()): Int {
            parts.add(PlacedPart(partId, Vec3(x, y, z), rotation, parentIndex = parent))
            return parts.size - 1
        }
        // The lander as it is, lifted onto the stack. Its parts go first so its pod is the root,
        // and everything below hangs from its engine.
        val lift = 25.3
        for (p in lander.parts) parts.add(p.copy(position = Vec3(p.position.x, p.position.y + lift, p.position.z)))
        val landerEngine = lander.parts.indexOfFirst { it.partId == "engine-vesper" }
        val landerChute = lander.parts.indexOfFirst { it.partId == "chute-canopy" }
        val landerLegs = lander.parts.indices.filter { lander.parts[it].partId == "leg-stilt" }
        // Sun Panels around the lander's tank between its legs, and a Battery Pack under one. It's
        // seven hours out to Luna, and without them its pod and assist would run it flat long
        // before it got there.
        val landerTank = lander.parts.indexOfFirst { it.partId == "tank-cask2" }
        val diagonal = 0.655 / kotlin.math.sqrt(2.0)
        for ((x, z) in listOf(1.0 to 1.0, -1.0 to 1.0, -1.0 to -1.0, 1.0 to -1.0)) {
            add("panel-glint", 2.0 + lift + 0.3, landerTank, x = x * diagonal, z = z * diagonal)
        }
        val packOut = (0.625 + 0.125) / kotlin.math.sqrt(2.0)
        add("battery-hoard", 2.0 + lift - 0.7, landerTank, x = packOut, z = packOut)
        faceOutward(parts, catalog)
        val release = add("decoupler-ring", 25.2, landerEngine)
        val taper = add("adapter-taper", 24.5, release)
        // The Shroud's ring, with its shell standing around everything above it.
        val shroud = add("fairing-base", 23.8, taper)
        val upperTank = add("tank-broad4", 21.7, shroud)
        val upperEngine = add("engine-ember", 19.0, upperTank)
        // The first stage hangs from the upper tank, not from the engine between them. The whole
        // climb's thrust goes up through this joint, and an engine's narrow mount tore off under
        // it.
        val staging = add("decoupler-broad", 18.15, upperTank)
        val tankA = add("tank-broad8", 14.0, staging)
        val tankB = add("tank-broad8", 6.0, tankA)
        val forge = add("engine-forge", 1.0, tankB)
        return CraftDesign(
            name = "Moonshot",
            parts = parts,
            stages = listOf(
                Stage(listOf(forge)),
                Stage(listOf(staging, upperEngine)),
                Stage(listOf(shroud)),
                Stage(listOf(release, landerEngine)),
                Stage(listOf(landerChute)),
                Stage(landerLegs),
            ),
            manualStaging = true,
            catalogHash = catalog.contentHash,
        )
    }

    /**
     * A lander with thruster blocks, the craft a base gets built from.
     *
     * This is deliberately not the plain [lander] with thrusters bolted on. That craft is what the
     * landing tests measure against, and adding a couple of hundred kilograms to it moved every
     * drop they check. That's a fair warning that gear margins are thin, and no reason to make the
     * test craft and the working vehicle the same thing.
     */
    fun moduleTug(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val base = lander(catalog)
        val parts = ArrayList(base.parts)
        val tank = parts.indexOfFirst { it.partId == "tank-cask2" }

        // Around the tank, opposite each other. Where they sit doesn't decide the torque (see
        // Forces.applyRcs), but it does decide where they're in the way, and out here they clear
        // the legs.
        for ((x, z) in listOf(0.78 to 0.0, -0.78 to 0.0, 0.0 to 0.78, 0.0 to -0.78)) {
            parts.add(
                PlacedPart(
                    partId = "rcs-nudge",
                    position = Vec3(x, 2.0, z),
                    rotation = Quat.identity(),
                    parentIndex = tank,
                )
            )
        }

        faceOutward(parts, catalog)

        return CraftDesign(
            name = "Stilt Tug",
            parts = parts,
            stages = base.stages,
            catalogHash = catalog.contentHash,
        )
    }

    /**
     * A rover from the first part of the land kit: a small chassis, an open seat and four tread
     * wheels, with the front pair steering. It's low and wide. It used to be a pod standing on four
     * wheels, and it rolled over in any hard turn and on the first rock on Luna.
     *
     * It has no engine and no stages. Driving is the throttle working through the wheels instead of
     * through a bell, which is the whole point being tested: a vehicle class is a different bag of
     * modules, not a different simulation.
     */
    fun rover(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Trundler", CraftOrientation.HORIZONTAL)
        val chassis = a.root("chassis-small")
        a.on(chassis, "deck-front", "cab-open")
        for (k in 1..4) a.on(chassis, "wheel-$k", "wheel-tread")
        return a.design()
    }

    /**
     * An aeroplane: a fuselage with wings, an air-breathing engine, and wheels to take off from.
     *
     * It's built [CraftOrientation.HORIZONTAL], so it launches lying on the ground facing east
     * instead of standing on its tail. +Y is still the nose, because a plane is a stack flown on
     * its side, not a new kind of object, and +Z is the sky, which is where the fin goes. The wings
     * are the stock [AeroSurface] module with a wing's area instead of a fin's.
     */
    fun aeroplane(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()

        fun add(partId: String, y: Double, parent: Int, x: Double = 0.0, z: Double = 0.0): Int {
            parts.add(
                PlacedPart(partId, Vec3(x, y, z), Quat.identity(), parentIndex = parent)
            )
            return parts.size - 1
        }

        val pod = add("pod-halo", 4.0, -1)
        val tank = add("tank-cask2", 2.4, pod)
        val engine = add("engine-zephyr", 0.6, tank)

        // Wings on either side of the tank, near the centre of mass, so the craft doesn't pitch the
        // moment it makes lift.
        add("wing-plank", 2.4, tank, x = 2.2)
        add("wing-plank", 2.4, tank, x = -2.2)

        // A tail fin on top for yaw stability. It does the same job it does on the rocket, and it
        // doesn't move. It's turned so its root faces down into the hull, which is the turn the
        // builder would give it on that node.
        add("fin-vane", 0.4, engine, z = 0.975)
        parts[parts.size - 1] = parts.last().copy(
            rotation = Quat.fromAxisAngle(Vec3.unitY(), -Math.PI / 2.0),
        )

        // Elevons well behind the wings. Being behind the centre of mass is what makes them pitch
        // the aircraft instead of rolling it. Nothing in the part says "elevator".
        add("tail-elevon", 0.4, engine, x = 1.525)
        add("tail-elevon", 0.4, engine, x = -1.525)

        // Undercarriage on the lower quarters, where the builder puts it: a pair under the pod and
        // a pair just behind the centre of mass, all at the same depth so it sits level. The main
        // wheels go *just* behind the balance point, far enough that it doesn't sit back on its
        // tail, and close enough that the elevons can lift the nose at take-off speed. At 0.4 m
        // behind, rotating meant lifting fifteen kilonewton-metres of the craft's own weight, which
        // was twice what the controls give at sixty metres a second, and it ran the whole runway
        // before the wing alone could lift it.
        val quarter = 0.625 * kotlin.math.sqrt(0.5) + 0.3 * kotlin.math.sqrt(0.5)
        for (side in listOf(1.0, -1.0)) {
            add("wheel-gear", 4.0, pod, x = side * quarter, z = -quarter)
            add("wheel-gear", 2.1, tank, x = side * quarter, z = -quarter)
        }

        faceOutward(parts, catalog)

        return CraftDesign(
            name = "Plank",
            parts = parts,
            stages = listOf(Stage(listOf(engine))),
            catalogHash = catalog.contentHash,
            orientation = CraftOrientation.HORIZONTAL,
        )
    }

    /**
     * A boat: a hull, a pod at the bow, and an air-breathing engine at the stern pushing it along.
     * It's an airboat, which is a real thing and doesn't need any part this game doesn't already
     * have. The only boat part here is the hull, and the hull is just a box that floats. Staying
     * upright and going straight both come from where the water pushes on it.
     */
    fun boat(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()

        fun add(partId: String, y: Double, parent: Int, parentNode: String?, ownNode: String?): Int {
            parts.add(
                PlacedPart(
                    partId, Vec3(0.0, y, 0.0), Quat.identity(), parentIndex = parent,
                    parentNodeId = parentNode, ownNodeId = ownNode,
                )
            )
            return parts.size - 1
        }

        val hull = add("hull-punt", 0.0, -1, null, null)
        // The pod at the bow, riding the hull's top node.
        add("pod-halo", 4.6, hull, "top", "bottom")
        // Fuel and engine at the stern, so the weight is spread along the hull instead of piled at
        // one end.
        val tank = add("tank-cask2", -5.0, hull, "bottom", "top")
        val engine = add("engine-zephyr", -6.8, tank, "bottom", "top")

        faceOutward(parts, catalog)

        return CraftDesign(
            name = "Punt",
            parts = parts,
            stages = listOf(Stage(listOf(engine))),
            catalogHash = catalog.contentHash,
            orientation = CraftOrientation.HORIZONTAL,
        )
    }

    /**
     * Turns every surface-mounted part that's still at its default rotation to face out from the
     * stack, the way the builder would have turned it: around the stack axis, toward the side it's
     * on. A fin, a leg, a wheel or a wing reaches out from its root along its own +X. Left
     * unturned, all four fins of a rocket pointed the same way and a left wing's tip sat against
     * the fuselage. It turns around the axis instead of using the shortest turn, because for a part
     * on the -X side that's a half turn around an arbitrary axis, which often flips a wing upside
     * down and back to front.
     */
    private fun faceOutward(parts: MutableList<PlacedPart>, catalog: PartCatalog): Boolean {
        var changed = false
        for (i in parts.indices) {
            val placed = parts[i]
            val def = catalog[placed.partId] ?: continue
            if (def.attachNodes.none { it.kind == com.rm.apogee.core.part.AttachNodeKind.SURFACE }) continue
            if (placed.rotation != Quat.identity()) continue
            val parent = parts.getOrNull(placed.parentIndex) ?: continue
            val dx = placed.position.x - parent.position.x
            val dz = placed.position.z - parent.position.z
            if (kotlin.math.hypot(dx, dz) < 1e-6) continue
            val rotation = Quat.fromAxisAngle(Vec3.unitY(), kotlin.math.atan2(-dz, dx))
            if (rotation.approxEqualsRotation(Quat.identity())) continue
            parts[i] = placed.copy(rotation = rotation)
            changed = true
        }
        return changed
    }

    /**
     * [design] with its surface parts turned to face outward. This is for stock designs saved by
     * older builds, whose fins and legs all pointed the same way. Anything the builder placed
     * already faces outward and is left alone.
     */
    fun facingOutward(design: CraftDesign, catalog: PartCatalog): CraftDesign {
        val parts = design.parts.toMutableList()
        val faced = if (faceOutward(parts, catalog)) design.copy(parts = parts) else design
        // And anything on an opposite node rolled the right way up.
        return Attachment.settled(faced, catalog)
    }

    // --- craft from the vehicle kits -----------------------------------------
    //
    // These are put together through the builder, node by node, exactly like a player would. So
    // each one is something that can actually be built, and its parts sit where the builder would
    // put them.

    private class Assembly(catalog: PartCatalog, name: String, orientation: CraftOrientation) {
        val builder = CraftBuilder(catalog).also {
            it.orientation = orientation
            it.name = name
        }

        fun root(partId: String): Int {
            check(builder.placeRoot(partId)) { "could not place $partId" }
            return 0
        }

        /** Attaches [partId] at node [nodeId] of part [parent] and returns the new part's index. */
        fun on(parent: Int, nodeId: String, partId: String): Int {
            val target = builder.openNodes().firstOrNull { it.partIndex == parent && it.node.id == nodeId }
                ?: error("${builder.design.parts[parent].partId} has no open node $nodeId")
            val added = builder.attach(partId, target)
            check(added.isNotEmpty()) { "$partId would not go on $nodeId" }
            return added.first()
        }

        fun design(): CraftDesign {
            builder.restage()
            return builder.design
        }
    }

    /**
     * A light jet from the aircraft kit: cockpit, fuselage, a jet at the tail, swept wings with
     * ailerons, a tailplane and rudder, and tricycle gear with the main wheels just behind the
     * balance point.
     */
    fun sparrow(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Sparrow", CraftOrientation.HORIZONTAL)
        val cockpit = a.root("cockpit-sparrow")
        val forward = a.on(cockpit, "bottom", "fuselage-short")
        val aft = a.on(forward, "bottom", "fuselage-long")
        val jet = a.on(aft, "bottom", "engine-zephyr")
        // Wings on the front station, where it flies trimmed with the tail neutral. At the middle
        // of the fuselage the nose hung so heavy that the tail could barely lift it, and if you let
        // go it dived into the ground. One station further forward and it pitched up by itself and
        // looped.
        a.on(aft, "side-right-fore", "wing-swept")
        a.on(aft, "side-left-fore", "wing-swept")
        // An all-moving tail. Elevons alone couldn't lift the nose until the wings did it for them,
        // at a hundred metres a second.
        a.on(jet, "surface-0", "tail-stabilator")
        a.on(jet, "surface-1", "tail-stabilator")
        a.on(jet, "surface-2", "tail-rudder")
        a.on(cockpit, "surface-3", "wheel-gear-nose")
        // Main wheels on the belly station just behind the balance point, which is a little ahead
        // of the middle of the long fuselage. That's close enough behind it for the tail to lift
        // the nose at flying speed.
        a.on(aft, "belly-right-2", "wheel-gear-main")
        a.on(aft, "belly-left-2", "wheel-gear-main")
        return a.design()
    }

    /** A four-wheeled buggy from the land kit: chassis, cab and cargo rack. */
    fun buggy(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Buggy", CraftOrientation.HORIZONTAL)
        val chassis = a.root("chassis-small")
        a.on(chassis, "deck-front", "cab-rover")
        a.on(chassis, "deck-rear", "rack-cargo")
        for (k in 1..4) a.on(chassis, "wheel-$k", "wheel-tread")
        return a.design()
    }

    /** A six-wheeled hauler from the land kit, on big crawler wheels. */
    fun hauler(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Hauler", CraftOrientation.HORIZONTAL)
        val chassis = a.root("chassis-large")
        a.on(chassis, "deck-front", "cab-rover")
        a.on(chassis, "deck-rear", "rack-cargo")
        a.on(chassis, "deck", "light-bar")
        for (k in 1..6) a.on(chassis, "wheel-$k", "wheel-large")
        // A winch on the front, for getting out of trouble.
        a.on(chassis, "front", "winch-drum")
        return a.design()
    }

    /**
     * A small V-hulled boat from the boat kit: a seat, an outboard, and a mooring clamp on each
     * side.
     */
    fun skiff(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Skiff", CraftOrientation.HORIZONTAL)
        val hull = a.root("hull-skiff")
        a.on(hull, "deck", "cab-open")
        // A skeg underneath. Without it, turning hard at full throttle, the motor's push rolled her
        // past seventy degrees.
        a.on(hull, "keel-front", "keel-skeg")
        a.on(hull, "transom", "motor-outboard")
        a.on(hull, "side-right", "mooring-clamp")
        a.on(hull, "side-left", "mooring-clamp")
        return a.design()
    }

    /**
     * A small sailing boat: a skiff's hull with a sloop rig in front of the seat, a keel under the
     * middle and another further aft to stop her sliding sideways, and a rudder at the stern. No
     * motor, so the throttle is her sheet.
     */
    fun sloop(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Sloop", CraftOrientation.HORIZONTAL)
        val hull = a.root("hull-skiff")
        a.on(hull, "deck", "cab-open")
        a.on(hull, "mast", "sail-sloop")
        a.on(hull, "keel-front", "keel-skeg")
        a.on(hull, "keel", "keel-skeg")
        a.on(hull, "stern", "rudder")
        a.on(hull, "side-right", "mooring-clamp")
        a.on(hull, "side-left", "mooring-clamp")
        return a.design()
    }

    // --- rotors, and lighter than air ----------------------------------------------

    /**
     * A light helicopter: a bubble cockpit for two, a rotor on its roof, a fuel tank and a tail boom
     * behind it with a tail rotor at the end, and skids to stand on.
     */
    fun hummingbird(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Hummingbird", CraftOrientation.HORIZONTAL)
        val cabin = a.root("cockpit-bubble")
        a.on(cabin, "spine", "rotor-main")
        a.on(cabin, "belly", "skids")
        val tank = a.on(cabin, "bottom", "fuselage-short")
        val boom = a.on(tank, "bottom", "boom-tail")
        a.on(boom, "tail-side", "rotor-tail")
        a.on(boom, "tail-fin", "tail-rudder")
        return a.design()
    }

    /** A drone: a keeper core and a battery on a cross frame, with a rotor at the end of each arm. */
    fun quad(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Quad", CraftOrientation.HORIZONTAL)
        val frame = a.root("frame-drone")
        a.on(frame, "top", "core-keeper")
        a.on(frame, "bottom", "battery-hoard")
        for (k in 1..4) a.on(frame, "arm-$k", "rotor-drone")
        return a.design()
    }

    /** A gas balloon with a basket for two. */
    fun skylark(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Skylark", CraftOrientation.VERTICAL)
        val basket = a.root("basket-wicker")
        a.on(basket, "top", "balloon-small")
        return a.design()
    }

    /**
     * An airship: a long envelope with a gondola under it and a power car behind that (a tank and a
     * propeller), fins and a rudder on its tail, and a keeper core to hold it over a spot.
     */
    fun zeppelin(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Zeppelin", CraftOrientation.HORIZONTAL)
        val envelope = a.root("envelope-airship")
        val gondola = a.on(envelope, "belly", "cockpit-bubble")
        a.on(gondola, "belly", "core-keeper")
        a.on(envelope, "tail-top", "tail-rudder")
        // The power car hangs behind the gondola, under the middle, since on the nose its weight
        // tipped the whole ship over.
        val tank = a.on(envelope, "belly-rear", "fuselage-short")
        a.on(tank, "top", "engine-prop")
        // Charge for the ballonets' pumps, topped up by the engine while it runs.
        a.on(tank, "side-right", "battery-hoard")
        a.on(tank, "side-left", "battery-hoard")
        a.on(envelope, "tail-right", "tail-stabilator")
        a.on(envelope, "tail-left", "tail-stabilator")
        return a.design()
    }

    /**
     * A platform that floats in the sky: a sky deck (a pad over a hull of light gas), a lift fan
     * under each corner, and a keeper core and batteries under the middle.
     */
    fun skyPlatform(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Sky Platform", CraftOrientation.VERTICAL)
        val deck = a.root("deck-sky")
        val core = a.on(deck, "under", "core-keeper")
        for (k in 1..4) a.on(deck, "corner-$k", "fan-lift")
        a.on(deck, "side-1", "battery-hoard")
        a.on(deck, "side-2", "battery-hoard")
        check(core > 0)
        return a.design()
    }

    /** A platform that floats on the sea: a sea deck on four pontoons, with a keeper core. */
    fun seaPlatform(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Sea Platform", CraftOrientation.VERTICAL)
        val deck = a.root("deck-sea")
        for (k in 1..4) a.on(deck, "pontoon-$k", "pontoon")
        a.on(deck, "equipment", "core-keeper")
        return a.design()
    }

    // --- submarines ---------------------------------------------------------------
    //
    // Each one has a pressure hull in the middle with a trim tank in front of it and behind it, a
    // screw at the tail, bow planes on each side and a rudder on top, and lamps under the hull
    // aimed at the floor. The deep ones also have a sonar underneath to find their way in the dark.
    // With its tanks blown it's nearly as heavy as the water it displaces, so it floats. Flooded,
    // it's a little heavier, so it sinks.
    //
    // The tanks sit on either side of the middle, as far forward as back. All of its lift is in
    // them, and with only one tank behind the hull it floated standing on its nose, and flooding or
    // blowing it would tip it again. Balanced like this, it lies level either way.

    /** The first one: a Pearl sphere between two trim tanks, good to three hundred metres. */
    fun minnow(catalog: PartCatalog = StockParts.catalog): CraftDesign = submarine(catalog, "Minnow", "pod-pearl", "ballast-trim")

    /**
     * Two aboard, down to a kilometre and a half: the Nautilus hull between two deep trim tanks.
     */
    fun nautilus(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        submarine(catalog, "Nautilus", "hull-nautilus", "ballast-deep", sonar = true)

    /**
     * Down to the bottom of the Terra Deep: the Abyss sphere between abyssal tanks, with a float
     * standing on top to hold up its weight. The float is a sail, high up, which also keeps it
     * upright.
     */
    fun abyss(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        submarine(catalog, "Abyss", "pod-abyss", "ballast-abyss", float = "float-foam", cell = "battery-abyss", sonar = true)

    private fun submarine(
        catalog: PartCatalog, name: String, hull: String, tank: String,
        float: String? = null, cell: String? = null, sonar: Boolean = false,
    ): CraftDesign {
        val a = Assembly(catalog, name, CraftOrientation.HORIZONTAL)
        val middle = a.root(hull)
        val fore = a.on(middle, "top", tank)
        val aft = a.on(middle, "bottom", tank)
        a.on(aft, "bottom", "screw-drive")
        // Bow planes on the front tank, the rudder at the back, and lamps and sonar under the hull.
        // The planes go forward to even out the water's drag along it. With them at the back next
        // to the screw and rudder, that end dragged the most, so when it sank the tail trailed up
        // and it went down nose first.
        a.on(fore, "side-right", "planes-dive")
        a.on(fore, "side-left", "planes-dive")
        a.on(aft, "spine-aft", "rudder")
        a.on(middle, "belly-right", "lamp-deep")
        a.on(middle, "belly-left", "lamp-deep")
        // To stay upright under water you want the weight low and the lift high. There's a lead
        // keel under the hull, because with everything else on its axis nothing held it level, and
        // the smallest push from its screw or planes stood it on end. The Abyss also has a float
        // standing on top. The keel's weight is also what takes it down at about a metre a second
        // when flooded. Sinking broadside, the lighter ones took half an hour to go down a
        // kilometre.
        a.on(middle, "belly", "keel-lead")
        val sail = float?.let { a.on(middle, "spine", it) }
        // A spare cell in the middle, on the float, where its weight doesn't tip anything.
        cell?.let { a.on(sail ?: middle, "spine", it) }
        if (sonar) a.on(fore, "belly", "sonar-array")
        return a.design()
    }

    /** A cutter with a keel, from the boat kit: wheelhouse, keel, rudder and outboard. */
    fun cutter(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Cutter", CraftOrientation.HORIZONTAL)
        val hull = a.root("hull-cutter")
        a.on(hull, "deck-rear", "cabin-wheelhouse")
        a.on(hull, "keel", "keel")
        a.on(hull, "keel-rear", "rudder")
        a.on(hull, "transom", "motor-outboard")
        a.on(hull, "side-right", "mooring-clamp")
        a.on(hull, "side-left", "mooring-clamp")
        return a.design()
    }

    /**
     * A working boat for bad weather: five decked hull sections, fourteen metres long, with a
     * wheelhouse in the middle and a deep keel. This is the one that rides out a storm that swamps
     * a skiff.
     */
    fun trawler(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Trawler", CraftOrientation.HORIZONTAL)
        val bow = a.root("hull-bow")
        val fore = a.on(bow, "bottom", "hull-mid")
        val mid = a.on(fore, "bottom", "hull-mid")
        val aft = a.on(mid, "bottom", "hull-mid")
        val stern = a.on(aft, "bottom", "hull-stern")
        a.on(mid, "deck", "cabin-wheelhouse")
        a.on(fore, "keel", "keel")
        a.on(aft, "keel", "keel")
        a.on(stern, "keel", "rudder")
        a.on(stern, "transom", "motor-outboard")
        a.on(mid, "side-right", "mooring-clamp")
        a.on(mid, "side-left", "mooring-clamp")
        return a.design()
    }

    // --- docking --------------------------------------------------------------

    /**
     * A lander made for building bases. Its legs are out on the diagonals with thrusters above
     * them, a standard docking ring on the side of its tank (at the same height as every other
     * tug's, so two landed side by side can be walked together ring to ring), and a small ring on
     * top for a probe.
     */
    fun portTug(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()
        fun add(partId: String, x: Double, y: Double, z: Double, parent: Int, rotation: Quat = Quat.identity()): Int {
            parts.add(PlacedPart(partId, Vec3(x, y, z), rotation, parentIndex = parent))
            return parts.size - 1
        }
        val pod = add("pod-halo", 0.0, 3.6, 0.0, -1)
        add("dock-port-small", 0.0, 4.3, 0.0, pod)
        val tank = add("tank-cask2", 0.0, 2.0, 0.0, pod)
        val engine = add("engine-vesper", 0.0, 0.5, 0.0, tank)
        // The ring sticks out past the legs, so rings meet before feet do.
        add("dock-port", 0.775, 1.5, 0.0, tank, quatFromTo(Vec3.unitY(), Vec3.unitX()))
        val d = kotlin.math.sqrt(0.5)
        val legs = ArrayList<Int>()
        for ((x, z) in listOf(d to d, -d to d, d to -d, -d to -d)) legs.add(add("leg-stilt", x, 0.2, z, tank))
        val out = 0.775 * d
        for ((x, z) in listOf(out to out, -out to out, out to -out, -out to -out)) add("rcs-nudge", x, 2.6, z, tank)
        faceOutward(parts, catalog)
        return CraftDesign(
            name = "Port Tug",
            parts = parts,
            stages = listOf(Stage(listOf(engine)), Stage(legs)),
            catalogHash = catalog.contentHash,
        )
    }

    /**
     * A pod with thrusters and a small docking ring on its nose. It's the lightest thing that can
     * dock.
     */
    fun dockProbe(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()
        parts.add(PlacedPart("pod-halo", Vec3.zero()))
        parts.add(PlacedPart("dock-port-small", Vec3(0.0, 0.7, 0.0), parentIndex = 0))
        for ((x, z) in listOf(0.62 to 0.0, -0.62 to 0.0, 0.0 to 0.62, 0.0 to -0.62)) {
            parts.add(PlacedPart("rcs-nudge", Vec3(x, -0.1, z), parentIndex = 0))
        }
        faceOutward(parts, catalog)
        return CraftDesign(name = "Dock Probe", parts = parts, stages = emptyList(), catalogHash = catalog.contentHash)
    }

    /** The land kit's buggy with a tow ball on the back. */
    fun towBuggy(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Tow Buggy", CraftOrientation.HORIZONTAL)
        val chassis = a.root("chassis-small")
        a.on(chassis, "deck-front", "cab-rover")
        a.on(chassis, "deck-rear", "rack-cargo")
        for (k in 1..4) a.on(chassis, "wheel-$k", "wheel-tread")
        a.on(chassis, "back", "hitch-ball")
        return a.design()
    }

    /** A trailer: a chassis on four wheels with a cargo rack and a drawbar to tow it by. */
    fun cart(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Cart", CraftOrientation.HORIZONTAL)
        val chassis = a.root("chassis-small")
        a.on(chassis, "deck", "rack-cargo")
        for (k in 1..4) a.on(chassis, "wheel-$k", "wheel-tread")
        a.on(chassis, "front", "hitch-coupling")
        return a.design()
    }

    // --- bases ------------------------------------------------------------------

    /**
     * The start of a base: a core on a foundation, with connectors on two sides to build out from
     * and a solar array on top. Set it down and found it, and modules brought up to its connectors
     * join it.
     */
    fun baseCore(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Base Core", CraftOrientation.VERTICAL)
        val foundation = a.root("base-foundation")
        val core = a.on(foundation, "top", "base-core")
        a.on(core, "surface-0", "base-connector")
        a.on(core, "surface-2", "base-connector")
        a.on(core, "top", "base-solar")
        return a.design()
    }

    /**
     * A base core that flies itself down. It's the core on its foundation, with connectors east and
     * west, and a descent stage built around it: a tank and engine on each of the other two sides,
     * legs on the diagonals, and thrusters on the tanks because the core has no reaction wheels.
     *
     * It's made for Luna, so the two engines are vacuum ones, each lifting a sixth of Terra's
     * weight. It lands on its legs with the foundation a hand's width off the ground and gets
     * founded where it stands. The descent stage stays on, and whatever it didn't burn becomes the
     * new base's first store.
     */
    fun baseCoreLander(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Base Core Lander", CraftOrientation.VERTICAL)
        val foundation = a.root("base-foundation")
        val core = a.on(foundation, "top", "base-core")
        a.on(core, "surface-0", "base-connector")
        a.on(core, "surface-1", "base-connector")
        a.on(core, "top", "base-solar")
        val parts = ArrayList(a.design().parts)
        // Heights measured from the underside of the foundation.
        val floor = parts[foundation].position.y - catalog.require("base-foundation").boundsHalfExtents.y
        fun add(partId: String, x: Double, y: Double, z: Double, parent: Int): Int {
            parts.add(PlacedPart(partId, Vec3(x, floor + y, z), Quat.identity(), parentIndex = parent))
            return parts.size - 1
        }
        // Tanks just clear of the edge of the foundation, and legs out on its corners.
        val tankOut = 2.75
        val legOut = 2.3
        val engines = ArrayList<Int>()
        for (side in listOf(1.0, -1.0)) {
            // Tank 1.5-5.5 m up and bell 0.5-1.5, so it clears the ground with the legs fully
            // pressed.
            val z = side * tankOut
            val tank = add("tank-cask4", 0.0, 3.5, z, core)
            engines.add(add("engine-vesper", 0.0, 1.0, z, tank))
            for (x in listOf(0.7, -0.7)) add("rcs-nudge", x, 5.1, z, tank)
        }
        // Feet a quarter metre below the foundation. Once the landing presses them, they leave it
        // close enough to the ground to be founded.
        val legs = ArrayList<Int>()
        for ((x, z) in listOf(1.0 to 1.0, -1.0 to 1.0, 1.0 to -1.0, -1.0 to -1.0)) {
            legs.add(add("leg-stilt", x * legOut, 0.55, z * legOut, foundation))
        }
        faceOutward(parts, catalog)
        return CraftDesign(
            name = "Base Core Lander",
            parts = parts,
            orientation = CraftOrientation.VERTICAL,
            stages = listOf(Stage(engines), Stage(legs)),
            catalogHash = catalog.contentHash,
        )
    }

    /** A habitat on its own foundation, with a connector on one side to join a base with. */
    fun habitatModule(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Habitat Module", CraftOrientation.VERTICAL)
        val foundation = a.root("base-foundation")
        val habitat = a.on(foundation, "top", "base-habitat")
        a.on(habitat, "surface-1", "base-connector")
        return a.design()
    }

    /**
     * A flatbed truck carrying [load]: a cab, eight big wheels, and a release clamp holding the
     * load on its own foundation. Drive it up beside where the load needs to go and stage it, and
     * the clamp lets go and the load drops onto its feet next to a base's connector, ready to be
     * pulled in and joined.
     */
    private fun flatbed(catalog: PartCatalog, name: String, load: (Assembly, Int) -> Unit): CraftDesign {
        val a = Assembly(catalog, name, CraftOrientation.HORIZONTAL)
        val bed = a.root("base-flatbed")
        a.on(bed, "cab", "cab-rover")
        for (k in 1..8) a.on(bed, "wheel-$k", "wheel-large")
        val clamp = a.on(bed, "deck", "base-release-clamp")
        val foundation = a.on(clamp, "top", "base-foundation")
        load(a, foundation)
        return a.design()
    }

    /**
     * A habitat module on a flatbed, with a connector on its right-hand side to join a base with.
     */
    fun moduleHauler(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        flatbed(catalog, "Module Hauler") { a, foundation ->
            val habitat = a.on(foundation, "top", "base-habitat")
            a.on(habitat, "surface-0", "base-connector")
        }

    /** A base core on a flatbed. Drive it out, set it down, and found it where it lands. */
    fun baseCoreHauler(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        flatbed(catalog, "Base Core Hauler") { a, foundation ->
            val core = a.on(foundation, "top", "base-core")
            a.on(core, "surface-0", "base-connector")
            a.on(core, "surface-1", "base-connector")
        }

    /**
     * A pad on its own: a deck to launch from and land on, with a depot and a power module standing
     * at two corners and a solar array on the power module. Found it where it's set down and it's a
     * base of its own.
     */
    fun padBase(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Pad Base", CraftOrientation.VERTICAL)
        val pad = a.root("base-pad")
        a.on(pad, "corner-1", "base-depot")
        val power = a.on(pad, "corner-2", "base-battery")
        a.on(power, "top", "base-solar")
        return a.design()
    }

    /** A propellant depot on a flatbed, to join to a base as its store. */
    fun depotHauler(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        flatbed(catalog, "Depot Hauler") { a, foundation ->
            val depot = a.on(foundation, "top", "base-depot")
            a.on(depot, "surface-0", "base-connector")
        }

    /** The smallest thing that counts as a craft. Used by physics tests. */
    fun probe(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        CraftDesign(
            name = "Probe",
            parts = listOf(PlacedPart("pod-halo", Vec3.zero())),
            stages = emptyList(),
            catalogHash = catalog.contentHash,
        )
}
