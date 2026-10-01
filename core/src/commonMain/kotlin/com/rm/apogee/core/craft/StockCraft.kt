package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.math.Math

/**
 * Reference craft, built in code. The tests fly them too, so a physics break shows up as one
 * failing to make orbit.
 *
 * Layout: +Y is up, the origin is the bottom of the stack, and the tree is rooted at the command
 * pod, so a decoupler drops the part below it and the crew keeps flying.
 */
object StockCraft {

    /** How far a ladder stands off a 1.25 m tank's axis, in metres, to sit on the skin. */
    private const val LADDER_RADIUS = 0.675


    /**
     * The Sounder: only the career starting kit, and the craft the early balance is set from. One
     * flight earns Hop and Staging.
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

    /** A two-stage launcher for a ~100 km orbit: about 3.5 km/s against the 3.4 needed, TWR 1.4. */
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

        // Built top down so the pod is the root, in bottom-up coordinates.
        val pod = add("pod-halo", 15.6, -1)
        add("chute-canopy", 16.4, pod)
        // A shield and ring under the pod, so it comes home alone, shield first.
        val shield = add("shield-halo", 14.9, pod)
        val podRing = add("decoupler-ring", 14.7, shield)
        // The long tank upstairs; the short one barely reaches orbit from the coast.
        val upperTank = add("tank-cask4", 12.6, podRing)
        val upperEngine = add("engine-vesper", 10.1, upperTank)
        val decoupler = add("decoupler-ring", 9.5, upperEngine)
        val lowerTankTop = add("tank-cask4", 7.4, decoupler)
        val lowerTankMid = add("tank-cask4", 3.4, lowerTankTop)
        val lowerTankBottom = add("tank-cask4", -0.6, lowerTankMid)
        // The Ember is 1.4 m tall and the tank ends at -2.6, so its centre is at -3.3.
        val mainEngine = add("engine-ember", -3.3, lowerTankBottom)

        // Fins low, well behind the centre of mass, so they stabilise it.
        val finRadius = 0.975
        add("fin-vane", -0.6, lowerTankBottom, x = finRadius)
        add("fin-vane", -0.6, lowerTankBottom, x = -finRadius)
        add("fin-vane", -0.6, lowerTankBottom, z = finRadius)
        add("fin-vane", -0.6, lowerTankBottom, z = -finRadius)

        val chute = 1

        val stages = listOf(
            // Light the lifter.
            Stage(listOf(mainEngine)),
            // Drop the first stage and light the vacuum engine together, with no coast.
            Stage(listOf(decoupler, upperEngine)),
            // Drop the upper stage; the pod goes home behind its shield.
            Stage(listOf(podRing)),
            // The chute.
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
     * The Starter I's lifter uncrewed: a Mote Probe Core on a Vesper stage with two Sun Wings and a
     * Whip Antenna. Flyable only in contact with home.
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
        // Wings high on the tank, hanging down it while folded.
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
     * A mining lander that needs no base: the Stilt Lander with an Ore Bin, Water Tank, Small
     * Converter, Auger Drill, four Kite Sun Wings and a battery. Land, dig, refine, fly on. The
     * wings use DEPLOY rather than staging, since they tear off in air.
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
        // Legs like the Stilt Lander's, reaching below the bell.
        for ((x, z) in listOf(1.0 to 0.0, -1.0 to 0.0, 0.0 to 1.0, 0.0 to -1.0)) add("leg-stilt", 0.2, tank, x = x, z = z)
        val ladderOut = LADDER_RADIUS / kotlin.math.sqrt(2.0)
        add("ladder-rung", 1.5, tank, x = -ladderOut, z = ladderOut)
        // The drill low between two legs, its bit reaching the ground.
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
        // To get through the night side of a low orbit.
        val pack = (0.625 + 0.125) / kotlin.math.sqrt(2.0)
        parts.add(PlacedPart("battery-hoard", Vec3(pack, 12.0, pack), Quat.identity(), parentIndex = upperTank))
        faceOutward(parts, catalog)
        return probe.copy(name = "Surveyor", parts = parts)
    }

