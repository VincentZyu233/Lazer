import Shared
import SwiftUI
import UIKit

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
        LazerHostViewController(hosting: MainViewControllerKt.MainViewController(bridge: LazerHostViewController.shell))
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Owns the Compose view controller so the status bar can follow the theme, which UIKit only asks a
/// view controller about.
final class LazerHostViewController: UIViewController {
    static let shell = LazerShell()
    static var prefersDarkStatusBar = false

    private let hosted: UIViewController

    init(hosting: UIViewController) {
        hosted = hosting
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(hosted)
        hosted.view.frame = view.bounds
        hosted.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hosted.view)
        NSLayoutConstraint.activate([
            hosted.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            hosted.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            hosted.view.topAnchor.constraint(equalTo: view.topAnchor),
            hosted.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        hosted.didMove(toParent: self)
    }

    override var childForStatusBarStyle: UIViewController? { hosted }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        LazerHostViewController.prefersDarkStatusBar ? .lightContent : .darkContent
    }
}
