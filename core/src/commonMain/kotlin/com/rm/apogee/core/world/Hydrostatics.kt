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
 * Each part's volume is carried as a grid of cells ([com.rm.apogee.core.part.PartDef.volumeCells]),
 * and each cell is weighed against the water surface where it is. The fraction of a cell below the
 * surface displaces that fraction of its volume, pushing up at the cell and not at the craft's
 * centre. So a hull that heels over has more of itself under water on the low side and rights
 * itself, and when there are waves, a crest under the bow lifts the bow. One force at the centre of
 * mass gives you neither, and that's the failure this is built to avoid.
 *
 * Drag is per cell too, and per axis of the part it belongs to. Water resists a cell's motion
 * across each face in proportion to that face's area. So a long, narrow hull slides easily along
 * its length and hardly at all sideways. That's a keel, arrived at without anything called a keel,
 * and it's what lets a boat turn instead of skating.
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
     * Parts that hit the water this tick, and how hard: the speed into it, plus some of the skim
     * across it. There are [splashCount] of them.
     */
    val splashParts = IntArray(MAX_SPLASHES)
    val splashSpeeds = DoubleArray(MAX_SPLASHES)
    var splashCount = 0
        private set

    /** The significant wave height where the last craft was, in metres. */
    var seaHeight: Double = 0.0
        private set

    /** The volume under water last tick, in m³, for tests and the HUD. */
    var submergedVolume: Double = 0.0
        private set

    /** The waves over the craft this tick. See [com.rm.apogee.core.sea.WavePatch]. */
    private var patch = com.rm.apogee.core.sea.WavePatch()

    /**
     * Each craft's waves, as last worked out. Building them means the sea state, the shelter and
     * the shoaling and every train's size, which a phone at 4x couldn't keep up with four times a
     * tick in a rough sea. So they're built every [PATCH_KEEP] seconds, or when the craft has gone
     * [PATCH_MOVE] metres, and carried on in between by each train's phase alone.
     */
    private class Kept(val patch: com.rm.apogee.core.sea.WavePatch, val at: Vec3, var body: String, var used: Double) {
        /** When it was last built, as opposed to carried on to. */
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

    /** Each part's water velocity this tick, inertial, xyz. NaN in x until it's worked out. */
    private var partWater = DoubleArray(96)

    fun apply(vessel: Vessel, attractor: CelestialBody, time: Double, dt: Double) {
        submergedVolume = 0.0
        splashCount = 0
        vessel.buoyed = false
        vessel.submerged = false
        val ocean = attractor.ocean ?: run { vessel.wet = null; return }
        val body = vessel.body

        // The sea where the craft is: its tide, and how big its waves are.
        attractor.rotationAt(time, rotation)
        attractor.toBodyFixed(body.position, rotation, bodyFixed)
        wavesFor(vessel, attractor, ocean, time)
        seaHeight = sea.significantHeight

        // Nowhere near the water. The margin is generous: the craft's own reach, since that's how
        // far below its centre a cell can be, plus the biggest crest the sea here might throw up.
        val altitude = attractor.altitudeOf(body.position)
        if (altitude - vessel.contactRadius > sea.height + SURFACE_MARGIN + CREST_MARGIN * sea.significantHeight) {
            // Clear of it, and known to be, so whatever goes under next, fast, counts as a splash.
            val n = vessel.defs.size
            (vessel.wet?.takeIf { it.size == n } ?: BooleanArray(n).also { vessel.wet = it }).fill(false)
            return
        }

        // Whether there's sea here at all, asked once for the whole craft instead of per cell,
        // because a craft is small next to a coastline.
        if (sea.depth <= 0.0) return

        // The water's up is against effective gravity (gravity minus what it takes to go round with
        // the planet), not straight out from the centre. Off the equator the two differ by a
        // fraction of a degree, and pushing straight out left every floating boat shoved gently
        // toward the equator, drifting forever.
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
        // The waves only need working out where a cell might be partly in them. One wholly below
        // the lowest trough the sea here can make is under, and one wholly above the highest crest
        // is clear.
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
                    // Cells stacked in one column share the surface over them, while the hull is
                    // close enough to upright that they stand over the same water.
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
        splashes(vessel, attractor, ocean, time)
        applySurfaces(vessel, attractor, ocean, time, dt)
        flood(vessel, attractor, ocean, time, dt)
        if (submergedCells == 0) return

        // How the hull meets the water as a whole, from its length and its speed through it. The
        // bow's wave only builds as it nears hull speed, and a long hull slips through the water
        // more easily than a short one at the same speed.
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
            // A hull's ends are shaped to part the water, and a keel's or a rudder's leading edge is
            // a foil's, sharper still. Anything else meets it square on.
            val hull = def.module<com.rm.apogee.core.part.Buoyancy>() != null
            val foil = !hull && def.module<com.rm.apogee.core.part.HydroSurface>() != null
            val endCd = if (hull) HULL_END_CD else if (foil) FOIL_END_CD else WATER_CD
            // A hull's own length makes few waves at a walking pace, and a foil deep under it
            // makes none. Anything else's +Y is just an axis, and on something standing up that's
            // its heave.
            val aheadMaking = if (hull || foil) WAVE_MAKING_AHEAD else WAVE_MAKING_SPEED
            // Only along the hull, though. A pontoon standing on end under a platform has its +Y
            // straight up, and there it's heave. Shaped by a bow wave that isn't there, it went
            // undamped, and the Sea Platform bounced about its harbour for good.
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

                // Drag against the water, which moves with the ground and with the waves. A crest
                // carries a boat forward, a trough pulls it back, and a big sea throws it around.
                body.velocityAtOffset(offset, relative)
                waterVelocity(vessel, attractor, ocean, time, i, waterVelocity)
                relative.subInPlace(waterVelocity)
                val speed = relative.length
                if (speed < 1e-6) continue

                // Into the part's own axes, where its face areas are known.
                body.orientation.inverseRotate(relative, local)
                placed.rotation.inverseRotate(local, local)
                // Only the faces that meet the water: the one leading into the flow along each
                // axis, and only where no part of the craft lies against it. A hull six cells long
                // meets the water with one bow, not six, and a hull of five sections with one, not
                // five.
                val open = faces[n].toInt()
                val areaX = if (open and (if (local.x > 0.0) PLUS_X else MINUS_X) != 0) size.y * size.z * fraction else 0.0
                val areaY = if (open and (if (local.y > 0.0) PLUS_Y else MINUS_Y) != 0) size.x * size.z * fraction else 0.0
                val areaZ = if (open and (if (local.z > 0.0) PLUS_Z else MINUS_Z) != 0) size.x * size.y * fraction else 0.0
                // |v| plus a constant, not |v|. The constant is wave-making. A hull moving through
                // the surface loses energy into the waves it makes, in proportion to speed instead
                // of its square, and that's what stops a floating boat bobbing. On quadratic drag
                // alone the stock boat was still heaving at a tenth of a metre a second half a
                // minute after launch.
                //
                // Less of it along the hull, off the bow and stern: going ahead at a walking pace,
                // a boat makes hardly any waves, and with the whole of it there a sailing boat in a
                // breeze made half the speed a real one does. A little is kept, or a moored boat
                // surged back and forth and never settled.
                //
                // Then there's skin friction, the water dragging along every wetted face it slides
                // past. That's what holds back a long flat hull skimming along with almost no bow
                // in the water.
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

                // Never more than stops this cell's share of the craft in one tick. Quadratic drag
                // on a craft arriving at a hundred metres a second is a force that would reverse
                // its motion, and an explicit step would fling it back out of the water.
                val limit = body.mass / submergedCells * speed / dt
                val magnitude = force.length
                if (magnitude > limit) force.mulInPlace(limit / magnitude)
                body.applyForceAtOffset(force, offset)
            }
        }
        var whole = 0.0
        for (def in vessel.defs) whole += def.displacedVolume
        vessel.submerged = whole > 0.0 && submergedVolume > SUBMERGED_SHARE * whole
    }

    /**
     * How long [vessel]'s hull is at the waterline, in metres: the length of its buoyant parts
     * along its forward axis. Worked out again only when its parts change.
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
     * How fast the water around part [index] is moving, inertial, into [out]: the ground's own
     * motion, plus the waves' at the part's depth. It's worked out once per part per tick, because
     * the water hardly changes across one.
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
        // And the current it's in, carrying the whole sea along.
        ocean.sea?.let { scratchVelocity.addInPlace(it.current(scratchFixed, below, scratchCurrent)) }
        rotation.rotate(scratchVelocity, out)
        attractor.surfaceVelocityAt(scratchPoint, scratchGround)
        out.addInPlace(scratchGround)
        partWater[o] = out.x; partWater[o + 1] = out.y; partWater[o + 2] = out.z
        return out
    }

    /**
     * Which faces of each part's volume cells meet the water, per part and cell, as bits. It's
     * worked out once for a craft's shape and kept until it changes. A face is open unless the
     * point just past it lies inside some part of the craft, either its own next cell or the hull
     * section it's joined to.
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
     * Water coming aboard. An open hull takes on whatever comes over its gunwale, either a crest
     * breaking over it or a heel that puts the edge under, like water over a weir, going by the
     * depth over the edge to the power of one and a half. A holed hull, open or not, does the same
     * through its broken sides. It's carried as weight, and a slow pump takes it out again while
     * nothing is coming in. Full enough, the boat sinks.
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
            // The rim: points around the top edge, each standing for its share of it.
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
                // here over the rest.
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
            // Work out the mass properties again every so often while it changes, not every tick.
            if (++floodedSinceMass >= MASS_EVERY) { vessel.recomputeMass(); floodedSinceMass = 0 }
        }
    }

    private var floodedSinceMass = 0
    private val rimLocal = Vec3()

    /**
     * Which parts went under this tick, and how fast, measured into the surface as it faces, moving
     * as it moves. A hull slamming into the face of a wave hits it as hard as its speed into the
     * slope, not the level. The first look at a craft only notes what's already wet, because a
     * craft set down in the sea, or just broken apart in it, hasn't hit anything.
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
     * Rudders, keels and foils: flat plates in the water.
     *
     * The plate's normal is its part's Z axis. Water flowing across it pushes back along that
     * normal, the same sin(a)cos(a) flat-plate force a wing makes, from the flow across the plate
     * times the flow along it. That's what stops a keeled boat sliding sideways while hardly
     * slowing it going forward. A controllable one that's deflected adds the push its deflection
     * makes, the same way an aircraft's control surfaces do. All of it is scaled by how much of the
     * plate is under water.
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
                // Along the plate's own push direction for the command, like Forces.deflect: across
                // the hull and the mounting radius.
                push += q * surface.controlAuthority * relative.lengthSq *
                    kotlin.math.sin(angle) * kotlin.math.cos(angle) * deflectSign(vessel, i)
            }
            // Never more than would stop the whole craft's motion across the plate in one tick.
            // Water is dense enough that an explicit step of the raw force could reverse it and
            // fling the boat sideways.
            val limit = body.mass * kotlin.math.abs(across) / dt + q * surface.controlAuthority * relative.lengthSq
            if (kotlin.math.abs(push) > limit) push = kotlin.math.sign(push) * limit
            force.setTo(plateNormal).mulInPlace(push)
            body.applyForceAtOffset(force, offset)
        }
    }

    /**
     * Which way along the plate's normal a positive deflection pushes: the direction
     * Forces.controlDeflection's rule gives (across the fuselage and the mounting radius),
     * projected onto the plate's normal.
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
     * How much of the cell centred at [point] is under water, 0..1.
     *
     * A cell sitting across the surface is partly under in proportion to how far its centre is
     * below it, over the cell's own height as seen from above, which for a cell tipped on its side
     * is its width, not its height. Without that, the lift would switch on and off as each cell
     * centre crossed the surface, and a floating hull would buzz.
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

    private companion object {
        /**
         * How much of a hull's bow drag there is at [speed] m/s through the water, for a hull
         * [length] metres long, 0..1. It's the wave the bow makes, and that's all about the
         * Froude number, speed over the square root of g times length. At a walking pace a hull
         * makes almost none, and it builds steeply to all of it as it nears hull speed, a Froude
         * number of about 0.4. Charged in full at every speed, it held a twenty-five metre schooner to
         * four knots in a fresh breeze.
         */
        fun waveMaking(speed: Double, length: Double): Double {
            val froude = speed / sqrt(STANDARD_GRAVITY * length.coerceAtLeast(0.5))
            val share = froude / HULL_SPEED_FROUDE
            // Then the hump: just past hull speed the hull is trying to climb its own bow wave, and
            // more power buys very little more speed. That's the wall a ship runs into. A planing
            // hull gets over it and up on top of the water, and the drag eases off again.
            val over = (froude - HUMP_FROUDE) / HUMP_WIDTH
            return (share * share * share * share).coerceAtMost(1.0) + HUMP * kotlin.math.exp(-over * over)
        }

        /**
         * The skin friction coefficient at [speed] through the water, on a hull [length] long: the
         * ITTC 1957 line, 0.075 / (log10 Re - 2)^2, with a form factor for a hull that isn't a flat
         * plate and an allowance for one that isn't polished. A long hull going quickly has a high
         * Reynolds number and a low coefficient, which is why a ship slips along where a dinghy
         * drags. A single number had a twenty-five metre ship dragging like a dinghy.
         */
        fun skinFriction(speed: Double, length: Double): Double {
            val reynolds = (speed * length / WATER_VISCOSITY).coerceAtLeast(MIN_REYNOLDS)
            val log = kotlin.math.log10(reynolds) - 2.0
            return (1.0 + FORM_FACTOR) * 0.075 / (log * log) + ROUGHNESS
        }

        const val STANDARD_GRAVITY = 9.81

        /** The Froude number at which a displacement hull reaches hull speed. */
        const val HULL_SPEED_FROUDE = 0.4

        /** The wave drag's hump past hull speed: how much more there is at its worst, where, and how wide. */
        const val HUMP = 2.0
        const val HUMP_FROUDE = 0.5
        const val HUMP_WIDTH = 0.12

        /** Seawater's kinematic viscosity, in m²/s. */
        const val WATER_VISCOSITY = 1.19e-6

        /** The lowest Reynolds number the friction line is taken at: a crawl, where it's laminar. */
        const val MIN_REYNOLDS = 1.0e5

        /** How much more than a flat plate of its area a hull's friction drags. */
        const val FORM_FACTOR = 0.2

        /** The allowance for a hull that isn't polished smooth. */
        const val ROUGHNESS = 0.0004

        /** How long a craft's waves are carried on before they're built again, in seconds, and how far it can go meanwhile, in metres. */
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
         * Points around a hull's rim that water can come in over, and the weir coefficient, in
         * m^0.5/s.
         */
        const val RIM_POINTS = 12
        const val WEIR = 1.7

        /**
         * Breaking crests over an open hull: m³/s per metre of its rim when the sea breaks fully
         * over it, from this much breaking up, in a sea of this height in metres or more.
         */
        const val BREAKER = 0.1
        const val BREAKING_FROM = 0.25
        const val BREAKER_HS = 6.0

        /** The pump, in m³/s. */
        const val PUMP = 0.01

        /** Ticks between mass recalculations while flooding. */
        const val MASS_EVERY = 10

        const val MAX_SPLASHES = 16

        /** How much of the speed across the water counts in a splash. Skimming in is gentler than diving. */
        const val SKIM_SHARE = 0.3

        /**
         * The drag coefficient of a hull face moving through water. It's around a bluff body's. A
         * hull isn't streamlined, but it is long, and the per-face areas are what make it prefer to
         * go forwards.
         */
        const val WATER_CD = 0.8

        /**
         * The drag coefficient of a hull's ends, moving along it. The bow and stern are shaped to
         * part the water, and a boat is built to go forwards.
         */
        const val HULL_END_CD = 0.25

        /**
         * The same for a keel, a rudder or a foil along its chord, on its face into the water. A
         * streamlined section drags a tenth of a blunt one. Met square on, a ship's keel five metres
         * long cost a schooner a knot and a half.
         */
        const val FOIL_END_CD = 0.08

        /** How nearly a hull part's +Y has to run along the craft to count as its length. */
        const val ALONG_HULL = 0.7

        /**
         * Cells in one column share the surface over them while they stand within this many metres
         * of each other across the horizontal.
         */
        private const val COLUMN_SHARE = 0.1

        /** How far past a cell's face, in metres, to look for more of the craft against it. */
        private const val FACE_PROBE = 0.02

        /** Face bits: which of a cell's faces meet the water. */
        private const val PLUS_X = 1; private const val MINUS_X = 2
        private const val PLUS_Y = 4; private const val MINUS_Y = 8
        private const val PLUS_Z = 16; private const val MINUS_Z = 32

        /**
         * The linear part of water drag, as the speed at which it equals the quadratic part. It's
         * sized so the stock boat's heave is damped to about a third of critical, so it settles in
         * a couple of bobs and still visibly floats instead of looking set in jelly.
         */
        const val WAVE_MAKING_SPEED = 1.0

        /** The same along a hull, ahead and astern. */
        const val WAVE_MAKING_AHEAD = 0.3
    }
}
