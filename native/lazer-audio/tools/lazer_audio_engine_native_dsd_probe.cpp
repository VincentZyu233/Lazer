/* Offline Engine-level regression for the Linux Native DSD session contract. A fake endpoint
 * verifies the exact direct request and initialized DSD clock without requiring ALSA hardware. */
#include "engine.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <memory>
#include <mutex>
#include <thread>
#include <utility>
#include <vector>

namespace {

using namespace lazer::audio;

constexpr int32_t kDsdByteClock = 352'800; // DSD64 / 8
constexpr int32_t kNativeDsdRate = 88'200; // U32 words at the DSD byte clock
constexpr int32_t kStereo = 2;
constexpr int32_t kNativeFrameBytes = kStereo * 4;
constexpr int32_t kDsfBlockBytes = 4096;
constexpr std::array<uint8_t, kNativeFrameBytes> kExpectedFirstFrame{
    0xAA, 0x55, 0xAA, 0x55, 0x33, 0xCC, 0x33, 0xCC,
};

void append16(std::vector<uint8_t> &bytes, uint16_t value) {
    bytes.push_back(static_cast<uint8_t>(value));
    bytes.push_back(static_cast<uint8_t>(value >> 8));
}

void append32(std::vector<uint8_t> &bytes, uint32_t value) {
    for (int shift = 0; shift < 32; shift += 8) {
        bytes.push_back(static_cast<uint8_t>(value >> shift));
    }
}

void append64(std::vector<uint8_t> &bytes, uint64_t value) {
    for (int shift = 0; shift < 64; shift += 8) {
        bytes.push_back(static_cast<uint8_t>(value >> shift));
    }
}

std::vector<uint8_t> makeStereoDsf(uint64_t durationMillis = 1000) {
    const uint64_t bitCount = static_cast<uint64_t>(kDsdByteClock) * 8 * durationMillis / 1000;
    const uint64_t audioBytesPerChannel = (bitCount + 7) / 8;
    const uint64_t blocks = (audioBytesPerChannel + kDsfBlockBytes - 1) / kDsfBlockBytes;
    const uint64_t paddedBytesPerChannel = blocks * kDsfBlockBytes;
    const uint64_t payloadBytes = paddedBytesPerChannel * kStereo;
    const uint64_t dataChunkBytes = 12 + payloadBytes;
    const uint64_t fileBytes = 28 + 52 + dataChunkBytes;

    std::vector<uint8_t> bytes;
    bytes.reserve(static_cast<size_t>(fileBytes));
    bytes.insert(bytes.end(), {'D', 'S', 'D', ' '});
    append64(bytes, 28);
    append64(bytes, fileBytes);
    append64(bytes, 0);
    bytes.insert(bytes.end(), {'f', 'm', 't', ' '});
    append64(bytes, 52);
    append32(bytes, 1); // format version
    append32(bytes, 0); // raw DSD
    append32(bytes, 2); // stereo channel type
    append32(bytes, kStereo);
    append32(bytes, kDsdByteClock * 8); // DSD bit rate in DSF metadata
    append32(bytes, 8); // MSB first
    append64(bytes, bitCount);
    append32(bytes, kDsfBlockBytes);
    append32(bytes, 0);
    bytes.insert(bytes.end(), {'d', 'a', 't', 'a'});
    append64(bytes, dataChunkBytes);

    std::array<std::vector<uint8_t>, kStereo> channels;
    for (int32_t channel = 0; channel < kStereo; ++channel) {
        auto &samples = channels[static_cast<size_t>(channel)];
        samples.resize(static_cast<size_t>(paddedBytesPerChannel), 0x69);
        for (uint64_t index = 0; index < audioBytesPerChannel; ++index) {
            const uint8_t even = channel == 0 ? 0xAA : 0x33;
            const uint8_t odd = channel == 0 ? 0x55 : 0xCC;
            samples[static_cast<size_t>(index)] = ((index & 1u) == 0) ? even : odd;
        }
    }
    for (uint64_t block = 0; block < blocks; ++block) {
        const size_t offset = static_cast<size_t>(block * kDsfBlockBytes);
        for (int32_t channel = 0; channel < kStereo; ++channel) {
            const auto &samples = channels[static_cast<size_t>(channel)];
            bytes.insert(bytes.end(), samples.begin() + static_cast<std::ptrdiff_t>(offset),
                samples.begin() + static_cast<std::ptrdiff_t>(offset + kDsfBlockBytes));
        }
    }
    return bytes;
}

struct MemoryReader {
    explicit MemoryReader(std::vector<uint8_t> input) : bytes(std::move(input)) {}
    std::vector<uint8_t> bytes;
    int64_t position = 0;
};

int32_t readBytes(void *opaque, uint8_t *destination, int32_t count) {
    auto *reader = static_cast<MemoryReader *>(opaque);
    if (reader == nullptr || destination == nullptr || count <= 0) return LAZER_AUDIO_READER_IO_ERROR;
    const int64_t remaining = static_cast<int64_t>(reader->bytes.size()) - reader->position;
    if (remaining <= 0) return LAZER_AUDIO_READER_EOF;
    const int32_t copied = static_cast<int32_t>(std::min<int64_t>(remaining, count));
    std::memcpy(destination, reader->bytes.data() + reader->position, static_cast<size_t>(copied));
    reader->position += copied;
    return copied;
}

int64_t seekBytes(void *opaque, int64_t position) {
    auto *reader = static_cast<MemoryReader *>(opaque);
    if (reader == nullptr || position < 0 || position > static_cast<int64_t>(reader->bytes.size())) return -1;
    reader->position = position;
    return position;
}

int64_t readerLength(void *opaque) {
    const auto *reader = static_cast<const MemoryReader *>(opaque);
    return reader == nullptr ? -1 : static_cast<int64_t>(reader->bytes.size());
}

LazerAudioReader asReader(MemoryReader &reader) {
    return LazerAudioReader{readBytes, seekBytes, readerLength, nullptr, &reader, nullptr};
}

struct Capture {
    std::mutex mutex;
    std::condition_variable changed;
    AudioOutputRequest request{};
    AudioOutputSession session{};
    std::vector<uint8_t> bytes;
    int32_t openCount = 0;
    int32_t writeCount = 0;
    bool invalidWrite = false;
};

class NativeDsdCaptureOutput final : public AudioOutput {
public:
    NativeDsdCaptureOutput(std::shared_ptr<Capture> capture, bool wrongClock)
        : capture_(std::move(capture)), wrongClock_(wrongClock) {}

