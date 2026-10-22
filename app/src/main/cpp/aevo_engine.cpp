// Motor de áudio do AEVO (beta 0.3).
//
// - Saída de baixa latência com Oboe (AAudio quando disponível), callback em tempo real.
// - Cada faixa é um "instrumento virtual": um SF2 carregado via TinySoundFont (tsf.h,
//   baixado pelo workflow do GitHub Actions), tocado no canal 0 do sintetizador.
// - Notas, pitch bend, CC e troca de preset chegam por uma fila de eventos, sem travar
//   a thread de áudio; o callback só consome a fila e renderiza.

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#define TSF_IMPLEMENTATION
#include "tsf.h"      // versão do AEVO: samples de 16 bits mapeados do arquivo (ver sf2_loader.h)
#include "sf2_loader.h"

#define LOG_TAG "AEVO"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kMaxVoices = 64;
constexpr int kChannel = 0;

enum class EventType : int { NoteOn, NoteOff, Control, PitchBend, SetPreset, SetVolume, AllNotesOff };

struct Event {
    EventType type;
    int track;
    int a;       // tecla / controlador / preset / valor do pitch bend
    int b;       // velocidade / valor do controlador
    float f;     // volume
};

// Fila de eventos: vários produtores (toque na tela, thread MIDI), um consumidor (áudio).
// Os produtores se alternam com um spinlock curto; o consumidor nunca espera.
class EventQueue {
public:
    bool push(const Event& e) {
        while (lock_.test_and_set(std::memory_order_acquire)) {}
        size_t head = head_.load(std::memory_order_relaxed);
        size_t next = (head + 1) % kSize;
        bool ok = next != tail_.load(std::memory_order_acquire);
        if (ok) {
            buf_[head] = e;
            head_.store(next, std::memory_order_release);
        }
        lock_.clear(std::memory_order_release);
        return ok;
    }

    bool pop(Event& e) {
        size_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) return false;
        e = buf_[tail];
        tail_.store((tail + 1) % kSize, std::memory_order_release);
        return true;
    }

private:
    static constexpr size_t kSize = 2048;
    Event buf_[kSize];
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
    std::atomic_flag lock_ = ATOMIC_FLAG_INIT;
};

struct Track {
    tsf* synth = nullptr;
    float volume = 0.8f;
    std::shared_ptr<sf2::Mapping> mapping;               // samples mapeados do arquivo (pode ser nulo)
    std::shared_ptr<std::atomic<int>> pending;           // cargas de preset em andamento
};

std::mutex gMutex;  // protege gTracks (adicionar/remover); o callback usa try_lock
std::map<int, Track> gTracks;
int gNextId = 0;
std::atomic<int> gSampleRate{48000};
EventQueue gEvents;

