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
