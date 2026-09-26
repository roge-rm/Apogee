package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Shape
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.TerrainField

/**
 * One paved work's surface as a mesh: see [Paving]. Compared by identity -
 * each is built once and kept.
 */
class PavingShape(val order: Int, val vertices: FloatArray, val indices: IntArray) : Shape

/**
 * The Cape's paving - pads, runway, apron, taxiways, roads, quay - drawn as
 * meshes of their own laid a few centimetres over the levelled ground,
 * rather than as the colour of the terrain's triangles. The terrain's
 * triangles run on the planet's grid, not along the runway, so paving
 * painted onto them had edges like a saw; these have the works' own
 * straight edges and round ends. The ground under them is drawn as the land
 * round it (`Terrain.groundMaterial`), so where a triangle reaches past the
 * paving's edge it is grass, not a tooth of asphalt. Still flat triangles,
 * flat-shaded; only their edges are the works' now.
 */
object Paving {

    /** One work's mesh, where it is, and what colour. */
    class Piece(val shape: PavingShape, val colour: FloatArray)

    /** Every piece, their vertices relative to [origin] - body-fixed, on the pad. */
    class Built(val origin: Vec3, val pieces: List<Piece>)

    /** Laid this far over the ground, m: over it, and still under a tyre's eye. */
    private const val LIFT = 0.04

    /** Grid spacing the surface follows the ground by, m. */
    private const val STEP = 20.0

    /** Rounded ends and discs, in this many pieces a half turn. */
    private const val HALF_TURN = 24

    fun build(field: TerrainField): Built? {
        val works = field.pavedWorks
        if (works.isEmpty()) return null
        val radius = field.bodyRadius
        val originDirection = field.worksDirection(0.0, 0.0)
        val origin = Vec3().setTo(originDirection).mulInPlace(radius + field.elevation(originDirection))
        val pieces = works.mapIndexed { k, work ->
            val triangles = ArrayList<DoubleArray>()
            outline(work, triangles)
            val vertices = FloatArray(triangles.size * 3 * 6)
            val direction = Vec3()
            val corner = Array(3) { Vec3() }
            var v = 0
            for (triangle in triangles) {
                for (c in 0 until 3) {
                    field.worksDirection(triangle[2 * c], triangle[2 * c + 1], direction)
                    val height = field.elevation(direction) + LIFT
                    corner[c].setTo(direction).mulInPlace(radius + height).subInPlace(origin)
                }
                val normal = Vec3().setTo(corner[1]).subInPlace(corner[0])
                    .crossInPlace(Vec3().setTo(corner[2]).subInPlace(corner[0])).normalizeInPlace()
                // Facing up, off the ground.
                val flip = (normal dot originDirection) < 0.0
                if (flip) normal.negateInPlace()
                for (c in if (flip) intArrayOf(0, 2, 1) else intArrayOf(0, 1, 2)) {
                    vertices[v++] = corner[c].x.toFloat(); vertices[v++] = corner[c].y.toFloat(); vertices[v++] = corner[c].z.toFloat()
                    vertices[v++] = normal.x.toFloat(); vertices[v++] = normal.y.toFloat(); vertices[v++] = normal.z.toFloat()
                }
            }
            val indices = IntArray(triangles.size * 3) { it }
            Piece(PavingShape(k + 1, vertices, indices), colourOf(work.material))
        }
        return Built(origin, pieces)
    }

    /** [work]'s surface as triangles, each three (east, north) corners, into [out]. */
    private fun outline(work: TerrainField.PavedWork, out: MutableList<DoubleArray>) {
        val w = work.halfWidth
        val dx = work.toEast - work.fromEast
        val dy = work.toNorth - work.fromNorth
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length < 1e-6) {
            fan(work.fromEast, work.fromNorth, w, 0.0, 2.0 * Math.PI, out)
            return
        }
        val ux = dx / length; val uy = dy / length
        val vx = -uy; val vy = ux
        // The strip, on a grid fine enough to follow the ground.
        val along = kotlin.math.ceil(length / STEP).toInt().coerceAtLeast(1)
        val across = kotlin.math.ceil(2.0 * w / STEP).toInt().coerceAtLeast(2)
        fun point(i: Int, j: Int): DoubleArray {
            val s = length * i / along
            val t = -w + 2.0 * w * j / across
            return doubleArrayOf(work.fromEast + ux * s + vx * t, work.fromNorth + uy * s + vy * t)
        }
        for (i in 0 until along) for (j in 0 until across) {
            val a = point(i, j); val b = point(i + 1, j); val c = point(i + 1, j + 1); val d = point(i, j + 1)
            out.add(doubleArrayOf(a[0], a[1], b[0], b[1], c[0], c[1]))
            out.add(doubleArrayOf(a[0], a[1], c[0], c[1], d[0], d[1]))
        }
        if (work.squareEnds) return
        // Round ends: half a disc on each, facing out.
        val heading = kotlin.math.atan2(uy, ux)
        fan(work.fromEast, work.fromNorth, w, heading + Math.PI / 2, heading + 3 * Math.PI / 2, out)
        fan(work.toEast, work.toNorth, w, heading - Math.PI / 2, heading + Math.PI / 2, out)
    }

    /** A disc of [radius] about [east], [north] from angle [from] to [to], in rings, into [out]. */
    private fun fan(east: Double, north: Double, radius: Double, from: Double, to: Double, out: MutableList<DoubleArray>) {
        val span = to - from
        val segments = kotlin.math.max(4, kotlin.math.ceil(HALF_TURN * span / Math.PI).toInt())
        val rings = kotlin.math.ceil(radius / STEP).toInt().coerceAtLeast(1)
        fun point(ring: Int, segment: Int): DoubleArray {
            val r = radius * ring / rings
            val angle = from + span * segment / segments
            return doubleArrayOf(east + r * kotlin.math.cos(angle), north + r * kotlin.math.sin(angle))
        }
        for (s in 0 until segments) {
            val centre = point(0, 0)
            val a = point(1, s); val b = point(1, s + 1)
            out.add(doubleArrayOf(centre[0], centre[1], a[0], a[1], b[0], b[1]))
            for (ring in 1 until rings) {
                val p = point(ring, s); val q = point(ring, s + 1); val r = point(ring + 1, s + 1); val t = point(ring + 1, s)
                out.add(doubleArrayOf(p[0], p[1], t[0], t[1], r[0], r[1]))
                out.add(doubleArrayOf(p[0], p[1], r[0], r[1], q[0], q[1]))
            }
        }
    }

    /** A paved surface's colour: as the terrain drew it. */
    private fun colourOf(material: SurfaceMaterial): FloatArray = when (material) {
        SurfaceMaterial.CONCRETE -> floatArrayOf(0.66f, 0.65f, 0.62f, 1f)
        else -> floatArrayOf(0.17f, 0.17f, 0.18f, 1f)
    }
}
