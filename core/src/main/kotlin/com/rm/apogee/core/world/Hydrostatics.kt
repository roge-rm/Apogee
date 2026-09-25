package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Water: buoyancy and drag, cell by cell.
 *
 * Each part's volume is carried as a grid of cells
 * ([com.rm.apogee.core.part.PartDef.volumeCells]), and each cell is weighed
 * against the water surface where it is. The fraction of a cell below the
 * surface displaces that fraction of its volume, pushing up at the cell and
 * not at the craft's centre - so a hull that heels has more of itself under
 * on the low side and rights itself, and when there are waves, a crest under
 * the bow lifts the bow. One force at the centre of mass gives neither, and is
 * the failure this is built to avoid.
 *
 * Drag is per cell too, and per axis of the part it belongs to: water resists
 * a cell's motion across each face in proportion to that face's area. A long,
 * narrow hull therefore slides easily along its length and hardly at all
 * sideways - which is a keel, arrived at without anything called a keel, and
 * what lets a boat turn rather than skate.
 */
class Hydrostatics {

    private val rotation = Quat()
    private val bodyFixed = Vec3()
    private val offset = Vec3()
    private val point = Vec3()
    private val waterVelocity = Vec3()
    private val relative = Vec3()
    private val local = Vec3()
    private val force = Vec3()
    private val up = Vec3()
    private val cellAxis = Vec3()

    /**
     * Parts that hit the water this tick, and how hard: the speed into it,
     * plus some of the skim across it. [splashCount] of them.
     */
    val splashParts = IntArray(MAX_SPLASHES)
    val splashSpeeds = DoubleArray(MAX_SPLASHES)
    var splashCount = 0
        private set

    /** Significant wave height where the last craft was, m. */
    var seaHeight: Double = 0.0
        private set

    /** Submerged volume last tick, m³, for tests and the HUD. */
    var submergedVolume: Double = 0.0
        private set

    /** The waves over the craft this tick: see [com.rm.apogee.core.sea.WavePatch]. */
    private val patch = com.rm.apogee.core.sea.WavePatch()
    private val sea: com.rm.apogee.core.sea.SeaSample get() = patch.middle
    private val scratchVelocity = Vec3()
    private val normal = Vec3()

    /** Where the surface is over each volume cell this tick, m from the centre, and how much of the cell is under. */
    private var cellSurface = DoubleArray(256)
    private var cellFraction = DoubleArray(256)

    /** Each part's water velocity this tick, inertial, xyz; NaN in x until worked out. */
    private var partWater = DoubleArray(96)

