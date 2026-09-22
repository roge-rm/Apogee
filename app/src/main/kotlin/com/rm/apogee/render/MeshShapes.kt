package com.rm.apogee.render

/**
 * Vertex and index data for one primitive, before it reaches the GPU.
 *
 * Separate from [Mesh] so the geometry can be generated and checked without a
 * GL context. Every shape here is built by hand, and a triangle wound the
 * wrong way is invisible from outside rather than obviously broken - see
 * [MeshShapesTest], which is what that separation is for.
 */
class MeshData(val vertices: FloatArray, val indices: IntArray)

/**
 * Builds the procedural primitives part definitions refer to.
 *
 * Units are metres, and shapes are centred on the origin with +Y as the axis
 * of revolution - a rocket's "up". That convention is what lets a stack of
 * parts be positioned purely by their attachment nodes.
 *
 * Triangles are wound **counter-clockwise seen from outside**, which is what
 * `glFrontFace(GL_CCW)` and `glCullFace(GL_BACK)` expect.
 */
object MeshShapes {

    fun box(halfExtentX: Float, halfExtentY: Float, halfExtentZ: Float): MeshData {
        val vertices = ArrayList<Float>(6 * 4 * Mesh.STRIDE_FLOATS)
        val indices = ArrayList<Int>(36)

        // Each face gets its own four vertices so the normals stay flat rather
        // than being averaged across the edges into a rounded-looking cube.
        val faces = arrayOf(
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
        )

        for (normal in faces) {
            val base = vertices.size / Mesh.STRIDE_FLOATS
            // Build an orthonormal basis for the face from its normal.
            val up = if (kotlin.math.abs(normal[1]) > 0.9f) {
                floatArrayOf(0f, 0f, 1f)
            } else {
                floatArrayOf(0f, 1f, 0f)
            }
            val right = cross(up, normal)
            val realUp = cross(normal, right)

            for ((su, sv) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val px = (normal[0] + right[0] * su + realUp[0] * sv) * halfExtentX
                val py = (normal[1] + right[1] * su + realUp[1] * sv) * halfExtentY
                val pz = (normal[2] + right[2] * su + realUp[2] * sv) * halfExtentZ
                vertices.add(px); vertices.add(py); vertices.add(pz)
                vertices.add(normal[0]); vertices.add(normal[1]); vertices.add(normal[2])
            }
            indices.add(base); indices.add(base + 1); indices.add(base + 2)
            indices.add(base); indices.add(base + 2); indices.add(base + 3)
        }

        return MeshData(vertices.toFloatArray(), indices.toIntArray())
    }

    /**
     * A cone frustum about the +Y axis. Covers cylinders too - a cylinder is
     * just a frustum whose radii match - so tanks, engine bells, nose cones and
     * pods are all one code path.
     */
    fun frustum(
        bottomRadius: Float,
        topRadius: Float,
        height: Float,
        segments: Int = 20,
        caps: Int = StackCaps.BOTH,
    ): MeshData {
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Int>()

        val halfHeight = height * 0.5f

        // Caps sit flush with the walls. Recessing them was tried and is
        // worse: it opens a well that a steep viewing angle can see into.
        // Which caps are drawn at all is [StackCaps]' decision.
        val capY = halfHeight

        // Side normals tilt with the slope, so a cone shades like a cone rather
        // than like a cylinder someone squashed.
        val slope = (bottomRadius - topRadius) / height
        val normalScale = 1f / kotlin.math.sqrt(1f + slope * slope)

        // Each segment gets its own four vertices rather than sharing a seam,
        // so the side normals stay per-segment and the silhouette reads cleanly.
        for (i in 0 until segments) {
            val quad = vertices.size / Mesh.STRIDE_FLOATS

            for (step in 0..1) {
                val angle = (2.0 * Math.PI * (i + step) / segments).toFloat()
                val cos = kotlin.math.cos(angle)
                val sin = kotlin.math.sin(angle)
                val nx = cos * normalScale
                val nz = sin * normalScale
                val ny = slope * normalScale

                // Bottom rim, then top rim, at this angle.
                vertices.add(cos * bottomRadius)
                vertices.add(-halfHeight)
                vertices.add(sin * bottomRadius)
                vertices.add(nx); vertices.add(ny); vertices.add(nz)

                vertices.add(cos * topRadius)
                vertices.add(halfHeight)
                vertices.add(sin * topRadius)
                vertices.add(nx); vertices.add(ny); vertices.add(nz)
            }

            // quad+0 bottom at this angle, +1 top at this angle, +2 bottom at
            // the next, +3 top at the next. Angle increases anti-clockwise
            // looking down -Y, so from outside the next segment is to the
            // *left*: winding up-and-left is what faces the triangle outward.
            indices.add(quad); indices.add(quad + 3); indices.add(quad + 2)
            indices.add(quad); indices.add(quad + 1); indices.add(quad + 3)
        }

        if (caps and StackCaps.TOP != 0) {
            addCap(vertices, indices, topRadius, capY, 1f, segments)
        }
        if (caps and StackCaps.BOTTOM != 0) {
            addCap(vertices, indices, bottomRadius, -capY, -1f, segments)
        }

        return MeshData(vertices.toFloatArray(), indices.toIntArray())
    }

