#include "output_wasapi.h"

#include <algorithm>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>
#include <string_view>

#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <ks.h>
#include <ksmedia.h>

namespace lazer::audio {

namespace {

/* Only KSDATAFORMAT_SUBTYPE_PCM and _IEEE_FLOAT are handed to the device; an EXTENSIBLE header is
 * always used for anything beyond 2 channels or 16 bits, because that is what the audio engine
 * reports for real devices. */
constexpr int32_t kWaveFormatExtensible = 0xFFFE;

std::string hresultText(HRESULT result) {
    std::ostringstream text;
    text << "0x" << std::hex << std::uppercase << static_cast<uint32_t>(result);
    return text.str();
}

bool utf8ToWide(std::string_view input, std::wstring &out) {
    out.clear();
    if (input.empty()) return true;
    if (input.size() > static_cast<size_t>(std::numeric_limits<int>::max())) return false;
    const int inputLength = static_cast<int>(input.size());
    const int required = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
        input.data(), inputLength, nullptr, 0);
    if (required <= 0) return false;
    out.resize(static_cast<size_t>(required));
    return MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
        input.data(), inputLength, out.data(), required) == required;
}

bool isFloatSubtype(const WAVEFORMATEX *format) {
    if (format == nullptr) return false;
    if (format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) return true;
    if (format->wFormatTag != kWaveFormatExtensible ||
        format->cbSize < sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX)) {
        return false;
    }
    const auto *extensible = reinterpret_cast<const WAVEFORMATEXTENSIBLE *>(format);
    return IsEqualGUID(extensible->SubFormat, KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);
}

bool isPcmSubtype(const WAVEFORMATEX *format) {
    if (format == nullptr) return false;
    if (format->wFormatTag == WAVE_FORMAT_PCM) return true;
    if (format->wFormatTag != kWaveFormatExtensible ||
        format->cbSize < sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX)) {
        return false;
    }
    const auto *extensible = reinterpret_cast<const WAVEFORMATEXTENSIBLE *>(format);
    return IsEqualGUID(extensible->SubFormat, KSDATAFORMAT_SUBTYPE_PCM);
}

uint32_t significantBits(const WAVEFORMATEX *format) {
    if (format == nullptr) return 16;
    if (format->wFormatTag == WAVE_FORMAT_PCM) return format->wBitsPerSample;
    if (format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) return 32;
    if (format->wFormatTag == kWaveFormatExtensible) {
        const auto *extensible = reinterpret_cast<const WAVEFORMATEXTENSIBLE *>(format);
        return extensible->Samples.wValidBitsPerSample > 0
            ? extensible->Samples.wValidBitsPerSample
            : format->wBitsPerSample;
    }
    return format->wBitsPerSample;
}

void fillExtensible(
    WAVEFORMATEXTENSIBLE &target, int32_t sampleRate, int32_t channels, uint32_t validBits,
    bool floatFormat, uint32_t containerBits = 0) {
    ZeroMemory(&target, sizeof(target));
    target.Format.wFormatTag = kWaveFormatExtensible;
    target.Format.nChannels = static_cast<WORD>(channels);
    target.Format.nSamplesPerSec = static_cast<DWORD>(sampleRate);
    if (containerBits == 0) containerBits = (validBits + 7) / 8 * 8;
    target.Format.wBitsPerSample = static_cast<WORD>(containerBits);
    target.Format.nBlockAlign = static_cast<WORD>(
        target.Format.nChannels * target.Format.wBitsPerSample / 8);
    target.Format.nAvgBytesPerSec = static_cast<DWORD>(
        static_cast<int64_t>(target.Format.nSamplesPerSec) * target.Format.nBlockAlign);
    target.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
    target.Samples.wValidBitsPerSample = static_cast<WORD>(validBits);
    target.dwChannelMask = channels == 1 ? KSAUDIO_SPEAKER_MONO
        : channels == 2 ? KSAUDIO_SPEAKER_STEREO
        : KSAUDIO_SPEAKER_5POINT1;
    target.SubFormat = floatFormat ? KSDATAFORMAT_SUBTYPE_IEEE_FLOAT
                                   : KSDATAFORMAT_SUBTYPE_PCM;
}

void copyFormat(WAVEFORMATEX *&destination, const WAVEFORMATEX *source) {
    /* The device hands back an allocated block; a caller-owned copy keeps CoTaskMemFree the only
     * deallocator in play here. */
    const size_t size = sizeof(WAVEFORMATEX) + source->cbSize;
    destination = static_cast<WAVEFORMATEX *>(CoTaskMemAlloc(size));
    if (destination == nullptr) return;
    std::memcpy(destination, source, size);
}

bool tryExclusivePcmFormat(IAudioClient *audioClient, const WAVEFORMATEX *candidate,
    WAVEFORMATEX *&chosen, uint32_t validBits, uint32_t &chosenBits, bool &usingFloat) {
    if (audioClient->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE, candidate, nullptr) != S_OK) {
        return false;
    }
    copyFormat(chosen, candidate);
    if (chosen == nullptr) return false;
    chosenBits = validBits;
    usingFloat = false;
    return true;
}

}  // namespace

