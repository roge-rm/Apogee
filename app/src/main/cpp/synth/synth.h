// The whole sound engine short of the device: voices, mixer and room.
//
// Two threads meet here and never lock. The game thread, once a frame,
// publishes the scene - every held sound, with its parameters - through a
// triple buffer, and pushes one-shots into a single-producer ring. The audio
// thread takes the newest scene when it next renders, eases each voice toward
// it, starts the one-shots, and mixes. Nothing on the audio thread allocates.
#pragma once

#include <atomic>
#include <cstdint>
#include <vector>

#include "dsp.h"
#include "recipes.h"

namespace apogee {

constexpr int kParams = 9;
constexpr int kMaxVoices = 64;
constexpr int kMaxSceneEntries = 64;
constexpr int kEventSlots = 128;

/**
 * Parameters every voice shares after its recipe's own: loudness (distance,
 * the mix; 0 means 1), pan, the distance filter's cutoff (0 for none), and
 * the Doppler pitch.
 */
constexpr int P_GAIN = 5;
constexpr int P_PAN = 6;
constexpr int P_LOWPASS = 7;
/** Doppler: frequency factor for a source moving relative to the listener (0 means 1). Engines and wheels only. */
constexpr int P_PITCH = 8;

/** One held sound, as the game describes it. */
struct SceneEntry {
    int32_t key = 0;
    int32_t recipe = 0;
    int32_t flags = 0;
    float p[kParams] = {};
};

struct Scene {
    int count = 0;
    SceneEntry entries[kMaxSceneEntries];
};

/** A one-shot, as the game describes it; [delay] seconds before it starts. */
struct Event {
    int32_t recipe = 0;
    int32_t flags = 0;
    uint32_t seed = 0;
    float delay = 0;
    float p[kParams] = {};
};

/** One voice: whatever DSP any recipe needs, so the pool is one fixed size. */
struct Voice {
    bool active = false;
    bool held = false;       // continuous, and still in the scene
    int32_t key = 0;
    int32_t recipe = 0;
    int32_t flags = 0;
    float target[kParams] = {};
    Smooth param[kParams];
    Smooth fade;             // in when started, out when dropped
    int delaySamples = 0;
    float age = 0;           // seconds since it started sounding
    float loudness = 0;      // recent level, for choosing whom to steal

    Rng rng;
    Pink pink, pink2;
    Brown brown, brown2;
    Svf f[5];
    Osc osc[3];
    Decay env[3];
    Resonators res;
    Crackle crackle;
    OnePole distance;
    OnePole hullLow;
    DcBlock dc;
    float state[8] = {};     // recipe scratch: timers, glides, sweeps
};

class Synth {
public:
    explicit Synth(float sampleRate, int voiceBudget = kMaxVoices);

    /** Game thread: the held sounds this frame. */
    void publishScene(const Scene& scene);

    /** Game thread: a one-shot. False if the queue is full (it is dropped). */
    bool pushEvent(const Event& event);

    /** Game thread: loudness per bus, 0..1 each. */
    void setBusGains(const float* gains);

    /** Game thread: how much room - more inside a hull, a little outdoors. */
    void setRoom(float amount) { roomTarget_.store(amount); }

    /** Audio thread: [frames] stereo frames, interleaved, into [out]. */
    void render(float* out, int frames);

    float sampleRate() const { return sampleRate_; }

    /** Off only to measure recipes' raw levels in the gallery. */
    bool limiting = true;
    int activeVoices() const;

private:
    void takeScene();
    void takeEvents();
    Voice* allocate();
    void startVoice(Voice& v, int recipe, int flags, const float* p, uint32_t seed, bool held);
    void renderVoice(Voice& v, float& left, float& right);
    float reverb(float in, int channel);

    float sampleRate_;
    int voiceBudget_;
    Voice voices_[kMaxVoices];

    // Triple buffer: the writer fills one and publishes it by swapping it
    // into [ready_] with the fresh bit (4) set; the reader, seeing the bit,
    // swaps its own old one in. Index and bit in one atomic, so neither side
    // can see a half-made swap.
    Scene buffers_[3];
    int writing_ = 0;
    int reading_ = 1;
    std::atomic<int> ready_{2};

    Event events_[kEventSlots];
    std::atomic<uint32_t> eventHead_{0};
    std::atomic<uint32_t> eventTail_{0};

    std::atomic<float> busTarget_[bus::COUNT];
    Smooth bus_[bus::COUNT];
    std::atomic<float> roomTarget_{0.15f};
    Smooth room_;

    // A small room: four combs and two all-passes a side.
    std::vector<float> comb_[2][4];
    int combIndex_[2][4] = {};
    float combFilter_[2][4] = {};
    std::vector<float> allpass_[2][2];
    int allpassIndex_[2][2] = {};

    float limiterGain_ = 1.0f;
    uint32_t nextSeed_ = 1;
};

}  // namespace apogee