    fun apply(vessel: Vessel, attractor: CelestialBody, time: Double, dt: Double) {
        submergedVolume = 0.0
        splashCount = 0
        vessel.buoyed = false
        val ocean = attractor.ocean ?: run { vessel.wet = null; return }
        val body = vessel.body

        // The sea where the craft is: its tide, and how big its waves are.
        attractor.rotationAt(time, rotation)
        attractor.toBodyFixed(body.position, rotation, bodyFixed)
        ocean.patch(bodyFixed, time, patch)
        seaHeight = sea.significantHeight

        // Nowhere near the water. The generous margin is the craft's own
        // reach, since that is how far below its centre a cell can be, and
        // the biggest crest the sea here might throw up.
        val altitude = attractor.altitudeOf(body.position)
        if (altitude - vessel.contactRadius > sea.height + SURFACE_MARGIN + CREST_MARGIN * sea.significantHeight) {
            // Clear of it, and known to be: whatever goes under next, fast,
            // is a splash.
            val n = vessel.defs.size
            (vessel.wet?.takeIf { it.size == n } ?: BooleanArray(n).also { vessel.wet = it }).fill(false)
            return
        }

        // Whether there is sea here at all, asked once for the whole craft
        // rather than per cell: a craft is small beside a coastline.
        if (sea.depth <= 0.0) return

        // The water's up is against effective gravity - gravity less what
        // it takes to go round with the planet - not straight out from the
        // centre. Off the equator the two differ by a fraction of a degree,
        // and pushing straight out left every floating boat shoved gently
        // toward the equator, drifting for ever.
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
        for (i in vessel.defs.indices) {
            for (cell in vessel.defs[i].volumeCells) {
                vessel.partPointOffsetWorld(i, cell, offset)
                point.setTo(offset).addInPlace(body.position)
                attractor.toBodyFixed(point, rotation, bodyFixed)
                val surface = attractor.radius + patch.height(bodyFixed)
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

        c = 0
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            val cellList = def.volumeCells
            val cellVolume = def.displacedVolume / cellList.size
            val size = def.volumeCellSize
            val placed = vessel.design.parts[i]
            val faces = exposure(vessel)[i]
            // A hull's ends are shaped to part the water; anything else meets it square on.
            val endCd = if (def.module<com.rm.apogee.core.part.Buoyancy>() != null) HULL_END_CD else WATER_CD

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

                // Drag against the water - which moves with the ground, and
                // with the waves: a crest carries a boat forward, a trough
                // draws it back, and a big sea throws it about.
                body.velocityAtOffset(offset, relative)
                waterVelocity(vessel, attractor, ocean, time, i, waterVelocity)
                relative.subInPlace(waterVelocity)
                val speed = relative.length
                if (speed < 1e-6) continue

                // Into the part's own axes, where its face areas are known.
                body.orientation.inverseRotate(relative, local)
                placed.rotation.inverseRotate(local, local)
                // Only the faces that meet the water: the one leading into
                // the flow along each axis, and only where nothing of the
                // craft lies against it. A hull six cells long meets the
                // water with one bow, not six - and a hull of five sections
                // with one, not five.
                val open = faces[n].toInt()
                val areaX = if (open and (if (local.x > 0.0) PLUS_X else MINUS_X) != 0) size.y * size.z * fraction else 0.0
                val areaY = if (open and (if (local.y > 0.0) PLUS_Y else MINUS_Y) != 0) size.x * size.z * fraction else 0.0
                val areaZ = if (open and (if (local.z > 0.0) PLUS_Z else MINUS_Z) != 0) size.x * size.y * fraction else 0.0
                // |v| + a constant, not |v|: the constant is wave-making.
                // A hull moving through the surface sheds energy into the
                // waves it makes, in proportion to speed rather than its
                // square, and that is what stops a floating boat bobbing. On
                // quadratic drag alone the stock boat was still heaving at a
                // tenth of a metre a second half a minute after launch.
                // And skin friction: the water dragging along every wetted
                // face it slides past - what holds back a long flat hull
                // skimming along with almost no bow in the water.
                val sideX = (if (open and PLUS_X != 0) 1 else 0) + (if (open and MINUS_X != 0) 1 else 0)
                val sideY = (if (open and PLUS_Y != 0) 1 else 0) + (if (open and MINUS_Y != 0) 1 else 0)
                val sideZ = if (open and MINUS_Z != 0) 1 else 0
                val wetX = sideX * size.y * size.z * fraction
                val wetY = sideY * size.x * size.z * fraction
                val wetZ = sideZ * size.x * size.y
                val flow = sqrt(local.x * local.x + local.y * local.y + local.z * local.z)
                val skin = 0.5 * rho * SKIN_FRICTION * flow
                local.setTo(
                    -0.5 * rho * WATER_CD * areaX * (abs(local.x) + WAVE_MAKING_SPEED) * local.x - skin * (wetY + wetZ) * local.x,
                    -0.5 * rho * endCd * areaY * (abs(local.y) + WAVE_MAKING_SPEED) * local.y - skin * (wetX + wetZ) * local.y,
                    -0.5 * rho * WATER_CD * areaZ * (abs(local.z) + WAVE_MAKING_SPEED) * local.z - skin * (wetX + wetY) * local.z,
                )
                placed.rotation.rotate(local, force)
                body.orientation.rotate(force, force)

                // Never more than stops this cell's share of the craft in one
                // tick. Quadratic drag on a craft arriving at a hundred metres
                // a second is a force that would reverse its motion, and an
                // explicit step would fling it back out of the water.
                val limit = body.mass / submergedCells * speed / dt
                val magnitude = force.length
                if (magnitude > limit) force.mulInPlace(limit / magnitude)
                body.applyForceAtOffset(force, offset)
            }
        }
    }

