package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.TerrainField
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry for a planet's surface, built by sampling its [TerrainField].
 *
 * The renderer does not generate terrain; it *asks* for it. The alternative -
 * reimplementing the same noise in GLSL - means two definitions of one
 * surface, and the first time either is touched a craft starts colliding with
 * ground that is no longer where it is drawn. Sampling the field costs a
 * mesh build; it buys the guarantee that what you see is what you land on.
 *
 * Two meshes, because one cannot serve both distances:
 *
 *  - [buildGlobe] covers the whole body at coarse resolution, for the view
 *    from orbit. Vertices are in units of body radii, so the existing scaled
 *    model matrix still applies.
 *  - [buildPatch] covers a few tens of kilometres under the craft at much
 *    finer resolution, with vertices **relative to the patch centre**. A
 *    surface vertex is 600,000 metres from the planet's centre, and float32
 *    quantises that to about six centimetres; subtracting the camera position
 *    in the shader at that magnitude is the precision disaster the whole
 *    floating-origin design exists to avoid. Making the patch local keeps its
 *    numbers small and the subtraction in double, where it belongs.
 */
object PlanetMesh {

    /**
     * Vertex layout: position(3), normal(3), elevation(1), slope(1).
     *
     * Slope is computed here rather than in the shader because this is where
     * both vectors are already to hand - the radial "up" and the real surface
     * normal. Deriving it in the fragment stage would mean shipping the
     * radial direction as well, which is three floats to avoid one.
     */
    const val STRIDE_FLOATS = 8

    class Data(val vertices: FloatArray, val indices: IntArray) {
        val vertexCount: Int get() = vertices.size / STRIDE_FLOATS
        val triangleCount: Int get() = indices.size / 3
    }

    /**
     * The whole body, as a displaced sphere in units of [field]-relative radii.
     *
     * @param rings latitude divisions. Longitude gets twice as many.
     */
    fun buildGlobe(field: TerrainField?, bodyRadius: Double, rings: Int): Data {
        val segments = rings * 2
        val vertices = FloatArray((rings + 1) * (segments + 1) * STRIDE_FLOATS)
        val indices = IntArray(rings * segments * 6)

        val direction = Vec3()
        var v = 0

        for (ring in 0..rings) {
            val phi = PI * ring / rings
            val y = cos(phi)
            val ringRadius = sin(phi)

            for (segment in 0..segments) {
                val theta = 2.0 * PI * segment / segments
                direction.setTo(ringRadius * cos(theta), y, ringRadius * sin(theta))

                // Ocean clamped to the datum, exactly as the patch does it,
                // so the globe carries its own water surface and there is one
                // mesh describing the planet rather than two. There used to be
                // a separate sea sphere over a sunken sea floor, and the two
                // intersected along every coastline - a triangle running from
                // a land vertex down to an ocean one crosses the sphere
                // partway, and the shoreline shattered into interleaved shards.
                val elevation = field?.elevation(direction) ?: 0.0
                val displaced = 1.0 + max(elevation, 0.0) / bodyRadius

                vertices[v++] = (direction.x * displaced).toFloat()
                vertices[v++] = (direction.y * displaced).toFloat()
                vertices[v++] = (direction.z * displaced).toFloat()
                // Radial normal is close enough at this scale; the relief is
                // three orders of magnitude smaller than the body.
                vertices[v++] = direction.x.toFloat()
                vertices[v++] = direction.y.toFloat()
                vertices[v++] = direction.z.toFloat()
                vertices[v++] = elevation.toFloat()
                // Radial normals carry no slope, so the globe reports none.
                // At fourteen kilometres between vertices there is no slope
                // left to report anyway.
                vertices[v++] = 0f
            }
        }

        var i = 0
        val stride = segments + 1
        for (ring in 0 until rings) {
            for (segment in 0 until segments) {
                val a = ring * stride + segment
                val b = a + stride
                indices[i++] = a; indices[i++] = b; indices[i++] = a + 1
                indices[i++] = a + 1; indices[i++] = b; indices[i++] = b + 1
            }
        }
        return Data(vertices, indices)
    }

