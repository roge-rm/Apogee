// The device end of the sound engine on Android: an Oboe stream feeding the synth.
#include <android/log.h>
#include <oboe/Oboe.h>

#include <memory>
#include <mutex>

#include "engine.h"
#include "synth/synth.h"

namespace {

constexpr const char* kTag = "ApogeeAudio";

class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    bool start(int voiceBudget) {
        std::lock_guard<std::mutex> lock(mutex_);
        budget_ = voiceBudget;
        return open();
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
    }

    void pause(bool paused) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return;
        if (paused) stream_->requestPause(); else stream_->requestStart();
    }

    apogee::Synth* synth() { return synth_.get(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override {
        synth_->render(static_cast<float*>(data), frames);
        return oboe::DataCallbackResult::Continue;
    }

    // The device changed (headphones, Bluetooth), so open a new stream.
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        __android_log_print(ANDROID_LOG_INFO, kTag, "stream closed (%s), reopening", oboe::convertToText(error));
        std::lock_guard<std::mutex> lock(mutex_);
        stream_.reset();
        open();
    }

private:
    bool open() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Game)
            ->setContentType(oboe::ContentType::Sonification)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "could not open a stream: %s", oboe::convertToText(result));
            return false;
        }
        // Made once at the first stream's rate and kept: the game thread holds it across device
        // changes.
        if (!synth_) synth_ = std::make_unique<apogee::Synth>(static_cast<float>(stream_->getSampleRate()), budget_);
        // Four bursts of slack, so a stalled frame doesn't run the buffer dry and crackle.
        stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 4);
        result = stream_->requestStart();
        __android_log_print(ANDROID_LOG_INFO, kTag, "stream %d Hz, burst %d, %s", stream_->getSampleRate(),
                            stream_->getFramesPerBurst(), oboe::convertToText(result));
        return result == oboe::Result::OK;
    }

    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<apogee::Synth> synth_;
    int budget_ = 32;
};

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
