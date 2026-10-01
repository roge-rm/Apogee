package com.rm.apogee.render

import com.rm.apogee.core.part.ModelSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math

/**
 * Meshes for the leaves of a part's [ModelSpec]. Everything is flat-shaded to match the ground.
 * Each triangle is turned to face away from a point inside the solid, so the order its corners
 * are listed in doesn't matter.
 */
object ModelShapes {

    fun build(spec: ModelSpec, caps: Int = StackCaps.BOTH): MeshData = when (spec) {
        is ModelSpec.Lathe -> lathe(spec.profile.map { it[0] to it[1] }, spec.segments, caps, spec.sweep, spec.from)
        is ModelSpec.NoseCone -> noseCone(spec, caps)
        is ModelSpec.Tank -> tank(spec, caps)
        is ModelSpec.Fin -> fin(spec)
        is ModelSpec.Loft -> loft(spec)
        is ModelSpec.Tyre -> tyre(spec)
        is ModelSpec.Prop -> prop(spec)
        is ModelSpec.Primitive, is ModelSpec.Compound ->
            throw IllegalArgumentException("${spec::class.simpleName} is not a leaf shape")
    }

    // --- surfaces of revolution --------------------------------------------

    /**
     * [profile] is (radius, y), walked from the bottom with the solid on the left. Each face points
     * to the walker's right, so a profile can turn back on itself, like an engine bell. Ends off
     * the axis get capped where [caps] asks.
     */
    fun lathe(
        profile: List<Pair<Double, Double>>,
        segments: Int,
        caps: Int = StackCaps.BOTH,
        /** Degrees round, from [from]. See [ModelSpec.Lathe.sweep]. */
        sweep: Double = 360.0,
        from: Double = 0.0,
    ): MeshData {
        val start = Math.toRadians(from)
        val span = Math.toRadians(sweep.coerceIn(1.0, 360.0))
        val whole = sweep >= 360.0
        require(profile.size >= 2) { "a lathe needs two profile points" }
        val soup = Soup()
        for (k in 0 until profile.size - 1) {
            val (r0, y0) = profile[k]
            val (r1, y1) = profile[k + 1]
            if (r0 <= 0.0 && r1 <= 0.0) continue
            // To the right of the walk, in the (radius, y) plane.
            val nr = y1 - y0
            val ny = -(r1 - r0)
            for (i in 0 until segments) {
                val a0 = start + span * i / segments
                val a1 = start + span * (i + 1) / segments
                val am = (a0 + a1) * 0.5
                val outward = doubleArrayOf(nr * cos(am), ny, nr * sin(am))
                val p00 = ring(r0, y0, a0); val p01 = ring(r0, y0, a1)
                val p10 = ring(r1, y1, a0); val p11 = ring(r1, y1, a1)
                if (r0 > 0.0) soup.triFacing(p00, p01, p11, outward)
                if (r1 > 0.0) soup.triFacing(p00, p11, p10, outward)
            }
        }
        val (rb, yb) = profile.first()
        val (rt, yt) = profile.last()
        if (whole && rb > 0.0 && caps and StackCaps.BOTTOM != 0) disc(soup, rb, yb, segments, up = false)
        if (whole && rt > 0.0 && caps and StackCaps.TOP != 0) disc(soup, rt, yt, segments, up = true)
        return soup.data()
    }

    /** A tangent ogive to a blunted tip, centred on its middle like the cone it replaces. */
    fun noseCone(spec: ModelSpec.NoseCone, caps: Int = StackCaps.BOTH): MeshData {
        val radius = spec.radius
        val length = spec.length
        val rho = (radius * radius + length * length) / (2 * radius)
        val tip = radius * spec.bluntness
        val steps = 10
        val profile = ArrayList<Pair<Double, Double>>()
        for (s in 0..steps) {
            // Closer together towards the tip, where the curve turns fastest.
            val t = 1.0 - (1.0 - s.toDouble() / steps).let { it * it }
            val y = t * length
            // y from the base: the full radius there, and a point at the tip.
            val r = sqrt(max(0.0, rho * rho - y * y)) + radius - rho
            if (r <= tip) {
                profile.add(tip to y - length * 0.5)
                break
            }
            profile.add(r to y - length * 0.5)
        }
        // Close the blunt tip with a tiny flat.
        profile.add(0.0 to profile.last().second)
        return lathe(profile, spec.segments, caps and StackCaps.BOTTOM)
    }

