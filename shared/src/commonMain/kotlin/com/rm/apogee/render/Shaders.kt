package com.rm.apogee.render

/**
 * GLSL sources as string constants. `#version 300 es` throughout, with no compute, no storage
 * buffers and no `gl_FragDepth`: minSdk 27 means old Adreno and Mali drivers that miscompile
 * anything clever.
 */
object Shaders {

    /**
     * Light after dark, shared by everything lit so it all agrees on how dark it is: a faint blue
     * full moon, and the air's night glow as a share of its daytime brightness.
     */
    private const val NIGHT_LIGHT = """
        const vec3 MOON = vec3(0.21, 0.25, 0.37);
        uniform vec3 uHaze;              // the air's colour over distance
        const float NIGHT_AIR = 0.08;
        // Lightning: its own cold white light, not dimmed at night.
        const vec3 FLASH = vec3(0.8, 0.85, 1.0);
        uniform float uFlash;
        // Moonlight left with [daylight] of the sun. All of it until the sun is well up, so dusk is
        // never darker than night.
        float moonLeft(float daylight) { return 1.0 - smoothstep(0.5, 1.0, daylight); }
        // Twilight: with the sun near the horizon the sky lights things from above, warmly.
        vec3 duskGlow(float daylight) { return vec3(0.17, 0.15, 0.18) * 4.0 * daylight * (1.0 - daylight); }
        // What distant things fade into: the air's colour, greyed and darkened under a storm like
        // the sky (see SKY_FRAGMENT).
        vec3 airHaze(float daylight, float lightScale) {
            float light = NIGHT_AIR + (1.0 - NIGHT_AIR) * daylight;
            vec3 overcast = vec3(0.42, 0.45, 0.50) * (0.4 + 0.6 * lightScale) * light;
            return mix(uHaze * light, overcast, 1.0 - lightScale);
        }
        // Lamps after dark: camera-relative position and reach, nearest few. Warm, fading to nothing
        // at the reach, and only lighting what faces them.
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
        // Under the sea daylight fades with depth, red first. uSea is the body's centre
        // (camera-relative) and the sea's radius, 0 for no sea. uWater is metres per e-fold for
        // red, green and blue.
        uniform vec4 uSea;
        uniform vec3 uWater;
        vec3 underSea(vec3 p) {
            if (uSea.w <= 0.0) return vec3(1.0);
            float below = uSea.w - length(p - uSea.xyz);
            return below > 0.0 ? exp(-below / uWater) : vec3(1.0);
        }
    """

    /**
     * Where the waves fade into the ground's flat water, as a share of the waves' reach, and how
     * far under the waves that water starts, in metres.
     */
    private const val SEA_EDGE = """
        const float SEA_BLEND = 0.8;
        const float SEA_BLEND_SINK = 12.0;
    """

