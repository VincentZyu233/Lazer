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

    func scanCode(chrome: IosScanChrome, onResult: @escaping (String?) -> Void) {
        pendingScan = onResult
        let scanner = LazerScannerViewController(chrome: chrome)
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


/// A QR scanner that carries the active palette over the camera, laid out the way the Android
/// scan activity lays itself out: a card at the top, a pill at the bottom, corner guides between.
private final class LazerScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onFound: ((String?) -> Void)?
    var onCancelled: (() -> Void)?

    private let chrome: IosScanChrome
    private var cameraUnavailable = false
    private let session = AVCaptureSession()
    private let preview = AVCaptureVideoPreviewLayer()
    private let guides = CAShapeLayer()

    init(chrome: IosScanChrome) {
        self.chrome = chrome
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let source = try? AVCaptureDeviceInput(device: device),
           session.canAddInput(source) {
            session.addInput(source)
            let output = AVCaptureMetadataOutput()
            if session.canAddOutput(output) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
            }
        } else {
            cameraUnavailable = true
        }
        preview.session = session
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        buildInterface()
    }

    private func buildInterface() {
        guides.fillColor = nil
        guides.strokeColor = palette(chrome.primaryArgb).cgColor
        guides.lineWidth = 4
        guides.lineCap = .round
        view.layer.addSublayer(guides)

        let card = UIView()
        card.backgroundColor = palette(chrome.backgroundArgb).withAlphaComponent(247 / 255)
        card.layer.cornerRadius = 22
        card.layer.shadowColor = UIColor.black.cgColor
        card.layer.shadowOpacity = 0.24
        card.layer.shadowRadius = 6
        card.layer.shadowOffset = CGSize(width: 0, height: 4)

        let mark = UIImageView(image: LazerScanGlyph.qr(side: 22))
        mark.tintColor = palette(chrome.onPrimaryContainerArgb)
        mark.backgroundColor = palette(chrome.primaryContainerArgb)
        mark.layer.cornerRadius = 15

        let title = UILabel()
        title.text = chrome.title
        title.font = .systemFont(ofSize: 20, weight: .medium)
        title.textColor = palette(chrome.onBackgroundArgb)

        let subtitle = UILabel()
        subtitle.text = chrome.subtitle
        subtitle.font = .systemFont(ofSize: 13, weight: .regular)
        subtitle.textColor = palette(chrome.onSurfaceVariantArgb)
        subtitle.numberOfLines = 2

        let column = UIStackView(arrangedSubviews: [title, subtitle])
        column.axis = .vertical
        column.spacing = 4
        // The card keeps its width and lets the copy wrap, exactly as the Android panel does.
        title.contentCompressionResistancePriority = .init(700)
        subtitle.contentCompressionResistancePriority = .init(700)

        let close = UIButton(type: .custom)
        close.setImage(LazerScanGlyph.close(side: 20), for: .normal)
        close.tintColor = palette(chrome.onBackgroundArgb)
        close.backgroundColor = palette(chrome.surfaceArgb)
        close.layer.cornerRadius = 24
        close.accessibilityLabel = chrome.backLabel
        close.addTarget(self, action: #selector(cancelScan), for: .touchUpInside)

        let subviews: [UIView] = [card, mark, column, close]
        for subview in subviews {
            subview.translatesAutoresizingMaskIntoConstraints = false
        }
        view.addSubview(card)
        card.addSubview(mark)
        card.addSubview(column)
        card.addSubview(close)

        // Android gives the panel 18dp margins and lets it run to 440dp at most.
        let fillsWidth = card.widthAnchor.constraint(equalTo: view.safeAreaLayoutGuide.widthAnchor, constant: -36)
        fillsWidth.priority = .defaultHigh

        let pill = UIView()
        pill.backgroundColor = palette(chrome.primaryArgb).withAlphaComponent(232 / 255)
        pill.layer.cornerRadius = 100
        pill.layer.shadowColor = UIColor.black.cgColor
        pill.layer.shadowOpacity = 0.18
        pill.layer.shadowRadius = 4
        pill.layer.shadowOffset = CGSize(width: 0, height: 3)
        let prompt = UILabel()
        prompt.text = chrome.prompt
        prompt.font = .systemFont(ofSize: 14, weight: .medium)
        prompt.textColor = LazerScanGlyph.isLight(chrome.primaryArgb) ? .black : .white
        prompt.textAlignment = .center
        prompt.translatesAutoresizingMaskIntoConstraints = false
        pill.addSubview(prompt)
        view.addSubview(pill)

        NSLayoutConstraint.activate([
            card.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 18),
            card.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            card.widthAnchor.constraint(lessThanOrEqualToConstant: 440),
            card.widthAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.widthAnchor, constant: -36),
            card.heightAnchor.constraint(equalToConstant: 76),
            fillsWidth,

            mark.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            mark.centerYAnchor.constraint(equalTo: card.centerYAnchor),
            mark.widthAnchor.constraint(equalToConstant: 44),
            mark.heightAnchor.constraint(equalToConstant: 44),

            close.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -8),
            close.centerYAnchor.constraint(equalTo: card.centerYAnchor),
            close.widthAnchor.constraint(equalToConstant: 48),
            close.heightAnchor.constraint(equalToConstant: 48),

            column.leadingAnchor.constraint(equalTo: mark.trailingAnchor, constant: 14),
            column.trailingAnchor.constraint(equalTo: close.leadingAnchor, constant: -8),
            column.centerYAnchor.constraint(equalTo: card.centerYAnchor),

            pill.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            pill.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -20),
            pill.leadingAnchor.constraint(greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            pill.trailingAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            prompt.leadingAnchor.constraint(equalTo: pill.leadingAnchor, constant: 18),
            prompt.trailingAnchor.constraint(equalTo: pill.trailingAnchor, constant: -18),
            prompt.topAnchor.constraint(equalTo: pill.topAnchor, constant: 11),
            prompt.bottomAnchor.constraint(equalTo: pill.bottomAnchor, constant: -11),
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview.frame = view.bounds
        let side = framingSide(for: view.bounds.size)
        let rect = CGRect(
            x: (view.bounds.width - side) / 2,
            y: (view.bounds.height - side) / 2,
            width: side,
            height: side
        )
        guides.frame = view.bounds
        guides.path = LazerScanGlyph.cornerGuides(around: rect, arm: side * 0.12)
    }

    /// The Android viewfinder measures 62% of the shorter side, kept between 190 and 330 points.
    private func framingSide(for size: CGSize) -> CGFloat {
        let shorter = max(1, min(size.width, size.height))
        let minimum = min(shorter, 190)
        let maximum = max(min(shorter, 330), minimum)
        return min(max((shorter * 0.62).rounded(), minimum), maximum)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if cameraUnavailable {
            dismiss(animated: true) { [weak self] in
                self?.onCancelled?()
                self?.onCancelled = nil
            }
            return
        }
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

    private func palette(_ argb: Int32) -> UIColor {
        let value = UInt32(bitPattern: argb)
        return UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: CGFloat((value >> 24) & 0xFF) / 255
        )
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

/// The two marks the Android scanner loads from vector drawables, drawn at the same 24 point grid.
private enum LazerScanGlyph {
    static func qr(side: CGFloat) -> UIImage {
        let path = UIBezierPath()
        path.usesEvenOddFillRule = true
        let finders: [(CGFloat, CGFloat)] = [(3, 3), (14, 3), (3, 14)]
        for finder in finders {
            path.append(UIBezierPath(rect: CGRect(x: finder.0, y: finder.1, width: 7, height: 7)))
            path.append(UIBezierPath(rect: CGRect(x: finder.0 + 2, y: finder.1 + 2, width: 3, height: 3)))
        }
        let cells: [(CGFloat, CGFloat, CGFloat, CGFloat)] = [
            (13, 13, 3, 3), (12, 17, 2, 4), (15, 18, 2, 3), (18, 18, 3, 3),
        ]
        for cell in cells {
            path.append(UIBezierPath(rect: CGRect(x: cell.0, y: cell.1, width: cell.2, height: cell.3)))
        }
        let shape = UIBezierPath()
        shape.move(to: CGPoint(x: 17, y: 13))
        shape.addLine(to: CGPoint(x: 21, y: 13))
        shape.addLine(to: CGPoint(x: 21, y: 15))
        shape.addLine(to: CGPoint(x: 19, y: 15))
        shape.addLine(to: CGPoint(x: 19, y: 17))
        shape.addLine(to: CGPoint(x: 17, y: 17))
        shape.closePath()
        path.append(shape)
        return render(side) { context in
            context.cgContext.saveGState()
            context.cgContext.scaleBy(x: side / 24, y: side / 24)
            UIColor.white.setFill()
            path.fill()
            context.cgContext.restoreGState()
        }
    }

    static func close(side: CGFloat) -> UIImage {
        let path = UIBezierPath()
        path.lineCapStyle = .round
        path.move(to: CGPoint(x: 6.4, y: 6.4))
        path.addLine(to: CGPoint(x: 17.6, y: 17.6))
        path.move(to: CGPoint(x: 17.6, y: 6.4))
        path.addLine(to: CGPoint(x: 6.4, y: 17.6))
        return render(side) { context in
            context.cgContext.saveGState()
            context.cgContext.scaleBy(x: side / 24, y: side / 24)
            UIColor.white.setStroke()
            path.lineWidth = 2
            path.stroke()
            context.cgContext.restoreGState()
        }
    }

    static func cornerGuides(around rect: CGRect, arm: CGFloat) -> CGPath {
        let path = CGMutablePath()
        let stroke: CGFloat = 4
        let left = rect.minX + stroke / 2
        let top = rect.minY + stroke / 2
        let right = rect.maxX - stroke / 2
        let bottom = rect.maxY - stroke / 2
        let corners: [(CGPoint, CGPoint)] = [
            (CGPoint(x: left, y: top), CGPoint(x: left + arm, y: top)),
            (CGPoint(x: left, y: top), CGPoint(x: left, y: top + arm)),
            (CGPoint(x: right, y: top), CGPoint(x: right - arm, y: top)),
            (CGPoint(x: right, y: top), CGPoint(x: right, y: top + arm)),
            (CGPoint(x: left, y: bottom), CGPoint(x: left + arm, y: bottom)),
            (CGPoint(x: left, y: bottom), CGPoint(x: left, y: bottom - arm)),
            (CGPoint(x: right, y: bottom), CGPoint(x: right - arm, y: bottom)),
            (CGPoint(x: right, y: bottom), CGPoint(x: right, y: bottom - arm)),
        ]
        for (from, to) in corners {
            path.move(to: from)
            path.addLine(to: to)
        }
        return path
    }

    /// Whether the primary colour is bright enough to need dark text, the same test Android runs.
    static func isLight(_ argb: Int32) -> Bool {
        let value = UInt32(bitPattern: argb)
        let channels = [(value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF]
        let linear = channels.map { raw -> Double in
            let channel = Double(raw) / 255
            return channel <= 0.03928 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2] > 0.45
    }

    private static func render(_ side: CGFloat, _ draw: (UIGraphicsImageRendererContext) -> Void) -> UIImage {
        let image = UIGraphicsImageRenderer(size: CGSize(width: side, height: side)).image(actions: draw)
        return image.withRenderingMode(.alwaysTemplate)
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
