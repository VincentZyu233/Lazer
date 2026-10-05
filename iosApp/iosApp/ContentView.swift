import AVFoundation
import MediaPlayer
import PhotosUI
import Shared
import UIKit
import UniformTypeIdentifiers
import WebKit

/// The sheets UIKit only lets a native caller put on screen. Kotlin decides what happens around them.
final class LazerShell: NSObject, IosShellBridge, WKNavigationDelegate {
    private let audio = LazerAudio()
    private var pendingScan: ((String?) -> Void)?
    private var pendingImage: ((String?) -> Void)?
    private var pickerHandler: LazerPickerHandler?
    private var audioDocumentPickerHandler: LazerAudioDocumentPickerHandler?
    private var localAudioCachePrepared = false
    private var localAudioQueuedURIs: Set<String> = []
    private var localAudioQueuePersisted = false
    private var localAudioActiveURI: String?
    private var localAudioInFlightBatchDirectories: Set<URL> = []
    private var backGesture: UIScreenEdgePanGestureRecognizer?
    private var backSink: IosBackGestureSink?

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

    func pickLocalAudioFiles(onPicked: @escaping (LazerLocalAudioPickerResult) -> Void) {
        guard let host = foregroundWindow()?.rootViewController else {
            onPicked(LazerLocalAudioPickerResult(files: [], unsupportedFileCount: 0, failedFileCount: 0))
            return
        }
        guard prepareLocalAudioCache() else {
            onPicked(LazerLocalAudioPickerResult(files: [], unsupportedFileCount: 0, failedFileCount: 0))
            return
        }
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.audio], asCopy: true)
        picker.allowsMultipleSelection = true
        let handler = LazerAudioDocumentPickerHandler { [weak self] urls in
            guard let self else { return }
            Task { @MainActor in
                let imported = await self.importLocalAudioFiles(urls)
                self.audioDocumentPickerHandler = nil
                onPicked(imported.result)
                if let batchDirectory = imported.batchDirectory {
                    self.localAudioInFlightBatchDirectories.remove(batchDirectory)
                    self.pruneLocalAudioCache()
                }
            }
        }
        audioDocumentPickerHandler = handler
        picker.delegate = handler
        host.present(picker, animated: true)
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

    func playerLoad(url: String, startPlaying: Bool, positionMillis: Int64, generation: Int64) {
        audio.load(
            url: url,
            startPlaying: startPlaying,
            positionMillis: Double(positionMillis) / 1000,
            playbackGeneration: generation
        )
        localAudioActiveURI = managedLocalAudioBatchDirectory(for: url) == nil ? nil : url
    }

    func playerUpdateLocalAudioQueue(uris: [String], persisted: Bool) {
        localAudioQueuedURIs = Set(uris)
        localAudioQueuePersisted = persisted
        if persisted { pruneLocalAudioCache() }
    }

    func playerLocalAudioFileExists(uri: String) -> Bool {
        guard let fileURL = managedLocalAudioFileURL(for: uri) else { return false }
        let values = try? fileURL.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        return values?.isRegularFile == true && values?.isSymbolicLink != true &&
            FileManager.default.isReadableFile(atPath: fileURL.path)
    }

    func playerPlay() { audio.play() }

    func playerPause() { audio.pause() }

    func playerSeekTo(positionMillis: Int64) { audio.seek(toMillis: positionMillis) }

    func playerRelease() {
        audio.release()
        localAudioActiveURI = nil
        pruneLocalAudioCache()
    }

    func playerPositionMillis() -> Int64 { audio.positionMillis }

    func playerDurationMillis() -> Int64 { audio.durationMillis }

    func playerBufferedMillis() -> Int64 { audio.bufferedEndMillis }

    func playerIsPlaying() -> Bool { audio.isPlaying }

    func playerSetEndedHandler(handler: @escaping () -> Void) { audio.onEnded = handler }

    func playerAttachFailureSink(sink: IosPlaybackFailureSink) {
        audio.onPlaybackFailure = { generation, detail in
            sink.didFailPlayback(generation: generation, detail: detail)
        }
    }

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

    func playerAttachAudioSessionSink(sink: IosAudioSessionSink) {
        audio.attach(audioSessionSink: sink)
    }

    /// The system's own left-edge swipe, reported to the shared page transform while it drags.
    func setBackGesture(enabled: Bool, sink: IosBackGestureSink) {
        backSink = sink
        guard let view = foregroundWindow()?.rootViewController?.view else { return }
        if let existing = backGesture {
            existing.isEnabled = enabled
            return
        }
        guard enabled else { return }
        let recognizer = UIScreenEdgePanGestureRecognizer(
            target: self, action: #selector(trackBackGesture(_:))
        )
        recognizer.edges = .left
        view.addGestureRecognizer(recognizer)
        backGesture = recognizer
    }

    @objc private func trackBackGesture(_ gesture: UIScreenEdgePanGestureRecognizer) {
        guard let sink = backSink, let view = gesture.view else { return }
        let travelled = gesture.translation(in: view).x
        let share = Float(max(0, min(1, travelled / max(1, view.bounds.width))))
        switch gesture.state {
        case .began, .changed:
            sink.reportProgress(progress: share, fromLeftEdge: true)
        case .ended:
            // A swipe that never reached the threshold slides back instead of closing the layer.
            if share > 0.3 {
                sink.reportProgress(progress: 1, fromLeftEdge: true)
                sink.confirmed()
            } else {
                sink.reportProgress(progress: 0, fromLeftEdge: true)
            }
        default:
            sink.reportProgress(progress: 0, fromLeftEdge: true)
        }
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

    private var localAudioImportDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("LazerLocalAudio", isDirectory: true)
    }

    /// Picker URLs are staged in an app-owned folder so the queue never depends on a security-scoped
    /// URL whose access lifetime cannot be represented by the shared playback model.
    private func prepareLocalAudioCache() -> Bool {
        if localAudioCachePrepared { return existingLocalAudioImportRoot() != nil }
        let fileManager = FileManager.default
        do {
            try fileManager.createDirectory(
                at: localAudioImportDirectory,
                withIntermediateDirectories: true
            )
            guard existingLocalAudioImportRoot() != nil else { return false }
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var directory = localAudioImportDirectory
            try directory.setResourceValues(values)
            localAudioCachePrepared = true
            return true
        } catch {
            return false
        }
    }

    @MainActor
    private func importLocalAudioFiles(
        _ urls: [URL]
    ) async -> (result: LazerLocalAudioPickerResult, batchDirectory: URL?) {
        guard prepareLocalAudioCache() else {
            return (
                LazerLocalAudioPickerResult(
                    files: [],
                    unsupportedFileCount: 0,
                    failedFileCount: Int32(clamping: urls.count)
                ),
                nil
            )
        }
        let supportedExtensions: Set<String> = ["wav", "wave", "flac"]
        let supported = urls.filter { supportedExtensions.contains($0.pathExtension.lowercased()) }
        var unsupportedCount = urls.count - supported.count
        guard !supported.isEmpty else {
            return (
                LazerLocalAudioPickerResult(
                    files: [],
                    unsupportedFileCount: Int32(clamping: unsupportedCount),
                    failedFileCount: 0
                ),
                nil
            )
        }

        let createdBatchDirectory = localAudioImportDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try FileManager.default.createDirectory(
                at: createdBatchDirectory,
                withIntermediateDirectories: true
            )
        } catch {
            return (
                LazerLocalAudioPickerResult(
                    files: [],
                    unsupportedFileCount: 0,
                    failedFileCount: Int32(clamping: urls.count)
                ),
                nil
            )
        }
        guard let root = existingLocalAudioImportRoot(),
              let batchDirectory = managedLocalAudioBatchDirectory(at: createdBatchDirectory, root: root) else {
            return (
                LazerLocalAudioPickerResult(
                    files: [],
                    unsupportedFileCount: 0,
                    failedFileCount: Int32(clamping: urls.count)
                ),
                nil
            )
        }
        localAudioInFlightBatchDirectories.insert(batchDirectory)

        let (copied, copyFailureCount) = await Task.detached(priority: .userInitiated) {
            () -> ([(url: URL, fallbackTitle: String)], Int) in
            var copied: [(url: URL, fallbackTitle: String)] = []
            var failureCount = 0
            let documentsDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let pickerInbox = documentsDirectory.appendingPathComponent("Inbox").standardizedFileURL
            for source in supported {
                let target = createdBatchDirectory.appendingPathComponent(UUID().uuidString)
                    .appendingPathExtension(source.pathExtension.lowercased())
                let accessed = source.startAccessingSecurityScopedResource()
                defer { if accessed { source.stopAccessingSecurityScopedResource() } }
                do {
                    if source.deletingLastPathComponent().standardizedFileURL == pickerInbox {
                        do {
                            try FileManager.default.moveItem(at: source, to: target)
                        } catch {
                            try FileManager.default.copyItem(at: source, to: target)
                            try? FileManager.default.removeItem(at: source)
                        }
                    } else {
                        try FileManager.default.copyItem(at: source, to: target)
                    }
                    copied.append((target, source.deletingPathExtension().lastPathComponent))
                } catch {
                    failureCount += 1
                }
            }
            return (copied, failureCount)
        }.value
        let failedFileCount = copyFailureCount

        var files: [LazerPickedAudioFile] = []
        for item in copied {
            let url = item.url
            let metadata = await localAudioMetadata(for: url)
            let uri = url.absoluteString
            files.append(LazerPickedAudioFile(
                uri: uri,
                title: metadata.title.isEmpty ? item.fallbackTitle : metadata.title,
                artist: metadata.artist,
                album: metadata.album,
                durationMillis: metadata.durationMillis
            ))
        }
        if copied.isEmpty {
            removeManagedLocalAudioBatch(batchDirectory)
            localAudioInFlightBatchDirectories.remove(batchDirectory)
            return (
                LazerLocalAudioPickerResult(
                    files: files,
                    unsupportedFileCount: Int32(clamping: unsupportedCount),
                    failedFileCount: Int32(clamping: failedFileCount)
                ),
                nil
            )
        }
        return (
            LazerLocalAudioPickerResult(
                files: files,
                unsupportedFileCount: Int32(clamping: unsupportedCount),
                failedFileCount: Int32(clamping: failedFileCount)
            ),
            batchDirectory
        )
    }

    private func localAudioMetadata(for url: URL) async -> (title: String, artist: String, album: String, durationMillis: Int64) {
        let asset = AVURLAsset(url: url)
        let metadata = (try? await asset.load(.commonMetadata)) ?? []
        let title = AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: .commonIdentifierTitle)
            .first?.stringValue ?? ""
        let artist = AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: .commonIdentifierArtist)
            .first?.stringValue ?? ""
        let album = AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: .commonIdentifierAlbumName)
            .first?.stringValue ?? ""
        let duration = (try? await asset.load(.duration))?.seconds ?? 0
        let durationMillis = duration.isFinite ? Int64(max(0, duration) * 1000) : 0
        return (title, artist, album, durationMillis)
    }

    private func existingLocalAudioImportRoot() -> URL? {
        let fileManager = FileManager.default
        let applicationSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .resolvingSymlinksInPath().standardizedFileURL
        let root = localAudioImportDirectory.standardizedFileURL
        guard root.lastPathComponent == "LazerLocalAudio",
              root.deletingLastPathComponent().resolvingSymlinksInPath().standardizedFileURL.path == applicationSupport.path,
              let values = try? root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
              values.isDirectory == true,
              values.isSymbolicLink != true else { return nil }
        let resolved = root.resolvingSymlinksInPath().standardizedFileURL
        guard resolved.deletingLastPathComponent().path == applicationSupport.path else { return nil }
        return resolved
    }

    private func managedLocalAudioBatchDirectory(for uri: String) -> URL? {
        guard let fileURL = managedLocalAudioFileURL(for: uri),
              let root = existingLocalAudioImportRoot() else { return nil }
        return managedLocalAudioBatchDirectory(at: fileURL.deletingLastPathComponent(), root: root)
    }

    private func managedLocalAudioFileURL(for uri: String) -> URL? {
        guard let fileURL = URL(string: uri), fileURL.isFileURL,
              let components = URLComponents(url: fileURL, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "file",
              components.host == nil || components.host == "",
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil else { return nil }
        let encodedPath = components.percentEncodedPath
        let rawComponents = encodedPath.split(separator: "/", omittingEmptySubsequences: true)
        for component in rawComponents {
            guard let decoded = String(component).removingPercentEncoding,
                  decoded != ".", decoded != "..",
                  !decoded.contains("/"), !decoded.contains("\\") else { return nil }
        }

        let standardized = fileURL.standardizedFileURL
        let rootPathComponents = localAudioImportDirectory.standardizedFileURL.pathComponents
        let pathComponents = standardized.pathComponents
        guard pathComponents.count == rootPathComponents.count + 2,
              Array(pathComponents.prefix(rootPathComponents.count)) == rootPathComponents else { return nil }
        let batchName = pathComponents[rootPathComponents.count]
        let filename = pathComponents[rootPathComponents.count + 1]
        let fileExtension = standardized.pathExtension.lowercased()
        guard Self.isCanonicalUUID(batchName),
              ["wav", "wave", "flac"].contains(fileExtension),
              Self.isCanonicalUUID(standardized.deletingPathExtension().lastPathComponent) else { return nil }

        guard let root = existingLocalAudioImportRoot(),
              managedLocalAudioBatchDirectory(
                  at: standardized.deletingLastPathComponent(), root: root
              ) != nil else { return nil }
        let resolved = standardized.resolvingSymlinksInPath().standardizedFileURL
        let resolvedComponents = resolved.pathComponents
        guard resolvedComponents.count == root.pathComponents.count + 2,
              Array(resolvedComponents.prefix(root.pathComponents.count)) == root.pathComponents,
              resolved.lastPathComponent == filename,
              resolved.deletingLastPathComponent().lastPathComponent == batchName else { return nil }
        if let values = try? resolved.resourceValues(forKeys: [.isSymbolicLinkKey]),
           values.isSymbolicLink == true { return nil }
        return resolved
    }

    private func managedLocalAudioBatchDirectory(at candidate: URL, root: URL) -> URL? {
        let standardized = candidate.standardizedFileURL
        guard standardized.deletingLastPathComponent().resolvingSymlinksInPath().standardizedFileURL.path == root.path,
              Self.isCanonicalUUID(standardized.lastPathComponent),
              let values = try? standardized.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
              values.isDirectory == true,
              values.isSymbolicLink != true else { return nil }
        let resolved = standardized.resolvingSymlinksInPath().standardizedFileURL
        guard resolved.deletingLastPathComponent().path == root.path,
              resolved.lastPathComponent == standardized.lastPathComponent else { return nil }
        return resolved
    }

    private func pruneLocalAudioCache() {
        guard localAudioQueuePersisted,
              let root = existingLocalAudioImportRoot(),
              let children = try? FileManager.default.contentsOfDirectory(
                  at: root,
                  includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]
              ) else { return }

        var retainedDirectories = Set<String>()
        var retainedURIs = localAudioQueuedURIs
        if let localAudioActiveURI { retainedURIs.insert(localAudioActiveURI) }
        for uri in retainedURIs {
            if let directory = managedLocalAudioBatchDirectory(for: uri) {
                retainedDirectories.insert(directory.path)
            }
        }
        for directory in localAudioInFlightBatchDirectories {
            if let managed = managedLocalAudioBatchDirectory(at: directory, root: root) {
                retainedDirectories.insert(managed.path)
            }
        }
        for child in children {
            guard let directory = managedLocalAudioBatchDirectory(at: child, root: root),
                  !retainedDirectories.contains(directory.path) else { continue }
            removeManagedLocalAudioBatch(directory)
        }
    }

    private func removeManagedLocalAudioBatch(_ candidate: URL) {
        guard let root = existingLocalAudioImportRoot(),
              let directory = managedLocalAudioBatchDirectory(at: candidate, root: root) else { return }
        try? FileManager.default.removeItem(at: directory)
    }

    private static func isCanonicalUUID(_ value: String) -> Bool {
        guard value.count == 36, let uuid = UUID(uuidString: value) else { return false }
        return value.caseInsensitiveCompare(uuid.uuidString) == .orderedSame
    }

    private func present(_ controller: UIViewController) {
        guard let host = foregroundWindow()?.rootViewController else { return }
        host.present(controller, animated: true)
    }
}