WasapiOutput::~WasapiOutput() {
    close();
}

int32_t WasapiOutput::open(const AudioOutputRequest &request, const StreamDescription &source,
    AudioOutputSession &session, std::string &error, LogProxy *log) {
    close();

    if (request.bitPerfect && !request.exclusive) {
        error = "bit-perfect output requires WASAPI exclusive mode";
        return LazerAudioErrorUnsupported;
    }
    if (request.requireDoP && (!request.exclusive || request.bitPerfect || !source.dsd ||
        !source.rawDsd || (source.channels != 1 && source.channels != 2) ||
        request.desired.sampleRate <= 0 || request.desired.channels != source.channels ||
        request.desired.sampleRate * 2 != source.sampleRate ||
        request.desired.bitsPerSample != 24 || request.desired.containerBitsPerSample != 24)) {
        error = "WASAPI DoP requires exclusive mode, raw mono/stereo DSD and the exact 24-bit carrier format";
        return LazerAudioErrorUnsupported;
    }
    if (request.bitPerfect && source.dsd) {
        error = "bit-perfect PCM output cannot be used with DSD; "
            "convert DSD to PCM or disable bit-perfect";
        return LazerAudioErrorUnsupported;
    }
    if (request.bitPerfect && (!source.lossless || !source.integerPcm ||
        !source.canonicalChannelLayout || !source.decoderFormatMatchesStream ||
        (source.channels != 1 && source.channels != 2) || source.sampleRate <= 0 ||
        (source.bitsPerSample != 16 && source.bitsPerSample != 24 &&
            source.bitsPerSample != 32))) {
        error = "bit-perfect output needs mono/stereo lossless integer PCM at 16/24/32 bit";
        if (!source.lossless) error += "; decoder does not identify the codec as lossless";
        if (!source.integerPcm) error += "; decoder is not producing integer PCM";
        if (!source.canonicalChannelLayout) error += "; source channel layout is not canonical";
        if (!source.decoderFormatMatchesStream) {
            error += "; decoder sample format, depth, rate or channels do not match the stream";
        }
        if (source.channels != 1 && source.channels != 2) error += "; channel count is not mono/stereo";
        if (source.sampleRate <= 0) error += "; sample rate is invalid";
        if (source.bitsPerSample != 16 && source.bitsPerSample != 24 &&
            source.bitsPerSample != 32) error += "; valid bit depth is not 16/24/32";
        return LazerAudioErrorUnsupported;
    }
    exclusive_ = request.exclusive;

    HRESULT result = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (result == RPC_E_CHANGED_MODE) {
        error = "the audio thread is already initialised for a different COM model";
        return LazerAudioErrorDevice;
    }
    if (FAILED(result)) {
        error = "CoInitializeEx failed: 0x" + std::to_string(static_cast<long>(result));
        return LazerAudioErrorDevice;
    }
    comInitialised_ = true;

    IMMDeviceEnumerator *enumerator = nullptr;
    result = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
        __uuidof(IMMDeviceEnumerator), reinterpret_cast<void **>(&enumerator));
    if (FAILED(result)) {
        error = "could not create the audio device enumerator";
        close();
        return LazerAudioErrorDevice;
    }
    enumerator_ = enumerator;

    IMMDevice *device = nullptr;
    std::wstring deviceIdWide;
    if (!request.deviceId.empty() && !utf8ToWide(request.deviceId, deviceIdWide)) {
        error = "the selected audio device ID is not valid UTF-8";
        close();
        return LazerAudioErrorInvalidArgument;
    }
    if (!deviceIdWide.empty()) {
        result = enumerator->GetDevice(deviceIdWide.c_str(), &device);
    } else {
        result = enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, &device);
    }
    if (FAILED(result) || device == nullptr) {
        error = "the selected audio device is not present";
        close();
        return LazerAudioErrorDevice;
    }
    device_ = device;

    IAudioClient *audioClient = nullptr;
    result = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
        reinterpret_cast<void **>(&audioClient));
    if (FAILED(result) || audioClient == nullptr) {
        error = "the audio device refused to open (it may be held by another exclusive player)";
        close();
        return LazerAudioErrorDevice;
    }
    audioClient_ = audioClient;

    /* The shared mix format is the shared-mode default. Exclusive mode separately negotiates a
     * source-matching candidate; neither is a physical-DAC format readback. */
    WAVEFORMATEX *mixFormat = nullptr;
    result = audioClient->GetMixFormat(&mixFormat);
    if (FAILED(result) || mixFormat == nullptr) {
        error = "the audio device reports no mix format";
        close();
        return LazerAudioErrorDevice;
    }

    WAVEFORMATEX *chosen = nullptr;
    bool usingFloat = false;
    uint32_t chosenBits = 16;
    int32_t formatSelection = LazerAudioFormatSelectionUnknown;
    const AUDCLNT_SHAREMODE shareMode =
        request.exclusive ? AUDCLNT_SHAREMODE_EXCLUSIVE : AUDCLNT_SHAREMODE_SHARED;

    if (request.requireDoP) {
        WAVEFORMATEXTENSIBLE candidate{};
        fillExtensible(candidate, request.desired.sampleRate, request.desired.channels,
            24, false, 24);
        if (audioClient->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE,
            &candidate.Format, nullptr) == S_OK) {
            copyFormat(chosen, &candidate.Format);
            if (chosen != nullptr) {
                chosenBits = 24;
                usingFloat = false;
                formatSelection = LazerAudioFormatSelectionDoPCarrier;
            }
        }
        if (chosen == nullptr) {
            CoTaskMemFree(mixFormat);
            error = "the device does not accept the exact 24-bit DoP carrier rate and channel format";
            close();
            return LazerAudioErrorUnsupported;
        }
    } else if (request.exclusive) {
        if (source.lossless && source.integerPcm &&
            source.sampleRate > 0 && (source.channels == 1 || source.channels == 2) &&
            (source.bitsPerSample == 16 || source.bitsPerSample == 24 ||
                source.bitsPerSample == 32)) {
            /* Offer the stream's own format first: a USB DAC that accepts 96 kHz will then be driven
             * at 96 kHz with no resampling anywhere in the path. */
            auto tryIntegerFormat = [&](uint32_t containerBits) {
                WAVEFORMATEXTENSIBLE candidate{};
                fillExtensible(candidate, source.sampleRate, source.channels,
                    static_cast<uint32_t>(source.bitsPerSample), false, containerBits);
                const HRESULT supported = audioClient->IsFormatSupported(
                    AUDCLNT_SHAREMODE_EXCLUSIVE, &candidate.Format, nullptr);
                if (supported != S_OK) return false;
                copyFormat(chosen, &candidate.Format);
                if (chosen == nullptr) return false;
                chosenBits = static_cast<uint32_t>(source.bitsPerSample);
                usingFloat = false;
                formatSelection = LazerAudioFormatSelectionExclusiveSource;
                return true;
            };
            bool exactFormatAccepted = tryIntegerFormat(
                static_cast<uint32_t>(source.bitsPerSample));
            if (!exactFormatAccepted && source.bitsPerSample == 24) {
                /* Many DAC drivers expose 24 valid bits in a 32-bit physical container. */
                exactFormatAccepted = tryIntegerFormat(32);
            }
            if (!exactFormatAccepted && source.bitsPerSample == 16) {
                /* Microsoft recommends trying both extensible and legacy PCM descriptors for mono/stereo. */
                WAVEFORMATEX candidate{};
                candidate.wFormatTag = WAVE_FORMAT_PCM;
                candidate.nChannels = static_cast<WORD>(source.channels);
                candidate.nSamplesPerSec = static_cast<DWORD>(source.sampleRate);
                candidate.wBitsPerSample = 16;
                candidate.nBlockAlign = static_cast<WORD>(candidate.nChannels * 2);
                candidate.nAvgBytesPerSec = candidate.nSamplesPerSec * candidate.nBlockAlign;
                const HRESULT supported = audioClient->IsFormatSupported(
                    AUDCLNT_SHAREMODE_EXCLUSIVE, &candidate, nullptr);
                if (supported == S_OK) {
                    copyFormat(chosen, &candidate);
                    if (chosen != nullptr) {
                        chosenBits = 16;
                        usingFloat = false;
                        formatSelection = LazerAudioFormatSelectionExclusiveSource;
                        exactFormatAccepted = true;
                    }
                }
            }
        }
        if (chosen == nullptr && !request.bitPerfect && source.sampleRate > 0 &&
            (source.channels == 1 || source.channels == 2)) {
            /* If the exact source depth is unavailable, keep the source clock when possible and
             * let the normal DSP/converter adapt precision at the output boundary. */
            uint32_t depths[] = {32, 24, 16};
            if (source.bitsPerSample == 16 || source.bitsPerSample == 24 ||
                source.bitsPerSample == 32) {
                depths[0] = static_cast<uint32_t>(source.bitsPerSample);
                depths[1] = source.bitsPerSample == 32 ? 24 : 32;
                depths[2] = source.bitsPerSample == 16 ? 24 : 16;
            }
            uint32_t lastTried = 0;
            for (const uint32_t depth : depths) {
                if (depth == lastTried) continue;
                lastTried = depth;
                auto tryDepth = [&](uint32_t containerBits) {
                    WAVEFORMATEXTENSIBLE candidate{};
                    fillExtensible(candidate, source.sampleRate, source.channels,
                        depth, false, containerBits);
                    if (audioClient->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE,
                        &candidate.Format, nullptr) != S_OK) return false;
                    copyFormat(chosen, &candidate.Format);
                    if (chosen == nullptr) return false;
                    chosenBits = depth;
                    usingFloat = false;
                    formatSelection = LazerAudioFormatSelectionExclusiveSameRateAlternate;
                    return true;
                };
                if (tryDepth(depth)) break;
                if (depth == 24 && tryDepth(32)) break;
                if (depth == 16) {
                    WAVEFORMATEX candidate{};
                    candidate.wFormatTag = WAVE_FORMAT_PCM;
                    candidate.nChannels = static_cast<WORD>(source.channels);
                    candidate.nSamplesPerSec = static_cast<DWORD>(source.sampleRate);
                    candidate.wBitsPerSample = 16;
                    candidate.nBlockAlign = static_cast<WORD>(candidate.nChannels * 2);
                    candidate.nAvgBytesPerSec = candidate.nSamplesPerSec * candidate.nBlockAlign;
                    if (audioClient->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE,
                        &candidate, nullptr) == S_OK) {
                        copyFormat(chosen, &candidate);
                        if (chosen != nullptr) {
                            chosenBits = 16;
                            usingFloat = false;
                            formatSelection = LazerAudioFormatSelectionExclusiveSameRateAlternate;
                            break;
                        }
                    }
                }
            }
        }
        if (chosen == nullptr && !request.bitPerfect && source.sampleRate > 0 &&
            source.channels == 1) {
            /* A mono source can still use the endpoint's common stereo path. Keep its sample clock
             * when possible; the float resampler/mixer converts mono to stereo after selection. */
            const uint32_t depths[] = {32, 24, 16};
            for (const uint32_t depth : depths) {
                auto tryStereoFormat = [&](uint32_t containerBits) {
                    WAVEFORMATEXTENSIBLE candidate{};
                    fillExtensible(candidate, source.sampleRate, 2, depth, false, containerBits);
                    const bool accepted = tryExclusivePcmFormat(audioClient, &candidate.Format,
                        chosen, depth, chosenBits, usingFloat);
                    if (accepted) {
                        formatSelection = LazerAudioFormatSelectionExclusiveMonoToStereo;
                    }
                    return accepted;
                };
                if (tryStereoFormat(depth)) break;
                if (depth == 24 && tryStereoFormat(32)) break;
                if (depth == 16) {
                    WAVEFORMATEX candidate{};
                    candidate.wFormatTag = WAVE_FORMAT_PCM;
                    candidate.nChannels = 2;
                    candidate.nSamplesPerSec = static_cast<DWORD>(source.sampleRate);
                    candidate.wBitsPerSample = 16;
                    candidate.nBlockAlign = 4;
                    candidate.nAvgBytesPerSec = candidate.nSamplesPerSec * candidate.nBlockAlign;
                    if (tryExclusivePcmFormat(audioClient, &candidate, chosen,
                        16, chosenBits, usingFloat)) {
                        formatSelection = LazerAudioFormatSelectionExclusiveMonoToStereo;
                        break;
                    }
                }
            }
        }
        if (chosen == nullptr) {
            if (request.bitPerfect) {
                CoTaskMemFree(mixFormat);
                error = source.lossless
                    ? "the device does not support the source PCM format for bit-perfect output"
                    : "bit-perfect output is available only for lossless PCM sources";
                close();
                return LazerAudioErrorUnsupported;
            }
            /* If no source candidate is accepted, try the system mix format as an exclusive
             * fallback. Successful Initialize below proves only the WASAPI stream format. */
            const HRESULT nativeProbe = audioClient->IsFormatSupported(
                AUDCLNT_SHAREMODE_EXCLUSIVE, mixFormat, nullptr);
            if (nativeProbe == S_OK) {
                copyFormat(chosen, mixFormat);
                if (chosen != nullptr) {
                    chosenBits = significantBits(chosen);
                    usingFloat = isFloatSubtype(chosen);
                    formatSelection = LazerAudioFormatSelectionExclusiveMixFallback;
                }
            }

            if (chosen == nullptr) {
                /* A shared mix format is not necessarily legal in exclusive mode. Try common
                 * stereo PCM formats exactly, starting at the endpoint mix rate and then the
                 * source's 44.1/48 kHz clock family ordered by proximity. Only this non-bit-perfect
                 * path may change rate or channel count; the source is converted to float first. */
                constexpr uint32_t commonRates[] = {
                    44100, 48000, 88200, 96000, 176400, 192000,
                    352800, 384000, 705600, 768000
                };
                std::vector<uint32_t> fallbackRates;
                auto appendUniqueRate = [&](uint32_t rate) {
                    if (rate == 0 || std::find(fallbackRates.begin(), fallbackRates.end(), rate) !=
                        fallbackRates.end()) return;
                    fallbackRates.push_back(rate);
                };

                appendUniqueRate(mixFormat->nSamplesPerSec);
                std::vector<uint32_t> orderedCommonRates;
                const bool sourceUses441Family = source.sampleRate % 44100 == 0;
                for (const uint32_t rate : commonRates) {
                    if (rate == static_cast<uint32_t>(source.sampleRate) ||
                        rate == mixFormat->nSamplesPerSec) continue;
                    orderedCommonRates.push_back(rate);
                }
                std::stable_sort(orderedCommonRates.begin(), orderedCommonRates.end(),
                    [&](uint32_t left, uint32_t right) {
                        const bool leftSameFamily = (left % 44100 == 0) == sourceUses441Family;
                        const bool rightSameFamily = (right % 44100 == 0) == sourceUses441Family;
                        if (leftSameFamily != rightSameFamily) return leftSameFamily;
                        const uint64_t leftDistance = left > static_cast<uint32_t>(source.sampleRate)
                            ? left - static_cast<uint32_t>(source.sampleRate)
                            : static_cast<uint32_t>(source.sampleRate) - left;
                        const uint64_t rightDistance = right > static_cast<uint32_t>(source.sampleRate)
                            ? right - static_cast<uint32_t>(source.sampleRate)
                            : static_cast<uint32_t>(source.sampleRate) - right;
                        return leftDistance < rightDistance;
                    });
                for (const uint32_t rate : orderedCommonRates) appendUniqueRate(rate);

                uint32_t depths[] = {32, 24, 16};
                if (source.bitsPerSample == 16 || source.bitsPerSample == 24 ||
                    source.bitsPerSample == 32) {
                    depths[0] = static_cast<uint32_t>(source.bitsPerSample);
                    depths[1] = source.bitsPerSample == 32 ? 24 : 32;
                    depths[2] = source.bitsPerSample == 16 ? 24 : 16;
                }
                for (const uint32_t rate : fallbackRates) {
                    for (const uint32_t depth : depths) {
                        auto tryStereoFormat = [&](uint32_t containerBits) {
                            WAVEFORMATEXTENSIBLE candidate{};
                            fillExtensible(candidate, static_cast<int32_t>(rate), 2, depth,
                                false, containerBits);
                            const bool accepted = tryExclusivePcmFormat(audioClient,
                                &candidate.Format, chosen, depth, chosenBits, usingFloat);
                            if (accepted) {
                                formatSelection = LazerAudioFormatSelectionExclusiveCommonRateFallback;
                            }
                            return accepted;
                        };
                        if (tryStereoFormat(depth)) break;
                        if (depth == 24 && tryStereoFormat(32)) break;
                        if (depth == 16) {
                            WAVEFORMATEX candidate{};
                            candidate.wFormatTag = WAVE_FORMAT_PCM;
                            candidate.nChannels = 2;
                            candidate.nSamplesPerSec = rate;
                            candidate.wBitsPerSample = 16;
                            candidate.nBlockAlign = 4;
                            candidate.nAvgBytesPerSec = candidate.nSamplesPerSec * candidate.nBlockAlign;
                            if (tryExclusivePcmFormat(audioClient, &candidate, chosen,
                                16, chosenBits, usingFloat)) {
                                formatSelection = LazerAudioFormatSelectionExclusiveCommonRateFallback;
                                break;
                            }
                        }
                    }
                    if (chosen != nullptr) break;
                }
            }
            if (chosen == nullptr) {
                CoTaskMemFree(mixFormat);
                error = "the device accepts none of the tried exclusive PCM conversion formats";
                close();
                return LazerAudioErrorUnsupported;
            }
        }
    } else {
        copyFormat(chosen, mixFormat);
        chosenBits = significantBits(chosen);
        usingFloat = isFloatSubtype(chosen);
        formatSelection = LazerAudioFormatSelectionSharedMix;
    }
    const uint32_t validBits = significantBits(chosen);
    if (chosen == nullptr || (!isPcmSubtype(chosen) && !isFloatSubtype(chosen)) ||
        (isFloatSubtype(chosen) && chosen->wBitsPerSample != 32) ||
        (isPcmSubtype(chosen) && (chosen->wBitsPerSample < 8 || chosen->wBitsPerSample > 32 ||
            chosen->wBitsPerSample % 8 != 0 || validBits < 8 || validBits > chosen->wBitsPerSample ||
            chosen->nBlockAlign != chosen->nChannels * chosen->wBitsPerSample / 8))) {
        CoTaskMemFree(chosen);
        CoTaskMemFree(mixFormat);
        error = "the audio endpoint selected an unsupported encoded or floating-point container format";
        close();
        return LazerAudioErrorUnsupported;
    }
    if (request.requireDoP && (chosen->nSamplesPerSec !=
        static_cast<DWORD>(request.desired.sampleRate) ||
        chosen->nChannels != static_cast<WORD>(request.desired.channels) ||
        chosen->wBitsPerSample != 24 || significantBits(chosen) != 24 || isFloatSubtype(chosen))) {
        CoTaskMemFree(chosen);
        CoTaskMemFree(mixFormat);
        error = "WASAPI did not preserve the exact DoP carrier format";
        close();
        return LazerAudioErrorUnsupported;
    }
    CoTaskMemFree(mixFormat);
    ownedFormat_ = chosen;

    /* GetDevicePeriod reports 100-nanosecond units, which is what Initialize wants, so the periods are
     * read as REFERENCE_TIME rather than a frame count. */
    REFERENCE_TIME defaultPeriod = 0;
    REFERENCE_TIME minimumPeriod = 0;
    audioClient->GetDevicePeriod(&defaultPeriod, &minimumPeriod);
    uint64_t periodHundredNanos = std::max<uint64_t>(
        std::max<uint64_t>(defaultPeriod, minimumPeriod), 10000);

    /* Event-driven shared streams require both durations to be zero. Exclusive event streams need
     * equal non-zero durations; use the endpoint's supported period for stable DAC handoff. */
    constexpr DWORD streamFlags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
    const REFERENCE_TIME exclusivePeriod = static_cast<REFERENCE_TIME>(periodHundredNanos);
    const REFERENCE_TIME bufferDuration = request.exclusive ? exclusivePeriod : 0;
    const REFERENCE_TIME periodicity = request.exclusive ? exclusivePeriod : 0;

    result = audioClient->Initialize(shareMode, streamFlags, bufferDuration, periodicity, chosen, nullptr);
    if (result == AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED && request.exclusive) {
        UINT32 alignedFrames = 0;
        HRESULT alignedResult = audioClient->GetBufferSize(&alignedFrames);
        if (SUCCEEDED(alignedResult) && alignedFrames > 0) {
            const REFERENCE_TIME alignedDuration = static_cast<REFERENCE_TIME>(
                (10'000'000.0 * static_cast<double>(alignedFrames) /
                    static_cast<double>(chosen->nSamplesPerSec)) + 0.5);
            /* An Initialize attempt that returns BUFFER_SIZE_NOT_ALIGNED may leave this client
             * unusable. Microsoft requires a fresh IAudioClient for the aligned retry. */
            audioClient->Release();
            audioClient_ = nullptr;
            audioClient = nullptr;
            alignedResult = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                reinterpret_cast<void **>(&audioClient));
            if (SUCCEEDED(alignedResult) && audioClient != nullptr) {
                audioClient_ = audioClient;
                result = audioClient->Initialize(shareMode, streamFlags, alignedDuration,
                    alignedDuration, chosen, nullptr);
                if (SUCCEEDED(result)) periodHundredNanos = static_cast<uint64_t>(alignedDuration);
            } else {
                result = FAILED(alignedResult) ? alignedResult : E_FAIL;
            }
        } else {
            result = FAILED(alignedResult) ? alignedResult : E_FAIL;
        }
    }
    if (FAILED(result)) {
        error = request.exclusive
            ? "the device rejected exclusive initialization (HRESULT " + hresultText(result) + ")"
            : "the shared endpoint rejected this format (HRESULT " + hresultText(result) + ")";
        close();
        return LazerAudioErrorDevice;
    }
    devicePeriodHundredNanos_ = periodHundredNanos;

    UINT32 bufferFrames = 0;
    result = audioClient->GetBufferSize(&bufferFrames);
    if (FAILED(result) || bufferFrames == 0) {
        error = "the audio endpoint reported no buffer";
        close();
        return LazerAudioErrorDevice;
    }
    bufferFrames_ = bufferFrames;
    periodFrames_ = static_cast<UINT32>(
        static_cast<uint64_t>(chosen->nSamplesPerSec) * periodHundredNanos / 10'000'000ULL);
    if (periodFrames_ == 0) periodFrames_ = 1;

    /* Auto-reset leaves the event nonsignaled after each wake, preventing a busy render loop. */
    eventHandle_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (eventHandle_ == nullptr) {
        error = "could not create the audio period event";
        close();
        return LazerAudioErrorDevice;
    }
    result = audioClient->SetEventHandle(static_cast<HANDLE>(eventHandle_));
    if (FAILED(result)) {
        error = "the audio endpoint does not support event signalling";
        close();
        return LazerAudioErrorDevice;
    }

    IAudioRenderClient *renderClient = nullptr;
    result = audioClient->GetService(__uuidof(IAudioRenderClient),
        reinterpret_cast<void **>(&renderClient));
    if (FAILED(result) || renderClient == nullptr) {
        error = "could not reach the render client";
        close();
        return LazerAudioErrorDevice;
    }
    renderClient_ = renderClient;

    TargetFormat target;
    target.sampleRate = static_cast<int32_t>(chosen->nSamplesPerSec);
    target.channels = chosen->nChannels;
    target.bitsPerSample = usingFloat ? 0 : static_cast<int32_t>(chosenBits);
    target.containerBitsPerSample = chosen->wBitsPerSample;
    session.target = target;
    session.engineFormat = PcmFormat{target.sampleRate, target.channels};
    session.formatSelection = formatSelection;
    session.periodFrames = static_cast<int32_t>(periodFrames_);
    session.bufferFrames = static_cast<int32_t>(bufferFrames_);
    session.description = (request.exclusive ? "WASAPI 独占 " : "WASAPI 共享 ") +
        std::to_string(target.sampleRate) + " Hz / " +
        std::to_string(target.bitsPerSample == 0 ? 32 : target.bitsPerSample) + " bit / " +
        std::to_string(target.channels) + " 声道";
    session.exclusive = request.exclusive;
    session.doP = request.requireDoP;
    session.writeMode = request.exclusive ? OutputWriteMode::FixedPeriod : OutputWriteMode::Variable;
    session.primeBeforeStart = request.exclusive;
    session.queueDepthAvailable = !request.exclusive;
    session_ = session;
    fixedPeriodPending_.store(false, std::memory_order_release);
    submittedFrames_.store(0, std::memory_order_release);

    /* The pump produces float32 for the DSP chain; a non-float device format only differs in the last
     * hop, which the engine performs when it fills the ring. */
    if (log != nullptr) log->write(LazerAudioLogInfo, session.description);
    return LazerAudioOk;
}

