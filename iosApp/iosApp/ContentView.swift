import AVFoundation
import MediaPlayer
import PhotosUI
import Shared
import UIKit
import WebKit

/// The sheets UIKit only lets a native caller put on screen. Kotlin decides what happens around them.
final class LazerShell: NSObject, IosShellBridge, WKNavigationDelegate {
    private let audio = LazerAudio()
    private var pendingScan: ((String?) -> Void)?
    private var pendingImage: ((String?) -> Void)?
    private var pickerHandler: LazerPickerHandler?

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
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        let picker = PHPickerViewController(configuration: configuration)
        let handler = LazerPickerHandler { [weak self] path in
            self?.pendingImage?(path)
            self?.pendingImage = nil
        }
        pickerHandler = handler
        picker.delegate = handler
        present(picker)
    }

    func share(text: String, title: String) {
        present(UIActivityViewController(activityItems: [text], applicationActivities: nil))
    }

    func presentSavedFile(path: String) {
        present(UIActivityViewController(
            activityItems: [URL(fileURLWithPath: path)],
            applicationActivities: nil
        ))
    }

    /// The sign-in browser. It carries the session and refuses to leave the login hosts.
    func makeAuthWebView(url: String, sessionCookie: String) -> UIView {
        let configuration = WKWebViewConfiguration()
        let store = configuration.websiteDataStore.httpCookieStore
        for cookie in LazerShell.sessionCookies(sessionCookie) {
            store.setCookie(cookie)
        }
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        if let address = URL(string: url) {
            webView.load(URLRequest(url: address))
        }
        return webView
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        let host = navigationAction.request.url?.host ?? ""
        decisionHandler(host == "music.163.com" || host.hasSuffix(".music.163.com") ? .allow : .cancel)
    }

    private static func sessionCookies(_ sessionCookie: String) -> [HTTPCookie] {
        let allowed = ["MUSIC_U", "MUSIC_A", "NMTID", "deviceId", "__csrf"]
        return sessionCookie.split(separator: ";").compactMap { field in
            let pair = field.split(separator: "=", maxSplits: 1).map(String.init)
            guard pair.count == 2 else { return nil }
            let name = pair[0].trimmingCharacters(in: .whitespaces)
            guard allowed.contains(name) else { return nil }
            return HTTPCookie(properties: [
                .name: name,
                .value: pair[1].trimmingCharacters(in: .whitespaces),
                .domain: ".music.163.com",
                .path: "/",
                .secure: "TRUE",
                .expires: Date().addingTimeInterval(60 * 60 * 24 * 365),
            ])
        }
    }

    func downloadToFile(url: String, onDone: @escaping (String?) -> Void) {
        download(url, to: nil) { path, _ in onDone(path) }
    }

    func downloadToDestination(url: String, destination: String, onDone: @escaping (String?) -> Void) {
        download(url, to: URL(fileURLWithPath: destination)) { path, _ in onDone(path) }
    }

    private func download(_ url: String, to destination: URL?, _ done: @escaping (String?, Bool) -> Void) {
        guard let address = URL(string: url) else { done(nil, false); return }
        let target = destination
            ?? FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        URLSession.shared.dataTask(with: address) { data, _, _ in
            guard let data, (try? data.write(to: target)) != nil else {
                DispatchQueue.main.async { done(nil, false) }
                return
            }
            DispatchQueue.main.async { done(target.path, true) }
        }.resume()
    }

    func playerLoad(url: String, startPlaying: Bool, positionMillis: Int64) {
        audio.load(url: url, startPlaying: startPlaying, positionMillis: Double(positionMillis) / 1000)
    }

    func playerPlay() { audio.play() }

    func playerPause() { audio.pause() }

    func playerSeekTo(positionMillis: Int64) { audio.seek(toMillis: positionMillis) }

    func playerRelease() { audio.release() }

    func playerPositionMillis() -> Int64 { audio.positionMillis }

    func playerDurationMillis() -> Int64 { audio.durationMillis }

    func playerIsPlaying() -> Bool { audio.isPlaying }

    func playerSetEndedHandler(handler: @escaping () -> Void) { audio.onEnded = handler }

    func playerAttachCommands(commands: IosPlayerCommands) { audio.attach(commands: commands) }

    func playerUpdateNowPlaying(
        title: String,
        artist: String,
        album: String,
        coverUrl: String?,
        positionMillis: Int64,
        durationMillis: Int64,
        isPlaying: Bool
    ) {
        audio.publishNowPlaying(
            title: title, artist: artist, album: album, coverUrl: coverUrl,
            positionMillis: positionMillis, durationMillis: durationMillis, isPlaying: isPlaying
        )
    }

    func playerSetAudioMode(exclusive: Bool, systemMedia: Bool) {
        audio.applyMode(exclusive: exclusive, systemMedia: systemMedia)
    }

    func requestMicrophoneAccess(onDone: @escaping () -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized, .denied, .restricted: onDone()
        default:
            AVCaptureDevice.requestAccess(for: .audio) { _ in
                DispatchQueue.main.async { onDone() }
            }
        }
    }

    func isMicrophoneGranted() -> Bool {
        AVCaptureDevice.authorizationStatus(for: .audio) == .authorized
    }

    func setDarkStatusBar(dark: Bool) {
        LazerHostViewController.prefersDarkStatusBar = dark
        foregroundWindow()?.rootViewController?.setNeedsStatusBarAppearanceUpdate()
    }

    private func foregroundWindow() -> UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.windows.first(where: \.isKeyWindow) }
            .first
    }

    private func present(_ controller: UIViewController) {
        guard let host = foregroundWindow()?.rootViewController else { return }
        host.present(controller, animated: true)
    }
}

