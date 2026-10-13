// Motor de áudio do AEVO: mixa várias trilhas, cada uma com um arquivo SF2
// carregado via TinySoundFont (tsf.h, baixado pelo workflow do GitHub Actions).

#include <jni.h>
#include <algorithm>
#include <map>
#include <mutex>
#include <vector>

#define TSF_IMPLEMENTATION
#include "tsf.h"

namespace {

struct Track {
    tsf* synth;
    float volume;
};

std::mutex gMutex;
std::map<int, Track> gTracks;
int gNextId = 0;
int gSampleRate = 44100;
std::vector<short> gScratch;
std::vector<float> gMix;

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeInit(JNIEnv*, jobject, jint sampleRate) {
    std::lock_guard<std::mutex> lock(gMutex);
    gSampleRate = sampleRate;
}

// Retorna o id da trilha, ou -1 se o SF2 não puder ser carregado.
JNIEXPORT jint JNICALL
Java_com_natan_aevo_Engine_nativeAddTrack(JNIEnv* env, jobject, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    std::vector<jbyte> bytes(static_cast<size_t>(len));
    env->GetByteArrayRegion(data, 0, len, bytes.data());

    tsf* synth = tsf_load_memory(bytes.data(), static_cast<int>(len));
    if (synth == nullptr) {
        return -1;
    }

    std::lock_guard<std::mutex> lock(gMutex);
    tsf_set_output(synth, TSF_STEREO_INTERLEAVED, gSampleRate, 0.0f);
    int id = gNextId++;
    gTracks[id] = Track{synth, 0.8f};
    return id;
}

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeRemoveTrack(JNIEnv*, jobject, jint id) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gTracks.find(id);
    if (it != gTracks.end()) {
        tsf_close(it->second.synth);
        gTracks.erase(it);
    }
}

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeSetVolume(JNIEnv*, jobject, jint id, jfloat volume) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gTracks.find(id);
    if (it != gTracks.end()) {
        it->second.volume = volume;
    }
}

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeNoteOn(JNIEnv*, jobject, jint id, jint key, jfloat velocity) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gTracks.find(id);
    if (it != gTracks.end()) {
        // Usa o primeiro preset do SF2 (índice 0) nesta versão.
        tsf_note_on(it->second.synth, 0, key, velocity);
    }
}

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeNoteOff(JNIEnv*, jobject, jint id, jint key) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gTracks.find(id);
    if (it != gTracks.end()) {
        tsf_note_off(it->second.synth, 0, key);
    }
}

// Preenche "out" com "frames" quadros estéreo intercalados (2 * frames shorts).
JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeRender(JNIEnv* env, jobject, jshortArray out, jint frames) {
    std::lock_guard<std::mutex> lock(gMutex);

    const size_t count = static_cast<size_t>(frames) * 2;
    gMix.assign(count, 0.0f);
    gScratch.resize(count);

    for (auto& entry : gTracks) {
        Track& track = entry.second;
        tsf_render_short(track.synth, gScratch.data(), frames, 0);
        for (size_t i = 0; i < count; ++i) {
            gMix[i] += static_cast<float>(gScratch[i]) * track.volume;
        }
    }

    jshort* dst = env->GetShortArrayElements(out, nullptr);
    for (size_t i = 0; i < count; ++i) {
        float v = std::max(-32768.0f, std::min(32767.0f, gMix[i]));
        dst[i] = static_cast<jshort>(v);
    }
    env->ReleaseShortArrayElements(out, dst, 0);
}

}  // extern "C"