int32_t WasapiOutput::start(std::string &error) {
    if (audioClient_ == nullptr) return LazerAudioErrorState;
    if (running_) return LazerAudioOk;
    const HRESULT result = static_cast<IAudioClient *>(audioClient_)->Start();
    if (FAILED(result)) {
        error = "the audio device did not start";
        return LazerAudioErrorDevice;
    }
    running_ = true;
    return LazerAudioOk;
}

int32_t WasapiOutput::stop(std::string &error) {
    if (audioClient_ == nullptr) return LazerAudioOk;
    const HRESULT result = static_cast<IAudioClient *>(audioClient_)->Stop();
    if (FAILED(result)) error = "the audio device did not stop";
    running_ = false;
    return LazerAudioOk;
}

int32_t WasapiOutput::reset(std::string &error) {
    if (audioClient_ == nullptr) return LazerAudioErrorState;
    const HRESULT stopped = static_cast<IAudioClient *>(audioClient_)->Stop();
    const HRESULT result = static_cast<IAudioClient *>(audioClient_)->Reset();
    if (FAILED(result)) {
        error = "the audio device could not be reset";
        return LazerAudioErrorDevice;
    }
    (void)stopped;
    running_ = false;
    fixedPeriodPending_.store(false, std::memory_order_release);
    submittedFrames_.store(0, std::memory_order_release);
    return LazerAudioOk;
}