    fun cylinder(
        radius: Float,
        height: Float,
        segments: Int = 20,
        caps: Int = StackCaps.BOTH,
    ): MeshData = frustum(radius, radius, height, segments, caps)

    /** A UV sphere. Used for spherical tanks and, later, celestial bodies. */
    fun sphere(radius: Float, rings: Int = 12, segments: Int = 20): MeshData {
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Int>()

        for (ring in 0..rings) {
            val phi = Math.PI * ring / rings
            val y = kotlin.math.cos(phi).toFloat()
            val ringRadius = kotlin.math.sin(phi).toFloat()
            for (segment in 0..segments) {
                val theta = 2.0 * Math.PI * segment / segments
                val x = (ringRadius * kotlin.math.cos(theta)).toFloat()
                val z = (ringRadius * kotlin.math.sin(theta)).toFloat()
                vertices.add(x * radius); vertices.add(y * radius); vertices.add(z * radius)
                vertices.add(x); vertices.add(y); vertices.add(z)
            }
        }

        val stride = segments + 1
        for (ring in 0 until rings) {
            for (segment in 0 until segments) {
                val a = ring * stride + segment
                val b = a + stride
                indices.add(a); indices.add(a + 1); indices.add(b)
                indices.add(a + 1); indices.add(b + 1); indices.add(b)
            }
        }
        return MeshData(vertices.toFloatArray(), indices.toIntArray())
    }

    /** A flat disc closing one end of a frustum. */
    private fun addCap(
        vertices: ArrayList<Float>,
        indices: ArrayList<Int>,
        radius: Float,
        y: Float,
        normalY: Float,
        segments: Int,
    ) {
        if (radius <= 0f) return
        val centre = vertices.size / Mesh.STRIDE_FLOATS
        vertices.add(0f); vertices.add(y); vertices.add(0f)
        vertices.add(0f); vertices.add(normalY); vertices.add(0f)

        for (i in 0..segments) {
            val angle = (2.0 * Math.PI * i / segments).toFloat()
            vertices.add(kotlin.math.cos(angle) * radius)
            vertices.add(y)
            vertices.add(kotlin.math.sin(angle) * radius)
            vertices.add(0f); vertices.add(normalY); vertices.add(0f)
        }
        for (i in 0 until segments) {
            val a = centre + 1 + i
            val b = centre + 2 + i
            // Wind the two caps oppositely so each faces away from the part's
            // interior and back-face culling keeps both.
            if (normalY > 0f) {
                indices.add(centre); indices.add(b); indices.add(a)
            } else {
                indices.add(centre); indices.add(a); indices.add(b)
            }
        }
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
}
