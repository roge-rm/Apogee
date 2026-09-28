package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.Terrain
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math
import com.rm.apogee.core.lruMapOf

/**
 * What the ground's shape and surface do to the wind, described at kilometre scale.
 *
 * Each lattice point looks at the ground around it on a ring 1.2 km out and boils it down to a few
 * numbers the wind can use whichever way it blows:
 * - whether it's a crest or a hollow (the centre against the ring's average);
 * - the slope (the ring's first harmonic), so wind meeting rising ground lifts and wind leaving it
 *   sinks;
 * - whether it's a valley, and along which axis (the second harmonic, because a valley is a ring
 *   that's low on two opposite sides and high on the other two);
 * - how rough the surface is, and how much it heats up for thermals.
 *
 * Points sit on a 3D lattice [SPACING] apart, projected onto the ground, and a sample blends the
 * eight around it, so the wind never jumps as a craft crosses from one to the next. Every point
 * comes purely from the terrain, and they're cached because each one costs nine elevation samples.
 */
class TerrainWind(private val terrain: Terrain, private val bodyRadius: Double) {

    /** The descriptor layout, one FloatArray of [SIZE] per point. */
    companion object {
        const val H0 = 0          // elevation, m
        const val MEAN = 1        // ring average, m
        const val GRAD = 2        // gradient, body-fixed, dh per metre (3)
        const val AXIS = 5        // valley axis, body-fixed unit (3)
        const val CHANNEL = 8     // how strongly the ground funnels the wind, 0..1
        const val Z0 = 9          // roughness length, m
        const val HEAT = 10       // thermal potential, 0..1
        const val OCEAN = 11      // 1 over open sea
        const val RELIEF = 12     // spread of the ring, m
        const val SIZE = 13

        /** The lattice spacing, in metres. */
        const val SPACING = 600.0

        /** The radius of the ring each point looks at, in metres. */
        const val RING = 1_200.0

        private const val MAX_CACHED = 24_000

        /** Aerodynamic roughness length in metres, by what the ground is. */
        fun roughness(material: SurfaceMaterial): Double = when (material) {
            SurfaceMaterial.FOREST -> 1.0
            SurfaceMaterial.SCREE -> 0.1
            SurfaceMaterial.ROCK, SurfaceMaterial.BASALT -> 0.06
            SurfaceMaterial.GRASS, SurfaceMaterial.CLAY -> 0.05
            SurfaceMaterial.DIRT -> 0.03
            SurfaceMaterial.MUD -> 0.02
            SurfaceMaterial.SAND, SurfaceMaterial.REGOLITH -> 0.01
            SurfaceMaterial.SNOW, SurfaceMaterial.ICE -> 0.002
            SurfaceMaterial.CONCRETE, SurfaceMaterial.ASPHALT -> 0.005
            else -> 0.05
        }

        /** How readily the ground heats up and sets off thermals, 0..1. */
        fun heat(material: SurfaceMaterial): Double = when (material) {
            SurfaceMaterial.SAND -> 1.0
            SurfaceMaterial.ROCK, SurfaceMaterial.BASALT -> 0.9
            SurfaceMaterial.SCREE, SurfaceMaterial.DIRT -> 0.8
            SurfaceMaterial.CLAY -> 0.7
            SurfaceMaterial.REGOLITH -> 0.6
            SurfaceMaterial.GRASS -> 0.5
            SurfaceMaterial.MUD -> 0.2
            SurfaceMaterial.FOREST -> 0.15
            SurfaceMaterial.SNOW -> 0.05
            SurfaceMaterial.CONCRETE, SurfaceMaterial.ASPHALT -> 0.85
            SurfaceMaterial.RED_DUST, SurfaceMaterial.TESSERA -> 0.9
            SurfaceMaterial.SULFUR, SurfaceMaterial.LAVA -> 1.0
            SurfaceMaterial.ORGANIC_SAND, SurfaceMaterial.THOLIN -> 0.6
            else -> 0.0
        }

        /** Open water: glassy, cold, and no thermals. */
        const val SEA_ROUGHNESS = 0.0003
    }

    private val cache = lruMapOf<Long, FloatArray>(4096, MAX_CACHED)

    private val corner = Vec3()
    private val cornerDir = Vec3()
    private val east = Vec3()
    private val north = Vec3()
    private val ringDir = Vec3()