int32_t WasapiOutput::enterRenderThread(std::string &error) {
    const HRESULT result = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (result == RPC_E_CHANGED_MODE) {
        error = "the render thread is already initialised for a different COM model";
        return LazerAudioErrorDevice;
    }
    if (FAILED(result)) {
        error = "render-thread CoInitializeEx failed: " + hresultText(result);
        return LazerAudioErrorDevice;
    }
    renderThreadComInitialised_ = true;
    return LazerAudioOk;
}

void WasapiOutput::leaveRenderThread() noexcept {
    if (!renderThreadComInitialised_) return;
    CoUninitialize();
    renderThreadComInitialised_ = false;
}

OutputWaitResult WasapiOutput::waitForReady(int32_t timeoutMillis, std::string &error) {
    if (eventHandle_ == nullptr) {
        error = "the audio period event is not available";
        return OutputWaitResult::Error;
    }
    const DWORD waitResult = WaitForSingleObject(
        static_cast<HANDLE>(eventHandle_), static_cast<DWORD>(std::max(timeoutMillis, 0)));
    if (waitResult == WAIT_TIMEOUT) return OutputWaitResult::Timeout;
    if (waitResult != WAIT_OBJECT_0) {
        error = "the audio period event failed";
        return OutputWaitResult::Error;
    }
    /* In exclusive event mode the next period signal retires the one complete packet submitted
     * for the previous period. Shared mode uses padding instead. */
    if (session_.writeMode == OutputWriteMode::FixedPeriod) {
        fixedPeriodPending_.store(false, std::memory_order_release);
    }
    return OutputWaitResult::Ready;
}

