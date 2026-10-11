// The device end of the sound engine on Linux and Windows: a miniaudio playback device feeding
// the synth. jni.cpp (in app/src/main/cpp) has the calls Kotlin makes.
#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_DECODING
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_NODE_GRAPH
#define MA_NO_ENGINE
#include <miniaudio.h>

#include <cstdio>
#include <memory>
#include <mutex>

#include "engine.h"
#include "synth/synth.h"

namespace {

/** 256 frames, 5.3 ms at 48 kHz, in three periods: about what a phone's four bursts give. */
constexpr ma_uint32 kPeriodFrames = 256;
constexpr ma_uint32 kPeriods = 3;

void onOutput(ma_device* device, void* out, const void*, ma_uint32 frames);

class Engine {
public:
    bool start(int voiceBudget) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (open_) return true;
        ma_device_config config = ma_device_config_init(ma_device_type_playback);
        config.playback.format = ma_format_f32;
        config.playback.channels = 2;
        config.sampleRate = 0;
        config.periodSizeInFrames = kPeriodFrames;
        config.periods = kPeriods;
        config.dataCallback = onOutput;
        config.pUserData = this;
        if (ma_device_init(nullptr, &config, &device_) != MA_SUCCESS) {
            std::fprintf(stderr, "W ApogeeAudio: could not open an output\n");
            return false;
        }
        // Made once at the first device's rate and kept, as on Android.
        if (!synth_) synth_ = std::make_unique<apogee::Synth>(static_cast<float>(device_.sampleRate), voiceBudget);
        open_ = true;
        const bool started = ma_device_start(&device_) == MA_SUCCESS;
        std::fprintf(stderr, "I ApogeeAudio: %s, %u Hz, %s\n", ma_get_backend_name(device_.pContext->backend),
                     device_.sampleRate, started ? "started" : "not started");
        return started;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!open_) return;
        ma_device_uninit(&device_);
        open_ = false;
    }

    void pause(bool paused) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!open_) return;
        if (paused) ma_device_stop(&device_); else ma_device_start(&device_);
    }

    apogee::Synth* synth() { return synth_.get(); }

    void render(float* out, ma_uint32 frames) { synth_->render(out, static_cast<int32_t>(frames)); }

private:
    std::mutex mutex_;
    ma_device device_{};
    bool open_ = false;
    std::unique_ptr<apogee::Synth> synth_;
};

void onOutput(ma_device* device, void* out, const void*, ma_uint32 frames) {
    static_cast<Engine*>(device->pUserData)->render(static_cast<float*>(out), frames);
}

Engine* engine = nullptr;

}  // namespace

namespace apogee {

bool engineStart(int voiceBudget) {
    if (!engine) engine = new Engine();
    return engine->start(voiceBudget);
}

void engineStop() {
    if (engine) engine->stop();
}

void enginePause(bool paused) {
    if (engine) engine->pause(paused);
}

Synth* engineSynth() { return engine ? engine->synth() : nullptr; }

}  // namespace apogee
