// Renders every sound the game makes to WAV files, off the device, through
// the same synth the game uses - for tuning by ear and by the numbers.
//
//   ./gradlew :app:soundGallery    ->   app/build/sound-gallery/*.wav
//
// Each file is a little scene: a held sound swept through its range, or a
// one-shot at a few strengths. A line per file gives its peak and RMS.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

#include "../synth/synth.h"

using namespace apogee;

namespace {

constexpr float kRate = 48000.0f;
constexpr int kBlock = 480;  // 10 ms: how often a scene is updated, as a frame would

void writeWav(const std::string& path, const std::vector<float>& stereo) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) { std::perror(path.c_str()); return; }
    auto u32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto u16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    uint32_t dataBytes = static_cast<uint32_t>(stereo.size() * 2);
    std::fwrite("RIFF", 1, 4, f); u32(36 + dataBytes);
    std::fwrite("WAVEfmt ", 1, 8, f); u32(16); u16(1); u16(2); u32(48000); u32(48000 * 4); u16(4); u16(16);
    std::fwrite("data", 1, 4, f); u32(dataBytes);
    for (float s : stereo) {
        int v = static_cast<int>(std::lround(clampf(s, -1.0f, 1.0f) * 32767.0f));
        u16(static_cast<uint16_t>(static_cast<int16_t>(v)));
    }
    std::fclose(f);
}

/** A held sound over [seconds], its parameters set each block by [shape](t 0..1, params). */
struct Held {
    int recipe;
    int flags = 0;
    std::function<void(float, float*)> shape;
};

/** A one-shot at [time] seconds. */
struct Shot {
    float time;
    int recipe;
    std::vector<float> p;
    int flags = 0;
};

bool raw = false;

void render(const std::string& dir, const std::string& name, float seconds, std::vector<Held> held,
            std::vector<Shot> shots = {}, float room = 0.15f) {
    Synth synth(kRate, 48);
    synth.setRoom(room);
    synth.limiting = !raw;
    std::vector<float> out(static_cast<size_t>(seconds * kRate) * 2, 0.0f);
    int frames = static_cast<int>(seconds * kRate);
    size_t nextShot = 0;
    for (int at = 0; at < frames; at += kBlock) {
        float t = static_cast<float>(at) / frames;
        Scene scene;
        scene.count = static_cast<int>(held.size());
        for (size_t i = 0; i < held.size(); ++i) {
            SceneEntry& e = scene.entries[i];
            e.key = static_cast<int>(i + 1);
            e.recipe = held[i].recipe;
            e.flags = held[i].flags;
            std::memset(e.p, 0, sizeof e.p);
            held[i].shape(t, e.p);
        }
        synth.publishScene(scene);
        while (nextShot < shots.size() && shots[nextShot].time * kRate <= at) {
            Event e;
            e.recipe = shots[nextShot].recipe;
            e.flags = shots[nextShot].flags;
            e.seed = static_cast<uint32_t>(nextShot * 7919 + 17);
            for (size_t j = 0; j < shots[nextShot].p.size() && j < kParams; ++j) e.p[j] = shots[nextShot].p[j];
            synth.pushEvent(e);
            ++nextShot;
        }
        int n = std::min(kBlock, frames - at);
        synth.render(out.data() + static_cast<size_t>(at) * 2, n);
    }
    double sum = 0; float peak = 0;
    for (float s : out) { sum += s * s; peak = std::max(peak, std::fabs(s)); }
    double rms = std::sqrt(sum / std::max<size_t>(1, out.size()));
    std::printf("%-22s peak %6.1f dBFS   rms %6.1f dBFS\n", name.c_str(),
                20.0 * std::log10(std::max(1e-9f, peak)), 20.0 * std::log10(std::max(1e-12, rms)));
    writeWav(dir + "/" + name + ".wav", out);
}

float ramp(float t) { return t < 0.5f ? t * 2 : (1 - t) * 2; }

}  // namespace