/// Owns AVPlayer for the shared queue. Kotlin decides what plays next; this only makes it audible.
private final class LazerAudio: NSObject {
    var onEnded: (() -> Void)?
    var onPlaybackFailure: ((Int64, String) -> Void)?

    // One player for the life of the app. MPNowPlayingSession is built around a fixed list of players,
    // so the item is what gets swapped between tracks; replacing the player would mean throwing away
    // the session, and with it the lock screen's and Dynamic Island's hold on the song.
    private let player = AVPlayer()
    private var observedItem: AVPlayerItem?
    private var itemStatusObservation: NSKeyValueObservation?
    private var currentPlaybackGeneration: Int64?
    private var reportedFailureGeneration: Int64?

    var positionMillis: Int64 {
        let seconds = player.currentTime().seconds
        return seconds.isFinite ? Int64(seconds * 1000) : 0
    }

    var durationMillis: Int64 {
        guard let seconds = player.currentItem?.duration.seconds, seconds.isFinite else { return 0 }
        return Int64(max(0, seconds) * 1000)
    }

    var isPlaying: Bool { player.timeControlStatus == .playing }

    /// How far the audio has arrived. The shared seek bar paints that behind the playhead.
    var bufferedEndMillis: Int64 {
        guard let item = player.currentItem, let value = item.loadedTimeRanges.first as? NSValue else {
            return 0
        }
        let range = value.timeRangeValue
        return Int64((CMTimeGetSeconds(range.start) + CMTimeGetSeconds(range.duration)) * 1000)
    }

