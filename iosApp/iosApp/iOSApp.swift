import SwiftUI
import Shared
import AVFoundation
import PhotosUI
import WebKit

/// The SwiftUI shell is a host only: Compose draws every screen, exactly as on Android.
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    var body: some View {
        ComposeRoot()
            .ignoresSafeArea()
    }
}

private struct ComposeRoot: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(bridge: LazerShell())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

