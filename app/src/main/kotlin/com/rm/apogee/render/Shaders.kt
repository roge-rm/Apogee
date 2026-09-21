package com.rm.apogee.render

/**
 * GLSL sources, kept as string constants rather than assets so a shader compile
 * error is a build-adjacent problem rather than a runtime surprise on one
 * device.
 *
 * `#version 300 es` throughout - GLES 3.0 is the floor (see QualityTier for why
 * we do not assume 3.1+).
 */
object Shaders {

    val VESSEL_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec3 aNormal;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;

        out vec3 vNormal;

        void main() {
            // The model matrix is already camera-relative (floating origin), so
            // there is no separate world-space stage here.
            vec4 worldPos = uModel * vec4(aPosition, 1.0);
            // Uniform scale only for now, so the normal matrix is just the
            // rotation part of the model matrix.
            vNormal = mat3(uModel) * aNormal;
            gl_Position = uViewProjection * worldPos;
        }
    """.trimIndent()

    val VESSEL_FRAGMENT = """
        #version 300 es
        precision mediump float;

        in vec3 vNormal;

        uniform vec4 uColor;
        uniform vec3 uLightDirection;

        out vec4 fragColor;

        void main() {
            vec3 n = normalize(vNormal);
            float lambert = max(dot(n, -uLightDirection), 0.0);
            // A flat ambient floor stands in for bounce light until there is a
            // real lighting model; without it the unlit side reads as a hole.
            float ambient = 0.25;
            vec3 lit = uColor.rgb * (ambient + lambert * 0.75);
            fragColor = vec4(lit, uColor.a);
        }
    """.trimIndent()
}