    /**
     * The description at [direction] (a unit vector, body-fixed), blended from the eight lattice
     * points around it, into [out] (size [SIZE]).
     */
    fun describe(direction: Vec3, out: DoubleArray) {
        val px = direction.x * bodyRadius / SPACING
        val py = direction.y * bodyRadius / SPACING
        val pz = direction.z * bodyRadius / SPACING
        val ix = floor(px).toInt(); val iy = floor(py).toInt(); val iz = floor(pz).toInt()
        val fx = px - ix; val fy = py - iy; val fz = pz - iz
        out.fill(0.0)
        for (c in 0 until 8) {
            val dx = c and 1; val dy = (c shr 1) and 1; val dz = (c shr 2) and 1
            val w = (if (dx == 1) fx else 1 - fx) * (if (dy == 1) fy else 1 - fy) * (if (dz == 1) fz else 1 - fz)
            if (w <= 0.0) continue
            val d = point(ix + dx, iy + dy, iz + dz)
            for (k in 0 until SIZE) out[k] += w * d[k]
        }
        // The blended axis is a blend of unit vectors, so keep it unit length.
        val ax = out[AXIS]; val ay = out[AXIS + 1]; val az = out[AXIS + 2]
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len > 1e-9) { out[AXIS] /= len; out[AXIS + 1] /= len; out[AXIS + 2] /= len }
    }

    private fun point(ix: Int, iy: Int, iz: Int): FloatArray {
        val key = (ix.toLong() and 0x1FFFFF) or ((iy.toLong() and 0x1FFFFF) shl 21) or ((iz.toLong() and 0x1FFFFF) shl 42)
        cache[key]?.let { return it }
        corner.setTo(ix * SPACING, iy * SPACING, iz * SPACING)
        cornerDir.setTo(corner).normalizeInPlace()
        val d = compute(cornerDir)
        cache[key] = d
        return d
    }

    private fun compute(d: Vec3): FloatArray {
        frame(d, east, north)
        // The air feels the sea's surface, not the floor under it. A canyon or a seamount down
        // there doesn't steer any wind.
        val sea = terrain.hasOcean
        val ground = terrain.elevation(d)
        val h0 = if (sea) max(ground, 0.0) else ground
        var sum = 0.0; var a1 = 0.0; var b1 = 0.0; var a2 = 0.0; var b2 = 0.0
        val heights = DoubleArray(8)
        for (k in 0 until 8) {
            val theta = k * Math.PI / 4.0
            val c = cos(theta); val s = sin(theta)
            ringDir.setTo(d).mulInPlace(bodyRadius)
                .addScaledInPlace(east, c * RING).addScaledInPlace(north, s * RING)
                .normalizeInPlace()
            val h = terrain.elevation(ringDir).let { if (sea) max(it, 0.0) else it }
            heights[k] = h
            sum += h
            a1 += h * c; b1 += h * s
            a2 += h * cos(2 * theta); b2 += h * sin(2 * theta)
        }
        val mean = sum / 8.0
        a1 *= 0.25; b1 *= 0.25; a2 *= 0.25; b2 *= 0.25
        var spread = 0.0
        for (h in heights) spread += (h - mean) * (h - mean)
        val relief = sqrt(spread / 8.0)

        // dh/dm along east and north.
        val ge = a1 / RING; val gn = b1 / RING
        // The ring's lowest heading is the valley's line.
        val low = (atan2(b2, a2) + Math.PI) / 2.0
        val axisE = cos(low); val axisN = sin(low)
        val anisotropy = hypot(a2, b2)
        val hollow = mean - h0
        val channel = ((anisotropy / 200.0).coerceIn(0.0, 0.8) * smooth(-50.0, 150.0, hollow))

        val ocean = sea && ground < 0.0
        val gradient = hypot(ge, gn)
        val slope = 1.0 - 1.0 / sqrt(1.0 + gradient * gradient)
        val material = if (ocean) null else terrain.material(d, h0, slope)

        val out = FloatArray(SIZE)
        out[H0] = h0.toFloat()
        out[MEAN] = mean.toFloat()
        out[GRAD] = (east.x * ge + north.x * gn).toFloat()
        out[GRAD + 1] = (east.y * ge + north.y * gn).toFloat()
        out[GRAD + 2] = (east.z * ge + north.z * gn).toFloat()
        out[AXIS] = (east.x * axisE + north.x * axisN).toFloat()
        out[AXIS + 1] = (east.y * axisE + north.y * axisN).toFloat()
        out[AXIS + 2] = (east.z * axisE + north.z * axisN).toFloat()
        out[CHANNEL] = channel.toFloat()
        out[Z0] = (if (material == null) SEA_ROUGHNESS else roughness(material)).toFloat()
        out[HEAT] = (if (material == null) 0.0 else heat(material)).toFloat()
        out[OCEAN] = if (ocean) 1f else 0f
        out[RELIEF] = relief.toFloat()
        return out
    }
}

/** Local east and north at unit [up] on a body turning around +Y. */
internal fun frame(up: Vec3, east: Vec3, north: Vec3) {
    east.setTo(up.z, 0.0, -up.x)
    if (east.lengthSq < 1e-12) east.setTo(1.0, 0.0, 0.0) else east.normalizeInPlace()
    north.setTo(up).crossInPlace(east)
}

internal fun smooth(edge0: Double, edge1: Double, x: Double): Double {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}
