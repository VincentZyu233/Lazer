package dev.naominet.lazer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

internal const val DESKTOP_OPENHOME_MAX_GENA_EVENT_BODY_BYTES = 4 * 1024 * 1024

/**
 * Short-lived callback endpoint for one UPnP GENA subscription.
 *
 * Create this before sending SUBSCRIBE, put [callbackUri] in CALLBACK, then call [acceptSid] with
 * the SID returned by the renderer. NOTIFY requests are accepted only from [rendererAddress] and
 * only for that SID. The event consumer runs on a bounded HTTP worker and should return quickly.
 */
internal class DesktopUpnpEventReceiver(
    bindAddress: InetAddress,
    private val rendererAddress: InetAddress,
    port: Int = 0,
    private val onEvent: (DesktopUpnpEvent) -> Unit,
    maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
    private val maxEventBodyBytes: Int = DEFAULT_MAX_EVENT_BODY_BYTES,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val stateLock = Any()
    private var acceptedSid: String? = null
    private var lastSequence: Long? = null
    private val executor: ThreadPoolExecutor
    private val token = newDesktopUpnpEventToken()
    private val path = "$EVENT_PATH_PREFIX$token"
    private val httpServer: HttpServer

    val localAddress: InetSocketAddress
        get() = httpServer.address

    /** Random, unguessable callback path. The URI is ready to put in a GENA CALLBACK header. */
    val callbackUri: URI

    init {
        require(isDesktopUpnpEventLocalUnicast(bindAddress)) {
            "The event receiver must bind to a local unicast address."
        }
        require(!rendererAddress.isAnyLocalAddress && !rendererAddress.isMulticastAddress) {
            "The renderer address must be a unicast address."
        }
        require(port in 0..65_535) { "The event receiver port is invalid." }
        require(maxConcurrentRequests in 1..MAX_CONCURRENT_REQUESTS) {
            "The event receiver concurrency limit is outside the supported range."
        }
        require(maxEventBodyBytes in 1..MAX_SUPPORTED_EVENT_BODY_BYTES) {
            "The event receiver body limit is outside the supported range."
        }

        val createdExecutor = ThreadPoolExecutor(
            maxConcurrentRequests,
            maxConcurrentRequests,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(maxConcurrentRequests * REQUEST_QUEUE_FACTOR),
            EventThreadFactory(),
            ThreadPoolExecutor.AbortPolicy(),
        )
        var createdServer: HttpServer? = null
        try {
            val server = HttpServer.create(InetSocketAddress(bindAddress, port), BACKLOG)
            createdServer = server
            server.executor = createdExecutor
            server.createContext(path, ::handleRequest)
            val address = server.address.address
            val host = if (address.address.size == 16) {
                "[${address.hostAddress.replace("%", "%25")}]"
            } else {
                address.hostAddress
            }
            val createdCallbackUri = URI("http://$host:${server.address.port}$path")
            server.start()

            executor = createdExecutor
            httpServer = server
            callbackUri = createdCallbackUri
        } catch (failure: Throwable) {
            // Construction can fail after the socket or executor has been allocated. Keep a
            // failed retry from leaking either resource, while preserving the original error.
            runCatching { createdServer?.stop(0) }
                .exceptionOrNull()
                ?.takeUnless { it === failure }
                ?.let(failure::addSuppressed)
            runCatching { createdExecutor.shutdownNow() }
                .exceptionOrNull()
                ?.takeUnless { it === failure }
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    /** Installs the SID returned by SUBSCRIBE and resets notification sequence tracking. */
    fun acceptSid(sid: String) {
        require(isDesktopUpnpEventSid(sid)) { "The UPnP event SID is invalid." }
        check(!closed.get()) { "The event receiver is closed." }
        synchronized(stateLock) {
            check(!closed.get()) { "The event receiver is closed." }
            acceptedSid = sid
            lastSequence = null
        }
    }

    /** Stops accepting notifications and forgets the active SID. */
    fun clearSid() {
        synchronized(stateLock) {
            acceptedSid = null
            lastSequence = null
        }
    }

    private fun handleRequest(exchange: HttpExchange) {
        try {
            when {
                !isDesktopUpnpRequestFromRenderer(exchange.remoteAddress.address, rendererAddress) ->
                    sendEmpty(exchange, HTTP_FORBIDDEN)
                exchange.requestURI.rawPath != path || exchange.requestURI.rawQuery != null ->
                    sendEmpty(exchange, HTTP_NOT_FOUND)
                exchange.requestMethod != "NOTIFY" -> {
                    exchange.responseHeaders.set("Allow", "NOTIFY")
                    sendEmpty(exchange, HTTP_METHOD_NOT_ALLOWED)
                }
                else -> handleNotify(exchange)
            }
        } catch (_: Exception) {
            sendEmptySafely(exchange, HTTP_BAD_REQUEST)
        } finally {
            exchange.close()
        }
    }

    private fun handleNotify(exchange: HttpExchange) {
        val nt = singleHeader(exchange, "NT") ?: return sendEmpty(exchange, HTTP_BAD_REQUEST)
        val nts = singleHeader(exchange, "NTS") ?: return sendEmpty(exchange, HTTP_BAD_REQUEST)
        val sid = singleHeader(exchange, "SID") ?: return sendEmpty(exchange, HTTP_BAD_REQUEST)
        val sequenceText = singleHeader(exchange, "SEQ") ?: return sendEmpty(exchange, HTTP_BAD_REQUEST)
        if (!nt.equals("upnp:event", ignoreCase = true) ||
            !nts.equals("upnp:propchange", ignoreCase = true) ||
            !isDesktopUpnpEventSid(sid)
        ) {
            return sendEmpty(exchange, HTTP_BAD_REQUEST)
        }
        val sequence = parseDesktopUpnpEventSequence(sequenceText)
            ?: return sendEmpty(exchange, HTTP_BAD_REQUEST)

        val contentLengths = exchange.requestHeaders["Content-length"].orEmpty()
        if (contentLengths.size > 1) return sendEmpty(exchange, HTTP_BAD_REQUEST)
        val declaredLength = contentLengths.singleOrNull()?.toLongOrNull()
        if (contentLengths.isNotEmpty() && (declaredLength == null || declaredLength < 0L)) {
            return sendEmpty(exchange, HTTP_BAD_REQUEST)
        }
        if (declaredLength != null && declaredLength > maxEventBodyBytes) {
            return sendEmpty(exchange, HTTP_CONTENT_TOO_LARGE)
        }

        val body = readBounded(exchange, maxEventBodyBytes)
            ?: return sendEmpty(exchange, HTTP_CONTENT_TOO_LARGE)
        if (body.isEmpty()) return sendEmpty(exchange, HTTP_BAD_REQUEST)
        val properties = try {
            parseDesktopUpnpEventProperties(body)
        } catch (_: Exception) {
            return sendEmpty(exchange, HTTP_BAD_REQUEST)
        }

        synchronized(stateLock) {
            if (closed.get()) return sendEmpty(exchange, HTTP_GONE)
            if (sid != acceptedSid) return sendEmpty(exchange, HTTP_PRECONDITION_FAILED)
            val previous = lastSequence
            if (previous != null && !isNewerDesktopUpnpEventSequence(previous, sequence)) {
                return sendEmpty(exchange, HTTP_PRECONDITION_FAILED)
            }
            lastSequence = sequence
            try {
                onEvent(DesktopUpnpEvent(sid = sid, sequence = sequence, properties = properties.toMap()))
            } catch (_: Exception) {
                // Event consumers must not turn a valid NOTIFY into a retry storm.
            }
        }
        sendEmpty(exchange, HTTP_OK)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clearSid()
        httpServer.stop(0)
        executor.shutdownNow()
    }

    private fun sendEmpty(exchange: HttpExchange, status: Int) {
        exchange.sendResponseHeaders(status, -1)
    }

    private fun sendEmptySafely(exchange: HttpExchange, status: Int) {
        try {
            sendEmpty(exchange, status)
        } catch (_: Exception) {
            // The peer may already have closed its request.
        }
    }

    private fun singleHeader(exchange: HttpExchange, name: String): String? {
        val values = exchange.requestHeaders[name] ?: return null
        if (values.size != 1) return null
        return values.single().trim().takeIf(String::isNotEmpty)
    }

    private fun readBounded(exchange: HttpExchange, limit: Int): ByteArray? {
        val result = ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val remainingPlusOne = limit - total + 1
            val read = exchange.requestBody.read(buffer, 0, minOf(buffer.size, remainingPlusOne))
            if (read < 0) break
            total += read
            if (total > limit) return null
            result.write(buffer, 0, read)
        }
        return result.toByteArray()
    }

    private companion object {
        const val EVENT_PATH_PREFIX = "/upnp/event/"
        const val DEFAULT_MAX_EVENT_BODY_BYTES = 256 * 1024
        const val MAX_SUPPORTED_EVENT_BODY_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_CONCURRENT_REQUESTS = 2
        const val MAX_CONCURRENT_REQUESTS = 8
        const val REQUEST_QUEUE_FACTOR = 4
        const val BACKLOG = 8
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_GONE = 410
        const val HTTP_PRECONDITION_FAILED = 412
        const val HTTP_CONTENT_TOO_LARGE = 413
    }

}

/** The decoded event property map includes values flattened from AVTransport LastChange XML. */
internal data class DesktopUpnpEvent(
    val sid: String,
    val sequence: Long,
    val properties: Map<String, String>,
) {
    val transportState: String? get() = properties["TransportState"]
    val transportStatus: String? get() = properties["TransportStatus"]
    val currentTransportActions: String? get() = properties["CurrentTransportActions"]
    val relativeTimePosition: String? get() = properties["RelativeTimePosition"]
    val currentTrackDuration: String? get() = properties["CurrentTrackDuration"]
    val currentTrackUri: String? get() = properties["CurrentTrackURI"]
    val currentTrackMetadata: String? get() = properties["CurrentTrackMetaData"]
    val avTransportUri: String? get() = properties["AVTransportURI"]
}

private fun parseDesktopUpnpEventProperties(body: ByteArray): Map<String, String> {
    val root = parseDesktopUpnpEventXml(body).documentElement ?: error("Missing GENA propertyset.")
    require(xmlLocalName(root) == "propertyset") { "Invalid GENA propertyset." }
    require(root.namespaceURI == GENA_EVENT_NAMESPACE) { "Invalid GENA event namespace." }

    val properties = linkedMapOf<String, String>()
    for (property in root.directEventChildren().filter { xmlLocalName(it) == "property" }) {
        for (variable in property.directEventChildren()) {
            val name = xmlLocalName(variable)
            val value = if (variable.hasAttribute("val")) variable.getAttribute("val") else variable.textContent.orEmpty()
            properties[name] = if (name == "CurrentTrackMetaData") value else value.trim()
            if (name == "LastChange" && value.isNotBlank()) {
                val nested = parseDesktopUpnpEventXml(value.toByteArray(Charsets.UTF_8)).documentElement
                    ?: error("Missing AVTransport LastChange root.")
                collectDesktopUpnpLastChange(nested, properties)
            }
        }
    }
    return properties
}

private fun parseDesktopUpnpEventXml(xml: ByteArray): org.w3c.dom.Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val builder = factory.newDocumentBuilder().apply {
        setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = throw exception
            override fun error(exception: SAXParseException) = throw exception
            override fun fatalError(exception: SAXParseException) = throw exception
        })
    }
    return builder.parse(ByteArrayInputStream(xml))
}