    /**
     * A lander: engine, tank, pod, chute and four sprung legs. The descent test flies it. Wide
     * footprint, gear below the bell, fuel for a retro burn but not orbit.
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

        // Bottom up from y = 0: engine 0-1, tank 1-3, pod 3-4.2, chute on top.
        val pod = add("pod-halo", 3.6, -1)
        val chute = add("chute-canopy", 4.4, pod)
        val tank = add("tank-cask2", 2.0, pod)
        val engine = add("engine-vesper", 0.5, tank)

        // Feet at y = -0.6, more than the 0.4 m of travel below the bell, so it still clears by
        // 0.2 m fully compressed. A metre out each way gives a two-metre footprint against tipping.
        val legReach = 1.0
        val legHeight = 0.2
        add("leg-stilt", legHeight, tank, x = legReach)
        add("leg-stilt", legHeight, tank, x = -legReach)
        add("leg-stilt", legHeight, tank, z = legReach)
        add("leg-stilt", legHeight, tank, z = -legReach)
        // Between two legs, from near the ground up to the pod.
        val ladderOut = LADDER_RADIUS / kotlin.math.sqrt(2.0)
        add("ladder-rung", 1.5, tank, x = -ladderOut, z = ladderOut)

        val stages = listOf(
            Stage(listOf(engine)),
            Stage(listOf(chute)),
            // Gear last.
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
     * One way to Luna: a Forge on two Broad Cask-8s, an Ember stage on a Broad Cask-4 for orbit and
     * the transfer, and the Stilt Lander under a Shroud to brake and land.
     */
    fun moonshot(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val lander = lander(catalog)
        val parts = ArrayList<PlacedPart>()
        fun add(partId: String, y: Double, parent: Int, x: Double = 0.0, z: Double = 0.0, rotation: Quat = Quat.identity()): Int {
            parts.add(PlacedPart(partId, Vec3(x, y, z), rotation, parentIndex = parent))
            return parts.size - 1
        }
        // The lander lifted onto the stack. Its parts go first so its pod is the root.
        val lift = 25.3
        for (p in lander.parts) parts.add(p.copy(position = Vec3(p.position.x, p.position.y + lift, p.position.z)))
        val landerEngine = lander.parts.indexOfFirst { it.partId == "engine-vesper" }
        val landerChute = lander.parts.indexOfFirst { it.partId == "chute-canopy" }
        val landerLegs = lander.parts.indices.filter { lander.parts[it].partId == "leg-stilt" }
        // Sun Panels and a Battery Pack, or it'd go flat in the seven hours to Luna.
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
        // The Shroud's ring; its shell stands round everything above.
        val shroud = add("fairing-base", 23.8, taper)
        val upperTank = add("tank-broad4", 21.7, shroud)
        val upperEngine = add("engine-ember", 19.0, upperTank)
        // Hung from the upper tank, not the engine: the whole climb's thrust goes through this
        // joint, and an engine mount tears off.
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
     * A lander with thruster blocks, for building bases. Kept apart from [lander], which the landing
     * tests are tuned to.
     */
    fun moduleTug(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val base = lander(catalog)
        val parts = ArrayList(base.parts)
        val tank = parts.indexOfFirst { it.partId == "tank-cask2" }

        // Placement doesn't set the torque (see Forces.applyRcs); out here they clear the legs.
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
     * A rover: small chassis, open seat and four tread wheels, front pair steering. Low and wide so
     * it doesn't roll. No engine or stages; the throttle drives the wheels.
     */
    fun rover(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Trundler", CraftOrientation.HORIZONTAL)
        val chassis = a.root("chassis-small")
        a.on(chassis, "deck-front", "cab-open")
        for (k in 1..4) a.on(chassis, "wheel-$k", "wheel-tread")
        return a.design()
    }

    /**
     * An aeroplane: fuselage, wings, an air-breathing engine and wheels. Built
     * [CraftOrientation.HORIZONTAL], so +Y is the nose and +Z the sky, where the fin goes.
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

        // Wings near the centre of mass, so lift doesn't pitch it.
        add("wing-plank", 2.4, tank, x = 2.2)
        add("wing-plank", 2.4, tank, x = -2.2)

        // A fixed tail fin on top for yaw stability, turned root down as the builder would.
        add("fin-vane", 0.4, engine, z = 0.975)
        parts[parts.size - 1] = parts.last().copy(
            rotation = Quat.fromAxisAngle(Vec3.unitY(), -Math.PI / 2.0),
        )

        // Elevons well behind the centre of mass, which is what makes them pitch it.
        add("tail-elevon", 0.4, engine, x = 1.525)
        add("tail-elevon", 0.4, engine, x = -1.525)

        // Gear on the lower quarters, level: a pair under the pod and a pair just behind the centre
        // of mass. Any further back and the elevons can't lift the nose at take-off speed.
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

    /** An airboat: a floating hull, a pod at the bow, and an air-breathing engine at the stern. */
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
        // Fuel and engine at the stern, to spread the weight along the hull.
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
     * Turns each surface part still at its default rotation to face out from the stack, about the
     * stack axis, as the builder would. Surface parts reach out along their own +X. A shortest-arc
     * turn would flip a -X wing upside down.
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
     * [design] with its surface parts turned outward, for stock designs saved by older builds.
     * Builder-placed parts already face out.
     */
    fun facingOutward(design: CraftDesign, catalog: PartCatalog): CraftDesign {
        val parts = design.parts.toMutableList()
        // Only upright craft; on one lying down it would stand a deck fitting on edge.
        val upright = design.orientation == CraftOrientation.VERTICAL
        val faced = if (upright && faceOutward(parts, catalog)) design.copy(parts = parts) else design
        // And anything on an opposite node rolled the right way up.
        return Attachment.settled(faced, catalog)
    }

    // --- craft from the vehicle kits -----------------------------------------
    //
    // Put together through the builder node by node, like a player would, so each can really be
    // built.

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

        /** Turns part [index] a quarter turn about the node it's on, [quarters] times. */
        fun turn(index: Int, quarters: Int = 1) {
            check(builder.turn(index, quarters)) { "${builder.design.parts[index].partId} wouldn't turn" }
        }

        /** A Boarding Ladder at node [nodeId] of hull [hull], turned to stand up the ship's side. */
        fun ladder(hull: Int, nodeId: String) = turn(on(hull, nodeId, "ladder-boat"), 3)

        fun design(): CraftDesign {
            builder.restage()
            return builder.design
        }
    }

    /** A light jet: cockpit, fuselage, tail jet, swept wings, tailplane, rudder and tricycle gear. */
    fun sparrow(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Sparrow", CraftOrientation.HORIZONTAL)
        val cockpit = a.root("cockpit-sparrow")
        val forward = a.on(cockpit, "bottom", "fuselage-short")
        val aft = a.on(forward, "bottom", "fuselage-long")
        val jet = a.on(aft, "bottom", "engine-zephyr")
        // Wings on the front station, where it trims with the tail neutral. Further back it's
        // nose-heavy; further forward it pitches up.
        a.on(aft, "side-right-fore", "wing-swept")
        a.on(aft, "side-left-fore", "wing-swept")
        // An all-moving tail; elevons alone can't lift the nose at take-off speed.
        a.on(jet, "surface-0", "tail-stabilator")
        a.on(jet, "surface-1", "tail-stabilator")
        a.on(jet, "surface-2", "tail-rudder")
        a.on(cockpit, "surface-3", "wheel-gear-nose")
        // Main wheels just behind the balance point, close enough for the tail to lift the nose.
        a.on(aft, "belly-right-2", "wheel-gear-main")
        a.on(aft, "belly-left-2", "wheel-gear-main")
        return a.design()
    }

    /**
     * A slow plane for short strips and decks: the Sparrow with plank wings of twice the area and a
     * pusher prop. Stalls under 30 m/s. Main wheels on the Sparrow's station, or it sits on its tail.
     */
    fun petrel(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Petrel", CraftOrientation.HORIZONTAL)
        val cockpit = a.root("cockpit-sparrow")
        val forward = a.on(cockpit, "bottom", "fuselage-short")
        val aft = a.on(forward, "bottom", "fuselage-long")
        val prop = a.on(aft, "bottom", "engine-prop")
        a.on(aft, "side-right-fore", "wing-plank")
        a.on(aft, "side-left-fore", "wing-plank")
        a.on(prop, "surface-0", "tail-stabilator")
        a.on(prop, "surface-1", "tail-stabilator")
        a.on(prop, "surface-2", "tail-rudder")
        a.on(prop, "surface-3", "hook-tail")
        a.on(cockpit, "surface-3", "wheel-gear-nose")
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
        // A winch on the front.
        a.on(chassis, "front", "winch-drum")
        return a.design()
    }

    /** A small V-hulled boat: a seat, an outboard and a mooring clamp each side. */
    fun skiff(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Skiff", CraftOrientation.HORIZONTAL)
        val hull = a.root("hull-skiff")
        a.on(hull, "deck", "cab-open")
        // A skeg, or a hard turn at full throttle rolls her over.
        a.on(hull, "keel-front", "keel-skeg")
        a.on(hull, "transom", "motor-outboard")
        a.on(hull, "side-right", "mooring-clamp")
        a.on(hull, "side-left", "mooring-clamp")
        return a.design()
    }

    /**
     * A jet boat: a decked runabout hull, so she doesn't swamp, with a water jet on the transom. No
     * skeg, which trips her in a hard turn.
     */
    fun jetBoat(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Jet Boat", CraftOrientation.HORIZONTAL)
        val hull = a.root("hull-runabout")
        a.on(hull, "deck", "cab-open")
        a.on(hull, "transom", "jet-water")
        a.on(hull, "side-right", "mooring-clamp")
        a.on(hull, "side-left", "mooring-clamp")
        return a.design()
    }

    /** A jet ski: a short, wide planing hull, a saddle to sit astride, and a jet pump on the back. */
    fun jetSki(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Jet Ski", CraftOrientation.HORIZONTAL)
        val hull = a.root("hull-jetski")
        a.on(hull, "deck", "seat-saddle")
        a.on(hull, "transom", "jet-pump")
        return a.design()
    }

    /**
     * A small sailing boat: a skiff hull with a sloop rig, two keels against leeway and a rudder. No
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

    /** A light helicopter: bubble cockpit, main rotor, tank, tail boom with tail rotor, and skids. */
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
     * An airship: envelope, gondola, a power car (tank and propeller), tail fins and rudder, and a
     * keeper core to hold it over a spot.
     */
    fun zeppelin(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Zeppelin", CraftOrientation.HORIZONTAL)
        val envelope = a.root("envelope-airship")
        val gondola = a.on(envelope, "belly", "cockpit-bubble")
        a.on(gondola, "belly", "core-keeper")
        a.on(envelope, "tail-top", "tail-rudder")
        // The power car under the middle; on the nose its weight tips the ship.
        val tank = a.on(envelope, "belly-rear", "fuselage-short")
        a.on(tank, "top", "engine-prop")
        // Charge for the ballonet pumps; the engine tops it up.
        a.on(tank, "side-right", "battery-hoard")
        a.on(tank, "side-left", "battery-hoard")
        a.on(envelope, "tail-right", "tail-stabilator")
        a.on(envelope, "tail-left", "tail-stabilator")
        return a.design()
    }

    /** A sky platform: a sky deck on light gas, a lift fan per corner, a keeper core and batteries. */
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
    // A pressure hull between two trim tanks, a screw at the tail, bow planes, a rudder and lamps
    // underneath; the deep ones add sonar. Blown it just floats, flooded it just sinks. The tanks
    // sit evenly fore and aft so it lies level either way.

    /** A Pearl sphere between two trim tanks, good to 300 m. */
    fun minnow(catalog: PartCatalog = StockParts.catalog): CraftDesign = submarine(catalog, "Minnow", "pod-pearl", "ballast-trim")

    /** Two aboard, down to 1.5 km: the Nautilus hull between two deep trim tanks. */
    fun nautilus(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        submarine(catalog, "Nautilus", "hull-nautilus", "ballast-deep", sonar = true)

    /**
     * Down to the bottom of the Terra Deep: the Abyss sphere between abyssal tanks, with a float on
     * top that holds up its weight and keeps it upright.
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
        // Bow planes forward to balance the drag of the screw and rudder aft, or it sinks nose
        // first.
        a.on(fore, "side-right", "planes-dive")
        a.on(fore, "side-left", "planes-dive")
        a.on(aft, "spine-aft", "rudder")
        a.on(middle, "belly-right", "lamp-deep")
        a.on(middle, "belly-left", "lamp-deep")
        // A lead keel keeps the weight low so it stays upright, and sinks it at about 1 m/s when
        // flooded. The Abyss's float on top keeps the lift high.
        a.on(middle, "belly", "keel-lead")
        val sail = float?.let { a.on(middle, "spine", it) }
        // A spare cell in the middle, where its weight doesn't tip it.
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

    /** A storm boat: five decked hull sections, 14 m long, a wheelhouse amidships and a deep keel. */
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

    /**
     * A small coaster: a 4.5 by 25 m ship's hull, a diesel and ship's rudder, wheelhouse aft and a
     * cargo rack forward. [electric] swaps the diesel for an electric drive and two battery banks.
     */
    fun coaster(catalog: PartCatalog = StockParts.catalog, electric: Boolean = false): CraftDesign {
        val a = Assembly(catalog, if (electric) "Electric Coaster" else "Coaster", CraftOrientation.HORIZONTAL)
        val bow = a.root("hull-wide-bow")
        val fore = a.on(bow, "bottom", "hull-wide-mid")
        val mid = a.on(fore, "bottom", "hull-wide-mid")
        val aft = a.on(mid, "bottom", "hull-wide-mid")
        val stern = a.on(aft, "bottom", "hull-wide-stern")
        a.on(stern, "deck", "cabin-wheelhouse")
        if (electric) {
            a.on(fore, "deck", "battery-ship")
            a.on(aft, "deck", "battery-ship")
        } else {
            a.on(fore, "deck", "rack-cargo")
        }
        a.on(mid, "keel", "keel")
        a.on(stern, "keel", "rudder-ship")
        a.on(stern, "transom", if (electric) "engine-electric" else "engine-diesel")
        a.on(mid, "side-right", "mooring-clamp")
        a.on(mid, "side-left", "mooring-clamp")
        a.ladder(aft, "side-right")
        return a.design()
    }

    /**
     * A schooner: the Coaster's hull with two masts instead of an engine, a deep keel and a ship's
     * rudder. The throttle is her sheets.
     */
    fun schooner(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Schooner", CraftOrientation.HORIZONTAL)
        val bow = a.root("hull-wide-bow")
        val fore = a.on(bow, "bottom", "hull-wide-mid")
        val mid = a.on(fore, "bottom", "hull-wide-mid")
        val aft = a.on(mid, "bottom", "hull-wide-mid")
        val stern = a.on(aft, "bottom", "hull-wide-stern")
        a.on(fore, "deck", "sail-schooner")
        a.on(aft, "deck", "sail-schooner")
        a.on(stern, "deck", "cabin-wheelhouse")
        a.on(mid, "keel", "keel-ship")
        a.on(stern, "keel", "rudder-ship")
        a.on(mid, "side-right", "mooring-clamp")
        a.on(mid, "side-left", "mooring-clamp")
        a.ladder(aft, "side-right")
        return a.design()
    }

    /** A deck barge, 34 by 10 m, with a tow bitt each end. No engine; a tug tows or pushes her. */
    fun deckBarge(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Deck Barge", CraftOrientation.HORIZONTAL)
        val bow = a.root("barge-bow")
        val fore = a.on(bow, "bottom", "barge-mid")
        val aft = a.on(fore, "bottom", "barge-mid")
        val stern = a.on(aft, "bottom", "barge-stern")
        a.on(bow, "deck", "bitt-tow")
        a.on(stern, "deck", "bitt-tow")
        a.on(fore, "side-right", "mooring-clamp")
        a.on(fore, "side-left", "mooring-clamp")
        a.ladder(aft, "side-right")
        return a.design()
    }

    /** A harbour tug: short ship's hull, diesel, wheelhouse forward, tow winch aft and push knees. */
    fun harbourTug(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Harbour Tug", CraftOrientation.HORIZONTAL)
        val bow = a.root("hull-wide-bow")
        val stern = a.on(bow, "bottom", "hull-wide-stern")
        a.on(bow, "deck", "cabin-wheelhouse")
        a.on(bow, "stem", "knees-push")
        a.on(stern, "deck", "winch-tow")
        a.on(stern, "keel", "rudder-ship")
        a.on(stern, "transom", "engine-diesel")
        a.on(bow, "side-right", "mooring-clamp")
        a.on(bow, "side-left", "mooring-clamp")
        a.ladder(stern, "side-right")
        return a.design()
    }

    /**
     * A landing barge: two flight deck tiles, 40 by 20 m, for landing at sea. Two diesels, a rudder,
     * the wheelhouse to one side and a tow bitt.
     */
    fun landingBarge(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Landing Barge", CraftOrientation.HORIZONTAL)
        val fore = a.root("deck-flight")
        val aft = a.on(fore, "aft", "deck-flight")
        a.on(fore, "deck-right", "cabin-wheelhouse")
        a.on(fore, "deck-fore", "bitt-tow")
        a.on(aft, "drive-right", "engine-diesel")
        a.on(aft, "drive-left", "engine-diesel")
        a.on(aft, "keel-aft", "rudder-ship")
        a.ladder(aft, "side-right")
        return a.design()
    }

    /**
     * A flat top: eight flight deck tiles, 160 by 20 m, long enough for a Petrel. Arresting wires
     * aft, a catapult forward, the island to starboard, two diesels and a rudder.
     */
    fun flatTop(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Flat Top", CraftOrientation.HORIZONTAL)
        val tiles = ArrayList<Int>()
        tiles.add(a.root("deck-flight"))
        repeat(7) { tiles.add(a.on(tiles.last(), "aft", "deck-flight")) }
        a.on(tiles[6], "deck", "gear-arrest")
        a.on(tiles[2], "deck-left", "catapult-deck")
        a.on(tiles[3], "deck-right", "cabin-wheelhouse")
        a.on(tiles[0], "deck-fore", "bitt-tow")
        a.on(tiles[7], "drive-right", "engine-diesel")
        a.on(tiles[7], "drive-left", "engine-diesel")
        a.on(tiles[7], "keel-aft", "rudder-ship")
        a.ladder(tiles[5], "side-right")
        return a.design()
    }

    // --- docking --------------------------------------------------------------

    /**
     * A base-building lander: legs on the diagonals with thrusters above, a docking ring on the
     * tank's side at the same height as every tug's (so two landed can meet ring to ring), and a
     * small ring on top for a probe.
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

    /** A pod with thrusters and a small docking ring: the lightest thing that can dock. */
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

    /** The start of a base: a core on a foundation, two connectors and a solar array. */
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
     * A base core that lands itself on Luna: the core and connectors with a descent stage round it
     * (two tanks and vacuum engines, diagonal legs, thrusters since the core has no reaction
     * wheels). It's founded where it stands, and leftover fuel becomes the base's first store.
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
        // Tanks just clear of the foundation's edge, legs on its corners.
        val tankOut = 2.75
        val legOut = 2.3
        val engines = ArrayList<Int>()
        for (side in listOf(1.0, -1.0)) {
            // Tank 1.5-5.5 m up and bell 0.5-1.5, clear of the ground with legs fully pressed.
            val z = side * tankOut
            val tank = add("tank-cask4", 0.0, 3.5, z, core)
            engines.add(add("engine-vesper", 0.0, 1.0, z, tank))
            for (x in listOf(0.7, -0.7)) add("rcs-nudge", x, 5.1, z, tank)
        }
        // Feet 0.25 m below the foundation, so pressed legs leave it close enough to found.
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
     * A flatbed truck carrying [load] on its own foundation in a release clamp. Stage it beside a
     * base and the load drops onto its feet, ready to join.
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

    /** A habitat module on a flatbed, with a connector on its right-hand side. */
    fun moduleHauler(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        flatbed(catalog, "Module Hauler") { a, foundation ->
            val habitat = a.on(foundation, "top", "base-habitat")
            a.on(habitat, "surface-0", "base-connector")
        }

    /** A base core on a flatbed, to set down and found. */
    fun baseCoreHauler(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        flatbed(catalog, "Base Core Hauler") { a, foundation ->
            val core = a.on(foundation, "top", "base-core")
            a.on(core, "surface-0", "base-connector")
            a.on(core, "surface-1", "base-connector")
        }

    /** A pad base: a deck to launch from and land on, with a depot, a power module and solar. */
    fun padBase(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Pad Base", CraftOrientation.VERTICAL)
        val pad = a.root("base-pad")
        a.on(pad, "corner-1", "base-depot")
        val power = a.on(pad, "corner-2", "base-battery")
        a.on(power, "top", "base-solar")
        return a.design()
    }

    /**
     * A sea floor base: core and power module on a Base Float and Sea Footing. Blown it floats with
     * about 24 t to spare, to be towed out; flooded it sits on the floor 15 t heavy. No solar.
     */
    fun seaFloorBase(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Sea Floor Base", CraftOrientation.VERTICAL)
        val footing = a.root("base-footing-sea")
        val float = a.on(footing, "top", "base-float")
        val core = a.on(float, "top", "base-core")
        a.on(core, "surface-0", "base-connector")
        a.on(core, "surface-2", "base-connector")
        a.on(core, "top", "base-battery")
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
