#include "android_uac2_iso_transport.h"

#include <chrono>
#include <cstdint>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

namespace lazer::uac2_transport_probe {
void ResetFakeLibusbState();
int CloseCount();
int ExitCount();
int ClosedFdCount();
} // namespace lazer::uac2_transport_probe

namespace {

using lazer::android_uac2::IsoTransportSession;
using lazer::android_uac2::NativeStreamConfig;

bool Check(const bool condition, const char* message) {
    if (condition) return true;
    std::cerr << message << '\n';
    return false;
}

template <typename Predicate>
bool WaitFor(Predicate predicate, const std::chrono::milliseconds timeout) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (std::chrono::steady_clock::now() < deadline) {
        if (predicate()) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return predicate();
}

NativeStreamConfig TestStreamConfig() {
    NativeStreamConfig config{};
    config.configuration_value = 1;
    config.interface_number = 1;
    config.alternate_setting = 1;
    config.sample_rate_hz = 48000;
    config.channel_count = 2;
    config.channel_config = 3;
    config.subslot_size_bytes = 2;
    config.valid_bit_resolution = 16;
    config.data_endpoint_address = 0x01;
    config.data_synchronization_type = 1; // Asynchronous; feedback is mandatory.
    config.data_maximum_packet_size_bytes = 1024;
    config.data_transactions_per_interval = 1;
    config.data_interval = 1;
    config.feedback_endpoint_address = 0x81;
    config.feedback_maximum_packet_size_bytes = 4;
    config.feedback_transactions_per_interval = 1;
    config.feedback_interval = 1;
    return config;
}

} // namespace

int main() {
    using namespace lazer::uac2_transport_probe;
    ResetFakeLibusbState();

    std::string error;
    auto session = IsoTransportSession::Open(42, &error);
    if (!Check(session != nullptr, error.empty() ? "Could not wrap borrowed USB descriptor" : error.c_str())) return 1;

    const auto config = TestStreamConfig();
    if (!Check(session->Start(config, &error) == 0,
               error.empty() ? "Could not start USB 2.0 async PCM stream" : error.c_str())) return 1;

    // The transport starts paused. Writes are accepted into its bounded queue but no queued frame
    // may become audible or advance the playhead before SetPlaying(true).
    std::vector<std::uint8_t> first_audio(400u * 4u, 0x55u);
    const int first_write = session->Write(first_audio.data(), first_audio.size(), 0);
    if (!Check(first_write == static_cast<int>(first_audio.size()),
               "Paused stream should accept complete PCM frames into its queue")) return 1;
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    if (!Check(session->played_frames_since_flush() == 0,
               "Paused stream must not report queued frames as played")) return 1;

    session->SetPlaying(true);
    if (!Check(WaitFor([&] { return session->played_frames_since_flush() > 0; },
                       std::chrono::milliseconds(500)),
               "Playing stream did not complete PCM data packets")) return 1;
    session->SetPlaying(false);
    if (!Check(session->Flush(1000), "Flush should wait for submitted packets and reset the epoch")) return 1;
    if (!Check(session->played_frames_since_flush() == 0,
               "Flush should reset the completed-frame playhead")) return 1;

    // A paused writer cannot outrun the device queue. A further frame is rejected once the
    // configured queue is full, proving the write path applies backpressure.
    const std::size_t queue_bytes = session->buffer_size_frames() * 4u;
    std::vector<std::uint8_t> bulk_audio(queue_bytes + 4096u, 0x22u);
    const int bulk_write = session->Write(bulk_audio.data(), bulk_audio.size(), 0);
    if (!Check(bulk_write == static_cast<int>(queue_bytes),
               "Paused PCM write should be capped at the bounded queue capacity")) return 1;
    const std::uint8_t one_frame[4] = {0, 0, 0, 0};
    if (!Check(session->Write(one_frame, sizeof(one_frame), 0) == 0,
               "A full PCM queue should report zero accepted frames")) return 1;

    // Stop-after-drain must transmit all accepted payload frames, and the same wrapped session
    // must support a later start/flush cycle without reopening or reconfiguring the USB device.
    if (!Check(session->Stop(true, 2000), "Drain stop did not complete all queued PCM frames")) return 1;
    if (!Check(session->played_frames_since_flush() == queue_bytes / 4u,
               "Drain stop playhead should count every queued PCM frame exactly once")) return 1;
    if (!Check(session->Start(config, &error) == 0,
               error.empty() ? "Could not restart after a completed drain" : error.c_str())) return 1;
    if (!Check(session->Flush(1000), "Restarted stream flush should succeed")) return 1;
    if (!Check(session->played_frames_since_flush() == 0,
               "Restarted stream flush should leave a fresh frame epoch")) return 1;
    if (!Check(session->Stop(false, 1000), "Immediate stop should cancel and drain callbacks")) return 1;

    session->Close();
    if (!Check(CloseCount() == 1 && ExitCount() == 1,
               "Native close should release its libusb handle and context once")) return 1;
    if (!Check(ClosedFdCount() == 0,
               "libusb_close must leave the Java-owned USB file descriptor open")) return 1;

    std::cout << "UAC2 transport lifecycle, pacing, bounded write, flush and drain checks passed\n";
    return 0;
}