int32_t WasapiOutput::writableFrames(std::string &error) {
    if (audioClient_ == nullptr) return LazerAudioErrorState;
    if (session_.writeMode == OutputWriteMode::FixedPeriod) {
        return static_cast<int32_t>(bufferFrames_);
    }
    UINT32 padding = 0;
    if (FAILED(static_cast<IAudioClient *>(audioClient_)->GetCurrentPadding(&padding))) {
        error = "the audio device stopped answering";
        return LazerAudioErrorDevice;
    }
    const UINT32 available = bufferFrames_ > padding ? bufferFrames_ - padding : 0;
    return static_cast<int32_t>(available);
}

int32_t WasapiOutput::queuedFrames(std::string &error) {
    if (audioClient_ == nullptr) return LazerAudioErrorState;
    if (!session_.queueDepthAvailable) return 0;
    UINT32 padding = 0;
    if (FAILED(static_cast<IAudioClient *>(audioClient_)->GetCurrentPadding(&padding))) {
        error = "the audio device stopped answering";
        return LazerAudioErrorDevice;
    }
    return static_cast<int32_t>(padding);
}

OutputDrainResult WasapiOutput::drain(std::string &error) {
    if (audioClient_ == nullptr) {
        error = "the audio device is not open";
        return OutputDrainResult::Error;
    }
    if (session_.writeMode == OutputWriteMode::FixedPeriod) {
        return fixedPeriodPending_.load(std::memory_order_acquire)
            ? OutputDrainResult::Pending : OutputDrainResult::Drained;
    }
    const int32_t queued = queuedFrames(error);
    if (queued < 0) return OutputDrainResult::Error;
    return queued == 0 ? OutputDrainResult::Drained : OutputDrainResult::Pending;
}

