/* Deterministic tests for WASAPI PCM negotiation order; no Windows endpoint is required. */
#include <cstdio>
#include <vector>

#include "wasapi_pcm_format_policy.h"

using lazer::audio::wasapi::PcmCandidate;
using lazer::audio::wasapi::PcmCandidateTier;
using lazer::audio::wasapi::PcmDescriptor;
using lazer::audio::wasapi::PcmMixFormat;
using lazer::audio::wasapi::PcmSourceFormat;
using lazer::audio::wasapi::exclusivePcmCandidates;
using lazer::audio::wasapi::selectExclusivePcmCandidate;

namespace {

bool fail(const char *message) {
    std::fprintf(stderr, "FAIL: %s\n", message);
    return false;
}

bool testStrictOnlyTriesExactSourceCandidates() {
    const PcmSourceFormat source{96000, 2, 24, true, true};
    const PcmMixFormat mix{48000};
    std::vector<PcmCandidate> tried;
    const auto selected = selectExclusivePcmCandidate(source, mix, true,
        [&](const PcmCandidate &candidate) {
            tried.push_back(candidate);
            return false;
        });
    if (selected) return fail("strict mode unexpectedly selected a rejected format");
    if (tried.size() != 2) return fail("strict mode did not try both exact 24-bit descriptors");
    for (const auto &candidate : tried) {
        if (candidate.tier != PcmCandidateTier::ExactSource ||
            candidate.sampleRate != source.sampleRate || candidate.channels != source.channels ||
            candidate.validBits != 24) {
            return fail("strict mode tried a non-exact source format");
        }
    }
    return true;
}

bool testExactCandidatesPrecedeAlternateDepth() {
    const PcmSourceFormat source{44100, 2, 16, true, true};
    const PcmMixFormat mix{48000};
    std::vector<PcmCandidate> tried;
    const auto selected = selectExclusivePcmCandidate(source, mix, false,
        [&](const PcmCandidate &candidate) {
            tried.push_back(candidate);
            return candidate.tier == PcmCandidateTier::SameRateAlternate;
        });
    if (!selected || selected->validBits != 32 || selected->sampleRate != source.sampleRate) {
        return fail("same-rate alternate depth was not selected after exact candidates");
    }
    if (tried.size() != 3 || tried[0].tier != PcmCandidateTier::ExactSource ||
        tried[1].descriptor != PcmDescriptor::LegacyInteger ||
        tried[2].tier != PcmCandidateTier::SameRateAlternate) {
        return fail("exact source candidate order changed before alternate depth");
    }
    return true;
}

bool testMonoToStereoPrecedesMixFormat() {
    const PcmSourceFormat source{44100, 1, 16, true, true};
    const PcmMixFormat mix{48000};
    std::vector<PcmCandidate> tried;
    const auto selected = selectExclusivePcmCandidate(source, mix, false,
        [&](const PcmCandidate &candidate) {
            tried.push_back(candidate);
            return candidate.tier == PcmCandidateTier::MonoToStereo;
        });
    if (!selected || selected->channels != 2 || selected->sampleRate != source.sampleRate) {
        return fail("mono-to-stereo candidate was not selected");
    }
    const auto candidates = exclusivePcmCandidates(source, mix, false);
    size_t firstMono = candidates.size();
    size_t firstMix = candidates.size();
    for (size_t index = 0; index < candidates.size(); ++index) {
        if (firstMono == candidates.size() && candidates[index].tier == PcmCandidateTier::MonoToStereo) {
            firstMono = index;
        }
        if (firstMix == candidates.size() && candidates[index].tier == PcmCandidateTier::MixFormat) {
            firstMix = index;
        }
    }
    if (!selected || firstMono == candidates.size() || firstMix <= firstMono ||
        candidates[firstMono].channels != 2) {
        return fail("mix-format fallback was tried before mono-to-stereo candidates");
    }
    return true;
}

bool testMixFormatPrecedesCommonRates() {
    const PcmSourceFormat source{96000, 2, 24, true, true};
    const PcmMixFormat mix{48000};
    std::vector<PcmCandidate> tried;
    const auto selected = selectExclusivePcmCandidate(source, mix, false,
        [&](const PcmCandidate &candidate) {
            tried.push_back(candidate);
            return candidate.tier == PcmCandidateTier::MixFormat;
        });
    if (!selected || selected->descriptor != PcmDescriptor::DeviceMix) {
        return fail("mix-format fallback was not selected after source candidates");
    }
    const auto candidates = exclusivePcmCandidates(source, mix, false);
    if (tried.size() >= candidates.size() || candidates[tried.size()].tier != PcmCandidateTier::CommonRate) {
        return fail("common-rate fallback did not follow the mix format");
    }
    return true;
}

bool testCommonRatesPreferSourceFamilyAndDistance() {
    const PcmSourceFormat source{96000, 2, 24, true, true};
    const PcmMixFormat mix{48000};
    std::vector<uint32_t> firstRateCandidates;
    const auto selected = selectExclusivePcmCandidate(source, mix, false,
        [&](const PcmCandidate &candidate) {
            if (candidate.tier == PcmCandidateTier::CommonRate &&
                (firstRateCandidates.empty() || firstRateCandidates.back() != candidate.sampleRate)) {
                firstRateCandidates.push_back(candidate.sampleRate);
            }
            return false;
        });
    if (selected || firstRateCandidates.size() < 5 || firstRateCandidates[0] != 48000 ||
        firstRateCandidates[1] != 192000 || firstRateCandidates[2] != 384000 ||
        firstRateCandidates[3] != 768000 || firstRateCandidates[4] != 88200) {
        std::fprintf(stderr, "  observed common-rate order:");
        for (const uint32_t rate : firstRateCandidates) std::fprintf(stderr, " %u", rate);
        std::fputc('\n', stderr);
        return fail("common rates no longer prioritize source clock family, then proximity");
    }
    return true;
}

}  // namespace

int main() {
    const bool strict = testStrictOnlyTriesExactSourceCandidates();
    const bool exactOrder = testExactCandidatesPrecedeAlternateDepth();
    const bool monoOrder = testMonoToStereoPrecedesMixFormat();
    const bool mixOrder = testMixFormatPrecedesCommonRates();
    const bool commonOrder = testCommonRatesPreferSourceFamilyAndDistance();
    if (strict && exactOrder && monoOrder && mixOrder && commonOrder) {
        std::puts("PASS: WASAPI exact, alternate-depth, mono, mix, common-rate and strict policies");
        return 0;
    }
    return 1;
}