/// Owns AVPlayer for the shared queue. Kotlin decides what plays next; this only makes it audible.
private final class LazerAudio: NSObject {
    var onEnded: (() -> Void)?

    private var player: AVPlayer?
    private var observedItem: AVPlayerItem?

    var positionMillis: Int64 { Int64((player?.currentTime().seconds ?? 0) * 1000) }

    var durationMillis: Int64 {
        guard let seconds = player?.currentItem?.duration.seconds, seconds.isFinite else { return 0 }
        return Int64(max(0, seconds) * 1000)
    }

    var isPlaying: Bool { player?.timeControlStatus == .playing }

    func load(url: String, startPlaying: Bool, positionMillis: Double) {
        guard let address = URL(string: url) else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        let item = AVPlayerItem(url: address)
        let next = AVPlayer(playerItem: item)
        NotificationCenter.default.addObserver(
            self, selector: #selector(itemDidFinish), name: .AVPlayerItemDidPlayToEndTime, object: item
        )
        observedItem = item
        player = next
        if positionMillis > 0 {
            next.seek(to: CMTime(seconds: positionMillis / 1000, preferredTimescale: 600))
        }
        if startPlaying { next.play() }
    }

    func play() { player?.play() }

    func pause() { player?.pause() }

    func seek(toMillis millis: Int64) {
        player?.seek(to: CMTime(seconds: Double(millis) / 1000, preferredTimescale: 600))
    }