// Aplica um evento (somente na thread de áudio, com gMutex travado).
void applyEvent(const Event& e) {
    auto it = gTracks.find(e.track);
    if (it == gTracks.end()) return;
    tsf* s = it->second.synth;
    switch (e.type) {
        case EventType::NoteOn:
            tsf_channel_note_on(s, kChannel, e.a, e.b / 127.0f);
            break;
        case EventType::NoteOff:
            tsf_channel_note_off(s, kChannel, e.a);
            break;
        case EventType::Control:
            tsf_channel_midi_control(s, kChannel, e.a, e.b);
            break;
        case EventType::PitchBend:
            tsf_channel_set_pitchwheel(s, kChannel, e.a);
            break;
        case EventType::SetPreset:
            tsf_channel_sounds_off_all(s, kChannel);
            tsf_channel_set_presetindex(s, kChannel, e.a);
            break;
        case EventType::SetVolume:
            it->second.volume = e.f;
            tsf_set_volume(s, e.f);
            break;
        case EventType::AllNotesOff:
            tsf_channel_sounds_off_all(s, kChannel);
            break;
    }
}

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback,
                    public std::enable_shared_from_this<AudioEngine> {
public:
    bool start() {
        if (stream_) return true;
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Media)
            ->setDataCallback(shared_from_this())
            ->setErrorCallback(shared_from_this());

        oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            LOGE("Falha ao abrir stream: %s", oboe::convertToText(result));
            stream_.reset();
            return false;
        }

        // Buffer de 2 rajadas: o menor valor que costuma rodar sem estalos.
        int burst = stream_->getFramesPerBurst();
        if (burst > 0) stream_->setBufferSizeInFrames(burst * 2);

        applySampleRate(stream_->getSampleRate());

        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Falha ao iniciar stream: %s", oboe::convertToText(result));
            stream_->close();
            stream_.reset();
            return false;
        }
        LOGI("Stream: %d Hz, burst %d, buffer %d, modo %d, compartilhamento %d",
             stream_->getSampleRate(), stream_->getFramesPerBurst(),
             stream_->getBufferSizeInFrames(), (int)stream_->getPerformanceMode(),
             (int)stream_->getSharingMode());
        return true;
    }

    void stop() {
        if (!stream_) return;
        stream_->stop();
        stream_->close();
        stream_.reset();
    }

    std::string info() {
        if (!stream_) return "Áudio parado";
        int rate = stream_->getSampleRate();
        int buffer = stream_->getBufferSizeInFrames();
        double bufferMs = rate > 0 ? 1000.0 * buffer / rate : 0.0;
        const char* mode = stream_->getPerformanceMode() == oboe::PerformanceMode::LowLatency
                               ? "baixa latência" : "latência normal";
        const char* sharing = stream_->getSharingMode() == oboe::SharingMode::Exclusive
                                  ? "exclusivo" : "compartilhado";
        const char* api = stream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSL ES";
        char text[200];
        snprintf(text, sizeof(text), "%s, %s, %s | %d Hz | buffer %d quadros (%.1f ms)",
                 api, mode, sharing, rate, buffer, bufferMs);
        return text;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                          int32_t numFrames) override {
        float* out = static_cast<float*>(audioData);
        std::fill(out, out + static_cast<size_t>(numFrames) * 2, 0.0f);

        // Se alguém está adicionando/removendo faixa neste instante, pula este bloco.
        // Os eventos continuam na fila para o próximo.
        std::unique_lock<std::mutex> lock(gMutex, std::try_to_lock);
        if (!lock.owns_lock()) return oboe::DataCallbackResult::Continue;

        Event e;
        while (gEvents.pop(e)) applyEvent(e);

        for (auto& entry : gTracks) {
            tsf_render_float(entry.second.synth, out, numFrames, 1);
        }
        for (int i = 0; i < numFrames * 2; ++i) {
            out[i] = std::max(-1.0f, std::min(1.0f, out[i]));
        }
        return oboe::DataCallbackResult::Continue;
    }

    // Fone desconectado, mudança de rota etc.: reabre o stream.
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result result) override {
        LOGE("Stream encerrado (%s), reabrindo", oboe::convertToText(result));
        stream_.reset();
        start();
    }

private:
    void applySampleRate(int rate) {
        gSampleRate = rate;
        std::lock_guard<std::mutex> lock(gMutex);
        for (auto& entry : gTracks) {
            tsf_set_output(entry.second.synth, TSF_STEREO_INTERLEAVED, rate, 0.0f);
            tsf_set_volume(entry.second.synth, entry.second.volume);
        }
    }

    std::shared_ptr<oboe::AudioStream> stream_;
};

std::shared_ptr<AudioEngine> gEngine = std::make_shared<AudioEngine>();

}  // namespace



extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_natan_aevo_Engine_nativeStartAudio(JNIEnv*, jobject) {
    return gEngine->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeStopAudio(JNIEnv*, jobject) {
    gEngine->stop();
}

JNIEXPORT jstring JNICALL
Java_com_natan_aevo_Engine_nativeAudioInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(gEngine->info().c_str());
}

