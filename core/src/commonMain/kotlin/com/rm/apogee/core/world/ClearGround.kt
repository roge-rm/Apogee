package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.ScatterKind
import com.rm.apogee.core.terrain.SurfaceMaterial
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Somewhere clear to set a craft down: on land (or water, for one that floats), near enough level,
 * not lava, with no tree, rock or other craft on it. The auto-land looks for one near where it is
 * before it comes down, instead of coming down on whatever's there. A helicopter let down into a
 * forest had its rotor in the trees, and a plane landed straight ahead two and a half kilometres
 * short of the runway, among them.
 *
 * It all works in the body-fixed frame, where the ground and its trees don't move. The ground and
 * the trees are the same on every machine, but the other craft are only as each sees them, so a
 * client's choice can differ from the server's where another craft is close.
 */
internal class ClearGround {
    /** Everything standing near the search, gathered once: x, y, z, reach, per thing. */
    private var things = DoubleArray(0)
    private var thingCount = 0

    /** The solid parts of other craft near the search, body-fixed: x, y, z, reach, per part. */
    private var parts = DoubleArray(0)
    private var partCount = 0

    private val east = Vec3()
    private val north = Vec3()
    private val probe = Vec3()
    private val scratch = Vec3()
    private val world = Vec3()

    /**
     * Gathers what stands within [reach] metres of body-fixed [centre] on [attractor]: its trees
     * and rocks (less the [felled] ones), and the solid parts of [others] other than [self] on the
     * ground there, with the body turned to [rotation]. Done once before a search.
     */
    fun gather(attractor: CelestialBody, centre: Vec3, reach: Double, felled: Set<Long>, others: Collection<Vessel>, self: Vessel, rotation: Quat) {
        thingCount = 0
        partCount = 0
        val radius = attractor.radius
        attractor.terrain?.scatter?.forEachBlockNear(centre, reach + SCATTER_MARGIN, scratch) { block ->
            for (k in 0 until block.count) {
                if (block.ids[k] in felled) continue
                val kind = ScatterKind.of(block.kinds[k].toInt())
                // A shrub or a stone is nothing to a craft coming down on it. Counted, there was
                // nowhere in a forest's clearings clear enough for a plane.
                if (kind in SMALL) continue
                val size = block.sizes[k].toDouble()
                // A tree's crown, not only its trunk, which is what a rotor or a wing meets.
                val reachOf = max(kind.radius * size, kind.height * size * CROWN_SHARE)
                addThing(block.x[k], block.y[k], block.z[k], reachOf)
            }
        }
        val inverse = rotation.conjugate()
        scratch.setTo(centre).normalizeInPlace().mulInPlace(radius)
        for (other in others) {
            if (other === self || other.referenceBodyId != self.referenceBodyId) continue
            inverse.rotate(other.body.position, world)
            if (world.distanceTo(scratch) > other.contactRadius + reach + SCATTER_MARGIN) continue
            for (i in other.defs.indices) {
                val def = other.defs[i]
                if (!def.solid || other.isBroken(i)) continue
                other.partPositionWorld(i, probe)
                inverse.rotate(probe, world)
                if (world.distanceTo(scratch) > reach + SCATTER_MARGIN + 50.0) continue
                val h = def.boundsHalfExtents
                addPart(world.x, world.y, world.z, max(h.x, max(h.y, h.z)))
            }
        }
    }

    private fun addThing(x: Double, y: Double, z: Double, r: Double) {
        if (things.size < (thingCount + 1) * 4) things = things.copyOf(max(64, things.size * 2))
        val o = thingCount * 4
        things[o] = x; things[o + 1] = y; things[o + 2] = z; things[o + 3] = r
        thingCount++
    }

    private fun addPart(x: Double, y: Double, z: Double, r: Double) {
        if (parts.size < (partCount + 1) * 4) parts = parts.copyOf(max(64, parts.size * 2))
        val o = partCount * 4
        parts[o] = x; parts[o + 1] = y; parts[o + 2] = z; parts[o + 3] = r
        partCount++
    }

    /**
     * Whether a circle [radius] metres across from body-fixed unit [direction] is clear to land on,
     * against what [gather] found. On water only if the craft [floats].
     */
    fun isClear(attractor: CelestialBody, direction: Vec3, radius: Double, floats: Boolean): Boolean {
        val terrain = attractor.terrain ?: return true
        val r = attractor.radius
        val centre = terrain.elevation(direction)
        val wet = terrain.hasOcean && centre < 0.0
        if (wet && !floats) return false
        if (!wet && terrain.material(direction, centre, 0.0) == SurfaceMaterial.LAVA) return false
        // Level enough, and on the same footing all round: all land, or all water.
        tangents(direction)
        var i = 0
        while (i < RIM_POINTS) {
            val a = i * 2.0 * PI / RIM_POINTS
            probe.setTo(direction).mulInPlace(r).addScaledInPlace(east, cos(a) * radius).addScaledInPlace(north, sin(a) * radius).normalizeInPlace()
            val e = terrain.elevation(probe)
            val rimWet = terrain.hasOcean && e < 0.0
            if (rimWet != wet) return false
            if (!wet && abs(e - centre) > radius * MOST_SLOPE) return false
            i++
        }
        // Nothing standing on it.
        val ground = r + (if (wet) 0.0 else centre)
        probe.setTo(direction).normalizeInPlace().mulInPlace(ground)
        for (k in 0 until thingCount) {
            val o = k * 4
            if (flatDistance(probe, things[o], things[o + 1], things[o + 2]) < radius + things[o + 3]) return false
        }
        for (k in 0 until partCount) {
            val o = k * 4
            // Only what's on the ground there, not something flying over.
            val height = sqrt(parts[o] * parts[o] + parts[o + 1] * parts[o + 1] + parts[o + 2] * parts[o + 2]) - ground
            if (height > PART_ABOVE) continue
            if (flatDistance(probe, parts[o], parts[o + 1], parts[o + 2]) < radius + parts[o + 3]) return false
        }
        return true
    }