    func release() {
        player?.pause()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        if let item = observedItem {
            NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: item)
        }
        observedItem = nil
        player = nil
    }

    @objc private func itemDidFinish() { onEnded?() }

    /* System media controls. They attach once for the life of the player; the mode switch decides
       whether the system hears about playback at all. */
    private var commands: IosPlayerCommands?
    private var wantsSystemMedia = true

    func attach(commands: IosPlayerCommands) {
        guard self.commands == nil else { return }
        self.commands = commands
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget(self, action: #selector(handlePlay))
        center.pauseCommand.addTarget(self, action: #selector(handlePause))
        center.nextTrackCommand.addTarget(self, action: #selector(handleNext))
        center.previousTrackCommand.addTarget(self, action: #selector(handlePrevious))
        center.changePlaybackPositionCommand.addTarget(self, action: #selector(handleSeek(_:)))
    }

    @objc private func handlePlay() -> MPRemoteCommandHandlerStatus {
        commands?.play()
        return .success
    }

    @objc private func handlePause() -> MPRemoteCommandHandlerStatus {
        commands?.pause()
        return .success
    }

    @objc private func handleNext() -> MPRemoteCommandHandlerStatus {
        commands?.next()
        return .success
    }

    @objc private func handlePrevious() -> MPRemoteCommandHandlerStatus {
        commands?.previous()
        return .success
    }

    @objc private func handleSeek(_ event: MPChangePlaybackPositionCommandEvent) -> MPRemoteCommandHandlerStatus {
        commands?.seekToMillis(millis: Int64(event.positionTime * 1000))
        return .success
    }

    func publishNowPlaying(
        title: String, artist: String, album: String, coverUrl: String?,
        positionMillis: Int64, durationMillis: Int64, isPlaying: Bool
    ) {
        guard wantsSystemMedia else { return }
        var info: [String: Any] = [
            "MPMediaItemPropertyTitle": title,
            "MPMediaItemPropertyArtist": artist,
            "MPMediaItemPropertyAlbumTitle": album,
            "MPMediaItemPropertyPlaybackDuration": Double(durationMillis) / 1000,
            "MPNowPlayingInfoPropertyElapsedPlaybackTime": Double(positionMillis) / 1000,
            "MPNowPlayingInfoPropertyPlaybackRate": isPlaying ? 1.0 : 0.0,
            "MPNowPlayingInfoPropertyDefaultPlaybackRate": 1.0,
        ]
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = info
        center.playbackState = isPlaying ? .playing : .paused
        guard let coverUrl, let address = URL(string: coverUrl) else { return }
        URLSession.shared.dataTask(with: address) { data, _, _ in
            guard let data, let image = UIImage(data: data) else { return }
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            info["MPMediaItemPropertyArtwork"] = artwork
            DispatchQueue.main.async { center.nowPlayingInfo = info }
        }.resume()
    }

    func applyMode(exclusive: Bool, systemMedia: Bool) {
        wantsSystemMedia = systemMedia
        try? AVAudioSession.sharedInstance().setCategory(.playback, options: exclusive ? [] : .duckOthers)
        let center = MPRemoteCommandCenter.shared()
        [center.playCommand, center.pauseCommand, center.nextTrackCommand,
         center.previousTrackCommand, center.changePlaybackPositionCommand].forEach {
            $0.isEnabled = systemMedia
        }
        if !systemMedia {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        }
    }
}


/// A QR scanner that stays on screen only while the listener is looking for a code.
private final class LazerScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onFound: ((String?) -> Void)?
    var onCancelled: (() -> Void)?

    private let session = AVCaptureSession()
    private let preview = AVCaptureVideoPreviewLayer()

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
        preview.session = session
        preview.videoGravity = .resizeAspectFill
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

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview.frame = view.bounds
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

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let code = object.stringValue else { return }
        dismiss(animated: true) { [weak self] in
            self?.onFound?(code)
            self?.onFound = nil
        }
    }
}

/// PHPicker needs an object that outlives the call while the system loads the image.
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
        provider.loadObject(ofClass: UIImage.self) { [weak self] image, _ in
            guard let self else { return }
            guard let uiImage = image as? UIImage, let data = uiImage.jpegData(compressionQuality: 0.92) else {
                DispatchQueue.main.async { self.onDone(nil) }
                return
            }
            let target = FileManager.default.temporaryDirectory
                .appendingPathComponent("lazer-background-" + UUID().uuidString + ".jpg")
            if (try? data.write(to: target)) != nil {
                DispatchQueue.main.async { self.onDone(target.path) }
            } else {
                DispatchQueue.main.async { self.onDone(nil) }
            }
        }
    }
}
