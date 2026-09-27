package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.Noise.simplex
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shapes worlds are made of, as pure functions of a place on the sphere: craters and great
 * basins, shield volcanoes, canyons, cliff scarps, dunes, cracks in ice, grooves, broken chaos,
 * pancake domes, rivers, ridged mountains and lone mesas. Each world's own land ([WorldLand]) is a
 * handful of these, placed and weighted to make it what it is.
 *
 * Positions are metres on the datum sphere (a unit direction times the body's radius), or unit
 * directions where only the direction matters. Everything comes from its seed, so every machine
 * grows the same ground.
 */
internal object Landforms {

    /** A unit direction from a latitude and longitude, in degrees. */
    fun at(latDegrees: Double, lonDegrees: Double): DoubleArray {
        val la = Math.toRadians(latDegrees); val lo = Math.toRadians(lonDegrees)
        return doubleArrayOf(cos(la) * cos(lo), sin(la), cos(la) * sin(lo))
    }

    /** Metres along the ground from unit [nx],[ny],[nz] to unit [c], on a body of [radius]. */
    fun distance(nx: Double, ny: Double, nz: Double, c: DoubleArray, radius: Double): Double {
        // Chord to arc: exact at any distance, and well behaved up close.
        val dx = nx - c[0]; val dy = ny - c[1]; val dz = nz - c[2]
        val chord = sqrt(dx * dx + dy * dy + dz * dz)
        return 2.0 * asin((chord / 2.0).coerceAtMost(1.0)) * radius
    }

    /** Fractal simplex, about -1..1, [octaves] deep, starting at [frequency] per metre. */
    fun fbm(seed: Int, x: Double, y: Double, z: Double, frequency: Double, octaves: Int): Double {
        var total = 0.0; var amplitude = 1.0; var f = frequency; var norm = 0.0
        for (o in 0 until octaves) {
            total += simplex(seed + o, x * f, y * f, z * f) * amplitude
            norm += amplitude
            amplitude *= 0.5
            f *= 2.03
        }
        return total / norm
    }

    /**
     * Ridged fractal, 0..1, with sharp crests where the simplex crosses zero, for mountain chains
     * and folded ground.
     */
    fun ridged(seed: Int, x: Double, y: Double, z: Double, frequency: Double, octaves: Int): Double {
        var total = 0.0; var amplitude = 1.0; var f = frequency; var norm = 0.0
        for (o in 0 until octaves) {
            val r = 1.0 - abs(simplex(seed + o, x * f, y * f, z * f))
            total += r * r * amplitude
            norm += amplitude
            amplitude *= 0.5
            f *= 2.1
        }
        return total / norm
    }

    fun smooth(t: Double) = Noise.smoothstep(t.coerceIn(0.0, 1.0))

    /** 1 inside [edge], falling to 0 over [width] beyond it. */
    fun within(d: Double, edge: Double, width: Double): Double = 1.0 - smooth((d - edge) / width)

    /**
     * A shield volcano [d] metres from its summit: broad and gently domed out to [radius], [height]
     * tall, with a caldera [calderaRadius] across sunk [calderaDepth] into its top.
     */
    fun shield(d: Double, radius: Double, height: Double, calderaRadius: Double = 0.0, calderaDepth: Double = 0.0): Double {
        if (d >= radius) return 0.0
        val x = d / radius
        var h = height * (1.0 - x) * (1.0 - x) * (1.0 + 2.0 * x) * 0.5 + height * 0.5 * (1.0 - x * x) * (1.0 - x)
        if (calderaRadius > 0.0 && d < calderaRadius * 1.3) {
            val c = d / calderaRadius
            h -= calderaDepth * (1.0 - smooth((c - 0.85) / 0.45))
        }
        return h
    }

    /**
     * A great basin [d] metres from its middle: a broad bowl [radius] across and [depth] deep,
     * ringed by [rings] low ridges in circles out beyond its rim. It's the scar of a blow that
     * nearly split the world.
     */
    fun basin(d: Double, radius: Double, depth: Double, rings: Int = 0, ringHeight: Double = 0.0): Double {
        val x = d / radius
        var h = if (x < 1.0) -depth * (1.0 - x * x) * (1.0 - 0.3 * x * x) else 0.0
        // A rim, rounded.
        h += depth * 0.15 * exp(-((x - 1.0) / 0.12) * ((x - 1.0) / 0.12))
        for (k in 1..rings) {
            val at = 1.0 + 0.35 * k
            val e = (x - at) / 0.05
            h += ringHeight * exp(-e * e) / k
        }
        return h
    }

    /**
     * How far unit [n] is from the great-circle arc from unit [a] to unit [b], in metres on a body
     * of [radius].
     */
    fun arcDistance(nx: Double, ny: Double, nz: Double, a: DoubleArray, b: DoubleArray, radius: Double): Double {
        // The arc's pole.
        var px = a[1] * b[2] - a[2] * b[1]; var py = a[2] * b[0] - a[0] * b[2]; var pz = a[0] * b[1] - a[1] * b[0]
        val pl = sqrt(px * px + py * py + pz * pz)
        if (pl < 1e-12) return distance(nx, ny, nz, a, radius)
        px /= pl; py /= pl; pz /= pl
        val off = nx * px + ny * py + nz * pz
        // The nearest point of the whole circle.
        var qx = nx - px * off; var qy = ny - py * off; var qz = nz - pz * off
        val ql = sqrt(qx * qx + qy * qy + qz * qz)
        if (ql < 1e-12) return Math.PI / 2 * radius
        qx /= ql; qy /= ql; qz /= ql
        // Within the arc when a to q and q to b both turn the arc's way.
        val s1 = (a[1] * qz - a[2] * qy) * px + (a[2] * qx - a[0] * qz) * py + (a[0] * qy - a[1] * qx) * pz
        val s2 = (qy * b[2] - qz * b[1]) * px + (qz * b[0] - qx * b[2]) * py + (qx * b[1] - qy * b[0]) * pz
        if (s1 >= 0.0 && s2 >= 0.0) return abs(asin(off.coerceIn(-1.0, 1.0))) * radius
        return minOf(distance(nx, ny, nz, a, radius), distance(nx, ny, nz, b, radius))
    }

    /** How far unit [n] is from a path through [points], in metres. */
    fun pathDistance(nx: Double, ny: Double, nz: Double, points: List<DoubleArray>, radius: Double): Double {
        var best = Double.MAX_VALUE
        for (k in 0 until points.size - 1) best = minOf(best, arcDistance(nx, ny, nz, points[k], points[k + 1], radius))
        return best
    }

    /**
     * A canyon's floor [d] metres off its line: [depth] down and [width] across, with steep walls
     * and a flat bottom.
     */
    fun canyon(d: Double, width: Double, depth: Double): Double {
        val x = d / (width / 2)
        if (x >= 1.4) return 0.0
        return -depth * (1.0 - smooth((x - 0.55) / 0.85))
    }

    /** A scarp, a long cliff [height] tall, stepping up on one side of a line [d] metres off it (signed). */
    fun scarp(signed: Double, width: Double, height: Double): Double =
        height * smooth(0.5 + signed / width)

    /** Signed metres from the great circle with pole [p], positive on the pole's side. */
    fun signedFromCircle(nx: Double, ny: Double, nz: Double, p: DoubleArray, radius: Double): Double =
        asin((nx * p[0] + ny * p[1] + nz * p[2]).coerceIn(-1.0, 1.0)) * radius

    /**
     * Dunes: long crests across the wind, [wavelength] apart and [height] tall, sharp on their
     * sheltered side, and broken and bent by noise so they look like sand instead of corrugated
     * iron.
     */
    fun dunes(seed: Int, x: Double, y: Double, z: Double, wx: Double, wy: Double, wz: Double, wavelength: Double, height: Double): Double {
        val bend = fbm(seed, x, y, z, 1.0 / (wavelength * 8), 2) * wavelength * 1.5
        val along = (x * wx + y * wy + z * wz + bend) / wavelength
        val phase = along - floor(along)
        // A gentle windward slope and a steep slip face.
        val profile = if (phase < 0.8) phase / 0.8 else (1.0 - phase) / 0.2
        val patch = 0.5 + 0.5 * fbm(seed + 7, x, y, z, 1.0 / (wavelength * 20), 2)
        return height * profile * profile * patch
    }

    /**
     * Double ridges along great circles, like the cracks on an icy moon: [count] circles from
     * [seed], each a pair of low ridges [width] across with a trough between. It also says how
     * close the nearest crack is, 0..1, for staining the ice there.
     */
    fun lineae(seed: Int, nx: Double, ny: Double, nz: Double, count: Int, radius: Double, width: Double, height: Double, near: DoubleArray): Double {
        var h = 0.0
        var closest = Double.MAX_VALUE
        for (k in 0 until count) {
            val p = pole(seed, k)
            // Each one only goes part of the way round, so they're arcs, not whole circles.
            val reach = 0.4 + 0.6 * Noise.hash(seed + 11, k, 0, 0)
            val phase = Noise.hash(seed + 12, k, 0, 0) * 2 * Math.PI
            val along = kotlin.math.atan2(ny * p[0] - nx * p[1], nz) + phase
            if (sin(along) < 1.0 - 2.0 * reach) continue
            val d = abs(signedFromCircle(nx, ny, nz, p, radius))
            closest = minOf(closest, d)
            if (d > width * 2) continue
            val x = d / width
            h += height * (exp(-((x - 0.5) / 0.25) * ((x - 0.5) / 0.25)) - 0.4 * exp(-(x / 0.2) * (x / 0.2)))
        }
        near[0] = 1.0 - smooth(closest / (width * 1.5))
        return h
    }

    /** A pole from [seed] and an index: somewhere on the sphere, unit length. */
    fun pole(seed: Int, k: Int): DoubleArray {
        val u = Noise.hash(seed, k, 1, 0) * 2 - 1
        val t = Noise.hash(seed, k, 2, 0) * 2 * Math.PI
        val s = sqrt(1 - u * u)
        return doubleArrayOf(s * cos(t), u, s * sin(t))
    }

    /** Grooved ground: parallel troughs [wavelength] apart and [depth] deep, running across [axis]. */
    fun grooves(x: Double, y: Double, z: Double, axis: DoubleArray, wavelength: Double, depth: Double): Double {
        val along = (x * axis[0] + y * axis[1] + z * axis[2]) / wavelength
        return -depth * (0.5 + 0.5 * cos(along * 2 * Math.PI))
    }

    /**
     * Broken ground: blocks [cell] across standing at heights up to [height], like a crust that
     * shattered and refroze.
     */
    fun chaos(seed: Int, x: Double, y: Double, z: Double, cell: Double, height: Double): Double {
        val bx = floor(x / cell).toInt(); val by = floor(y / cell).toInt(); val bz = floor(z / cell).toInt()
        val lift = Noise.hash(seed, bx, by, bz)
        // Blocks with sloped edges, not sheer ones.
        val fx = x / cell - bx; val fy = y / cell - by; val fz = z / cell - bz
        val edge = minOf(minOf(fx, 1 - fx), minOf(minOf(fy, 1 - fy), minOf(fz, 1 - fz)))
        return height * lift * smooth(edge / 0.15)
    }

    /**
     * One of a scatter of round landforms (pancake domes, mesas, pits) on a lattice of cells [cell]
     * metres across, at most one per cell with chance [chance]. It calls [action] with (metres from
     * its middle, its size 0..1, which one) for each one whose reach of [reach] cells covers the
     * point.
     */
    inline fun scattered(
        seed: Int, px: Double, py: Double, pz: Double, radius: Double, cell: Double, chance: Double,
        action: (Double, Double, Int) -> Unit,
    ) {
        val cx = floor(px / cell).toInt(); val cy = floor(py / cell).toInt(); val cz = floor(pz / cell).toInt()
        for (i in -1..1) for (j in -1..1) for (k in -1..1) {
            val x = cx + i; val y = cy + j; val z = cz + k
            if (Noise.hash(seed, x, y, z) > chance) continue
            val vx = (x + Noise.hash(seed + 1, x, y, z)) * cell
            val vy = (y + Noise.hash(seed + 2, x, y, z)) * cell
            val vz = (z + Noise.hash(seed + 3, x, y, z)) * cell
            val vl = sqrt(vx * vx + vy * vy + vz * vz)
            if (abs(vl - radius) > cell * 0.4) continue
            val sx = vx / vl * radius; val sy = vy / vl * radius; val sz = vz / vl * radius
            val dx = px - sx; val dy = py - sy; val dz = pz - sz
            action(sqrt(dx * dx + dy * dy + dz * dz), Noise.hash(seed + 4, x, y, z), Noise.hashInt(seed + 5, x, y, z))
        }
    }

    /**
     * A pancake dome [d] metres from its middle: flat-topped, [radius] across and [height] tall,
     * with steep sides.
     */
    fun pancake(d: Double, radius: Double, height: Double): Double = height * (1.0 - smooth((d / radius - 0.8) / 0.25))

    /**
     * A mesa [d] metres from its middle: [radius] across its top and [height] tall, with cliffs
     * [skirt] wide falling away from it.
     */
    fun mesa(d: Double, radius: Double, height: Double, skirt: Double): Double = height * (1.0 - smooth((d - radius) / skirt))

    /**
     * River channels: winding troughs where noise crosses zero, [depth] deep, with [width] as a
     * share of the pattern.
     */
    fun rivers(seed: Int, x: Double, y: Double, z: Double, scale: Double, width: Double, depth: Double): Double {
        val w = simplex(seed + 1, x / (scale / 5), y / (scale / 5), z / (scale / 5)) * scale * 0.1
        val c = abs(simplex(seed, (x + w) / scale, (y - w) / scale, (z + w) / scale))
        return -depth * (1.0 - smooth(c / width))
    }

    /**
     * Cellular ground: shallow pits packed edge to edge, like a melon's skin or a nitrogen
     * glacier's churning cells. It returns 0 at a cell's middle, rising to 1 on the ridges between
     * them.
     */
    fun cells(seed: Int, x: Double, y: Double, z: Double, cell: Double): Double {
        val cx = floor(x / cell).toInt(); val cy = floor(y / cell).toInt(); val cz = floor(z / cell).toInt()
        var d1 = Double.MAX_VALUE; var d2 = Double.MAX_VALUE
        for (i in -1..1) for (j in -1..1) for (k in -1..1) {
            val gx = cx + i; val gy = cy + j; val gz = cz + k
            val fx = (gx + Noise.hash(seed, gx, gy, gz)) * cell - x
            val fy = (gy + Noise.hash(seed + 1, gx, gy, gz)) * cell - y
            val fz = (gz + Noise.hash(seed + 2, gx, gy, gz)) * cell - z
            val d = fx * fx + fy * fy + fz * fz
            if (d < d1) { d2 = d1; d1 = d } else if (d < d2) d2 = d
        }
        // Near the boundary the two nearest are almost equally close.
        return 1.0 - smooth((sqrt(d2) - sqrt(d1)) / (cell * 0.35))
    }
}

/**
 * Craters in size classes on a lattice, done Luna's way, for any world. There's one or none per
 * cell of a 3D lattice, from cells near the surface only. See [LunaLand] for why.
 */
internal class Craters(private val seed: Int, private val radius: Double, private val classes: List<CraterClass>) {

    class CraterClass(val cell: Double, val chance: Double, val minRadius: Double, val maxRadius: Double)

    /**
     * Every crater near the point added up, keeping [keep] of the lattice's craters, so younger
     * ground has fewer.
     */
    fun height(px: Double, py: Double, pz: Double, keep: Double): Double {
        var total = 0.0
        for (c in classes.indices) forEach(px, py, pz, c, keep) { x01, r, age -> total += profile(x01, r) * age }
        return total
    }

    /** Whether the point is on a fresh crater's rim or apron (0.85..1.6 radii) of class [upTo] or bigger. */
    fun onRim(px: Double, py: Double, pz: Double, keep: Double, upTo: Int = 2): Boolean {
        for (c in 0..minOf(upTo, classes.size - 1)) {
            var rubble = false
            forEach(px, py, pz, c, keep) { x01, _, age -> if (age > 0.88 && x01 > 0.85 && x01 < 1.6) rubble = true }
            if (rubble) return true
        }
        return false
    }

    /** Whether the point is on a crater's floor (under [floor] radii) of class [upTo] or bigger. */
    fun onFloor(px: Double, py: Double, pz: Double, keep: Double, floor: Double = 0.6, upTo: Int = 2): Boolean {
        for (c in 0..minOf(upTo, classes.size - 1)) {
            var inside = false
            forEach(px, py, pz, c, keep) { x01, _, _ -> if (x01 < floor) inside = true }
            if (inside) return true
        }
        return false
    }

    private inline fun forEach(px: Double, py: Double, pz: Double, c: Int, keep: Double, action: (Double, Double, Double) -> Unit) {
        val cls = classes[c]
        val cell = cls.cell
        val cx = floor(px / cell).toInt(); val cy = floor(py / cell).toInt(); val cz = floor(pz / cell).toInt()
        val s = seed + c * 977
        for (i in -1..1) for (j in -1..1) for (k in -1..1) {
            val x = cx + i; val y = cy + j; val z = cz + k
            if (Noise.hash(s, x, y, z) > cls.chance * keep) continue
            val vx = (x + Noise.hash(s + 1, x, y, z)) * cell
            val vy = (y + Noise.hash(s + 2, x, y, z)) * cell
            val vz = (z + Noise.hash(s + 3, x, y, z)) * cell
            val vl = sqrt(vx * vx + vy * vy + vz * vz)
            if (abs(vl - radius) > cell * 0.4) continue
            val sx = vx / vl * radius; val sy = vy / vl * radius; val sz = vz / vl * radius
            val dx = px - sx; val dy = py - sy; val dz = pz - sz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val r = cls.minRadius + (cls.maxRadius - cls.minRadius) * Noise.hash(s + 4, x, y, z)
            val x01 = d / r
            if (x01 >= EJECTA_REACH) continue
            action(x01, r, 0.35 + 0.65 * Noise.hash(s + 5, x, y, z))
        }
    }

    /** A crater's height at [x] radii from its middle. See [LunaLand]'s. */
    private fun profile(x: Double, r: Double): Double {
        val complex = r > complexRadius
        val depth = r * (if (complex) 0.1 else 0.2)
        val rim = depth * 0.25
        if (x < 1.0) {
            val s = if (complex) Noise.smoothstep(((x - 0.7) / 0.3).coerceIn(0.0, 1.0)) else { val q = 1.0 - x * x; 1.0 - q * q }
            var h = -depth + (depth + rim) * s
            if (complex) h += depth * 0.55 * exp(-(x / 0.13) * (x / 0.13))
            return h
        }
        val e = (x - 1.0) / 0.3
        val h = rim * (0.6 * exp(-e * e) + 0.4 / (1.0 + 4.0 * (x - 1.0) * (x - 1.0)))
        if (x < 1.4) return h
        return h * Noise.smoothstep(((EJECTA_REACH - x) / (EJECTA_REACH - 1.4)).coerceIn(0.0, 1.0))
    }

    /**
     * Past this radius a crater has a flat floor and a central peak. It's a fiftieth of the world's
     * radius.
     */
    private val complexRadius = radius / 50.0

    companion object {
        const val EJECTA_REACH = 2.6

        /** Luna's crater classes, scaled to a world of [radius]: the same battering, sized to fit. */
        fun scaledFrom(radius: Double, density: Double = 1.0): List<CraterClass> {
            val k = radius / 200_000.0
            return listOf(
                CraterClass(90_000.0 * k, 0.30 * density, 8_000.0 * k, 25_000.0 * k),
                CraterClass(22_000.0 * k, 0.40 * density, 1_500.0 * k, 6_000.0 * k),
                CraterClass(4_000.0 * k, 0.45 * density, 200.0 * k, 1_000.0 * k),
                // The smallest stay the size they are, because a pit is a pit on any world.
                CraterClass(700.0, 0.45 * density, 30.0, 160.0),
                CraterClass(120.0, 0.30 * density, 5.0, 22.0),
            )
        }
    }
}

/** A world's own land: its height and its ground at each place. */
internal interface WorldLand {
    /** Height above datum at unit direction [nx],[ny],[nz], in metres. */
    fun height(nx: Double, ny: Double, nz: Double): Double
    /** What the ground is at that place, [elevation] up and [slope] steep (0 flat, 1 a wall). */
    fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial
}

/**
 * The [Terrain] of any world except Terra and Luna: its [land]'s height and ground, sampled into
 * tiles the same way theirs are. Lifeless, and dry unless it has seas ([hasSea], like Aurantia's
 * methane).
 */
class WorldField internal constructor(
    override val bodyRadius: Double,
    override val maxElevation: Double,
    private val land: WorldLand,
    private val hasSea: Boolean = false,
    override val world: String = "",
) : Terrain {
    override val generation: Int get() = TerrainField.GENERATION
    override val hasOcean: Boolean get() = hasSea
    override val barren: Boolean get() = true
    override val tiles: TerrainTileCache by lazy { TerrainTileCache(this) }
    private val scatterField: ScatterField by lazy { ScatterField(this) }
    override val scatter: ScatterField? get() = scatterField

    override fun ventField(direction: Vec3): Double {
        val s = seabed ?: return 0.0
        val l = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        return s.ventField(direction.x / l, direction.y / l, direction.z / l)
    }

    /** Under its sea is the open ocean's floor, the same as Terra's. See [Seabed]. */
    private val seabed: Seabed? = if (hasSea) Seabed(world.hashCode(), bodyRadius) else null

    override fun elevation(direction: Vec3): Double {
        val l = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        val nx = direction.x / l; val ny = direction.y / l; val nz = direction.z / l
        val h = land.height(nx, ny, nz)
        return if (seabed != null && h < 0.0) seabed.global(nx, ny, nz, h) else h
    }

    override fun material(direction: Vec3, elevation: Double, slope: Double): SurfaceMaterial {
        val l = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        val nx = direction.x / l; val ny = direction.y / l; val nz = direction.z / l
        if (seabed != null && elevation < -Seabed.SEA_EDGE) return seabed.material(nx, ny, nz, elevation, slope)
        return land.material(nx, ny, nz, elevation, slope)
    }
}