    func load(url: String, startPlaying: Bool, positionMillis: Double, playbackGeneration: Int64) {
        loadGeneration &+= 1
        let generation = loadGeneration
        currentPlaybackGeneration = playbackGeneration
        reportedFailureGeneration = nil
        itemPreparationTask?.cancel()
        itemPreparationTimeoutTask?.cancel()
        itemPreparationTask = nil
        itemPreparationTimeoutTask = nil
        sourceTrackSampleRateHz = nil
        selectedSampleRateRequestHz = nil
        didFinishSampleRatePreparation = false
        playbackRequested = startPlaying
        if !audioSessionInterrupted { playbackBlockedByInterruption = false }
        if let previous = observedItem {
            NotificationCenter.default.removeObserver(
                self, name: AVPlayerItem.didPlayToEndTimeNotification, object: previous
            )
            NotificationCenter.default.removeObserver(
                self, name: AVPlayerItem.failedToPlayToEndTimeNotification, object: previous
            )
        }
        itemStatusObservation?.invalidate()
        itemStatusObservation = nil
        observedItem = nil
        player.pause()
        guard let address = URL(string: url) else {
            player.replaceCurrentItem(with: nil)
            reportPlaybackFailure(
                generation: playbackGeneration,
                item: nil,
                detail: "The audio URL is invalid."
            )
            return
        }
        let asset = AVURLAsset(url: address)
        let item = AVPlayerItem(asset: asset)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(itemDidFinish(_:)),
            name: AVPlayerItem.didPlayToEndTimeNotification,
            object: item
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(itemFailedToPlayToEnd(_:)),
            name: AVPlayerItem.failedToPlayToEndTimeNotification,
            object: item
        )
        observedItem = item
        // A new song has new metadata, so the next publish writes the whole record again. These
        // belong to the queue that publishes them, not to the thread that asked for the track.
        mediaQueue.async { [weak self] in
            guard let self else { return }
            self.nowPlayingKey = nil
            self.publishedPlaying = nil
        }
        player.replaceCurrentItem(with: item)
        itemStatusObservation = item.observe(\.status, options: [.new]) { [weak self, weak item] observed, _ in
            guard observed.status == .failed else { return }
            let detail = observed.error?.localizedDescription ?? "The audio item failed to load."
            DispatchQueue.main.async { [weak self, weak item] in
                guard let self, let item else { return }
                guard self.observedItem === item,
                      self.currentPlaybackGeneration == playbackGeneration else { return }
                self.reportPlaybackFailure(
                    generation: playbackGeneration,
                    item: item,
                    detail: detail
                )
            }
        }
        if positionMillis > 0 {
            player.seek(to: CMTime(seconds: positionMillis / 1000, preferredTimescale: 600))
        }
        itemPreparationTask = Task { @MainActor [weak self] in
            let sampleRate = await Self.unambiguousTrackSampleRateHz(in: asset)
            guard let self, self.loadGeneration == generation else { return }
            self.sourceTrackSampleRateHz = sampleRate
            if !self.didFinishSampleRatePreparation {
                self.finishSampleRatePreparation(generation: generation)
            } else {
                // A timeout may already have started playback without a preference request. Show
                // late metadata, but keep the per-item rate decision made at the deadline.
                self.publishAudioSessionSnapshot()
            }
        }
        itemPreparationTimeoutTask = Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: 750_000_000) } catch { return }
            guard let self, self.loadGeneration == generation else { return }
            self.finishSampleRatePreparation(generation: generation)
        }
    }

    func play() {
        playbackRequested = true
        if !audioSessionInterrupted { playbackBlockedByInterruption = false }
        guard didFinishSampleRatePreparation, !audioSessionInterrupted, !playbackBlockedByInterruption else { return }
        applySessionCategory(preferredSampleRateHz: selectedSampleRateRequestHz)
        if audioSessionActive { player.play() }
    }

    func pause() {
        playbackRequested = false
        player.pause()
    }

    func seek(toMillis millis: Int64) {
        player.seek(to: CMTime(seconds: Double(millis) / 1000, preferredTimescale: 600))
    }

    func release() {
        loadGeneration &+= 1
        currentPlaybackGeneration = nil
        reportedFailureGeneration = nil
        itemPreparationTask?.cancel()
        itemPreparationTimeoutTask?.cancel()
        itemPreparationTask = nil
        itemPreparationTimeoutTask = nil
        sourceTrackSampleRateHz = nil
        selectedSampleRateRequestHz = nil
        didFinishSampleRatePreparation = true
        player.pause()
        playbackRequested = false
        playbackBlockedByInterruption = false
        audioSessionInterrupted = false
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setActive(false, options: .notifyOthersOnDeactivation)
            audioSessionActive = false
            audioSessionInterrupted = false
            audioSessionConfigurationError = ""
        } catch {
            audioSessionActive = false
            audioSessionConfigurationError = error.localizedDescription
        }
        publishAudioSessionSnapshot()
        mediaQueue.async { [weak self] in
            guard let self else { return }
            self.nowPlayingSession?.nowPlayingInfoCenter.nowPlayingInfo = nil
            self.nowPlayingKey = nil
            self.artworkUrl = nil
            self.artwork = nil
            self.publishedPlaying = nil
        }
        if let item = observedItem {
            NotificationCenter.default.removeObserver(self, name: AVPlayerItem.didPlayToEndTimeNotification, object: item)
            NotificationCenter.default.removeObserver(
                self, name: AVPlayerItem.failedToPlayToEndTimeNotification, object: item
            )
        }
        itemStatusObservation?.invalidate()
        itemStatusObservation = nil
        observedItem = nil
        player.replaceCurrentItem(with: nil)
    }

    @objc private func itemDidFinish(_ notification: Notification) {
        guard let item = notification.object as? AVPlayerItem, item === observedItem else { return }
        onEnded?()
    }

    @objc private func itemFailedToPlayToEnd(_ notification: Notification) {
        guard let item = notification.object as? AVPlayerItem else { return }
        let playbackError = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
        let detail = playbackError?.localizedDescription
            ?? item.error?.localizedDescription
            ?? "The audio item failed to play."
        DispatchQueue.main.async { [weak self, weak item] in
            guard let self, let item else { return }
            guard self.observedItem === item,
                  let generation = self.currentPlaybackGeneration else { return }
            self.reportPlaybackFailure(
                generation: generation,
                item: item,
                detail: detail
            )
        }
    }

    private func reportPlaybackFailure(generation: Int64, item: AVPlayerItem?, detail: String) {
        guard currentPlaybackGeneration == generation, reportedFailureGeneration != generation else { return }
        if let item, item !== observedItem { return }
        reportedFailureGeneration = generation
        playbackRequested = false
        player.pause()
        onPlaybackFailure?(generation, detail)
    }

    /* System media controls. They attach once for the life of the player; the mode switch decides
       whether the system hears about playback at all. Everything that talks to the media services
       daemon keeps to this queue, because a thread that only asked for audio should not wait on it. */
    private let mediaQueue = DispatchQueue(label: "lazer.media")
    private var commands: IosPlayerCommands?
    private var audioSessionSink: IosAudioSessionSink?
    // AVAudioSession has no isActive property; track successful activation/deactivation and
    // interruption notifications to label the app's session lifecycle accurately.
    private var audioSessionActive = false
    private var audioSessionInterrupted = false
    private var audioSessionConfigurationError = ""
    private var playbackRequested = false
    private var playbackBlockedByInterruption = false
    private var sourceTrackSampleRateHz: Double?
    // Freeze the rate choice when preflight completes so late metadata cannot renegotiate this item.
    private var selectedSampleRateRequestHz: Double?
    private var didFinishSampleRatePreparation = true
    private var loadGeneration: UInt64 = 0
    private var itemPreparationTask: Task<Void, Never>?
    private var itemPreparationTimeoutTask: Task<Void, Never>?
    private var wantsSystemMedia = true
    private var exclusiveAudio = false
    private var nowPlayingKey: String?
    private var artworkUrl: String?
    private var artwork: MPMediaItemArtwork?
    /* Now Playing, as a session rather than a dictionary. MPNowPlayingSession owns the info center and
       the command center for this app's own audio, and it keeps the playhead moving on its own between
       updates, which is what the lock screen, Control Centre, CarPlay and the Dynamic Island all read.
       Commands go to the session's centre and never to MPRemoteCommandCenter.shared(), because two
       centres answering for one player is how controls end up dead. */
    private var nowPlayingSession: MPNowPlayingSession?
    private var publishedPlaying: Bool?
    private var publishedElapsed: Double = 0
    private var publishedAt: TimeInterval = 0
    private var publishedRate: Double = 0

    /// Shared mode explicitly mixes with other apps; priority mode asks the system to interrupt them.
    /// AVAudioSession does not expose CoreAudio Hog Mode or prove a bit-perfect DAC path.
    private func applySessionCategory(preferredSampleRateHz: Double? = nil) {
        guard !audioSessionInterrupted else {
            publishAudioSessionSnapshot()
            return
        }
        let session = AVAudioSession.sharedInstance()
        let options: AVAudioSession.CategoryOptions = exclusiveAudio ? [] : .mixWithOthers
        let shouldChangeRate = preferredSampleRateHz.map {
            abs(session.preferredSampleRate - $0) >= 0.5
        } ?? false
        audioSessionConfigurationError = ""
        var canSetPreferredRate = true
        if shouldChangeRate && audioSessionActive {
            do {
                try session.setActive(false, options: [])
                audioSessionActive = false
            } catch {
                canSetPreferredRate = false
                appendAudioSessionError(error.localizedDescription)
            }
        }
        do {
            try session.setCategory(.playback, options: options)
        } catch {
            appendAudioSessionError(error.localizedDescription)
        }
        if shouldChangeRate, canSetPreferredRate, let preferredSampleRateHz {
            do {
                try session.setPreferredSampleRate(preferredSampleRateHz)
            } catch {
                appendAudioSessionError(error.localizedDescription)
            }
        }
        do {
            try session.setActive(true)
            audioSessionActive = true
            audioSessionInterrupted = false
        } catch {
            audioSessionActive = false
            appendAudioSessionError(error.localizedDescription)
        }
        publishAudioSessionSnapshot()
    }

    private func appendAudioSessionError(_ message: String) {
        audioSessionConfigurationError = audioSessionConfigurationError.isEmpty
            ? message
            : "\(audioSessionConfigurationError); \(message)"
    }

    private func finishSampleRatePreparation(generation: UInt64) {
        guard loadGeneration == generation, !didFinishSampleRatePreparation else { return }
        didFinishSampleRatePreparation = true
        selectedSampleRateRequestHz = sourceTrackSampleRateHz
        itemPreparationTimeoutTask?.cancel()
        itemPreparationTimeoutTask = nil
        if playbackRequested && !audioSessionInterrupted && !playbackBlockedByInterruption {
            applySessionCategory(preferredSampleRateHz: selectedSampleRateRequestHz)
            if audioSessionActive { player.play() }
        } else {
            publishAudioSessionSnapshot()
        }
    }

    private static func unambiguousTrackSampleRateHz(in asset: AVURLAsset) async -> Double? {
        guard let tracks = try? await asset.loadTracks(withMediaType: .audio), !tracks.isEmpty else {
            return nil
        }
        var trackRates: [Int] = []
        for track in tracks {
            guard let descriptions = try? await track.load(.formatDescriptions), !descriptions.isEmpty else {
                return nil
            }
            let rates = descriptions.compactMap { description -> Double? in
                guard let rate = description.audioStreamBasicDescription?.mSampleRate,
                      rate.isFinite, rate > 0 else { return nil }
                return rate
            }
            guard rates.count == descriptions.count else { return nil }
            let roundedRates = rates.compactMap { rate -> Int? in
                let rounded = rate.rounded()
                guard rounded >= 1, rounded < Double(Int.max) else { return nil }
                return Int(rounded)
            }
            guard roundedRates.count == rates.count else { return nil }
            let distinctRates = Set(roundedRates)
            guard distinctRates.count == 1, let rate = distinctRates.first else { return nil }
            trackRates.append(rate)
        }
        guard Set(trackRates).count == 1, let rate = trackRates.first else { return nil }
        return Double(rate)
    }

    private func publishAudioSessionSnapshot() {
        let publish = { [weak self] in
            guard let self, let sink = self.audioSessionSink else { return }
            let session = AVAudioSession.sharedInstance()
            let outputs = session.currentRoute.outputs
            sink.didChangeAudioSession(
                sourceTrackSampleRateHz: self.sourceTrackSampleRateHz ?? .nan,
                preferredSampleRateHz: session.preferredSampleRate,
                sampleRateHz: session.sampleRate,
                outputChannelCount: Int32(session.outputNumberOfChannels),
                outputRouteName: outputs.map(\.portName).joined(separator: ", "),
                outputPortTypes: outputs.map { String($0.portType.rawValue) }.joined(separator: ", "),
                ioBufferDurationSeconds: session.ioBufferDuration,
                active: self.audioSessionActive,
                interrupted: self.audioSessionInterrupted,
                configurationError: self.audioSessionConfigurationError
            )
        }
        if Thread.isMainThread {
            publish()
        } else {
            DispatchQueue.main.async(execute: publish)
        }
    }

    func attach(audioSessionSink: IosAudioSessionSink) {
        self.audioSessionSink = audioSessionSink
        publishAudioSessionSnapshot()
    }

    /// Built once around the one player, then reused for every track.
    private func ensureSession() -> MPNowPlayingSession {
        if let session = nowPlayingSession { return session }
        let session = MPNowPlayingSession(players: [player])
        // The session would otherwise publish what it reads off the AVPlayerItem, and our stream
        // carries no metadata - title, artist and artwork come from the API and have to be written.
        session.automaticallyPublishesNowPlayingInfo = false
        let center = session.remoteCommandCenter
        center.playCommand.addTarget(self, action: #selector(handlePlay))
        center.pauseCommand.addTarget(self, action: #selector(handlePause))
        center.togglePlayPauseCommand.addTarget(self, action: #selector(handleToggle))
        center.nextTrackCommand.addTarget(self, action: #selector(handleNext))
        center.previousTrackCommand.addTarget(self, action: #selector(handlePrevious))
        center.changePlaybackPositionCommand.addTarget(self, action: #selector(handleSeek(_:)))
        // Control Centre and the lock screen offer ±15/±30 skips rather than a scrub bar, and a
        // Bluetooth or headset button reaches for the toggle. Without all three the transport looks
        // present and answers to nothing.
        center.skipForwardCommand.preferredIntervals = [30]
        center.skipBackwardCommand.preferredIntervals = [30]
        center.skipForwardCommand.addTarget(self, action: #selector(handleSkipForward(_:)))
        center.skipBackwardCommand.addTarget(self, action: #selector(handleSkipBackward(_:)))
        center.changePlaybackRateCommand.isEnabled = false
        nowPlayingSession = session
        return session
    }

    /* Whether the system gets to hear about this playback at all. A session holding no players has
       nothing to publish, which is the honest form of "independent playback" - and being active is
       something the system grants rather than something the app assigns, so it has to be asked for. */
    private func applySessionOwnership(_ systemMedia: Bool) {
        let session = ensureSession()
        let holds = session.players.contains { $0 === player }
        if systemMedia {
            if !holds { session.addPlayer(player) }
            session.becomeActiveIfPossible()
        } else if holds {
            session.removePlayer(player)
        }
    }

    func attach(commands: IosPlayerCommands) {
        guard self.commands == nil else { return }
        self.commands = commands
        mediaQueue.async { [weak self] in
            guard let self else { return }
            self.applySessionOwnership(self.wantsSystemMedia)
        }
        // A call, a Siri interruption or a pulled-out earbud is the system telling the player what
        // just happened to its audio. Nobody else in the app hears those, so the queue would keep
        // believing it was playing.
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification, object: nil
        )
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification, object: nil
        )
        publishAudioSessionSnapshot()
    }

    @objc private func handleToggle() -> MPRemoteCommandHandlerStatus {
        if player.timeControlStatus == .playing {
            commands?.pause()
        } else {
            commands?.play()
        }
        return .success
    }

    @objc private func handleSkipForward(_ event: MPSkipIntervalCommandEvent) -> MPRemoteCommandHandlerStatus {
        commands?.seekToMillis(millis: positionMillis + Int64(event.interval * 1000))
        return .success
    }

    @objc private func handleSkipBackward(_ event: MPSkipIntervalCommandEvent) -> MPRemoteCommandHandlerStatus {
        commands?.seekToMillis(millis: max(0, positionMillis - Int64(event.interval * 1000)))
        return .success
    }

    @objc private func handleInterruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        let resumeValue = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            switch type {
            case .began:
                self.audioSessionInterrupted = true
                self.audioSessionActive = false
                self.playbackBlockedByInterruption = true
                // AVPlayer may pause itself before this notification arrives. Keep the app's
                // playback intent separate so the end event can decide whether resuming is valid.
                self.player.pause()
                self.publishAudioSessionSnapshot()
            case .ended:
                self.audioSessionInterrupted = false
                let options = AVAudioSession.InterruptionOptions(rawValue: resumeValue)
                let systemAllowsResume = options.contains(.shouldResume)
                self.playbackBlockedByInterruption = !systemAllowsResume
                if self.playbackRequested && systemAllowsResume && self.didFinishSampleRatePreparation {
                    self.applySessionCategory(preferredSampleRateHz: self.selectedSampleRateRequestHz)
                    if self.audioSessionActive { self.player.play() }
                } else {
                    self.publishAudioSessionSnapshot()
                }
            @unknown default:
                self.audioSessionInterrupted = false
                self.playbackBlockedByInterruption = true
                self.publishAudioSessionSnapshot()
            }
        }
    }

    @objc private func handleRouteChange(_ note: Notification) {
        let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
        let reason: AVAudioSession.RouteChangeReason?
        if let raw {
            reason = AVAudioSession.RouteChangeReason(rawValue: raw)
        } else {
            reason = nil
        }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.publishAudioSessionSnapshot()
            guard reason == .oldDeviceUnavailable else { return }
            // The output went away with the listener: stop, rather than keep playing into a speaker
            // nobody is holding.
            self.commands?.pause()
        }
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
        // The metadata belongs to the track and the clock belongs to the playhead, and the session
        // keeps that playhead running on its own from one update. Rewriting the record every tick is
        // what makes the scrubber stutter, so this only speaks up when the song changes, when the
        // transport flips, or when reality has drifted more than half a second from what the system
        // is showing.
        let key = "\(title)\u{1}\(artist)\u{1}\(album)\u{1}\(durationMillis)"
        let seconds = Double(positionMillis) / 1000
        let now = ProcessInfo.processInfo.systemUptime
        mediaQueue.async {
            guard let center = self.nowPlayingSession?.nowPlayingInfoCenter else { return }
            let metadataChanged = center.nowPlayingInfo == nil || self.nowPlayingKey != key
            let carried = self.publishedElapsed
                + (self.publishedRate > 0 ? (now - self.publishedAt) * self.publishedRate : 0)
            let transportChanged = self.publishedPlaying != isPlaying
            if !metadataChanged && !transportChanged && abs(seconds - carried) < 0.5 { return }
            self.publishedPlaying = isPlaying
            self.publishedElapsed = seconds
            self.publishedAt = now
            self.publishedRate = isPlaying ? 1.0 : 0.0
            if metadataChanged {
                self.nowPlayingKey = key
                var info: [String: Any] = [
                    MPMediaItemPropertyTitle: title,
                    MPMediaItemPropertyArtist: artist,
                    MPMediaItemPropertyAlbumTitle: album,
                    MPMediaItemPropertyPlaybackDuration: Double(durationMillis) / 1000,
                    MPNowPlayingInfoPropertyElapsedPlaybackTime: seconds,
                    MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
                    MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
                ]
                if self.artworkUrl == coverUrl, let artwork = self.artwork {
                    info[MPMediaItemPropertyArtwork] = artwork
                }
                center.nowPlayingInfo = info
                self.loadArtwork(url: coverUrl, center: center)
            } else {
                var info = center.nowPlayingInfo ?? [:]
                info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = seconds
                info[MPNowPlayingInfoPropertyPlaybackRate] = self.publishedRate
                center.nowPlayingInfo = info
            }
            center.playbackState = isPlaying ? .playing : .paused
        }
    }

    /// One artwork per cover. The system wants the artwork in the same dictionary as the rest, so the
    /// download patches whatever the now-playing info holds by the time it lands.
    private func loadArtwork(url: String?, center: MPNowPlayingInfoCenter) {
        guard let url, let address = URL(string: url) else { return }
        guard artworkUrl != url else { return }
        artworkUrl = url
        artwork = nil
        URLSession.shared.dataTask(with: address) { [weak self] data, _, _ in
            guard let self, let data, let image = UIImage(data: data) else { return }
            let artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self.mediaQueue.async {
                guard self.artworkUrl == url else { return }
                self.artwork = artwork
                var info = center.nowPlayingInfo ?? [:]
                info[MPMediaItemPropertyArtwork] = artwork
                center.nowPlayingInfo = info
            }
        }.resume()
    }

    func applyMode(exclusive: Bool, systemMedia: Bool) {
        wantsSystemMedia = systemMedia
        exclusiveAudio = exclusive
        if playbackRequested && didFinishSampleRatePreparation && !audioSessionInterrupted && !playbackBlockedByInterruption {
            applySessionCategory(preferredSampleRateHz: selectedSampleRateRequestHz)
        } else {
            publishAudioSessionSnapshot()
        }
        mediaQueue.async { [weak self] in
            guard let self else { return }
            self.applySessionOwnership(systemMedia)
            if !systemMedia {
                self.nowPlayingSession?.nowPlayingInfoCenter.nowPlayingInfo = nil
                self.nowPlayingKey = nil
                self.publishedPlaying = nil
            }
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
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
        title.setContentCompressionResistancePriority(UILayoutPriority(700), for: .horizontal)
        subtitle.setContentCompressionResistancePriority(UILayoutPriority(700), for: .horizontal)

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

    /// The frame is measured by the shared code, the same measurement the Android viewfinder uses.
    private func framingSide(for size: CGSize) -> CGFloat {
        let shorter = min(size.width, size.height)
        return CGFloat(LazerScreenHostKt.lazerScanFrameSidePt(shorterSidePt: Float(shorter)))
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Without a camera the panel stays up with its own way out, which is what the Android
        // scanner does over a black preview; vanishing by itself would leave the tap unanswered.
        if !cameraUnavailable && !session.isRunning { session.startRunning() }
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
        shape.close()
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

/// The document picker retains its delegate only weakly, so LazerShell holds this handler until its
/// completion arrives. Copy mode gives the app a durable staging URL without a persisted file grant.
private final class LazerAudioDocumentPickerHandler: NSObject, UIDocumentPickerDelegate {
    private var onDone: (([URL]) -> Void)?

    init(onDone: @escaping ([URL]) -> Void) {
        self.onDone = onDone
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        finish(controller, urls: urls)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        finish(controller, urls: [])
    }

    private func finish(_ controller: UIDocumentPickerViewController, urls: [URL]) {
        let callback = onDone
        onDone = nil
        controller.dismiss(animated: true) { callback?(urls) }
    }
}
