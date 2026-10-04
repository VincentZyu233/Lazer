package dev.naominet.lazer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short-lived, renderer-scoped HTTP source for explicitly authorized local WAV/FLAC files.
 * It deliberately has no filesystem path in its URL or request API.
 */
internal class DesktopUpnpMediaServer(
    bindAddress: InetAddress,
    private val rendererAddress: InetAddress,
    port: Int = 0,
    private val clock: Clock = Clock.systemUTC(),
    private val leaseTtl: Duration = DEFAULT_LEASE_TTL,
    maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
) : AutoCloseable {
    private val leases = ConcurrentHashMap<String, LeaseRecord>()
    private val closed = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        maxConcurrentRequests,
        maxConcurrentRequests,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxConcurrentRequests * REQUEST_QUEUE_FACTOR),
        DaemonThreadFactory(),
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val httpServer: HttpServer

    val localAddress: InetSocketAddress
        get() = httpServer.address

    init {
        require(isLocalUnicast(bindAddress)) { "The media server must bind to a local unicast address." }
        require(!rendererAddress.isAnyLocalAddress && !rendererAddress.isMulticastAddress) {
            "The renderer address must be a unicast address."
        }
        require(port in 0..65_535) { "The media server port is invalid." }
        require(!leaseTtl.isNegative && !leaseTtl.isZero) { "The media lease TTL must be positive." }
        require(maxConcurrentRequests in 1..MAX_CONCURRENT_REQUESTS) {
            "The media server concurrency limit is outside the supported range."
        }
        val createdServer = try {
            HttpServer.create(InetSocketAddress(bindAddress, port), BACKLOG)
        } catch (error: Throwable) {
            executor.shutdownNow()
            throw error
        }
        try {
            createdServer.executor = executor
            createdServer.createContext(MEDIA_PATH_PREFIX, ::handleRequest)
            createdServer.start()
        } catch (error: Throwable) {
            runCatching { createdServer.stop(0) }
            executor.shutdownNow()
            throw error
        }
        httpServer = createdServer
    }

    /** Authorizes exactly one whole local file and returns its opaque, revocable bearer URL. */
    fun createLease(source: DesktopTrackSource.LocalFile, mimeType: String = defaultMimeType(source)): DesktopUpnpMediaLease {
        check(!closed.get()) { "The media server is closed." }
        require(isAllowedMimeType(source, mimeType)) { "The MIME type is not allowed for this local file." }
        val path = Path.of(source.absolutePath).toAbsolutePath().normalize()
        require(source.cueSheetPath == null && source.cueTrackNumber == null &&
            source.cueStartFrame75 == 0L && source.cueEndFrame75 == 0L) {
            "CUE tracks and partial-file sources cannot be sent to a network renderer."
        }
        val snapshot = captureSnapshot(path, mimeType)
        val token = newToken()
        val expiresAt = try {
            Instant.now(clock).plus(leaseTtl)
        } catch (error: Exception) {
            throw IllegalArgumentException("The media lease TTL is outside the supported range.", error)
        }
        leases.entries.removeIf { !Instant.now(clock).isBefore(it.value.expiresAt) }
        val record = LeaseRecord(path, snapshot, mimeType, expiresAt)
        check(!closed.get()) { "The media server is closed." }
        check(leases.putIfAbsent(token, record) == null) { "Unable to allocate a media lease." }
        if (closed.get()) {
            leases.remove(token, record)
            throw IllegalStateException("The media server is closed.")
        }
        return mediaLease(token, record)
    }

    /** Issues a new capability URL for the same unchanged snapshot as a live lease. */
    fun reissueLease(
        existingLease: DesktopUpnpMediaLease,
        mimeType: String = existingLease.mimeType,
    ): DesktopUpnpMediaLease? {
        if (closed.get()) return null
        return existingLease.reissueTo(this, mimeType)
    }

    private fun handleRequest(exchange: HttpExchange) {
        try {
            if (!isDesktopUpnpRequestFromRenderer(exchange.remoteAddress.address, rendererAddress)) {
                sendEmpty(exchange, HTTP_FORBIDDEN)
                return
            }
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                exchange.responseHeaders.set("Allow", "GET, HEAD")
                sendEmpty(exchange, HTTP_METHOD_NOT_ALLOWED)
                return
            }
            val rawPath = exchange.requestURI.rawPath.orEmpty()
            if (exchange.requestURI.rawQuery != null || !TOKEN_PATH.matches(rawPath)) {
                sendEmpty(exchange, HTTP_NOT_FOUND)
                return
            }
            val token = rawPath.substring(MEDIA_PATH_PREFIX.length)
            val record = leases[token]
            if (record == null || !Instant.now(clock).isBefore(record.expiresAt)) {
                if (record != null) leases.remove(token, record)
                sendEmpty(exchange, HTTP_NOT_FOUND)
                return
            }
            if (!matchesSnapshot(record)) {
                leases.remove(token, record)
                sendEmpty(exchange, HTTP_GONE)
                return
            }

            val rangeHeaders = exchange.requestHeaders["Range"].orEmpty()
            val range = if (rangeHeaders.size > 1) ParsedRange.Invalid
            else parseSingleByteRange(rangeHeaders.firstOrNull(), record.snapshot.size)
            if (range is ParsedRange.Invalid) {
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                exchange.responseHeaders.set("Content-Range", "bytes */${record.snapshot.size}")
                exchange.responseHeaders.set("Content-Length", "0")
                sendEmpty(exchange, HTTP_RANGE_NOT_SATISFIABLE)
                return
            }
            val selected = (range as? ParsedRange.Valid)?.range
            val start = selected?.first ?: 0L
            val end = selected?.last ?: (record.snapshot.size - 1)
            val contentLength = if (end < start) 0L else end - start + 1L
            val status = if (selected == null) HTTP_OK else HTTP_PARTIAL_CONTENT

            val channel = try {
                openStableFile(record)
            } catch (_: IOException) {
                leases.remove(token, record)
                sendEmpty(exchange, HTTP_GONE)
                return
            } catch (_: SecurityException) {
                leases.remove(token, record)
                sendEmpty(exchange, HTTP_GONE)
                return
            }
            channel.use { file ->
                exchange.responseHeaders.set("Content-Type", record.mimeType)
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                exchange.responseHeaders.set("Content-Length", contentLength.toString())
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
                if (selected != null) {
                    exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${record.snapshot.size}")
                }
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(status, -1)
                    return@use
                }
                exchange.sendResponseHeaders(status, contentLength)
                try {
                    file.position(start)
                    streamRange(file, exchange, contentLength)
                    if (!matchesSnapshot(record)) throw IOException("The authorized file changed during transfer.")
                } catch (_: IOException) {
                    // Once response headers are sent, closing the exchange is the only safe failure mode.
                }
            }
        } catch (_: IOException) {
            // A disconnected renderer should not terminate a request worker or expose server details.
        } finally {
            exchange.close()
        }
    }

    private fun openStableFile(record: LeaseRecord): FileChannel {
        if (!matchesSnapshot(record)) throw IOException("The authorized file changed.")
        val channel = try {
            FileChannel.open(record.path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        } catch (error: IOException) {
            throw error
        } catch (error: SecurityException) {
            throw error
        }
        try {
            if (!matchesSnapshot(record)) throw IOException("The authorized file was replaced.")
            return channel
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
    }

    private fun matchesSnapshot(record: LeaseRecord): Boolean = try {
        val current = readAttributes(record.path)
        !hasSymbolicLinkComponent(record.path) && current.isRegularFile && !current.isSymbolicLink &&
            current.size() == record.snapshot.size &&
            current.lastModifiedTime() == record.snapshot.modifiedTime &&
            current.creationTime() == record.snapshot.creationTime &&
            (record.snapshot.fileKey == null || current.fileKey() == record.snapshot.fileKey)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun streamRange(channel: FileChannel, exchange: HttpExchange, length: Long) {
        var remaining = length
        val buffer = ByteBuffer.allocate(TRANSFER_CHUNK_BYTES)
        val output = exchange.responseBody
        while (remaining > 0L) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val count = channel.read(buffer)
            if (count <= 0) throw IOException("The authorized file ended during transfer.")
            output.write(buffer.array(), 0, count)
            remaining -= count
        }
        output.flush()
    }

    private fun revoke(token: String) {
        leases.remove(token)
    }

    private fun reissue(
        token: String,
        target: DesktopUpnpMediaServer,
        mimeType: String,
    ): DesktopUpnpMediaLease? {
        val original = activeLeaseRecord(token) ?: return null
        val replacement = target.installReissuedLease(original, mimeType) ?: return null
        return try {
            if (activeLeaseRecord(token)?.hasSameAuthorizationAs(original) == true) {
                replacement
            } else {
                replacement.revoke()
                null
            }
        } catch (error: Exception) {
            replacement.revoke()
            throw error
        }
    }

    /** Returns only a still-live lease whose original file identity remains intact. */
    private fun activeLeaseRecord(token: String): LeaseRecord? {
        if (closed.get()) return null
        val now = Instant.now(clock)
        var activeRecord: LeaseRecord? = null
        leases.computeIfPresent(token) { _, record ->
            if (closed.get() || !now.isBefore(record.expiresAt) || !matchesSnapshot(record)) {
                null
            } else {
                activeRecord = record
                record
            }
        }
        return activeRecord.takeUnless { closed.get() }
    }

    private fun installReissuedLease(original: LeaseRecord, mimeType: String): DesktopUpnpMediaLease? {
        if (closed.get() || !isAllowedMimeType(original.path, mimeType) ||
            !hasExpectedAudioSignature(original.path, mimeType) || !matchesSnapshot(original)
        ) {
            return null
        }
        val now = Instant.now(clock)
        val expiresAt = try {
            now.plus(leaseTtl)
        } catch (_: Exception) {
            return null
        }
        leases.entries.removeIf { !now.isBefore(it.value.expiresAt) }
        if (closed.get() || !matchesSnapshot(original)) return null

        val token = newToken()
        val record = LeaseRecord(original.path, original.snapshot, mimeType, expiresAt)
        if (leases.putIfAbsent(token, record) != null) return null
        if (closed.get() || !matchesSnapshot(record)) {
            leases.remove(token, record)
            return null
        }
        val lease = mediaLease(token, record)
        if (closed.get()) {
            lease.revoke()
            return null
        }
        return lease
    }

    private fun mediaLease(token: String, record: LeaseRecord): DesktopUpnpMediaLease {
        val host = uriHost(httpServer.address.address)
        val uri = URI("http://$host:${httpServer.address.port}$MEDIA_PATH_PREFIX$token")
        return DesktopUpnpMediaLease(
            uri,
            record.mimeType,
            record.snapshot.size,
            renewAction = { renew(token) },
            reissueAction = { target, mimeType -> reissue(token, target, mimeType) },
            revokeAction = { revoke(token) },
        )
    }

    private fun LeaseRecord.hasSameAuthorizationAs(other: LeaseRecord): Boolean =
        path == other.path && snapshot == other.snapshot && mimeType == other.mimeType

    /** Extends a live lease without changing its token, but never reauthorizes a changed source. */
    private fun renew(token: String): Boolean {
        if (closed.get()) return false
        val now = Instant.now(clock)
        val expiresAt = try {
            now.plus(leaseTtl)
        } catch (_: Exception) {
            leases.remove(token)
            return false
        }
        var renewed = false
        leases.computeIfPresent(token) { _, record ->
            if (closed.get() || !now.isBefore(record.expiresAt) || !matchesSnapshot(record)) {
                null
            } else {
                renewed = true
                record.copy(expiresAt = expiresAt)
            }
        }
        // close() can race with computeIfPresent; do not report success if it won that race.
        if (closed.get()) {
            leases.remove(token)
            return false
        }
        return renewed
    }

    private fun sendEmpty(exchange: HttpExchange, status: Int) {
        if (exchange.responseHeaders.getFirst("Content-Length") == null) {
            exchange.responseHeaders.set("Content-Length", "0")
        }
        exchange.sendResponseHeaders(status, -1)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            leases.clear()
            httpServer.stop(0)
            executor.shutdownNow()
        }
    }

    private data class FileSnapshot(
        val size: Long,
        val modifiedTime: FileTime,
        val creationTime: FileTime,
        val fileKey: Any?,
    )

    private data class LeaseRecord(
        val path: Path,
        val snapshot: FileSnapshot,
        val mimeType: String,
        val expiresAt: Instant,
    )

    companion object {
        private const val MEDIA_PATH_PREFIX = "/media/"
        private const val TRANSFER_CHUNK_BYTES = 64 * 1024
        private const val DEFAULT_MAX_CONCURRENT_REQUESTS = 4
        private const val MAX_CONCURRENT_REQUESTS = 32
        private const val REQUEST_QUEUE_FACTOR = 4
        private const val BACKLOG = 16
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_GONE = 410
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private val DEFAULT_LEASE_TTL: Duration = Duration.ofMinutes(5)
        private val TOKEN_PATH = Regex("^/media/[A-Za-z0-9_-]{43}$")
        private val SECURE_RANDOM = SecureRandom()

        /** Selects the local unicast source address the operating system would route to the renderer. */
        fun routeLocalAddress(rendererAddress: InetAddress, rendererPort: Int = 1900): InetAddress {
            require(!rendererAddress.isAnyLocalAddress && !rendererAddress.isMulticastAddress) {
                "The renderer address must be a unicast address."
            }
            require(rendererPort in 1..65_535) { "The renderer port is invalid." }
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(rendererAddress, rendererPort))
                return socket.localAddress.takeIf(::isLocalUnicast)
                    ?: throw IOException("No local unicast route exists to the renderer.")
            }
        }

        private fun defaultMimeType(source: DesktopTrackSource.LocalFile): String = when {
            sourceFileExtension(source) == "wav" -> MIME_WAV
            sourceFileExtension(source) == "flac" -> MIME_FLAC
            else -> throw IllegalArgumentException("Only WAV and FLAC local files can be served.")
        }

        private fun isAllowedMimeType(source: DesktopTrackSource.LocalFile, mimeType: String): Boolean {
            return isAllowedMimeType(sourceFileExtension(source), mimeType)
        }

        private fun isAllowedMimeType(path: Path, mimeType: String): Boolean {
            val extension = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return isAllowedMimeType(extension, mimeType)
        }

        private fun isAllowedMimeType(extension: String, mimeType: String): Boolean {
            return when (extension) {
                "wav" -> mimeType == MIME_WAV || mimeType == MIME_X_WAV
                "flac" -> mimeType == MIME_FLAC
                else -> false
            }
        }

        private fun sourceFileExtension(source: DesktopTrackSource.LocalFile): String =
            runCatching { Path.of(source.absolutePath).fileName?.toString()?.substringAfterLast('.', "")?.lowercase() }
                .getOrNull().orEmpty()

        private fun captureSnapshot(path: Path, mimeType: String): FileSnapshot {
            require(!hasSymbolicLinkComponent(path)) { "Symbolic links are not allowed in the local audio path." }
            val initial = readAttributes(path)
            require(initial.isRegularFile && !initial.isSymbolicLink) { "Only regular, non-symlink files can be served." }
            require(initial.size() > 0L) { "The local audio file is empty." }
            require(hasExpectedAudioSignature(path, mimeType)) { "The local file does not match its WAV/FLAC MIME type." }
            val afterRead = readAttributes(path)
            require(sameIdentityAndMetadata(initial, afterRead)) { "The local file changed while it was being authorized." }
            require(afterRead.fileKey() != null || afterRead.creationTime().toMillis() != 0L) {
                "This filesystem does not expose a stable identity for the local file."
            }
            return FileSnapshot(afterRead.size(), afterRead.lastModifiedTime(), afterRead.creationTime(), afterRead.fileKey())
        }

        private fun hasExpectedAudioSignature(path: Path, mimeType: String): Boolean {
            val header = ByteArray(12)
            val count = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(header)
                while (buffer.hasRemaining()) {
                    val read = channel.read(buffer)
                    if (read < 0) break
                }
                buffer.position()
            }
            return when (mimeType) {
                MIME_FLAC -> count >= 4 && header.copyOfRange(0, 4).contentEquals("fLaC".toByteArray(Charsets.US_ASCII))
                MIME_WAV, MIME_X_WAV -> count >= 12 &&
                    (header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) ||
                        header.copyOfRange(0, 4).contentEquals("RF64".toByteArray(Charsets.US_ASCII)) ||
                        header.copyOfRange(0, 4).contentEquals("BW64".toByteArray(Charsets.US_ASCII))) &&
                    header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))
                else -> false
            }
        }

        private fun readAttributes(path: Path): BasicFileAttributes =
            java.nio.file.Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

        private fun hasSymbolicLinkComponent(path: Path): Boolean {
            var current = path.root ?: return true
            for (component in path) {
                current = current.resolve(component)
                if (java.nio.file.Files.isSymbolicLink(current)) return true
            }
            return false
        }

        private fun sameIdentityAndMetadata(first: BasicFileAttributes, second: BasicFileAttributes): Boolean =
            first.isRegularFile && second.isRegularFile && !first.isSymbolicLink && !second.isSymbolicLink &&
                first.size() == second.size() && first.lastModifiedTime() == second.lastModifiedTime() &&
                first.creationTime() == second.creationTime() &&
                (first.fileKey() == null || first.fileKey() == second.fileKey())

        private fun newToken(): String {
            val bytes = ByteArray(32)
            SECURE_RANDOM.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun uriHost(address: InetAddress): String = if (address.address.size == 16) {
            "[${address.hostAddress.replace("%", "%25")}]"
        } else {
            address.hostAddress
        }

        private fun isLocalUnicast(address: InetAddress): Boolean =
            !address.isAnyLocalAddress && !address.isMulticastAddress &&
                (address.isLoopbackAddress || runCatching { NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false))
    }
}

