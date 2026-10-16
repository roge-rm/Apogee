package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import kotlin.math.abs
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math

/**
 * Water: buoyancy and drag, cell by cell.
 *
 * Each part's volume is a grid of cells ([com.rm.apogee.core.part.PartDef.volumeCells]), each
 * weighed against the water surface where it is. The share under displaces that share of its
 * volume, pushing up at the cell, not the craft's centre. So a heeled hull rights itself and a
 * crest under the bow lifts the bow, which one force at the centre of mass can't do.
 *
 * Drag is per cell and per axis of its part, by each face's area. A long narrow hull slides easily
 * along its length and hardly at all sideways, which acts as a keel and lets a boat turn rather
 * than skate.
 *
 * Both go to [Stress] through [Vessel.recordForce], so a heavy sea working a hull can snap its
 * joints. Only what changes is passed on, each part's water force less its average over the last
 * few seconds ([POUNDING_SETTLE]): a lead keel hanging under a hull is held up like a craft on the
 * ground, which loads nothing, while waves lifting and dropping it do. Drag is passed on only up to
 * [POUNDING_DRAG] of the cell's own buoyancy: a hard landing is [World.impact]'s job, and counting
 * its whole stop here too would break craft twice.
 */
class Hydrostatics {

    private val rotation = Quat()
    private val bodyFixed = Vec3()
    private val offset = Vec3()
    private val point = Vec3()
    private val waterVelocity = Vec3()
    private val scratchCurrent = Vec3()
    private val relative = Vec3()
    private val local = Vec3()
    private val force = Vec3()
    private val up = Vec3()
    private val cellAxis = Vec3()

    /**
     * Parts that hit the water this tick, [splashCount] of them, and how hard: the speed into it
     * plus some of the skim across it.
     */
    val splashParts = IntArray(MAX_SPLASHES)
    val splashSpeeds = DoubleArray(MAX_SPLASHES)
    var splashCount = 0
        private set

    /** The torque about the centre of mass of what this tick's [apply] handed [Stress], N m. */
    val recordedTorque = Vec3()

    /** Off for tests that need a sea without breakers. */
    internal var slamOn = true

    /** The last slammed craft's crest speed in m/s and the way its sea runs, for tests. */
    internal var lastCrestSpeed = 0.0
        private set
    internal val lastSeaWay = Vec3()

    /** Whether the sea where the last craft was breaks too hard for it to sleep. See [keepsAwake]. */
    val breakers: Boolean get() = keepsAwake(sea)

    /** The significant wave height where the last craft was, in metres. */
    var seaHeight: Double = 0.0
        private set

    /** The volume under water last tick, in m³, for tests and the HUD. */
    var submergedVolume: Double = 0.0
        private set

    /** The waves over the craft this tick. See [com.rm.apogee.core.sea.WavePatch]. */
    private var patch = com.rm.apogee.core.sea.WavePatch()

    /**
     * Each craft's waves as last built. Building them (sea state, shelter, shoaling, every train's
     * size) is too slow for a phone at 4x in a rough sea, so they're built every [PATCH_KEEP]
     * seconds or after [PATCH_MOVE] metres, and carried on between by each train's phase alone.
     */
    private class Kept(val patch: com.rm.apogee.core.sea.WavePatch, val at: Vec3, var body: String, var used: Double) {
        /** When it was last built, not just carried on. */
        var built = Double.NaN
    }
    private val kept = HashMap<VesselId, Kept>()
    private var keptPrunedAt = 0.0

    /**
     * The sea's height at body-fixed [p] for [vessel] at [time], from its kept waves, or NaN when
     * they're stale, somewhere else, or not over open water.
     */
    fun keptHeight(vessel: Vessel, attractor: CelestialBody, p: Vec3, time: Double): Double {
        val k = kept[vessel.id] ?: return Double.NaN
        val age = time - k.built
        if (k.body != attractor.id || k.built.isNaN() || age < 0.0 || age >= PATCH_KEEP || !k.patch.afloat) return Double.NaN
        if (k.at.distanceTo(p) > PATCH_MOVE) return Double.NaN
        k.patch.advanceTo(time)
        return k.patch.height(p)
    }

    private fun wavesFor(vessel: Vessel, attractor: CelestialBody, ocean: com.rm.apogee.core.terrain.Ocean, time: Double) {
        val k = kept.getOrPut(vessel.id) { Kept(com.rm.apogee.core.sea.WavePatch(), Vec3(), "", time) }
        k.used = time
        patch = k.patch
        val age = time - k.built
        if (k.built.isNaN() || age < 0.0 || age >= PATCH_KEEP || k.body != attractor.id ||
            k.at.distanceTo(bodyFixed) > PATCH_MOVE) {
            ocean.patch(bodyFixed, time, k.patch)
            k.at.setTo(bodyFixed)
            k.body = attractor.id
            k.built = time
        } else {
            k.patch.advanceTo(time)
        }
        // Now and then, let go of craft that have gone.
        if (time - keptPrunedAt > PRUNE_EVERY) {
            keptPrunedAt = time
            kept.entries.removeAll { time - it.value.used > PRUNE_EVERY }
        }
    }
    private val sea: com.rm.apogee.core.sea.SeaSample get() = patch.middle
    private val scratchVelocity = Vec3()
    private val normal = Vec3()

    /**
     * Where the surface is over each volume cell this tick, in metres from the centre, and how much
     * of the cell is under.
     */
    private var cellSurface = DoubleArray(256)
    private var cellFraction = DoubleArray(256)

    /** Each part's water force this tick, world axes, xyz, for [pound]. */
    private var partHydro = DoubleArray(96)

    /** Each part's water velocity this tick, inertial, xyz. NaN in x until it's worked out. */
    private var partWater = DoubleArray(96)

