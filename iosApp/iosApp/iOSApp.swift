import SwiftUI
import AVFoundation

@main
struct iOSApp: App {
    @StateObject private var audioPlayer = IOSAudioPlayer()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(audioPlayer)
        }
    }
}

/// AVPlayer stays native while Kotlin owns queue selection and the visible Compose player state.
@MainActor
final class IOSAudioPlayer: ObservableObject {
    private var player: AVPlayer?
    private var observers: [NSObjectProtocol] = []

    init() {
        let center = NotificationCenter.default
        observers.append(
            center.addObserver(
                forName: Notification.Name("dev.naominet.lazer.play-url"),
                object: nil,
                queue: .main
            ) { [weak self] notification in
                guard
                    let value = notification.object as? String,
                    let url = URL(string: value)
                else { return }
                Task { @MainActor in self?.play(url) }
            }
        )
        observers.append(
            center.addObserver(
                forName: Notification.Name("dev.naominet.lazer.pause"),
                object: nil,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor in self?.player?.pause() }
            }
        )
        observers.append(
            center.addObserver(
                forName: Notification.Name("dev.naominet.lazer.resume"),
                object: nil,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor in self?.player?.play() }
            }
        )
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    private func play(_ url: URL) {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
        } catch {
            // AVPlayer can still report its own playback failure; Compose keeps the UI responsive.
        }
        let nextPlayer = AVPlayer(url: url)
        player = nextPlayer
        nextPlayer.play()
    }
}
