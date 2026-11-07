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