private fun collectDesktopUpnpLastChange(element: Element, properties: MutableMap<String, String>) {
    for (child in element.directEventChildren()) {
        val name = xmlLocalName(child)
        if (name == "InstanceID") {
            collectDesktopUpnpLastChange(child, properties)
        } else if (child.hasAttribute("val")) {
            properties[name] = child.getAttribute("val")
        } else if (child.directEventChildren().isEmpty()) {
            properties[name] = child.textContent.orEmpty().trim()
        } else {
            collectDesktopUpnpLastChange(child, properties)
        }
    }
}

private fun Element.directEventChildren(): List<Element> {
    val children = mutableListOf<Element>()
    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val node = nodes.item(index)
        if (node.nodeType == Node.ELEMENT_NODE) children += node as Element
    }
    return children
}

private fun xmlLocalName(node: Element): String =
    node.localName ?: node.nodeName.substringAfter(':')

private fun newDesktopUpnpEventToken(): String = ByteArray(32).let { bytes ->
    eventReceiverSecureRandom.nextBytes(bytes)
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private val eventReceiverSecureRandom = SecureRandom()

private fun isDesktopUpnpEventSid(value: String): Boolean = SID_PATTERN.matches(value)

private val SID_PATTERN = Regex("""uuid:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

private fun parseDesktopUpnpEventSequence(value: String): Long? =
    value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.toLongOrNull()
        ?.takeIf { it in 0L..MAX_EVENT_SEQUENCE }

/** Accepts forward sequence gaps so one dropped NOTIFY does not disable the rest of the SID. */
internal fun isNewerDesktopUpnpEventSequence(previous: Long, current: Long): Boolean {
    if (previous !in 0L..MAX_EVENT_SEQUENCE || current !in 0L..MAX_EVENT_SEQUENCE) return false
    val distance = if (current > previous) {
        current - previous
    } else {
        MAX_EVENT_SEQUENCE - previous + current
    }
    return distance in 1L..(MAX_EVENT_SEQUENCE / 2L)
}

private fun isDesktopUpnpEventLocalUnicast(address: InetAddress): Boolean =
    !address.isAnyLocalAddress && !address.isMulticastAddress &&
        (address.isLoopbackAddress || runCatching { NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false))

private class EventThreadFactory : ThreadFactory {
    private val nextId = java.util.concurrent.atomic.AtomicInteger()
    override fun newThread(task: Runnable): Thread = Thread(task, "upnp-event-${nextId.incrementAndGet()}").apply {
        isDaemon = true
    }
}

private const val GENA_EVENT_NAMESPACE = "urn:schemas-upnp-org:event-1-0"
private const val MAX_EVENT_SEQUENCE = 4_294_967_295L
