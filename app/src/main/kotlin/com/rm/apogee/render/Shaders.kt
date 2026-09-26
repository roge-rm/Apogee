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

    /**
     * Light after dark, shared by everything lit: a full moon's worth, faint
     * and blue, and the air's own glow at night as a share of its daytime
     * brightness - so ground, craft, trees and haze all agree on how dark it is.
     */
    private const val NIGHT_LIGHT = """
        const vec3 MOON = vec3(0.21, 0.25, 0.37);
        uniform vec3 uHaze;              // the air's colour over distance
        const float NIGHT_AIR = 0.08;
        // Lightning: a cold white light of its own, day or night - it does
        // not come from the sun, so it is not dimmed with the sun at night.
        const vec3 FLASH = vec3(0.8, 0.85, 1.0);
        uniform float uFlash;
        // How much of the moonlight is left with [daylight] of the sun: all
        // of it until the sun is well up, so dusk is never darker than night.
        float moonLeft(float daylight) { return 1.0 - smoothstep(0.5, 1.0, daylight); }
        // Twilight: with the sun about the horizon the sky itself glows and
        // lights everything from above, a little warm.
        vec3 duskGlow(float daylight) { return vec3(0.17, 0.15, 0.18) * 4.0 * daylight * (1.0 - daylight); }
        // Lamps lit after dark: a floodlight's pool on the concrete, a
        // hangar's light on its floor. Camera-relative position and reach,
        // the nearest few; warm, fading to nothing at the reach, and on
        // what faces them - the outside of a roof over one stays dark.
        const int LAMPS = 8;
        const vec3 LAMP = vec3(1.0, 0.9, 0.62) * 2.2;
        uniform vec4 uLamps[LAMPS];
        uniform int uLampCount;
        vec3 lampLight(vec3 p, vec3 n) {
            vec3 sum = vec3(0.0);
            for (int i = 0; i < LAMPS; i++) {
                if (i >= uLampCount) break;
                vec3 d = uLamps[i].xyz - p;
                float r = length(d);
                float f = clamp(1.0 - r / uLamps[i].w, 0.0, 1.0);
                sum += f * f * max(dot(n, d / max(r, 0.01)), 0.0);
            }
            return LAMP * sum;
        }
    """

    /**
     * How much of the sun's (or at night the moon's) direct light reaches a
     * point: blocked by something near the craft (the near map), by a hill
     * (the mountains' map), or under a cloud (the cloud grid). 1 is all of
     * it. Only direct light: ambient, dusk glow and lightning are untouched,
     * so a shadow is never black. Samplers on units 1, 2 and 3, always - two
     * sampler types sharing a unit is an error at draw time, even unread.
     */
    const val SHADOW = """
        uniform highp sampler2DShadow uNearShadow;
        uniform highp sampler2DShadow uFarShadow;
        uniform highp sampler2D uCloudShadow;
        uniform mat4 uNearShadowMatrix;
        uniform mat4 uFarShadowMatrix;
        uniform mat4 uCloudMatrix;
        uniform float uNearOn;
        uniform float uFarOn;
        uniform float uCloudOn;       // how dark a full cloud's shadow is, 0 for none
        uniform float uNearTexel;     // one texel, in the map's own units
        uniform float uFarTexel;
        uniform float uNearOffset;    // metres along the normal, against self-shading
        uniform float uFarOffset;
        uniform float uKernel;        // extra taps each way: 0 or 1
        uniform float uShadowStrength;

        float shadowTaps(highp sampler2DShadow map, vec3 c, float texel, float kernel) {
            if (kernel < 0.5) return texture(map, c);
            float sum = 0.0;
            for (float x = -1.0; x <= 1.0; x += 1.0)
                for (float y = -1.0; y <= 1.0; y += 1.0)
                    sum += texture(map, c + vec3(x * texel, y * texel, 0.0));
            return sum / 9.0;
        }

        // 1 lit, 0 shaded, fading to lit at the map's edge; -1 off the map.
        float fromMap(highp sampler2DShadow map, mat4 m, vec3 p, float texel, float kernel) {
            vec4 c = m * vec4(p, 1.0);
            vec2 d = abs(c.xy - 0.5) * 2.0;
            float edge = max(d.x, d.y);
            if (edge >= 1.0 || c.z >= 1.0 || c.z <= 0.0) return -1.0;
            return mix(shadowTaps(map, c.xyz, texel, kernel), 1.0, smoothstep(0.8, 1.0, edge));
        }

        float directLight(vec3 p, vec3 n) {
            float lit = 1.0;
            if (uNearOn > 0.5) {
                float v = fromMap(uNearShadow, uNearShadowMatrix, p + n * uNearOffset, uNearTexel, uKernel);
                if (v >= 0.0) lit = v;
            }
            if (uFarOn > 0.5) {
                float v = fromMap(uFarShadow, uFarShadowMatrix, p + n * uFarOffset, uFarTexel, 0.0);
                if (v >= 0.0) lit = min(lit, v);
            }
            // Never all of it: the open sky still lights a shadow, bluish and
            // dim - a third or so of the sun - so it reads as shade, not a hole.
            lit = mix(1.0, lit, uShadowStrength * 0.65);
            if (uCloudOn > 0.0) {
                vec4 c = uCloudMatrix * vec4(p, 1.0);
                if (c.x > 0.0 && c.x < 1.0 && c.y > 0.0 && c.y < 1.0) {
                    vec2 cloud = texture(uCloudShadow, c.xy).rg;
                    // Only under the cloud: flying above it, it shades nothing.
                    float below = 1.0 - smoothstep(cloud.g - 0.004, cloud.g + 0.004, c.z);
                    lit *= 1.0 - cloud.r * uCloudOn * below;
                }
            }
            return lit;
        }
    """

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

    /**
     * [VESSEL_VERTEX] for many cloud lobes in one draw: each lobe's model
     * matrix, shape and colour come from its instance's attributes rather
     * than uniforms. A HIGH sky was nearly two thousand draw calls a frame,
     * one lobe each, and that alone took most of the frame.
     */
    val CLOUD_INSTANCED_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in mat4 iModel;
        // 1/scale^2, and the ambient as its fourth.
        layout(location = 6) in vec4 iInvScaleSq;
        layout(location = 7) in vec4 iColor;

        uniform mat4 uViewProjection;

        flat out vec3 vNormal;
        out float vDistance;
        out vec3 vToCamera;
        flat out vec4 vColor;
        flat out float vAmbient;

        void main() {
            vec4 worldPos = iModel * vec4(aPosition, 1.0);
            vToCamera = -worldPos.xyz;
            vNormal = mat3(iModel) * (aNormal * iInvScaleSq.xyz);
            vDistance = length(worldPos.xyz);
            vColor = iColor;
            vAmbient = iInvScaleSq.w;
            gl_Position = uViewProjection * worldPos;
        }
    """.trimIndent()

    /** [VESSEL_FRAGMENT], its colour and ambient per instance: see [CLOUD_INSTANCED_VERTEX]. */
    val CLOUD_INSTANCED_FRAGMENT: String by lazy {
        VESSEL_FRAGMENT
            .replace("uniform vec4 uColor;", "flat in vec4 vColor;")
            .replace("uniform float uAmbient;", "flat in float vAmbient;")
            .replace("uColor", "vColor")
            .replace("uAmbient", "vAmbient")
    }

    val VESSEL_FRAGMENT = """
        #version 300 es
        // highp: distances run to tens of kilometres now that clouds are
        // drawn here, past what mediump holds on many phone GPUs (65 km).
        precision highp float;

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
        // How much sun reaches here: 1 by day, 0 in the planet's shadow.
        uniform float uDaylight;
        // Aerial perspective, as the ground has it: far things fade into the air.
        uniform float uHazeDistance;
        uniform float uAtmosphereFactor;
        // 1 for a part, which is shaded by what is between it and the sun; 0 for cloud.
        uniform float uReceivesShadow;
        // 1 for another world, seen across space: no haze, no fog, no moon.
        uniform float uSkyBody;

        out vec4 fragColor;

        $NIGHT_LIGHT
        $SHADOW

        void main() {
            vec3 n = normalize(vNormal);
            float direct = uReceivesShadow > 0.5 ? directLight(-vToCamera, n) : 1.0;
            float facing = dot(n, -uLightDirection);
            float wrapped = facing * 0.5 + 0.5;
            // Cloud: light wraps well round, and the shade is soft - facets
            // side by side differ a little, not from white to grey.
            float diffuse = mix(max(facing, 0.0), 0.35 + 0.65 * wrapped, uWrap);
            // At night the sun no longer reaches through the planet: a full
            // moon, opposite it, lights things faint and blue instead.
            float moonFacing = mix(max(-facing, 0.0), 1.0 - wrapped, uWrap);
            vec3 moon = MOON * (0.55 + 0.45 * moonFacing * direct) * (0.4 + 0.6 * uLightScale);
            vec3 lit = uColor.rgb * ((uAmbient + diffuse * 0.8 * uLightScale * direct) * uDaylight + moon * moonLeft(uDaylight) + duskGlow(uDaylight));
            lit += uColor.rgb * FLASH * uFlash;
            if (uLampCount > 0) lit += uColor.rgb * lampLight(-vToCamera, n);
            // Ambient of one or more means it glows - a flame - at its own colour.
            if (uAmbient >= 1.0) lit = uColor.rgb;
            if (uSkyBody > 0.5) {
                // Lit by the sun alone, its night side nearly black; through
                // a day sky, washed a little toward it, as the moon is.
                vec3 world = uColor.rgb * (0.03 + 1.1 * max(facing, 0.0));
                fragColor = vec4(mix(world, uHaze, 0.35 * uAtmosphereFactor * uDaylight), 1.0);
                return;
            }
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            lit = mix(lit, uHaze * (NIGHT_AIR + (1.0 - NIGHT_AIR) * uDaylight), clamp(haze, 0.0, 1.0));
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

    /** For drawing a shadow map: depth only, nothing coloured. */
    val DEPTH_FRAGMENT = """
        #version 300 es
        precision mediump float;
        void main() {}
    """.trimIndent()

    // ---- particles ----------------------------------------------------------

    /** Smoke, dust, spray, rain and bolts: flat-coloured, camera-relative, built on the CPU. */
    val PARTICLE_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec4 aColor;

        uniform mat4 uViewProjection;

        flat out vec4 vColor;
        out float vDistance;

        void main() {
            vColor = aColor;
            vDistance = length(aPosition);
            gl_Position = uViewProjection * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    val PARTICLE_FRAGMENT = """
        #version 300 es
        precision highp float;

        flat in vec4 vColor;
        in float vDistance;

        uniform float uFogDistance;
        uniform vec3 uFogColor;

        out vec4 fragColor;

        void main() {
            float fog = 1.0 - exp(-vDistance / max(uFogDistance, 1.0));
            fragColor = vec4(mix(vColor.rgb, uFogColor, clamp(fog, 0.0, 1.0)), vColor.a);
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
        uniform float uDaylight;         // 1 by day, 0 in the planet's shadow
        // This world's sky.
        uniform vec3 uZenith;
        uniform vec3 uHorizon;
        uniform vec3 uSunset;
        uniform vec3 uRim;
        // The star: cos of its angular radius, and its glow, 0..1.
        uniform float uSunCos;
        uniform float uSunGlow;

        out vec4 fragColor;

        $NIGHT_LIGHT

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
            vec3 day = mix(uHorizon, uZenith, clamp(height, 0.0, 1.0));
            // A wash of light around the sun's direction.
            day += vec3(0.9, 0.75, 0.55) * pow(sunAmount, 12.0) * 0.5;
            // Night: a deep blue-black, a little lighter low down, with the
            // stars through it - dimmed by the air, and gone near the horizon.
            vec3 night = mix(vec3(0.035, 0.05, 0.09), vec3(0.008, 0.014, 0.035), clamp(height * 2.0, 0.0, 1.0));
            night += space * 0.8 * smoothstep(0.0, 0.25, height);
            vec3 sky = mix(night, day, uDaylight);
            // Twilight: a warm band low on the sun's side of the sky while
            // the sun is just below the horizon or just above it.
            vec3 level = normalize(dir - uUpDirection * height + uUpDirection * 1e-4);
            vec3 sunFlat = normalize(uSunDirection - uUpDirection * dot(uSunDirection, uUpDirection) + uUpDirection * 1e-4);
            float dusk = 4.0 * uDaylight * (1.0 - uDaylight);
            float sunSide = pow(max(dot(level, sunFlat), 0.0), 2.0);
            sky += uSunset * dusk * sunSide * exp(-max(height, 0.0) * 7.0) * 0.55;

            // The atmosphere fades out with altitude, taking the stars from
            // invisible at sea level to fully visible in vacuum.
            vec3 color = mix(space, sky, clamp(uAtmosphereFactor, 0.0, 1.0));

            // The star itself: a white disc, softened a pixel's worth at its
            // edge, in a glow that shrinks as it fades into the distance.
            float toSun = dot(dir, uSunDirection);
            if (uSunCos <= 1.0) {
                // (Past the giants the disc is less than a float can tell from a point.)
                float disc = uSunCos < 1.0 ? smoothstep(uSunCos - 0.00003, uSunCos + 0.00001, toSun) : 0.0;
                float glow = pow(max(toSun, 0.0), mix(20000.0, 900.0, uSunGlow)) * (0.35 + 0.65 * uSunGlow);
                color += vec3(1.0, 0.95, 0.85) * (disc * 3.0 + glow * 0.9);
            }

            // A thin band of glow along the horizon, strongest just above it.
            float rim = exp(-abs(height) * 14.0) * uAtmosphereFactor * (0.1 + 0.9 * uDaylight);
            color += uRim * rim * 0.5;

            // A storm overhead greys and darkens it; inside cloud there is
            // only the cloud.
            vec3 overcast = vec3(0.42, 0.45, 0.50) * (0.4 + 0.6 * uLightScale) * (NIGHT_AIR + (1.0 - NIGHT_AIR) * uDaylight);
            color = mix(color, overcast, (1.0 - uLightScale) * uAtmosphereFactor);
            color = mix(color, uFogColor, clamp(uSkyFog, 0.0, 1.0));
            // A strike lights the whole sky it is in.
            color += vec3(0.5, 0.55, 0.7) * uFlash * uAtmosphereFactor;

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
        out vec3 vPosition;

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
            vPosition = world.xyz;
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
        in vec3 vPosition;

        uniform vec3 uSunDirection;
        uniform float uAtmosphereFactor;
        uniform float uHazeDistance;
        uniform float uLightScale;
        uniform float uFogDistance;
        uniform vec3 uFogColor;
        uniform float uDaylight;

        out vec4 fragColor;

        $NIGHT_LIGHT
        $SHADOW

        void main() {
            // Two-sided: the meshes are drawn without culling.
            vec3 n = normalize(vNormal);
            if (!gl_FrontFacing) n = -n;
            float direct = directLight(vPosition, n);
            float lambert = max(dot(n, uSunDirection), 0.0) * direct;
            vec3 moon = MOON * (0.55 + 0.45 * max(-dot(n, uSunDirection), 0.0) * direct) * (0.4 + 0.6 * uLightScale);
            vec3 lit = vColour * ((0.28 + lambert * 0.9 * uLightScale) * uDaylight + moon * moonLeft(uDaylight) + duskGlow(uDaylight));
            lit += vColour * FLASH * uFlash;
            if (uLampCount > 0) lit += vColour * lampLight(vPosition, n);
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            vec3 hazeColor = uHaze * (NIGHT_AIR + (1.0 - NIGHT_AIR) * uDaylight);
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
        // The sea: drawn as waves out to uSeaReach metres (0 for none), and
        // beyond that the sea bed is lifted to the water - uTide above the
        // datum - and coloured as water, as the terrain always used to draw it.
        uniform float uSeaReach;
        uniform float uTide;
        uniform vec3 uBodyCentre;

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
            vSkirt = aWet >= 3.5 ? 1.0 : 0.0;
            float code = aWet >= 3.5 ? aWet - 4.0 : aWet;
            vColour = aColour;
            vWet = 0.0;
            if (code >= 0.5 && length(worldPos.xyz) > uSeaReach) {
                // Out past the waves: water, flat at the tide, blue by depth.
                float depth = (code - 1.0) * 1000.0;
                vec3 up = normalize(worldPos.xyz - uBodyCentre);
                worldPos.xyz += up * (depth + uTide);
                vColour = mix(vec3(0.10, 0.30, 0.46), vec3(0.02, 0.09, 0.22), clamp(depth / 900.0, 0.0, 1.0));
                vWet = 1.0;
            }
            vPosition = worldPos.xyz;
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
        uniform float uDaylight;      // how much sun reaches the camera
        uniform float uLightScale;    // sunlight left under a storm
        uniform float uFogDistance;   // weather fog: cloud, rain
        uniform vec3 uFogColor;

        out vec4 fragColor;

        $NIGHT_LIGHT
        $SHADOW

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

            float direct = directLight(vPosition, n);
            float lambert = max(dot(n, uSunDirection), 0.0) * direct;
            float daylight = smoothstep(-0.08, 0.35, dot(n, uSunDirection));
            // Night is not black: a full moon opposite the sun, faint and
            // blue, so the land keeps its shape after dark.
            vec3 night = MOON * (0.55 + 0.45 * max(-dot(n, uSunDirection), 0.0) * direct) * (0.4 + 0.6 * uLightScale);
            vec3 lit = surface * (night * moonLeft(uDaylight) + duskGlow(uDaylight) + (0.06 + lambert * 1.10 * uLightScale) * daylight);
            lit += surface * FLASH * uFlash;
            if (uLampCount > 0) lit += surface * lampLight(vPosition, n);

            // A glint off the water, which is most of what reads as sea
            // rather than as a blue-painted plain.
            vec3 halfway = normalize(uSunDirection + vViewDir);
            float glint = pow(max(dot(n, halfway), 0.0), 90.0);
            lit += vec3(1.0, 0.96, 0.88) * glint * daylight * wet * 0.8 * direct;

            // Aerial perspective: from inside the atmosphere, distant ground is
            // washed out by the air between, which is what makes a horizon read
            // as distant rather than as a painted edge.
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            vec3 hazeColor = uHaze * (NIGHT_AIR + (1.0 - NIGHT_AIR) * daylight);
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

    // ---- the sea ------------------------------------------------------------

    /**
     * The sea's surface, built on the CPU from the wave function the physics
     * uses. The only arithmetic here is carrying each vertex on by its rate
     * of rise for the few hundredths of a second since the surface was
     * built - the waves themselves are never worked out on the GPU.
     */
    val SEA_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec3 aUp;
        layout(location = 2) in vec4 aColour;
        layout(location = 3) in float aRise;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;
        uniform float uAhead;   // seconds since the surface was built for

        flat out vec4 vColour;
        out vec3 vPosition;
        out float vDistance;
        out vec3 vViewDir;

        void main() {
            vec3 p = aPosition + aUp * (aRise * uAhead);
            vec4 world = uModel * vec4(p, 1.0);
            vPosition = world.xyz;
            vColour = aColour;
            vDistance = length(world.xyz);
            vViewDir = -world.xyz / max(vDistance, 0.001);
            gl_Position = uViewProjection * world;
        }
    """.trimIndent()

    /**
     * Flat facets, as the land: each lit as a plane, catching the sun in a
     * sparkle or the sky in a sheen as it tilts. Colour, clarity and foam
     * arrive from the CPU per facet. Not drawn past [uSeaReach], where the
     * terrain draws flat water; and from beneath, a bright rippled ceiling.
     */
    val SEA_FRAGMENT = """
        #version 300 es
        precision highp float;

        flat in vec4 vColour;
        in vec3 vPosition;
        in float vDistance;
        in vec3 vViewDir;

        uniform vec3 uSunDirection;
        uniform float uAtmosphereFactor;
        uniform float uHazeDistance;
        uniform vec3 uBodyCentre;
        uniform float uDaylight;
        uniform float uLightScale;
        uniform float uFogDistance;
        uniform vec3 uFogColor;
        uniform float uSeaReach;
        uniform float uUnderwater;
        uniform vec3 uSeaSky;            // the sky a sea mirrors

        out vec4 fragColor;

        $NIGHT_LIGHT
        $SHADOW

        void main() {
            if (vDistance > uSeaReach) discard;
            vec3 n = normalize(cross(dFdx(vPosition), dFdy(vPosition)));
            vec3 up = normalize(vPosition - uBodyCentre);
            if (dot(n, up) < 0.0) n = -n;
            bool below = uUnderwater > 0.5;
            float daylight = smoothstep(-0.08, 0.35, dot(up, uSunDirection));

            if (below) {
                // From under the water: the surface a bright, rippling
                // ceiling, lit by the sky above it.
                float through = 0.35 + 0.65 * max(dot(-n, -up), 0.0);
                vec3 lit = vec3(0.30, 0.68, 0.72) * through * (0.15 + 0.85 * daylight * uLightScale);
                float fog = 1.0 - exp(-vDistance / max(uFogDistance, 1.0));
                fragColor = vec4(mix(lit, uFogColor, clamp(fog, 0.0, 1.0)), 0.85);
                return;
            }

            vec3 surface = vColour.rgb;
            float direct = directLight(vPosition, n);
            float lambert = max(dot(n, uSunDirection), 0.0) * direct;
            vec3 night = MOON * (0.55 + 0.45 * max(-dot(n, uSunDirection), 0.0) * direct) * (0.4 + 0.6 * uLightScale);
            // Facets turned to the sun bright, turned away dark: the flat
            // look, on water as on land - harder than on land, for the sea's
            // slopes are gentler.
            float shade = 0.22 + 1.25 * pow(lambert, 0.8) * uLightScale;
            vec3 lit = surface * (night * moonLeft(uDaylight) + duskGlow(uDaylight) + shade * daylight);
            lit += surface * FLASH * uFlash;
            if (uLampCount > 0) lit += surface * lampLight(vPosition, n);

            // The sky in it, most at a glancing angle - what makes water
            // read as water - and the sun's sparkle off facets turned to it.
            float facing = max(dot(n, vViewDir), 0.0);
            float fresnel = 0.03 + 0.97 * pow(1.0 - facing, 5.0);
            vec3 sky = mix(uHaze, uSeaSky, 0.5) * (NIGHT_AIR + (1.0 - NIGHT_AIR) * daylight) * (0.5 + 0.5 * uLightScale);
            lit = mix(lit, sky, fresnel * 0.5);
            vec3 halfway = normalize(uSunDirection + vViewDir);
            float glint = pow(max(dot(n, halfway), 0.0), 140.0);
            lit += vec3(1.0, 0.96, 0.88) * glint * daylight * direct * 1.6 * uLightScale;

            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            lit = mix(lit, uHaze * (NIGHT_AIR + (1.0 - NIGHT_AIR) * daylight), clamp(haze, 0.0, 1.0));
            float fog = 1.0 - exp(-vDistance / max(uFogDistance, 1.0));
            lit = mix(lit, uFogColor, clamp(fog, 0.0, 1.0));

            // Clear over the shallows, but glassy at a low angle.
            float alpha = mix(vColour.a, 1.0, fresnel);
            fragColor = vec4(lit, alpha);
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
