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
        // 1/scale^2 per axis: (1,1,1) for a part, the lobe's shape for a cloud.
        uniform vec3 uInvScaleSq;

        // `flat`: the provoking vertex's normal is used across the whole
        // triangle instead of being interpolated. That one qualifier is the
        // entire faceted look, and it costs nothing - a smooth-normalled
        // cylinder comes out as flat strips.
        flat out vec3 vNormal;
        out float vDistance;
        out vec3 vToCamera;

        void main() {
            // The model matrix is already camera-relative (floating origin), so
            // there is no separate world-space stage here.
            vec4 worldPos = uModel * vec4(aPosition, 1.0);
            vToCamera = -worldPos.xyz;
            // The model matrix is R*S; R*S^-1*n is the normal, and that is
            // R*S times n/S^2 - exact for a stretched cloud lobe, and for a
            // part (unit scale) just the rotation.
            vNormal = mat3(uModel) * (aNormal * uInvScaleSq);
            vDistance = length(worldPos.xyz);
            gl_Position = uViewProjection * worldPos;
        }
    """.trimIndent()

    val VESSEL_FRAGMENT = """
        #version 300 es
        precision mediump float;

        flat in vec3 vNormal;
        in float vDistance;
        in vec3 vToCamera;

        uniform vec4 uColor;
        uniform vec3 uLightDirection;
        // A flat ambient floor stands in for bounce light; without it the
        // unlit side of a craft reads as a hole cut in the sky. Clouds, lit
        // through themselves, take a much higher one.
        uniform float uAmbient;
        // Sunlight left under a storm, 0..1.
        uniform float uLightScale;
        // Weather fog: metres to fade over, and what it fades to.
        uniform float uFogDistance;
        uniform vec3 uFogColor;
        // 1 for cloud: light wraps round it, so a facet in shade still reads
        // by its angle instead of every underside being one flat grey.
        uniform float uWrap;
        // Aerial perspective, as the ground has it: far things fade into the air.
        uniform float uHazeDistance;
        uniform float uAtmosphereFactor;

        out vec4 fragColor;

        void main() {
            vec3 n = normalize(vNormal);
            float facing = dot(n, -uLightDirection);
            float wrapped = facing * 0.5 + 0.5;
            float diffuse = mix(max(facing, 0.0), wrapped * wrapped, uWrap);
            vec3 lit = uColor.rgb * (uAmbient + diffuse * 0.8 * uLightScale);
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            lit = mix(lit, vec3(0.52, 0.66, 0.85), clamp(haze, 0.0, 1.0));
            float fog = 1.0 - exp(-vDistance / max(uFogDistance, 1.0));
            // A cloud thins toward its outline: facets seen edge-on let the
            // sky through, so it ends softly instead of in a hard cut-out.
            float alpha = uColor.a;
            if (uWrap > 0.5) {
                float faceOn = abs(dot(n, normalize(vToCamera)));
                alpha *= mix(0.25, 1.0, smoothstep(0.05, 0.6, faceOn));
            }
            fragColor = vec4(mix(lit, uFogColor, clamp(fog, 0.0, 1.0)), alpha);
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
        uniform float uLightScale;       // sunlight left under a storm
        uniform float uSkyFog;           // 1 inside cloud: the sky is gone
        uniform vec3 uFogColor;

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

            // A storm overhead greys and darkens it; inside cloud there is
            // only the cloud.
            vec3 overcast = vec3(0.42, 0.45, 0.50) * (0.4 + 0.6 * uLightScale);
            color = mix(color, overcast, (1.0 - uLightScale) * uAtmosphereFactor);
            color = mix(color, uFogColor, clamp(uSkyFog, 0.0, 1.0));

            fragColor = vec4(color, 1.0);
        }
    """.trimIndent()

    // ---- planet surface ----------------------------------------------------

    // ---- scatter -------------------------------------------------------------

    /**
     * Rocks and trees, instanced. Each instance is turned about its block's
     * local vertical and scaled, then placed like a terrain chunk: relative to
     * the block's centre, so float only ever sees a few hundred metres.
     */
    val SCATTER_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec3 aColour;
        layout(location = 3) in vec4 aInstance;
        layout(location = 4) in float aYaw;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;
        // East, up, north at the block, as columns.
        uniform mat3 uBasis;
        // The wind at the block, in the same east, up, north axes, m/s.
        uniform vec3 uWind;
        uniform float uTime;

        flat out vec3 vNormal;
        flat out vec3 vColour;
        out float vDistance;

        void main() {
            float c = cos(aYaw);
            float s = sin(aYaw);
            vec3 p = aPosition * aInstance.w;
            vec3 turned = vec3(c * p.x - s * p.z, p.y, s * p.x + c * p.z);
            // Leaning downwind and swaying with the gusts, more the taller -
            // a tree bends, a boulder does not. Each on its own phase, from
            // where it stands, so a forest does not move in step.
            float speed = length(uWind.xz);
            if (speed > 0.1 && turned.y > 0.0) {
                float phase = aInstance.x * 0.37 + aInstance.z * 0.51;
                float bend = clamp(speed / 25.0, 0.0, 0.35) * (0.8 + 0.25 * sin(uTime * 2.3 + phase));
                float reach = turned.y * turned.y / (turned.y + 6.0);
                turned.xz += (uWind.xz / speed) * reach * bend;
            }
            vec3 n = vec3(c * aNormal.x - s * aNormal.z, aNormal.y, s * aNormal.x + c * aNormal.z);
            vec4 world = uModel * vec4(uBasis * turned + aInstance.xyz, 1.0);
            vNormal = normalize(mat3(uModel) * (uBasis * n));
            vColour = aColour;
            vDistance = length(world.xyz);
            gl_Position = uViewProjection * world;
        }
    """.trimIndent()

    /** Lit as the ground is, so a forest sits in its landscape rather than on it. */
    val SCATTER_FRAGMENT = """
        #version 300 es
        precision highp float;

        flat in vec3 vNormal;
        flat in vec3 vColour;
        in float vDistance;

        uniform vec3 uSunDirection;
        uniform float uAtmosphereFactor;
        uniform float uHazeDistance;
        uniform float uLightScale;
        uniform float uFogDistance;
        uniform vec3 uFogColor;

        out vec4 fragColor;

        void main() {
            // Two-sided: the meshes are drawn without culling.
            vec3 n = normalize(vNormal);
            if (!gl_FrontFacing) n = -n;
            float lambert = max(dot(n, uSunDirection), 0.0);
            vec3 lit = vColour * (0.28 + lambert * 0.9 * uLightScale);
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            vec3 hazeColor = vec3(0.52, 0.66, 0.85);
            lit = mix(lit, hazeColor, clamp(haze, 0.0, 1.0));
            float fog = 1.0 - exp(-vDistance / max(uFogDistance, 1.0));
            fragColor = vec4(mix(lit, uFogColor, clamp(fog, 0.0, 1.0)), 1.0);
        }
    """.trimIndent()

    val TERRAIN_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec3 aColour;
        layout(location = 3) in float aWet;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;

        // Flat, for the facets: each triangle takes one vertex's colour whole,
        // which is the low-poly look. Its normal is worked out per triangle in
        // the fragment shader, from the position.
        flat out vec3 vColour;
        flat out float vWet;
        out vec3 vViewDir;
        out float vDistance;
        out vec3 vPosition;
        // Skirts: nonzero anywhere inside a skirt triangle, zero on the
        // ground, and the ground normal the skirt was given to light it by.
        out float vSkirt;
        out vec3 vGroundNormal;

        void main() {
            vec4 worldPos = uModel * vec4(aPosition, 1.0);
            vPosition = worldPos.xyz;
            vColour = aColour;
            vSkirt = aWet >= 1.5 ? 1.0 : 0.0;
            vWet = aWet >= 1.5 ? aWet - 2.0 : aWet;
            vGroundNormal = mat3(uModel) * aNormal;
            // The camera sits at the scene origin, so the vector to it is the
            // negated camera-relative position.
            vDistance = length(worldPos.xyz);
            vViewDir = -worldPos.xyz / max(vDistance, 1.0);
            gl_Position = uViewProjection * worldPos;
        }
    """.trimIndent()

    /**
     * Lights the surface. Its colour arrives already decided.
     *
     * Colour used to be worked out here from height and slope. It is decided
     * on the CPU now, from the same material the collider grips by, so ground
     * that looks like ice is ice. What is left is light, a glint off water,
     * and air.
     */
    val TERRAIN_FRAGMENT = """
        #version 300 es
        precision highp float;

        flat in vec3 vColour;
        flat in float vWet;
        in vec3 vViewDir;
        in float vDistance;
        in vec3 vPosition;
        in float vSkirt;
        in vec3 vGroundNormal;

        uniform vec3 uSunDirection;
        uniform float uAtmosphereFactor;
        uniform float uHazeDistance;
        uniform float uHasAtmosphere; // 1 for a body with air, 0 for one without
        uniform float uDiscardNearer; // the globe leaves the chunks' ground alone
        uniform vec3 uBodyCentre;     // the planet's centre, camera-relative
        uniform float uLightScale;    // sunlight left under a storm
        uniform float uFogDistance;   // weather fog: cloud, rain
        uniform vec3 uFogColor;

        out vec4 fragColor;

        void main() {
            if (vDistance < uDiscardNearer) discard;
            // The triangle's own face, from how its position changes across
            // the screen. It used to be lit by one of its corners' normals -
            // averaged from the ground around that corner - and on rough
            // ground that points well away from the face, so neighbouring
            // triangles came out light and dark in a pattern unrelated to
            // their slope, crawling as the detail changed under a moving
            // camera: the shimmer. This is the facet exactly, and steady.
            vec3 n = normalize(cross(dFdx(vPosition), dFdy(vPosition)));
            if (dot(n, vViewDir) < 0.0) n = -n;
            // A skirt is a vertical strip hiding a crack, not ground: lit as
            // the ground above it, so it does not show as a line.
            if (vSkirt > 0.001) n = normalize(vGroundNormal);
            vec3 surface = vColour;
            float wet = vWet;

            float lambert = max(dot(n, uSunDirection), 0.0);
            float daylight = smoothstep(-0.08, 0.35, dot(n, uSunDirection));
            vec3 lit = surface * (0.06 + lambert * 1.10 * uLightScale) * daylight;

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
            // None at all on an airless world: there is no air to glow, and
            // standing on one, every grazing facet would turn blue.
            // Against the planet's curve - straight up from its centre - not
            // the facet, nor the ground's slope: it is the limb of the world that glows. Taken per facet,
            // every mountain face turned edge-on to the camera lit up blue,
            // and as the camera moved they flickered on and off in streaks.
            vec3 up = normalize(vPosition - uBodyCentre);
            float fresnel = pow(1.0 - max(dot(up, vViewDir), 0.0), 3.0);
            lit += vec3(0.25, 0.45, 0.78) * fresnel * daylight * 0.9 *
                (1.0 - clamp(uAtmosphereFactor, 0.0, 1.0)) * uHasAtmosphere;

            float fog = 1.0 - exp(-vDistance / max(uFogDistance, 1.0));
            lit = mix(lit, uFogColor, clamp(fog, 0.0, 1.0));

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
