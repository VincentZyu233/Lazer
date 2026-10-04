package dev.naominet.lazer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class DesktopUpnpGenaLease(
    val sid: String,
    /** Null means that the renderer granted an infinite subscription. */
    val timeoutSeconds: Long?,
) {
    val renewalDelayMillis: Long?
        get() = timeoutSeconds?.let { seconds ->
            // Renew with a quarter of the lease still available, bounded against extreme values.
            (seconds.coerceAtMost(MAX_RENEWAL_DELAY_SECONDS) * 750L).coerceAtLeast(MIN_RENEWAL_DELAY_MILLIS)
        }

    private companion object {
        const val MIN_RENEWAL_DELAY_MILLIS = 250L
        const val MAX_RENEWAL_DELAY_SECONDS = 24L * 60L * 60L
    }
}

internal interface DesktopUpnpGenaTransport {
    fun subscribe(
        eventSubUri: URI,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        callbackUri: URI,
        requestedTimeoutSeconds: Long,
    ): DesktopUpnpGenaLease

    fun renew(
        eventSubUri: URI,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        sid: String,
        requestedTimeoutSeconds: Long,
    ): DesktopUpnpGenaLease

    fun unsubscribe(
        eventSubUri: URI,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        sid: String,
    )
}

internal class DesktopUpnpGenaHttpException(
    val statusCode: Int,
    method: String,
) : IOException("UPnP $method returned HTTP $statusCode.")

/** Bounded backoff for recreating a local GENA callback receiver after construction fails. */
internal class DesktopUpnpEventReceiverRetryPolicy {
    private var delayIndex = 0
    private var nextAttemptDeadlineNanos: Long? = null

    /** Returns true when another construction attempt may start at [nowNanos]. */
    fun isAttemptDue(nowNanos: Long): Boolean {
        val deadline = nextAttemptDeadlineNanos ?: return true
        // Subtraction, rather than comparing absolute values, remains correct across nanoTime wrap.
        return nowNanos - deadline >= 0L
    }

    /** Records a failed attempt and schedules the next one using the bounded delay sequence. */
    fun recordFailure(nowNanos: Long) {
        val delayNanos = RETRY_DELAYS_MILLIS[delayIndex] * NANOS_PER_MILLI
        nextAttemptDeadlineNanos = nowNanos + delayNanos
        if (delayIndex < RETRY_DELAYS_MILLIS.lastIndex) delayIndex += 1
    }

    /** Clears the backoff after a receiver is created or the playback session is reset. */
    fun reset() {
        delayIndex = 0
        nextAttemptDeadlineNanos = null
    }

