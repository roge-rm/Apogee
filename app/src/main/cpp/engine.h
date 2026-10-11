// The device end of the sound engine, one per platform: engine.cpp on Android (Oboe),
// desktop/native/engine_desktop.cpp elsewhere (miniaudio). jni.cpp calls these.
#pragma once

namespace apogee {

class Synth;

/** Opens the output and starts the synth with [voiceBudget] voices. False if there's no output. */
bool engineStart(int voiceBudget);
void engineStop();
void enginePause(bool paused);

/** The synth, once a stream has opened, or null. */
Synth* engineSynth();

}  // namespace apogee