    /**
     * The nearest clear spot to body-fixed [from], a circle [radius] across, looked for out to [reach]
     * metres in rings, into [out] as a body-fixed unit direction. Null if there's none in reach.
     */
    fun find(attractor: CelestialBody, from: Vec3, radius: Double, reach: Double, floats: Boolean, out: Vec3): Vec3? {
        val centre = Vec3().setTo(from).normalizeInPlace()
        if (isClear(attractor, centre, radius, floats)) return out.setTo(centre)
        val r = attractor.radius
        val step = max(radius, MIN_STEP)
        tangents(centre)
        val e = Vec3().setTo(east)
        val n = Vec3().setTo(north)
        var ring = step
        while (ring <= reach) {
            val count = max(6, (2.0 * PI * ring / step).toInt())
            for (k in 0 until count) {
                val a = k * 2.0 * PI / count
                out.setTo(centre).mulInPlace(r).addScaledInPlace(e, cos(a) * ring).addScaledInPlace(n, sin(a) * ring).normalizeInPlace()
                if (isClear(attractor, out, radius, floats)) return out
            }
            ring += step
        }
        return null
    }

    /**
     * Whether a straight strip [length] metres long and [halfWidth] metres either side of its line,
     * from body-fixed [start] along the level direction [along], is clear to land and roll out on.
     */
    fun stripClear(attractor: CelestialBody, start: Vec3, along: Vec3, length: Double, halfWidth: Double, floats: Boolean): Boolean {
        val r = attractor.radius
        val terrain = attractor.terrain
        val first = terrain?.elevation(start) ?: 0.0
        var d = 0.0
        while (d <= length) {
            scratch.setTo(start).normalizeInPlace().mulInPlace(r).addScaledInPlace(along, d).normalizeInPlace()
            if (!isClear(attractor, scratch, halfWidth, floats)) return false
            // And no hill or dip along it a wheel would run into.
            if (terrain != null && !(terrain.hasOcean && first < 0.0) && abs(terrain.elevation(scratch) - first) > max(STRIP_RISE, d * STRIP_SLOPE)) return false
            d += STRIP_STEP
        }
        return true
    }

    /** Level east and north at body-fixed [direction], into [east] and [north]. */
    private fun tangents(direction: Vec3) {
        probe.setTo(direction).normalizeInPlace()
        east.setTo(0.0, 1.0, 0.0).crossInPlace(probe)
        if (east.lengthSq < 1e-12) east.setTo(1.0, 0.0, 0.0).crossInPlace(probe)
        east.normalizeInPlace()
        north.setTo(probe).crossInPlace(east)
    }

    /** How far apart [a] and the point [x], [y], [z] are across the level at [a], in metres. */
    private fun flatDistance(a: Vec3, x: Double, y: Double, z: Double): Double {
        val len = a.length
        val ux = a.x / len; val uy = a.y / len; val uz = a.z / len
        val dx = x - a.x; val dy = y - a.y; val dz = z - a.z
        val along = dx * ux + dy * uy + dz * uz
        val fx = dx - along * ux; val fy = dy - along * uy; val fz = dz - along * uz
        return sqrt(fx * fx + fy * fy + fz * fz)
    }

    companion object {
        /** A tree's crown, as a share of its height, out from its trunk. */
        const val CROWN_SHARE = 0.3

        /** How much further than asked to gather what's standing, in metres. */
        const val SCATTER_MARGIN = 20.0

        /** What stands too small to matter to a landing. */
        private val SMALL = setOf(ScatterKind.SHRUB, ScatterKind.BOULDER_SMALL, ScatterKind.NODULE)

        /** How many points round the edge of a spot are tried for level ground. */
        const val RIM_POINTS = 6

        /** The most the ground can rise or fall across a spot, as a share of its radius: about eight degrees. */
        const val MOST_SLOPE = 0.14

        /** The closest spots are tried to each other, in metres. */
        const val MIN_STEP = 8.0

        /** Higher than this over the ground, in metres, another craft's part is flying, not in the way. */
        const val PART_ABOVE = 40.0

        /** A landing strip's points, how far apart, and how much it can rise or fall along it. */
        const val STRIP_STEP = 25.0
        const val STRIP_RISE = 3.0
        const val STRIP_SLOPE = 0.03
    }
}