    int32_t open(const AudioOutputRequest &request, const StreamDescription &source,
        AudioOutputSession &session, std::string &error, LogProxy *) override {
        std::lock_guard guard(capture_->mutex);
        capture_->request = request;
        ++capture_->openCount;
        if (!request.requireNativeDsd || !request.exclusive || request.bitPerfect ||
            !request.desired.nativeDsd || request.desired.doP || !source.dsd || !source.rawDsd ||
            source.sampleRate != kDsdByteClock || source.channels != kStereo) {
            error = "engine did not request direct Native DSD for raw stereo DSD";
            return LazerAudioErrorUnsupported;
        }

        TargetFormat target;
        target.sampleRate = wrongClock_ ? kNativeDsdRate / 2 : kNativeDsdRate;
        target.channels = kStereo;
        target.bitsPerSample = 32;
        target.containerBitsPerSample = 32;
        target.nativeDsd = true;
        target.nativeDsdBigEndian = true;
        session.target = target;
        session.engineFormat = PcmFormat{target.sampleRate, target.channels};
        session.formatSelection = LazerAudioFormatSelectionNativeDsdU32Be;
        session.periodFrames = 128;
        session.bufferFrames = 512;
        session.writeMode = OutputWriteMode::Variable;
        session.exclusive = true;
        session.queueDepthAvailable = true;
        session.nativeDsd = true;
        capture_->session = session;
        open_ = true;
        return LazerAudioOk;
    }

