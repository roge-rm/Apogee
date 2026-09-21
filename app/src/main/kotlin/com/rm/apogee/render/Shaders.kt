package com.rm.apogee.render

/**
 * GLSL sources, kept as string constants rather than assets so a shader compile
 * error is a build-adjacent problem rather than a runtime surprise on one
 * device.
 *
 * `#version 300 es` throughout, and deliberately conservative beyond that: no
 * compute, no storage buffers, no `gl_FragDepth`. minSdk 27 puts Adreno 5xx and
 * Mali-T8xx drivers in the test matrix, and those are exactly the drivers that
 * quietly mis-compile anything clever.
 */
object Shaders {

    // ---- vessels and parts -------------------------------------------------

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
            // Uniform scale only, so the normal matrix is just the rotation.
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
            // A flat ambient floor stands in for bounce light; without it the
            // unlit side of a craft reads as a hole cut in the sky.
            float ambient = 0.28;
            vec3 lit = uColor.rgb * (ambient + lambert * 0.8);
            fragColor = vec4(lit, uColor.a);
        }
    """.trimIndent()

    // ---- sky ---------------------------------------------------------------

    /**
     * A single oversized triangle covering the screen.
     *
     * Cheaper than a quad and avoids the diagonal seam where two triangles
     * meet, which shows up in gradients exactly like the ones a sky is made of.
     */
    val SKY_VERTEX = """
        #version 300 es
        out vec2 vNdc;

        void main() {
            // Three vertices spanning well past the viewport.
            vec2 positions[3] = vec2[3](
                vec2(-1.0, -1.0),
                vec2( 3.0, -1.0),
                vec2(-1.0,  3.0)
            );
            vNdc = positions[gl_VertexID];
            gl_Position = vec4(vNdc, 1.0, 1.0);
        }
    """.trimIndent()

    /**
     * Stars, and an atmosphere that thins with altitude.
     *
     * The view ray is rebuilt from the camera basis rather than by inverting a
     * matrix - the basis is already to hand, and a matrix inverse per frame in
     * a fragment shader is a needless cost.
     */
    val SKY_FRAGMENT = """
        #version 300 es
        precision highp float;

        in vec2 vNdc;

        uniform vec3 uCameraRight;
        uniform vec3 uCameraUp;
        uniform vec3 uCameraForward;
        uniform float uTanHalfFov;
        uniform float uAspect;

        uniform vec3 uUpDirection;      // away from the planet's centre
        uniform vec3 uSunDirection;
        uniform float uAtmosphereFactor; // 1 at sea level, 0 in vacuum

        out vec4 fragColor;

        // Cheap 3D hash. Good enough for stars, which only need to be
        // stationary and unevenly spaced.
        float hash13(vec3 p) {
            p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
            p *= 17.0;
            return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
        }

        vec3 starField(vec3 dir) {
            // Quantise the direction into cells and light a few of them.
            vec3 cell = floor(dir * 220.0);
            float h = hash13(cell);
            float star = smoothstep(0.9975, 1.0, h);
            // Vary brightness so the field does not look like a regular grid.
            float brightness = 0.35 + 0.65 * hash13(cell + 7.3);
            // A faint colour cast, warm to cool.
            vec3 tint = mix(vec3(1.0, 0.86, 0.72), vec3(0.78, 0.86, 1.0), hash13(cell + 3.1));
            return star * brightness * tint;
        }

        void main() {
            vec3 dir = normalize(
                uCameraForward +
                uCameraRight * (vNdc.x * uTanHalfFov * uAspect) +
                uCameraUp * (vNdc.y * uTanHalfFov)
            );

            vec3 space = starField(dir);

            // Height of this ray above the local horizon, -1 straight down to
            // +1 straight up.
            float height = dot(dir, uUpDirection);
            float sunAmount = max(dot(dir, uSunDirection), 0.0);

            // Daylight sky: deep blue overhead, pale toward the horizon.
            vec3 zenith = vec3(0.09, 0.22, 0.52);
            vec3 horizon = vec3(0.55, 0.68, 0.86);
            vec3 sky = mix(horizon, zenith, clamp(height, 0.0, 1.0));
            // A wash of light around the sun's direction.
            sky += vec3(0.9, 0.75, 0.55) * pow(sunAmount, 12.0) * 0.5;

            // The atmosphere fades out with altitude, taking the stars from
            // invisible at sea level to fully visible in vacuum.
            vec3 color = mix(space, sky, clamp(uAtmosphereFactor, 0.0, 1.0));

            // A thin band of glow along the horizon, strongest just above it.
            float rim = exp(-abs(height) * 14.0) * uAtmosphereFactor;
            color += vec3(0.30, 0.45, 0.70) * rim * 0.5;

            fragColor = vec4(color, 1.0);
        }
    """.trimIndent()

    // ---- planet ------------------------------------------------------------

    val PLANET_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec3 aNormal;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;

        out vec3 vNormal;
        out vec3 vViewDir;
        out float vDistance;

        void main() {
            vec4 worldPos = uModel * vec4(aPosition, 1.0);
            vNormal = normalize(mat3(uModel) * aNormal);
            // The camera sits at the scene origin, so the vector to it is
            // simply the negated camera-relative position - and its length is
            // how far this patch of ground is from the viewer.
            vDistance = length(worldPos.xyz);
            vViewDir = -worldPos.xyz / max(vDistance, 1.0);
            gl_Position = uViewProjection * worldPos;
        }
    """.trimIndent()

    /**
     * A planet surface with continents, ice and an atmospheric limb.
     *
     * The terrain here is *appearance only* - the simulation collides against a
     * sphere at the datum radius. When real terrain arrives it will come from
     * the same height function on both sides; until then this must not be
     * mistaken for ground that can be landed on.
     */
    /**
     * A planet surface with continents, ice, distance haze and a limb.
     *
     * The terrain here is *appearance only* - the simulation collides against a
     * sphere at the datum radius. When real terrain arrives it will come from
     * the same height function on both sides; until then this must not be
     * mistaken for ground that can be landed on.
     */
    val PLANET_FRAGMENT = """
        #version 300 es
        precision highp float;

        in vec3 vNormal;
        in vec3 vViewDir;
        in float vDistance;

        uniform vec3 uSunDirection;
        uniform vec3 uHomeDirection;
        /** 1 when the camera is at sea level, 0 in vacuum. */
        uniform float uAtmosphereFactor;
        /** Distance at which ground is fully lost to haze, metres. */
        uniform float uHazeDistance;

        out vec4 fragColor;

        float hash13(vec3 p) {
            p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
            p *= 17.0;
            return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
        }

        // Value noise: interpolate a hash over a lattice.
        float noise3(vec3 p) {
            vec3 i = floor(p);
            vec3 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float n000 = hash13(i + vec3(0.0, 0.0, 0.0));
            float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
            float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
            float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
            float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
            float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
            float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
            float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
            return mix(
                mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
                mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y),
                f.z
            );
        }

        void main() {
            vec3 n = normalize(vNormal);

            // Three octaves. More would cost more than it shows at this scale,
            // and low-end drivers are in the test matrix.
            float elevation =
                noise3(n * 2.2) * 0.55 +
                noise3(n * 5.7) * 0.28 +
                noise3(n * 13.0) * 0.17;

            // Put a continent under the launch complex. Cosmetic - the
            // simulation collides with a sphere - but the alternative is a
            // launch pad in open water.
            elevation += 0.26 * smoothstep(0.55, 0.995, dot(n, uHomeDirection));

            vec3 deep = vec3(0.03, 0.09, 0.26);
            vec3 shallow = vec3(0.05, 0.24, 0.42);
            vec3 land = vec3(0.13, 0.30, 0.11);
            vec3 highland = vec3(0.36, 0.30, 0.17);

            // A tight coastline: a wide blend makes every shore look like a
            // swamp and robs the planet of shape at distance.
            float coast = smoothstep(0.515, 0.535, elevation);
            vec3 surface = mix(
                mix(deep, shallow, smoothstep(0.34, 0.50, elevation)),
                mix(land, highland, smoothstep(0.56, 0.74, elevation)),
                coast
            );

            // Ice toward the poles.
            float polar = smoothstep(0.72, 0.92, abs(n.y));
            surface = mix(surface, vec3(0.84, 0.88, 0.93), polar);

            float lambert = max(dot(n, uSunDirection), 0.0);
            // Soften the terminator; a hard edge reads as a rendering fault.
            float daylight = smoothstep(-0.08, 0.35, dot(n, uSunDirection));
            vec3 lit = surface * (0.05 + lambert * 1.15) * daylight;

            // Aerial perspective. From *inside* the atmosphere the far ground
            // is not rimmed with glow, it is washed out by the air in between -
            // which is what makes a horizon read as distant rather than as a
            // painted edge. Exponential, on the same shape as the density model.
            float haze = 1.0 - exp(-vDistance / max(uHazeDistance, 1.0));
            haze *= uAtmosphereFactor;
            vec3 hazeColor = vec3(0.52, 0.66, 0.85) * (0.35 + daylight * 0.65);
            lit = mix(lit, hazeColor, clamp(haze, 0.0, 1.0));

            // The limb is the opposite case: seen from *outside*, a planet's
            // edge glows because the line of sight grazes a long column of air.
            // Fading it out as the camera descends stops the whole surface
            // turning to haze when standing on it.
            float fresnel = pow(1.0 - max(dot(n, vViewDir), 0.0), 3.0);
            lit += vec3(0.25, 0.45, 0.78) * fresnel * daylight * 0.9 *
                (1.0 - clamp(uAtmosphereFactor, 0.0, 1.0));

            fragColor = vec4(lit, 1.0);
        }
    """.trimIndent()

    // ---- orbit lines (map view) --------------------------------------------

    val LINE_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;

        uniform mat4 uViewProjection;
        uniform mat4 uModel;

        void main() {
            gl_Position = uViewProjection * uModel * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    val LINE_FRAGMENT = """
        #version 300 es
        precision mediump float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() {
            fragColor = uColor;
        }
    """.trimIndent()
}
