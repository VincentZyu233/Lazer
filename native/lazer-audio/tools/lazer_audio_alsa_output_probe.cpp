/* Hardware-free checks for ALSA output negotiation and queue lifecycle. The `null` PCM is enabled
 * only when both explicit CMake test options are on; release builds accept direct hw: names only. */
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "output_alsa.h"
#include "output_alsa_recovery.h"

extern "C" int32_t lazer_audio_test_alsa_dop_null_negotiation(int32_t carrierRate,
    int32_t channels, int32_t *actualRate, int32_t *actualChannels,
    int32_t *validBits, int32_t *containerBits);
extern "C" int32_t lazer_audio_test_alsa_dop_stage_bytes(const uint8_t *bytes,
    int32_t frameCount, int32_t channels, uint8_t *destination, int32_t destinationBytes);
extern "C" int32_t lazer_audio_test_alsa_dop_suspend_resume(int32_t alsaError,
    int32_t retryCount, int32_t finalResumeResult, int32_t *resumeCalls, int32_t *waitCalls);

namespace {

bool fail(const std::string &message) {
    std::fprintf(stderr, "FAIL: %s\n", message.c_str());
    return false;
}

bool exerciseFormat(int32_t bitsPerSample) {
    lazer::audio::AudioOutputRequest request;
    request.deviceId = "null";
    request.exclusive = true;
    request.bufferMillis = 120;
    request.bitPerfect = true;
    request.desired.sampleRate = 44'100;
    request.desired.channels = 2;

    lazer::audio::StreamDescription source;
    source.sampleRate = 44'100;
    source.channels = 2;
    source.bitsPerSample = bitsPerSample;
    source.lossless = true;
    source.integerPcm = true;
    source.canonicalChannelLayout = true;
    source.decoderFormatMatchesStream = true;

    lazer::audio::AlsaOutput output;
    lazer::audio::AudioOutputSession session;
    std::string error;
    const int32_t opened = output.open(request, source, session, error, nullptr);
    if (opened != LazerAudioOk) {
        return fail("could not negotiate " + std::to_string(bitsPerSample) + "-bit ALSA null PCM: " + error);
    }
    if (!output.isOpen() || session.target.sampleRate != 44'100 ||
        session.target.channels != 2 || session.target.bitsPerSample != bitsPerSample ||
        (bitsPerSample != 24 && session.target.containerBitsPerSample != bitsPerSample) ||
        (bitsPerSample == 24 && session.target.containerBitsPerSample != 24 &&
            session.target.containerBitsPerSample != 32)) {
        return fail("ALSA readback did not match the requested " +
            std::to_string(bitsPerSample) + "-bit PCM format");
    }
    if (session.exclusive || session.description.find("ALSA null") == std::string::npos) {
        return fail("the null PCM was incorrectly reported as a direct exclusive hardware endpoint");
    }

    if (output.start(error) != LazerAudioOk) {
        return fail("could not start ALSA null PCM: " + error);
    }
    const int32_t writable = output.writableFrames(error);
    if (writable <= 0) return fail("ALSA null PCM reported no writable frames: " + error);
    const int32_t frames = std::min({writable, session.periodFrames, 32});
    const int32_t frameBytes = session.target.frameBytes();
    if (frames <= 0 || frameBytes <= 0) return fail("ALSA negotiated invalid output queue dimensions");
    std::vector<uint8_t> silence(static_cast<size_t>(frames) * static_cast<size_t>(frameBytes), 0);
    const int32_t written = output.write(silence.data(), frames, error);
    if (written != frames) return fail("ALSA null PCM accepted only a partial test block: " + error);

    const auto readiness = output.waitForReady(0, error);
    if (readiness == lazer::audio::OutputWaitResult::Error) {
        return fail("ALSA null PCM readiness query failed: " + error);
    }
    const int32_t queued = output.queuedFrames(error);
    if (queued < 0) return fail("ALSA null PCM queue query failed: " + error);

    bool drained = false;
    for (int attempt = 0; attempt < 50; ++attempt) {
        error.clear();
        const auto result = output.drain(error);
        if (result == lazer::audio::OutputDrainResult::Error) {
            return fail("ALSA null PCM drain query failed: " + error);
        }
        if (result == lazer::audio::OutputDrainResult::Drained) {
            drained = true;
            break;
        }
        if (output.waitForReady(10, error) == lazer::audio::OutputWaitResult::Error) {
            return fail("ALSA null PCM readiness query failed while draining: " + error);
        }
    }
    if (!drained) return fail("ALSA null PCM did not drain the accepted test frames");

    if (output.stop(error) != LazerAudioOk) return fail("ALSA null PCM pause/stop failed: " + error);
    if (output.start(error) != LazerAudioOk) return fail("ALSA null PCM resume failed: " + error);
    if (output.reset(error) != LazerAudioOk) return fail("ALSA null PCM reset failed: " + error);
    if (output.drain(error) != lazer::audio::OutputDrainResult::Drained) {
        return fail("ALSA null PCM queue was not empty after reset: " + error);
    }
    output.close();
    if (output.isOpen()) return fail("ALSA null PCM remained open after close");

    std::printf("PASS: ALSA null PCM %d-bit negotiation, write, pause/resume, reset and drain\n",
        bitsPerSample);
    return true;
}

lazer::audio::AudioOutputRequest exactDopRequest(const std::string &device) {
    lazer::audio::AudioOutputRequest request;
    request.deviceId = device;
    request.requireDoP = true;
    request.exclusive = true;
    request.bufferMillis = 120;
    request.desired.sampleRate = 176'400;
    request.desired.channels = 2;
    request.desired.bitsPerSample = 24;
    request.desired.containerBitsPerSample = 24;
    return request;
}

lazer::audio::StreamDescription rawDsdDescription() {
    lazer::audio::StreamDescription source;
    source.sampleRate = 352'800;
    source.channels = 2;
    source.dsd = true;
    source.rawDsd = true;
    source.dsdRateMultiplier = 64;
    source.lossless = true;
    return source;
}

bool exerciseDopExactCarrierNegotiation() {
    for (const int32_t channels : {1, 2}) {
        int32_t actualRate = 0;
        int32_t actualChannels = 0;
        int32_t validBits = 0;
        int32_t containerBits = 0;
        const int32_t negotiated = lazer_audio_test_alsa_dop_null_negotiation(
            176'400, channels, &actualRate, &actualChannels, &validBits, &containerBits);
        if (negotiated != LazerAudioOk || actualRate != 176'400 || actualChannels != channels ||
            validBits != 24 || containerBits != 24) {
            return fail("test-only ALSA null route did not accept exact 176.4 kHz " +
                std::to_string(channels) + "-channel S24_3LE DoP carrier params");
        }
    }

    /* Public DoP opens are deliberately direct-hw-only. Exercise null/plughw rejection through
     * the real backend and confirm neither name can enter PCM fallback negotiation. */
    for (const char *device : {"null", "plughw:0,0"}) {
        auto request = exactDopRequest(device);
        lazer::audio::AlsaOutput output;
        lazer::audio::AudioOutputSession session;
        std::string error;
        const int32_t opened = output.open(request, rawDsdDescription(), session, error, nullptr);
        if (opened != LazerAudioErrorInvalidArgument || output.isOpen() ||
            error.find("direct hw:") == std::string::npos) {
            return fail("DoP public open accepted a plugin/null endpoint or entered fallback for " +
                std::string(device));
        }
    }

    std::puts("PASS: ALSA DoP exact S24_3LE carrier tuple and direct-hw-only fallback rejection");
    return true;
}

bool exerciseDopMarkerBytesPassThrough() {
    const std::vector<uint8_t> dopFrames{
        0x11, 0x22, 0x05, 0xA1, 0xB2, 0x05,
        0x33, 0x44, 0xFA, 0xC3, 0xD4, 0xFA,
        0x55, 0x66, 0x05, 0xE5, 0xF6, 0x05,
    };
    std::vector<uint8_t> staged(dopFrames.size());
    const int32_t copied = lazer_audio_test_alsa_dop_stage_bytes(
        dopFrames.data(), 3, 2, staged.data(), static_cast<int32_t>(staged.size()));
    if (copied != 3 || staged != dopFrames) {
        return fail("ALSA DoP staging changed payload or synchronized marker bytes");
    }
    std::puts("PASS: ALSA DoP staging preserves carrier payload and marker bytes verbatim");
    return true;
}

bool exerciseDopRecoveryFailsWhenMarkerPhaseIsUnknown() {
    int32_t resumeCalls = -1;
    int32_t waitCalls = -1;
    if (lazer_audio_test_alsa_dop_suspend_resume(-EPIPE, 0, 0,
            &resumeCalls, &waitCalls) != 0 || resumeCalls != 0 || waitCalls != 0) {
        return fail("ALSA DoP XRUN did not fail immediately without preparing/replaying the queue");
    }
    if (lazer_audio_test_alsa_dop_suspend_resume(-ESTRPIPE, 0, 0,
            &resumeCalls, &waitCalls) != 1 || resumeCalls != 1 || waitCalls != 0) {
        return fail("ALSA DoP successful suspend resume did not preserve its existing queue");
    }
    if (lazer_audio_test_alsa_dop_suspend_resume(-ESTRPIPE, 2, 0,
            &resumeCalls, &waitCalls) != 1 || resumeCalls != 3 || waitCalls != 2) {
        return fail("ALSA DoP suspend resume did not wait through EAGAIN before retaining the queue");
    }
    if (lazer_audio_test_alsa_dop_suspend_resume(-ESTRPIPE, 0, -ENOSYS,
            &resumeCalls, &waitCalls) != 0 || resumeCalls != 1 || waitCalls != 0) {
        return fail("ALSA DoP failed suspend resume incorrectly fell back to prepare/requeue");
    }
    const int retryCount = static_cast<int>(lazer::audio::kAlsaSuspendResumeMaxWaitIntervals + 2);
    if (lazer_audio_test_alsa_dop_suspend_resume(-ESTRPIPE, retryCount, 0,
            &resumeCalls, &waitCalls) != 0 ||
        resumeCalls != static_cast<int>(lazer::audio::kAlsaSuspendResumeMaxWaitIntervals + 1) ||
        waitCalls != static_cast<int>(lazer::audio::kAlsaSuspendResumeMaxWaitIntervals)) {
        return fail("ALSA DoP suspend resume did not stop after its bounded EAGAIN retry window");
    }
    std::puts("PASS: ALSA DoP recovery stops on XRUN, failed resume, and bounded EAGAIN while preserving successful queue");
    return true;
}

}  // namespace

int main() {
    bool passed = true;
    passed &= exerciseFormat(16);
    passed &= exerciseFormat(24);
    passed &= exerciseFormat(32);
    passed &= exerciseDopExactCarrierNegotiation();
    passed &= exerciseDopMarkerBytesPassThrough();
    passed &= exerciseDopRecoveryFailsWhenMarkerPhaseIsUnknown();
    return passed ? 0 : 1;
}