    /** A cylinder with chamfered ends and raised bands. */
    fun tank(spec: ModelSpec.Tank, caps: Int = StackCaps.BOTH): MeshData {
        val r = spec.radius
        val h = spec.height * 0.5
        val c = spec.chamfer
        val profile = ArrayList<Pair<Double, Double>>()
        profile.add(r - c to -h)
        profile.add(r to -h + c)
        val band = 0.025
        val bandHalf = 0.04
        for (b in 1..spec.bands) {
            val y = -h + spec.height * b / (spec.bands + 1)
            profile.add(r to y - bandHalf)
            profile.add(r + band to y - bandHalf)
            profile.add(r + band to y + bandHalf)
            profile.add(r to y + bandHalf)
        }
        profile.add(r to h - c)
        profile.add(r - c to h)
        return lathe(profile, spec.segments, caps)
    }

    // --- flying surfaces ----------------------------------------------------

    /**
     * A trapezoid with a diamond section, rooted at -span/2 and reaching to +span/2 along X, with
     * the chord along Y and the leading edge at +Y.
     */
    fun fin(spec: ModelSpec.Fin): MeshData {
        val soup = Soup()
        val x0 = -spec.span * 0.5
        val x1 = spec.span * 0.5
        val rootLe = spec.rootChord * 0.5
        val rootTe = -spec.rootChord * 0.5
        val tipLe = rootLe - spec.sweep
        val tipTe = tipLe - spec.tipChord
        val t0 = spec.thickness * 0.5
        val t1 = t0 * (spec.tipChord / spec.rootChord).coerceIn(0.2, 1.0)
        // Thickest at forty percent of the chord back from the leading edge.
        fun section(x: Double, le: Double, te: Double, t: Double): Array<DoubleArray> {
            val mid = le + (te - le) * 0.4
            return arrayOf(
                doubleArrayOf(x, le, 0.0), doubleArrayOf(x, mid, t),
                doubleArrayOf(x, te, 0.0), doubleArrayOf(x, mid, -t),
            )
        }
        val root = section(x0, rootLe, rootTe, t0)
        val tip = section(x1, tipLe, tipTe, t1)
        val inside = doubleArrayOf(0.0, (rootLe + rootTe + tipLe + tipTe) * 0.25, 0.0)
        for (k in 0 until 4) {
            val n = (k + 1) % 4
            soup.quad(root[k], root[n], tip[n], tip[k], inside)
        }
        soup.quad(root[0], root[1], root[2], root[3], inside)
        soup.quad(tip[0], tip[1], tip[2], tip[3], inside)
        return soup.data()
    }

    // --- lofted bodies ------------------------------------------------------

    fun loft(spec: ModelSpec.Loft): MeshData {
        val soup = Soup()
        val rings = spec.sections.map { outline(it, spec.around) }
        for (k in 0 until rings.size - 1) {
            val a = rings[k]; val b = rings[k + 1]
            val s0 = spec.sections[k]; val s1 = spec.sections[k + 1]
            val inside = doubleArrayOf(
                0.0,
                (s0.y + s1.y) * 0.5,
                (s0.top + s0.bottom + s1.top + s1.bottom) * 0.25,
            )
            for (i in a.indices) {
                val j = (i + 1) % a.size
                soup.quad(a[i], a[j], b[j], b[i], inside)
            }
        }
        if (spec.capped) {
            for (end in listOf(0, rings.size - 1)) {
                val s = spec.sections[end]
                val centre = doubleArrayOf(0.0, s.y, (s.top + s.bottom) * 0.5)
                val next = spec.sections[if (end == 0) 1 else end - 1]
                val inside = doubleArrayOf(0.0, (s.y + next.y) * 0.5, centre[2])
                val ring = rings[end]
                for (i in ring.indices) soup.tri(centre, ring[i], ring[(i + 1) % ring.size], inside)
            }
        }
        return soup.data()
    }