int main(int argc, char** argv) {
    std::string dir = argc > 1 ? argv[1] : ".";
    raw = argc > 2 && std::string(argv[2]) == "raw";

    // Vehicles.
    render(dir, "rocket-throttle", 8, {{recipe::ROCKET, 0, [](float t, float* p) { p[0] = ramp(t); p[1] = 0.8f; p[2] = 1; p[3] = 0.5f; }}},
           {{0.0f, recipe::IGNITION, {0.8f}}});
    render(dir, "rocket-climbing", 8, {{recipe::ROCKET, 0, [](float t, float* p) { p[0] = 1; p[1] = 0.8f; p[2] = 1 - t; p[3] = 0.5f; }}});
    render(dir, "rocket-hull", 6, {{recipe::ROCKET, flag::HULL, [](float, float* p) { p[0] = 1; p[1] = 0.8f; p[2] = 0; }}}, {}, 0.5f);
    render(dir, "rocket-small", 5, {{recipe::ROCKET, 0, [](float, float* p) { p[0] = 1; p[1] = 0.1f; p[2] = 1; p[3] = 0.2f; }}});
    render(dir, "jet-spool", 8, {{recipe::JET, 0, [](float t, float* p) { p[0] = 0.4f + 0.6f * ramp(t); p[1] = ramp(t); p[2] = t; }}});
    render(dir, "prop", 6, {{recipe::PROP, 0, [](float t, float* p) { p[0] = 0.8f; p[1] = 40 + 60 * ramp(t); p[2] = ramp(t); }}});
    render(dir, "rover-gravel", 6, {{recipe::ROVER, 0, [](float t, float* p) { p[0] = 0.6f; p[1] = 12 * ramp(t); p[2] = 0.8f; }}});
    render(dir, "rover-skid", 4, {{recipe::ROVER, 0, [](float t, float* p) { p[0] = 0.8f; p[1] = 10; p[2] = 0.2f; p[3] = t > 0.4f ? 1 : 0; }}});
    render(dir, "outboard", 6, {{recipe::OUTBOARD, 0, [](float t, float* p) { p[0] = 0.8f; p[1] = ramp(t); }}});
    render(dir, "rcs", 3, {{recipe::RCS, 0, [](float t, float* p) { p[0] = (static_cast<int>(t * 12) % 3 == 0) ? 1.0f : 0.0f; }}});

    // Air and strain.
    render(dir, "airflow", 8, {{recipe::AIRFLOW, 0, [](float t, float* p) { p[0] = ramp(t); p[1] = ramp(t); p[2] = std::max(0.0f, ramp(t) - 0.6f) * 2.5f; }}});
    render(dir, "reentry", 8, {{recipe::REENTRY, 0, [](float t, float* p) { p[0] = ramp(t); p[1] = ramp(t); }}});
    render(dir, "stress", 10, {{recipe::STRESS, 0, [](float t, float* p) { p[0] = t; }}});

    // Places and weather.
    render(dir, "wind", 12, {{recipe::WIND, 0, [](float t, float* p) { p[0] = 0.2f + 0.8f * ramp(t); p[1] = 0.8f; p[2] = ramp(t); }}});
    render(dir, "rain", 6, {{recipe::RAIN, 0, [](float t, float* p) { p[0] = 0.7f; p[1] = t; }}});
    render(dir, "surf", 16, {{recipe::SURF, 0, [](float, float* p) { p[0] = 0.8f; }}});
    render(dir, "fire", 6, {{recipe::FIRE, 0, [](float, float* p) { p[0] = 0.8f; }}});
    render(dir, "cabin", 10, {{recipe::CABIN, flag::HULL, [](float, float* p) { p[0] = 0.8f; }}}, {}, 0.5f);
    render(dir, "thunder", 10, {}, {{0.2f, recipe::THUNDER, {1.0f}}, {4.5f, recipe::THUNDER, {0.2f}}});

    // Crashes.
    render(dir, "impact-metal", 6, {}, {{0.1f, recipe::IMPACT, {0.2f, material::METAL, 0.3f}}, {2.0f, recipe::IMPACT, {1.0f, material::METAL, 0.6f}}, {4.0f, recipe::IMPACT, {1.8f, material::METAL, 0.9f}}});
    render(dir, "impact-rock", 4, {}, {{0.1f, recipe::IMPACT, {0.3f, material::ROCK, 0.5f}}, {1.8f, recipe::IMPACT, {1.5f, material::ROCK, 0.8f}}});
    render(dir, "impact-earth", 4, {}, {{0.1f, recipe::IMPACT, {0.3f, material::EARTH, 0.5f}}, {1.8f, recipe::IMPACT, {1.5f, material::EARTH, 0.8f}}});
    render(dir, "impact-sand-snow", 4, {}, {{0.1f, recipe::IMPACT, {1.0f, material::SAND, 0.6f}}, {1.8f, recipe::IMPACT, {1.0f, material::SNOW, 0.6f}}});
    render(dir, "crunch", 3, {}, {{0.1f, recipe::CRUNCH, {0.5f}}, {1.5f, recipe::CRUNCH, {1.5f}}});
    render(dir, "tear", 3, {}, {{0.1f, recipe::TEAR, {1.0f}}});
    render(dir, "explosion-near", 8, {}, {{0.1f, recipe::EXPLOSION, {1.0f}}});
    render(dir, "explosion-far", 8, {}, {{0.1f, recipe::EXPLOSION, {0.8f, 0, 0, 0, 0, 0, 0, 400.0f}}});
    render(dir, "splash", 4, {}, {{0.1f, recipe::SPLASH, {0.4f}}, {1.8f, recipe::SPLASH, {1.5f}}});

    // Parts and interface.
    render(dir, "stage", 3, {}, {{0.1f, recipe::STAGE, {1.0f}}});
    render(dir, "chute", 3, {}, {{0.1f, recipe::CHUTE, {1.0f}}});
    render(dir, "clunk", 2, {}, {{0.1f, recipe::CLUNK, {1.0f}}, {0.9f, recipe::CLUNK, {0.4f}}});
    render(dir, "click", 1, {}, {{0.1f, recipe::CLICK, {0.0f}}, {0.5f, recipe::CLICK, {1.0f}}});
    render(dir, "caution", 1, {}, {{0.1f, recipe::CAUTION, {0.0f}}});
    return 0;
}
