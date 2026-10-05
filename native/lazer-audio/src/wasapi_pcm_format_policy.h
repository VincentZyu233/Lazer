/* Platform-independent candidate ordering for exclusive WASAPI PCM negotiation. */
#ifndef LAZER_AUDIO_WASAPI_PCM_FORMAT_POLICY_H
#define LAZER_AUDIO_WASAPI_PCM_FORMAT_POLICY_H

#include <cstdint>
#include <optional>
#include <utility>
#include <vector>

namespace lazer::audio::wasapi {

enum class PcmCandidateTier {
    ExactSource,
    SameRateAlternate,
    MonoToStereo,
    MixFormat,
    CommonRate,
};

enum class PcmDescriptor {
    ExtensibleInteger,
    LegacyInteger,
    DeviceMix,
};

struct PcmSourceFormat {
    uint32_t sampleRate = 0;
    uint16_t channels = 0;
    uint16_t bitsPerSample = 0;
    bool lossless = false;
    bool integerPcm = false;
};

struct PcmMixFormat {
    uint32_t sampleRate = 0;
};

struct PcmCandidate {
    PcmCandidateTier tier = PcmCandidateTier::ExactSource;
    PcmDescriptor descriptor = PcmDescriptor::ExtensibleInteger;
    uint32_t sampleRate = 0;
    uint16_t channels = 0;
    uint16_t validBits = 0;
    uint16_t containerBits = 0;
};

[[nodiscard]] std::vector<PcmCandidate> exclusivePcmCandidates(
    const PcmSourceFormat &source, const PcmMixFormat &mix, bool bitPerfect);

/* The injected predicate is normally IAudioClient::IsFormatSupported. Keeping selection generic
 * lets the ordering and strict no-fallback rule run deterministically without Windows or a DAC. */
template <typename IsFormatSupported>
[[nodiscard]] std::optional<PcmCandidate> selectExclusivePcmCandidate(
    const PcmSourceFormat &source, const PcmMixFormat &mix, bool bitPerfect,
    IsFormatSupported &&isFormatSupported) {
    for (const PcmCandidate &candidate : exclusivePcmCandidates(source, mix, bitPerfect)) {
        if (isFormatSupported(candidate)) return candidate;
    }
    return std::nullopt;
}

}  // namespace lazer::audio::wasapi

#endif  // LAZER_AUDIO_WASAPI_PCM_FORMAT_POLICY_H
