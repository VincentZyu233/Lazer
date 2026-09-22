package dev.naominet.lazer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

/** Persistent audio cache that can be read while its file is still downloading. */
internal class DesktopAudioCache(
    private val cacheDirectory: Path = Path.of(
        System.getProperty("user.home"),
        ".lazer",
        "cache",
        "audio",
    ),
    private val onProgress: (trackId: Long, fraction: Float) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = ConcurrentHashMap<String, AudioCacheEntry>()
    private val lifecycleLock = Any()
    private var closed = false
    private var clearing = false

    // Shared across entries: each download only owns its response and one copy buffer.
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun open(
        trackId: Long,
        variantKey: String,
        url: String,
        expectedBytes: Long?,
    ): InputStream = synchronized(lifecycleLock) {
        check(!closed) { "音频缓存已关闭" }
        check(!clearing) { "音频缓存正在清理" }
        val fileName = audioCacheFileName(trackId, variantKey)
        val entry = entries.computeIfAbsent(fileName) {
            AudioCacheEntry(
                mediaPath = cacheDirectory.resolve(fileName),
                scope = scope,
                trackId = trackId,
                onProgress = onProgress,
                httpClient = httpClient,
                onIdle = { idleEntry -> entries.remove(fileName, idleEntry) },
            )
        }
        entry.ensureDownload(url, expectedBytes)
        onProgress(trackId, entry.bufferedFraction())
        entry.openInputStream()
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            entries.values.forEach(AudioCacheEntry::cancel)
            entries.clear()
            scope.cancel()
        }
    }

    suspend fun clear(): Int {
        val snapshot = synchronized(lifecycleLock) {
            check(!closed) { "音频缓存已关闭" }
            check(!clearing) { "音频缓存正在清理" }
            clearing = true
            entries.values.toList().also { entries.clear() }
        }
        try {
            // Cancel every request before joining any one of them.
            snapshot.forEach(AudioCacheEntry::cancel)
            snapshot.forEach { it.join() }
            return runCatching {
                if (!Files.isDirectory(cacheDirectory)) return@runCatching 0
                var removed = 0
                Files.newDirectoryStream(cacheDirectory).use { paths ->
                    paths.forEach { path -> if (Files.deleteIfExists(path)) removed += 1 }
                }
                removed
            }.getOrDefault(0)
        } finally {
            synchronized(lifecycleLock) { clearing = false }
        }
    }
}

