import SwiftUI
import Shared
import AVFoundation
import PhotosUI
import UIKit
import WebKit

/// The sheets UIKit only lets a native caller put on screen. Kotlin decides what happens around them.
final class LazerShell: NSObject, IosShellBridge {
    private var pendingScan: ((String?) -> Void)?
    private var pendingImage: ((String?) -> Void)?

    func scanCode(onResult: @escaping (String?) -> Void) {
        pendingScan = onResult
        let scanner = LazerScannerViewController()
        scanner.onFound = { [weak self] code in
            self?.pendingScan?(code)
            self?.pendingScan = nil
        }
        scanner.onCancelled = { [weak self] in
            self?.pendingScan?(nil)
            self?.pendingScan = nil
        }
        present(scanner)
    }

    func pickImage(onPicked: @escaping (String?) -> Void) {
        pendingImage = onPicked
        let configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = LazerPickerHandler { [weak self] path in
            self?.pendingImage?(path)
            self?.pendingImage = nil
        }
        present(picker)
    }

    func share(text: String, title: String) {
        let panel = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        panel.completionWithItemsHandler = { _, _, _, _ in }
        present(panel)
    }

    func presentSavedFile(path: String) {
        let url = URL(fileURLWithPath: path)
        present(UIActivityViewController(activityItems: [url], applicationActivities: nil))
    }

    func makeAuthWebView(url: String, sessionCookie: String) -> UIView {
        let configuration = WKWebViewConfiguration()
        let store = configuration.websiteDataStore.httpCookieStore
        for cookie in LazerShell.sessionCookies(sessionCookie) {
            store.setCookie(cookie)
        }
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.allowsBackForwardNavigationGestures = false
        if let address = URL(string: url) {
            webView.load(URLRequest(url: address))
        }
        return webView
    }

    /// Only the fields the Gateway issued travel into the browser, and only to the login hosts.
    private static func sessionCookies(_ sessionCookie: String) -> [HTTPCookie] {
        let allowed = ["MUSIC_U", "MUSIC_A", "NMTID", "deviceId", "__csrf"]
        return sessionCookie.split(separator: ";").compactMap { field in
            let pair = field.split(separator: "=", maxSplits: 1).map(String.init)
            guard pair.count == 2, allowed.contains(pair[0].trimmingCharacters(in: .whitespaces)) else { return nil }
            var properties: [HTTPCookiePropertyKey: Any] = [
                .name: pair[0].trimmingCharacters(in: .whitespaces),
                .value: pair[1].trimmingCharacters(in: .whitespaces),
                .domain: ".music.163.com",
                .path: "/",
                .secure: "TRUE",
            ]
            properties[.expires] = Date().addingTimeInterval(60 * 60 * 24 * 365)
            return HTTPCookie(properties: properties)
        }
    }

    private func present(_ controller: UIViewController) {
        let keyWindow = UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.windows.first(where: \.isKeyWindow) }
            .first
        guard let host = keyWindow?.rootViewController else { return }
        if controller.modalPresentationStyle == .automatic {
            controller.modalPresentationStyle = .pageSheet
        }
        host.present(controller, animated: true)
    }
}

/// A QR scanner that stays on this screen only as long as the listener is looking for a code.
private final class LazerScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onFound: ((String?) -> Void)?
    var onCancelled: (() -> Void)?

    private let session = AVCaptureSession()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let source = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(source) else {
            onCancelled?()
            return
        }
        session.addInput(source)
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
        }
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        preview.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        view.layer.addSublayer(preview)

        let cancel = UIButton(type: .system)
        cancel.setTitle("取消", for: .normal)
        cancel.addTarget(self, action: #selector(cancelScan), for: .touchUpInside)
        cancel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(cancel)
        NSLayoutConstraint.activate([
            cancel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            cancel.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !session.isRunning { session.startRunning() }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }

    @objc private func cancelScan() {
        dismiss(animated: true) { [weak self] in
            self?.onCancelled?()
            self?.onCancelled = nil
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let code = object.stringValue else { return }
        dismiss(animated: true) { [weak self] in
            self?.onFound?(code)
            self?.onFound = nil
        }
    }
}

/// PHPicker needs an object to keep the result callback alive while the system loads the image.
private final class LazerPickerHandler: NSObject, PHPickerViewControllerDelegate {
    private let onDone: (String?) -> Void

    init(onDone: @escaping (String?) -> Void) {
        self.onDone = onDone
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else {
            onDone(nil)
            return
        }
        provider.loadObject(ofClass: UIImage.self) { image, _ in
            guard let uiImage = image as? UIImage, let data = uiImage.jpegData(compressionQuality: 0.92) else {
                DispatchQueue.main.async { self.onDone(nil) }
                return
            }
            let target = FileManager.default.temporaryDirectory
                .appendingPathComponent("lazer-background-\(UUID().uuidString).jpg")
            do {
                try data.write(to: target)
                DispatchQueue.main.async { self.onDone(target.path) }
            } catch {
                DispatchQueue.main.async { self.onDone(nil) }
            }
        }
    }
}