    fun apply(vessel: Vessel, attractor: CelestialBody, time: Double, dt: Double) {
        recordedTorque.setZero()
        submergedVolume = 0.0
        splashCount = 0
        vessel.buoyed = false
        vessel.submerged = false
        val ocean = attractor.ocean ?: run { vessel.wet = null; vessel.resetWaterLoad(); return }
        val body = vessel.body

        // Far above any sea there could be, it's clear without asking the sea, so craft high in
        // flight don't build waves.
        if (attractor.altitudeOf(body.position) - vessel.contactRadius > CLEAR_ABOVE) {
            seaHeight = 0.0
            val n = vessel.defs.size
            (vessel.wet?.takeIf { it.size == n } ?: BooleanArray(n).also { vessel.wet = it }).fill(false)
            vessel.resetWaterLoad()
            return
        }

        // The sea where the craft is: its tide, and how big its waves are.
        attractor.rotationAt(time, rotation)
        attractor.toBodyFixed(body.position, rotation, bodyFixed)
        wavesFor(vessel, attractor, ocean, time)
        seaHeight = sea.significantHeight

        // Nowhere near the water. The margin is the craft's reach (how far below its centre a cell
        // can be) plus the biggest crest the sea here might throw up.
        val altitude = attractor.altitudeOf(body.position)
        if (altitude - vessel.contactRadius > sea.height + SURFACE_MARGIN + CREST_MARGIN * sea.significantHeight) {
            // Known to be clear, so whatever goes under next, fast, counts as a splash.
            val n = vessel.defs.size
            (vessel.wet?.takeIf { it.size == n } ?: BooleanArray(n).also { vessel.wet = it }).fill(false)
            // Clear of the water, so the next splash down starts afresh rather than straight into
            // Stress.
            vessel.resetWaterLoad()
            return
        }

        // Whether there's sea here at all, asked once for the craft, since it's small next to a
        // coastline.
        if (sea.depth <= 0.0) { vessel.resetWaterLoad(); return }

        // The water's up is against effective gravity (gravity less the spin's share), not straight
        // out from the centre. Off the equator they differ slightly, and pushing straight out
        // drifts every floating boat toward the equator.
        attractor.gravityAt(body.position, force)
        attractor.angularVelocity(spin)
        spinning.setTo(spin).crossInPlace(body.position)
        spinning.setTo(spin.cross(spinning))
        force.subInPlace(spinning)
        val g = force.length
        buoyUp.setTo(force).mulInPlace(-1.0 / g)
        val rho = ocean.density

        // Every cell weighed against the water over it, once.
        var cells = 0
        for (def in vessel.defs) cells += def.volumeCells.size
        if (cellSurface.size < cells) { cellSurface = DoubleArray(cells * 2); cellFraction = DoubleArray(cells * 2) }
        if (partWater.size < vessel.defs.size * 3) partWater = DoubleArray(vessel.defs.size * 6)
        for (i in vessel.defs.indices) partWater[i * 3] = Double.NaN
        var submergedCells = 0
        var c = 0
        // Waves only need working out where a cell might be partly in them: one wholly below the
        // lowest trough is under, one wholly above the highest crest is clear.
        val tideRadius = attractor.radius + patch.middle.tide
        val highest = tideRadius + patch.reach
        val lowest = tideRadius - patch.reach
        for (i in vessel.defs.indices) {
            val size = vessel.defs[i].volumeCellSize
            val half = 0.5 * (size.x + size.y + size.z)
            var columnX = Double.NaN; var columnY = Double.NaN
            var columnSurface = 0.0
            for (cell in vessel.defs[i].volumeCells) {
                vessel.partPointOffsetWorld(i, cell, offset)
                point.setTo(offset).addInPlace(body.position)
                val r = point.length
                val surface = when {
                    r + half < lowest -> lowest
                    r - half > highest -> highest
                    // Cells stacked in one column share the surface over them while the hull is
                    // near upright.
                    cell.x == columnX && cell.y == columnY && horizontalFrom(point, lastColumnPoint) < COLUMN_SHARE -> columnSurface
                    else -> {
                        attractor.toBodyFixed(point, rotation, bodyFixed)
                        val s = attractor.radius + patch.height(bodyFixed)
                        columnX = cell.x; columnY = cell.y; columnSurface = s
                        lastColumnPoint.setTo(point)
                        s
                    }
                }
                cellSurface[c] = surface
                val fraction = depthFraction(vessel, i, point, surface)
                cellFraction[c] = fraction
                if (fraction > 0.0) submergedCells++
                c++
            }
        }

        vessel.buoyed = submergedCells > 0
        if (partHydro.size < vessel.defs.size * 3) partHydro = DoubleArray(vessel.defs.size * 6)
        partHydro.fill(0.0, 0, vessel.defs.size * 3)
        splashes(vessel, attractor, ocean, time)
        applySurfaces(vessel, attractor, ocean, time, dt)
        flood(vessel, attractor, ocean, time, dt)
        slam(vessel, attractor, ocean, submergedCells, dt)
        rigs(vessel, attractor, ocean, time, dt)
        if (submergedCells == 0) { pound(vessel, dt); return }

        // How the hull meets the water as a whole, from its length and speed. The bow wave only
        // builds near hull speed, and a long hull slips through more easily than a short one.
        val length = hullLength(vessel)
        attractor.surfaceVelocityAt(body.position, relative)
        val through = relative.subInPlace(body.linearVelocity).length
        val waves = waveMaking(through, length)
        val skinCoefficient = skinFriction(through, length)

        c = 0
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            val cellList = def.volumeCells
            val cellVolume = def.displacedVolume / cellList.size
            val size = def.volumeCellSize
            val placed = vessel.design.parts[i]
            val faces = exposure(vessel)[i]
            // A hull's ends part the water, and a keel's or rudder's leading edge is a foil's,
            // sharper still. Anything else meets it square on.
            val hull = def.module<com.rm.apogee.core.part.Buoyancy>() != null
            val foil = !hull && def.module<com.rm.apogee.core.part.HydroSurface>() != null
            val endCd = if (hull) HULL_END_CD else if (foil) FOIL_END_CD else WATER_CD
            // A hull makes few waves along its length at a walking pace, and a foil deep under it
            // none. Anything else's +Y is just an axis, and standing up that's its heave.
            val aheadMaking = if (hull || foil) WAVE_MAKING_AHEAD else WAVE_MAKING_SPEED
            // Only along the hull, though. A pontoon on end under a platform has +Y straight up,
            // where it's heave and needs full damping.
            placed.rotation.rotate(Vec3.unitY(), point)
            val endShare = if (hull && abs(point dot vessel.design.orientation.forward) > ALONG_HULL) waves else 1.0

            for ((n, cell) in cellList.withIndex()) {
                val fraction = cellFraction[c++]
                if (fraction <= 0.0) continue
                vessel.partPointOffsetWorld(i, cell, offset)
                point.setTo(offset).addInPlace(body.position)

                // Buoyancy: the weight of the water displaced, straight up.
                val displaced = cellVolume * fraction
                submergedVolume += displaced
                force.setTo(buoyUp).mulInPlace(rho * g * displaced)
                body.applyForceAtOffset(force, offset)
                hydro(i, force)

                // Drag against the water, which moves with the ground and the waves. A crest
                // carries a boat forward, a trough pulls it back, a big sea throws it around.
                body.velocityAtOffset(offset, relative)
                waterVelocity(vessel, attractor, ocean, time, i, waterVelocity)
                relative.subInPlace(waterVelocity)
                val speed = relative.length
                if (speed < 1e-6) continue

                // Into the part's axes, where its face areas are known.
                body.orientation.inverseRotate(relative, local)
                placed.rotation.inverseRotate(local, local)
                // Only faces that meet the water: the leading one along each axis, and only where
                // no part of the craft lies against it. A hull of six cells or five sections has
                // one bow.
                val open = faces[n].toInt()
                val areaX = if (open and (if (local.x > 0.0) PLUS_X else MINUS_X) != 0) size.y * size.z * fraction else 0.0
                val areaY = if (open and (if (local.y > 0.0) PLUS_Y else MINUS_Y) != 0) size.x * size.z * fraction else 0.0
                val areaZ = if (open and (if (local.z > 0.0) PLUS_Z else MINUS_Z) != 0) size.x * size.y * fraction else 0.0
                // |v| plus a constant, not |v|. The constant is wave-making: a hull loses energy to
                // its waves in proportion to speed, not its square, and that's what stops a boat
                // bobbing. Quadratic drag alone leaves it heaving long after launch.
                //
                // Less of it along the hull: a boat at a walking pace makes hardly any waves, and
                // the full amount halves a sailing boat's speed. A little is kept so a moored boat
                // doesn't surge forever.
                //
                // Then skin friction along every wetted face, which holds back a long flat hull
                // skimming with almost no bow in the water.
                val sideX = (if (open and PLUS_X != 0) 1 else 0) + (if (open and MINUS_X != 0) 1 else 0)
                val sideY = (if (open and PLUS_Y != 0) 1 else 0) + (if (open and MINUS_Y != 0) 1 else 0)
                val sideZ = if (open and MINUS_Z != 0) 1 else 0
                val wetX = sideX * size.y * size.z * fraction
                val wetY = sideY * size.x * size.z * fraction
                val wetZ = sideZ * size.x * size.y
                val flow = sqrt(local.x * local.x + local.y * local.y + local.z * local.z)
                val skin = 0.5 * rho * skinCoefficient * flow
                local.setTo(
                    -0.5 * rho * WATER_CD * areaX * (abs(local.x) + WAVE_MAKING_SPEED) * local.x - skin * (wetY + wetZ) * local.x,
                    -0.5 * rho * endCd * areaY * (endShare * abs(local.y) + aheadMaking) * local.y - skin * (wetX + wetZ) * local.y,
                    -0.5 * rho * WATER_CD * areaZ * (abs(local.z) + WAVE_MAKING_SPEED) * local.z - skin * (wetX + wetY) * local.z,
                )
                placed.rotation.rotate(local, force)
                body.orientation.rotate(force, force)
                // A swimmer lies along the way they're going and meets the water head and shoulders
                // first, not stood up like a box.
                if (vessel.swimming) force.mulInPlace(SWIMMER_SHAPE)

                // Never more than stops this cell's share of the craft in a tick, or quadratic drag
                // on a fast arrival reverses its motion and an explicit step flings it back out.
                val limit = body.mass / submergedCells * speed / dt
                val magnitude = force.length
                if (magnitude > limit) force.mulInPlace(limit / magnitude)
                body.applyForceAtOffset(force, offset)
                val pounding = POUNDING_DRAG * rho * g * cellVolume
                val felt = force.length
                if (felt > pounding) force.mulInPlace(pounding / felt)
                hydro(i, force)
            }
        }
        pound(vessel, dt)
        var whole = 0.0
        for (def in vessel.defs) whole += def.displacedVolume
        vessel.submerged = whole > 0.0 && submergedVolume > SUBMERGED_SHARE * whole
    }

    /** Hands [force] on [part] to [Stress], and adds its moment to [recordedTorque]. */
    private fun record(vessel: Vessel, part: Int, force: Vec3) {
        vessel.recordForce(part, force)
        vessel.partOffsetWorld(part, slamArm)
        recordedTorque.addInPlace(slamArm.crossInPlace(force))
    }

    private fun hydro(part: Int, force: Vec3) {
        partHydro[part * 3] += force.x
        partHydro[part * 3 + 1] += force.y
        partHydro[part * 3 + 2] += force.z
    }

    /**
     * Hands [Stress] what changed in each part's water force this tick: the force less its recent
     * average, which then moves toward it.
     */
    private fun pound(vessel: Vessel, dt: Double) {
        val n = vessel.defs.size
        val average = vessel.waterLoad
        if (average.size != n * 3) return
        val follow = (dt / POUNDING_SETTLE).coerceAtMost(1.0)
        // Only once it's been afloat a while, so settling after a launch or a landing loads
        // nothing. Out of the water it counts back down at the same rate.
        vessel.waterTime = (vessel.waterTime + if (vessel.buoyed) dt else -dt).coerceIn(0.0, 2.0 * POUNDING_SETTLE)
        val share = ((vessel.waterTime - POUNDING_SETTLE) / POUNDING_SETTLE).coerceIn(0.0, 1.0)
        for (i in 0 until n) {
            val o = i * 3
            // A part just added, or a craft just changed, starts from what it carries now.
            if (average[o].isNaN()) {
                average[o] = partHydro[o]; average[o + 1] = partHydro[o + 1]; average[o + 2] = partHydro[o + 2]
            }
            force.setTo(partHydro[o] - average[o], partHydro[o + 1] - average[o + 1], partHydro[o + 2] - average[o + 2])
            average[o] += force.x * follow; average[o + 1] += force.y * follow; average[o + 2] += force.z * follow
            if (share > 0.0 && force.lengthSq > 0.0) record(vessel, i, force.mulInPlace(share))
        }
    }

    /**
     * Breaking crests and green water hitting the craft. Each wet cell near the top of a tall
     * breaking crest's front is struck by water running at the crest's speed along the way the sea
     * runs: a pressure of half rho v² times [SLAM_CD], by how much the crest breaks and how tall it
     * stands, on the cell's open faces toward the sea. v is the crest speed less the cell's own
     * speed along the sea, so a craft already carried along feels less. Smoothed over
     * [SLAM_SMOOTH] per part, pushed on the craft and handed to [Stress], phased in like [pound].
     *
     * Nothing pushes down on decks: pushing a bow under has it plunge, and the impact takes small
     * parts off ships that should ride a rogue.
     */
    private fun slam(vessel: Vessel, attractor: CelestialBody, ocean: com.rm.apogee.core.terrain.Ocean, submergedCells: Int, dt: Double) {
        val n = vessel.defs.size
        val load = vessel.slamLoad
        if (load.size != n * 6) return
        val hs = sea.significantHeight
        val most = mostBreaking(sea)
        val live = submergedCells > 0 && most > SLAM_FROM && hs > SLAM_HS_FROM && sea.depth > 0.0
        if (!live && !vessel.slamming) return
        if (!slamOn) { if (vessel.slamming) { load.fill(0.0); vessel.slamming = false }; return }
        val body = vessel.body
        val tideRadius = attractor.radius + sea.tide
        val follow = (dt / SLAM_SMOOTH).coerceAtMost(1.0)
        val crestSpeed = if (live) seaRun(body.position, slamDirection) else 0.0
        // The way the sea runs in the body's axes, and a step a sixteenth of its waves' length
        // for their slope.
        rotation.inverseRotate(slamDirection, slamAlong)
        val slamStep = (2.0 * Math.PI * crestSpeed * crestSpeed / STANDARD_GRAVITY / 16.0).coerceAtLeast(1.0)
        attractor.surfaceVelocityAt(body.position, slamGround)
        // Cells under water or under a breaking lip, so the craft's mass is shared among them.
        var struck = 0
        if (live) {
            var k = 0
            for (i in 0 until n) for (cell in vessel.defs[i].volumeCells) {
                if (cellFraction[k] > 0.0 || lipWeight(vessel, i, cell, cellSurface[k], tideRadius) > 0.0) struck++
                k++
            }
        }
        val cellMass = body.mass / struck.coerceAtLeast(1)
        val share = ((vessel.waterTime - POUNDING_SETTLE) / POUNDING_SETTLE).coerceIn(0.0, 1.0)
        var any = false
        var c = 0
        for (i in 0 until n) {
            val def = vessel.defs[i]
            val cells = def.volumeCells
            slamForce.setZero(); slamMoment.setZero()
            if (live) {
                val size = def.volumeCellSize
                val placed = vessel.design.parts[i]
                val faces = exposure(vessel)[i]
                // A ladder is rungs: most of the water goes through.
                val solid = if (def.module<com.rm.apogee.core.part.Ladder>() != null) LADDER_SOLID else 1.0
                vessel.partOffsetWorld(i, slamCentre)
                // The sea's run in the part's axes, and which faces it meets.
                body.orientation.inverseRotate(slamDirection, slamLocal)
                placed.rotation.inverseRotate(slamLocal, slamLocal)
                for (cn in cells.indices) {
                    val surface = cellSurface[c + cn]
                    // In the water, or above it under the breaking lip, less toward its top.
                    val fraction = kotlin.math.max(cellFraction[c + cn], lipWeight(vessel, i, cells[cn], surface, tideRadius))
                    if (fraction <= 0.0) continue
                    val stands = surface - tideRadius
                    val breaks = smooth(CREST_FROM, CREST_FULL, stands / hs) * most
                    if (breaks <= SLAM_FROM || stands <= SLAM_HEIGHT_FROM) continue
                    vessel.partPointOffsetWorld(i, cells[cn], offset)
                    point.setTo(offset).addInPlace(body.position)
                    // Only near the top: the broken water doesn't reach far down.
                    val zone = SLAM_ZONE + SLAM_ZONE_SHARE * stands
                    val reach = 1.0 - (surface - point.length) / zone
                    if (reach <= 0.0) continue
                    // Only on the crest's front, where the sea slopes down ahead: once it's passed
                    // over, the water runs on.
                    attractor.toBodyFixed(point, rotation, bodyFixed)
                    slamProbe.setTo(bodyFixed).addScaledInPlace(slamAlong, slamStep)
                    val ahead = patch.height(slamProbe)
                    slamProbe.setTo(bodyFixed).addScaledInPlace(slamAlong, -slamStep)
                    val face = smooth(FACE_FROM, FACE_FULL, (patch.height(slamProbe) - ahead) / (2.0 * slamStep))
                    if (face <= 0.0) continue
                    body.velocityAtOffset(offset, relative)
                    relative.subInPlace(slamGround)
                    val closing = crestSpeed - (relative dot slamDirection)
                    if (closing <= 0.0) continue
                    val strength = (breaks - SLAM_FROM) / (1.0 - SLAM_FROM) *
                        smooth(SLAM_HEIGHT_FROM, SLAM_HEIGHT_FULL, stands) * reach.coerceAtMost(1.0) * face * fraction
                    val pressure = 0.5 * ocean.density * SLAM_CD * strength * closing * closing
                    // Faces toward the sea: the water runs along +d, so it meets the -d face.
                    val open = faces[cn].toInt()
                    val front =
                        (if (open and (if (slamLocal.x > 0.0) MINUS_X else PLUS_X) != 0) abs(slamLocal.x) * size.y * size.z else 0.0) +
                        (if (open and (if (slamLocal.y > 0.0) MINUS_Y else PLUS_Y) != 0) abs(slamLocal.y) * size.x * size.z else 0.0) +
                        (if (open and (if (slamLocal.z > 0.0) MINUS_Z else PLUS_Z) != 0) abs(slamLocal.z) * size.x * size.y else 0.0)
                    force.setTo(slamDirection).mulInPlace(solid * pressure * front)
                    // Never more than brings this cell's share of the craft up to the crest's speed
                    // in a tick, or a light craft is flung past it.
                    val limit = cellMass * closing / dt
                    val magnitude = force.length
                    if (magnitude > limit) force.mulInPlace(limit / magnitude)
                    slamForce.addInPlace(force)
                    // Its moment about the part's centre.
                    slamArm.setTo(offset).subInPlace(slamCentre)
                    slamMoment.addInPlace(slamArm.crossInPlace(force))
                }
            }
            var wet = 0
            if (live) for (cn in cells.indices) {
                if (cellFraction[c + cn] > 0.0 || lipWeight(vessel, i, cells[cn], cellSurface[c + cn], tideRadius) > 0.0) wet++
            }
            c += cells.size
            val o = i * 6
            load[o] += (slamForce.x - load[o]) * follow
            load[o + 1] += (slamForce.y - load[o + 1]) * follow
            load[o + 2] += (slamForce.z - load[o + 2]) * follow
            load[o + 3] += (slamMoment.x - load[o + 3]) * follow
            load[o + 4] += (slamMoment.y - load[o + 4]) * follow
            load[o + 5] += (slamMoment.z - load[o + 5]) * follow
            force.setTo(load[o], load[o + 1], load[o + 2])
            val felt = force.length
            if (felt < SLAM_LEAST) {
                // Faded out: let it go rather than carry a trickle forever.
                if (!live || slamForce.lengthSq == 0.0) for (k in 0 until 6) load[o + k] = 0.0 else any = true
                continue
            }
            any = true
            // Pushes no more than brings the part's wet share of the craft up to the crest's speed
            // now, so the smoothed load can't carry it on past the crest.
            vessel.partOffsetWorld(i, offset)
            body.velocityAtOffset(offset, relative)
            relative.subInPlace(slamGround)
            val limit = wet * cellMass * (crestSpeed - (relative dot slamDirection)).coerceAtLeast(0.0) / dt
            if (felt <= 0.0 || limit <= 0.0) continue
            val keep = (limit / felt).coerceAtMost(1.0)
            force.mulInPlace(keep)
            body.applyForceAtOffset(force, offset)
            slamMoment.setTo(load[o + 3], load[o + 4], load[o + 5]).mulInPlace(keep)
            body.applyTorque(slamMoment)
            if (share > 0.0) record(vessel, i, force.mulInPlace(share))
        }
        vessel.slamming = any
        if (any && live) {
            lastCrestSpeed = crestSpeed
            lastSeaWay.setTo(slamDirection)
        }
    }

    /**
     * Masts and sails driven into the water, as a boat is knocked down or rolls over: the water's
     * drag on the sail set, square to it, and on the bare spar across it, over the share of the mast
     * under the surface. Handed to [Stress] whole, so a sail dragged under can snap its mast.
     */
    private fun rigs(vessel: Vessel, attractor: CelestialBody, ocean: com.rm.apogee.core.terrain.Ocean, time: Double, dt: Double) {
        val body = vessel.body
        val sheet = vessel.control.throttle.coerceIn(0.0, 1.0)
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            val sail = def.module<com.rm.apogee.core.part.Sail>() ?: continue
            val placed = vessel.design.parts[i]
            placed.rotation.rotate(Vec3.unitY(), rigAxis)
            body.orientation.rotate(rigAxis, rigAxis)
            vessel.partOffsetWorld(i, rigCentre)
            val half = def.boundsHalfExtents.y
            // How deep the mast's foot and head are under the surface.
            val foot = depthAt(attractor, rigPoint.setTo(rigCentre).addScaledInPlace(rigAxis, -half).addInPlace(body.position))
            val head = depthAt(attractor, rigPoint.setTo(rigCentre).addScaledInPlace(rigAxis, half).addInPlace(body.position))
            if (foot <= 0.0 && head <= 0.0) continue
            // The share of it under, from foot (-1) to head (1) along the mast.
            val under: Double
            val from: Double
            val to: Double
            if (foot > 0.0 && head > 0.0) { under = 1.0; from = -1.0; to = 1.0 } else {
                val cross = foot / (foot - head)
                under = if (foot > 0.0) cross else 1.0 - cross
                if (foot > 0.0) { from = -1.0; to = -1.0 + 2.0 * cross } else { from = -1.0 + 2.0 * cross; to = 1.0 }
            }
            // Pushed where the wet part's middle is.
            offset.setTo(rigCentre).addScaledInPlace(rigAxis, 0.5 * (from + to) * half)
            body.velocityAtOffset(offset, relative)
            waterVelocity(vessel, attractor, ocean, time, i, waterVelocity)
            relative.subInPlace(waterVelocity)
            val speed = relative.length
            if (speed < 1e-6) continue
            // Square to the sail as it's set: its chord runs from the part's -Z, turned round the
            // mast by its angle.
            val angle = vessel.sailAngle[i]
            rigNormal.setTo(kotlin.math.cos(angle), 0.0, -kotlin.math.sin(angle))
            placed.rotation.rotate(rigNormal, rigNormal)
            body.orientation.rotate(rigNormal, rigNormal)
            val torn = vessel.isBroken(i)
            // Stowed by its action group, it's furled whatever the sheet.
            val set = if (vessel.groupState(i) >= 0) sheet else 0.0
            val canvas = if (torn) 0.0 else set * sail.area * under
            val across = relative dot rigNormal
            force.setTo(rigNormal).mulInPlace(-0.5 * ocean.density * SAIL_WATER_CD * canvas * across * abs(across))
            // The spar across its length, with the boom, rigging and any sail furled on it.
            rigNormal.setTo(relative).addScaledInPlace(rigAxis, -(relative dot rigAxis))
            val furled = if (torn) 0.0 else (1.0 - set) * FURLED_SHARE * sail.area
            val spar = (2.0 * kotlin.math.max(def.boundsHalfExtents.x, def.boundsHalfExtents.z) * 2.0 * half + furled) * under
            force.addScaledInPlace(rigNormal, -0.5 * ocean.density * SPAR_CD * spar * rigNormal.length)
            // No more than stops the whole craft in a tick, so it can't throw it back.
            val limit = body.mass * speed / dt
            val magnitude = force.length
            if (magnitude > limit) force.mulInPlace(limit / magnitude)
            body.applyForceAtOffset(force, offset)
            record(vessel, i, force)
        }
    }

    /** How far under the sea's surface [point] (inertial) is, in metres; less than 0 above it. */
    private fun depthAt(attractor: CelestialBody, point: Vec3): Double {
        attractor.toBodyFixed(point, rotation, rigFixed)
        return attractor.radius + patch.height(rigFixed) - point.length
    }

    /**
     * How much of the breaking lip over a tall crest reaches [cell] of [part], from 1 at the
     * surface to nothing [LIP_SHARE] of the slam zone above it. Nothing for a crest too low to slam.
     */
    private fun lipWeight(vessel: Vessel, part: Int, cell: Vec3, surface: Double, tideRadius: Double): Double {
        val stands = surface - tideRadius
        if (stands <= SLAM_HEIGHT_FROM) return 0.0
        val lip = LIP_SHARE * (SLAM_ZONE + SLAM_ZONE_SHARE * stands)
        vessel.partPointOffsetWorld(part, cell, slamLip)
        val above = slamLip.addInPlace(vessel.body.position).length - surface
        return if (above >= lip) 0.0 else (1.0 - above / lip).coerceAtMost(1.0)
    }

    /**
     * Which way the sea runs at the craft at [position], into [out] (inertial, unit, across the
     * surface), and its crests' speed in m/s: each train's way and phase speed, weighed by its
     * energy, so a rogue running through has its own.
     */
    private fun seaRun(position: Vec3, out: Vec3): Double {
        val l = position.length
        val ux = position.x / l; val uy = position.y / l; val uz = position.z / l
        // Into the body's axes for the patch, which is body-fixed.
        rotation.inverseRotate(slamUp.setTo(ux, uy, uz), slamUp)
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var speed = 0.0; var weight = 0.0
        for (j in 0 until patch.n) {
            val a = patch.a[j]
            val w = a * a
            val dot = patch.dx[j] * slamUp.x + patch.dy[j] * slamUp.y + patch.dz[j] * slamUp.z
            sx += w * (patch.dx[j] - dot * slamUp.x); sy += w * (patch.dy[j] - dot * slamUp.y); sz += w * (patch.dz[j] - dot * slamUp.z)
            speed += w * patch.omega[j] / patch.k[j]
            weight += w
        }
        out.setTo(sx, sy, sz)
        if (weight <= 0.0 || out.lengthSq < 1e-18) { out.setZero(); return 0.0 }
        out.normalizeInPlace()
        rotation.rotate(out, out)
        return speed / weight
    }

    private val slamDirection = Vec3()
    private val slamGround = Vec3()
    private val slamLocal = Vec3()
    private val slamForce = Vec3()
    private val slamMoment = Vec3()
    private val slamCentre = Vec3()
    private val slamArm = Vec3()
    private val slamUp = Vec3()
    private val slamAlong = Vec3()
    private val slamProbe = Vec3()
    private val slamLip = Vec3()
    private val rigAxis = Vec3()
    private val rigCentre = Vec3()
    private val rigPoint = Vec3()
    private val rigNormal = Vec3()
    private val rigFixed = Vec3()

    /** 0 below [from], 1 above [to], smooth between. */
    private fun smooth(from: Double, to: Double, x: Double): Double {
        val t = ((x - from) / (to - from)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * [vessel]'s hull length at the waterline in metres: its buoyant parts along its forward axis.
     * Redone only when its parts change.
     */
    private fun hullLength(vessel: Vessel): Double {
        if (vessel.hullLengthOf === vessel.defs) return vessel.hullLength
        val forward = vessel.design.orientation.forward
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (def.module<com.rm.apogee.core.part.Buoyancy>() == null) continue
            val placed = vessel.design.parts[i]
            val half = 0.5 * maxOf(def.volumeCellSize.x, def.volumeCellSize.y, def.volumeCellSize.z)
            for (cell in def.volumeCells) {
                placed.rotation.rotate(cell, point).addInPlace(placed.position)
                val along = point dot forward
                lo = minOf(lo, along - half)
                hi = maxOf(hi, along + half)
            }
        }
        vessel.hullLength = if (hi > lo) hi - lo else 2.0 * vessel.contactRadius
        vessel.hullLengthOf = vessel.defs
        return vessel.hullLength
    }

    /**
     * The water's velocity around part [index], inertial, into [out]: the ground's motion plus the
     * waves' at the part's depth. Once per part per tick, since it hardly changes across one.
     */
    private fun waterVelocity(vessel: Vessel, attractor: CelestialBody, ocean: com.rm.apogee.core.terrain.Ocean, time: Double, index: Int, out: Vec3): Vec3 {
        val o = index * 3
        if (!partWater[o].isNaN()) return out.setTo(partWater[o], partWater[o + 1], partWater[o + 2])
        vessel.partOffsetWorld(index, scratchOffset)
        scratchPoint.setTo(scratchOffset).addInPlace(vessel.body.position)
        attractor.toBodyFixed(scratchPoint, rotation, scratchFixed)
        val surface = attractor.radius + patch.height(scratchFixed)
        val below = kotlin.math.max(0.0, surface - scratchPoint.length)
        patch.velocity(scratchFixed, below, scratchVelocity)
        // And the current, carrying the whole sea along.
        ocean.sea?.let { scratchVelocity.addInPlace(it.current(scratchFixed, below, scratchCurrent)) }
        rotation.rotate(scratchVelocity, out)
        attractor.surfaceVelocityAt(scratchPoint, scratchGround)
        out.addInPlace(scratchGround)
        partWater[o] = out.x; partWater[o + 1] = out.y; partWater[o + 2] = out.z
        return out
    }

    /**
     * Which faces of each part's volume cells meet the water, per part and cell, as bits. Worked
     * out once per shape. A face is open unless the point just past it lies inside some part of the
     * craft, its own next cell or the hull section it's joined to.
     */
    private fun exposure(vessel: Vessel): Array<ByteArray> {
        val design = vessel.design
        vessel.faceExposure?.let { if (vessel.faceExposureFor === design) return it }
        val defs = vessel.defs
        val parts = design.parts
        val probe = Vec3(); val world = Vec3(); val local = Vec3()
        val table = Array(defs.size) { i ->
            val def = defs[i]
            val size = def.volumeCellSize
            val placed = parts[i]
            ByteArray(def.volumeCells.size) { n ->
                val cell = def.volumeCells[n]
                var bits = 0
                for (face in 0 until 6) {
                    val axis = face / 2
                    val sign = if (face % 2 == 0) 1.0 else -1.0
                    probe.setTo(cell)
                    when (axis) {
                        0 -> probe.x += sign * (0.5 * size.x + FACE_PROBE)
                        1 -> probe.y += sign * (0.5 * size.y + FACE_PROBE)
                        else -> probe.z += sign * (0.5 * size.z + FACE_PROBE)
                    }
                    placed.rotation.rotate(probe, world).addInPlace(placed.position)
                    var covered = false
                    for (j in defs.indices) {
                        val other = parts[j]
                        local.setTo(world).subInPlace(other.position)
                        other.rotation.inverseRotate(local, local)
                        val h = defs[j].boundsHalfExtents
                        if (abs(local.x) <= h.x && abs(local.y) <= h.y && abs(local.z) <= h.z) { covered = true; break }
                    }
                    if (!covered) bits = bits or (1 shl face)
                }
                bits.toByte()
            }
        }
        vessel.faceExposure = table
        vessel.faceExposureFor = design
        return table
    }

    private val lastColumnPoint = Vec3()

    /** How far apart [a] and [b] are across the local horizontal, in metres. */
    private fun horizontalFrom(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        val along = (dx * a.x + dy * a.y + dz * a.z) / a.length
        return sqrt((dx * dx + dy * dy + dz * dz - along * along).coerceAtLeast(0.0))
    }

    private val spin = Vec3()
    private val spinning = Vec3()
    private val buoyUp = Vec3()

    private val scratchOffset = Vec3()
    private val scratchPoint = Vec3()
    private val scratchFixed = Vec3()
    private val scratchGround = Vec3()

    /**
     * Water coming aboard. An open hull takes on whatever comes over its gunwale, from a breaking
     * crest or a heel that puts the edge under, like a weir (depth over the edge to the 1.5 power).
     * A holed hull, open or not, does the same through its broken sides. It's carried as weight and
     * a slow pump clears it while nothing comes in. Full enough, the boat sinks.
     */
    private fun flood(vessel: Vessel, attractor: CelestialBody, ocean: com.rm.apogee.core.terrain.Ocean, time: Double, dt: Double) {
        val flooded = vessel.flooded
        if (flooded.size != vessel.defs.size) return
        var changed = false
        for (i in vessel.defs.indices) {
            val hull = vessel.defs[i].module<com.rm.apogee.core.part.Buoyancy>() ?: continue
            val holed = vessel.isBroken(i)
            if (!hull.open && !holed) continue
            val box = vessel.defs[i].mesh as? com.rm.apogee.core.part.MeshSpec.Box ?: continue
            val capacity = com.rm.apogee.core.part.Buoyancy.capacity(vessel.defs[i], ocean.density)
            // The rim: points round the top edge, each standing for its share.
            val w = box.width; val h = box.height
            val perimeter = 2.0 * (w + h)
            val each = perimeter / RIM_POINTS
            val top = if (holed) 0.0 else box.depth * 0.5
            var inflow = 0.0
            for (k in 0 until RIM_POINTS) {
                val f = (k + 0.5) / RIM_POINTS * perimeter
                when {
                    f < w -> rimLocal.setTo(f - w * 0.5, h * 0.5, top)
                    f < w + h -> rimLocal.setTo(w * 0.5, h * 0.5 - (f - w), top)
                    f < 2 * w + h -> rimLocal.setTo(w * 0.5 - (f - w - h), -h * 0.5, top)
                    else -> rimLocal.setTo(-w * 0.5, -h * 0.5 + (f - 2 * w - h), top)
                }
                vessel.partPointOffsetWorld(i, rimLocal, offset)
                point.setTo(offset).addInPlace(vessel.body.position)
                attractor.toBodyFixed(point, rotation, bodyFixed)
                val over = attractor.radius + patch.height(bodyFixed) - point.length
                if (over > 0.0) inflow += WEIR * each * over * kotlin.math.sqrt(over)
            }
            // A breaking crest tumbles in over an open hull wherever it is.
            if (hull.open || holed) {
                vessel.partOffsetWorld(i, offset)
                point.setTo(offset).addInPlace(vessel.body.position)
                attractor.toBodyFixed(point, rotation, bodyFixed)
                val here = patch.height(bodyFixed)
                val atSurface = kotlin.math.abs(attractor.radius + here - point.length) < box.depth + sea.significantHeight * 0.3
                // Breaking where the crests are: the sea's breaking, by how high the water stands
                // here.
                val crest = if (sea.significantHeight > 0.1) ((here - sea.tide) / sea.significantHeight).coerceIn(0.0, 1.0) else 0.0
                val breaking = kotlin.math.max(sea.breaking, crest * kotlin.math.min(1.0, sea.stormHeight / 5.0))
                if (atSurface && breaking > BREAKING_FROM) {
                    inflow += BREAKER * (breaking - BREAKING_FROM) * kotlin.math.min(sea.significantHeight, BREAKER_HS) / BREAKER_HS * perimeter
                }
            }
            val before = flooded[i]
            flooded[i] = if (inflow > 0.0) {
                (before + inflow * ocean.density * dt).coerceAtMost(capacity)
            } else {
                (before - PUMP * ocean.density * dt).coerceAtLeast(0.0)
            }
            if (flooded[i] != before) changed = true
        }
        if (changed) {
            // Redo the mass properties every so often while it changes, not every tick.
            if (++floodedSinceMass >= MASS_EVERY) { vessel.recomputeMass(); floodedSinceMass = 0 }
        }
    }

    private var floodedSinceMass = 0
    private val rimLocal = Vec3()

    /**
     * Which parts went under this tick and how fast, measured into the surface as it faces and
     * moves, so slamming into a wave's face counts its slope. The first look at a craft only notes
     * what's wet, since a craft set down in the sea or just broken apart in it hasn't hit anything.
     */
    private fun splashes(vessel: Vessel, attractor: CelestialBody, ocean: com.rm.apogee.core.terrain.Ocean, time: Double) {
        val n = vessel.defs.size
        val known = vessel.wet?.takeIf { it.size == n }
        val wet = known ?: BooleanArray(n).also { vessel.wet = it }
        val body = vessel.body
        for (i in 0 until n) {
            vessel.partOffsetWorld(i, offset)
            point.setTo(offset).addInPlace(body.position)
            attractor.toBodyFixed(point, rotation, bodyFixed)
            val under = point.length < attractor.radius + patch.height(bodyFixed)
            if (under && !wet[i] && known != null && splashCount < MAX_SPLASHES) {
                body.velocityAtOffset(offset, relative)
                waterVelocity(vessel, attractor, ocean, time, i, waterVelocity)
                relative.subInPlace(waterVelocity)
                attractor.toBodyFixed(point, rotation, bodyFixed)
                patch.normal(bodyFixed, scratchVelocity)
                rotation.rotate(scratchVelocity, normal)
                val into = -(relative dot normal)
                if (into > 0.0) {
                    val skim = kotlin.math.sqrt((relative.lengthSq - into * into).coerceAtLeast(0.0))
                    splashParts[splashCount] = i
                    splashSpeeds[splashCount] = into + SKIM_SHARE * skim
                    splashCount++
                }
            }
            wet[i] = under
        }
    }

    /**
     * Rudders, keels and foils: flat plates in the water, normal along the part's Z.
     *
     * Flow across the plate pushes back along the normal with a wing's sin(a)cos(a) flat-plate
     * force, which stops a keeled boat sliding sideways while hardly slowing it forward. A
     * deflected controllable one adds its push, as an aircraft's control surfaces do. All scaled by
     * how much of the plate is wet.
     */
    private fun applySurfaces(vessel: Vessel, attractor: CelestialBody, ocean: com.rm.apogee.core.terrain.Ocean, time: Double, dt: Double) {
        val body = vessel.body
        for (i in vessel.defs.indices) {
            val surface = vessel.defs[i].module<com.rm.apogee.core.part.HydroSurface>() ?: continue
            vessel.partOffsetWorld(i, offset)
            point.setTo(offset).addInPlace(body.position)
            attractor.toBodyFixed(point, rotation, bodyFixed)
            val depth = attractor.radius + patch.height(bodyFixed) - point.length
            val wetted = ((depth + surface.halfDepth) / (2 * surface.halfDepth)).coerceIn(0.0, 1.0)
            if (wetted <= 0.0) continue

            body.velocityAtOffset(offset, relative)
            waterVelocity(vessel, attractor, ocean, time, i, waterVelocity)
            relative.subInPlace(waterVelocity)
            // The plate's axes in the world.
            val placed = vessel.design.parts[i]
            placed.rotation.rotate(Vec3.unitZ(), plateNormal)
            body.orientation.rotate(plateNormal, plateNormal)
            val across = relative dot plateNormal
            val along = kotlin.math.sqrt(kotlin.math.max(0.0, relative.lengthSq - across * across))
            val q = 0.5 * ocean.density * surface.area * surface.liftCoefficient * wetted
            var push = -q * across * along
            if (surface.controllable) {
                val deflection = vessel.surfaceDeflection.getOrElse(i) { 0.0 }
                val angle = Math.toRadians(surface.maxDeflection) * deflection
                // Along the plate's push direction for the command, like Forces.deflect: across the
                // hull and the mounting radius.
                push += q * surface.controlAuthority * relative.lengthSq *
                    kotlin.math.sin(angle) * kotlin.math.cos(angle) * deflectSign(vessel, i)
            }
            // Never more than stops the craft's motion across the plate in a tick. Water is dense
            // enough that an explicit step could reverse it and fling the boat sideways.
            val limit = body.mass * kotlin.math.abs(across) / dt + q * surface.controlAuthority * relative.lengthSq
            if (kotlin.math.abs(push) > limit) push = kotlin.math.sign(push) * limit
            force.setTo(plateNormal).mulInPlace(push)
            body.applyForceAtOffset(force, offset)
        }
    }

    /**
     * Which way along the plate's normal a positive deflection pushes, by
     * Forces.controlDeflection's rule (across the fuselage and the mounting radius).
     */
    private fun deflectSign(vessel: Vessel, partIndex: Int): Double {
        vessel.centerOfMass(scratchCentre)
        scratchRadial.setTo(vessel.design.parts[partIndex].position).subInPlace(scratchCentre)
        scratchRadial.y = 0.0
        if (scratchRadial.length < 1e-6) return 0.0
        scratchRadial.normalizeInPlace()
        val push = Vec3(0.0, 1.0, 0.0).crossInPlace(scratchRadial)
        vessel.design.parts[partIndex].rotation.rotate(Vec3.unitZ(), scratchPlate)
        val dot = push dot scratchPlate
        return if (dot >= 0.0) 1.0 else -1.0
    }

    private val plateNormal = Vec3()
    private val scratchCentre = Vec3()
    private val scratchRadial = Vec3()
    private val scratchPlate = Vec3()

    /**
     * How much of the cell centred at [point] is under water, 0..1: its centre's depth over the
     * cell's height as seen from above (its width, if tipped on its side). Without it the lift
     * switches as each centre crosses the surface and a floating hull buzzes.
     */
    private fun depthFraction(
        vessel: Vessel,
        partIndex: Int,
        point: Vec3,
        surface: Double,
    ): Double {
        val depth = surface - point.length

        // The cell's vertical extent: its three edges projected onto the local vertical.
        val def = vessel.defs[partIndex]
        val size = def.volumeCellSize
        val placed = vessel.design.parts[partIndex]
        up.setTo(point).normalizeInPlace()
        vessel.body.orientation.inverseRotate(up, cellAxis)
        placed.rotation.inverseRotate(cellAxis, cellAxis)
        val height = abs(cellAxis.x) * size.x + abs(cellAxis.y) * size.y + abs(cellAxis.z) * size.z

        return (depth / height + 0.5).coerceIn(0.0, 1.0)
    }

    internal companion object {
        /**
         * How much of a hull's bow drag there is at [speed] m/s for a hull [length] metres long,
         * 0..1. It's the bow wave, set by the Froude number (speed over sqrt(g * length)): almost
         * none at a walking pace, building steeply to all of it near hull speed (Froude about 0.4).
         */
        fun waveMaking(speed: Double, length: Double): Double {
            val froude = speed / sqrt(STANDARD_GRAVITY * length.coerceAtLeast(0.5))
            val share = froude / HULL_SPEED_FROUDE
            // Then the hump: just past hull speed the hull climbs its own bow wave and more power
            // buys little speed. A planing hull gets over it and the drag eases.
            val over = (froude - HUMP_FROUDE) / HUMP_WIDTH
            return (share * share * share * share).coerceAtMost(1.0) + HUMP * kotlin.math.exp(-over * over)
        }

        /**
         * The skin friction coefficient at [speed] for a hull [length] long: the ITTC 1957 line,
         * 0.075 / (log10 Re - 2)^2, plus a form factor and a roughness allowance. A long fast hull
         * has a high Reynolds number and a low coefficient, so a ship slips along where a dinghy
         * drags.
         */
        fun skinFriction(speed: Double, length: Double): Double {
            val reynolds = (speed * length / WATER_VISCOSITY).coerceAtLeast(MIN_REYNOLDS)
            val log = kotlin.math.log10(reynolds) - 2.0
            return (1.0 + FORM_FACTOR) * 0.075 / (log * log) + ROUGHNESS
        }

        const val STANDARD_GRAVITY = 9.81

        /** The Froude number at which a displacement hull reaches hull speed. */
        const val HULL_SPEED_FROUDE = 0.4

        /**
         * The wave drag's hump past hull speed: how much more there is at its worst, where, and how
         * wide.
         */
        const val HUMP = 2.0
        const val HUMP_FROUDE = 0.5
        const val HUMP_WIDTH = 0.12

        /** Seawater's kinematic viscosity, in m²/s. */
        const val WATER_VISCOSITY = 1.19e-6

        /**
         * The lowest Reynolds number the friction line is taken at: a crawl, where it's laminar.
         */
        const val MIN_REYNOLDS = 1.0e5

        /** How much more than a flat plate of its area a hull's friction drags. */
        const val FORM_FACTOR = 0.2

        /** The allowance for a hull that isn't polished smooth. */
        const val ROUGHNESS = 0.0004

        /** Higher than this over the datum, in metres, no tide or crest could reach a craft. */
        const val CLEAR_ABOVE = 500.0

        /**
         * How long a craft's waves are carried on before they're rebuilt, in seconds, and how far
         * it can go meanwhile, in metres.
         */
        const val PATCH_KEEP = 0.05
        const val PATCH_MOVE = 50.0

        /** How often craft that have gone are let go of, in seconds of world time. */
        const val PRUNE_EVERY = 10.0

        /** The share of its volume under water past which a craft counts as submerged. */
        const val SUBMERGED_SHARE = 0.97

        /** Metres above the surface within which a craft is worth sampling. */
        const val SURFACE_MARGIN = 2.0

        /**
         * How many significant wave heights above the average a crest can reach, for the margin.
         */
        const val CREST_MARGIN = 1.2

        /**
         * Points round a hull's rim that water can come in over, and the weir coefficient in
         * m^0.5/s.
         */
        const val RIM_POINTS = 12
        const val WEIR = 1.7

        /**
         * Breaking crests over an open hull: m³/s per metre of rim when the sea breaks fully, from
         * this much breaking up, in a sea of this height in metres or more.
         */
        const val BREAKER = 0.1
        const val BREAKING_FROM = 0.25
        const val BREAKER_HS = 6.0

        /**
         * Slam from breaking water. The pressure is half rho v² times [SLAM_CD], v the crest's
         * speed into the cell, scaled by how far the breaking passes [SLAM_FROM] and by the
         * crest's height over the tide from [SLAM_HEIGHT_FROM] to [SLAM_HEIGHT_FULL] metres.
         * A cell breaks fully where its water stands [CREST_FULL] significant heights over the
         * tide, from [CREST_FROM]. A storm sea of [STORM_BREAKS] metres breaks fully on its
         * crests. Seas under [SLAM_HS_FROM] metres don't slam. From 9 m so Wild's biggest storm
         * crests barely slam and a rogue's do fully.
         */
        const val SLAM_CD = 2.0
        const val SLAM_FROM = 0.3
        const val SLAM_HEIGHT_FROM = 9.0
        const val SLAM_HEIGHT_FULL = 25.0
        const val CREST_FROM = 0.25
        const val CREST_FULL = 0.7
        const val STORM_BREAKS = 5.0
        const val SLAM_HS_FROM = 2.0

        /** A breaking sea this tall, in metres of significant height, keeps craft afloat awake. */
        const val AWAKE_HS = 4.0

        /** The most the sea in [s] can break: the sample's own, and a storm's crests. */
        fun mostBreaking(s: com.rm.apogee.core.sea.SeaSample): Double =
            kotlin.math.max(s.breaking, kotlin.math.min(1.0, s.stormHeight / STORM_BREAKS))

        /**
         * Whether the sea in [s] breaks hard enough to slam or pound a craft, or a tsunami's flow
         * would carry it off, so it can't sleep.
         */
        fun keepsAwake(s: com.rm.apogee.core.sea.SeaSample): Boolean =
            s.depth > 0.0 && (s.significantHeight > AWAKE_HS && mostBreaking(s) > SLAM_FROM || s.flow.lengthSq > AWAKE_FLOW * AWAKE_FLOW)

        /** A tsunami's flow this fast, in m/s, keeps craft afloat awake. */
        const val AWAKE_FLOW = 1.0

        /** How steeply the sea has to fall away ahead for the front of a crest to slam, from and fully. */
        const val FACE_FROM = 0.1
        const val FACE_FULL = 0.35

        /** Drag coefficients in water of a sail square on, and of a bare mast across it. */
        const val SAIL_WATER_CD = 1.2
        const val SPAR_CD = 1.0

        /** A furled sail with its boom and rigging meets the water like this share of it set. */
        const val FURLED_SHARE = 0.15

        /** How far over the surface a tall breaking crest's lip reaches, as a share of the slam zone. */
        const val LIP_SHARE = 0.25

        /**
         * How deep the broken water reaches, in metres: this much plus a share of the crest's
         * height over the tide.
         */
        const val SLAM_ZONE = 1.0
        const val SLAM_ZONE_SHARE = 0.3

        /** How much of a ladder's outline meets the water. */
        const val LADDER_SOLID = 0.2

        /** How long a part's slam takes to build or ease, in seconds. */
        const val SLAM_SMOOTH = 0.25

        /** Under this many newtons a part's slam is let go. */
        const val SLAM_LEAST = 1.0

        /** The pump, in m³/s. */
        const val PUMP = 0.01

        /** Ticks between mass recalculations while flooding. */
        const val MASS_EVERY = 10

        const val MAX_SPLASHES = 16

        /**
         * How much of the speed across the water counts in a splash. Skimming in is gentler than
         * diving.
         */
        const val SKIM_SHARE = 0.3

        /**
         * The drag coefficient of a hull face moving through water, about a bluff body's. The
         * per-face areas are what make a hull prefer to go forwards.
         */
        const val WATER_CD = 0.8

        /**
         * The drag coefficient of a hull's ends, moving along it. Bow and stern are shaped to part
         * the water.
         */
        const val HULL_END_CD = 0.25

        /**
         * The same for a keel, rudder or foil along its chord. A streamlined section drags about a
         * tenth of a blunt one.
         */
        const val FOIL_END_CD = 0.08

        /** How nearly a hull part's +Y has to run along the craft to count as its length. */
        const val ALONG_HULL = 0.7

        /**
         * Cells in one column share the surface while within this many metres across the
         * horizontal.
         */
        private const val COLUMN_SHARE = 0.1

        /** How far past a cell's face, in metres, to look for more of the craft against it. */
        private const val FACE_PROBE = 0.02

        /** Face bits: which of a cell's faces meet the water. */
        private const val PLUS_X = 1; private const val MINUS_X = 2
        private const val PLUS_Y = 4; private const val MINUS_Y = 8
        private const val PLUS_Z = 16; private const val MINUS_Z = 32

        /**
         * The linear part of water drag, as the speed where it equals the quadratic part. Sized to
         * damp the stock boat's heave to about a third of critical, so it settles in a couple of
         * bobs and still looks afloat.
         */
        const val WAVE_MAKING_SPEED = 1.0

        /**
         * The most water drag a cell passes on to its joints, as a share of its buoyancy when
         * wholly under.
         */
        const val POUNDING_DRAG = 1.0

        /**
         * How long a part's water force takes to count as its usual load, in seconds. Shorter than
         * a sea's waves, so they still work the hull.
         */
        const val POUNDING_SETTLE = 4.0

        /** How much of an upright person's drag in the water a swimmer has. */
        const val SWIMMER_SHAPE = 0.06

        /** The same along a hull, ahead and astern. */
        const val WAVE_MAKING_AHEAD = 0.3
    }
}