    /**
     * How much of the sun's (or moon's) direct light reaches a point, 1 for all: blocked near the
     * craft (near map), by hills (far map) or by cloud (cloud grid). Ambient, dusk and lightning are
     * untouched, so shadows are never black. Samplers are always on units 1, 2 and 3, since two
     * sampler types sharing a unit is an error at draw time even if unread.
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

        // 1 lit, 0 shaded, fading to lit at the map's edge. -1 off the map.
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
            // Never all of it: the open sky still lights a shadow.
            lit = mix(1.0, lit, uShadowStrength * 0.65);
            if (uCloudOn > 0.0) {
                vec4 c = uCloudMatrix * vec4(p, 1.0);
                if (c.x > 0.0 && c.x < 1.0 && c.y > 0.0 && c.y < 1.0) {
                    vec2 cloud = texture(uCloudShadow, c.xy).rg;
                    // Only below the cloud.
                    float below = 1.0 - smoothstep(cloud.g - 0.004, cloud.g + 0.004, c.z);
                    // Fade out toward the grid's edge so it isn't a dark square from high up.
                    float edge = max(abs(c.x - 0.5), abs(c.y - 0.5)) * 2.0;
                    lit *= 1.0 - cloud.r * uCloudOn * below * (1.0 - smoothstep(0.7, 1.0, edge));
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
        // 1/scale^2 per axis: (1,1,1) for a part, or the lobe's shape for a cloud.
        uniform vec3 uInvScaleSq;

        // `flat`: one normal per triangle. That's the whole faceted look.
        flat out vec3 vNormal;
        out float vDistance;
        out vec3 vToCamera;
        // Out from the shape's axis, and height up it (-1 to 1). Rain curtains only.
        out vec3 vRound;
        out float vHeight;
        out vec3 vAxis;

        void main() {
            // The model matrix is already camera-relative (floating origin).
            vec4 worldPos = uModel * vec4(aPosition, 1.0);
            vToCamera = -worldPos.xyz;
            // The model matrix is R*S, so the normal R*S^-1*n is R*S times n/S^2.
            vNormal = mat3(uModel) * (aNormal * uInvScaleSq);
            vRound = mat3(uModel) * (vec3(aPosition.x, 0.0, aPosition.z) * uInvScaleSq);
            vHeight = aPosition.y;
            vAxis = mat3(uModel) * vec3(0.0, 1.0, 0.0);
            vDistance = length(worldPos.xyz);
            gl_Position = uViewProjection * worldPos;
        }
    """.trimIndent()

    /**
     * [VESSEL_VERTEX] for many cloud lobes in one draw, with model matrix, shape and colour per
     * instance. A HIGH sky has about two thousand lobes.
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
        // Unused by a lobe, but the shared fragment shader reads them.
        out vec3 vRound;
        out float vHeight;
        out vec3 vAxis;

        void main() {
            vec4 worldPos = iModel * vec4(aPosition, 1.0);
            vToCamera = -worldPos.xyz;
            vNormal = mat3(iModel) * (aNormal * iInvScaleSq.xyz);
            vRound = vNormal;
            vHeight = 0.0;
            vAxis = vNormal;
            vDistance = length(worldPos.xyz);
            vColor = iColor;
            vAmbient = iInvScaleSq.w;
            gl_Position = uViewProjection * worldPos;
        }
    """.trimIndent()

    /** [VESSEL_FRAGMENT] with colour and ambient per instance. See [CLOUD_INSTANCED_VERTEX]. */
    val CLOUD_INSTANCED_FRAGMENT: String by lazy {
        VESSEL_FRAGMENT
            .replace("uniform vec4 uColor;", "flat in vec4 vColor;")
            .replace("uniform float uAmbient;", "flat in float vAmbient;")
            .replace("uColor", "vColor")
            .replace("uAmbient", "vAmbient")
    }

