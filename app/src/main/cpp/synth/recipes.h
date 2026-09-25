// The sounds the game can make, by number. The Kotlin side has the same list
// in audio/Recipes.kt; the two must agree, and a test checks they do.
#pragma once

namespace apogee {

namespace recipe {
// Continuous: held for as long as the scene asks for them.
constexpr int ROCKET = 1;      // p0 output, p1 size, p2 air pressure, p3 crackle
constexpr int JET = 2;         // p0 output, p1 spool, p2 airspeed
constexpr int PROP = 3;        // p0 output, p1 blade rate Hz, p2 load
constexpr int AIRFLOW = 4;     // p0 loudness, p1 speed, p2 buffet
constexpr int REENTRY = 5;     // p0 loudness, p1 intensity
constexpr int WIND = 6;        // p0 loudness, p1 gustiness, p2 brightness
constexpr int RAIN = 7;        // p0 loudness, p1 patter on the hull
constexpr int CABIN = 8;       // p0 loudness, p1 hull settling, p2 hum
constexpr int STRESS = 9;      // p0 how near the limit (0..1)
constexpr int FIRE = 10;       // p0 loudness
constexpr int ROVER = 11;      // p0 motor load, p1 wheel speed m/s, p2 surface grit, p3 skid
constexpr int OUTBOARD = 12;   // p0 output, p1 revs
constexpr int SURF = 13;       // p0 loudness
constexpr int RCS = 14;        // p0 how many firing
constexpr int LAST_CONTINUOUS = 49;

// One-shots: fired once, then they ring out.
constexpr int IMPACT = 50;     // p0 energy, p1 material, p2 size
constexpr int CRUNCH = 51;     // p0 energy
constexpr int TEAR = 52;       // p0 energy
constexpr int EXPLOSION = 53;  // p0 size
constexpr int SPLASH = 54;     // p0 energy
constexpr int STAGE = 55;      // p0 size
constexpr int THUNDER = 56;    // p0 closeness
constexpr int CLICK = 57;      // p0 kind
constexpr int CAUTION = 58;    // p0 kind
constexpr int IGNITION = 59;   // p0 size
constexpr int CHUTE = 60;      // p0 size
constexpr int CLUNK = 61;      // p0 size
}  // namespace recipe

/** Surfaces an impact can be on, for its character. */
namespace material {
constexpr int METAL = 0;
constexpr int ROCK = 1;
constexpr int EARTH = 2;
constexpr int SAND = 3;
constexpr int SNOW = 4;
constexpr int WOOD = 5;
}  // namespace material

/** What mix bus a recipe plays through. */
namespace bus {
constexpr int VEHICLE = 0;
constexpr int ENVIRONMENT = 1;
constexpr int IMPACTS = 2;
constexpr int UI = 3;
constexpr int MUSIC = 4;
constexpr int COUNT = 5;
}  // namespace bus

inline int busOf(int r) {
    switch (r) {
        case recipe::WIND: case recipe::RAIN: case recipe::SURF: case recipe::THUNDER: case recipe::FIRE:
            return bus::ENVIRONMENT;
        case recipe::IMPACT: case recipe::CRUNCH: case recipe::TEAR: case recipe::EXPLOSION: case recipe::SPLASH:
            return bus::IMPACTS;
        case recipe::CLICK: case recipe::CAUTION:
            return bus::UI;
        default:
            return bus::VEHICLE;
    }
}

/** Voice flags. */
namespace flag {
/** Heard through the craft's own structure: low, close, muffled - how sound reaches you in vacuum. */
constexpr int HULL = 1;
}  // namespace flag

}  // namespace apogee
