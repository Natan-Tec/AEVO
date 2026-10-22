// Carregador de SF2 do AEVO: lê a estrutura do arquivo pelo descritor (fd) e MAPEIA os samples
// direto do arquivo (mmap), em vez de copiá-los para a memória. Assim um SF2 de 1 GB não ocupa
// 1 GB de RAM: o Android só traz do armazenamento as páginas que realmente forem tocadas e pode
// devolvê-las quando faltar memória.
//
// Este arquivo não depende de JNI nem de Oboe, para poder ser testado no computador.
// Quem inclui precisa ter incluído "tsf.h" antes (com TSF_IMPLEMENTATION em um único .cpp).

#pragma once

#include <sys/mman.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <memory>
#include <thread>
#include <utility>
#include <vector>

namespace sf2 {

// Área do arquivo mapeada na memória. Fica viva enquanto alguém (faixa, thread de pré-carga) a usar.
struct Mapping {
    void* base = nullptr;
    size_t length = 0;
    const short* samples = nullptr;  // primeiro sample (dentro de base)
    size_t sampleCount = 0;

    Mapping() = default;
    Mapping(const Mapping&) = delete;
    Mapping& operator=(const Mapping&) = delete;
    ~Mapping() {
        if (base != nullptr) munmap(base, length);
    }
};

struct FdStream {
    int fd = -1;
    bool allowMap = true;
    std::shared_ptr<Mapping> mapping;
};

inline int fdRead(void* data, void* ptr, unsigned int size) {
    auto* s = static_cast<FdStream*>(data);
    auto* out = static_cast<char*>(ptr);
    unsigned int total = 0;
    while (total < size) {
        ssize_t n = ::read(s->fd, out + total, size - total);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        total += static_cast<unsigned int>(n);
    }
    return static_cast<int>(total);
}

inline int fdSkip(void* data, unsigned int count) {
    auto* s = static_cast<FdStream*>(data);
    return lseek64(s->fd, static_cast<off64_t>(count), SEEK_CUR) >= 0 ? 1 : 0;
}

inline int fdMap(void* data, unsigned int size, const short** out) {
    auto* s = static_cast<FdStream*>(data);
    if (!s->allowMap) return 0;
    off64_t pos = lseek64(s->fd, 0, SEEK_CUR);
    if (pos < 0) return 0;

    const off64_t page = sysconf(_SC_PAGESIZE);
    const off64_t aligned = pos - (pos % page);
    const size_t delta = static_cast<size_t>(pos - aligned);
    const size_t length = static_cast<size_t>(size) + delta;

    void* base = mmap64(nullptr, length, PROT_READ, MAP_PRIVATE, s->fd, aligned);
    if (base == MAP_FAILED) return 0;

    auto mapping = std::make_shared<Mapping>();
    mapping->base = base;
    mapping->length = length;
    mapping->samples = reinterpret_cast<const short*>(static_cast<char*>(base) + delta);
    mapping->sampleCount = size / sizeof(short);

    if (lseek64(s->fd, static_cast<off64_t>(size), SEEK_CUR) < 0) return 0;  // ~Mapping desfaz o mmap
    s->mapping = mapping;
    *out = mapping->samples;
    return 1;
}

enum LoadStatus { kOk = 0, kInvalid = -1, kNotSeekable = -2 };

struct Loaded {
    tsf* synth = nullptr;
    std::shared_ptr<Mapping> mapping;  // nulo se os samples foram copiados para a memória
    LoadStatus status = kInvalid;
};

// Lê um SF2 pelo descritor. NÃO fecha o fd (quem chamou decide).
inline Loaded load(int fd, bool allowMap = true) {
    Loaded result;
    if (lseek64(fd, 0, SEEK_SET) < 0) {
        result.status = kNotSeekable;
        return result;
    }
    FdStream state;
    state.fd = fd;
    state.allowMap = allowMap;

    tsf_stream stream;
    stream.data = &state;
    stream.read = &fdRead;
    stream.skip = &fdSkip;
    stream.map = &fdMap;

    result.synth = tsf_load(&stream);
    if (result.synth != nullptr) {
        result.mapping = state.mapping;
        result.status = kOk;
    }
    return result;
}

// Trecho de samples (em bytes, relativo ao início dos samples) usado por um preset.
using Range = std::pair<size_t, size_t>;

inline std::vector<Range> presetRanges(const tsf* synth, int presetIndex, size_t sampleCount) {
    std::vector<Range> ranges;
    if (presetIndex < 0 || presetIndex >= synth->presetNum) return ranges;
    const tsf_preset& preset = synth->presets[presetIndex];
    for (int i = 0; i < preset.regionNum; ++i) {
        const tsf_region& r = preset.regions[i];
        size_t begin = std::min<size_t>(r.offset, sampleCount);
        size_t end = std::min<size_t>(r.end, sampleCount);
        if (end > begin) ranges.emplace_back(begin * sizeof(short), end * sizeof(short));
    }
    std::sort(ranges.begin(), ranges.end());
    std::vector<Range> merged;
    for (const Range& r : ranges) {
        if (!merged.empty() && r.first <= merged.back().second) {
            merged.back().second = std::max(merged.back().second, r.second);
        } else {
            merged.push_back(r);
        }
    }
    return merged;
}

// Traz do armazenamento para a memória os samples de um preset (cache de páginas do sistema),
// para que a primeira nota não precise esperar o disco. Roda fora da thread de áudio.
inline void warm(const Mapping& mapping, const std::vector<Range>& ranges) {
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    const char* base = reinterpret_cast<const char*>(mapping.samples);
    volatile char sink = 0;
    for (const Range& r : ranges) {
        const uintptr_t startAddr = reinterpret_cast<uintptr_t>(base + r.first);
        const uintptr_t alignedStart = startAddr - (startAddr % page);
        madvise(reinterpret_cast<void*>(alignedStart), (startAddr - alignedStart) + (r.second - r.first),
                MADV_WILLNEED);
    }
    for (const Range& r : ranges) {
        for (size_t offset = r.first; offset < r.second; offset += page) sink = sink + base[offset];
    }
    (void)sink;
}

// Pré-carrega em segundo plano e depois chama "done". "pending" conta quantas cargas estão em andamento.
template <typename Done>
void warmAsync(std::shared_ptr<Mapping> mapping, std::vector<Range> ranges,
               std::shared_ptr<std::atomic<int>> pending, Done done) {
    pending->fetch_add(1);
    std::thread([mapping = std::move(mapping), ranges = std::move(ranges), pending, done]() mutable {
        if (mapping) warm(*mapping, ranges);
        done();
        pending->fetch_sub(1);
    }).detach();
}

}  // namespace sf2