    val VESSEL_FRAGMENT = """
        #version 300 es
        // highp: cloud distances run past what mediump holds on many phones (65 km).
        precision highp float;

        flat in vec3 vNormal;
        in float vDistance;
        in vec3 vToCamera;
        in vec3 vRound;
        in float vHeight;
        in vec3 vAxis;

        uniform vec4 uColor;
        uniform vec3 uLightDirection;
        // A flat ambient for bounce light. Clouds take a much higher one.
        uniform float uAmbient;
        // Sunlight left under a storm, 0..1.
        uniform float uLightScale;
        // Weather fog: metres to fade over, and what it fades to.
        uniform float uFogDistance;
        uniform vec3 uFogColor;
        // 1 for cloud: light wraps round, so shaded facets still show their angle.
        uniform float uWrap;
        // How much sun reaches here: 1 by day, 0 in the planet's shadow.
        uniform float uDaylight;
        // Aerial perspective, as on the ground.
        uniform float uHazeDistance;
        uniform float uAtmosphereFactor;
        // 1 for a part, which takes shadows. 0 for cloud.
        uniform float uReceivesShadow;
        // 1 for another world, seen across space: no haze, no fog, no moon.
        uniform float uSkyBody;
        // 1 for a rain curtain. See RenderItem.curtain.
        uniform float uCurtain;

        out vec4 fragColor;

        $NIGHT_LIGHT
        $SHADOW

        void main() {
            vec3 n = normalize(vNormal);
            float direct = uReceivesShadow > 0.5 ? directLight(-vToCamera, n) : 1.0;
            float facing = dot(n, -uLightDirection);
            float wrapped = facing * 0.5 + 0.5;
            // Cloud: wrapped, soft shade.
            float diffuse = mix(max(facing, 0.0), 0.35 + 0.65 * wrapped, uWrap);
            // At night a full moon opposite the sun lights things faint and blue.
            float moonFacing = mix(max(-facing, 0.0), 1.0 - wrapped, uWrap);
            vec3 moon = MOON * (0.55 + 0.45 * moonFacing * direct) * (0.4 + 0.6 * uLightScale);
            vec3 sea = underSea(-vToCamera);
            vec3 lit = uColor.rgb * ((uAmbient + diffuse * 0.8 * uLightScale * direct) * uDaylight + moon * moonLeft(uDaylight) + duskGlow(uDaylight)) * sea;
            lit += uColor.rgb * FLASH * uFlash * sea;
            if (uLampCount > 0) lit += uColor.rgb * lampLight(-vToCamera, n);
            // Ambient of 1 or more glows at its own colour, like a flame.
            if (uAmbient >= 1.0) lit = uColor.rgb;
            if (uSkyBody > 0.5) {
                // Sunlit only, night side nearly black, washed a little toward a day sky.
                vec3 world = uColor.rgb * (0.03 + 1.1 * max(facing, 0.0));
                fragColor = vec4(mix(world, uHaze, 0.35 * uAtmosphereFactor * uDaylight), 1.0);
                return;
            }
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            lit = mix(lit, airHaze(uDaylight, uLightScale), clamp(haze, 0.0, 1.0));
            float fog = 1.0 - exp(-vDistance / max(uFogDistance, 1.0));
            // A cloud thins toward its outline, where facets are edge-on.
            float alpha = uColor.a;
            if (uCurtain > 0.5) {
                // Rain thins to nothing at a shaft's sides, by how squarely its round side faces the
                // camera (measured across the shaft only), and into the cloud at its top.
                vec3 axis = normalize(vAxis);
                vec3 toCamera = normalize(vToCamera);
                vec3 level = toCamera - axis * dot(axis, toCamera);
                float faceOn = abs(dot(normalize(vRound), level)) / max(length(level), 1e-3);
                alpha *= smoothstep(0.0, 0.75, faceOn) * (1.0 - smoothstep(0.45, 1.0, vHeight));
            } else if (uWrap > 0.5) {
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

    /** Smoke, dust, spray, rain and bolts: flat-coloured, camera-relative, and built on the CPU. */
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

    /** One oversized triangle covering the screen: cheaper than a quad, and no diagonal seam. */
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

    /** Stars, and an atmosphere that thins with altitude. The view ray comes from the camera basis. */
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
        uniform float uSkyFog;           // 1 inside cloud, where the sky is gone
        uniform vec3 uFogColor;
        uniform float uDaylight;         // 1 by day, 0 in the planet's shadow
        // This world's sky.
        uniform vec3 uZenith;
        uniform vec3 uHorizon;
        uniform vec3 uSunset;
        uniform vec3 uRim;
        // The star: the cos of its angular radius, and its glow, 0..1.
        uniform float uSunCos;
        uniform float uSunGlow;

        out vec4 fragColor;

        $NIGHT_LIGHT

        // A cheap 3D hash, good enough for stars.
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
            // Vary the brightness so the field doesn't look like a regular grid.
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

            // The ray's height above the local horizon, -1 down to +1 up.
            float height = dot(dir, uUpDirection);
            float sunAmount = max(dot(dir, uSunDirection), 0.0);

            // Daylight sky: deep blue overhead, pale toward the horizon.
            vec3 day = mix(uHorizon, uZenith, clamp(height, 0.0, 1.0));
            // A wash of light around the sun's direction.
            day += vec3(0.9, 0.75, 0.55) * pow(sunAmount, 12.0) * 0.5;
            // Night: deep blue-black, lighter low down, stars fading out near the horizon.
            vec3 night = mix(vec3(0.035, 0.05, 0.09), vec3(0.008, 0.014, 0.035), clamp(height * 2.0, 0.0, 1.0));
            night += space * 0.8 * smoothstep(0.0, 0.25, height);
            vec3 sky = mix(night, day, uDaylight);
            // Twilight: a warm band low on the sun's side while the sun is near the horizon.
            vec3 level = normalize(dir - uUpDirection * height + uUpDirection * 1e-4);
            vec3 sunFlat = normalize(uSunDirection - uUpDirection * dot(uSunDirection, uUpDirection) + uUpDirection * 1e-4);
            float dusk = 4.0 * uDaylight * (1.0 - uDaylight);
            float sunSide = pow(max(dot(level, sunFlat), 0.0), 2.0);
            sky += uSunset * dusk * sunSide * exp(-max(height, 0.0) * 7.0) * 0.55;

            // The air fades with altitude, letting the stars through.
            vec3 color = mix(space, sky, clamp(uAtmosphereFactor, 0.0, 1.0));

            // The star: a white disc with a soft edge, in a glow that shrinks with distance.
            float toSun = dot(dir, uSunDirection);
            if (uSunCos <= 1.0) {
                // Past the giants the disc is too small for a float to tell from a point.
                float disc = uSunCos < 1.0 ? smoothstep(uSunCos - 0.00003, uSunCos + 0.00001, toSun) : 0.0;
                float glow = pow(max(toSun, 0.0), mix(20000.0, 900.0, uSunGlow)) * (0.35 + 0.65 * uSunGlow);
                color += vec3(1.0, 0.95, 0.85) * (disc * 3.0 + glow * 0.9);
            }

            // A thin band of glow along the horizon, strongest just above it.
            float rim = exp(-abs(height) * 14.0) * uAtmosphereFactor * (0.1 + 0.9 * uDaylight);
            color += uRim * rim * 0.5;

            // A storm overhead greys and darkens it, and inside cloud there's only the cloud.
            vec3 overcast = vec3(0.42, 0.45, 0.50) * (0.4 + 0.6 * uLightScale) * (NIGHT_AIR + (1.0 - NIGHT_AIR) * uDaylight);
            color = mix(color, overcast, (1.0 - uLightScale) * uAtmosphereFactor);
            color = mix(color, uFogColor, clamp(uSkyFog, 0.0, 1.0));
            // A strike lights the whole sky it's in.
            color += vec3(0.5, 0.55, 0.7) * uFlash * uAtmosphereFactor;

            fragColor = vec4(color, 1.0);
        }
    """.trimIndent()