    private companion object {
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 3_000L, 6_000L, 12_000L, 30_000L)
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** GENA transport that pins every request to the local interface used by the callback receiver. */
internal class DesktopUpnpGenaClient(
    private val transport: DesktopUpnpGenaTransport = DesktopHttpUpnpGenaTransport,
    private val requestedTimeoutSeconds: Long = DEFAULT_SUBSCRIPTION_TIMEOUT_SECONDS,
) {
    init {
        require(requestedTimeoutSeconds in 1L..MAX_SUBSCRIPTION_TIMEOUT_SECONDS) {
            "UPnP event subscription timeout is outside the supported range."
        }
    }

    fun subscribe(
        device: DesktopUpnpRendererDevice,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        callbackUri: URI,
        serviceKind: DesktopUpnpRendererServiceKind = DesktopUpnpRendererServiceKind.AV_TRANSPORT,
    ): DesktopUpnpGenaLease {
        val eventSubUri = requireEventEndpoint(device, serviceKind)
        require(isCallbackUriForLocalAddress(callbackUri, localAddress)) {
            "UPnP event callback does not use the selected local interface."
        }
        requireCompatibleAddresses(rendererAddress, localAddress)
        return transport.subscribe(
            eventSubUri,
            rendererAddress,
            localAddress,
            callbackUri,
            requestedTimeoutSeconds,
        )
    }

    fun renew(
        device: DesktopUpnpRendererDevice,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        sid: String,
        serviceKind: DesktopUpnpRendererServiceKind = DesktopUpnpRendererServiceKind.AV_TRANSPORT,
    ): DesktopUpnpGenaLease = transport.renew(
        requireEventEndpoint(device, serviceKind),
        rendererAddress,
        localAddress,
        requireValidSid(sid),
        requestedTimeoutSeconds,
    )

    fun unsubscribe(
        device: DesktopUpnpRendererDevice,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        sid: String,
        serviceKind: DesktopUpnpRendererServiceKind = DesktopUpnpRendererServiceKind.AV_TRANSPORT,
    ) {
        transport.unsubscribe(
            requireEventEndpoint(device, serviceKind),
            rendererAddress,
            localAddress,
            requireValidSid(sid),
        )
    }

    private fun requireEventEndpoint(
        device: DesktopUpnpRendererDevice,
        serviceKind: DesktopUpnpRendererServiceKind,
    ): URI {
        val serviceName = serviceKind.serviceName
        val eventSubUri = device.services[serviceKind]?.eventSubUri
            ?: throw IOException("Renderer does not expose a $serviceName event URL.")
        require(eventSubUri.scheme.equals("http", ignoreCase = true) &&
            isDesktopUpnpControlUriAllowed(device.descriptionUri, eventSubUri)
        ) {
            "UPnP $serviceName event URL is outside the device origin or does not use HTTP."
        }
        require(eventSubUri.rawUserInfo == null && eventSubUri.rawFragment == null) {
            "UPnP event URL contains unsupported URL components."
        }
        return eventSubUri
    }

    private companion object {
        const val DEFAULT_SUBSCRIPTION_TIMEOUT_SECONDS = 1_800L
        const val MAX_SUBSCRIPTION_TIMEOUT_SECONDS = 86_400L
    }
}

/** Serializes initial SUBSCRIBE retries, lease renewals, and reconnect-triggered refreshes. */
internal class DesktopUpnpGenaSubscriptionManager(
    private val initialLease: DesktopUpnpGenaLease?,
    private val refreshRequests: Channel<Unit>,
    private val retryDelayMillis: Long,
    private val isCurrent: () -> Boolean,
    private val subscribe: suspend () -> DesktopUpnpGenaLease,
    private val renew: suspend (sid: String) -> DesktopUpnpGenaLease,
    private val installLease: (DesktopUpnpGenaLease) -> Boolean,
    private val onLeaseChanged: (DesktopUpnpGenaLease?) -> Unit,
    private val onSidCleared: () -> Unit,
    private val abandonLease: suspend (DesktopUpnpGenaLease) -> Unit,
) {
    init {
        require(retryDelayMillis > 0L)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        var lease = initialLease
        var retrySoon = lease == null
        var attemptImmediately = false
        while (isActive && isCurrent()) {
            if (!attemptImmediately) {
                val waitMillis = if (retrySoon) retryDelayMillis else lease?.renewalDelayMillis
                if (waitMillis == null) {
                    refreshRequests.receive()
                } else {
                    withTimeoutOrNull(waitMillis) { refreshRequests.receive() }
                }
            }
            attemptImmediately = false
            if (!isCurrent()) break

            val currentLease = lease
            if (currentLease == null) {
                val subscribed = try {
                    withContext(NonCancellable) {
                        val result = subscribe()
                        try {
                            if (installLease(result)) result
                            else {
                                abandonLease(result)
                                null
                            }
                        } catch (error: Exception) {
                            runCatching { abandonLease(result) }
                            throw error
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (!isCurrent()) break
                    retrySoon = true
                    continue
                }
                if (subscribed == null) {
                    if (!isCurrent()) break
                    retrySoon = true
                    continue
                }
                lease = subscribed
                retrySoon = false
                continue
            }

            try {
                val renewed = renew(currentLease.sid)
                if (!isCurrent()) break
                onLeaseChanged(renewed)
                lease = renewed
                retrySoon = false
            } catch (error: CancellationException) {
                throw error
            } catch (error: DesktopUpnpGenaHttpException) {
                if (error.statusCode != 412) {
                    retrySoon = true
                    continue
                }
                // A 412 means this SID is no longer valid. Reject its notifications and
                // immediately establish a new subscription with the existing callback URL.
                onSidCleared()
                lease = null
                onLeaseChanged(null)
                retrySoon = false
                attemptImmediately = true
            } catch (_: Exception) {
                retrySoon = true
            }
        }
    }
}

private object DesktopHttpUpnpGenaTransport : DesktopUpnpGenaTransport {
    override fun subscribe(
        eventSubUri: URI,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        callbackUri: URI,
        requestedTimeoutSeconds: Long,
    ): DesktopUpnpGenaLease = perform(
        method = "SUBSCRIBE",
        eventSubUri = eventSubUri,
        rendererAddress = rendererAddress,
        localAddress = localAddress,
        extraHeaders = listOf(
            "CALLBACK: <${callbackUri.toASCIIString()}>",
            "NT: upnp:event",
            "TIMEOUT: Second-${validateRequestedTimeout(requestedTimeoutSeconds)}",
        ),
        requireSid = true,
        requireTimeout = true,
    ).toLease()

    override fun renew(
        eventSubUri: URI,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        sid: String,
        requestedTimeoutSeconds: Long,
    ): DesktopUpnpGenaLease {
        val lease = perform(
            method = "SUBSCRIBE",
            eventSubUri = eventSubUri,
            rendererAddress = rendererAddress,
            localAddress = localAddress,
            extraHeaders = listOf(
                "SID: ${requireValidSid(sid)}",
                "TIMEOUT: Second-${validateRequestedTimeout(requestedTimeoutSeconds)}",
            ),
            requireSid = false,
            requireTimeout = true,
        ).toLease(fallbackSid = sid)
        if (lease.sid != sid) throw IOException("UPnP renewal returned a different subscription SID.")
        return lease
    }

    override fun unsubscribe(
        eventSubUri: URI,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        sid: String,
    ) {
        perform(
            method = "UNSUBSCRIBE",
            eventSubUri = eventSubUri,
            rendererAddress = rendererAddress,
            localAddress = localAddress,
            extraHeaders = listOf("SID: ${requireValidSid(sid)}"),
            requireSid = false,
            requireTimeout = false,
        )
    }

    private fun perform(
        method: String,
        eventSubUri: URI,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        extraHeaders: List<String>,
        requireSid: Boolean,
        requireTimeout: Boolean,
    ): GenaResponse {
        require(method == "SUBSCRIBE" || method == "UNSUBSCRIBE")
        requireCompatibleAddresses(rendererAddress, localAddress)
        require(eventSubUri.scheme.equals("http", ignoreCase = true) && eventSubUri.rawUserInfo == null &&
            eventSubUri.rawFragment == null
        ) {
            "UPnP event URL is invalid."
        }
        val port = desktopUpnpEffectivePort(eventSubUri)
        val target = eventSubUri.rawPath.orEmpty().ifEmpty { "/" } +
            eventSubUri.rawQuery?.let { "?$it" }.orEmpty()
        require(target.startsWith('/') && '\r' !in target && '\n' !in target && ' ' !in target) {
            "UPnP event request target is invalid."
        }
        val hostHeader = eventSubUri.rawAuthority ?: throw IOException("UPnP event URL has no host.")
        val requestHeaders = buildString {
            append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Connection: close\r\n")
            append("Accept: */*\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)

        Socket().use { socket ->
            socket.bind(InetSocketAddress(localAddress, 0))
            socket.connect(InetSocketAddress(rendererAddress, port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = READ_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write(requestHeaders)
                flush()
            }
            val response = readHeaders(socket.getInputStream())
            if (response.statusCode !in 200..299) {
                throw DesktopUpnpGenaHttpException(response.statusCode, method)
            }
            if (requireSid && response.sid == null) throw IOException("UPnP subscription response has no SID.")
            if (requireTimeout && singleHeader(response.headers, "timeout") == null) {
                throw IOException("UPnP subscription response has no TIMEOUT header.")
            }
            return response
        }
    }

    private fun GenaResponse.toLease(fallbackSid: String? = null): DesktopUpnpGenaLease = DesktopUpnpGenaLease(
        sid = sid ?: fallbackSid ?: throw IOException("UPnP subscription response has no SID."),
        timeoutSeconds = timeoutSeconds,
    )

    private fun readHeaders(input: java.io.InputStream): GenaResponse {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < MAX_RESPONSE_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw IOException("UPnP event response ended before its headers completed.")
            output.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return parseGenaResponseHeaders(output.toByteArray())
        }
        throw IOException("UPnP event response headers exceed the size limit.")
    }

    private fun parseGenaResponseHeaders(bytes: ByteArray): GenaResponse {
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val lines = text.removeSuffix("\r\n\r\n").split("\r\n")
        val status = HTTP_STATUS.matchEntire(lines.firstOrNull().orEmpty())
            ?: throw IOException("UPnP event response has an invalid status line.")
        val headers = linkedMapOf<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("UPnP event response contains an invalid header.")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (!HTTP_HEADER_NAME.matches(name) || '\r' in value || '\n' in value) {
                throw IOException("UPnP event response contains an invalid header.")
            }
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }
        return GenaResponse(
            statusCode = status.groupValues[1].toInt(),
            headers = headers.mapValues { it.value.toList() },
            sid = singleHeader(headers.mapValues { it.value.toList() }, "sid")?.let(::requireValidSid),
            timeoutSeconds = singleHeader(headers.mapValues { it.value.toList() }, "timeout")
                ?.let(::parseGenaTimeout),
        )
    }

    private fun singleHeader(headers: Map<String, List<String>>, key: String): String? {
        val values = headers[key].orEmpty()
        if (values.size > 1) throw IOException("UPnP event response has duplicate $key headers.")
        return values.singleOrNull()
    }

    private data class GenaResponse(
        val statusCode: Int = 200,
        val headers: Map<String, List<String>> = emptyMap(),
        val sid: String?,
        val timeoutSeconds: Long?,
    )

}

internal fun parseGenaTimeout(value: String?): Long? {
    val match = value?.trim()?.let { GENA_TIMEOUT.matchEntire(it) }
        ?: throw IOException("UPnP event response has an invalid TIMEOUT header.")
    val seconds = match.groupValues[1]
    if (seconds.equals("infinite", ignoreCase = true)) return null
    return seconds.toLongOrNull()?.takeIf { it > 0L }
        ?: throw IOException("UPnP event response has an invalid TIMEOUT header.")
}

internal fun requireValidSid(sid: String): String {
    if (!GENA_SID.matches(sid)) throw IOException("UPnP event subscription SID is invalid.")
    return sid
}

internal fun desktopUpnpEffectivePort(uri: URI): Int = when {
    uri.port in 1..65_535 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    else -> 80
}

private fun validateRequestedTimeout(seconds: Long): Long {
    require(seconds in 1L..86_400L) { "UPnP subscription timeout is outside the supported range." }
    return seconds
}

private fun isCallbackUriForLocalAddress(callbackUri: URI, localAddress: InetAddress): Boolean {
    if (!isHttpUri(callbackUri) || callbackUri.rawUserInfo != null || callbackUri.rawFragment != null ||
        callbackUri.port !in 1..65_535 || callbackUri.rawPath.isNullOrEmpty() || callbackUri.rawQuery != null
    ) return false
    val callbackAddress = desktopUpnpNumericAddress(callbackUri) ?: return false
    return callbackAddress.address.contentEquals(localAddress.address)
}

private fun requireCompatibleAddresses(rendererAddress: InetAddress, localAddress: InetAddress) {
    require(!rendererAddress.isAnyLocalAddress && !rendererAddress.isMulticastAddress &&
        !localAddress.isAnyLocalAddress && !localAddress.isMulticastAddress &&
        rendererAddress.javaClass == localAddress.javaClass
    ) { "UPnP event transport addresses must be matching unicast IPv4 or IPv6 addresses." }
}

private val GENA_TIMEOUT = Regex("Second-([0-9]+|infinite)", RegexOption.IGNORE_CASE)
private val GENA_SID = Regex("uuid:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private const val CONNECT_TIMEOUT_MILLIS = 2_000
private const val READ_TIMEOUT_MILLIS = 3_000
private const val MAX_RESPONSE_HEADER_BYTES = 16 * 1024
private val HTTP_STATUS = Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*)?")
private val HTTP_HEADER_NAME = Regex("[a-z0-9!#$%&'*+.^_`|~-]+")
