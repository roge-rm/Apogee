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

        // `flat`: the provoking vertex's normal is used across the whole
        // triangle instead of being interpolated. That one qualifier is the
        // entire faceted look, and it costs nothing - a smooth-normalled
        // cylinder comes out as flat strips.
        flat out vec3 vNormal;

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

        flat in vec3 vNormal;

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

    // ---- planet surface ----------------------------------------------------

    val TERRAIN_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in float aElevation;
        layout(location = 3) in float aSlope;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;

        // Flat, for the same reason as the vessel shader. Screen-space
        // derivatives would give a truer face normal, but they measure a
        // pixel-scale change against a camera-relative position of tens of
        // metres and come back as noise.
        flat out vec3 vNormal;
        flat out float vSlope;
        out vec3 vViewDir;
        out float vElevation;
        out float vDistance;

        void main() {
            vec4 worldPos = uModel * vec4(aPosition, 1.0);
            vNormal = normalize(mat3(uModel) * aNormal);
            vSlope = aSlope;
            vElevation = aElevation;
            // The camera sits at the scene origin, so the vector to it is the
            // negated camera-relative position, and its length is how far this
            // patch of ground is from the viewer.
            vDistance = length(worldPos.xyz);
            vViewDir = -worldPos.xyz / max(vDistance, 1.0);
            gl_Position = uViewProjection * worldPos;
        }
    """.trimIndent()

    /**
     * Colours the surface from the height and slope it was *built* with.
     *
     * Note what is absent: any noise at all. The terrain arrives as geometry
     * sampled from the simulation's own height field, so this shader only has
     * to decide what that height looks like. That is the whole point - there
     * is one definition of the surface, and it is not in here.
     */
    val TERRAIN_FRAGMENT = """
        #version 300 es
        precision highp float;

        flat in vec3 vNormal;
        flat in float vSlope;
        in vec3 vViewDir;
        in float vElevation;
        in float vDistance;

        uniform vec3 uSunDirection;
        uniform float uAtmosphereFactor;
        uniform float uHazeDistance;
        uniform float uMaxElevation;

        out vec4 fragColor;

        void main() {
            vec3 n = normalize(vNormal);

            // Height bands, in metres rather than as a fraction of the
            // tallest peak the field could theoretically produce. Normalising
            // by uMaxElevation put every band the craft ever flies over into
            // the bottom sixth of the scale, and the whole world came out one
            // shade of green.
            float h = vElevation;

            vec3 shore = vec3(0.72, 0.66, 0.46);
            vec3 grass = vec3(0.22, 0.42, 0.18);
            vec3 meadow = vec3(0.30, 0.46, 0.20);
            vec3 upland = vec3(0.35, 0.40, 0.21);
            vec3 dry = vec3(0.48, 0.44, 0.27);
            vec3 rock = vec3(0.38, 0.35, 0.32);
            vec3 snow = vec3(0.92, 0.94, 0.97);

            // Hard steps, not gradients. A low-poly look is as much about a
            // small palette with visible edges as it is about the facets.
            vec3 surface = shore;
            surface = mix(surface, grass, step(30.0, h));
            surface = mix(surface, meadow, step(220.0, h));
            surface = mix(surface, upland, step(520.0, h));
            surface = mix(surface, dry, step(900.0, h));
            surface = mix(surface, rock, step(1350.0, h));
            surface = mix(surface, snow, step(1800.0, h));

            // Anything steep is bare rock whatever height it is at, which is
            // what turns a hillside into a hillside rather than a green ramp.
            surface = mix(surface, rock, step(0.22, vSlope));

            // Below the datum this mesh is water, not ground.
            //
            // Both meshes clamp their ocean vertices to sea level and leave
            // the colouring here, so water is a band on the one surface
            // rather than a second sphere over a sunken sea floor. Depth
            // comes from how far down the floor *would* have been, which the
            // elevation attribute still carries although the geometry is flat.
            float depth = clamp(-vElevation / 900.0, 0.0, 1.0);
            vec3 water = mix(vec3(0.10, 0.30, 0.46), vec3(0.02, 0.09, 0.22), depth);
            float wet = step(vElevation, 0.0);
            surface = mix(surface, water, wet);

            // Ice toward the poles.
            float polar = step(0.86, abs(n.y));
            surface = mix(surface, snow, polar * 0.85);

            float lambert = max(dot(n, uSunDirection), 0.0);
            float daylight = smoothstep(-0.08, 0.35, dot(n, uSunDirection));
            vec3 lit = surface * (0.06 + lambert * 1.10) * daylight;

            // A glint off the water, which is most of what reads as sea
            // rather than as a blue-painted plain.
            vec3 halfway = normalize(uSunDirection + vViewDir);
            float glint = pow(max(dot(n, halfway), 0.0), 90.0);
            lit += vec3(1.0, 0.96, 0.88) * glint * daylight * wet * 0.8;

            // Aerial perspective: from inside the atmosphere, distant ground is
            // washed out by the air between, which is what makes a horizon read
            // as distant rather than as a painted edge.
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            vec3 hazeColor = vec3(0.52, 0.66, 0.85) * (0.35 + daylight * 0.65);
            lit = mix(lit, hazeColor, clamp(haze, 0.0, 1.0));

            // Seen from outside, a planet's edge glows because the line of
            // sight grazes a long column of air. Faded out as the camera
            // descends, or the whole surface turns to haze when standing on it.
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