    /** A rounded rectangle in X-Z, drawn in to a V at the bottom. */
    private fun outline(s: ModelSpec.Loft.Section, around: Int): List<DoubleArray> {
        val points = ArrayList<DoubleArray>(around)
        val cx = 0.0
        val cz = (s.top + s.bottom) * 0.5
        val hx = s.halfWidth
        val hz = (s.top - s.bottom) * 0.5
        for (i in 0 until around) {
            val a = 2 * PI * i / around
            val c = cos(a); val sn = sin(a)
            // Superellipse: round 1 gives an ellipse, and round 0 a rectangle.
            val e = 2.0 / (1.0 + (1.0 - s.round.coerceIn(0.0, 1.0)) * 6.0)
            var x = hx * Math.signum(c) * Math.pow(abs(c), e)
            var z = hz * Math.signum(sn) * Math.pow(abs(sn), e)
            if (z < 0) {
                // The V: the lower half narrows towards the keel.
                val depth = -z / hz
                x *= 1.0 - s.vee * depth
            }
            points.add(doubleArrayOf(cx + x, s.y, cz + z))
        }
        return points
    }

    // --- wheels and propellers ----------------------------------------------

    /**
     * A tire about the X axis: a rounded ring with tread blocks as separate solids on the crown, so
     * there are no cracks between steps.
     */
    fun tyre(spec: ModelSpec.Tyre): MeshData {
        val r = spec.radius
        val w = spec.width * 0.5
        val inner = r * 0.62
        val shoulder = r - spec.width * 0.18
        val segments = max(spec.lugs * 2, 16)
        val soup = Soup()
        // Built about Y, then turned so the axle runs along X.
        val profile = listOf(
            inner to -w * 0.8, shoulder to -w, r to -w * 0.6, r to w * 0.6, shoulder to w, inner to w * 0.8,
        )
        for (k in 0 until profile.size - 1) {
            val (r0, y0) = profile[k]
            val (r1, y1) = profile[k + 1]
            val inside = doubleArrayOf(0.0, (y0 + y1) * 0.5, 0.0)
            for (i in 0 until segments) {
                val a0 = 2 * PI * i / segments
                val a1 = 2 * PI * (i + 1) / segments
                soup.quad(ring(r0, y0, a0), ring(r0, y0, a1), ring(r1, y1, a1), ring(r1, y1, a0), inside)
            }
        }
        disc(soup, inner, -w * 0.8, segments, up = false)
        disc(soup, inner, w * 0.8, segments, up = true)
        // Tread blocks, alternating sides like a real pattern.
        if (spec.lugDepth > 0.0) {
            val arc = PI / spec.lugs * 0.55
            for (l in 0 until spec.lugs) {
                val centre = 2 * PI * l / spec.lugs
                val y0 = if (l % 2 == 0) -w * 0.6 else -w * 0.1
                val y1 = y0 + w * 0.7
                val corners = ArrayList<DoubleArray>(8)
                for (rr in listOf(r - 0.005, r + spec.lugDepth)) {
                    for (a in listOf(centre - arc, centre + arc)) for (y in listOf(y0, y1)) corners.add(ring(rr, y, a))
                }
                box(soup, corners, ring(r - spec.lugDepth, (y0 + y1) * 0.5, centre))
            }
        }
        return soup.data().turnedYToX()
    }

    /** Blades about +Y: thin slabs from the hub out, each one pitched. */
    fun prop(spec: ModelSpec.Prop): MeshData {
        val soup = Soup()
        val hub = spec.radius * 0.12
        val pitch = Math.toRadians(spec.pitchDegrees)
        val halfChord = spec.chord * 0.5
        val halfThick = spec.chord * 0.08
        for (b in 0 until spec.blades) {
            val a = 2 * PI * b / spec.blades
            val out = doubleArrayOf(cos(a), 0.0, sin(a))
            val along = doubleArrayOf(-sin(a), 0.0, cos(a))
            // Chord direction pitched out of the disc, and thickness across it.
            val chord = doubleArrayOf(along[0] * cos(pitch), sin(pitch), along[2] * cos(pitch))
            val thick = doubleArrayOf(-along[0] * sin(pitch), cos(pitch), -along[2] * sin(pitch))
            val corners = ArrayList<DoubleArray>(8)
            for (rr in listOf(hub, spec.radius)) {
                val taper = if (rr == hub) 1.0 else 0.6
                for (sc in listOf(-1.0, 1.0)) for (st in listOf(-1.0, 1.0)) {
                    corners.add(
                        DoubleArray(3) { i ->
                            out[i] * rr + chord[i] * sc * halfChord * taper + thick[i] * st * halfThick
                        }
                    )
                }
            }
            val inside = DoubleArray(3) { i -> out[i] * (hub + spec.radius) * 0.5 }
            box(soup, corners, inside)
        }
        return soup.data()
    }

