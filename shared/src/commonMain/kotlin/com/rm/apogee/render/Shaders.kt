package com.rm.apogee.render

/**
 * GLSL sources as string constants. `#version 300 es` throughout, with no compute, no storage
 * buffers and no `gl_FragDepth`: minSdk 27 means old Adreno and Mali drivers that miscompile
 * anything clever.
 */
object Shaders {
    /**
     * The weather's fog, with dust counted only along the part of the way that's inside its layer:
     * [weatherFog] for a point [rel] from the camera (render space), [d] metres off. The layer's top
     * is uDustTop over the camera (under it if negative), uFogUp up at the camera; uDustFog 0 for
     * no dust, when it's the plain fog. Needs uFogDistance declared before it.
     */
    private val DUST_FOG = """
        uniform float uDustFog;
        uniform float uDustTop;
        uniform vec3 uFogUp;

        float dustPath(vec3 rel, float d) {
            float s = dot(rel, uFogUp);
            if (s >= 0.0) return uDustTop <= 0.0 ? 0.0 : (s <= uDustTop ? d : d * uDustTop / s);
            return uDustTop >= 0.0 ? d : (s >= uDustTop ? 0.0 : d * (1.0 - uDustTop / s));
        }

        float weatherFog(vec3 rel, float d) {
            float k = d / max(uFogDistance, 1.0);
            if (uDustFog > 0.0) k += dustPath(rel, d) / uDustFog;
            return 1.0 - exp(-k);
        }
    """


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
        const float FLASH_AIR = 0.6;
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
            // A strike lights the air it's in.
            return mix(uHaze * light, overcast, 1.0 - lightScale) + FLASH * FLASH_AIR * uFlash;
        }
        // Lamps after dark: camera-relative position and reach, nearest few. Warm, fading to nothing
        // at the reach, and only lighting what faces them. A beam's way and the cosine of its edge
        // are in uLampBeams; an edge past -1 lights all round.
        const int LAMPS = 8;
        const vec3 LAMP = vec3(1.0, 0.9, 0.62) * 2.2;
        uniform vec4 uLamps[LAMPS];
        uniform vec4 uLampBeams[LAMPS];
        uniform int uLampCount;
        vec3 lampLight(vec3 p, vec3 n) {
            vec3 sum = vec3(0.0);
            for (int i = 0; i < LAMPS; i++) {
                if (i >= uLampCount) break;
                vec3 d = uLamps[i].xyz - p;
                float r = length(d);
                vec3 toward = d / max(r, 0.01);
                float f = clamp(1.0 - r / uLamps[i].w, 0.0, 1.0);
                // Soft at the beam's edge.
                float edge = uLampBeams[i].w;
                float inBeam = edge < -1.0 ? 1.0 : smoothstep(edge, edge + 0.08, dot(-toward, uLampBeams[i].xyz));
                sum += f * f * inBeam * max(dot(n, toward), 0.0);
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
     * Where the ground's flat water rises from the bed and the waves fade into it, as shares of the
     * waves' reach, and how far under the waves that water starts, in metres.
     */
    private val TSUNAMI_FIELD = """
        // Tsunamis' sea round the camera (see TsunamiField): heights in cm, and for the two drawn
        // quarter seconds since each arrived with its height in deep water in what's left over,
        // ${TsunamiField.SIDE} a side, uTsunamiGrid.x metres apart along uTsunamiEast and
        // uTsunamiNorth from uTsunamiCentre (camera-relative); uTsunamiGrid.y is 0 for none.
        uniform vec4 uTsunami[${TsunamiField.VEC4S}];
        uniform vec4 uTsunamiFirst[${TsunamiField.VEC4S}];
        uniform vec4 uTsunamiSecond[${TsunamiField.VEC4S}];
        uniform vec3 uTsunamiCentre;
        uniform vec3 uTsunamiEast;
        uniform vec3 uTsunamiNorth;
        uniform vec3 uTsunamiGrid;
        // The first's seconds between crests, seconds since the field was worked out, the most it
        // reaches on a coast in metres, and the shelf's delay per (sqrt 6 - sqrt depth) in seconds.
        uniform vec4 uTsunamiWave;
        // The second's seconds between crests (0 for none) and the most it reaches on a coast.
        uniform vec4 uTsunamiWave2;
        const float WET_BED = 0.4;
        float tsunamiPick(vec4 v, int k) {
            int c = k - (k / 4) * 4;
            return c == 0 ? v.x : c == 1 ? v.y : c == 2 ? v.z : v.w;
        }
        // Height, and the first's deep height and seconds since it arrived.
        vec3 tsunamiPoint(int k) {
            float v = tsunamiPick(uTsunamiFirst[k / 4], k);
            float q = floor(v);
            return vec3(tsunamiPick(uTsunami[k / 4], k) * 0.01, (v - q) * ${TsunamiField.DEEP_SCALE}, q * 0.25);
        }
        // The second's deep height and seconds since it arrived.
        vec2 tsunamiSecond(int k) {
            float v = tsunamiPick(uTsunamiSecond[k / 4], k);
            float q = floor(v);
            return vec2((v - q) * ${TsunamiField.DEEP_SCALE}, q * 0.25);
        }
        // At camera-relative p: how far the tsunamis move the sea, in metres, the first's height in
        // deep water there and seconds since it arrived (with the field's age in), and the same two
        // for the second in [second].
        vec3 tsunamiAt(vec3 p, out vec2 second) {
            second = vec2(0.0, ${NO_CREST});
            if (uTsunamiGrid.y < 0.5) return vec3(0.0, 0.0, ${NO_CREST});
            const int SIDE = ${TsunamiField.SIDE};
            float middle = float(SIDE - 1) * 0.5;
            vec3 d = p - uTsunamiCentre;
            float x = dot(d, uTsunamiEast) / uTsunamiGrid.x + middle;
            float y = dot(d, uTsunamiNorth) / uTsunamiGrid.x + middle;
            if (x < 0.0 || y < 0.0 || x >= float(SIDE - 1) || y >= float(SIDE - 1)) return vec3(0.0, 0.0, ${NO_CREST});
            int i = int(x); int j = int(y);
            float fx = x - float(i); float fy = y - float(j);
            int k = j * SIDE + i;
            vec3 v = mix(mix(tsunamiPoint(k), tsunamiPoint(k + 1), fx), mix(tsunamiPoint(k + SIDE), tsunamiPoint(k + SIDE + 1), fx), fy);
            vec2 w = mix(mix(tsunamiSecond(k), tsunamiSecond(k + 1), fx), mix(tsunamiSecond(k + SIDE), tsunamiSecond(k + SIDE + 1), fx), fy);
            // Gone by the square's edge.
            float edge = max(abs(x - middle), abs(y - middle)) / middle;
            float fade = 1.0 - smoothstep(0.7, 1.0, edge);
            second = vec2(w.x * fade, w.y + uTsunamiWave.y);
            return vec3(v.xy * fade, v.z + uTsunamiWave.y);
        }
        // One tsunami [tau] seconds after it arrived, [deep] metres high in deep water, over water
        // [depth] metres deep: seconds since its crest, how white, which tier it's drawn in and how
        // big, as Tsunamis.drawn has them.
        vec4 tsunamiOne(float tauRaw, float deep, float depth, float period, float cap) {
            // Slowed over the shelf, it gets there later.
            float tau = tauRaw - uTsunamiWave.w * (2.449 - sqrt(clamp(depth, 0.0, 6.0)));
            if (period <= 0.0 || tau >= 7200.0 || tauRaw <= 0.0) return vec4(${NO_CREST}, 0.0, 0.0, -1.0);
            float d = max(depth, 2.0);
            float a = min(cap, deep * sqrt(sqrt(3000.0 / d)));
            float steep = smoothstep(0.1, 0.35, a / d);
            float envelope = exp(-max(tau, 0.0) / 2400.0) * (1.0 - smoothstep(5760.0, 7200.0, tau));
            float white = (smoothstep(0.6, 0.9, steep) - 0.8 * (1.0 - smoothstep(0.0, 0.15, steep))) * smoothstep(0.3, 0.9, a * envelope);
            float rise = 0.5 + (min(3.0 / period, 0.5) - 0.5) * steep;
            float crest = tau - (0.5 + 0.5 * rise) * period;
            // Its own crest nearest behind (Tsunamis.sinceCrest), for which tier its foam is in.
            float k = floor((crest + period - 250.0) / period);
            float since = k > 0.0 ? crest - k * period : crest;
            float tier = abs(white) < 0.01 || since <= -30.0 || since >= 240.0 ? 0.0 : since < 70.0 ? 2.0 : 1.0;
            return vec4(crest, white, tier, a * envelope);
        }
        // Seconds since the crest, how white it is (see TSUNAMI_FOAM) and its period, at water
        // [depth] metres deep, from tsunamiAt's [t] and [second]: whichever of the two
        // Tsunamis.drawn would pick, a bore or lines showing over a paler sea behind over none,
        // then the bigger.
        vec3 tsunamiCrest(vec3 t, vec2 second, float depth) {
            vec4 a = tsunamiOne(t.z, t.y, depth, uTsunamiWave.x, uTsunamiWave.z);
            vec4 b = tsunamiOne(second.y, second.x, depth, uTsunamiWave2.x, uTsunamiWave2.y);
            if (b.z > a.z || (b.z == a.z && b.w > a.w)) return vec3(b.xy, uTsunamiWave2.x);
            return vec3(a.xy, a.w < 0.0 ? 0.0 : uTsunamiWave.x);
        }
    """

    /**
     * A tsunami's foam, 0..1, from [c] seconds since its first crest passed (Tsunamis.crest), [w]
     * how white (over 0 a bore with lines behind, under 0 a gentle crest's line), [period] seconds
     * between crests (0 for the first only), [blur] seconds a pixel spans and [slow] seconds per
     * metre along the ground (tsunamiSlowness). Narrower than a pixel, a band is widened and
     * dimmed, and the lines go, so nothing aliases far off.
     */
    private val TSUNAMI_FOAM = """
        const vec3 TSUNAMI_WHITE = vec3(0.93, 0.93, 0.97);
        float tsunamiBand(float s, float lo, float hi, float blur) {
            float half0 = 0.5 * (hi - lo);
            float wide = max(half0, blur);
            float d = abs(s - 0.5 * (hi + lo)) - wide;
            return (1.0 - smoothstep(-0.5 * blur, 0.5 * blur, d)) * sqrt(half0 / wide);
        }
        // Seconds per metre along the ground that [c] changes by at [p], from its screen derivatives.
        float tsunamiSlowness(float c, vec3 p) {
            vec2 dc = vec2(dFdx(c), dFdy(c));
            vec3 a = dFdx(p); vec3 b = dFdy(p);
            float aa = dot(a, a); float ab = dot(a, b); float bb = dot(b, b);
            float det = aa * bb - ab * ab;
            if (det <= 0.0) return 1.0;
            return sqrt(max((bb * dc.x * dc.x - 2.0 * ab * dc.x * dc.y + aa * dc.y * dc.y) / det, 0.0));
        }
        float tsunamiFoam(float c, float w, float period, float blur, float slow) {
            if (abs(w) < 0.01) return 0.0;
            float k = period > 0.0 ? max(floor(c / period + 0.5), 0.0) : 0.0;
            float s = c - k * period;
            blur = max(blur, 0.05);
            float white;
            if (w > 0.0) {
                // The face and the white water just behind it, then lines thinning out.
                white = tsunamiBand(s, -3.0, 15.0, blur);
                float behind = s - 15.0;
                if (behind > 0.0 && behind < 52.0) {
                    float line = 0.5 + 0.5 * cos(6.2831853 * behind / 7.0);
                    float edge = 0.45 + 0.55 * smoothstep(0.0, 52.0, behind);
                    white = max(white, (1.0 - smoothstep(0.84, 1.75, blur)) * smoothstep(edge, edge + 0.1, line) * 0.9);
                }
            } else {
                // No wider than 150 m on the ground, as out at sea it runs at 100 m/s and more, and
                // none where two fronts meet and the time hardly changes.
                white = tsunamiBand(s, -min(8.0, 150.0 * slow), 0.0, blur) * smoothstep(0.0015, 0.004, slow);
            }
            // The sea behind the front a little paler.
            white = max(white, 0.12 * smoothstep(-blur, blur, s) * (1.0 - smoothstep(30.0, 240.0, s)));
            // Nothing where a pixel spans minutes: the edge of the train, or a triangle that's half dry.
            return white * abs(w) * (1.0 - smoothstep(30.0, 90.0, blur));
        }
    """

    /** Seconds since no crest has come: [com.rm.apogee.core.weather.Weather.TSUNAMI_NO_CREST]. */
    private const val NO_CREST = "-10000.0"

    /** 2 pi / 4096: the froth's sines repeat every 4096 m, so the origin can be taken modulo that. */
    private const val FROTH_RATE = "0.0015339808"

    private const val SEA_EDGE = """
        const float SEA_RISE = 0.6;
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
        $DUST_FOG
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
            // A negative ambient marks ground dust: shaded softly so facets barely show, little
            // hazed, and solid up close.
            bool dust = uAmbient < 0.0;
            float ambient = abs(uAmbient);
            // Cloud: wrapped, soft shade.
            float diffuse = dust ? 0.7 + 0.3 * wrapped : mix(max(facing, 0.0), 0.35 + 0.65 * wrapped, uWrap);
            // At night a full moon opposite the sun lights things faint and blue.
            float moonFacing = mix(max(-facing, 0.0), 1.0 - wrapped, uWrap);
            vec3 moon = MOON * (0.55 + 0.45 * moonFacing * direct) * (0.4 + 0.6 * uLightScale);
            vec3 sea = underSea(-vToCamera);
            vec3 lit = uColor.rgb * ((ambient + diffuse * 0.8 * uLightScale * direct) * uDaylight + moon * moonLeft(uDaylight) + duskGlow(uDaylight)) * sea;
            lit += uColor.rgb * FLASH * uFlash * sea;
            if (uLampCount > 0) lit += uColor.rgb * lampLight(-vToCamera, n);
            // Ambient of 1 or more glows at its own colour, like a flame.
            if (ambient >= 1.0) lit = uColor.rgb;
            if (uSkyBody > 0.5) {
                // Sunlit only, night side nearly black, washed a little toward a day sky.
                vec3 world = uColor.rgb * (0.03 + 1.1 * max(facing, 0.0));
                fragColor = vec4(mix(world, uHaze, 0.35 * uAtmosphereFactor * uDaylight), 1.0);
                return;
            }
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor * (dust ? 0.4 : 1.0);
            lit = mix(lit, airHaze(uDaylight, uLightScale), clamp(haze, 0.0, 1.0));
            float fog = weatherFog(-vToCamera, vDistance);
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
            } else if (dust) {
                float faceOn = abs(dot(n, normalize(vToCamera)));
                alpha *= mix(0.7, 1.0, smoothstep(0.05, 0.6, faceOn)) * smoothstep(1.0, 12.0, length(vToCamera));
            } else if (uWrap > 0.5) {
                float faceOn = abs(dot(n, normalize(vToCamera)));
                alpha *= mix(0.25, 1.0, smoothstep(0.05, 0.6, faceOn));
                // Thin the surface before the camera crosses it; weather fog fills the interior.
                alpha *= smoothstep(0.0, 250.0, length(vToCamera));
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
        out vec3 vRelative;

        void main() {
            vColor = aColor;
            vDistance = length(aPosition);
            vRelative = aPosition;
            gl_Position = uViewProjection * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    val PARTICLE_FRAGMENT = """
        #version 300 es
        precision highp float;

        flat in vec4 vColor;
        in float vDistance;
        in vec3 vRelative;

        uniform float uFogDistance;
        uniform vec3 uFogColor;
        $DUST_FOG

        out vec4 fragColor;

        void main() {
            float fog = weatherFog(vRelative, vDistance);
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
        uniform float uDustFog;          // dust's fog distance, metres; 0 for none
        uniform float uDustTop;          // the dust layer's top over the camera, metres
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
            // Dust hides the sky by how much of it the way up passes through: all of it level or
            // down, less the steeper the way out of a low layer.
            float dustSky = 0.0;
            if (uDustFog > 0.0) {
                float path = height > 0.0 ? (uDustTop <= 0.0 ? 0.0 : uDustTop / max(height, 1e-3)) : 1e9;
                dustSky = 0.97 * (1.0 - exp(-path / uDustFog));
            }
            color = mix(color, uFogColor, clamp(max(uSkyFog, dustSky), 0.0, 1.0));
            // The sun's glow scattered round it in the dust.
            color += uFogColor * 0.9 * pow(max(toSun, 0.0), 24.0) * dustSky * uDaylight;
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
        // Each world's own colour for the kind, multiplied in.
        uniform vec3 uTint;

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
            vColour = aColour * uTint;
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
        $DUST_FOG
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
            float fog = weatherFog(vPosition, vDistance);
            fragColor = vec4(mix(lit, uFogColor, clamp(fog, 0.0, 1.0)), 1.0);
        }
    """.trimIndent()

    /**
     * Water over low land past the waves, from a tsunami's flood grid (TsunamiFlood): levels over
     * the datum in an RG8 texture, two bytes each, 0 where dry.
     */
    private val TSUNAMI_FLOOD = """
        uniform highp sampler2D uTsunamiFlood;
        uniform vec3 uFloodCentre;   // camera-relative
        uniform vec3 uFloodEast;
        uniform vec3 uFloodNorth;
        // Spacing in metres, 1 when there's a flood, the camera's height over the datum and the
        // body's radius, in metres.
        uniform vec4 uFloodGrid;
        float floodTexel(int i, int j) {
            vec2 v = texelFetch(uTsunamiFlood, ivec2(i, j), 0).rg;
            return (floor(v.r * 255.0 + 0.5) * 256.0 + floor(v.g * 255.0 + 0.5)) / ${TsunamiFlood.PER_METRE};
        }
        // The flood's level at camera-relative p, from the flooded points round it, or 0.
        float floodLevel(vec3 p) {
            const int SIDE = ${TsunamiFlood.SIDE};
            float middle = float(SIDE - 1) * 0.5;
            vec3 d = p - uFloodCentre;
            float x = dot(d, uFloodEast) / uFloodGrid.x + middle;
            float y = dot(d, uFloodNorth) / uFloodGrid.x + middle;
            if (x < 0.0 || y < 0.0 || x >= float(SIDE - 1) || y >= float(SIDE - 1)) return 0.0;
            int i = int(x); int j = int(y);
            float fx = x - float(i); float fy = y - float(j);
            float sum = 0.0; float weight = 0.0;
            float a = floodTexel(i, j); float b = floodTexel(i + 1, j);
            float c = floodTexel(i, j + 1); float e = floodTexel(i + 1, j + 1);
            float wa = (1.0 - fx) * (1.0 - fy); float wb = fx * (1.0 - fy);
            float wc = (1.0 - fx) * fy; float we = fx * fy;
            if (a > 0.0) { sum += a * wa; weight += wa; }
            if (b > 0.0) { sum += b * wb; weight += wb; }
            if (c > 0.0) { sum += c * wc; weight += wc; }
            if (e > 0.0) { sum += e * we; weight += we; }
            // Only near enough a flooded point.
            return weight >= 0.25 ? sum / weight : 0.0;
        }
        // How far to lift the ground at camera-relative p, [away] from the camera, to the flood's
        // water, and how deep that water is: rising from under the ground beneath the waves, as
        // the far sea does. Its height is taken along the camera's up, as float can't hold the
        // distance from the centre to a centimetre.
        vec2 floodLift(vec3 p, float away) {
            float level = floodLevel(p);
            if (level <= 0.0) return vec2(0.0);
            vec3 up = -normalize(uBodyCentre);
            float along = dot(p, up);
            vec3 across = p - up * along;
            float ground = uFloodGrid.z + along + dot(across, across) / (2.0 * (uFloodGrid.w + uFloodGrid.z + along));
            float water = level + uTide - ground;
            if (water < 0.1) return vec2(0.0);
            float under = uSeaReach > 0.0 ? SEA_BLEND_SINK * (1.0 - smoothstep(uSeaReach * SEA_BLEND, uSeaReach, away)) : 0.0;
            // Gone toward the grid's edge.
            float edge = max(abs(dot(p - uFloodCentre, uFloodEast)), abs(dot(p - uFloodCentre, uFloodNorth))) / (uFloodGrid.x * float(${TsunamiFlood.SIDE} - 1) * 0.5);
            float lift = max(water - under, 0.0) * (1.0 - smoothstep(0.8, 1.0, edge));
            return lift > 0.0 ? vec2(lift, water) : vec2(0.0);
        }
    """

    /**
     * [TSUNAMI_FIELD] and [TSUNAMI_FLOOD] with no tsunami near, for the terrain drawn without one
     * ([TERRAIN_VERTEX_CALM]): the same results as theirs with none set, so the compiler can drop
     * the work and the uniform arrays. On a small GPU (a Mali-G52 MC1) they cost even untaken.
     */
    private val TSUNAMI_NONE = """
        const vec3 uTsunamiGrid = vec3(1.0, 0.0, 0.0);
        const vec4 uFloodGrid = vec4(1.0, 0.0, 0.0, 1.0);
        const float WET_BED = 0.4;
        vec3 tsunamiAt(vec3 p, out vec2 second) {
            second = vec2(0.0, ${NO_CREST});
            return vec3(0.0, 0.0, ${NO_CREST});
        }
        vec3 tsunamiCrest(vec3 t, vec2 second, float depth) { return vec3(${NO_CREST}, 0.0, 0.0); }
        vec2 floodLift(vec3 p, float away) { return vec2(0.0); }
    """

    /** [TERRAIN_VERTEX] and [TERRAIN_FRAGMENT] for frames with no tsunami near (no TsunamiField). */
    val TERRAIN_VERTEX_CALM by lazy { withDefine(TERRAIN_VERTEX, "NO_TSUNAMI") }
    val TERRAIN_FRAGMENT_CALM by lazy { withDefine(TERRAIN_FRAGMENT, "NO_TSUNAMI") }

    /** [TERRAIN_FRAGMENT] for the LOW tier: a tsunami's foam lines sized by depth, not derivatives. */
    val TERRAIN_FRAGMENT_LOW by lazy { withDefine(TERRAIN_FRAGMENT, "CHEAP_SLOWNESS") }

    private fun withDefine(source: String, define: String) = source.replaceFirst("#version 300 es", "#version 300 es\n#define $define")

    val TERRAIN_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec3 aColour;
        layout(location = 3) in float aWet;
        layout(location = 4) in vec3 aCoarsePosition;
        layout(location = 5) in vec3 aCoarseNormal;
        layout(location = 6) in vec3 aCoarseColour;
        layout(location = 7) in float aCoarseWet;
        layout(location = 8) in vec3 aCoarseWater;
        layout(location = 9) in vec3 aCoarseUpperColour;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;
        uniform float uMorph;  // 0 at full detail, 1 on the parent's planes
        // The sea: waves out to uSeaReach metres (0 for none). Beyond that the seabed is lifted to
        // the water (uTide above the datum) and coloured as water.
        uniform float uSeaReach;
        uniform float uTide;
        uniform vec3 uBodyCentre;
        uniform float uMethane;  // 1 for a sea of liquid methane
        $SEA_EDGE
        #ifdef NO_TSUNAMI
        $TSUNAMI_NONE
        #else
        $TSUNAMI_FIELD
        $TSUNAMI_FLOOD
        #endif

        // Flat: each triangle takes one vertex's colour. The normal comes per triangle in the
        // fragment shader.
        flat out vec3 vColour;
        flat out vec3 vCoarseLower;
        flat out vec3 vCoarseUpper;
        out vec2 vGrid;
        flat out vec2 vGridCorner;
        flat out float vWet;
        // Far water: the bed under it, and how much of the water's own colour hides it.
        flat out vec3 vBed;
        flat out float vSee;
        flat out float vDepth;
        flat out float vRise;
        out vec3 vViewDir;
        out float vDistance;
        out vec3 vPosition;
        // Nonzero inside a skirt triangle, plus the ground normal to light it by.
        out float vSkirt;
        out vec3 vGroundNormal;
        // A tsunami's seconds since its crest and whiteness, for its foam (see TSUNAMI_FOAM), and
        // its period.
        out vec2 vTsunami;
        flat out float vTsunamiPeriod;

        void main() {
            vec4 worldPos = uModel * vec4(aPosition, 1.0);
            vec4 coarsePos = uModel * vec4(aCoarsePosition, 1.0);
            vSkirt = aWet >= 3.5 ? 1.0 : 0.0;
            float code = aWet >= 3.5 ? aWet - 4.0 : aWet;
            float coarseCode = aCoarseWet >= 3.5 ? aCoarseWet - 4.0 : aCoarseWet;
            vCoarseLower = aCoarseColour;
            vCoarseUpper = aCoarseUpperColour;
            int vertex = gl_VertexID;
            if (vertex >= ${TerrainChunk.SIDE * TerrainChunk.SIDE}) {
                int edge = (vertex - ${TerrainChunk.SIDE * TerrainChunk.SIDE}) / ${TerrainChunk.SIDE};
                int along = (vertex - ${TerrainChunk.SIDE * TerrainChunk.SIDE}) % ${TerrainChunk.SIDE};
                vertex = edge == 0 ? along : edge == 1 ? ${TerrainChunk.CELLS * TerrainChunk.SIDE} + along :
                    edge == 2 ? along * ${TerrainChunk.SIDE} : along * ${TerrainChunk.SIDE} + ${TerrainChunk.CELLS};
            }
            vGrid = vec2(vertex % ${TerrainChunk.SIDE}, vertex / ${TerrainChunk.SIDE});
            vGridCorner = vGrid;
            vColour = aColour;
            vWet = 0.0;
            vBed = aColour;
            vSee = 1.0;
            vDepth = 0.0;
            vRise = 0.0;
            vTsunami = vec2(${NO_CREST}, 0.0);
            vTsunamiPeriod = 0.0;
            float away = length(worldPos.xyz);
            // A tsunami moves the water, and where it draws back the bed lies bare and wet.
            vec2 second;
            vec3 wave = tsunamiAt(worldPos.xyz, second);
            // Land too keeps the time since the crest, with no white, so it runs on smoothly
            // across a triangle at the coast.
            if (uTsunamiGrid.y > 0.5) {
                vec3 crest = tsunamiCrest(wave, second, max((code - 1.0) * 1000.0, 0.0));
                vTsunami = vec2(crest.x, 0.0);
                vTsunamiPeriod = crest.z;
            }
            if (coarseCode > 0.0 && (uSeaReach <= 0.0 || away > uSeaReach * SEA_RISE)) {
                float rise = uSeaReach > 0.0 ? smoothstep(uSeaReach * SEA_RISE, uSeaReach * SEA_BLEND, away) : 1.0;
                float under = uSeaReach > 0.0 ? SEA_BLEND_SINK * (1.0 - smoothstep(uSeaReach * SEA_BLEND, uSeaReach, away)) : 0.0;
                vec3 up = normalize(coarsePos.xyz - uBodyCentre);
                vec3 coarseWater = (uModel * vec4(aCoarseWater, 1.0)).xyz;
                vec2 coarseSecond;
                float coarseLevel = uTide + tsunamiAt(coarsePos.xyz, coarseSecond).x;
                if (uTsunamiGrid.y < 0.5 || (coarseCode - 1.0) * 1000.0 + coarseLevel > 0.0)
                    coarsePos.xyz = mix(coarsePos.xyz, coarseWater + up * (coarseLevel - under), rise);
            }
            if (code >= 0.5 && uTsunamiGrid.y > 0.5 && (code - 1.0) * 1000.0 + uTide + wave.x <= 0.0) {
                vWet = WET_BED;
            } else if (code >= 0.5 && (uSeaReach <= 0.0 || away > uSeaReach * SEA_RISE)) {
                // Past the waves: flat water at the tide, blue by depth. Under the waves it rises
                // from the bed to a little below them, so no triangle stands from bed to surface
                // where it starts, and there's no line where the waves fade into it.
                float depth = (code - 1.0) * 1000.0;
                vec3 up = normalize(worldPos.xyz - uBodyCentre);
                // (smoothstep's edges can't be equal, so no waves is its own case.)
                bool waves = uSeaReach > 0.0;
                float rise = waves ? smoothstep(uSeaReach * SEA_RISE, uSeaReach * SEA_BLEND, away) : 1.0;
                float under = waves ? SEA_BLEND_SINK * (1.0 - smoothstep(uSeaReach * SEA_BLEND, uSeaReach, away)) : 0.0;
                worldPos.xyz += up * (depth + uTide + wave.x - under) * rise;
                // The waves' own colours by depth (see SeaScene.colour), over the bed as the waves
                // let it show through, so the two meet unseen.
                float shallow = 1.0 - smoothstep(1.0, 14.0, depth);
                float mid = 1.0 - smoothstep(10.0, 60.0, depth);
                vec3 water = vec3(0.03, 0.20, 0.42) + (vec3(0.05, 0.40, 0.60) - vec3(0.03, 0.20, 0.42)) * mid
                    + (vec3(0.14, 0.72, 0.70) - vec3(0.05, 0.40, 0.60)) * shallow;
                // Liquid methane, as SeaScene has it.
                if (uMethane > 0.5) water = vec3(0.06, 0.045, 0.025) + vec3(0.10, 0.07, 0.03) * shallow;
                vColour = water;
                if (uTsunamiGrid.y > 0.5) {
                    vec3 crest = tsunamiCrest(wave, second, depth);
                    vTsunami = crest.xy;
                    vTsunamiPeriod = crest.z;
                }
                vSee = 0.35 + 0.57 * smoothstep(0.5, 20.0, depth);
                vDepth = depth;
                vRise = rise;
                vWet = 1.0;
            } else if (code < 0.5 && uFloodGrid.y > 0.5 && (uSeaReach <= 0.0 || away > uSeaReach * SEA_RISE)) {
                // Low land a tsunami floods, past the waves: muddy water at its level, rising from
                // the ground under the waves as the far sea does.
                vec2 flood = floodLift(worldPos.xyz, away);
                if (flood.x > 0.0) {
                    worldPos.xyz += normalize(worldPos.xyz - uBodyCentre) * flood.x;
                    float mud = smoothstep(0.0, 0.6, flood.y);
                    vColour = mix(vec3(0.14, 0.72, 0.70), vec3(0.36, 0.32, 0.24), mud);
                    vSee = max(0.35 + 0.57 * smoothstep(0.5, 20.0, flood.y), 0.85 * mud);
                    vDepth = flood.y;
                    vRise = uSeaReach > 0.0 ? smoothstep(uSeaReach * SEA_RISE, uSeaReach * SEA_BLEND, away) : 1.0;
                    vWet = 1.0;
                }
            }
            // The parent's ground under a flood too, so it doesn't jump as the detail changes.
            if (coarseCode < 0.5 && uFloodGrid.y > 0.5 && (uSeaReach <= 0.0 || away > uSeaReach * SEA_RISE)) {
                vec2 flood = floodLift(coarsePos.xyz, away);
                coarsePos.xyz += normalize(coarsePos.xyz - uBodyCentre) * flood.x;
            }
            worldPos.xyz = mix(worldPos.xyz, coarsePos.xyz, uMorph);
            vPosition = worldPos.xyz;
            vGroundNormal = mat3(uModel) * mix(aNormal, aCoarseNormal, uMorph);
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
        flat in vec3 vCoarseLower;
        flat in vec3 vCoarseUpper;
        in vec2 vGrid;
        flat in vec2 vGridCorner;
        flat in float vWet;
        flat in vec3 vBed;
        flat in float vSee;
        flat in float vDepth;
        flat in float vRise;
        in vec3 vViewDir;
        in float vDistance;
        in vec3 vPosition;
        in float vSkirt;
        in vec3 vGroundNormal;
        in vec2 vTsunami;
        flat in float vTsunamiPeriod;

        uniform vec3 uSunDirection;
        uniform float uMorph;
        uniform float uAtmosphereFactor;
        uniform float uHazeDistance;
        uniform float uHasAtmosphere; // 1 for a body with air, 0 for one without
        uniform float uDiscardNearer; // the globe leaves the chunks' ground alone
        uniform float uSkyBody;       // 1 for another world seen across space
        uniform vec3 uFarRim;         // and the colour of its air round its edge
        uniform vec4 uFarDust;        // and a world-wide dust storm over it: colour, how much
        uniform vec3 uBodyCentre;     // the planet's centre, camera-relative
        uniform float uDaylight;      // how much sun reaches the camera
        uniform vec3 uSeaSky;            // the sky a sea reflects
        uniform float uLightScale;    // sunlight left under a storm
        uniform float uFlatLight;     // 0..1: a whiteout's light from all round
        uniform float uFogDistance;   // weather fog: cloud, rain
        uniform vec3 uFogColor;
        $DUST_FOG

        out vec4 fragColor;

        $NIGHT_LIGHT
        $SHADOW
        $TSUNAMI_FOAM

        void main() {
            vec3 viewDir = normalize(-vPosition);
            if (vDistance < uDiscardNearer) discard;
            #ifndef NO_TSUNAMI
            // Out of any branch, so the derivatives are good.
            float tsunamiBlur = fwidth(vTsunami.x);
            #ifdef CHEAP_SLOWNESS
            // The crest's seconds per metre over water this deep, as shallow-water waves go,
            // rather than from derivatives: near enough, and much cheaper on a small GPU.
            float tsunamiSlow = inversesqrt(9.81 * max(vDepth, 1.0));
            #else
            float tsunamiSlow = tsunamiSlowness(vTsunami.x, vPosition);
            #endif
            #endif
            // The triangle's own face normal, from screen derivatives. Vertex normals shimmer on
            // rough ground as the detail changes.
            vec3 n = normalize(cross(dFdx(vPosition), dFdy(vPosition)));
            if (dot(n, viewDir) < 0.0) n = -n;
            // A skirt hides a crack, so light it like the ground above it.
            if (vSkirt > 0.001) n = normalize(vGroundNormal);
            // Far water and its crack-hiding skirts share one continuous sea normal.
            if (vWet > 0.5) n = normalize(vPosition - uBodyCentre);
            vec3 surface = vColour;
            float wet = vWet;
            vec2 fromCorner = vGrid - vGridCorner;
            vec3 coarseColour = fromCorner.x - fromCorner.y >= 0.0 ? vCoarseLower : vCoarseUpper;
            if (wet < 0.5) surface = mix(surface, coarseColour, uMorph);
            // Sea floor the water has left: darker, and glinting.
            if (wet > 0.2 && wet < 0.5) surface *= 0.62;

            if (uSkyBody > 0.5) {
                // Another world across space: sunlit only, night side nearly black, a glint off its
                // seas, its air glowing round the edge, washed a little toward a day sky.
                vec3 rimUp = normalize(vPosition - uBodyCentre);
                float facing = dot(n, uSunDirection);
                // A world-wide dust storm hides its ground and seas under a lit veil.
                vec3 seen = mix(surface, uFarDust.rgb, uFarDust.a);
                float glint = wet * (1.0 - uFarDust.a);
                float veiled = max(dot(mix(n, rimUp, uFarDust.a), uSunDirection), 0.0);
                vec3 world = seen * (0.03 + 1.1 * veiled);
                vec3 half0 = normalize(uSunDirection + viewDir);
                world += vec3(1.0, 0.96, 0.88) * pow(max(dot(n, half0), 0.0), 60.0) * glint * 0.6 * step(0.0, facing);
                float rim = pow(1.0 - max(dot(rimUp, viewDir), 0.0), 3.0);
                world += uFarRim * rim * smoothstep(-0.1, 0.3, dot(rimUp, uSunDirection)) * 0.9 * uHasAtmosphere;
                fragColor = vec4(mix(world, uHaze, 0.35 * uAtmosphereFactor * uDaylight), 1.0);
                return;
            }

            float direct = directLight(vPosition, n);
            float lambert = max(dot(n, uSunDirection), 0.0) * direct;
            float daylight = smoothstep(-0.08, 0.35, dot(n, uSunDirection));
            // In a whiteout the light comes from all round, as bright on every slope.
            lambert = mix(lambert, 0.8 * uDaylight, uFlatLight);
            daylight = mix(daylight, uDaylight, uFlatLight);
            // A faint blue full moon keeps the land's shape after dark.
            vec3 night = MOON * (0.55 + 0.45 * max(-dot(n, uSunDirection), 0.0) * direct) * (0.4 + 0.6 * uLightScale);
            vec3 sea = underSea(vPosition);
            vec3 lit = surface * (night * moonLeft(uDaylight) + duskGlow(uDaylight) + (0.06 + lambert * 1.10 * uLightScale) * daylight) * sea;
            lit += surface * FLASH * uFlash * sea;
            if (uLampCount > 0) lit += surface * lampLight(vPosition, n);

            vec3 halfway = normalize(uSunDirection + viewDir);
            if (wet > 0.5) {
                // A tsunami's foam, per pixel, so its edges are smooth.
                #ifdef NO_TSUNAMI
                float foam = 0.0;
                #else
                float foam = tsunamiFoam(vTsunami.x, vTsunami.y, vTsunamiPeriod, tsunamiBlur, tsunamiSlow);
                #endif
                surface = mix(surface, TSUNAMI_WHITE, foam);
                // Flat water past the waves, lit the way the waves are (see SEA_FRAGMENT), so there's
                // no ring where one meets the other.
                // The bed lit like ground and darkened by the water over it, as the bed under the
                // waves is (underSea), under water lit like water and as clear as the waves.
                vec3 bed = mix(vBed, coarseColour, uMorph) * (night * moonLeft(uDaylight) + duskGlow(uDaylight) + (0.06 + lambert * 1.10 * uLightScale) * daylight)
                    * exp(-vDepth / uWater);
                float shade = 0.22 + 1.25 * pow(lambert, 0.8) * uLightScale;
                // Foam is white whichever way it's turned, as on the waves.
                shade = mix(shade, (0.85 + 0.3 * lambert) * uLightScale, foam);
                vec3 water = surface * (night * moonLeft(uDaylight) + duskGlow(uDaylight) + shade * daylight) + surface * FLASH * uFlash;
                float facing = max(dot(n, viewDir), 0.0);
                float reflected = 0.03 + 0.97 * pow(1.0 - facing, 5.0);
                vec3 sky = mix(uHaze, uSeaSky, 0.5) * (NIGHT_AIR + (1.0 - NIGHT_AIR) * daylight) * (0.5 + 0.5 * uLightScale);
                water = mix(water, sky, reflected * 0.5 * (1.0 - foam));
                // Still rising under the waves, it's more bed than water.
                lit = mix(bed, water, mix(max(vSee, foam), 1.0, reflected) * vRise);
                // The waves scatter the sun into sparkle. Flat, it's a broad dim sheen instead, about
                // as bright on the whole.
                lit += vec3(1.0, 0.96, 0.88) * pow(max(dot(n, halfway), 0.0), 40.0) * daylight * direct * 0.35 * uLightScale * vRise;
            } else {
                // A glint off wet ground.
                float glint = pow(max(dot(n, halfway), 0.0), 90.0);
                lit += vec3(1.0, 0.96, 0.88) * glint * daylight * wet * 0.8 * direct;
            }

            // Aerial perspective: distant ground fades into the air.
            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            vec3 hazeColor = airHaze(daylight, uLightScale);
            lit = mix(lit, hazeColor, clamp(haze, 0.0, 1.0));

            // Seen from outside, a planet's edge glows. Fades out as the camera comes down, and none
            // without air. Taken against the planet's curve, not the facet, or edge-on mountain
            // faces flicker blue.
            vec3 up = normalize(vPosition - uBodyCentre);
            float fresnel = pow(1.0 - max(dot(up, viewDir), 0.0), 3.0);
            lit += vec3(0.25, 0.45, 0.78) * fresnel * daylight * 0.9 *
                (1.0 - clamp(uAtmosphereFactor, 0.0, 1.0)) * uHasAtmosphere;

            float fog = weatherFog(vPosition, vDistance);
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
        layout(location = 2) in vec4 aColour;
        layout(location = 3) in float aRise;
        layout(location = 4) in vec2 aTsunami;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;
        uniform float uAhead;   // seconds since the surface was built for
        // Up at the surface's origin, body-fixed: near enough up anywhere on it to carry it by its rise.
        uniform vec3 uUp;
        // The surface's origin, body-fixed, in metres modulo 4096, for the foam's froth.
        uniform vec3 uFrothOrigin;

        flat out vec4 vColour;
        out vec3 vPosition;
        out float vDistance;
        out vec3 vViewDir;
        // A tsunami's seconds since its crest, carried on to now, and whiteness (see TSUNAMI_FOAM).
        out vec2 vTsunami;
        // Its period, packed with the whiteness (SeaScene.packTsunami): 4 x period + whiteness.
        flat out float vTsunamiPeriod;
        out vec3 vFroth;

        void main() {
            vec3 p = aPosition + uUp * (aRise * uAhead);
            float period = floor((aTsunami.y + 2.0) * 0.25);
            vTsunami = vec2(aTsunami.x + uAhead, aTsunami.y - 4.0 * period);
            vTsunamiPeriod = period;
            vFroth = aPosition + uFrothOrigin;
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
        in vec2 vTsunami;
        flat in float vTsunamiPeriod;
        in vec3 vFroth;

        uniform vec3 uSunDirection;
        uniform float uAtmosphereFactor;
        uniform float uHazeDistance;
        uniform vec3 uBodyCentre;
        uniform float uDaylight;
        uniform float uLightScale;
        uniform float uFogDistance;
        uniform vec3 uFogColor;
        $DUST_FOG
        uniform float uSeaReach;
        $SEA_EDGE
        uniform float uUnderwater;
        uniform vec3 uSeaSky;            // the sky a sea reflects

        out vec4 fragColor;

        $NIGHT_LIGHT
        $SHADOW
        $TSUNAMI_FOAM

        void main() {
            if (vDistance > uSeaReach) discard;
            // Out of any branch, so the derivatives are good.
            float tsunamiBlur = fwidth(vTsunami.x);
            float tsunamiSlow = tsunamiSlowness(vTsunami.x, vPosition);
            vec3 n = normalize(cross(dFdx(vPosition), dFdy(vPosition)));
            vec3 up = normalize(vPosition - uBodyCentre);
            if (dot(n, up) < 0.0) n = -n;
            bool below = uUnderwater > 0.5;
            float daylight = smoothstep(-0.08, 0.35, dot(up, uSunDirection));

            if (below) {
                // From below: a bright ceiling lit by the sky.
                float through = 0.35 + 0.65 * max(dot(-n, -up), 0.0);
                vec3 lit = vec3(0.30, 0.68, 0.72) * through * (0.15 + 0.85 * daylight * uLightScale);
                float fog = weatherFog(vPosition, vDistance);
                fragColor = vec4(mix(lit, uFogColor, clamp(fog, 0.0, 1.0)), 0.85);
                return;
            }

            vec3 surface = vColour.rgb;
            // A tsunami's foam, per pixel so its edges are smooth, frothy up close: the time it
            // goes by is nudged by a little noise on the ground, gone before it would shimmer.
            float tsunami = 0.0;
            if (abs(vTsunami.y) > 0.01) {
                vec3 q = vFroth * ${FROTH_RATE};
                float froth = sin(q.x * 640.0 + 2.0 * sin(q.z * 300.0)) + sin(q.y * 560.0 + 2.0 * sin(q.x * 410.0))
                    + sin(q.z * 760.0 + 2.0 * sin(q.y * 350.0));
                froth *= 0.4 * (1.0 - smoothstep(150.0, 600.0, vDistance));
                tsunami = tsunamiFoam(vTsunami.x + froth, vTsunami.y, vTsunamiPeriod, tsunamiBlur, tsunamiSlow);
                surface = mix(surface, TSUNAMI_WHITE, tsunami);
            }
            float direct = directLight(vPosition, n);
            float lambert = max(dot(n, uSunDirection), 0.0) * direct;
            vec3 night = MOON * (0.55 + 0.45 * max(-dot(n, uSunDirection), 0.0) * direct) * (0.4 + 0.6 * uLightScale);
            // Bright to the sun, dark away; harder than on land since the slopes are gentler.
            float shade = 0.22 + 1.25 * pow(lambert, 0.8) * uLightScale;
            // Foam is white whichever way it's turned. Nothing but foam is this white.
            float foam = max(smoothstep(0.86, 0.9, min(min(surface.r, surface.g), surface.b)), tsunami);
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
            float facingSun = max(dot(n, halfway), 0.0);
            // Sparkle near, and further out the flat water's broad sheen (TERRAIN_FRAGMENT), which
            // it has become by the time it fades into it.
            float sheen = smoothstep(uSeaReach * 0.3, uSeaReach * SEA_BLEND, vDistance);
            float glint = mix(pow(facingSun, 140.0) * 1.6 * (1.0 - foam), pow(facingSun, 40.0) * 0.35, sheen);
            lit += vec3(1.0, 0.96, 0.88) * glint * daylight * direct * uLightScale;

            float haze = (1.0 - exp(-vDistance / max(uHazeDistance, 1.0))) * uAtmosphereFactor;
            lit = mix(lit, airHaze(daylight, uLightScale), clamp(haze, 0.0, 1.0));
            float fog = weatherFog(vPosition, vDistance);
            lit = mix(lit, uFogColor, clamp(fog, 0.0, 1.0));

            // Clear in the shallows, glassy at low angles, fading into the flat water at the edge.
            float alpha = mix(max(vColour.a, tsunami), 1.0, fresnel) * (1.0 - smoothstep(uSeaReach * SEA_BLEND, uSeaReach, vDistance));
            fragColor = vec4(lit, alpha);
        }
    """.trimIndent()

    // ---- hurricanes from high up: a disc of cloud each -----------------------

    val HURRICANE_VEIL_VERTEX = """
        #version 300 es
        layout(location = 0) in vec3 aPosition;
        layout(location = 1) in vec4 aColour;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;

        out vec4 vColour;
        out vec3 vUp;
        out vec3 vBody;
        // From the camera, in metres.
        out vec3 vRelative;

        void main() {
            vColour = aColour;
            vUp = mat3(uModel) * aPosition;
            vBody = aPosition;
            vec4 relative = uModel * vec4(aPosition, 1.0);
            vRelative = relative.xyz;
            gl_Position = uViewProjection * relative;
        }
    """.trimIndent()

    /**
     * Lit like the map's veil, but solid where the vertices say, with a little texture. Drawn only
     * between [uFrom] and [uTo] metres from the camera, so a near and a far pass can share it, and
     * not where the world's ball (centre [uCentre] from the camera, radius [uRadius]) is in front.
     */
    val HURRICANE_VEIL_FRAGMENT = """
        #version 300 es
        precision highp float;

        in vec4 vColour;
        in vec3 vUp;
        in vec3 vBody;
        in vec3 vRelative;

        uniform vec3 uSunDirection;
        uniform float uNoiseScale;
        uniform float uDrift;
        uniform float uAlpha;
        uniform float uFrom;
        uniform float uTo;
        uniform vec3 uCentre;
        uniform float uRadius;

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
            float away = length(vRelative);
            if (away < uFrom || away >= uTo) discard;
            // Behind the world: the sight line passes inside the ball before it gets here.
            vec3 along = vRelative / away;
            float nearest = dot(uCentre, along);
            if (nearest > 0.0 && nearest < away && dot(uCentre, uCentre) - nearest * nearest < uRadius * uRadius) discard;
            vec3 p = normalize(vBody) * uNoiseScale + vec3(uDrift, 0.0, -uDrift);
            float n = 0.5 * noise(p) + 0.3 * noise(p * 2.3 + 7.1) + 0.2 * noise(p * 5.1 - 3.7);
            float m = clamp((n - 0.5) * 2.4 + 0.5, 0.0, 1.0);
            // Thin cloud breaks up; thick cloud stays whole.
            float alpha = clamp(vColour.a * (0.7 + 0.6 * m) - 0.12 * (1.0 - m), 0.0, 0.95) * uAlpha;
            vec3 colour = vColour.rgb * (0.86 + 0.14 * m);
            vec3 up = normalize(vUp);
            // Slopes facing the sun brighter than level cloud, those facing away in shadow.
            vec3 normal = normalize(cross(dFdx(vRelative), dFdy(vRelative)));
            if (dot(normal, up) < 0.0) normal = -normal;
            float slope = dot(normal, uSunDirection) - dot(up, uSunDirection);
            colour *= clamp(1.0 + 1.5 * slope, 0.3, 1.35);
            float day = smoothstep(-0.12, 0.3, dot(up, uSunDirection));
            fragColor = vec4(colour * (0.05 + 0.95 * day), alpha * (0.3 + 0.7 * day));
        }
    """.trimIndent()

    /**
     * Dust storms' veil, with [HURRICANE_VEIL_VERTEX]: an even, soft tint over the ground with only
     * gentle broad variation, thicker toward the limb where the sight line runs through more of it.
     */
    val DUST_VEIL_FRAGMENT = """
        #version 300 es
        precision highp float;

        in vec4 vColour;
        in vec3 vUp;
        in vec3 vBody;
        in vec3 vRelative;

        uniform vec3 uSunDirection;
        uniform float uDrift;
        uniform float uAlpha;
        uniform float uFrom;
        uniform float uTo;
        uniform vec3 uCentre;
        uniform float uRadius;

        out vec4 fragColor;

        void main() {
            float away = length(vRelative);
            if (away < uFrom || away >= uTo) discard;
            vec3 along = vRelative / away;
            float nearest = dot(uCentre, along);
            if (nearest > 0.0 && nearest < away && dot(uCentre, uCentre) - nearest * nearest < uRadius * uRadius) discard;
            vec3 up = normalize(vUp);
            vec3 b = normalize(vBody);
            // Broad, slow swirls only: a few waves round the world.
            float n = 0.5 + 0.25 * sin(3.0 * b.x + 2.0 * b.z + uDrift * 0.7) * sin(2.0 * b.y - 3.0 * b.z + uDrift * 0.5)
                + 0.12 * sin(7.0 * b.y + 5.0 * b.x - uDrift);
            // Thicker toward the limb.
            float slant = abs(dot(along, up));
            float limb = 1.0 + 0.6 * (1.0 - smoothstep(0.05, 0.6, slant));
            float alpha = clamp(vColour.a * (0.85 + 0.3 * n) * limb, 0.0, 0.97) * uAlpha;
            vec3 colour = vColour.rgb * (0.9 + 0.12 * n);
            float day = smoothstep(-0.12, 0.3, dot(up, uSunDirection));
            float lit = 0.35 + 0.65 * clamp(dot(up, uSunDirection), 0.0, 1.0);
            fragColor = vec4(colour * (0.05 + 0.95 * day) * lit, alpha * (0.4 + 0.6 * day));
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
        layout(location = 1) in vec3 aOther;
        layout(location = 2) in float aSide;

        uniform mat4 uViewProjection;
        uniform mat4 uModel;
        // Half the screen's width and height in pixels, then half the ribbon's width in pixels.
        uniform vec4 uScreen;

        out float vSide;

        void main() {
            vec4 here = uViewProjection * uModel * vec4(aPosition, 1.0);
            vec4 there = uViewProjection * uModel * vec4(aOther, 1.0);
            vSide = aSide;
            // Behind the camera: dropped.
            if (here.w <= 1e-6 || there.w <= 1e-6) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
            vec2 halfScreen = uScreen.xy;
            vec2 a = here.xy / here.w * halfScreen;
            vec2 b = there.xy / there.w * halfScreen;
            vec2 along = b - a;
            float span = max(1e-6, sqrt(dot(along, along)));
            vec2 across = vec2(-along.y, along.x) / span;
            // Out to the side in pixels, back into clip space at this end's depth.
            here.xy += across * aSide * uScreen.z / halfScreen * here.w;
            gl_Position = here;
        }
    """.trimIndent()

    val LINE_FRAGMENT = """
        #version 300 es
        precision mediump float;
        uniform vec4 uColor;
        in float vSide;
        out vec4 fragColor;
        void main() {
            // Soft edges, a pixel or so wide.
            float edge = 1.0 - smoothstep(0.55, 1.0, abs(vSide));
            fragColor = vec4(uColor.rgb, uColor.a * edge);
        }
    """.trimIndent()
}