// Carrega um SF2 pelo descritor de arquivo (o motor fecha o fd).
// Retorna o id da faixa; -1 = SF2 inválido ou grande demais; -2 = o fd não permite leitura
// aleatória (o chamador deve copiar o arquivo para o cache e tentar de novo).
JNIEXPORT jint JNICALL
Java_com_natan_aevo_Engine_nativeAddTrackFd(JNIEnv*, jobject, jint fd) {
    sf2::Loaded loaded = sf2::load(fd);
    close(fd);
    if (loaded.status != sf2::kOk) return loaded.status;

    tsf* synth = loaded.synth;
    tsf_set_max_voices(synth, kMaxVoices);
    tsf_channel_set_presetindex(synth, kChannel, 0);  // cria o canal 0

    Track track;
    track.synth = synth;
    track.mapping = loaded.mapping;
    track.pending = std::make_shared<std::atomic<int>>(0);

    int id;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        tsf_set_output(synth, TSF_STEREO_INTERLEAVED, gSampleRate.load(), 0.0f);
        tsf_set_volume(synth, track.volume);
        id = gNextId++;
        gTracks[id] = track;
    }

    // Traz para a memória só os samples do primeiro preset, em segundo plano.
    if (track.mapping) {
        auto ranges = sf2::presetRanges(synth, 0, track.mapping->sampleCount);
        sf2::warmAsync(track.mapping, std::move(ranges), track.pending, [] {});
    }
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

JNIEXPORT jint JNICALL
Java_com_natan_aevo_Engine_nativePresetCount(JNIEnv*, jobject, jint id) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gTracks.find(id);
    return it == gTracks.end() ? 0 : tsf_get_presetcount(it->second.synth);
}

JNIEXPORT jstring JNICALL
Java_com_natan_aevo_Engine_nativePresetName(JNIEnv* env, jobject, jint id, jint index) {
    std::string name;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        auto it = gTracks.find(id);
        if (it != gTracks.end()) {
            const char* n = tsf_get_presetname(it->second.synth, index);
            if (n) name = n;
        }
    }
    return env->NewStringUTF(name.c_str());
}

// Troca o preset. Se os samples vêm do arquivo, primeiro os traz para a memória em segundo plano
// e só então troca (o som antigo continua até lá).
JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeSetPreset(JNIEnv*, jobject, jint id, jint index) {
    std::shared_ptr<sf2::Mapping> mapping;
    std::shared_ptr<std::atomic<int>> pending;
    std::vector<sf2::Range> ranges;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        auto it = gTracks.find(id);
        if (it == gTracks.end()) return;
        mapping = it->second.mapping;
        pending = it->second.pending;
        if (mapping) ranges = sf2::presetRanges(it->second.synth, index, mapping->sampleCount);
    }
    if (!mapping) {
        gEvents.push({EventType::SetPreset, id, index, 0, 0.0f});
        return;
    }
    sf2::warmAsync(mapping, std::move(ranges), pending, [id, index] {
        gEvents.push({EventType::SetPreset, id, index, 0, 0.0f});
    });
}

JNIEXPORT jboolean JNICALL
Java_com_natan_aevo_Engine_nativeIsLoading(JNIEnv*, jobject, jint id) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gTracks.find(id);
    return (it != gTracks.end() && it->second.pending && it->second.pending->load() > 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeSetVolume(JNIEnv*, jobject, jint id, jfloat volume) {
    gEvents.push({EventType::SetVolume, id, 0, 0, volume});
}

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeNoteOn(JNIEnv*, jobject, jint id, jint key, jint velocity) {
    gEvents.push({EventType::NoteOn, id, key, velocity, 0.0f});
}

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeNoteOff(JNIEnv*, jobject, jint id, jint key) {
    gEvents.push({EventType::NoteOff, id, key, 0, 0.0f});
}

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeControl(JNIEnv*, jobject, jint id, jint controller, jint value) {
    gEvents.push({EventType::Control, id, controller, value, 0.0f});
}

// value: 0..16383, centro = 8192
JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativePitchBend(JNIEnv*, jobject, jint id, jint value) {
    gEvents.push({EventType::PitchBend, id, value, 0, 0.0f});
}

JNIEXPORT void JNICALL
Java_com_natan_aevo_Engine_nativeAllNotesOff(JNIEnv*, jobject, jint id) {
    gEvents.push({EventType::AllNotesOff, id, 0, 0, 0.0f});
}

}  // extern "C"
