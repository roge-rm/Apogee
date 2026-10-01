package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.Terrain
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The whole planet at orbital resolution, sampled from its [Terrain] so the drawn ground and the
 * physics ground are the same. This is the far view; near the ground it's [TerrainChunk]s.
 * Vertices are in body radii, so the scaled model matrix applies.
 */
object PlanetMesh {

    /** Indices are 32-bit: at 256 rings there are more vertices than a short can count. */
    class Data(val vertices: FloatArray, val indices: IntArray)

    /** @param rings latitude divisions. Longitude gets twice as many. */
    fun buildGlobe(field: Terrain?, bodyRadius: Double, rings: Int, world: String = "terra"): Data {
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

                // The seabed at its depth (no deeper than the shader can lift); the terrain shader
                // raises it to the water and colours it as sea, so there's no separate sea sphere.
                val elevation = field?.elevation(direction) ?: 0.0
                val sea = field?.hasOcean ?: false
                val drawn = if (sea) max(elevation, -TerrainChunk.MAX_DEPTH_CODE) else elevation
                val displaced = 1.0 + drawn / bodyRadius

                vertices[v] = (direction.x * displaced).toFloat()
                vertices[v + 1] = (direction.y * displaced).toFloat()
                vertices[v + 2] = (direction.z * displaced).toFloat()
                // Radial normals. At 14 km between vertices the relief hardly shows.
                vertices[v + 3] = direction.x.toFloat()
                vertices[v + 4] = direction.y.toFloat()
                vertices[v + 5] = direction.z.toFloat()
                if (field == null && GiantLook.isGiant(world)) {
                    // A giant has no ground, just its bands of cloud.
                    GiantLook.colour(world, direction, vertices, v + 6)
                    vertices[v + 9] = 0f
                } else if ((sea && elevation < 0.0) || field == null) {
                    TerrainPalette.water(-elevation, vertices, v + 6, field?.world ?: "terra")
                    vertices[v + 9] = if (field == null) 0f else (1.0 - drawn / 1_000.0).toFloat()
                } else {
                    val material = field.groundMaterial(direction, elevation, 0.0)
                    TerrainPalette.colour(material, elevation, ring * 7919 + segment, vertices, v + 6, field.world)
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
                // Anticlockwise seen from outside, or culling shows the inside of the far half.
                indices[i++] = a; indices[i++] = a + 1; indices[i++] = b
                indices[i++] = a + 1; indices[i++] = b + 1; indices[i++] = b
            }
        }
        return Data(vertices, indices)
    }
}