int32_t WasapiOutput::write(const uint8_t *bytes, int32_t frameCount, std::string &error) {
    if (audioClient_ == nullptr || renderClient_ == nullptr) return LazerAudioErrorState;
    auto *client = static_cast<IAudioClient *>(audioClient_);
    auto *render = static_cast<IAudioRenderClient *>(renderClient_);

    UINT32 toCopy = 0;
    if (session_.writeMode == OutputWriteMode::FixedPeriod) {
        if (frameCount != static_cast<int32_t>(bufferFrames_)) {
            error = "fixed-period streams require one complete endpoint buffer per write";
            return LazerAudioErrorInvalidArgument;
        }
        toCopy = bufferFrames_;
    } else {
        UINT32 padding = 0;
        if (FAILED(client->GetCurrentPadding(&padding))) {
            error = "the audio device stopped answering";
            return LazerAudioErrorDevice;
        }
        const UINT32 available = bufferFrames_ > padding ? bufferFrames_ - padding : 0;
        toCopy = std::min<UINT32>(available, static_cast<UINT32>(std::max(frameCount, 0)));
    }
    if (toCopy == 0) return 0;

    BYTE *destination = nullptr;
    HRESULT result = render->GetBuffer(toCopy, &destination);
    if (FAILED(result) || destination == nullptr) {
        error = "the render client ran out of buffer";
        return LazerAudioErrorDevice;
    }
    std::memcpy(destination, bytes, static_cast<size_t>(toCopy) * session_.target.frameBytes());
    result = render->ReleaseBuffer(toCopy, 0);
    if (FAILED(result)) {
        /* The packet is already handed back, so it must not be released a second time; reporting the
         * failure lets the engine reopen the endpoint instead. */
        error = "the render client refused the packet";
        return LazerAudioErrorDevice;
    }
    submittedFrames_.fetch_add(toCopy, std::memory_order_release);
    if (session_.writeMode == OutputWriteMode::FixedPeriod) {
        fixedPeriodPending_.store(true, std::memory_order_release);
    }
    return static_cast<int32_t>(toCopy);
}

