// The calls Kotlin makes into the sound engine (com.rm.apogee.audio.Synth), the same on Android and
// desktop. Each platform's engine file opens the device.
#include <jni.h>

#include <algorithm>

#include "engine.h"
#include "synth/synth.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_rm_apogee_audio_Synth_nativeStart(JNIEnv*, jobject, jint voiceBudget) {
    return apogee::engineStart(voiceBudget) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_rm_apogee_audio_Synth_nativeStop(JNIEnv*, jobject) {
    apogee::engineStop();
}

JNIEXPORT void JNICALL
Java_com_rm_apogee_audio_Synth_nativePause(JNIEnv*, jobject, jboolean paused) {
    apogee::enginePause(paused == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_rm_apogee_audio_Synth_nativeScene(JNIEnv* env, jobject, jint count, jintArray keys, jintArray recipes,
                                                 jintArray flags, jfloatArray params) {
    apogee::Synth* synth = apogee::engineSynth();
    if (!synth) return;
    apogee::Scene scene;
    scene.count = std::min(static_cast<int>(count), apogee::kMaxSceneEntries);
    // Copy only the entries in use onto the stack, not the whole arrays.
    jint k[apogee::kMaxSceneEntries];
    jint r[apogee::kMaxSceneEntries];
    jint f[apogee::kMaxSceneEntries];
    jfloat p[apogee::kMaxSceneEntries * apogee::kParams];
    if (scene.count > 0) {
        env->GetIntArrayRegion(keys, 0, scene.count, k);
        env->GetIntArrayRegion(recipes, 0, scene.count, r);
        env->GetIntArrayRegion(flags, 0, scene.count, f);
        env->GetFloatArrayRegion(params, 0, scene.count * apogee::kParams, p);
    }
    for (int i = 0; i < scene.count; ++i) {
        scene.entries[i].key = k[i];
        scene.entries[i].recipe = r[i];
        scene.entries[i].flags = f[i];
        for (int j = 0; j < apogee::kParams; ++j) scene.entries[i].p[j] = p[i * apogee::kParams + j];
    }
    synth->publishScene(scene);
}

JNIEXPORT void JNICALL
Java_com_rm_apogee_audio_Synth_nativeEvent(JNIEnv* env, jobject, jint recipe, jint flags, jint seed, jfloat delay,
                                                 jfloatArray params) {
    apogee::Synth* synth = apogee::engineSynth();
    if (!synth) return;
    apogee::Event event;
    event.recipe = recipe;
    event.flags = flags;
    event.seed = static_cast<uint32_t>(seed);
    event.delay = delay;
    jfloat* p = env->GetFloatArrayElements(params, nullptr);
    for (int j = 0; j < apogee::kParams; ++j) event.p[j] = p[j];
    env->ReleaseFloatArrayElements(params, p, JNI_ABORT);
    synth->pushEvent(event);
}

JNIEXPORT void JNICALL
Java_com_rm_apogee_audio_Synth_nativeBusGains(JNIEnv* env, jobject, jfloatArray gains) {
    apogee::Synth* synth = apogee::engineSynth();
    if (!synth) return;
    jfloat* g = env->GetFloatArrayElements(gains, nullptr);
    float copy[apogee::bus::COUNT];
    for (int b = 0; b < apogee::bus::COUNT; ++b) copy[b] = g[b];
    env->ReleaseFloatArrayElements(gains, g, JNI_ABORT);
    synth->setBusGains(copy);
}

JNIEXPORT jint JNICALL
Java_com_rm_apogee_audio_Synth_nativeActiveVoices(JNIEnv*, jobject) {
    apogee::Synth* synth = apogee::engineSynth();
    return synth ? synth->activeVoices() : 0;
}

JNIEXPORT void JNICALL
Java_com_rm_apogee_audio_Synth_nativeRoom(JNIEnv*, jobject, jfloat amount) {
    if (apogee::Synth* synth = apogee::engineSynth()) synth->setRoom(amount);
}

}  // extern "C"