/** A capability URL plus the exact representation metadata sent to the renderer. */
internal class DesktopUpnpMediaLease internal constructor(
    val mediaUri: URI,
    val mimeType: String,
    val fileSize: Long,
    private val renewAction: () -> Boolean,
    private val reissueAction: (DesktopUpnpMediaServer, String) -> DesktopUpnpMediaLease?,
    private val revokeAction: () -> Unit,
) : AutoCloseable {
    private val revoked = AtomicBoolean(false)

    /** Keeps this capability URL alive only while its original authorization remains valid. */
    @Synchronized
    fun renew(): Boolean {
        if (revoked.get()) return false
        val renewed = try {
            renewAction()
        } catch (_: Exception) {
            false
        }
        if (renewed) return true
        revoked.set(true)
        revokeAction()
        return false
    }

    @Synchronized
    internal fun reissueTo(target: DesktopUpnpMediaServer, mimeType: String): DesktopUpnpMediaLease? {
        if (revoked.get()) return null
        return try {
            reissueAction(target, mimeType)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun revoke() {
        if (revoked.compareAndSet(false, true)) revokeAction()
    }

    override fun close() = revoke()
}

internal data class DesktopByteRange(val first: Long, val last: Long)

internal sealed interface ParsedRange {
    data class Valid(val range: DesktopByteRange) : ParsedRange
    data object Invalid : ParsedRange
}

/** Implements one RFC 9110 byte range. Multiple and malformed ranges are rejected with 416. */
internal fun parseSingleByteRange(header: String?, fileSize: Long): ParsedRange? {
    if (header == null) return null
    if (fileSize < 0L || ',' in header) return ParsedRange.Invalid
    val value = header.trim()
    if (!value.startsWith("bytes=", ignoreCase = true)) return ParsedRange.Invalid
    val spec = value.substringAfter('=', "").trim()
    if (spec.isEmpty() || spec.any(Char::isWhitespace)) return ParsedRange.Invalid
    val separator = spec.indexOf('-')
    if (separator < 0 || separator != spec.lastIndexOf('-')) return ParsedRange.Invalid
    val startPart = spec.substring(0, separator)
    val endPart = spec.substring(separator + 1)
    if (startPart.isEmpty()) {
        val suffixLength = parseAsciiLong(endPart)?.takeIf { it > 0L } ?: return ParsedRange.Invalid
        if (fileSize == 0L) return ParsedRange.Invalid
        val selectedLength = minOf(suffixLength, fileSize)
        return ParsedRange.Valid(DesktopByteRange(fileSize - selectedLength, fileSize - 1L))
    }
    val start = parseAsciiLong(startPart) ?: return ParsedRange.Invalid
    val requestedEnd = if (endPart.isEmpty()) fileSize - 1L else {
        parseAsciiLong(endPart) ?: return ParsedRange.Invalid
    }
    if (fileSize == 0L || start >= fileSize || requestedEnd < start) return ParsedRange.Invalid
    return ParsedRange.Valid(DesktopByteRange(start, minOf(requestedEnd, fileSize - 1L)))
}

private fun parseAsciiLong(value: String): Long? =
    value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()

internal fun isDesktopUpnpRequestFromRenderer(remoteAddress: InetAddress?, rendererAddress: InetAddress): Boolean =
    remoteAddress != null && remoteAddress.address.contentEquals(rendererAddress.address)

private class DaemonThreadFactory : ThreadFactory {
    private val nextId = java.util.concurrent.atomic.AtomicInteger()
    override fun newThread(task: Runnable): Thread = Thread(task, "upnp-media-${nextId.incrementAndGet()}").apply {
        isDaemon = true
    }
}

private const val MIME_WAV = "audio/wav"
private const val MIME_X_WAV = "audio/x-wav"
private const val MIME_FLAC = "audio/flac"