void WasapiOutput::releaseCom() {
    if (renderClient_ != nullptr) {
        static_cast<IAudioRenderClient *>(renderClient_)->Release();
        renderClient_ = nullptr;
    }
    if (audioClient_ != nullptr) {
        static_cast<IAudioClient *>(audioClient_)->Release();
        audioClient_ = nullptr;
    }
    if (device_ != nullptr) {
        static_cast<IMMDevice *>(device_)->Release();
        device_ = nullptr;
    }
    if (enumerator_ != nullptr) {
        static_cast<IMMDeviceEnumerator *>(enumerator_)->Release();
        enumerator_ = nullptr;
    }
    if (eventHandle_ != nullptr) {
        CloseHandle(static_cast<HANDLE>(eventHandle_));
        eventHandle_ = nullptr;
    }
    if (ownedFormat_ != nullptr) {
        CoTaskMemFree(ownedFormat_);
        ownedFormat_ = nullptr;
    }
    if (comInitialised_) {
        CoUninitialize();
        comInitialised_ = false;
    }
}

void WasapiOutput::close() {
    if (audioClient_ != nullptr && running_) {
        static_cast<IAudioClient *>(audioClient_)->Stop();
    }
    running_ = false;
    exclusive_ = false;
    fixedPeriodPending_.store(false, std::memory_order_release);
    releaseCom();
    bufferFrames_ = 0;
    periodFrames_ = 0;
    devicePeriodHundredNanos_ = 0;
    session_ = AudioOutputSession{};
    submittedFrames_.store(0, std::memory_order_release);
}

std::unique_ptr<AudioOutput> createPlatformAudioOutput() {
    return std::make_unique<WasapiOutput>();
}

}  // namespace lazer::audio
