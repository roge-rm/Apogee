package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.StockParts

/**
 * Reference craft, built in code.
 *
 * These exist so the simulation has something real to fly before the builder
 * exists, and so the headless ascent scenario has a fixed subject - a
 * regression in thrust, drag or staging shows up as this craft failing to make
 * orbit, which is a far more legible signal than a number moving in a unit
 * test.
 *
 * Layout convention: +Y is up, the design origin is at the very bottom of the
 * stack, and the tree is rooted at the command pod with everything hanging
 * below it. Rooting at the pod is what makes staging work - separating the
 * subtree below a decoupler discards the spent stage and leaves the crew
 * flying, rather than the other way round.
 */
object StockCraft {

    /**
     * Two-stage launcher sized to reach a ~100 km orbit with margin.
     *
     * Roughly 3.5 km/s of delta-v against the ~3.4 km/s the homeworld demands,
     * at a liftoff thrust-to-weight of about 1.4.
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

        // Built top-down so the pod can be the root, but positioned in the
        // bottom-up coordinates the stack actually occupies.
        val pod = add("pod-halo", 15.6, -1)
        add("chute-canopy", 16.4, pod)
        // A shield under the pod and a ring under that: the pod comes home
        // on its own, shield first.
        val shield = add("shield-halo", 14.9, pod)
        val podRing = add("decoupler-ring", 14.7, shield)
        // The long tank on the upper stage: from the coast, at sea level, the
        // short one reached orbit with next to nothing left (Dan).
        val upperTank = add("tank-cask4", 12.6, podRing)
        val upperEngine = add("engine-vesper", 10.1, upperTank)
        val decoupler = add("decoupler-ring", 9.5, upperEngine)
        val lowerTankTop = add("tank-cask4", 7.4, decoupler)
        val lowerTankMid = add("tank-cask4", 3.4, lowerTankTop)
        val lowerTankBottom = add("tank-cask4", -0.6, lowerTankMid)
        // Below the tank, not inside it. The Ember is 1.4m tall and the
        // bottom tank ends at -2.6, so its centre belongs at -3.3; at -2.0 all
        // but the last ten centimetres of the engine was buried in the tank
        // above it, which is what made the stack look wrong at the base.
        val mainEngine = add("engine-ember", -3.3, lowerTankBottom)

        // Fins low on the stack, well behind the centre of mass, which is what
        // makes them stabilising rather than destabilising.
        val finRadius = 0.975
        add("fin-vane", -0.6, lowerTankBottom, x = finRadius)
        add("fin-vane", -0.6, lowerTankBottom, x = -finRadius)
        add("fin-vane", -0.6, lowerTankBottom, z = finRadius)
        add("fin-vane", -0.6, lowerTankBottom, z = -finRadius)

        val chute = 1

        val stages = listOf(
            // Ignite the lifter.
            Stage(listOf(mainEngine)),
            // Separate the spent first stage and light the vacuum engine. Both
            // in one stage, so there is no coasting gap.
            Stage(listOf(decoupler, upperEngine)),
            // Let go of the upper stage: the pod goes home behind its shield.
            Stage(listOf(podRing)),
            // Chute, for the last of the way down.
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
     * A lander: engine, tank, pod, chute and four sprung legs.
     *
     * Deliberately not the starter rocket with legs bolted on. Arriving is a
     * different problem from leaving, and this is the craft the descent
     * scenario flies - a wide footprint, gear that reaches below the engine
     * bell, and enough propellant for a retro burn but not for orbit.
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

        // Bottom of the stack is y = 0: engine 0.0-1.0, tank 1.0-3.0,
        // pod 3.0-4.2, chute on top.
        val pod = add("pod-halo", 3.6, -1)
        val chute = add("chute-canopy", 4.4, pod)
        val tank = add("tank-cask2", 2.0, pod)
        val engine = add("engine-vesper", 0.5, tank)

        // Feet at y = -0.6: further below the engine bell at 0.0 than the
        // suspension's 0.4 m of travel, so the leg can compress fully and the
        // bell still clears the ground by 0.2 m. Gear that reaches less far
        // than it compresses is decoration - the first firm landing bottoms
        // the springs out and puts the engine in the dirt anyway, which is
        // exactly what the first version of this craft did.
        //
        // A metre out from the axis each way gives a two-metre footprint. The
        // footprint is what stops a lander tipping; the legs' strength only
        // decides whether it survives the arrival.
        val legReach = 1.0
        val legHeight = 0.2
        add("leg-stilt", legHeight, tank, x = legReach)
        add("leg-stilt", legHeight, tank, x = -legReach)
        add("leg-stilt", legHeight, tank, z = legReach)
        add("leg-stilt", legHeight, tank, z = -legReach)

        val stages = listOf(
            Stage(listOf(engine)),
            Stage(listOf(chute)),
            // Gear last, because it is the last thing you want out.
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
     * A lander with thruster blocks: the craft a base gets built out of.
     *
     * Deliberately not the plain [lander] with thrusters bolted on. That craft
     * is what the landing tests are measured against, and adding a couple of
     * hundred kilograms to it moved every drop they pin - which is a fair
     * warning that gear margins are thin, and no reason to make the fixture
     * and the working vehicle the same thing.
     */
    fun moduleTug(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val base = lander(catalog)
        val parts = ArrayList(base.parts)
        val tank = parts.indexOfFirst { it.partId == "tank-cask2" }

        // Ringed around the tank, opposite each other. Position does not
        // decide the torque - see Forces.applyRcs - but it does decide where
        // they are in the way, and out here they clear the legs.
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
     * A rover: a pod on four wheels.
     *
     * No engine and no stages. Driving is the throttle acting through the
     * wheels instead of through a bell, which is the whole claim being tested
     * - that a vehicle class is a different bag of modules rather than a
     * different simulation.
     */
    fun rover(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()
        parts.add(PlacedPart("pod-halo", Vec3.zero()))

        // Wheels at the four corners, below the pod so it rides clear of the
        // ground. The front pair steer.
        // A metre out each side, not seventy centimetres: a track of two
        // metres under a pod this tall is what keeps it on its wheels across
        // real country. At 1.4 m it rolled over half a kilometre off the pad.
        for ((x, z) in listOf(1.0 to 0.8, -1.0 to 0.8, 1.0 to -0.8, -1.0 to -0.8)) {
            parts.add(
                PlacedPart(
                    partId = "wheel-tread",
                    position = Vec3(x, -0.85, z),
                    rotation = Quat.identity(),
                    parentIndex = 0,
                )
            )
        }

        faceOutward(parts, catalog)

        return CraftDesign(
            name = "Trundler",
            parts = parts,
            stages = emptyList(),
            catalogHash = catalog.contentHash,
        )
    }

    /**
     * An aeroplane: a fuselage with wings, an air-breathing engine, and wheels
     * to take off from.
     *
     * Built [CraftOrientation.HORIZONTAL], so it launches lying on the ground
     * nose-east rather than standing on its tail. +Y is still the nose - a
     * plane is a stack flown on its side, not a new kind of object - and +Z is
     * the sky, which is where the fin goes. The wings are the stock
     * [AeroSurface] module with a wing's area instead of a fin's.
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

        // Wings either side of the tank, near the centre of mass so the craft
        // does not pitch the moment it makes lift.
        add("wing-plank", 2.4, tank, x = 2.2)
        add("wing-plank", 2.4, tank, x = -2.2)

        // A tail fin on top for yaw stability - the same job it does on the
        // rocket, and it does not deflect. Turned so its root faces down into
        // the hull, which is the turn the builder would give it on that node.
        add("fin-vane", 0.4, engine, z = 0.975)
        parts[parts.size - 1] = parts.last().copy(
            rotation = Quat.fromAxisAngle(Vec3.unitY(), -Math.PI / 2.0),
        )

        // Elevons well behind the wings. Being aft of the centre of mass is
        // what makes them pitch the aircraft rather than roll it; nothing in
        // the part says "elevator".
        add("tail-elevon", 0.4, engine, x = 1.525)
        add("tail-elevon", 0.4, engine, x = -1.525)

        // Undercarriage on the lower quarters, where the builder puts it: a
        // pair under the pod and a pair just behind the centre of mass, all at
        // the same depth so it sits level. The mains go *just* behind the
        // balance point - far enough that it does not sit back on its tail,
        // near enough that the elevons can lift the nose off at take-off
        // speed. At 0.4 m behind, rotating meant lifting fifteen kilonewton-
        // metres of the craft's own weight, twice what the controls give at
        // sixty metres a second, and it ran the whole runway before the wing
        // alone was enough.
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
     * A boat: a hull, a pod at the bow, and an air-breathing engine at the
     * stern pushing it along - an airboat, which is a real thing and needs no
     * part this game does not already have. Nothing here is a boat part
     * except the hull, and the hull is only a box that floats; staying upright
     * and going straight both come from where the water pushes on it.
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
        // Pod at the bow, riding the hull's top node.
        add("pod-halo", 4.6, hull, "top", "bottom")
        // Fuel and engine at the stern, so the weight is spread along the
        // hull rather than piled at one end of it.
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
     * Turns every surface-mounted part still at its default rotation to face
     * out from the stack, as the builder would have turned it: about the
     * stack axis, towards the side it is on. A fin, a leg, a wheel or a wing
     * reaches out from its root along its own +X; left unturned, all four fins
     * of a rocket pointed the same way, and a left wing's tip sat against the
     * fuselage. About the axis rather than by a shortest-arc turn, which for a
     * part on the -X side is a half turn about an arbitrary axis - often one
     * that flips a wing upside down and back to front.
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
     * [design] with its surface parts faced outward - for stock designs saved
     * by older builds, whose fins and legs all pointed one way. Anything the
     * builder placed already faces outward and is left as it is.
     */
    fun facingOutward(design: CraftDesign, catalog: PartCatalog): CraftDesign {
        val parts = design.parts.toMutableList()
        val faced = if (faceOutward(parts, catalog)) design.copy(parts = parts) else design
        // And anything on an opposed node rolled the right way up.
        return Attachment.settled(faced, catalog)
    }

    // --- craft from the vehicle kits -----------------------------------------
    //
    // Assembled through the builder, node by node, exactly as a player would
    // put them together - so each is something that can be built, and its
    // parts sit where the builder would put them.

    private class Assembly(catalog: PartCatalog, name: String, orientation: CraftOrientation) {
        val builder = CraftBuilder(catalog).also {
            it.orientation = orientation
            it.name = name
        }

        fun root(partId: String): Int {
            check(builder.placeRoot(partId)) { "could not place $partId" }
            return 0
        }

        /** Attaches [partId] at node [nodeId] of part [parent]; the new part's index. */
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
     * A light jet from the aircraft kit: cockpit, fuselage, a jet at the
     * tail, swept wings with ailerons, a tailplane and rudder, and tricycle
     * gear with the mains just behind the balance point.
     */
    fun sparrow(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Sparrow", CraftOrientation.HORIZONTAL)
        val cockpit = a.root("cockpit-sparrow")
        val forward = a.on(cockpit, "bottom", "fuselage-short")
        val aft = a.on(forward, "bottom", "fuselage-long")
        val jet = a.on(aft, "bottom", "engine-zephyr")
        // Wings on the fore station, where it flies trimmed with the tail
        // neutral. At the fuselage's middle the nose hung so heavy that the
        // tail could barely lift it, and let go it dived into the ground; a
        // station further forward and it pitched up on its own and looped.
        a.on(aft, "side-right-fore", "wing-swept")
        a.on(aft, "side-left-fore", "wing-swept")
        // An all-moving tail: elevons alone could not lift the nose until the
        // wings did it for them, at a hundred metres a second.
        a.on(jet, "surface-0", "tail-stabilator")
        a.on(jet, "surface-1", "tail-stabilator")
        a.on(jet, "surface-2", "tail-rudder")
        a.on(cockpit, "surface-3", "wheel-gear-nose")
        // Mains on the belly station just behind the balance point, which
        // falls a little ahead of the long fuselage's middle: close enough
        // behind it that the tail can lift the nose at flying speed.
        a.on(aft, "belly-right-2", "wheel-gear-main")
        a.on(aft, "belly-left-2", "wheel-gear-main")
        return a.design()
    }

    /** A four-wheeled buggy from the land kit: chassis, cab, cargo rack. */
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
        return a.design()
    }

    /** A small V-hulled boat from the boat kit: a seat, an outboard, and a mooring clamp each side. */
    fun skiff(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Skiff", CraftOrientation.HORIZONTAL)
        val hull = a.root("hull-skiff")
        a.on(hull, "deck", "cab-open")
        // A skeg under her: without it, hard over at full throttle, the
        // motor's push rolled her past seventy degrees (Dan).
        a.on(hull, "keel-front", "keel-skeg")
        a.on(hull, "transom", "motor-outboard")
        a.on(hull, "side-right", "mooring-clamp")
        a.on(hull, "side-left", "mooring-clamp")
        return a.design()
    }

    /** A keeled cutter from the boat kit: wheelhouse, keel, rudder and outboard. */
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
     * A working boat for weather: five decked hull sections, fourteen
     * metres of her, a wheelhouse amidships and a deep keel - the one that
     * rides out a storm sea that swamps a skiff.
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
     * A lander made for building bases: legs out on the diagonals, thrusters
     * above them, a standard docking ring on the side of its tank - level
     * with every other tug's, so two landed side by side can be walked
     * together ring to ring - and a small ring on top for a probe.
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

    /** A pod with thrusters and a small docking ring on its nose: the lightest thing that docks. */
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

    /** A trailer: a chassis on four wheels with a cargo rack and a drawbar to be towed by. */
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
     * The start of a base: a core on a foundation, with connectors on two
     * sides to build out from and a solar array on top. Set down, founded,
     * and modules brought up to its connectors join it.
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

    /** A habitat on its own foundation, with a connector on one side to join a base by. */
    fun habitatModule(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Habitat Module", CraftOrientation.VERTICAL)
        val foundation = a.root("base-foundation")
        val habitat = a.on(foundation, "top", "base-habitat")
        a.on(habitat, "surface-1", "base-connector")
        return a.design()
    }

    /**
     * A flatbed truck carrying [load]: a cab, eight big wheels, and a release
     * clamp holding the load on its own foundation. Driven up beside where
     * it is to go and staged, the clamp lets go and the load drops onto its
     * feet - next to a base's connector, to be drawn in and joined.
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

    /** A habitat module on a flatbed, with a connector on its right-hand side to join a base by. */
    fun moduleHauler(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        flatbed(catalog, "Module Hauler") { a, foundation ->
            val habitat = a.on(foundation, "top", "base-habitat")
            a.on(habitat, "surface-0", "base-connector")
        }

    /** A base's core on a flatbed: drive it out, set it down, found it where it lands. */
    fun baseCoreHauler(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        flatbed(catalog, "Base Core Hauler") { a, foundation ->
            val core = a.on(foundation, "top", "base-core")
            a.on(core, "surface-0", "base-connector")
            a.on(core, "surface-1", "base-connector")
        }

    /**
     * A pad on its own: a deck to launch from and land on, with a depot and
     * a power module standing at two corners and a solar array on the power
     * module. Founded where it is set down, it is a base of its own.
     */
    fun padBase(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val a = Assembly(catalog, "Pad Base", CraftOrientation.VERTICAL)
        val pad = a.root("base-pad")
        a.on(pad, "corner-1", "base-depot")
        val power = a.on(pad, "corner-2", "base-battery")
        a.on(power, "top", "base-solar")
        return a.design()
    }

    /** A propellant depot on a flatbed, to be joined to a base as its store. */
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