private class AudioCacheEntry(
    private val mediaPath: Path,
    private val scope: CoroutineScope,
    private val trackId: Long,
    private val onProgress: (trackId: Long, fraction: Float) -> Unit,
    private val httpClient: HttpClient,
    private val onIdle: (AudioCacheEntry) -> Unit,
) {
    private val completePath = mediaPath.resolveSibling("${mediaPath.fileName}.complete")
    private val dataLock = ReentrantLock()
    private val dataChanged = dataLock.newCondition()
    private val stateLock = Any()

    @Volatile
    private var downloadedBytes = existingSize()

    @Volatile
    private var expectedBytes = 0L

    @Volatile
    private var complete = hasValidCompletionMarker()

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var cancelled = false

    @Volatile
    private var dataRevision = 0L

    private var downloadJob: Job? = null
    private val readers = ConcurrentHashMap.newKeySet<GrowingCacheInputStream>()

    fun ensureDownload(url: String, requestedExpectedBytes: Long?) {
        synchronized(stateLock) {
            val requestedSize = requestedExpectedBytes?.coerceAtLeast(0L) ?: 0L
            if (requestedSize > 0L) expectedBytes = requestedSize
            if (complete && requestedSize > 0L && downloadedBytes != requestedSize) {
                complete = false
                Files.deleteIfExists(completePath)
            }
            if (complete) {
                onProgress(trackId, 1f)
                return
            }
            if (downloadJob?.isActive == true) return

            Files.createDirectories(mediaPath.parent)
            if (!Files.exists(mediaPath)) Files.createFile(mediaPath)
            downloadedBytes = Files.size(mediaPath)
            if (requestedSize > 0L && downloadedBytes > requestedSize) {
                Files.newOutputStream(
                    mediaPath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).close()
                downloadedBytes = 0L
            }
            failure = null
            downloadJob = scope.launch { download(url) }
        }
    }

    fun openInputStream(): InputStream = GrowingCacheInputStream(this, mediaPath).also(readers::add)

    fun readerClosed(reader: GrowingCacheInputStream) {
        readers.remove(reader)
        if (isIdle()) onIdle(this)
    }

    fun bufferedFraction(): Float = when {
        complete -> 1f
        expectedBytes > 0L -> bufferedFraction(downloadedBytes, expectedBytes)
        else -> 0f
    }

    fun availableFrom(position: Long): Long = (downloadedBytes - position).coerceAtLeast(0L)
    fun isComplete(): Boolean = complete
    fun currentFailure(): Throwable? = failure

    fun currentDataRevision(): Long = dataRevision

    fun awaitMoreData(observedRevision: Long) {
        dataLock.lock()
        try {
            if (dataRevision == observedRevision) dataChanged.await()
        } finally {
            dataLock.unlock()
        }
    }

    fun wakeReaders() {
        dataLock.lock()
        try {
            dataRevision += 1L
            dataChanged.signalAll()
        } finally {
            dataLock.unlock()
        }
    }

    fun cancel() {
        cancelled = true
        failure = IOException("音频缓存已关闭")
        synchronized(stateLock) { downloadJob }?.cancel()
        readers.forEach { runCatching { it.close() } }
        wakeReaders()
        if (isIdle()) onIdle(this)
    }

    suspend fun join() {
        synchronized(stateLock) { downloadJob }?.join()
    }

    fun isCancelled(): Boolean = cancelled

    private fun isIdle(): Boolean =
        readers.isEmpty() && (complete || failure != null || cancelled)

    private suspend fun openHttp(request: HttpRequest): HttpResponse<InputStream> {
        val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        try {
            return runInterruptible { future.get() }
        } catch (error: Throwable) {
            // Covers cancellation racing with response delivery as well as blocked headers.
            future.whenComplete { response, _ -> response?.body()?.close() }
            future.cancel(true)
            throw error
        }
    }

    private suspend fun download(url: String) {
        try {
            var start = Files.size(mediaPath)
            if (expectedBytes > 0L && start == expectedBytes) {
                downloadedBytes = start
                Files.writeString(
                    completePath,
                    start.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                complete = true
                onProgress(trackId, 1f)
                return
            }
            currentCoroutineContext().ensureActive()
            val uri = URI(url)
            val input: InputStream
            val append: Boolean
            val responseBytes: Long
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                val request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Lazer/1.2")
                    .header("Accept-Encoding", "identity")
                    .apply { if (start > 0L) header("Range", "bytes=$start-") }
                    .GET()
                    .build()
                val response = openHttp(request)
                input = response.body()
                if (response.statusCode() !in 200..299) {
                    input.close()
                    throw IOException("音频下载失败（HTTP ${response.statusCode()}）")
                }
                append = start > 0L && response.statusCode() == 206
                responseBytes = response.headers().firstValueAsLong("Content-Length").orElse(0L)
            } else {
                val connection = uri.toURL().openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                }
                input = runInterruptible { connection.getInputStream() }
                append = false
                responseBytes = connection.contentLengthLong.coerceAtLeast(0L)
            }

            input.use {
                currentCoroutineContext().ensureActive()
                if (start > 0L && !append) {
                    start = 0L
                    Files.newOutputStream(
                        mediaPath,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    ).close()
                    downloadedBytes = 0L
                }
                if (expectedBytes <= 0L && responseBytes > 0L) expectedBytes = start + responseBytes

                val openOptions = if (append) {
                    arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                } else {
                    arrayOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                }
                Files.newOutputStream(mediaPath, *openOptions).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastUpdateNs = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        // HttpClient's InputStream blocks interruptibly, but its request timeout
                        // ends at headers. Retain the old 20-second body inactivity timeout.
                        val count = withTimeout(20_000L) {
                            runInterruptible { input.read(buffer) }
                        }
                        if (count < 0) break
                        if (count == 0) continue
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        wakeReaders()

                        val now = System.nanoTime()
                        if (now - lastUpdateNs >= 100_000_000L) {
                            onProgress(trackId, bufferedFraction())
                            lastUpdateNs = now
                        }
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            val finalSize = Files.size(mediaPath)
            val expected = expectedBytes
            if (expected > 0L && finalSize < expected) {
                throw IOException("音频缓存下载不完整（$finalSize/$expected）")
            }
            downloadedBytes = finalSize
            Files.writeString(
                completePath,
                finalSize.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            complete = true
            onProgress(trackId, 1f)
        } catch (error: Throwable) {
            if (!cancelled) failure = error
            if (error is CancellationException) throw error
        } finally {
            wakeReaders()
            if (isIdle()) onIdle(this)
        }
    }

    private fun existingSize(): Long = runCatching {
        if (Files.isRegularFile(mediaPath)) Files.size(mediaPath) else 0L
    }.getOrDefault(0L)

    private fun hasValidCompletionMarker(): Boolean = runCatching {
        downloadedBytes > 0L &&
            Files.isRegularFile(completePath) &&
            Files.readString(completePath).trim().toLongOrNull() == downloadedBytes
    }.getOrDefault(false)
}

private class GrowingCacheInputStream(
    private val entry: AudioCacheEntry,
    mediaPath: Path,
) : InputStream() {
    private val file = RandomAccessFile(mediaPath.toFile(), "r")
    private var position = 0L
    private val singleByte = ByteArray(1)

    @Volatile
    private var closed = false

    override fun read(): Int {
        return if (read(singleByte, 0, 1) < 0) -1 else singleByte[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val observedRevision = entry.currentDataRevision()
            if (closed || entry.isCancelled()) throw IOException("音频缓存读取已关闭")
            val available = entry.availableFrom(position)
            if (available > 0L) {
                val count = min(length.toLong(), available).toInt()
                file.seek(position)
                val read = file.read(buffer, offset, count)
                if (read > 0) {
                    position += read
                    return read
                }
            }
            if (entry.isComplete()) return -1
            entry.currentFailure()?.let { throw IOException("音频缓存下载中断", it) }
            entry.awaitMoreData(observedRevision)
        }
    }

    override fun skip(count: Long): Long {
        if (count <= 0L || closed) return 0L
        val skipped = min(count, entry.availableFrom(position))
        position += skipped
        return skipped
    }

    override fun available(): Int = min(entry.availableFrom(position), Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        if (closed) return
        closed = true
        try {
            file.close()
        } finally {
            entry.readerClosed(this)
            entry.wakeReaders()
        }
    }
}

internal fun audioCacheFileName(trackId: Long, variantKey: String): String {
    val safeVariant = variantKey.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96)
        .ifBlank { "default" }
    return "$trackId-$safeVariant.audio"
}

internal fun bufferedFraction(downloadedBytes: Long, expectedBytes: Long): Float {
    if (expectedBytes <= 0L) return 0f
    return (downloadedBytes.coerceAtLeast(0L).toDouble() / expectedBytes.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}