    /**
     * A square of ground centred on [centreDirection], in metres relative to
     * the point on the surface directly below it.
     *
     * @param extentMetres how far the patch reaches from its centre.
     * @param resolution vertices along each edge.
     * @param outCentre receives the patch's own position, in the body-fixed
     *   frame, so the caller can place it.
     */
    fun buildPatch(
        field: TerrainField,
        bodyRadius: Double,
        centreDirection: Vec3,
        extentMetres: Double,
        resolution: Int,
        outCentre: Vec3,
    ): Data {
        val up = centreDirection.normalized()
        // A local frame on the surface to lay the grid out in.
        val east = (if (kotlin.math.abs(up.y) < 0.9) Vec3.unitY() else Vec3.unitX())
            .cross(up).normalizeInPlace()
        val north = up.cross(east).normalizeInPlace()

        val centreRadius = field.surfaceRadius(up)
        outCentre.setTo(up).mulInPlace(centreRadius)

        val vertices = FloatArray(resolution * resolution * STRIDE_FLOATS)
        val indices = IntArray((resolution - 1) * (resolution - 1) * 6)

        val direction = Vec3()
        val point = Vec3()
        var v = 0

        for (row in 0 until resolution) {
            val alongNorth = (row.toDouble() / (resolution - 1) - 0.5) * 2.0 * extentMetres
            for (column in 0 until resolution) {
                val alongEast = (column.toDouble() / (resolution - 1) - 0.5) * 2.0 * extentMetres

                // Offset on the tangent plane, then renormalised back onto the
                // sphere - so the patch curves with the body instead of being
                // a flat plate that sinks below the horizon at its edges.
                direction.setTo(
                    up.x * bodyRadius + east.x * alongEast + north.x * alongNorth,
                    up.y * bodyRadius + east.y * alongEast + north.y * alongNorth,
                    up.z * bodyRadius + east.z * alongEast + north.z * alongNorth,
                ).normalizeInPlace()

                // Water is part of the patch, clamped to the datum, rather
                // than seabed left for the sea sphere to cover. The sphere is
                // drawn in the far pass and the patch in the near one, with a
                // depth clear between them, so a sunken seabed here would
                // paint straight over the sea and every ocean would come out
                // as bare rock. Clamping also means the drawn water surface
                // is exactly TerrainField.surfaceRadius - the same surface
                // the simulation floats things on.
                val elevation = field.elevation(direction)
                val radius = bodyRadius + kotlin.math.max(elevation, 0.0)
                point.setTo(direction).mulInPlace(radius).subInPlace(outCentre)

                vertices[v++] = point.x.toFloat()
                vertices[v++] = point.y.toFloat()
                vertices[v++] = point.z.toFloat()
                vertices[v++] = direction.x.toFloat()
                vertices[v++] = direction.y.toFloat()
                vertices[v++] = direction.z.toFloat()
                vertices[v++] = elevation.toFloat()
                // Radial normals carry no slope, so the globe reports none.
                // At fourteen kilometres between vertices there is no slope
                // left to report anyway.
                vertices[v++] = 0f
            }
        }

        // Real normals from the finished heights. The radial approximation the
        // globe uses is fine at orbital distance and useless here, where the
        // shading *is* the terrain.
        computePatchNormals(vertices, resolution)

        // Wound east-then-north, which puts the face normal along local up.
        // The obvious ordering (origin, north, east) points it into the
        // planet, and the entire patch is then back-face culled - the ground
        // simply is not there, and what you see is the sea underneath it.
        var i = 0
        for (row in 0 until resolution - 1) {
            for (column in 0 until resolution - 1) {
                val a = row * resolution + column
                val b = a + resolution
                indices[i++] = a; indices[i++] = a + 1; indices[i++] = b
                indices[i++] = a + 1; indices[i++] = b + 1; indices[i++] = b
            }
        }
        return Data(vertices, indices)
    }

    /**
     * Replaces each vertex's radial direction with the real surface normal,
     * and records how far the two have diverged as the slope.
     */
    private fun computePatchNormals(vertices: FloatArray, resolution: Int) {
        fun position(index: Int, axis: Int) = vertices[index * STRIDE_FLOATS + axis]

        for (row in 0 until resolution) {
            for (column in 0 until resolution) {
                val here = row * resolution + column
                val left = if (column > 0) here - 1 else here
                val right = if (column < resolution - 1) here + 1 else here
                val down = if (row > 0) here - resolution else here
                val up = if (row < resolution - 1) here + resolution else here

                val dx0 = position(right, 0) - position(left, 0)
                val dy0 = position(right, 1) - position(left, 1)
                val dz0 = position(right, 2) - position(left, 2)
                val dx1 = position(up, 0) - position(down, 0)
                val dy1 = position(up, 1) - position(down, 1)
                val dz1 = position(up, 2) - position(down, 2)

                var nx = dy0 * dz1 - dz0 * dy1
                var ny = dz0 * dx1 - dx0 * dz1
                var nz = dx0 * dy1 - dy0 * dx1
                val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                if (length > 1e-9f) {
                    nx /= length; ny /= length; nz /= length
                }

                // east x north is local up, matching the triangle winding.
                val base = here * STRIDE_FLOATS
                // Against the radial direction still sitting in these slots:
                // 0 on the flat, 1 on a wall.
                val radial = vertices[base + 3] * nx +
                    vertices[base + 4] * ny +
                    vertices[base + 5] * nz
                vertices[base + 7] = (1f - radial).coerceIn(0f, 1f)
                vertices[base + 3] = nx
                vertices[base + 4] = ny
                vertices[base + 5] = nz
            }
        }
    }
}