    // --- helpers ------------------------------------------------------------

    private fun ring(r: Double, y: Double, angle: Double) = doubleArrayOf(r * cos(angle), y, r * sin(angle))

    private fun disc(soup: Soup, r: Double, y: Double, segments: Int, up: Boolean) {
        val centre = doubleArrayOf(0.0, y, 0.0)
        val inside = doubleArrayOf(0.0, y + if (up) -1.0 else 1.0, 0.0)
        for (i in 0 until segments) {
            soup.tri(centre, ring(r, y, 2 * PI * i / segments), ring(r, y, 2 * PI * (i + 1) / segments), inside)
        }
    }

    /**
     * A hexahedron from eight corners ordered (inner, outer) x (chord -, +) x (thickness -, +).
     */
    private fun box(soup: Soup, c: List<DoubleArray>, inside: DoubleArray) {
        fun q(a: Int, b: Int, d: Int, e: Int) = soup.quad(c[a], c[b], c[d], c[e], inside)
        q(0, 1, 3, 2) // inner end
        q(4, 5, 7, 6) // outer end
        q(0, 1, 5, 4) // chord - side
        q(2, 3, 7, 6) // chord + side
        q(0, 2, 6, 4) // thickness -
        q(1, 3, 7, 5) // thickness +
    }

    /** Swaps the axis of revolution from Y to X. A proper rotation, so faces keep their facing. */
    private fun MeshData.turnedYToX(): MeshData {
        val v = vertices.copyOf()
        var i = 0
        while (i < v.size) {
            val x = v[i]; val y = v[i + 1]
            v[i] = y; v[i + 1] = -x
            val nx = v[i + 3]; val ny = v[i + 4]
            v[i + 3] = ny; v[i + 4] = -nx
            i += Mesh.STRIDE_FLOATS
        }
        return MeshData(v, indices)
    }

    /** Flat-shaded triangles, each turned to face away from the given inside point. */
    class Soup {
        private val vertices = ArrayList<Float>()

        fun tri(a: DoubleArray, b: DoubleArray, c: DoubleArray, inside: DoubleArray) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length < 1e-12) return // degenerate, nothing to draw
            nx /= length; ny /= length; nz /= length
            val mx = (a[0] + b[0] + c[0]) / 3 - inside[0]
            val my = (a[1] + b[1] + c[1]) / 3 - inside[1]
            val mz = (a[2] + b[2] + c[2]) / 3 - inside[2]
            if (nx * mx + ny * my + nz * mz < 0) {
                add(a, -nx, -ny, -nz); add(c, -nx, -ny, -nz); add(b, -nx, -ny, -nz)
            } else {
                add(a, nx, ny, nz); add(b, nx, ny, nz); add(c, nx, ny, nz)
            }
        }

        /** Like [tri], but facing along [outward] instead of away from a point. */
        fun triFacing(a: DoubleArray, b: DoubleArray, c: DoubleArray, outward: DoubleArray) {
            val m = doubleArrayOf((a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3, (a[2] + b[2] + c[2]) / 3)
            tri(a, b, c, doubleArrayOf(m[0] - outward[0], m[1] - outward[1], m[2] - outward[2]))
        }

        fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray, inside: DoubleArray) {
            tri(a, b, c, inside)
            tri(a, c, d, inside)
        }

        private fun add(p: DoubleArray, nx: Double, ny: Double, nz: Double) {
            vertices.add(p[0].toFloat()); vertices.add(p[1].toFloat()); vertices.add(p[2].toFloat())
            vertices.add(nx.toFloat()); vertices.add(ny.toFloat()); vertices.add(nz.toFloat())
        }

        fun data(): MeshData {
            val v = vertices.toFloatArray()
            return MeshData(v, IntArray(v.size / Mesh.STRIDE_FLOATS) { it })
        }
    }
}