    void close() override { open_.store(false, std::memory_order_release); }
    int32_t start(std::string &) override { return open_ ? LazerAudioOk : LazerAudioErrorState; }
    int32_t stop(std::string &) override { return LazerAudioOk; }
    int32_t reset(std::string &) override { return LazerAudioOk; }

    OutputWaitResult waitForReady(int32_t, std::string &) override {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
        return OutputWaitResult::Ready;
    }

    int32_t writableFrames(std::string &) override { return 32; }
    int32_t queuedFrames(std::string &) override { return 0; }
    OutputDrainResult drain(std::string &) override { return OutputDrainResult::Drained; }

    int32_t write(const uint8_t *bytes, int32_t frames, std::string &) override {
        if (!open_ || bytes == nullptr || frames <= 0) {
            std::lock_guard guard(capture_->mutex);
            capture_->invalidWrite = true;
            return LazerAudioErrorDevice;
        }
        std::lock_guard guard(capture_->mutex);
        capture_->bytes.insert(capture_->bytes.end(), bytes,
            bytes + static_cast<size_t>(frames) * kNativeFrameBytes);
        ++capture_->writeCount;
        capture_->changed.notify_all();
        return frames;
    }

    [[nodiscard]] bool isOpen() const noexcept override {
        return open_.load(std::memory_order_acquire);
    }

private:
    std::shared_ptr<Capture> capture_;
    bool wrongClock_ = false;
    std::atomic<bool> open_{false};
};

bool hasExpectedDsdPayload(const Capture &capture) {
    return std::search(capture.bytes.begin(), capture.bytes.end(),
        kExpectedFirstFrame.begin(), kExpectedFirstFrame.end()) != capture.bytes.end();
}

bool waitForNativePayload(const std::shared_ptr<Capture> &capture) {
    std::unique_lock guard(capture->mutex);
    return capture->changed.wait_for(guard, std::chrono::seconds(5), [&capture] {
        return hasExpectedDsdPayload(*capture);
    });
}

int fail(const char *message) {
    std::cerr << "FAIL: " << message << '\n';
    return 1;
}

int runNativeDsdCase() {
    MemoryReader reader(makeStereoDsf());
    const LazerAudioReader input = asReader(reader);
    auto capture = std::make_shared<Capture>();

    LazerAudioEngineConfig config{};
    config.abi_version = LAZER_AUDIO_ABI_VERSION;
    config.struct_size = sizeof(config);
    config.device.dsd_output_mode = LazerAudioDsdOutputRequireNative;
    config.device.buffer_millis = 120;
    Engine *engine = Engine::create(config,
        std::make_unique<NativeDsdCaptureOutput>(capture, false));
    if (engine == nullptr) return fail("Native DSD engine creation failed");

    const LazerAudioOpenParams params{sizeof(LazerAudioOpenParams), 0, 0};
    const int32_t opened = engine->open(nullptr, &input, params);
    if (opened != LazerAudioOk) {
        std::cerr << "open error: " << engine->lastError() << '\n';
        engine->destroy();
        return fail("raw stereo DSD did not open through the required Native DSD path");
    }

    LazerAudioStreamInfo info{};
    engine->streamInfo(info);
    if (info.output_format_kind != LazerAudioOutputFormatNativeDsd ||
        info.output_dsd_rate_multiplier != 64 ||
        info.output_format_selection != LazerAudioFormatSelectionNativeDsdU32Be ||
        info.output_sample_rate != kNativeDsdRate || info.output_channels != kStereo ||
        info.output_bits_per_sample != 32 || info.output_container_bits_per_sample != 32 ||
        info.output_telemetry_valid != 0 || info.bit_perfect_active != 0) {
        engine->destroy();
        return fail("Native DSD session telemetry was reported as PCM or bit-perfect PCM");
    }

    if (engine->setVolume(0.5) != LazerAudioErrorUnsupported) {
        engine->destroy();
        return fail("software volume was accepted during Native DSD output");
    }
    const LazerAudioEqBand band{LazerAudioEqBandPeak, 1000.0, 3.0, 1.0, 1};
    const LazerAudioDspConfig dsp{0.0, 1, &band, 0, -1.0, 0};
    if (engine->setDsp(dsp) != LazerAudioErrorUnsupported) {
        engine->destroy();
        return fail("EQ was accepted during Native DSD output");
    }

    if (engine->play() != LazerAudioOk || !waitForNativePayload(capture)) {
        std::cerr << "play error: " << engine->lastError() << '\n';
        engine->destroy();
        return fail("Native DSD output did not submit packed frames to the fake endpoint");
    }
    if (engine->stop() != LazerAudioOk) {
        engine->destroy();
        return fail("stop failed while Native DSD output was active");
    }
    engine->destroy();

    std::lock_guard guard(capture->mutex);
    if (capture->openCount != 1 || !capture->request.requireNativeDsd ||
        !capture->request.exclusive || capture->request.bitPerfect ||
        !capture->request.desired.nativeDsd || capture->request.desired.doP ||
        capture->request.desired.sampleRate != kDsdByteClock ||
        capture->request.desired.channels != kStereo || !capture->session.nativeDsd ||
        capture->session.doP || !capture->session.exclusive ||
        capture->session.target.sampleRate * (capture->session.target.containerBitsPerSample / 8) !=
            kDsdByteClock || capture->session.target.channels != kStereo ||
        capture->session.target.bitsPerSample != 32 || capture->invalidWrite ||
        capture->writeCount == 0 || capture->bytes.empty() ||
        capture->bytes.size() % kNativeFrameBytes != 0) {
        return fail("the fake endpoint did not receive the exact direct U32 Native DSD clock and frames");
    }
    const auto firstPackedDsdFrame = std::search(capture->bytes.begin(), capture->bytes.end(),
        kExpectedFirstFrame.begin(), kExpectedFirstFrame.end());
    if (firstPackedDsdFrame == capture->bytes.end()) {
        return fail("Native DSD output contained no source payload matching the packed DSF data");
    }
    std::cout << "PASS: DSD64 opened as exclusive Native DSD U32_BE at 88.2 kHz; telemetry, DSP guards and stop are correct\n";
    return 0;
}

int runWrongClockCase() {
    MemoryReader reader(makeStereoDsf());
    const LazerAudioReader input = asReader(reader);
    auto capture = std::make_shared<Capture>();

    LazerAudioEngineConfig config{};
    config.abi_version = LAZER_AUDIO_ABI_VERSION;
    config.struct_size = sizeof(config);
    config.device.dsd_output_mode = LazerAudioDsdOutputRequireNative;
    Engine *engine = Engine::create(config,
        std::make_unique<NativeDsdCaptureOutput>(capture, true));
    if (engine == nullptr) return fail("Native DSD wrong-clock engine creation failed");

    const LazerAudioOpenParams params{sizeof(LazerAudioOpenParams), 0, 0};
    const int32_t opened = engine->open(nullptr, &input, params);
    const std::string error = engine->lastError();
    engine->destroy();
    if (opened != LazerAudioErrorUnsupported ||
        error.find("Native DSD format contract") == std::string::npos) {
        std::cerr << "open result=" << opened << " error=" << error << '\n';
        return fail("an invalid Native DSD output clock was not rejected by the engine contract");
    }
    std::cout << "PASS: mismatched Native DSD word clock was rejected before playback\n";
    return 0;
}

} // namespace

namespace lazer::audio {
std::unique_ptr<AudioOutput> createPlatformAudioOutput() { return nullptr; }
} // namespace lazer::audio

int main() {
    if (runNativeDsdCase() != 0) return 1;
    if (runWrongClockCase() != 0) return 1;
    return 0;
}