    // ---- planet surface ----------------------------------------------------

    // ---- scatter -------------------------------------------------------------

    /**
     * Rocks and trees, instanced. Each is turned about the block's vertical, scaled, and placed
     * relative to the block's centre, so float only sees a few hundred metres.
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
        // East, up and north at the block, as columns.
        uniform mat3 uBasis;
        // The wind at the block, in the same east, up and north axes, in m/s.
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
            // Lean downwind and sway, more the taller it is, each with its own phase.
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

    /** Lit the same way as the ground. */
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
            // Two-sided, because the meshes are drawn without culling.
            vec3 n = normalize(vNormal);
            if (!gl_FrontFacing) n = -n;
            float direct = directLight(vPosition, n);
            float lambert = max(dot(n, uSunDirection), 0.0) * direct;
            vec3 moon = MOON * (0.55 + 0.45 * max(-dot(n, uSunDirection), 0.0) * direct) * (0.4 + 0.6 * uLightScale);
            vec3 sea = underSea(vPosition);
            vec3 lit = vColour * ((0.28 + lambert * 0.9 * uLightScale) * uDaylight + moon * moonLeft(uDaylight) + duskGlow(uDaylight)) * sea;
            lit += vColour * FLASH * uFlash * sea;
            if (uLampCount > 0) lit += vColour * lampLight(vPosition, n);
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            vec3 hazeColor = airHaze(uDaylight, uLightScale);
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
        // The sea: waves out to uSeaReach metres (0 for none). Beyond that the seabed is lifted to
        // the water (uTide above the datum) and coloured as water.
        uniform float uSeaReach;
        uniform float uTide;
        uniform vec3 uBodyCentre;
        $SEA_EDGE

        // Flat: each triangle takes one vertex's colour. The normal comes per triangle in the
        // fragment shader.
        flat out vec3 vColour;
        flat out float vWet;
        out vec3 vViewDir;
        out float vDistance;
        out vec3 vPosition;
        // Nonzero inside a skirt triangle, plus the ground normal to light it by.
        out float vSkirt;
        out vec3 vGroundNormal;

