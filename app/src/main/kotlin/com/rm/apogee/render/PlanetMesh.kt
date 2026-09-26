package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.Terrain
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The whole planet at orbital resolution, built by sampling its [Terrain].
 *
 * The renderer does not generate terrain; it *asks* for it. Reimplementing the
 * noise in GLSL would mean two definitions of one surface, and the first time
 * either was touched a craft would start colliding with ground that was no
 * longer where it was drawn.
 *
 * Only the far view. Near the ground the surface is [TerrainChunk]s at
 * whatever level of detail each distance deserves; this is what is seen past
 * them and from orbit. Vertices are in units of body radii, so the scaled
 * model matrix applies.
 */
object PlanetMesh {

    /** Indices are 32-bit: at 256 rings the globe has more vertices than a short can number. */
    class Data(val vertices: FloatArray, val indices: IntArray)

    /** @param rings latitude divisions. Longitude gets twice as many. */
    fun buildGlobe(field: Terrain?, bodyRadius: Double, rings: Int): Data {
        val segments = rings * 2
        val stride = TerrainChunk.STRIDE_FLOATS
        val vertices = FloatArray((rings + 1) * (segments + 1) * stride)
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

                // The sea bed at its depth - no deeper than the shader can
                // lift - which the terrain shader raises to the water and
                // colours as sea: one mesh for the planet, not a separate sea
                // sphere that would intersect it along every coastline.
                val elevation = field?.elevation(direction) ?: 0.0
                val sea = field?.hasOcean ?: false
                val drawn = if (sea) max(elevation, -TerrainChunk.MAX_DEPTH_CODE) else elevation
                val displaced = 1.0 + drawn / bodyRadius

                vertices[v] = (direction.x * displaced).toFloat()
                vertices[v + 1] = (direction.y * displaced).toFloat()
                vertices[v + 2] = (direction.z * displaced).toFloat()
                // Radial normals: at fourteen kilometres between vertices the
                // relief is three orders of magnitude below the body.
                vertices[v + 3] = direction.x.toFloat()
                vertices[v + 4] = direction.y.toFloat()
                vertices[v + 5] = direction.z.toFloat()
                if ((sea && elevation < 0.0) || field == null) {
                    TerrainPalette.water(-elevation, vertices, v + 6)
                    vertices[v + 9] = if (field == null) 0f else (1.0 - drawn / 1_000.0).toFloat()
                } else {
                    val material = field.groundMaterial(direction, elevation, 0.0)
                    TerrainPalette.colour(material, elevation, ring * 7919 + segment, vertices, v + 6)
                    vertices[v + 9] = 0f
                }
                v += stride
            }
        }

        var i = 0
        val rowStride = segments + 1
        for (ring in 0 until rings) {
            for (segment in 0 until segments) {
                val a = ring * rowStride + segment
                val b = a + rowStride
                // Counter-clockwise seen from outside. Wound the other way, as
                // it was, every triangle faced into the planet: culling took
                // the near half away and left the inside of the far half on
                // show, dark in the middle where it faced away from the sun and
                // lit only round the edge - a bright ring round a black hole,
                // from anywhere above the height the chunks cover.
                indices[i++] = a; indices[i++] = a + 1; indices[i++] = b
                indices[i++] = a + 1; indices[i++] = b + 1; indices[i++] = b
            }
        }
        return Data(vertices, indices)
    }
}