    /**
     * How fast the water around part [index] is moving, inertial, into
     * [out]: the ground's own motion, and the waves' at the part's depth.
     * Worked out once a part per tick - the water hardly changes across one.
     */
    private fun waterVelocity(vessel: Vessel, attractor: CelestialBody, ocean: com.rm.apogee.core.terrain.Ocean, time: Double, index: Int, out: Vec3): Vec3 {
        val o = index * 3
        if (!partWater[o].isNaN()) return out.setTo(partWater[o], partWater[o + 1], partWater[o + 2])
        vessel.partOffsetWorld(index, scratchOffset)
        scratchPoint.setTo(scratchOffset).addInPlace(vessel.body.position)
        attractor.toBodyFixed(scratchPoint, rotation, scratchFixed)
        val surface = attractor.radius + patch.height(scratchFixed)
        patch.velocity(scratchFixed, kotlin.math.max(0.0, surface - scratchPoint.length), scratchVelocity)
        rotation.rotate(scratchVelocity, out)
        attractor.surfaceVelocityAt(scratchPoint, scratchGround)
        out.addInPlace(scratchGround)
        partWater[o] = out.x; partWater[o + 1] = out.y; partWater[o + 2] = out.z
        return out
    }

    /**
     * Which faces of each part's volume cells meet the water, per part and
     * cell, as bits - worked out once for a craft's shape and kept until it
     * changes. A face is open unless the point just past it lies inside
     * some part of the craft: its own next cell, or the hull section it is
     * joined to.
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

    private val spin = Vec3()
    private val spinning = Vec3()
    private val buoyUp = Vec3()

    private val scratchOffset = Vec3()
    private val scratchPoint = Vec3()
    private val scratchFixed = Vec3()
    private val scratchGround = Vec3()

    /**
     * Water coming aboard. An open hull ships whatever comes over its
     * gunwale - a crest breaking over it, or a heel that puts the edge under
     * - as water over a weir, by the depth over the edge to the power of one
     * and a half; a holed hull, open or not, the same through its broken
     * sides. Carried as weight; a slow pump takes it out again while nothing
     * is coming in. Full enough, the boat sinks.
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
            // The rim: points round the top edge, each standing for its share of it.
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
                // Breaking where the crests are: the sea's breaking, by how
                // high the water stands here over the rest.
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
            // Mass properties again every so often while it changes, not every tick.
            if (++floodedSinceMass >= MASS_EVERY) { vessel.recomputeMass(); floodedSinceMass = 0 }
        }
    }

    private var floodedSinceMass = 0
    private val rimLocal = Vec3()

    /**
     * Which parts went under this tick, and how fast - into the surface as
     * it faces, moving as it does: a hull slamming into the face of a wave
     * hits it as hard as its speed into the slope, not the level. The first
     * look at a craft only notes what is already wet: a craft set down in
     * the sea, or just broken apart in it, has not hit anything.
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
     * The plate's normal is its part's Z axis. Water flowing across it pushes
     * back along that normal - the same sin(a)cos(a) flat-plate force a wing
     * makes, from the flow across the plate times the flow along it - which is
     * what stops a keeled boat sliding sideways while barely slowing it going
     * ahead. A controllable one deflected adds the push its deflection makes,
     * as an aircraft's control surfaces do. All of it scaled by how much of
     * the plate is under.
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
                // Along the plate's own push direction for the command, as
                // Forces.deflect: across the hull and the mounting radius.
                push += q * surface.controlAuthority * relative.lengthSq *
                    kotlin.math.sin(angle) * kotlin.math.cos(angle) * deflectSign(vessel, i)
            }
            // Never more than would stop the whole craft's motion across the
            // plate in one tick: water is dense enough that an explicit step
            // of the raw force could reverse it and fling the boat sideways.
            val limit = body.mass * kotlin.math.abs(across) / dt + q * surface.controlAuthority * relative.lengthSq
            if (kotlin.math.abs(push) > limit) push = kotlin.math.sign(push) * limit
            force.setTo(plateNormal).mulInPlace(push)
            body.applyForceAtOffset(force, offset)
        }
    }

    /**
     * Which way along the plate's normal a positive deflection pushes: the
     * direction Forces.controlDeflection's rule gives - across the fuselage
     * and the mounting radius - projected onto the plate's normal.
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
     * A cell straddling the surface is partly submerged in proportion to how
     * far its centre is below it, over the cell's own height as seen from
     * above - which for a cell tipped on its side is its width, not its
     * height. Without that the lift would switch on and off as each cell
     * centre crossed the surface, and a floating hull would buzz.
     */
    private fun depthFraction(
        vessel: Vessel,
        partIndex: Int,
        point: Vec3,
        surface: Double,
    ): Double {
        val depth = surface - point.length

        // The cell's vertical extent: its three edges projected onto the local
        // vertical.
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
        /** Metres above the surface within which a craft is worth sampling. */
        const val SURFACE_MARGIN = 2.0

        /** How many significant wave heights above the mean a crest may reach, for the margin. */
        const val CREST_MARGIN = 1.2

        /** Points round a hull's rim that water can come in over; and the weir coefficient, m^0.5/s. */
        const val RIM_POINTS = 12
        const val WEIR = 1.7

        /**
         * Breaking crests over an open hull: m³/s a metre of its rim when the
         * sea breaks full over it, from this much breaking up, in a sea of
         * this height, m, or more.
         */
        const val BREAKER = 0.1
        const val BREAKING_FROM = 0.25
        const val BREAKER_HS = 6.0

        /** The pump, m³/s. */
        const val PUMP = 0.01

        /** Ticks between mass recomputations while flooding. */
        const val MASS_EVERY = 10

        const val MAX_SPLASHES = 16

        /** How much of the speed across the water counts in a splash: skimming in is gentler than diving. */
        const val SKIM_SHARE = 0.3

        /**
         * Drag coefficient of a hull face moving through water. Bluff-body
         * order; a hull is not streamlined, but it is long, and the per-face
         * areas are what make it prefer to go forwards.
         */
        const val WATER_CD = 0.8

        /**
         * Drag coefficient of a hull's ends, moving along it: bow and stern
         * are shaped to part the water, and a boat is built to go forwards.
         */
        const val HULL_END_CD = 0.25

        /** Skin friction coefficient of water sliding along a wetted face: turbulent, on a hull not over-smooth. */
        const val SKIN_FRICTION = 0.006

        /** How far past a cell's face, m, to look for more of the craft against it. */
        private const val FACE_PROBE = 0.02

        /** Face bits: which of a cell's faces meet the water. */
        private const val PLUS_X = 1; private const val MINUS_X = 2
        private const val PLUS_Y = 4; private const val MINUS_Y = 8
        private const val PLUS_Z = 16; private const val MINUS_Z = 32

        /**
         * The linear part of water drag, as the speed at which it equals the
         * quadratic part. Sized so the stock boat's heave is damped to about
         * a third of critical: settles in a couple of bobs, still visibly
         * floats rather than being set in jelly.
         */
        const val WAVE_MAKING_SPEED = 1.0
    }
}