        void main() {
            vec4 worldPos = uModel * vec4(aPosition, 1.0);
            vSkirt = aWet >= 3.5 ? 1.0 : 0.0;
            float code = aWet >= 3.5 ? aWet - 4.0 : aWet;
            vColour = aColour;
            vWet = 0.0;
            float away = length(worldPos.xyz);
            if (code >= 0.5 && away > uSeaReach * SEA_BLEND) {
                // Past the waves: flat water at the tide, blue by depth. It starts a little under
                // the waves inside their edge, so there's no line between them.
                float depth = (code - 1.0) * 1000.0;
                vec3 up = normalize(worldPos.xyz - uBodyCentre);
                float under = SEA_BLEND_SINK * (1.0 - smoothstep(uSeaReach * SEA_BLEND, uSeaReach, away));
                worldPos.xyz += up * (depth + uTide - under);
                vColour = mix(vec3(0.10, 0.30, 0.46), vec3(0.02, 0.09, 0.22), clamp(depth / 900.0, 0.0, 1.0));
                vWet = 1.0;
            }
            vPosition = worldPos.xyz;
            vGroundNormal = mat3(uModel) * aNormal;
            // The camera is at the origin.
            vDistance = length(worldPos.xyz);
            vViewDir = -worldPos.xyz / max(vDistance, 1.0);
            gl_Position = uViewProjection * worldPos;
        }
    """.trimIndent()

    /**
     * Lights the surface. Colour comes from the CPU, from the same material the collider uses, so
     * this only adds light, a glint off water, and air.
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
        uniform float uSkyBody;       // 1 for another world seen across space
        uniform vec3 uFarRim;         // and the colour of its air round its edge
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
            // The triangle's own face normal, from screen derivatives. Vertex normals shimmer on
            // rough ground as the detail changes.
            vec3 n = normalize(cross(dFdx(vPosition), dFdy(vPosition)));
            if (dot(n, vViewDir) < 0.0) n = -n;
            // A skirt hides a crack, so light it like the ground above it.
            if (vSkirt > 0.001) n = normalize(vGroundNormal);
            vec3 surface = vColour;
            float wet = vWet;

            if (uSkyBody > 0.5) {
                // Another world across space: sunlit only, night side nearly black, a glint off its
                // seas, its air glowing round the edge, washed a little toward a day sky.
                vec3 rimUp = normalize(vPosition - uBodyCentre);
                float facing = dot(n, uSunDirection);
                vec3 world = surface * (0.03 + 1.1 * max(facing, 0.0));
                vec3 half0 = normalize(uSunDirection + vViewDir);
                world += vec3(1.0, 0.96, 0.88) * pow(max(dot(n, half0), 0.0), 60.0) * wet * 0.6 * step(0.0, facing);
                float rim = pow(1.0 - max(dot(rimUp, vViewDir), 0.0), 3.0);
                world += uFarRim * rim * smoothstep(-0.1, 0.3, dot(rimUp, uSunDirection)) * 0.9 * uHasAtmosphere;
                fragColor = vec4(mix(world, uHaze, 0.35 * uAtmosphereFactor * uDaylight), 1.0);
                return;
            }

            float direct = directLight(vPosition, n);
            float lambert = max(dot(n, uSunDirection), 0.0) * direct;
            float daylight = smoothstep(-0.08, 0.35, dot(n, uSunDirection));
            // A faint blue full moon keeps the land's shape after dark.
            vec3 night = MOON * (0.55 + 0.45 * max(-dot(n, uSunDirection), 0.0) * direct) * (0.4 + 0.6 * uLightScale);
            vec3 sea = underSea(vPosition);
            vec3 lit = surface * (night * moonLeft(uDaylight) + duskGlow(uDaylight) + (0.06 + lambert * 1.10 * uLightScale) * daylight) * sea;
            lit += surface * FLASH * uFlash * sea;
            if (uLampCount > 0) lit += surface * lampLight(vPosition, n);

            // A glint off the water.
            vec3 halfway = normalize(uSunDirection + vViewDir);
            float glint = pow(max(dot(n, halfway), 0.0), 90.0);
            lit += vec3(1.0, 0.96, 0.88) * glint * daylight * wet * 0.8 * direct;

            // Aerial perspective: distant ground fades into the air.
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            vec3 hazeColor = airHaze(daylight, uLightScale);
            lit = mix(lit, hazeColor, clamp(haze, 0.0, 1.0));

            // Seen from outside, a planet's edge glows. Fades out as the camera comes down, and none
            // without air. Taken against the planet's curve, not the facet, or edge-on mountain
            // faces flicker blue.
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
     * The sea's surface, built on the CPU from the physics' wave function. Here each vertex is only
     * carried on by its rate of rise since the surface was built.
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
     * Flat facets like the land, each catching sun or sky as it tilts. Colour, clarity and foam come
     * from the CPU. Not drawn past [uSeaReach], where the terrain draws flat water. From beneath
     * it's a bright rippled ceiling.
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
        $SEA_EDGE
        uniform float uUnderwater;
        uniform vec3 uSeaSky;            // the sky a sea reflects

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
                // From below: a bright ceiling lit by the sky.
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
            // Bright to the sun, dark away; harder than on land since the slopes are gentler.
            float shade = 0.22 + 1.25 * pow(lambert, 0.8) * uLightScale;
            // Foam is white whichever way it's turned. Nothing but foam is this white.
            float foam = smoothstep(0.86, 0.9, min(min(surface.r, surface.g), surface.b));
            shade = mix(shade, (0.85 + 0.3 * lambert) * uLightScale, foam);
            vec3 lit = surface * (night * moonLeft(uDaylight) + duskGlow(uDaylight) + shade * daylight);
            lit += surface * FLASH * uFlash;
            if (uLampCount > 0) lit += surface * lampLight(vPosition, n);

            // The sky reflected, most at a glancing angle, and the sun's sparkle.
            float facing = max(dot(n, vViewDir), 0.0);
            float fresnel = 0.03 + 0.97 * pow(1.0 - facing, 5.0);
            vec3 sky = mix(uHaze, uSeaSky, 0.5) * (NIGHT_AIR + (1.0 - NIGHT_AIR) * daylight) * (0.5 + 0.5 * uLightScale);
            lit = mix(lit, sky, fresnel * 0.5 * (1.0 - foam));
            vec3 halfway = normalize(uSunDirection + vViewDir);
            float glint = pow(max(dot(n, halfway), 0.0), 140.0);
            lit += vec3(1.0, 0.96, 0.88) * glint * daylight * direct * 1.6 * uLightScale * (1.0 - foam);

            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            lit = mix(lit, airHaze(daylight, uLightScale), clamp(haze, 0.0, 1.0));
            float fog = 1.0 - exp(-vDistance / max(uFogDistance, 1.0));
            lit = mix(lit, uFogColor, clamp(fog, 0.0, 1.0));

            // Clear in the shallows, glassy at low angles, fading into the flat water at the edge.
            float alpha = mix(vColour.a, 1.0, fresnel) * (1.0 - smoothstep(uSeaReach * SEA_BLEND, uSeaReach, vDistance));
            fragColor = vec4(lit, alpha);
        }
    """.trimIndent()

    // ---- the map's cloud: a veil over the globe --------------------------

    val CLOUD_SHELL_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec4 aColour;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;

        out vec4 vColour;
        out vec3 vUp;
        out vec3 vBody;

        void main() {
            vColour = aColour;
            vUp = mat3(uModel) * aPosition;
            vBody = aPosition;
            gl_Position = uViewProjection * uModel * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    /**
     * Sunlit like the ground below: bright by day, faint grey at night. Cover comes from the
     * vertices; texture from per-pixel noise in the planet's frame, so it stays put over the ground.
     */
    val CLOUD_SHELL_FRAGMENT = """
        #version 300 es
        precision highp float;

        in vec4 vColour;
        in vec3 vUp;
        in vec3 vBody;

        uniform vec3 uSunDirection;
        // Noise cells per body radius, so a cell is the same size on any world.
        uniform float uNoiseScale;
        // A slow drift, so the texture moves as the weather does.
        uniform float uDrift;

        out vec4 fragColor;

        float hash(vec3 p) {
            p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
            p *= 17.0;
            return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
        }

        float noise(vec3 x) {
            vec3 i = floor(x);
            vec3 f = fract(x);
            f = f * f * (3.0 - 2.0 * f);
            return mix(
                mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x), mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
                mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x), mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y),
                f.z);
        }

        void main() {
            vec3 p = normalize(vBody) * uNoiseScale + vec3(uDrift, 0.0, -uDrift);
            float n = 0.5 * noise(p) + 0.3 * noise(p * 2.3 + 7.1) + 0.2 * noise(p * 5.1 - 3.7);
            // Stretch to 0..1, since layered noise sits near a half. It thins or thickens the cover
            // round the weather's amount and opens breaks where thinnest.
            float m = clamp((n - 0.5) * 2.4 + 0.5, 0.0, 1.0);
            float alpha = min(vColour.a * (0.25 + 0.95 * m), 0.9) * smoothstep(0.08, 0.3, m);
            // Thick cloud's tops are brighter than a thin veil's.
            vec3 colour = vColour.rgb * (0.82 + 0.18 * m);
            float day = smoothstep(-0.12, 0.3, dot(normalize(vUp), uSunDirection));
            fragColor = vec4(colour * (0.05 + 0.95 * day), alpha * (0.3 + 0.7 * day));
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
